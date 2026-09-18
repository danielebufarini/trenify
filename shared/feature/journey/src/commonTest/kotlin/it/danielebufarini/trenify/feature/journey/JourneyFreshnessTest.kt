package it.danielebufarini.trenify.feature.journey

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.pause
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.JourneyPolicy
import it.danielebufarini.trenify.core.domain.SearchJourneys
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.testing.FakeJourneyRepository
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.journeyRequest
import it.danielebufarini.trenify.core.testing.journeyResult
import it.danielebufarini.trenify.core.testing.testJourney
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Time-driven freshness for restored journey detail (T7.14-B): the
 * re-resolution provenance ages through elapsed local time with zero
 * provider calls, and resume after suspension recomputes elapsed age.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JourneyFreshnessTest {
    private fun kotlinx.coroutines.test.TestScope.restored(
        lifecycle: LifecycleRegistry,
        repository: FakeJourneyRepository,
        clock: MutableClock,
    ) = JourneyDetailComponent.restored(
        DefaultComponentContext(lifecycle),
        testJourney.lookupKey(journeyRequest),
        search = SearchJourneys(repository),
        correlate = null,
        dispatcher = StandardTestDispatcher(testScheduler),
        policy = JourneyPolicy(),
        clock = clock,
    )

    @Test fun restoredProvenanceAgesAcrossTtlWithZeroProviderCalls() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val repository = FakeJourneyRepository()
        repository.state.value = DataResult.Data(journeyResult, DataFreshness.Fresh(clock.now(), null))
        val component = restored(lifecycle, repository, clock)
        lifecycle.resume()
        try {
            runCurrent()
            assertEquals(testJourney, component.journey)
            assertTrue(component.state.value.journeyFreshness is DataFreshness.Fresh)
            // The journey TTL (3 minutes) passes with no further provider work.
            val calls = repository.calls
            clock.instant += 4.minutes
            advanceTimeBy(4 * 60_000L)
            runCurrent()
            assertTrue(component.state.value.journeyFreshness is DataFreshness.Stale)
            assertEquals(repository.calls, calls)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun restoredResumeAfterSuspensionRecomputesAge() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val repository = FakeJourneyRepository()
        repository.state.value = DataResult.Data(journeyResult, DataFreshness.Fresh(clock.now(), null))
        val component = restored(lifecycle, repository, clock)
        lifecycle.resume()
        try {
            runCurrent()
            assertTrue(component.state.value.journeyFreshness is DataFreshness.Fresh)
            lifecycle.pause()
            runCurrent()
            clock.instant += 10.minutes
            advanceTimeBy(10 * 60_000L)
            runCurrent()
            assertTrue(component.state.value.journeyFreshness is DataFreshness.Fresh)
            lifecycle.resume()
            runCurrent()
            assertTrue(component.state.value.journeyFreshness is DataFreshness.Stale)
        } finally {
            lifecycle.destroy()
        }
    }
}
