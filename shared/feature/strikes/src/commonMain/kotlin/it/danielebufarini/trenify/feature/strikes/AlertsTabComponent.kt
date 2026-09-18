package it.danielebufarini.trenify.feature.strikes

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.pushNew
import com.arkivanov.decompose.router.stack.pushToFront
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import it.danielebufarini.trenify.core.domain.CurrentStrikeWindow
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.LoadStrikes
import it.danielebufarini.trenify.core.domain.ObserveStrikeNotifications
import it.danielebufarini.trenify.core.domain.OpenStrikeReference
import it.danielebufarini.trenify.core.domain.SetStrikeNotifications
import it.danielebufarini.trenify.core.domain.StrikeInformationPolicy
import it.danielebufarini.trenify.core.domain.StrikePolicy
import it.danielebufarini.trenify.core.domain.StrikeReferenceResult
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Strike
import it.danielebufarini.trenify.core.model.StrikeId
import it.danielebufarini.trenify.core.model.StrikeStatus
import it.danielebufarini.trenify.core.ui.RealtimeState
import it.danielebufarini.trenify.core.ui.FreshnessWatcher
import it.danielebufarini.trenify.core.ui.agedDisplay
import it.danielebufarini.trenify.core.ui.componentScope
import it.danielebufarini.trenify.core.ui.unlessNotFound
import it.danielebufarini.trenify.core.model.staleTransitionAt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

data class StrikesState(
    val realtime: RealtimeState<List<Strike>> = RealtimeState(loading = true),
    val notificationsEnabled: Boolean = false,
    val notificationPermissionGranted: Boolean? = null,
    val referenceTime: Instant = Clock.System.now(),
    val referenceInProgress: Boolean = false,
    val referenceFailed: Boolean = false,
)

interface AlertsTabComponent {
    val stack: Value<ChildStack<*, Child>>
    val state: Value<StrikesState>
    fun refresh()
    fun open(strikeId: StrikeId)
    /**
     * Shared strike routing (T7.13-E): opens the strike detail through the
     * shared tree even when the strike is not in the currently loaded list
     * (the detail then shows the controlled not-found path). Returns false
     * without stacking a duplicate when the requested detail is already
     * visible, so duplicate OS callbacks stay idempotent while legitimate
     * later navigation still routes.
     *
     * Uses pushToFront semantics: when the target already sits below
     * another destination (A -> B -> A) the existing entry moves to the
     * front instead of duplicating or throwing ([Overview, A, B] + tap A
     * -> [Overview, B, A]; Back -> B -> Overview). An absent target is
     * pushed normally.
     */
    fun openStrikeIfNew(strikeId: StrikeId): Boolean
    fun back()
    fun toggleNotifications()

    /**
     * True only for an actually available official strike/source reference:
     * validated by the dedicated strike/operator-information policy, never
     * the booking allowlist. Malformed/untrusted/unsupported references are
     * not launchable.
     */
    fun canOpenReference(url: String?): Boolean

    /**
     * Opens an official strike/source reference through the injected URL
     * abstraction after dedicated-policy validation. A URL's existence never
     * promotes strike impact.
     */
    fun openReference(url: String?)

    sealed interface Child {
        data object Overview : Child
        data class Detail(val strikeId: StrikeId) : Child
    }
}

class DefaultAlertsTabComponent(
    componentContext: ComponentContext,
    private val loadStrikes: LoadStrikes? = null,
    observeNotifications: ObserveStrikeNotifications? = null,
    private val setNotifications: SetStrikeNotifications? = null,
    private val requestNotificationPermission: suspend () -> Boolean = { false },
    private val clock: Clock = Clock.System,
    private val policy: StrikePolicy = StrikePolicy(),
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val openStrikeReference: OpenStrikeReference? = null,
    private val informationPolicy: StrikeInformationPolicy = StrikeInformationPolicy(),
) : AlertsTabComponent, ComponentContext by componentContext {
    private val navigation = StackNavigation<Configuration>()
    private val scope = componentScope(lifecycle, dispatcher)
    private val freshnessWatcher = FreshnessWatcher(lifecycle, scope, clock)
    private val mutable = MutableValue(StrikesState())
    /**
     * Single source of truth for Alerts' active strike window (Home
     * pattern). It is derived from the clock at construction and at each
     * explicit refresh, and drives both the provider fetch and the
     * database observation, so the two can never diverge: a strike newly
     * entering the future edge on a later refresh is observed because the
     * observation moved with the fetch. No polling is introduced; the
     * window only advances on explicit refresh.
     */
    private val activeWindow: MutableStateFlow<CurrentStrikeWindow> =
        MutableStateFlow(policy.currentWindow(clock))

    override val state: Value<StrikesState> = mutable
    override val stack: Value<ChildStack<*, AlertsTabComponent.Child>> = childStack(
        source = navigation,
        // The selected strike restores as minimal StrikeId criteria; the
        // detail content re-observes the repository and shows the
        // controlled not-found path when the strike is gone.
        serializer = Configuration.serializer(),
        initialConfiguration = Configuration.Overview,
        handleBackButton = true,
        childFactory = { configuration, _ ->
            when (configuration) {
                Configuration.Overview -> AlertsTabComponent.Child.Overview
                is Configuration.Detail -> {
                    // Malformed persisted selections recover to the
                    // overview; a blank id must never crash restoration.
                    val strikeId = runCatching { StrikeId(configuration.strikeId) }.getOrNull()
                    if (strikeId == null) AlertsTabComponent.Child.Overview
                    else AlertsTabComponent.Child.Detail(strikeId)
                }
            }
        },
    )

    init {
        // Elapsed-time aging (T7.14-B): the held strike list becomes Stale
        // at the strike cache TTL with zero provider calls; resume
        // recomputes from the clock.
        freshnessWatcher.observe(
            nextDeadline = { mutable.value.realtime.freshness?.staleTransitionAt(policy.cacheTtl) },
            onTick = {
                val aged = mutable.value.realtime.agedDisplay(clock.now(), policy.cacheTtl)
                if (aged !== mutable.value.realtime) mutable.value = mutable.value.copy(realtime = aged)
            },
        )
        if (loadStrikes == null) {
            mutable.value = StrikesState(RealtimeState(data = emptyList()), referenceTime = clock.now())
        } else {
            observeStrikes()
            refresh()
        }
        observeNotifications?.let { observe ->
            scope.launch {
                observe().collect { enabled -> mutable.value = mutable.value.copy(notificationsEnabled = enabled) }
            }
        }
    }

    /**
     * Long-lived observation following the active window (Home pattern):
     * every explicit refresh re-subscribes observation to the same window
     * the fetch just used. The replay emitted right after a switch
     * predates the fetch that motivated it, so it is dropped — applying it
     * would clobber the refresh status written on completion. Fresh
     * post-switch emissions (the fetch's own database writes and warning
     * updates) flow through normally, as does the initial subscription.
     */
    private fun observeStrikes() {
        val load = loadStrikes ?: return
        scope.launch {
            var primed = false
            activeWindow.flatMapLatest { window ->
                val fresh = load.observe(window, includeRevoked = true)
                if (!primed) {
                    primed = true
                    fresh
                } else {
                    fresh.drop(1)
                }
            }.collect { result ->
                mutable.value = when (result) {
                    is DataResult.Data -> mutable.value.copy(
                        realtime = RealtimeState(
                            data = result.value,
                            loading = mutable.value.realtime.loading,
                            failure = result.warning.unlessNotFound(),
                            stale = result.freshness !is DataFreshness.Fresh,
                            freshness = result.freshness,
                        ),
                        referenceTime = clock.now(),
                    )
                    // A bare NOT_FOUND with no cached strikes is the
                    // never-fetched sentinel: the list renders its empty
                    // state while the refresh resolves.
                    is DataResult.Failure ->
                        if (result.error == DomainFailure.NOT_FOUND && mutable.value.realtime.data == null) {
                            mutable.value.copy(
                                realtime = RealtimeState(loading = mutable.value.realtime.loading),
                            )
                        } else {
                            mutable.value.copy(
                                realtime = mutable.value.realtime.copy(
                                    failure = result.error.unlessNotFound()
                                        .takeUnless { mutable.value.realtime.loading },
                                ),
                            )
                        }
                }
                freshnessWatcher.poke()
            }
        }
    }

    override fun refresh() {
        val load = loadStrikes ?: return
        // One captured instant per logical refresh, shared with Home and the
        // coordinator through the repository single flight, and published as
        // the active observation range before the fetch starts so refresh
        // and observation can never diverge.
        val window = policy.currentWindow(clock)
        activeWindow.value = window
        mutable.value = mutable.value.copy(realtime = mutable.value.realtime.copy(loading = true, failure = null))
        scope.launch {
            val result = load(window, force = true)
            // The fetch result is authoritative for its own window: the
            // observation replay after the switch above may predate the
            // fetch (dropped) or already include it, in any order — and with
            // an immediate provider the fetch's own emission can even arrive
            // before the re-subscription queries, becoming the dropped
            // first item — so the completed refresh publishes both status
            // and data. Later observation emissions refine the same state.
            // (Mirrors Home; with a real repository the published data and
            // the observation agree because both read the same fetch.)
            val data = if (result is DataResult.Data) result.value.strikes else null
            mutable.value = mutable.value.copy(
                realtime = mutable.value.realtime.copy(
                    loading = false,
                    data = data ?: mutable.value.realtime.data,
                    failure = ((result as? DataResult.Failure)?.error ?: (result as? DataResult.Data)?.warning).unlessNotFound(),
                    stale = result is DataResult.Data && result.freshness !is DataFreshness.Fresh,
                    freshness = (result as? DataResult.Data)?.freshness ?: mutable.value.realtime.freshness,
                ),
            )
            // New repository truth restarts freshness boundary tracking.
            freshnessWatcher.poke()
        }
    }

    override fun open(strikeId: StrikeId) {
        if (mutable.value.realtime.data.orEmpty().any { it.id == strikeId }) {
            navigation.pushNew(Configuration.Detail(strikeId.value))
        }
    }

    override fun openStrikeIfNew(strikeId: StrikeId): Boolean {
        val active = stack.value.active.instance
        if (active is AlertsTabComponent.Child.Detail && active.strikeId == strikeId) return false
        navigation.pushToFront(Configuration.Detail(strikeId.value))
        return true
    }

    override fun back() {
        navigation.pop()
    }

    override fun toggleNotifications() {
        val set = setNotifications ?: return
        scope.launch {
            if (mutable.value.notificationsEnabled) {
                set(false)
            } else {
                val granted = requestNotificationPermission()
                mutable.value = mutable.value.copy(notificationPermissionGranted = granted)
                if (granted) set(true)
            }
        }
    }

    override fun canOpenReference(url: String?): Boolean =
        openStrikeReference?.canOpen(url) ?: informationPolicy.officialReferenceUsable(url.orEmpty())

    override fun openReference(url: String?) {
        val open = openStrikeReference ?: return
        if (mutable.value.referenceInProgress) return
        mutable.value = mutable.value.copy(referenceInProgress = true, referenceFailed = false)
        scope.launch {
            val result = open(url)
            currentCoroutineContext().ensureActive()
            mutable.value = mutable.value.copy(
                referenceInProgress = false,
                referenceFailed = result is StrikeReferenceResult.Failed ||
                    result is StrikeReferenceResult.Unavailable,
            )
        }
    }

    /**
     * Durable alerts route contract (T7.13-A): the overview plus the
     * selected strike identity only.
     */
    @Serializable
    private sealed interface Configuration {
        @Serializable
        @SerialName("overview")
        data object Overview : Configuration

        @Serializable
        @SerialName("detail")
        data class Detail(val strikeId: String) : Configuration
    }
}
