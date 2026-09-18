package it.danielebufarini.trenify.app

import it.danielebufarini.trenify.core.domain.StrikeChangeEvent
import it.danielebufarini.trenify.core.domain.StrikeChangeKind
import it.danielebufarini.trenify.core.domain.StrikePolicy
import it.danielebufarini.trenify.core.platform.ApplicationState
import it.danielebufarini.trenify.core.platform.ApplicationStateObserver
import it.danielebufarini.trenify.core.platform.BackgroundScheduler
import it.danielebufarini.trenify.core.platform.BackgroundTask
import it.danielebufarini.trenify.core.platform.Connectivity
import it.danielebufarini.trenify.core.platform.ConnectivityStatus
import it.danielebufarini.trenify.core.platform.EffectiveNotificationPermission
import it.danielebufarini.trenify.core.platform.ExternalUrlLauncher
import it.danielebufarini.trenify.core.platform.LifecycleIntegration
import it.danielebufarini.trenify.core.platform.NotificationMessage
import it.danielebufarini.trenify.core.platform.NotificationPermission
import it.danielebufarini.trenify.core.platform.NotificationPresenter
import it.danielebufarini.trenify.core.platform.PlatformServices
import it.danielebufarini.trenify.core.testing.FakeNotificationSettingsRepository
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testStrike
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T7.7 strike delivery precedence: installation switch x strike opt-in x
 * effective OS permission, read fresh at dispatch time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StrikeCoordinatorNotificationGatingTest {
    private class Permission(var effective: EffectiveNotificationPermission) : NotificationPermission {
        override suspend fun isGranted(): Boolean = effective == EffectiveNotificationPermission.GRANTED
        override suspend fun request(): Boolean = isGranted()
        override suspend fun effective(): EffectiveNotificationPermission = effective
    }

    @Test fun deliversOnlyWhenGlobalOptInAndPermissionAllow() = runTest {
        val permission = Permission(EffectiveNotificationPermission.GRANTED)
        val repository = FakeStrikeRepository().apply {
            notificationsEnabled.value = true
            pending += StrikeChangeEvent(testStrike, StrikeChangeKind.SCHEDULED)
        }
        val settings = FakeNotificationSettingsRepository()
        val notifications = mutableListOf<NotificationMessage>()
        val applicationState = MutableStateFlow(ApplicationState.Background)
        val connectivity = MutableStateFlow(ConnectivityStatus.Available)
        val services = PlatformServices(
            Connectivity { connectivity },
            ApplicationStateObserver { applicationState },
            NotificationPresenter(notifications::add),
            permission,
            object : BackgroundScheduler {
                override fun register(taskId: String, handler: suspend () -> Unit) = Unit
                override suspend fun schedule(task: BackgroundTask) = Unit
                override suspend fun cancel(taskId: String) = Unit
            },
            ExternalUrlLauncher { false },
            LifecycleIntegration { applicationState.value = it },
        )
        val coordinator = StrikeCoordinator(
            repository, repository, services, settings, backgroundScope, MutableClock(),
            localizer = FakeNotificationLocalizer(),
        )
        runCurrent()

        coordinator.refreshOnce()
        runCurrent()
        assertEquals(1, notifications.size)

        // Each gate suppresses delivery independently while refresh still runs.
        repository.pending += StrikeChangeEvent(testStrike, StrikeChangeKind.MODIFIED)
        settings.setNotificationsEnabled(false)
        coordinator.refreshOnce()
        runCurrent()
        assertEquals(1, notifications.size)
        settings.setNotificationsEnabled(true)

        repository.pending += StrikeChangeEvent(testStrike, StrikeChangeKind.MODIFIED)
        repository.notificationsEnabled.value = false
        coordinator.refreshOnce()
        runCurrent()
        assertEquals(1, notifications.size)
        repository.notificationsEnabled.value = true

        repository.pending += StrikeChangeEvent(testStrike, StrikeChangeKind.MODIFIED)
        permission.effective = EffectiveNotificationPermission.DENIED
        coordinator.refreshOnce()
        runCurrent()
        assertEquals(1, notifications.size)
        permission.effective = EffectiveNotificationPermission.GRANTED

        // Refresh itself is never gated: the strike list still updates with
        // every gate combination above.
        assertEquals(4, repository.refreshes)
    }

    @Test fun strikeListRefreshIsIndependentOfDeliveryGates() = runTest {
        val repository = FakeStrikeRepository()
        val settings = FakeNotificationSettingsRepository().apply { setNotificationsEnabled(false) }
        val applicationState = MutableStateFlow(ApplicationState.Background)
        val connectivity = MutableStateFlow(ConnectivityStatus.Available)
        val services = PlatformServices(
            Connectivity { connectivity },
            ApplicationStateObserver { applicationState },
            NotificationPresenter {},
            Permission(EffectiveNotificationPermission.DENIED),
            object : BackgroundScheduler {
                override fun register(taskId: String, handler: suspend () -> Unit) = Unit
                override suspend fun schedule(task: BackgroundTask) = Unit
                override suspend fun cancel(taskId: String) = Unit
            },
            ExternalUrlLauncher { false },
            LifecycleIntegration { applicationState.value = it },
        )
        val coordinator = StrikeCoordinator(
            repository,
            repository,
            services,
            settings,
            backgroundScope,
            MutableClock(),
            StrikePolicy(),
            localizer = FakeNotificationLocalizer(),
        )
        runCurrent()
        coordinator.refreshOnce()
        runCurrent()
        assertEquals(1, repository.refreshes)
    }
}
