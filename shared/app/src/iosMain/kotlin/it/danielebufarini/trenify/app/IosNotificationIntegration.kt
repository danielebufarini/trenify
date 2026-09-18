package it.danielebufarini.trenify.app

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

import it.danielebufarini.trenify.core.platform.NotificationDestination
import it.danielebufarini.trenify.core.platform.NotificationEntryHandoff
import it.danielebufarini.trenify.core.platform.NotificationTap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.UserNotifications.UNNotification
import platform.darwin.NSObject
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol

/**
 * Thin iOS notification boundary (T7.13-G), implemented in shared Kotlin so
 * Swift stays a bridge with no repository/provider/domain decisions.
 *
 * - [onResponse] extracts only the minimal payload from a notification
 *   response and forwards the validated destination to the same shared
 *   routing entry Android uses ([AppGraph.deliverNotificationDestination]).
 *   Cold-start responses buffer in the graph handoff when the root is not
 *   ready yet; warm responses route immediately. Malformed payloads return
 *   false and are safely ignored. Duplicate callbacks are idempotent in
 *   the shared routing entry point.
 * - [foregroundOptions] implements the foreground presentation policy: the
 *   shared [ForegroundNotificationPolicy] (saved effective preferences)
 *   decides eligibility, the host only decides mechanics (banner + sound
 *   when eligible, nothing when not — never unconditional).
 *
 * The pure map decoding is unit-tested without UIKit; this adapter only
 * bridges UN* types to it.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalObjCRefinement::class)
@HiddenFromObjC
class IosNotificationTapRouter internal constructor(
    private val deliver: (NotificationDestination) -> Unit,
    private val eligible: suspend (NotificationDestination?) -> Boolean,
) {
    constructor(graph: AppGraph) : this(
        deliver = graph::deliverNotificationDestination,
        eligible = graph::shouldPresentForegroundNotification,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Handles one notification response. Returns true when a validated
     * destination was forwarded to shared routing.
     */
    fun onResponse(userInfo: Map<Any?, *>?): Boolean {
        val destination = userInfo.toDestination() ?: return false
        deliver(destination)
        return true
    }

    /**
     * Resolves foreground presentation options for one notification:
     * banner + sound when the shared policy allows delivery, zero (silent)
     * otherwise. The result is delivered to [present], which forwards it to
     * the system completion handler.
     */
    fun foregroundOptions(userInfo: Map<Any?, *>?, present: (ULong) -> Unit) {
        scope.launch {
            val destination = userInfo.toDestination()
            val allowed = eligible(destination)
            present(
                if (allowed) {
                    UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionSound
                } else {
                    0u
                },
            )
        }
    }

    fun close() {
        scope.cancel()
    }

    private fun Map<Any?, *>?.toDestination(): NotificationDestination? {
        if (this == null) return null
        val fields = entries.mapNotNull { (key, value) ->
            val stringKey = key as? String ?: return@mapNotNull null
            if (!stringKey.startsWith("trenify.dest.")) return@mapNotNull null
            stringKey to (value as? String)
        }.toMap()
        return NotificationTap.decode(fields)
    }
}

/**
 * Minimal seam over the notification-center weak delegate slot (T7.13-G
 * corrective pass). Production uses the real `UNUserNotificationCenter`;
 * tests substitute a weak-holding fake, because the real center requires a
 * bundled application process and crashes in the bundle-less test binary.
 * The seam carries no business decisions — only the delegate reference.
 */
internal interface IosNotificationCenterAccess {
    fun getDelegate(): Any?
    fun setDelegate(delegate: Any?)
}

@OptIn(ExperimentalForeignApi::class)
internal object RealIosNotificationCenterAccess : IosNotificationCenterAccess {
    private fun center() = UNUserNotificationCenter.currentNotificationCenter()
    override fun getDelegate(): Any? = center().delegate
    override fun setDelegate(delegate: Any?) {
        center().delegate = delegate as? UNUserNotificationCenterDelegateProtocol
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class IosNotificationCenterDelegate(
    private val router: IosNotificationTapRouter,
) : NSObject(), UNUserNotificationCenterDelegateProtocol {
    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        didReceiveNotificationResponse: UNNotificationResponse,
        withCompletionHandler: () -> Unit,
    ) {
        handleResponse(
            didReceiveNotificationResponse.notification.request.content.userInfo,
            withCompletionHandler,
        )
    }

    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        willPresentNotification: UNNotification,
        withCompletionHandler: (ULong) -> Unit,
    ) {
        handleForeground(
            willPresentNotification.request.content.userInfo,
            withCompletionHandler,
        )
    }

    /**
     * Plain-Kotlin entry for one response, shared by the system callback
     * above: validates the payload, forwards it to shared routing and always
     * runs [completion]. Returns true when a validated destination was
     * forwarded; malformed payloads are safely ignored.
     */
    fun handleResponse(userInfo: Map<Any?, *>?, completion: () -> Unit): Boolean {
        try {
            return router.onResponse(userInfo)
        } finally {
            completion()
        }
    }

    /**
     * Plain-Kotlin entry for foreground presentation, shared by the system
     * callback above.
     */
    fun handleForeground(userInfo: Map<Any?, *>?, present: (ULong) -> Unit) {
        router.foregroundOptions(userInfo, present)
    }
}

/**
 * Process-lifetime owner of the iOS notification-center delegate (T7.13-G
 * corrective pass).
 *
 * `UNUserNotificationCenter.delegate` is a weak property: the center never
 * owns the delegate, so somebody durable must. This object is that explicit
 * owner — its lifetime is the process, which is at least the notification
 * integration / application-graph lifetime. It strongly retains both the
 * delegate and its router from [install] until [uninstall].
 *
 * This object is a minimal typed/raw notification bridge, not a service
 * locator: it vends no repositories, components or navigation. It only
 * decodes the transport payload (through the shared [NotificationTap]
 * contract — no train/strike business decisions) and forwards validated
 * destinations to the attached graph's shared routing entry, buffering them
 * while no graph is attached.
 *
 * Threading: install/uninstall/attach/detach and the delegate callbacks all
 * run on the iOS main queue; tests run single-threaded. No locking is
 * needed across that discipline.
 *
 * - [install] is idempotent and must be called from the platform launch
 *   boundary (the Swift `UIApplicationDelegate`, before the application
 *   finishes launching — Apple requires the delegate that early, and
 *   native session creation is too late for a cold-start tap).
 * - [attachGraph] (called once the graph exists) enables the warm path
 *   and drains at most one buffered cold
 *   response exactly once into the graph, which routes it on top of the
 *   restored tree. A response arriving with no graph attached is buffered
 *   deterministically (latest tap wins, like [NotificationEntryHandoff]).
 * - [detach] forgets the graph without touching the installed delegate, so
 *   a later graph can attach cleanly.
 * - [uninstall] clears the center delegate only when it is still the
 *   instance this object installed (identity check): it never clears a
 *   delegate owned by another instance. Uninstalling twice is safe.
 */
@OptIn(ExperimentalForeignApi::class)
object IosNotificationLaunch {
    /**
     * Center access seam (see [IosNotificationCenterAccess]): the real
     * center in production, a weak-holding fake in tests. Internal so tests
     * can substitute it; never a service locator.
     */
    internal var centerAccess: IosNotificationCenterAccess = RealIosNotificationCenterAccess
    private var delegate: IosNotificationCenterDelegate? = null
    private var router: IosNotificationTapRouter? = null
    private val pending = NotificationEntryHandoff()
    private var attachedGraph: AppGraph? = null
    private var deliver: ((NotificationDestination) -> Unit)? = null
    private var shouldPresent: (suspend (NotificationDestination?) -> Boolean)? = null

    fun install() {
        if (delegate != null) return
        val owned = IosNotificationTapRouter(
            deliver = { destination ->
                val current = deliver
                if (current == null) pending.offer(destination) else current(destination)
            },
            eligible = { destination -> shouldPresent?.invoke(destination) ?: false },
        )
        val ownedDelegate = IosNotificationCenterDelegate(owned)
        router = owned
        delegate = ownedDelegate
        centerAccess.setDelegate(ownedDelegate)
    }

    fun uninstall() {
        val owned = delegate ?: return
        if (centerAccess.getDelegate() === owned) {
            centerAccess.setDelegate(null)
        }
        delegate = null
        router?.close()
        router = null
    }

    /**
     * Ownership probe for tests: the strongly retained delegate while
     * installed, null otherwise. A strong `var` is strong by language
     * semantics, so a non-null probe across the whole active lifetime
     * proves the owner outlives every caller-side reference (`install`
     * returns nothing and the center holds the delegate weakly per Apple's
     * API contract). The original defect — a delegate temporary owned by
     * nobody — reads null here.
     */
    internal fun retainedDelegateForTest(): Any? = delegate

    /**
     * Attaches the application graph: responses now route immediately
     * through the shared entry, and one buffered cold response — if any —
     * is delivered exactly once.
     */
    @OptIn(ExperimentalObjCRefinement::class)
    @HiddenFromObjC
    fun attachGraph(graph: AppGraph) {
        check(attachedGraph == null || attachedGraph === graph) { "An iOS application session is already attached" }
        attach(
            deliver = graph::deliverNotificationDestination,
            shouldPresent = graph::shouldPresentForegroundNotification,
        )
        attachedGraph = graph
    }

    /**
     * Attaches raw forwarding closures (same contract as [attachGraph],
     * used by tests without spinning an application graph).
     */
    @OptIn(ExperimentalObjCRefinement::class)
    @HiddenFromObjC
    fun attach(
        deliver: (NotificationDestination) -> Unit,
        shouldPresent: suspend (NotificationDestination?) -> Boolean,
    ) {
        attachedGraph = null
        this.deliver = deliver
        this.shouldPresent = shouldPresent
        pending.consume()?.let(deliver)
    }

    internal fun hasAttachedGraph(): Boolean = attachedGraph != null

    internal fun detachGraph(graph: AppGraph) {
        if (attachedGraph === graph) detach()
    }

    @OptIn(ExperimentalObjCRefinement::class)
    @HiddenFromObjC
    fun detach() {
        attachedGraph = null
        deliver = null
        shouldPresent = null
    }

    internal fun pendingForTest(): NotificationDestination? = pending.peek()
}
