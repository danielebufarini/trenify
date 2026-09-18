package it.danielebufarini.trenify.app

import it.danielebufarini.trenify.core.domain.StrikeChangeEvent
import it.danielebufarini.trenify.core.domain.StrikeChangeKind
import it.danielebufarini.trenify.core.domain.StrikePolicy
import it.danielebufarini.trenify.core.model.StrikeStatus
import it.danielebufarini.trenify.core.platform.ApplicationState
import it.danielebufarini.trenify.core.platform.ApplicationStateObserver
import it.danielebufarini.trenify.core.platform.BackgroundScheduler
import it.danielebufarini.trenify.core.platform.BackgroundTask
import it.danielebufarini.trenify.core.platform.Connectivity
import it.danielebufarini.trenify.core.platform.ConnectivityStatus
import it.danielebufarini.trenify.core.platform.ExternalUrlLauncher
import it.danielebufarini.trenify.core.platform.LifecycleIntegration
import it.danielebufarini.trenify.core.platform.NotificationMessage
import it.danielebufarini.trenify.core.platform.NotificationPermission
import it.danielebufarini.trenify.core.platform.NotificationPresenter
import it.danielebufarini.trenify.core.platform.PlatformServices
import it.danielebufarini.trenify.core.testing.FakeNotificationSettingsRepository
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.TickingClock
import it.danielebufarini.trenify.core.testing.testStrike
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class StrikeCoordinatorTest {
    @Test
    fun triggerRulesDeduplicateAndTrackModifiedOrRevokedStrikes() = runTest {
        val clock = MutableClock()
        val repository = FakeStrikeRepository().apply {
            notificationsEnabled.value = true
            pending += StrikeChangeEvent(testStrike, StrikeChangeKind.SCHEDULED)
        }
        val applicationState = MutableStateFlow(ApplicationState.Foreground)
        val connectivity = MutableStateFlow(ConnectivityStatus.Available)
        val notifications = mutableListOf<NotificationMessage>()
        val scheduler = RecordingStrikeScheduler()
        val services = PlatformServices(
            Connectivity { connectivity },
            ApplicationStateObserver { applicationState },
            NotificationPresenter(notifications::add),
            GrantedStrikePermission,
            scheduler,
            ExternalUrlLauncher { false },
            LifecycleIntegration { applicationState.value = it },
        )
        StrikeCoordinator(
            repository,
            repository,
            services,
            FakeNotificationSettingsRepository(),
            backgroundScope,
            clock,
            StrikePolicy(foregroundRefreshInterval = 1.seconds, backgroundRefreshInterval = 1.seconds),
            localizer = FakeNotificationLocalizer(),
        )
        runCurrent()
        assertEquals(1, repository.refreshes)
        assertEquals(1, notifications.size)

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, repository.refreshes)
        assertEquals(1, notifications.size)

        val revoked = StrikeChangeEvent(testStrike.copy(status = StrikeStatus.REVOKED), StrikeChangeKind.REVOKED)
        repository.pending += revoked
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, notifications.size)
        assertEquals("[L] strike REVOKED", notifications.last().title)

        applicationState.value = ApplicationState.Background
        runCurrent()
        assertTrue(scheduler.scheduled.any { it.id == StrikeCoordinator.BACKGROUND_TASK_ID })
    }

    @Test
    fun refreshOnceDerivesWindowFromASingleCapturedNow() = runTest {
        val clock = TickingClock()
        val policy = StrikePolicy()
        val repository = FakeStrikeRepository()
        val applicationState = MutableStateFlow(ApplicationState.Foreground)
        val connectivity = MutableStateFlow(ConnectivityStatus.Available)
        val services = PlatformServices(
            Connectivity { connectivity },
            ApplicationStateObserver { applicationState },
            NotificationPresenter {},
            GrantedStrikePermission,
            RecordingStrikeScheduler(),
            ExternalUrlLauncher { false },
            LifecycleIntegration { applicationState.value = it },
        )
        val coordinator = StrikeCoordinator(
            repository,
            repository,
            services,
            FakeNotificationSettingsRepository(),
            backgroundScope,
            clock,
            policy,
            localizer = FakeNotificationLocalizer(),
        )
        runCurrent()
        // Notifications stay disabled, so the init loop performs no refresh.
        assertEquals(0, repository.refreshes)
        coordinator.refreshOnce()
        runCurrent()
        // One captured instant derives the window: with two independent
        // now() reads a ticking clock would stretch it past the policy span.
        val (from, to) = repository.refreshWindows.single()
        assertEquals(policy.historyWindow + policy.futureWindow, to - from)
    }
}

private object GrantedStrikePermission : NotificationPermission {
    override suspend fun isGranted() = true
    override suspend fun request() = true
}

private class RecordingStrikeScheduler : BackgroundScheduler {
    val scheduled = mutableListOf<BackgroundTask>()
    override fun register(taskId: String, handler: suspend () -> Unit) = Unit
    override suspend fun schedule(task: BackgroundTask) {
        scheduled += task
    }
    override suspend fun cancel(taskId: String) = Unit
}
