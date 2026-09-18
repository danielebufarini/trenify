package it.danielebufarini.trenify.feature.journey

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import it.danielebufarini.trenify.core.domain.CorrelateJourneyLeg
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.LoadStrikes
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.TrainRun
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.model.TrainStop
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.journeyDestination
import it.danielebufarini.trenify.core.testing.journeyRequest
import it.danielebufarini.trenify.core.testing.testJourney
import it.danielebufarini.trenify.core.testing.testRunId
import it.danielebufarini.trenify.core.testing.testStation
import it.danielebufarini.trenify.core.testing.testSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * T7.12-D available realtime enrichment: journey detail reuses already
 * correlated/cached snapshots read-only — scheduled data stays primary and
 * enrichment never issues realtime refreshes (no per-leg N+1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JourneyRealtimeEnrichmentTest {
    private val leg = testJourney.legs.single()

    private fun correlatedRun(delayMinutes: Int? = 10) = TrainRun(
        testSummary.copy(operator = leg.operator, status = TrainStatus.RUNNING, delayMinutes = delayMinutes),
        listOf(
            TrainStop(testStation, scheduledDeparture = leg.departure),
            TrainStop(journeyDestination, scheduledArrival = leg.arrival),
        ),
    )

    @Test
    fun detailEnrichesFromCacheWithoutPerLegRefreshes() = runTest {
        val lifecycle = LifecycleRegistry()
        val trains = FakeRealtimeRepositories()
        trains.trainState.value = DataResult.Data(correlatedRun(), DataFreshness.Fresh(MutableClock().now(), null))
        val component = JourneyDetailComponent(
            DefaultComponentContext(lifecycle),
            testJourney,
            CorrelateJourneyLeg(trains),
            {},
            StandardTestDispatcher(testScheduler),
            strikes = LoadStrikes(FakeStrikeRepository()),
            trains = trains,
        )
        lifecycle.resume()
        try {
            advanceUntilIdle()
            // Correlation resolved the leg through its cached read.
            assertEquals(testRunId, component.state.value.trainRuns[0])
            // Enrichment shows the cached observation…
            val enriched = (component.state.value.correlatedRuns[0] as DataResult.Data).value
            assertEquals(10, enriched.summary.delayMinutes)
            assertEquals(TrainStatus.RUNNING, enriched.summary.status)
            // …without any further realtime refresh: correlation did its
            // single cached read and enrichment is observation-only.
            assertEquals(1, trains.trainRefreshes)
            // Scheduled journey data stays primary and untouched.
            assertEquals(leg.departure, testJourney.legs.single().departure)
            assertEquals(leg.arrival, testJourney.legs.single().arrival)

            // A newer cached observation updates enrichment with no refresh.
            trains.trainState.value = DataResult.Data(
                correlatedRun(delayMinutes = 25),
                DataFreshness.Fresh(MutableClock().now(), null),
            )
            runCurrent()
            assertEquals(
                25,
                (component.state.value.correlatedRuns[0] as DataResult.Data).value.summary.delayMinutes,
            )
            assertEquals(1, trains.trainRefreshes)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun uncorrelatedLegsHaveNoEnrichmentAndIssueNoRefresh() = runTest {
        val lifecycle = LifecycleRegistry()
        val trains = FakeRealtimeRepositories()
        // Unknown operator on the cached run: correlation cannot verify it.
        trains.trainState.value = DataResult.Data(
            correlatedRun().copy(summary = correlatedRun().summary.copy(operator = Operator("Italo"))),
            DataFreshness.Fresh(MutableClock().now(), null),
        )
        val component = JourneyDetailComponent(
            DefaultComponentContext(lifecycle),
            testJourney,
            CorrelateJourneyLeg(trains),
            {},
            StandardTestDispatcher(testScheduler),
            trains = trains,
        )
        lifecycle.resume()
        try {
            advanceUntilIdle()
            assertTrue(component.state.value.trainRuns.isEmpty())
            assertTrue(component.state.value.correlatedRuns.isEmpty())
            // Correlation's own reads happened; enrichment added none and
            // invented no snapshot.
            assertTrue(trains.trainRefreshes <= 2)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun resultsNeverTouchRealtimeRepositories() = runTest {
        val lifecycle = LifecycleRegistry()
        val trains = FakeRealtimeRepositories()
        val component = JourneyResultsComponent(
            DefaultComponentContext(lifecycle),
            journeyRequest,
            null,
            {},
            StandardTestDispatcher(testScheduler),
            LoadStrikes(FakeStrikeRepository()),
        )
        lifecycle.resume()
        try {
            runCurrent()
            // Results have no realtime source at all: no correlation, no
            // enrichment, no refresh — scheduled warnings only.
            assertEquals(0, trains.trainRefreshes)
            assertTrue(trains.searches.isEmpty())
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun staleEnrichmentIsFlaggedWhileScheduledStaysPrimary() = runTest {
        val lifecycle = LifecycleRegistry()
        val trains = FakeRealtimeRepositories()
        trains.trainState.value = DataResult.Data(
            correlatedRun(),
            DataFreshness.Stale(MutableClock().now(), 2.hours, null),
            DomainFailure.TEMPORARY,
        )
        val component = JourneyDetailComponent(
            DefaultComponentContext(lifecycle),
            testJourney,
            CorrelateJourneyLeg(trains),
            {},
            StandardTestDispatcher(testScheduler),
            trains = trains,
        )
        lifecycle.resume()
        try {
            advanceUntilIdle()
            // Warning-backed stale fallback is not authoritative correlation evidence,
            // so nothing resolves and the scheduled journey remains primary.
            assertNull(component.state.value.trainRuns[0])
            // …and scheduled journey data stands alone with nothing invented.
            assertTrue(component.state.value.correlatedRuns.isEmpty())
            assertEquals(leg.departure, component.journey?.legs?.single()?.departure)
        } finally {
            lifecycle.destroy()
        }
    }
}
