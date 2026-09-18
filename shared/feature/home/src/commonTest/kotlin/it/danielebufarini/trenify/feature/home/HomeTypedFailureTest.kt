package it.danielebufarini.trenify.feature.home

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.LoadStrikes
import it.danielebufarini.trenify.core.domain.ObserveActiveMonitors
import it.danielebufarini.trenify.core.domain.StrikeRefresh
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.testing.FakeMonitoringRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testStrike
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Typed FS §23 failure presentation for Home strikes (T7.14-A): the strike
 * section keeps cached content with a typed warning, reports no-cache
 * failures with retry intact, and recovers on refresh.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeTypedFailureTest {
    @Test fun staleStrikesPlusOfflineKeepContentWithTypedWarning() = runTest {
        val lifecycle = LifecycleRegistry()
        val history = FakeRealtimeRepositories()
        val strikes = FakeStrikeRepository()
        val clock = MutableClock()
        strikes.state.value = DataResult.Data(
            listOf(testStrike), DataFreshness.Stale(clock.now(), 90.seconds, null), DomainFailure.OFFLINE)
        strikes.refreshResult = DataResult.Failure(DomainFailure.OFFLINE)
        val component = HomeComponent(
            DefaultComponentContext(lifecycle),
            history,
            history,
            ObserveActiveMonitors(FakeMonitoringRepository()),
            LoadStrikes(strikes),
            StandardTestDispatcher(testScheduler),
            clock = clock,
        )
        lifecycle.resume()
        try {
            runCurrent()
            assertTrue(component.state.value.strikes.data?.any { it.id == testStrike.id } == true)
            assertEquals(DomainFailure.OFFLINE, component.state.value.strikes.failure)
            assertTrue(component.state.value.strikes.failed)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun noCacheOfflineIsTypedAndRefreshRecovers() = runTest {
        val lifecycle = LifecycleRegistry()
        val history = FakeRealtimeRepositories()
        val strikes = FakeStrikeRepository()
        val clock = MutableClock()
        strikes.state.value = DataResult.Failure(DomainFailure.OFFLINE)
        strikes.refreshResult = DataResult.Failure(DomainFailure.OFFLINE)
        val component = HomeComponent(
            DefaultComponentContext(lifecycle),
            history,
            history,
            ObserveActiveMonitors(FakeMonitoringRepository()),
            LoadStrikes(strikes),
            StandardTestDispatcher(testScheduler),
            clock = clock,
        )
        lifecycle.resume()
        try {
            runCurrent()
            assertEquals(DomainFailure.OFFLINE, component.state.value.strikes.failure)
            strikes.state.value = DataResult.Data(listOf(testStrike), DataFreshness.Fresh(clock.now(), null))
            strikes.refreshResult = DataResult.Data(StrikeRefresh(listOf(testStrike)), DataFreshness.Fresh(clock.now(), null))
            component.retry()
            runCurrent()
            assertNull(component.state.value.strikes.failure)
        } finally {
            lifecycle.destroy()
        }
    }
}
