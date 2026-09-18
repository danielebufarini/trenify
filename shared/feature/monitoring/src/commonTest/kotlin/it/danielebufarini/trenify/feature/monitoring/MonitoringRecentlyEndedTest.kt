package it.danielebufarini.trenify.feature.monitoring

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import it.danielebufarini.trenify.core.domain.MonitorThresholds
import it.danielebufarini.trenify.core.domain.MonitoredTrainSnapshot
import it.danielebufarini.trenify.core.domain.MonitoringRepository
import it.danielebufarini.trenify.core.domain.ObserveActiveMonitors
import it.danielebufarini.trenify.core.domain.ObserveEndedMonitors
import it.danielebufarini.trenify.core.domain.RemoveEndedMonitor
import it.danielebufarini.trenify.core.domain.StopTrainMonitoring
import it.danielebufarini.trenify.core.domain.TrainMonitorEvent
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.testing.FakeMonitoringRepository
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testRun
import it.danielebufarini.trenify.core.testing.testRunId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * T7.11 Recently ended at the component level: Active versus Recently ended
 * separation, manual Stop versus terminal retention, early removal and
 * retention expiry — without any polling from the UI layer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitoringRecentlyEndedTest {
    private fun kotlinx.coroutines.test.TestScope.component(
        repository: MonitoringRepository,
        onTrain: (it.danielebufarini.trenify.core.model.TrainRunId) -> Unit = {},
    ): DefaultMonitoringTabComponent {
        val component = DefaultMonitoringTabComponent(
            DefaultComponentContext(LifecycleRegistry()),
            ObserveActiveMonitors(repository),
            StopTrainMonitoring(repository),
            onTrain,
            StandardTestDispatcher(testScheduler),
            observeEndedMonitors = ObserveEndedMonitors(repository),
            removeEndedMonitor = RemoveEndedMonitor(repository),
        )
        return component
    }

    private suspend fun endMonitor(repository: FakeMonitoringRepository, clock: MutableClock) {
        val created = assertNotNull(repository.observeMonitor(testRunId).first())
        val arrived = testRun.copy(summary = testRun.summary.copy(status = TrainStatus.ARRIVED))
        repository.completeTerminally(
            created.id,
            MonitoredTrainSnapshot(arrived, DataFreshness.Unknown, clock.now()),
            listOf(TrainMonitorEvent.Arrived(testRunId)),
            clock.now(), 2L)
    }

    @Test fun activeAndEndedAreExposedSeparately() = runTest {
        val clock = MutableClock()
        val repository = FakeMonitoringRepository(clock)
        val other = testRunId.copy(number = TrainNumber("456"))
        repository.createMonitor(testRunId, expiresAt = clock.now() + 1.hours)
        repository.createMonitor(other, expiresAt = clock.now() + 1.hours)
        endMonitor(repository, clock)
        val component = component(repository)
        runCurrent()

        assertEquals(listOf(other), component.state.value.active.map { it.monitor.trainRunId })
        val ended = component.state.value.ended.single()
        assertEquals(testRunId, ended.monitor.trainRunId)
        assertNotNull(ended.monitor.endedAt)
        assertEquals(TrainStatus.ARRIVED, ended.monitor.lastSnapshot?.train?.summary?.status)
    }

    @Test fun stopRemovesActiveImmediatelyWithoutRetention() = runTest {
        val clock = MutableClock()
        val repository = FakeMonitoringRepository(clock)
        repository.createMonitor(testRunId, expiresAt = clock.now() + 1.hours)
        val component = component(repository)
        runCurrent()
        assertEquals(1, component.state.value.active.size)

        component.stop(testRunId)
        runCurrent()

        // Immediate removal with no Recently ended retention.
        assertTrue(component.state.value.active.isEmpty())
        assertTrue(component.state.value.ended.isEmpty())
        assertNull(repository.observeMonitor(testRunId).first())
    }

    @Test fun removeEndedDeletesPermanentlyAndIsIdempotent() = runTest {
        val clock = MutableClock()
        val repository = FakeMonitoringRepository(clock)
        repository.createMonitor(testRunId, expiresAt = clock.now() + 1.hours)
        endMonitor(repository, clock)
        val component = component(repository)
        runCurrent()
        assertEquals(1, component.state.value.ended.size)

        component.removeEnded(testRunId)
        runCurrent()
        assertTrue(component.state.value.ended.isEmpty())
        assertNull(repository.observeMonitor(testRunId).first())

        // Duplicate removal is safe and regenerates no events.
        component.removeEnded(testRunId)
        runCurrent()
        assertEquals(1, repository.persistedEvents.filterIsInstance<TrainMonitorEvent.Arrived>().size)
    }

    @Test fun removeEndedNeverTouchesActiveMonitors() = runTest {
        val clock = MutableClock()
        val repository = FakeMonitoringRepository(clock)
        repository.createMonitor(testRunId, expiresAt = clock.now() + 1.hours)
        val component = component(repository)
        runCurrent()

        component.removeEnded(testRunId)
        runCurrent()
        assertEquals(1, component.state.value.active.size)
        assertNotNull(repository.observeMonitor(testRunId).first())
    }

    @Test fun expiredEndedDisappearsReactivelyAfterCleanup() = runTest {
        val clock = MutableClock()
        val repository = FakeMonitoringRepository(clock)
        repository.createMonitor(testRunId, expiresAt = clock.now() + 48.hours)
        endMonitor(repository, clock)
        val component = component(repository)
        runCurrent()
        assertEquals(1, component.state.value.ended.size)

        clock.instant += 24.hours
        repository.cleanupExpiredEnded(clock.now())
        runCurrent()
        assertTrue(component.state.value.ended.isEmpty())
        assertTrue(component.state.value.active.isEmpty())
    }

    @Test fun endedMonitorSurvivesComponentRecreationWithinRetention() = runTest {
        val clock = MutableClock()
        val repository = FakeMonitoringRepository(clock)
        repository.createMonitor(testRunId, expiresAt = clock.now() + 48.hours)
        endMonitor(repository, clock)
        val lifecycle = LifecycleRegistry()
        val first = DefaultMonitoringTabComponent(
            DefaultComponentContext(lifecycle),
            ObserveActiveMonitors(repository),
            StopTrainMonitoring(repository),
            {},
            StandardTestDispatcher(testScheduler),
            observeEndedMonitors = ObserveEndedMonitors(repository),
            removeEndedMonitor = RemoveEndedMonitor(repository),
        )
        runCurrent()
        assertEquals(1, first.state.value.ended.size)
        lifecycle.destroy()

        clock.instant += 23.hours
        val second = component(repository)
        runCurrent()
        // Still visible just before the deadline with the original endedAt and
        // the persisted final snapshot.
        val ended = second.state.value.ended.single()
        assertEquals(TrainStatus.ARRIVED, ended.monitor.lastSnapshot?.train?.summary?.status)
        assertNotNull(ended.monitor.endedAt)
    }

    @Test fun openEndedDoesNotRestartMonitoringOrAlterState() = runTest {
        val clock = MutableClock()
        val repository = FakeMonitoringRepository(clock)
        repository.createMonitor(testRunId, expiresAt = clock.now() + 1.hours)
        endMonitor(repository, clock)
        var opened = 0
        val component = component(repository) { opened++ }
        runCurrent()

        component.open(testRunId)
        runCurrent()

        // Navigation is a plain callback: the ended monitor keeps its endedAt
        // and final snapshot, stays out of active observation, and no refresh
        // is triggered from the Recently ended UI.
        assertEquals(1, opened)
        val ended = assertNotNull(repository.observeMonitor(testRunId).first())
        assertNotNull(ended.endedAt)
        assertTrue(repository.observeActiveMonitors().first().isEmpty())
        assertEquals(1, repository.observeEndedMonitors().first().size)
    }

    @Test fun cancelledEndedMonitorIsRetainedWithFinalSnapshot() = runTest {
        val clock = MutableClock()
        val repository = FakeMonitoringRepository(clock)
        repository.createMonitor(
            testRunId,
            MonitorThresholds(delayMinutes = 15),
            clock.now() + 1.hours,
        )
        val created = assertNotNull(repository.observeMonitor(testRunId).first())
        val cancelled = testRun.copy(summary = testRun.summary.copy(status = TrainStatus.CANCELLED))
        repository.completeTerminally(
            created.id,
            MonitoredTrainSnapshot(cancelled, DataFreshness.Unknown, clock.now()),
            listOf(TrainMonitorEvent.Cancelled(testRunId)),
            clock.now(), 2L)
        val component = component(repository)
        runCurrent()

        assertTrue(component.state.value.active.isEmpty())
        val ended = component.state.value.ended.single()
        assertEquals(TrainStatus.CANCELLED, ended.monitor.lastSnapshot?.train?.summary?.status)
        assertEquals(TrainMonitorEvent.Cancelled(testRunId), ended.monitor.lastEvent)
    }
}
