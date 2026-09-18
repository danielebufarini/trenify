package it.danielebufarini.trenify.feature.station

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.testing.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Time-driven freshness for the station board (T7.14-B): Fresh becomes
 * Stale through elapsed local time with zero provider calls, and resume
 * after suspension recomputes elapsed age.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StationFreshnessTest {
    @Test fun boardBecomesStaleAcrossTtlWithZeroProviderCalls() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val repositories = FakeRealtimeRepositories()
        repositories.boardState.value = DataResult.Data(
            StationBoard(testStation, BoardKind.DEPARTURES, listOf(testSummary)),
            DataFreshness.Fresh(clock.now(), null),
        )
        val component = StationBoardComponent(
            DefaultComponentContext(lifecycle), testStation.id,
            ObserveStation(repositories), LoadStationBoard(repositories), repositories, MutableStateFlow(false),
            {}, StandardTestDispatcher(testScheduler), clock = clock,
        )
        try {
            lifecycle.resume()
            runCurrent()
            assertTrue(component.state.value.freshness is DataFreshness.Fresh)
            clock.instant += 31.seconds
            advanceTimeBy(31_000)
            runCurrent()
            assertTrue(component.state.value.freshness is DataFreshness.Stale)
            assertTrue(component.state.value.stale)
            assertEquals(1, component.state.value.data?.trains?.size)
            assertEquals(0, repositories.boardRefreshes)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun boardResumeAfterSuspensionRecomputesAge() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val repositories = FakeRealtimeRepositories()
        repositories.boardState.value = DataResult.Data(
            StationBoard(testStation, BoardKind.DEPARTURES, listOf(testSummary)),
            DataFreshness.Fresh(clock.now(), null),
        )
        val component = StationBoardComponent(
            DefaultComponentContext(lifecycle), testStation.id,
            ObserveStation(repositories), LoadStationBoard(repositories), repositories, MutableStateFlow(false),
            {}, StandardTestDispatcher(testScheduler), clock = clock,
        )
        try {
            lifecycle.resume()
            runCurrent()
            assertTrue(component.state.value.freshness is DataFreshness.Fresh)
            lifecycle.pause()
            runCurrent()
            clock.instant += 5.minutes
            advanceTimeBy(5 * 60_000L)
            runCurrent()
            assertTrue(component.state.value.freshness is DataFreshness.Fresh)
            lifecycle.resume()
            runCurrent()
            assertTrue(component.state.value.freshness is DataFreshness.Stale)
            assertEquals(0, repositories.boardRefreshes)
        } finally {
            lifecycle.destroy()
        }
    }
}
