package it.danielebufarini.trenify.core.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.Network.*
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume
import platform.UserNotifications.*
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_get_global_queue

private const val SECONDS_FROM_1970_TO_APPLE_REFERENCE_DATE = 978_307_200.0

actual fun createPlatformServices(): PlatformServices = PlatformServices.defaults().copy(
    connectivity = IosConnectivity(),
    notifications = IosNotificationPresenter(),
    notificationPermission = IosNotificationPermission(),
    backgroundScheduler = IosBackgroundScheduler(),
    externalUrlLauncher = IosExternalUrlLauncher(),
)

/**
 * Opens validated booking URLs in Safari via the supported asynchronous
 * `openURL:options:completionHandler:` API. Validation stays in domain policy;
 * the HTTPS gate here is defense in depth at the platform boundary.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosExternalUrlLauncher : ExternalUrlLauncher {
    override suspend fun open(url: String): Boolean {
        val target = NSURL.URLWithString(url) ?: return false
        if (target.scheme?.lowercase() != "https") return false
        return suspendCancellableCoroutine { continuation ->
            dispatch_async(dispatch_get_main_queue()) {
                if (!continuation.isActive) return@dispatch_async
                try {
                    UIApplication.sharedApplication.openURL(target, emptyMap<Any?, Any?>(), { opened ->
                        if (continuation.isActive) continuation.resume(opened)
                    })
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosNotificationPresenter : NotificationPresenter {
    override suspend fun show(message: NotificationMessage) {
        suspendCancellableCoroutine { continuation ->
            val content = UNMutableNotificationContent().apply {
                setTitle(message.title)
                setBody(message.body)
                setSound(UNNotificationSound.defaultSound)
                // Minimal typed destination payload (T7.13-D/G): routing
                // metadata only, decoded by the shared tap contract. Display
                // text is never parsed for navigation.
                message.destination?.let { destination ->
                    @Suppress("UNCHECKED_CAST")
                    val userInfo = NotificationTap.extras(destination) as Map<Any?, Any>
                    setUserInfo(userInfo)
                }
            }
            val request = UNNotificationRequest.requestWithIdentifier(message.id, content, null)
            UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request) {
                continuation.resume(Unit)
            }
        }
    }
}

/**
 * Effective iOS delivery mapping (T7.7), semantically aligned with Android:
 * authorized, provisional and ephemeral grants allow delivery; denied,
 * not-determined or unknown statuses deny it. No notification business policy
 * lives here, only the authorization mapping.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun iosAuthorizationAllowsDelivery(status: UNAuthorizationStatus?): Boolean = status in setOf(
    UNAuthorizationStatusAuthorized,
    UNAuthorizationStatusProvisional,
    UNAuthorizationStatusEphemeral,
)

@OptIn(ExperimentalForeignApi::class)
private class IosNotificationPermission : NotificationPermission {
    override suspend fun isGranted(): Boolean = effective() == EffectiveNotificationPermission.GRANTED

    /**
     * Effective delivery state (T7.7) from the system authorization state,
     * semantically aligned with Android: authorized, provisional and
     * ephemeral grants allow delivery, denied or not-determined denies it.
     * Notification business policy stays shared; this is only a mapping.
     */
    override suspend fun effective(): EffectiveNotificationPermission = suspendCancellableCoroutine { continuation ->
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            val granted = iosAuthorizationAllowsDelivery(settings?.authorizationStatus)
            continuation.resume(
                if (granted) EffectiveNotificationPermission.GRANTED else EffectiveNotificationPermission.DENIED,
            )
        }
    }

    override suspend fun request(): Boolean = suspendCancellableCoroutine { continuation ->
        val options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge
        UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(options) { granted, _ ->
            continuation.resume(granted)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosBackgroundScheduler : BackgroundScheduler {
    private val scheduler = BGTaskScheduler.sharedScheduler
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun register(taskId: String, handler: suspend () -> Unit) {
        scheduler.registerForTaskWithIdentifier(taskId, null) { task ->
            if (task == null) return@registerForTaskWithIdentifier
            val job: Job = scope.launch {
                val succeeded = runCatching { handler() }.isSuccess
                task.setTaskCompletedWithSuccess(succeeded)
            }
            task.expirationHandler = { job.cancel() }
        }
    }

    override suspend fun schedule(task: BackgroundTask) {
        val request = BGAppRefreshTaskRequest(task.id)
        request.earliestBeginDate = NSDate(
            timeIntervalSinceReferenceDate =
                task.earliestStartEpochMillis.toDouble() / 1_000.0 - SECONDS_FROM_1970_TO_APPLE_REFERENCE_DATE,
        )
        scheduler.submitTaskRequest(request, null)
    }

    override suspend fun cancel(taskId: String) {
        scheduler.cancelTaskRequestWithIdentifier(taskId)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosConnectivity : Connectivity {
    private val state = MutableStateFlow(ConnectivityStatus.Unknown)
    private val monitor = nw_path_monitor_create()
    init {
        nw_path_monitor_set_update_handler(monitor) { path ->
            state.value = if (nw_path_get_status(path) == nw_path_status_satisfied)
                ConnectivityStatus.Available else ConnectivityStatus.Unavailable
        }
        nw_path_monitor_set_queue(monitor, dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u))
        nw_path_monitor_start(monitor)
    }
    override fun status(): StateFlow<ConnectivityStatus> = state
    override fun close() { nw_path_monitor_cancel(monitor) }
}
