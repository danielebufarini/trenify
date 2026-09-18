package it.danielebufarini.trenify.feature.train

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
 * Time-driven freshness for train detail (T7.14-B): Fresh becomes Stale
 * through elapsed local time with zero provider calls, resume recomputes
 * after suspension, stale-plus-failure retains content with a typed warning,
 * success returns to Fresh, and destroy cancels local scheduling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainFreshnessTest {
    private fun kotlinx.coroutines.test.TestScope.detail(
        lifecycle: LifecycleRegistry,
        repositories: FakeRealtimeRepositories,
        clock: MutableClock,
    ) = TrainDetailComponent(
        DefaultComponentContext(lifecycle), testRunId, ObserveTrainRun(repositories),
        MutableStateFlow(false), StandardTestDispatcher(testScheduler), clock = clock,
    )

    @Test fun freshBecomesStaleAcrossTtlWithZeroProviderCalls() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val repositories = FakeRealtimeRepositories()
        repositories.trainState.value =
            DataResult.Data(testRun, DataFreshness.Fresh(clock.now(), null))
        val component = detail(lifecycle, repositories, clock)
        try {
            lifecycle.resume()
            runCurrent()
            assertTrue(component.state.value.freshness is DataFreshness.Fresh)
            assertFalse(component.state.value.stale)
            clock.instant += 31.seconds
            advanceTimeBy(31_000)
            runCurrent()
            assertTrue(component.state.value.freshness is DataFreshness.Stale)
            assertTrue(component.state.value.stale)
            assertEquals(testRun, component.state.value.data)
            assertEquals(0, repositories.trainRefreshes)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun resumeAfterLongSuspensionRecomputesElapsedAge() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val repositories = FakeRealtimeRepositories()
        repositories.trainState.value =
            DataResult.Data(testRun, DataFreshness.Fresh(clock.now(), null))
        val component = detail(lifecycle, repositories, clock)
        try {
            lifecycle.resume()
            runCurrent()
            assertTrue(component.state.value.freshness is DataFreshness.Fresh)
            lifecycle.pause()
            runCurrent()
            // Time passes while suspended: no scheduling may fire.
            clock.instant += 5.minutes
            advanceTimeBy(5 * 60_000L)
            runCurrent()
            assertTrue(component.state.value.freshness is DataFreshness.Fresh)
            lifecycle.resume()
            runCurrent()
            assertTrue(component.state.value.freshness is DataFreshness.Stale)
            assertEquals(0, repositories.trainRefreshes)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun stalePlusRefreshFailureRetainsContentThenSuccessReturnsFresh() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val repositories = FakeRealtimeRepositories()
        repositories.trainState.value =
            DataResult.Data(testRun, DataFreshness.Fresh(clock.now(), null))
        val component = detail(lifecycle, repositories, clock)
        try {
            lifecycle.resume()
            runCurrent()
            clock.instant += 31.seconds
            advanceTimeBy(31_000)
            runCurrent()
            assertTrue(component.state.value.stale)
            // A failed refresh keeps cached content, stale provenance and a
            // typed warning instead of a generic error screen.
            val fetchedAt = (component.state.value.freshness as DataFreshness.Stale).fetchedAt
            repositories.trainState.value = DataResult.Data(
                testRun, DataFreshness.Stale(fetchedAt, 60.seconds, null), DomainFailure.OFFLINE)
            runCurrent()
            assertEquals(testRun, component.state.value.data)
            assertEquals(DomainFailure.OFFLINE, component.state.value.realtime.failure)
            assertTrue(component.state.value.stale)
            // A later success returns to Fresh and clears the warning.
            clock.instant += 2.minutes
            repositories.trainState.value =
                DataResult.Data(testRun, DataFreshness.Fresh(clock.now(), null))
            runCurrent()
            assertNull(component.state.value.realtime.failure)
            assertTrue(component.state.value.freshness is DataFreshness.Fresh)
            assertFalse(component.state.value.stale)
            assertEquals(0, repositories.trainRefreshes)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun destroyCancelsLocalFreshnessScheduling() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val repositories = FakeRealtimeRepositories()
        repositories.trainState.value =
            DataResult.Data(testRun, DataFreshness.Fresh(clock.now(), null))
        val component = detail(lifecycle, repositories, clock)
        lifecycle.resume()
        runCurrent()
        assertTrue(component.state.value.freshness is DataFreshness.Fresh)
        lifecycle.destroy()
        clock.instant += 60.minutes
        advanceTimeBy(60 * 60_000L)
        runCurrent()
        // No scheduling survives destroy: the display copy is untouched and
        // no provider call was issued.
        assertTrue(component.state.value.freshness is DataFreshness.Fresh)
        assertEquals(0, repositories.trainRefreshes)
    }
}
