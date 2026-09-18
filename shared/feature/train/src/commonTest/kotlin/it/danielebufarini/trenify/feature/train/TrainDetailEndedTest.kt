package it.danielebufarini.trenify.feature.train

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.MonitorEventKind
import it.danielebufarini.trenify.core.domain.MonitorThresholds
import it.danielebufarini.trenify.core.domain.MonitoredTrainSnapshot
import it.danielebufarini.trenify.core.domain.ObserveTrainMonitor
import it.danielebufarini.trenify.core.domain.ObserveTrainRun
import it.danielebufarini.trenify.core.domain.RemoveEndedMonitor
import it.danielebufarini.trenify.core.domain.StartTrainMonitoring
import it.danielebufarini.trenify.core.domain.StopTrainMonitoring
import it.danielebufarini.trenify.core.domain.TrainMonitorEvent
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.testing.FakeMonitoringRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testRun
import it.danielebufarini.trenify.core.testing.testRunId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * T7.11 corrective: a train detail opened for a retained ended monitor is
 * lifecycle-aware and read-only. The persisted monitor endedAt is
 * authoritative (never the possibly stale train cache): no automatic provider
 * access happens, no Stop/preferences controls are active, and only the
 * Recently ended manual removal applies.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainDetailEndedTest {
    private fun kotlinx.coroutines.test.TestScope.detail(
        trains: FakeRealtimeRepositories,
        monitoring: FakeMonitoringRepository,
        lifecycle: LifecycleRegistry = LifecycleRegistry(),
    ): TrainDetailComponent = TrainDetailComponent(
        DefaultComponentContext(lifecycle),
        testRunId,
        ObserveTrainRun(trains),
        MutableStateFlow(true),
        StandardTestDispatcher(testScheduler),
        observeMonitor = ObserveTrainMonitor(monitoring),
        startMonitoring = StartTrainMonitoring(monitoring),
        stopMonitoring = StopTrainMonitoring(monitoring),
        removeEndedMonitor = RemoveEndedMonitor(monitoring),
    )

    private suspend fun endMonitor(monitoring: FakeMonitoringRepository, clock: MutableClock) {
        monitoring.createMonitor(testRunId, MonitorThresholds(delayMinutes = 15), clock.now() + 1.hours)
        val created = assertNotNull(monitoring.observeMonitor(testRunId).first())
        val arrived = testRun.copy(summary = testRun.summary.copy(status = TrainStatus.ARRIVED))
        monitoring.completeTerminally(
            created.id,
            MonitoredTrainSnapshot(arrived, DataFreshness.Unknown, clock.now()),
            listOf(TrainMonitorEvent.Arrived(testRunId)),
            clock.now(), 2L)
    }

    @Test fun endedDetailIssuesZeroAutomaticProviderCallsDespiteStaleCache() = runTest {
        val clock = MutableClock()
        val trains = FakeRealtimeRepositories()
        val monitoring = FakeMonitoringRepository(clock)
        endMonitor(monitoring, clock)
        // The shared train cache still looks non-terminal (stale RUNNING).
        trains.trainState.value = DataResult.Data(testRun, DataFreshness.Unknown)

        val lifecycle = LifecycleRegistry()
        val component = detail(trains, monitoring, lifecycle)
        try {
            runCurrent()
            assertTrue(component.state.value.isEnded)
            assertFalse(component.state.value.isMonitored)
            assertNotNull(component.state.value.monitor)

            lifecycle.resume()
            runCurrent()
            advanceTimeBy(30.seconds)
            runCurrent()
            // The visibility-triggered refresh resolved the persisted ended
            // lifecycle first: zero provider calls, no polling loop.
            assertEquals(0, trains.trainRefreshes)
            assertTrue(component.state.value.isEnded)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun endedDetailActionsAreReadOnlyExceptRemove() = runTest {
        val clock = MutableClock()
        val trains = FakeRealtimeRepositories()
        val monitoring = FakeMonitoringRepository(clock)
        endMonitor(monitoring, clock)

        val lifecycle = LifecycleRegistry()
        val component = detail(trains, monitoring, lifecycle)
        try {
            runCurrent()
            lifecycle.resume()
            runCurrent()

            // Manual refresh, Stop/Start toggle and preference edits are
            // no-ops for an ended monitor.
            component.refresh()
            component.toggleMonitoring()
            component.setMonitorNotificationsEnabled(false)
            component.saveMonitorThreshold()
            component.setMonitorEventFlag(MonitorEventKind.DELAY, false)
            runCurrent()
            assertEquals(0, trains.trainRefreshes)
            val ended = assertNotNull(monitoring.observeMonitor(testRunId).first())
            assertNotNull(ended.endedAt)
            assertTrue(ended.notificationsEnabled)

            // The Recently ended manual removal applies and is permanent.
            component.removeEndedMonitor()
            runCurrent()
            assertNull(monitoring.observeMonitor(testRunId).first())
            component.removeEndedMonitor()
            runCurrent()
            assertNull(monitoring.observeMonitor(testRunId).first())
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun activeDetailKeepsAutomaticRefreshAndControls() = runTest {
        val clock = MutableClock()
        val trains = FakeRealtimeRepositories()
        val monitoring = FakeMonitoringRepository(clock)
        monitoring.createMonitor(testRunId, MonitorThresholds(delayMinutes = 15), clock.now() + 1.hours)

        val lifecycle = LifecycleRegistry()
        val component = detail(trains, monitoring, lifecycle)
        try {
            runCurrent()
            assertFalse(component.state.value.isEnded)
            assertTrue(component.state.value.isMonitored)

            lifecycle.resume()
            runCurrent()
            // Active monitors still poll automatically through detail.
            assertTrue(trains.trainRefreshes > 0)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun removeIsNoOpWithoutEndedLifecycle() = runTest {
        val clock = MutableClock()
        val trains = FakeRealtimeRepositories()
        val monitoring = FakeMonitoringRepository(clock)
        monitoring.createMonitor(testRunId, MonitorThresholds(delayMinutes = 15), clock.now() + 1.hours)

        val lifecycle = LifecycleRegistry()
        val component = detail(trains, monitoring, lifecycle)
        try {
            runCurrent()
            component.removeEndedMonitor()
            runCurrent()
            // Active monitor untouched by the ended-only removal.
            assertNotNull(monitoring.observeMonitor(testRunId).first())
            assertEquals(1, monitoring.observeActiveMonitors().first().size)
        } finally {
            lifecycle.destroy()
        }
    }
}
