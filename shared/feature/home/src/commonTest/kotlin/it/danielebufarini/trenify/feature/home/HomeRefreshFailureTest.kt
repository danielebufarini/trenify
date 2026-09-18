package it.danielebufarini.trenify.feature.home

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.DomainFailure
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Failed-refresh presentation on Home (T7.14 corrective): a monitor whose
 * latest refresh attempt failed stays visible, is immediately stale, and
 * carries the typed OFFLINE/TEMPORARY failure for the FS §23 wording.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeRefreshFailureTest {
    private fun kotlinx.coroutines.test.TestScope.home(
        lifecycle: LifecycleRegistry,
        monitoring: FakeMonitoringRepository,
        strikes: FakeStrikeRepository,
        clock: MutableClock,
    ) = HomeComponent(
        DefaultComponentContext(lifecycle),
        FakeRealtimeRepositories(),
        FakeRealtimeRepositories(),
        ObserveActiveMonitors(monitoring),
        LoadStrikes(strikes),
        StandardTestDispatcher(testScheduler),
        clock = clock,
    )

    private suspend fun stageDegraded(
        monitoring: FakeMonitoringRepository,
        clock: MutableClock,
        failure: DomainFailure,
    ) {
        val created = monitoring.createMonitor(testRunId, MonitorThresholds(delayMinutes = 15), clock.now() + 1.hours)
        monitoring.monitors.value = listOf(
            created.copy(
                lastSnapshot = MonitoredTrainSnapshot(
                    testRun,
                    DataFreshness.Fresh(clock.now(), null),
                    clock.now(),
                ),
            ),
        )
        monitoring.recordRefreshFailure(created.id, failure, clock.now())
    }

    @Test fun offlineDegradedMonitorIsStaleWithTypedFailure() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val monitoring = FakeMonitoringRepository(clock)
        stageDegraded(monitoring, clock, DomainFailure.OFFLINE)
        val strikes = FakeStrikeRepository()
        strikes.state.value = DataResult.Data(emptyList(), DataFreshness.Unknown)
        strikes.refreshResult = DataResult.Data(StrikeRefresh(emptyList()), DataFreshness.Unknown)
        val component = home(lifecycle, monitoring, strikes, clock)
        lifecycle.resume()
        try {
            runCurrent()
            val state = component.state.value
            val monitor = state.monitors.single()
            assertEquals(DomainFailure.OFFLINE, monitor.refreshFailure)
            assertTrue(monitor.id.value in state.staleMonitorIds)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun notFoundDegradedMonitorKeepsKnownTrainContext() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val monitoring = FakeMonitoringRepository(clock)
        stageDegraded(monitoring, clock, DomainFailure.NOT_FOUND)
        val strikes = FakeStrikeRepository()
        strikes.state.value = DataResult.Data(emptyList(), DataFreshness.Unknown)
        strikes.refreshResult = DataResult.Data(StrikeRefresh(emptyList()), DataFreshness.Unknown)
        val component = home(lifecycle, monitoring, strikes, clock)
        lifecycle.resume()
        try {
            runCurrent()
            val state = component.state.value
            val monitor = state.monitors.single()
            assertEquals(DomainFailure.NOT_FOUND, monitor.refreshFailure)
            assertTrue(monitor.id.value in state.staleMonitorIds)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun temporaryDegradedMonitorIsStaleWithTypedFailure() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val monitoring = FakeMonitoringRepository(clock)
        stageDegraded(monitoring, clock, DomainFailure.TEMPORARY)
        val strikes = FakeStrikeRepository()
        strikes.state.value = DataResult.Data(emptyList(), DataFreshness.Unknown)
        strikes.refreshResult = DataResult.Data(StrikeRefresh(emptyList()), DataFreshness.Unknown)
        val component = home(lifecycle, monitoring, strikes, clock)
        lifecycle.resume()
        try {
            runCurrent()
            val state = component.state.value
            val monitor = state.monitors.single()
            assertEquals(DomainFailure.TEMPORARY, monitor.refreshFailure)
            assertTrue(monitor.id.value in state.staleMonitorIds)
        } finally {
            lifecycle.destroy()
        }
    }
}
