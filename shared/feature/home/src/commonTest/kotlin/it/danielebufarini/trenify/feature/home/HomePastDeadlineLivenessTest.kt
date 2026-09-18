package it.danielebufarini.trenify.feature.home

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.LoadStrikes
import it.danielebufarini.trenify.core.domain.MonitorThresholds
import it.danielebufarini.trenify.core.domain.MonitoredTrainSnapshot
import it.danielebufarini.trenify.core.domain.ObserveActiveMonitors
import it.danielebufarini.trenify.core.domain.StrikeRefresh
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.testing.FakeMonitoringRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testRun
import it.danielebufarini.trenify.core.testing.testRunId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Past-deadline watcher liveness (T7.14 final pass): a persisted monitor
 * snapshot older than its TTL when Home starts observing must age to
 * stale exactly once and then stop — the component-level watcher never
 * busy-loops the Main thread waiting for a boundary that already
 * passed. (This test completing at all is the regression signal: the
 * runTest scheduler would never go idle under the old tight loop.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomePastDeadlineLivenessTest {
    @Test fun oldPersistedSnapshotAgesOnceWithoutBusyLoop() = runTest {
        val lifecycle = LifecycleRegistry()
        val stored = MutableClock()
        val monitoring = FakeMonitoringRepository(stored)
        val created = monitoring.createMonitor(
            testRunId,
            MonitorThresholds(delayMinutes = 15),
            stored.now() + 48.hours,
        )
        monitoring.monitors.value = listOf(
            created.copy(
                lastSnapshot = MonitoredTrainSnapshot(
                    testRun,
                    DataFreshness.Fresh(stored.now(), null),
                    stored.now(),
                ),
            ),
        )
        // Home opens much later than the snapshot TTL: every monitor
        // deadline is already in the past.
        val late = MutableClock()
        late.instant += 6.hours
        val strikes = FakeStrikeRepository()
        strikes.state.value = DataResult.Data(emptyList(), DataFreshness.Unknown)
        strikes.refreshResult = DataResult.Data(StrikeRefresh(emptyList()), DataFreshness.Unknown)
        val component = HomeComponent(
            DefaultComponentContext(lifecycle),
            FakeRealtimeRepositories(),
            FakeRealtimeRepositories(),
            ObserveActiveMonitors(monitoring),
            LoadStrikes(strikes),
            StandardTestDispatcher(testScheduler),
            clock = late,
        )
        lifecycle.resume()
        runCurrent()
        try {
            assertTrue(created.id.value in component.state.value.staleMonitorIds)
            // Further time passes with no new data: the watcher schedules
            // nothing and the scheduler stays idle.
            late.instant += 60.minutes
            advanceTimeBy(60 * 60_000L)
            runCurrent()
            assertTrue(created.id.value in component.state.value.staleMonitorIds)
        } finally {
            lifecycle.destroy()
        }
    }
}
