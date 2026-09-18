package it.danielebufarini.trenify.feature.monitoring

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.pause
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.domain.MonitorThresholds
import it.danielebufarini.trenify.core.domain.MonitoredTrainSnapshot
import it.danielebufarini.trenify.core.domain.ObserveActiveMonitors
import it.danielebufarini.trenify.core.domain.ObserveEndedMonitors
import it.danielebufarini.trenify.core.domain.TrainMonitorEvent
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.testing.FakeMonitoringRepository
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testRun
import it.danielebufarini.trenify.core.testing.testRunId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Time-driven freshness for monitor cards (T7.14-B): active and
 * recently-ended persisted snapshots age through elapsed local time with
 * zero provider calls, one component-level deadline serves every visible
 * card, resume recomputes after suspension, and destroy cancels scheduling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitorFreshnessTest {
    private fun kotlinx.coroutines.test.TestScope.component(
        lifecycle: LifecycleRegistry,
        repository: FakeMonitoringRepository,
        clock: MutableClock,
    ) = DefaultMonitoringTabComponent(
        DefaultComponentContext(lifecycle),
        ObserveActiveMonitors(repository),
        onTrain = {},
        dispatcher = StandardTestDispatcher(testScheduler),
        observeEndedMonitors = ObserveEndedMonitors(repository),
        policy = it.danielebufarini.trenify.core.domain.RealtimePolicy(),
        clock = clock,
    )

    private suspend fun stageActiveWithSnapshot(repository: FakeMonitoringRepository, clock: MutableClock) {
        val created = repository.createMonitor(testRunId, MonitorThresholds(delayMinutes = 15), clock.now() + 1.hours)
        repository.monitors.value = listOf(
            created.copy(lastSnapshot = MonitoredTrainSnapshot(testRun, DataFreshness.Fresh(clock.now(), null), clock.now())),
        )
    }

    private suspend fun stageEndedWithSnapshot(repository: FakeMonitoringRepository, clock: MutableClock) {
        val other = testRunId.copy(number = TrainNumber("456"))
        val created = repository.createMonitor(other, MonitorThresholds(delayMinutes = 15), clock.now() + 1.hours)
        val arrived = testRun.copy(summary = testRun.summary.copy(status = TrainStatus.ARRIVED))
        repository.completeTerminally(
            created.id,
            MonitoredTrainSnapshot(arrived, DataFreshness.Fresh(clock.now(), null), clock.now()),
            listOf(TrainMonitorEvent.Arrived(other)),
            clock.now(), 2L,
        )
    }

    @Test fun activeAndEndedSnapshotsAgeWithZeroProviderCalls() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val repository = FakeMonitoringRepository(clock)
        stageActiveWithSnapshot(repository, clock)
        stageEndedWithSnapshot(repository, clock)
        val component = component(lifecycle, repository, clock)
        lifecycle.resume()
        try {
            runCurrent()
            assertEquals(1, component.state.value.active.size)
            assertEquals(1, component.state.value.ended.size)
            assertTrue(component.state.value.active.single().displayFreshness is DataFreshness.Fresh)
            assertTrue(component.state.value.ended.single().displayFreshness is DataFreshness.Fresh)
            // One boundary crossing ages the active card (60 s monitor TTL)
            // while the ended ARRIVED snapshot keeps its day-long TTL.
            clock.instant += 61.seconds
            advanceTimeBy(61_000)
            runCurrent()
            assertTrue(component.state.value.active.single().displayFreshness is DataFreshness.Stale)
            assertTrue(component.state.value.ended.single().displayFreshness is DataFreshness.Fresh)
            // A day later the retained ended snapshot ages too.
            clock.instant += 25.hours
            advanceTimeBy(25 * 60 * 60_000L)
            runCurrent()
            assertTrue(component.state.value.ended.single().displayFreshness is DataFreshness.Stale)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun multipleCardsShareOneDeadlineAndResumeRecomputes() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val repository = FakeMonitoringRepository(clock)
        val ids = listOf(testRunId, testRunId.copy(number = TrainNumber("456")), testRunId.copy(number = TrainNumber("789")))
        for (id in ids) {
            val created = repository.createMonitor(id, MonitorThresholds(delayMinutes = 15), clock.now() + 1.hours)
            repository.monitors.value = repository.monitors.value.map {
                if (it.trainRunId == id) {
                    it.copy(lastSnapshot = MonitoredTrainSnapshot(
                        testRun.copy(summary = testRun.summary.copy(id = id)),
                        DataFreshness.Fresh(clock.now(), null), clock.now()))
                } else it
            }
        }
        val component = component(lifecycle, repository, clock)
        lifecycle.resume()
        try {
            runCurrent()
            assertEquals(3, component.state.value.active.size)
            // A single boundary advance ages every card with no provider
            // work: no per-card timers or polling loops are involved.
            clock.instant += 61.seconds
            advanceTimeBy(61_000)
            runCurrent()
            assertTrue(component.state.value.active.all { it.displayFreshness is DataFreshness.Stale })
            lifecycle.pause()
            runCurrent()
            // Fresh replacements observed while paused apply on resume.
            val refreshed = clock.now()
            repository.monitors.value = repository.monitors.value.map {
                it.copy(lastSnapshot = MonitoredTrainSnapshot(testRun, DataFreshness.Fresh(refreshed, null), refreshed))
            }
            runCurrent()
            // No time passes before resume: the replacements stay Fresh,
            // proving paused observation plus resume recomputation.
            lifecycle.resume()
            runCurrent()
            assertTrue(component.state.value.active.all { it.displayFreshness is DataFreshness.Fresh })
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun recreatedComponentShowsAgedProvenanceWithoutRefresh() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val repository = FakeMonitoringRepository(clock)
        val created = repository.createMonitor(
            testRunId, MonitorThresholds(delayMinutes = 15), clock.now() + 48.hours)
        repository.monitors.value = listOf(
            created.copy(lastSnapshot = MonitoredTrainSnapshot(
                testRun, DataFreshness.Fresh(clock.now(), null), clock.now())),
        )
        // The snapshot persists at t0; the component is (re)created two
        // hours later and must show Stale provenance from the first
        // observation — never a frozen Fresh, and no refresh is required.
        clock.instant += 2.hours
        val component = component(lifecycle, repository, clock)
        lifecycle.resume()
        try {
            runCurrent()
            assertEquals(1, component.state.value.active.size)
            assertTrue(component.state.value.active.single().displayFreshness is DataFreshness.Stale)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun destroyCancelsCardScheduling() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val repository = FakeMonitoringRepository(clock)
        stageActiveWithSnapshot(repository, clock)
        val component = component(lifecycle, repository, clock)
        lifecycle.resume()
        try {
            runCurrent()
            assertTrue(component.state.value.active.single().displayFreshness is DataFreshness.Fresh)
        } finally {
            lifecycle.destroy()
        }
        clock.instant += 2.hours
        advanceTimeBy(2 * 60 * 60_000L)
        runCurrent()
        assertTrue(component.state.value.active.single().displayFreshness is DataFreshness.Fresh)
        assertNotNull(component.state.value.active.single().monitor.lastSnapshot)
        assertNull(component.state.value.active.single().monitor.endedAt)
    }
}
