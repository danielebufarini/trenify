package it.danielebufarini.trenify.app

import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.MonitorThresholds
import it.danielebufarini.trenify.core.domain.MonitoringPolicy
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.TrainStatus
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
import it.danielebufarini.trenify.core.testing.FakeMonitoringRepository
import it.danielebufarini.trenify.core.testing.FakeNotificationSettingsRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testRun
import it.danielebufarini.trenify.core.testing.testRunId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * T7.11 corrective pass 2: a one-shot background graph closes right after
 * [MonitoringCoordinator.performBackgroundRefresh] returns, so the refresh
 * must await the terminal delivery attempts its own refresh created —
 * otherwise closing destroys a pending delivery it just committed.
 * Cancelling the background job cancels the pending delivery instead of
 * leaking it. The foreground path stays decoupled (it never awaits).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundDeliveryLifetimeTest {
    private class Harness(scope: kotlinx.coroutines.CoroutineScope) {
        val clock = MutableClock()
        val monitoring = FakeMonitoringRepository(clock)
        val trains = FakeRealtimeRepositories()
        val settings = FakeNotificationSettingsRepository()
        var showCalls = 0
        var showCompleted = 0
        var showGate: CompletableDeferred<Unit>? = null
        val shown = mutableListOf<NotificationMessage>()
        val appState = MutableStateFlow(ApplicationState.Background)
        val connectivity = MutableStateFlow(ConnectivityStatus.Available)
        val services = PlatformServices(
            Connectivity { connectivity },
            ApplicationStateObserver { appState },
            NotificationPresenter { message: NotificationMessage ->
                showCalls++
                showGate?.await()
                showCompleted++
                shown += message
            },
            GrantedDeliveryPermission,
            RecordingDeliveryScheduler(),
            ExternalUrlLauncher { false },
            LifecycleIntegration { appState.value = it },
        )
        val coordinator = MonitoringCoordinator(
            monitoring,
            trains,
            services,
            settings,
            scope,
            clock,
            MonitoringPolicy(),
            localizer = FakeNotificationLocalizer(),
        )

        suspend fun seedActive() {
            monitoring.createMonitor(testRunId, MonitorThresholds(delayMinutes = 15), clock.now() + 1.hours)
            coordinator.refreshOnce()
        }

        fun arrived() = testRun.copy(summary = testRun.summary.copy(status = TrainStatus.ARRIVED))
    }

    private suspend fun kotlinx.coroutines.test.TestScope.harness() =
        Harness(scope = backgroundScope).also { runCurrent() }

    @Test fun backgroundRefreshAwaitsTerminalDeliveryBeforeReturning() = runTest {
        val harness = harness()
        harness.seedActive()
        runCurrent()
        assertTrue(harness.shown.isEmpty())

        // The terminal refresh parks inside the notification attempt.
        harness.showGate = CompletableDeferred()
        harness.trains.trainState.value = DataResult.Data(harness.arrived(), DataFreshness.Unknown)
        val background = async { harness.coordinator.performBackgroundRefresh() }
        runCurrent()
        runCurrent()

        // The refresh reached its normal return point but must not complete
        // while its required delivery attempt is still outstanding.
        assertEquals(1, harness.showCalls)
        assertEquals(0, harness.showCompleted)
        assertFalse(background.isCompleted)

        // Once the attempt completes, the background refresh returns and the
        // graph/scope may close safely.
        harness.showGate?.complete(Unit)
        runCurrent()
        background.await()
        runCurrent()
        assertEquals(1, harness.showCompleted)
        assertEquals(1, harness.shown.size)
        assertEquals("[L] arrived", harness.shown.single().body)
        assertTrue(background.isCompleted)
    }

    @Test fun cancellingBackgroundRefreshCancelsPendingDeliveryWithoutLeak() = runTest {
        val harness = harness()
        harness.seedActive()
        runCurrent()

        harness.showGate = CompletableDeferred()
        harness.trains.trainState.value = DataResult.Data(harness.arrived(), DataFreshness.Unknown)
        val background = async { harness.coordinator.performBackgroundRefresh() }
        runCurrent()
        runCurrent()
        assertEquals(1, harness.showCalls)
        assertFalse(background.isCompleted)

        // Cancelling the Android-job equivalent cancels the pending
        // delivery instead of leaking it.
        background.cancel()
        runCurrent()
        assertTrue(background.isCancelled)

        // Releasing the gate afterwards must not complete a leaked show:
        // the cancelled attempt stays cancelled.
        harness.showGate?.complete(Unit)
        runCurrent()
        runCurrent()
        assertEquals(0, harness.showCompleted)
        assertTrue(harness.shown.isEmpty())
    }
}

private object GrantedDeliveryPermission : NotificationPermission {
    override suspend fun isGranted() = true
    override suspend fun request() = true
    override suspend fun effective() = EffectiveNotificationPermission.GRANTED
}

private class RecordingDeliveryScheduler : BackgroundScheduler {
    val scheduled = mutableListOf<BackgroundTask>()
    override fun register(taskId: String, handler: suspend () -> Unit) = Unit
    override suspend fun schedule(task: BackgroundTask) {
        scheduled += task
    }
    override suspend fun cancel(taskId: String) = Unit
}
