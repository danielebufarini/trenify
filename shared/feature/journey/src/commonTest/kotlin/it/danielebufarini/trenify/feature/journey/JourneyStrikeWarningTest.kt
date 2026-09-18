package it.danielebufarini.trenify.feature.journey

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.LoadStrikes
import it.danielebufarini.trenify.core.domain.SearchJourneys
import it.danielebufarini.trenify.core.domain.StrikeImpact
import it.danielebufarini.trenify.core.domain.StrikeRefresh
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Journey
import it.danielebufarini.trenify.core.model.JourneySearchRequest
import it.danielebufarini.trenify.core.model.StrikeGeography
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.StrikeRelevance
import it.danielebufarini.trenify.core.model.StrikeStatus
import it.danielebufarini.trenify.core.testing.FakeJourneyRepository
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.journeyDestination
import it.danielebufarini.trenify.core.testing.journeyResult
import it.danielebufarini.trenify.core.testing.journeyRequest
import it.danielebufarini.trenify.core.testing.testJourney
import it.danielebufarini.trenify.core.testing.testStation
import it.danielebufarini.trenify.core.testing.testStrike
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * T7.12-B journey strike warnings from scheduled intervals: results and
 * detail expose warnings without any realtime correlation, through one
 * targeted strike refresh — never one lookup per journey/result/card.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JourneyStrikeWarningTest {
    private val clock = MutableClock()
    private fun overlappingStrike() = testStrike.copy(
        start = journeyRequest.at - 2.hours,
        end = journeyRequest.at + 4.hours,
        operators = listOf(Operator("Trenitalia")),
    )

    private fun freshState() = DataResult.Data(
        listOf(overlappingStrike()),
        DataFreshness.Fresh(clock.now(), null),
    )

    @Test
    fun resultsExposeWarningsWithoutRealtimeThroughOneTargetedRefresh() = runTest {
        val lifecycle = LifecycleRegistry()
        val journeys = FakeJourneyRepository()
        val strikes = FakeStrikeRepository()
        strikes.state.value = freshState()
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
            // Exactly one targeted strike refresh for the whole results list.
            assertEquals(1, strikes.refreshes)
            assertEquals(
                journeyRequest.departureFrom to journeyRequest.departureUntil + JourneySearchRequest.horizon,
                strikes.refreshWindows.single(),
            )
            val warnings = component.state.value.strikeWarnings[testJourney]
            val warning = requireNotNull(warnings).single()
            assertEquals(overlappingStrike().id, warning.strike.id)
            assertEquals(overlappingStrike().start, warning.strike.start)
            assertEquals(overlappingStrike().end, warning.strike.end)
            assertEquals(overlappingStrike().source, warning.strike.source)
            assertEquals(StrikeImpact.LIKELY, warning.assessment.impact)
            assertFalse(component.state.value.strikesStale)
            assertFalse(component.state.value.strikesFailed)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun manyResultsStillCauseOnlyOneStrikeRefresh() = runTest {
        val lifecycle = LifecycleRegistry()
        val journeys = FakeJourneyRepository()
        val extra = listOf(1, 2).map { shift ->
            val legs = testJourney.legs.map { leg ->
                leg.copy(departure = leg.departure + shift.hours, arrival = leg.arrival + shift.hours)
            }
            Journey(legs, testJourney.sources)
        }
        journeys.state.value = DataResult.Data(
            journeyResult.copy(journeys = listOf(testJourney) + extra),
            DataFreshness.Fresh(clock.now(), null),
        )
        val strikes = FakeStrikeRepository()
        strikes.state.value = freshState()
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
            assertEquals(3, component.state.value.journeys.size)
            assertEquals(1, strikes.refreshes)
            assertEquals(3, component.state.value.strikeWarnings.size)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun revokedStrikeRemovesResultWarningReactively() = runTest {
        val lifecycle = LifecycleRegistry()
        val strikes = FakeStrikeRepository()
        strikes.state.value = freshState()
        val component = JourneyResultsComponent(
            DefaultComponentContext(lifecycle),
            journeyRequest,
            SearchJourneys(FakeJourneyRepository()),
            {},
            StandardTestDispatcher(testScheduler),
            LoadStrikes(strikes),
            clock = clock,
        )
        lifecycle.resume()
        try {
            runCurrent()
            assertTrue(component.state.value.strikeWarnings.isNotEmpty())
            strikes.state.value = DataResult.Data(
                listOf(overlappingStrike().copy(status = StrikeStatus.REVOKED)),
                DataFreshness.Fresh(clock.now(), null),
            )
            runCurrent()
            assertTrue(component.state.value.strikeWarnings.isEmpty())
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun strikeRefreshFailureRetainsCachedWarningsAsStale() = runTest {
        val lifecycle = LifecycleRegistry()
        val strikes = FakeStrikeRepository()
        strikes.state.value = freshState()
        val component = JourneyResultsComponent(
            DefaultComponentContext(lifecycle),
            journeyRequest,
            SearchJourneys(FakeJourneyRepository()),
            {},
            StandardTestDispatcher(testScheduler),
            LoadStrikes(strikes),
            clock = clock,
        )
        lifecycle.resume()
        try {
            runCurrent()
            assertTrue(component.state.value.strikeWarnings.isNotEmpty())
            strikes.state.value = DataResult.Data(
                listOf(overlappingStrike()),
                DataFreshness.Stale(clock.now(), 2.hours, null),
                DomainFailure.TEMPORARY,
            )
            strikes.refreshResult = DataResult.Data(
                StrikeRefresh(listOf(overlappingStrike())),
                DataFreshness.Stale(clock.now(), 2.hours, null),
                DomainFailure.TEMPORARY,
            )
            component.refreshStrikes()
            runCurrent()
            // Cached warnings survive the failure, flagged stale/failed.
            assertTrue(component.state.value.strikeWarnings.isNotEmpty())
            assertTrue(component.state.value.strikesStale)
            assertTrue(component.state.value.strikesFailed)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun unknownCoverageSurfacesUnknownInsteadOfStale() = runTest {
        val lifecycle = LifecycleRegistry()
        // Default fake state: cached data with Unknown freshness (never
        // authoritatively covered) and no overlapping strike.
        val strikes = FakeStrikeRepository()
        val component = JourneyResultsComponent(
            DefaultComponentContext(lifecycle),
            journeyRequest,
            SearchJourneys(FakeJourneyRepository()),
            {},
            StandardTestDispatcher(testScheduler),
            LoadStrikes(strikes),
            clock = clock,
        )
        lifecycle.resume()
        try {
            runCurrent()
            assertTrue(component.state.value.strikeWarnings.isEmpty())
            assertTrue(component.state.value.strikesUnknown)
            assertFalse(component.state.value.strikesStale)
            assertFalse(component.state.value.strikesFailed)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun detailExposesLegWarningsWithoutAnyRealtimeCorrelation() = runTest {
        val lifecycle = LifecycleRegistry()
        val strikes = FakeStrikeRepository()
        strikes.state.value = freshState()
        var opened = false
        val component = JourneyDetailComponent(
            DefaultComponentContext(lifecycle),
            testJourney,
            correlate = null,
            onTrain = { opened = true },
            dispatcher = StandardTestDispatcher(testScheduler),
            strikes = LoadStrikes(strikes),
            clock = clock,
        )
        lifecycle.resume()
        try {
            runCurrent()
            assertEquals(1, strikes.refreshes)
            assertEquals(
                testJourney.departure to testJourney.arrival,
                strikes.refreshWindows.single(),
            )
            val warnings = requireNotNull(component.state.value.strikeWarnings[0])
            assertEquals(StrikeImpact.LIKELY, warnings.single().assessment.impact)
            // No realtime correlation happened or is needed.
            assertTrue(component.state.value.trainRuns.isEmpty())
            assertFalse(component.state.value.correlating)
            assertFalse(opened)
            assertFalse(component.state.value.strikesStale)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun unknownOperatorAndGeographyStayConservativeOnJourneys() = runTest {
        val lifecycle = LifecycleRegistry()
        val strikes = FakeStrikeRepository()
        val anonymous = testStrike.copy(
            start = journeyRequest.at - 2.hours,
            end = journeyRequest.at + 4.hours,
            operators = emptyList(),
            geography = StrikeGeography(StrikeRelevance.UNKNOWN),
        )
        strikes.state.value = DataResult.Data(listOf(anonymous), DataFreshness.Fresh(clock.now(), null))
        val component = JourneyResultsComponent(
            DefaultComponentContext(lifecycle),
            journeyRequest,
            SearchJourneys(FakeJourneyRepository()),
            {},
            StandardTestDispatcher(testScheduler),
            LoadStrikes(strikes),
            clock = clock,
        )
        lifecycle.resume()
        try {
            runCurrent()
            val warnings = requireNotNull(component.state.value.strikeWarnings[testJourney])
            assertEquals(StrikeImpact.POTENTIAL, warnings.single().assessment.impact)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun disjointStrikeProducesNoWarning() = runTest {
        val lifecycle = LifecycleRegistry()
        val strikes = FakeStrikeRepository()
        strikes.state.value = DataResult.Data(
            listOf(testStrike.copy(
                start = Instant.parse("2026-09-07T19:18:00Z"),
                end = Instant.parse("2026-09-08T19:00:00Z"),
            )),
            DataFreshness.Fresh(clock.now(), null),
        )
        val component = JourneyResultsComponent(
            DefaultComponentContext(lifecycle),
            journeyRequest,
            SearchJourneys(FakeJourneyRepository()),
            {},
            StandardTestDispatcher(testScheduler),
            LoadStrikes(strikes),
            clock = clock,
        )
        lifecycle.resume()
        try {
            runCurrent()
            assertTrue(component.state.value.strikeWarnings.isEmpty())
            // The journey itself is untouched: status is not a warning concept.
            assertEquals(testStation, testJourney.origin)
            assertEquals(journeyDestination, testJourney.destination)
        } finally {
            lifecycle.destroy()
        }
    }
}
