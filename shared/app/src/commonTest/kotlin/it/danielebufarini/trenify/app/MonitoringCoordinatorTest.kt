package it.danielebufarini.trenify.app

import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.platform.*
import it.danielebufarini.trenify.core.testing.FakeMonitoringRepository
import it.danielebufarini.trenify.core.testing.FakeNotificationSettingsRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testRun
import it.danielebufarini.trenify.core.testing.testRunId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class MonitoringCoordinatorTest {
    @Test fun oneLifecycleAwareCoordinatorRefreshesDiffsAndSchedulesBackgroundWork() = runTest {
        val clock = MutableClock()
        val monitoring = FakeMonitoringRepository(clock)
        monitoring.createMonitor(testRunId, MonitorThresholds(delayMinutes = 15), clock.now() + 1.hours)
        val trains = FakeRealtimeRepositories()
        val appState = MutableStateFlow(ApplicationState.Background)
        val connectivity = MutableStateFlow(ConnectivityStatus.Available)
        val notifications = mutableListOf<NotificationMessage>()
        val scheduler = RecordingBackgroundScheduler()
        val services = PlatformServices(
            Connectivity { connectivity },
            ApplicationStateObserver { appState },
            NotificationPresenter(notifications::add),
            GrantedPermission,
            scheduler,
            ExternalUrlLauncher { false },
            LifecycleIntegration { appState.value = it },
        )
        MonitoringCoordinator(
            monitoring,
            trains,
            services,
            FakeNotificationSettingsRepository(),
            backgroundScope,
            clock,
            MonitoringPolicy(foregroundRefreshInterval = 1.seconds),
            localizer = FakeNotificationLocalizer(),
        )
        runCurrent()
        assertEquals(1, scheduler.scheduled.size)
        assertEquals(0, trains.trainRefreshes)

        appState.value = ApplicationState.Foreground
        runCurrent()
        assertEquals(1, trains.trainRefreshes)
        assertTrue(scheduler.cancelled.isNotEmpty())

        // Establish a known delay baseline first: unknown -> 20 is no
        // truthful transition and must not notify (T7.11 corrective).
        trains.trainState.value = DataResult.Data(
            testRun.copy(summary = testRun.summary.copy(delayMinutes = 5)),
            DataFreshness.Unknown,
        )
        clock.instant += 1.seconds
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, trains.trainRefreshes)
        assertEquals(0, notifications.size)

        trains.trainState.value = DataResult.Data(
            testRun.copy(summary = testRun.summary.copy(delayMinutes = 20)),
            DataFreshness.Unknown,
        )
        clock.instant += 1.seconds
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(3, trains.trainRefreshes)
        assertEquals(1, notifications.size)

        clock.instant += 1.seconds
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(4, trains.trainRefreshes)
        assertEquals(1, notifications.size)

        appState.value = ApplicationState.Background
        runCurrent()
        val refreshes = trains.trainRefreshes
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(refreshes, trains.trainRefreshes)
        assertTrue(scheduler.scheduled.size >= 2)
    }
}

private object GrantedPermission : NotificationPermission {
    override suspend fun isGranted() = true
    override suspend fun request() = true
}

private class RecordingBackgroundScheduler : BackgroundScheduler {
    val scheduled = mutableListOf<BackgroundTask>()
    val cancelled = mutableListOf<String>()
    var handler: (suspend () -> Unit)? = null
    override fun register(taskId: String, handler: suspend () -> Unit) {
        this.handler = handler
    }
    override suspend fun schedule(task: BackgroundTask) {
        scheduled += task
    }
    override suspend fun cancel(taskId: String) {
        cancelled += taskId
    }
}
