package it.danielebufarini.trenify.feature.journey

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.pause
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.domain.CorrelateJourneyLeg
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.LoadStrikes
import it.danielebufarini.trenify.core.domain.SearchJourneys
import it.danielebufarini.trenify.core.domain.StrikeImpact
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.TrainRun
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.model.TrainStop
import it.danielebufarini.trenify.core.testing.FakeJourneyRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.journeyDestination
import it.danielebufarini.trenify.core.testing.journeyRequest
import it.danielebufarini.trenify.core.testing.testJourney
import it.danielebufarini.trenify.core.testing.testRunId
import it.danielebufarini.trenify.core.testing.testStation
import it.danielebufarini.trenify.core.testing.testStrike
import it.danielebufarini.trenify.core.testing.testSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Union-deadline aging for secondary journey freshness (T7.14 corrective):
 * correlated realtime legs and strike coverage age through elapsed local
 * time with zero provider calls, a later emission restores Fresh, and
 * pause/resume/destroy behave like the primary watcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JourneySecondaryFreshnessTest {
    private val leg = testJourney.legs.single()

    private fun correlatedRun(delayMinutes: Int? = 10) = TrainRun(
        testSummary.copy(operator = leg.operator, status = TrainStatus.RUNNING, delayMinutes = delayMinutes),
        listOf(
            TrainStop(testStation, scheduledDeparture = leg.departure),
            TrainStop(journeyDestination, scheduledArrival = leg.arrival),
        ),
    )

    private fun overlappingStrike() = testStrike.copy(
        start = journeyRequest.at - 2.hours,
        end = journeyRequest.at + 4.hours,
        operators = listOf(Operator("Trenitalia")),
    )

    @Test fun correlatedLegAgesAcrossTrainTtlWithZeroProviderCallsThenRecovers() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val trains = FakeRealtimeRepositories()
        trains.trainState.value = DataResult.Data(correlatedRun(), DataFreshness.Fresh(clock.now(), null))
        val component = JourneyDetailComponent(
            DefaultComponentContext(lifecycle),
            testJourney,
            CorrelateJourneyLeg(trains),
            {},
            StandardTestDispatcher(testScheduler),
            trains = trains,
            clock = clock,
        )
        lifecycle.resume()
        try {
            runCurrent()
            val enriched = component.state.value.correlatedRuns[0] as? DataResult.Data
                ?: error("correlated leg must be present")
            assertTrue(enriched.freshness is DataFreshness.Fresh)
            val refreshes = trains.trainRefreshes
            // The train TTL (30 s) passes with no further provider work.
            clock.instant += 31.seconds
            advanceTimeBy(31_000)
            runCurrent()
            val aged = component.state.value.correlatedRuns[0] as? DataResult.Data
                ?: error("correlated leg must be retained")
            assertTrue(aged.freshness is DataFreshness.Stale)
            assertEquals(refreshes, trains.trainRefreshes)
            // A later repository emission restores Fresh correctly.
            trains.trainState.value = DataResult.Data(
                correlatedRun(delayMinutes = 25),
                DataFreshness.Fresh(clock.now(), null),
            )
            runCurrent()
            val recovered = component.state.value.correlatedRuns[0] as? DataResult.Data
                ?: error("correlated leg must be retained")
            assertTrue(recovered.freshness is DataFreshness.Fresh)
            assertEquals(25, recovered.value.summary.delayMinutes)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun resultsStrikeCoverageAgesAcrossCacheTtlWithoutEmission() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val journeys = FakeJourneyRepository()
        journeys.state.value = DataResult.Data(
            journeys.state.value.let { (it as DataResult.Data).value },
            DataFreshness.Fresh(clock.now(), null),
        )
        val strikes = FakeStrikeRepository()
        strikes.state.value = DataResult.Data(
            listOf(overlappingStrike()),
            DataFreshness.Fresh(clock.now(), null),
        )
        val component = JourneyResultsComponent(
            DefaultComponentContext(lifecycle),
            journeyRequest,
            SearchJourneys(journeys),
            {},
            StandardTestDispatcher(testScheduler),
            LoadStrikes(strikes),
            clock = clock,
        )
        lifecycle.resume()
        try {
            runCurrent()
            val before = requireNotNull(component.state.value.strikeWarnings[testJourney]).single()
            assertEquals(overlappingStrike().id, before.strike.id)
            assertEquals(StrikeImpact.LIKELY, before.assessment.impact)
            assertFalse(component.state.value.strikesStale)
            val journeyCalls = journeys.calls
            val strikeRefreshes = strikes.refreshes
            // The strike cache TTL (45 min) passes with no strike or journey emission.
            clock.instant += 46.minutes
            advanceTimeBy(46 * 60_000L)
            runCurrent()
            assertTrue(component.state.value.strikesStale)
            val after = requireNotNull(component.state.value.strikeWarnings[testJourney]).single()
            // Warning identity/interval/status is not otherwise mutated.
            assertEquals(before.strike.id, after.strike.id)
            assertEquals(before.strike.start, after.strike.start)
            assertEquals(before.strike.end, after.strike.end)
            assertEquals(before.assessment.impact, after.assessment.impact)
            assertEquals(StrikeImpact.LIKELY, after.assessment.impact)
            // The aging transition caused zero strike-provider calls.
            assertEquals(journeyCalls, journeys.calls)
            assertEquals(strikeRefreshes, strikes.refreshes)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun detailStrikeCoverageAgesAcrossCacheTtlWithoutEmission() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val strikes = FakeStrikeRepository()
        strikes.state.value = DataResult.Data(
            listOf(overlappingStrike()),
            DataFreshness.Fresh(clock.now(), null),
        )
        val component = JourneyDetailComponent(
            DefaultComponentContext(lifecycle),
            testJourney,
            null,
            {},
            StandardTestDispatcher(testScheduler),
            strikes = LoadStrikes(strikes),
            clock = clock,
        )
        lifecycle.resume()
        try {
            runCurrent()
            val before = requireNotNull(component.state.value.strikeWarnings[0]).single()
            assertEquals(StrikeImpact.LIKELY, before.assessment.impact)
            assertFalse(component.state.value.strikesStale)
            val strikeRefreshes = strikes.refreshes
            clock.instant += 46.minutes
            advanceTimeBy(46 * 60_000L)
            runCurrent()
            assertTrue(component.state.value.strikesStale)
            val after = requireNotNull(component.state.value.strikeWarnings[0]).single()
            assertEquals(before.strike.id, after.strike.id)
            assertEquals(before.assessment.impact, after.assessment.impact)
            assertEquals(strikeRefreshes, strikes.refreshes)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun secondaryFreshnessPausesAndResumes() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val journeys = FakeJourneyRepository()
        journeys.state.value = DataResult.Data(
            journeys.state.value.let { (it as DataResult.Data).value },
            DataFreshness.Fresh(clock.now(), null),
        )
        val strikes = FakeStrikeRepository()
        strikes.state.value = DataResult.Data(
            listOf(overlappingStrike()),
            DataFreshness.Fresh(clock.now(), null),
        )
        val component = JourneyResultsComponent(
            DefaultComponentContext(lifecycle),
            journeyRequest,
            SearchJourneys(journeys),
            {},
            StandardTestDispatcher(testScheduler),
            LoadStrikes(strikes),
            clock = clock,
        )
        lifecycle.resume()
        try {
            runCurrent()
            assertTrue(component.state.value.results.freshness is DataFreshness.Fresh)
            assertFalse(component.state.value.strikesStale)
            lifecycle.pause()
            runCurrent()
            // Time passes while suspended: neither primary nor secondary may age.
            clock.instant += 60.minutes
            advanceTimeBy(60 * 60_000L)
            runCurrent()
            assertTrue(component.state.value.results.freshness is DataFreshness.Fresh)
            assertFalse(component.state.value.strikesStale)
            lifecycle.resume()
            runCurrent()
            assertTrue(component.state.value.results.freshness is DataFreshness.Stale)
            assertTrue(component.state.value.strikesStale)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun destroyCancelsUnionDeadline() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val journeys = FakeJourneyRepository()
        journeys.state.value = DataResult.Data(
            journeys.state.value.let { (it as DataResult.Data).value },
            DataFreshness.Fresh(clock.now(), null),
        )
        val strikes = FakeStrikeRepository()
        strikes.state.value = DataResult.Data(
            listOf(overlappingStrike()),
            DataFreshness.Fresh(clock.now(), null),
        )
        val component = JourneyResultsComponent(
            DefaultComponentContext(lifecycle),
            journeyRequest,
            SearchJourneys(journeys),
            {},
            StandardTestDispatcher(testScheduler),
            LoadStrikes(strikes),
            clock = clock,
        )
        lifecycle.resume()
        runCurrent()
        assertTrue(component.state.value.results.freshness is DataFreshness.Fresh)
        lifecycle.destroy()
        clock.instant += 60.minutes
        advanceTimeBy(60 * 60_000L)
        runCurrent()
        // The cancelled watcher schedules nothing after destroy.
        assertTrue(component.state.value.results.freshness is DataFreshness.Fresh)
        assertFalse(component.state.value.strikesStale)
    }
}
