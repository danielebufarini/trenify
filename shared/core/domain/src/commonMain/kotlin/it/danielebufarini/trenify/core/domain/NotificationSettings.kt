package it.danielebufarini.trenify.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * Installation-wide local notification and monitoring preferences (T7.7).
 *
 * These are defaults for newly created monitors and delivery gates for every
 * local notification kind. Changing them never rewrites existing monitors:
 * editing a specific monitor is an explicit per-monitor operation through
 * [MonitoringRepository.updateMonitorPreferences] and
 * [MonitoringRepository.setMonitorNotificationsEnabled].
 *
 * The installation switch controls notification delivery only. It never stops
 * polling, disables or removes a monitor, deletes snapshots, or resets event
 * history.
 */
data class NotificationSettings(
    val notificationsEnabled: Boolean = true,
    val defaultThresholds: MonitorThresholds = MonitorThresholds(),
)

interface NotificationSettingsRepository {
    fun observe(): Flow<NotificationSettings>
    suspend fun setNotificationsEnabled(enabled: Boolean)
    suspend fun setDefaultThresholds(thresholds: MonitorThresholds)
}

class ObserveNotificationSettings(private val repository: NotificationSettingsRepository) {
    operator fun invoke(): Flow<NotificationSettings> = repository.observe()
}

class SetNotificationsEnabled(private val repository: NotificationSettingsRepository) {
    suspend operator fun invoke(enabled: Boolean) = repository.setNotificationsEnabled(enabled)
}

class SetDefaultMonitorThresholds(private val repository: NotificationSettingsRepository) {
    suspend operator fun invoke(thresholds: MonitorThresholds) = repository.setDefaultThresholds(thresholds)
}

/**
 * Mutes or unmutes train notifications without removing or disabling the
 * monitor. Monitor evaluation and snapshot progression continue while muted;
 * only delivery is gated.
 */
class SetMonitorNotifications(private val repository: MonitoringRepository) {
    suspend operator fun invoke(trainRunId: it.danielebufarini.trenify.core.model.TrainRunId, enabled: Boolean) =
        repository.setMonitorNotificationsEnabled(trainRunId, enabled)
}

/**
 * Edits the notification thresholds/event flags of an existing monitor.
 * Identity, enabled state, creation metadata, expiry, snapshots and event
 * claims are preserved; the monitor is never recreated and no synthetic
 * change events are produced.
 */
class UpdateMonitorThresholds(private val repository: MonitoringRepository) {
    suspend operator fun invoke(
        trainRunId: it.danielebufarini.trenify.core.model.TrainRunId,
        thresholds: MonitorThresholds,
    ) = repository.updateMonitorPreferences(trainRunId, thresholds)
}

/**
 * Shared delivery policy for local notifications (T7.7).
 *
 * A train event may be delivered only when the installation-wide switch, that
 * train's notification switch, the applicable event flag and the effective OS
 * permission all allow it. A strike notification requires the installation
 * switch, the strike opt-in and the effective OS permission.
 *
 * Schedule, status and route-change events have no dedicated flag in the
 * supported delay/platform/cancellation/departure/arrival fields, so they are
 * governed by the installation switch, the per-train switch and OS permission
 * only. Partial-cancellation events follow the cancellation flag.
 */
object NotificationDelivery {
    fun eventFlagEnabled(thresholds: MonitorThresholds, event: TrainMonitorEvent): Boolean = when (event.kind) {
        MonitorEventKind.DELAY -> thresholds.notifyDelay
        MonitorEventKind.CANCELLATION -> thresholds.notifyCancellation
        MonitorEventKind.PARTIAL_CANCELLATION -> thresholds.notifyCancellation
        MonitorEventKind.PLATFORM -> thresholds.notifyPlatform
        MonitorEventKind.DEPARTURE -> thresholds.notifyDeparture
        MonitorEventKind.ARRIVAL -> thresholds.notifyArrival
        MonitorEventKind.SCHEDULE -> true
        MonitorEventKind.STATUS -> true
        // Route changes have no dedicated installation flag in the supported
        // delay/platform/cancellation/departure/arrival fields (T7.7): like
        // schedule/status events they are governed by the installation switch,
        // the per-train switch and OS permission only.
        MonitorEventKind.ROUTE_CHANGED -> true
    }

    fun shouldDeliverTrainEvent(
        settings: NotificationSettings,
        monitor: TrainMonitor,
        event: TrainMonitorEvent,
        permissionGranted: Boolean,
    ): Boolean = settings.notificationsEnabled &&
        monitor.notificationsEnabled &&
        eventFlagEnabled(monitor.thresholds, event) &&
        permissionGranted

    fun shouldDeliverStrikeEvent(
        settings: NotificationSettings,
        strikeOptInEnabled: Boolean,
        permissionGranted: Boolean,
    ): Boolean = settings.notificationsEnabled && strikeOptInEnabled && permissionGranted
}
