package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import it.danielebufarini.trenify.core.domain.ObserveNotificationSettings
import it.danielebufarini.trenify.core.domain.ObserveStrikeNotifications
import it.danielebufarini.trenify.core.domain.SetDefaultMonitorThresholds
import it.danielebufarini.trenify.core.domain.SetNotificationsEnabled
import it.danielebufarini.trenify.core.domain.SetStrikeNotifications
import it.danielebufarini.trenify.core.testing.FakeNotificationPermission
import it.danielebufarini.trenify.core.testing.FakeNotificationSettingsRepository
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.feature.settings.DefaultSettingsComponent
import it.danielebufarini.trenify.feature.strikes.DefaultAlertsTabComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T7.7 strike preference integration: Alerts and Settings observe and mutate
 * the same persisted opt-in, and Settings adds the installation-wide gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStrikeReactivityTest {
    @Test fun alertsAndSettingsShareOneStrikePreference() = runTest {
        val strikes = FakeStrikeRepository()
        val settingsRepository = FakeNotificationSettingsRepository()
        val settingsComponent = DefaultSettingsComponent(
            DefaultComponentContext(LifecycleRegistry()),
            ObserveNotificationSettings(settingsRepository),
            SetNotificationsEnabled(settingsRepository),
            SetDefaultMonitorThresholds(settingsRepository),
            ObserveStrikeNotifications(strikes),
            SetStrikeNotifications(strikes),
            notificationPermission = FakeNotificationPermission(),
            requestNotificationPermission = { true },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val alerts = DefaultAlertsTabComponent(
            DefaultComponentContext(LifecycleRegistry()),
            null,
            ObserveStrikeNotifications(strikes),
            SetStrikeNotifications(strikes),
            requestNotificationPermission = { true },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        assertFalse(settingsComponent.state.value.strikeNotificationsEnabled)
        assertFalse(alerts.state.value.notificationsEnabled)

        // A change from Alerts is observed by Settings through the one repo.
        alerts.toggleNotifications()
        runCurrent()
        assertTrue(strikes.notificationsEnabled.value)
        assertTrue(settingsComponent.state.value.strikeNotificationsEnabled)

        // A change from Settings is observed by Alerts.
        settingsComponent.toggleStrikeNotifications()
        runCurrent()
        assertFalse(strikes.notificationsEnabled.value)
        assertFalse(alerts.state.value.notificationsEnabled)
    }
}
