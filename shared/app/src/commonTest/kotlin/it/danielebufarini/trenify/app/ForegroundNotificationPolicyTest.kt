package it.danielebufarini.trenify.app

import it.danielebufarini.trenify.core.domain.MonitorThresholds
import it.danielebufarini.trenify.core.domain.NotificationSettings
import it.danielebufarini.trenify.core.platform.EffectiveNotificationPermission
import it.danielebufarini.trenify.core.platform.NotificationDestination
import it.danielebufarini.trenify.core.testing.FakeNotificationPermission
import it.danielebufarini.trenify.core.testing.FakeNotificationSettingsRepository
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Foreground presentation eligibility (T7.13-G): shared business policy,
 * never unconditional, following the saved effective preferences.
 */
class ForegroundNotificationPolicyTest {
    private val train = NotificationDestination.Train("test", "123", "opaque-origin", "2026-09-05")
    private val strike = NotificationDestination.Strike("mit-strikes:8479")

    private fun policy(
        notificationsEnabled: Boolean = true,
        permission: EffectiveNotificationPermission = EffectiveNotificationPermission.GRANTED,
        strikeOptIn: Boolean = true,
    ): Triple<ForegroundNotificationPolicy, FakeNotificationSettingsRepository, FakeStrikeRepository> {
        val settings = FakeNotificationSettingsRepository(
            NotificationSettings(notificationsEnabled = notificationsEnabled),
        )
        val strikes = FakeStrikeRepository()
        strikes.notificationsEnabled.value = strikeOptIn
        return Triple(
            ForegroundNotificationPolicy(settings, strikes, FakeNotificationPermission(permission)),
            settings,
            strikes,
        )
    }

    @Test
    fun eligibleTrainPresents() = runTest {
        assertTrue(policy().first.shouldPresent(train))
    }

    @Test
    fun eligibleStrikePresents() = runTest {
        assertTrue(policy().first.shouldPresent(strike))
    }

    @Test
    fun installationSwitchOffSuppressesEverything() = runTest {
        val (underTest, _, _) = policy(notificationsEnabled = false)
        assertFalse(underTest.shouldPresent(train))
        assertFalse(underTest.shouldPresent(strike))
        assertFalse(underTest.shouldPresent(null))
    }

    @Test
    fun deniedOsPermissionSuppressesEverything() = runTest {
        val (underTest, _, _) = policy(permission = EffectiveNotificationPermission.DENIED)
        assertFalse(underTest.shouldPresent(train))
        assertFalse(underTest.shouldPresent(strike))
    }

    @Test
    fun strikeOptOutSuppressesOnlyStrikes() = runTest {
        val (underTest, _, _) = policy(strikeOptIn = false)
        assertTrue(underTest.shouldPresent(train))
        assertFalse(underTest.shouldPresent(strike))
    }

    @Test
    fun monitorThresholdEditsDoNotAffectEligibility() = runTest {
        val (underTest, settings, _) = policy()
        settings.state.value = settings.state.value.copy(defaultThresholds = MonitorThresholds(delayMinutes = 3))
        assertTrue(underTest.shouldPresent(train))
    }
}
