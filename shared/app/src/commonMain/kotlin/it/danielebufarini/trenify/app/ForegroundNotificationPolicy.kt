package it.danielebufarini.trenify.app

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

import it.danielebufarini.trenify.core.domain.NotificationSettingsRepository
import it.danielebufarini.trenify.core.domain.StrikeNotificationRepository
import it.danielebufarini.trenify.core.platform.EffectiveNotificationPermission
import it.danielebufarini.trenify.core.platform.NotificationDestination
import it.danielebufarini.trenify.core.platform.NotificationPermission
import kotlinx.coroutines.flow.first

/**
 * Foreground local-notification presentation eligibility (T7.13-G).
 *
 * The host decides platform presentation mechanics, but event/business
 * eligibility stays shared and follows the saved effective preferences —
 * presentation is never unconditional. The installation switch gates every
 * destination; strike destinations additionally require the strike opt-in.
 * Monitor destinations need no per-tap monitor check: dispatch already
 * claimed the event under the persisted preference state.
 *
 * Pure shared logic over injected repositories: unit-tested with fakes on
 * JVM and iOS, and used by both the iOS foreground delegate and any
 * Android foreground path that needs it.
 */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
class ForegroundNotificationPolicy(
    private val notificationSettings: NotificationSettingsRepository,
    private val strikeNotifications: StrikeNotificationRepository?,
    private val permission: NotificationPermission,
) {
    suspend fun shouldPresent(destination: NotificationDestination?): Boolean {
        if (permission.effective() != EffectiveNotificationPermission.GRANTED) return false
        if (!notificationSettings.observe().first().notificationsEnabled) return false
        if (destination is NotificationDestination.Strike) {
            if (strikeNotifications?.observeNotificationsEnabled()?.first() != true) return false
        }
        return true
    }
}
