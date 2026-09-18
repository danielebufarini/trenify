package it.danielebufarini.trenify.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import it.danielebufarini.trenify.core.domain.MonitorThresholds
import it.danielebufarini.trenify.core.domain.NotificationSettings
import it.danielebufarini.trenify.core.domain.NotificationSettingsRepository
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Installation-wide notification preferences (T7.7) stored in the shared
 * `app_metadata` key/value table, so no schema migration is required.
 *
 * Absent keys read as the documented defaults (notifications on, 15-minute
 * threshold, every supported event flag on); corrupt values degrade to the
 * same defaults instead of breaking observation. There is exactly one
 * persisted installation preference set, and strike opt-in stays solely in
 * [SqlDelightStrikeRepository] behind `StrikeNotificationRepository`.
 */
class SqlDelightNotificationSettingsRepository(
    private val database: TrenifyDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : NotificationSettingsRepository {
    private val queries = database.appMetadataQueries

    override fun observe(): Flow<NotificationSettings> =
        queries.selectAll().asFlow().mapToList(dispatcher).map { rows ->
            val values = rows.associate { it.key to it.value_ }
            NotificationSettings(
                notificationsEnabled = values[KEY_ENABLED]?.toBooleanStrictOrNull() ?: true,
                defaultThresholds = MonitorThresholds(
                    delayMinutes = values[KEY_DELAY_MINUTES]?.toIntOrNull()?.takeIf { it > 0 }
                        ?: DEFAULT_DELAY_MINUTES,
                    notifyDelay = values[KEY_NOTIFY_DELAY]?.toBooleanStrictOrNull() ?: true,
                    notifyPlatform = values[KEY_NOTIFY_PLATFORM]?.toBooleanStrictOrNull() ?: true,
                    notifyCancellation = values[KEY_NOTIFY_CANCELLATION]?.toBooleanStrictOrNull() ?: true,
                    notifyDeparture = values[KEY_NOTIFY_DEPARTURE]?.toBooleanStrictOrNull() ?: true,
                    notifyArrival = values[KEY_NOTIFY_ARRIVAL]?.toBooleanStrictOrNull() ?: true,
                ),
            )
        }.distinctUntilChanged()

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        queries.insertOrReplace(KEY_ENABLED, enabled.key)
    }

    override suspend fun setDefaultThresholds(thresholds: MonitorThresholds) {
        val delayMinutes = thresholds.delayMinutes
        require(delayMinutes == null || delayMinutes > 0)
        database.transaction {
            queries.insertOrReplace(KEY_DELAY_MINUTES, (delayMinutes ?: DEFAULT_DELAY_MINUTES).toString())
            queries.insertOrReplace(KEY_NOTIFY_DELAY, thresholds.notifyDelay.key)
            queries.insertOrReplace(KEY_NOTIFY_PLATFORM, thresholds.notifyPlatform.key)
            queries.insertOrReplace(KEY_NOTIFY_CANCELLATION, thresholds.notifyCancellation.key)
            queries.insertOrReplace(KEY_NOTIFY_DEPARTURE, thresholds.notifyDeparture.key)
            queries.insertOrReplace(KEY_NOTIFY_ARRIVAL, thresholds.notifyArrival.key)
        }
    }

    private companion object {
        const val DEFAULT_DELAY_MINUTES = 15
        const val KEY_ENABLED = "notifications.enabled"
        const val KEY_DELAY_MINUTES = "notifications.default.delay_minutes"
        const val KEY_NOTIFY_DELAY = "notifications.default.notify_delay"
        const val KEY_NOTIFY_PLATFORM = "notifications.default.notify_platform"
        const val KEY_NOTIFY_CANCELLATION = "notifications.default.notify_cancellation"
        const val KEY_NOTIFY_DEPARTURE = "notifications.default.notify_departure"
        const val KEY_NOTIFY_ARRIVAL = "notifications.default.notify_arrival"
    }
}

private val Boolean.key: String get() = if (this) "true" else "false"
