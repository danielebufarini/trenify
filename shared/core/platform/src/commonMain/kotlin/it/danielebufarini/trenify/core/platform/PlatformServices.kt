package it.danielebufarini.trenify.core.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ConnectivityStatus {
    Available,
    Unavailable,
    Unknown,
}

fun interface Connectivity {
    fun status(): StateFlow<ConnectivityStatus>
    fun close() = Unit
}

enum class ApplicationState {
    Foreground,
    Background,
    Unknown,
}

fun interface ApplicationStateObserver {
    fun state(): StateFlow<ApplicationState>
}

data class NotificationMessage(
    val id: String,
    val title: String,
    val body: String,
    /**
     * Optional typed routing destination (T7.13-D). Routing metadata only:
     * it never affects [id] (anti-spam/deduplication fingerprint) and the
     * title/body are never parsed for navigation.
     */
    val destination: NotificationDestination? = null,
)

fun interface NotificationPresenter {
    suspend fun show(message: NotificationMessage)
}

interface NotificationPermission {
    suspend fun isGranted(): Boolean
    suspend fun request(): Boolean

    /**
     * Effective delivery state (T7.7). Defaults to the runtime grant for
     * platforms without a richer check; Android and iOS override it with
     * system/channel and authorization state respectively.
     */
    suspend fun effective(): EffectiveNotificationPermission =
        if (isGranted()) EffectiveNotificationPermission.GRANTED else EffectiveNotificationPermission.DENIED
}

data class BackgroundTask(
    val id: String,
    val earliestStartEpochMillis: Long,
)

interface BackgroundScheduler {
    fun register(taskId: String, handler: suspend () -> Unit) = Unit
    suspend fun schedule(task: BackgroundTask)
    suspend fun cancel(taskId: String)
}

fun interface ExternalUrlLauncher {
    suspend fun open(url: String): Boolean
}

fun interface LifecycleIntegration {
    fun setApplicationState(state: ApplicationState)
}

data class PlatformServices(
    val connectivity: Connectivity,
    val applicationState: ApplicationStateObserver,
    val notifications: NotificationPresenter,
    val notificationPermission: NotificationPermission,
    val backgroundScheduler: BackgroundScheduler,
    val externalUrlLauncher: ExternalUrlLauncher,
    val lifecycle: LifecycleIntegration,
) {
    companion object {
        fun defaults(): PlatformServices {
            val applicationState = MutableStateFlow(ApplicationState.Unknown)
            return PlatformServices(
                connectivity = Connectivity {
                    MutableStateFlow(ConnectivityStatus.Unknown)
                },
                applicationState = ApplicationStateObserver { applicationState },
                notifications = NotificationPresenter {},
                notificationPermission = object : NotificationPermission {
                    override suspend fun isGranted(): Boolean = false
                    override suspend fun request(): Boolean = false
                },
                backgroundScheduler = object : BackgroundScheduler {
                    override suspend fun schedule(task: BackgroundTask) = Unit
                    override suspend fun cancel(taskId: String) = Unit
                },
                externalUrlLauncher = ExternalUrlLauncher { false },
                lifecycle = LifecycleIntegration { state -> applicationState.value = state },
            )
        }
    }
}

expect fun createPlatformServices(): PlatformServices
