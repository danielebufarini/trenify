package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.domain.BookingHandoffResult
import it.danielebufarini.trenify.core.domain.BookingLinkPolicy
import it.danielebufarini.trenify.core.domain.BookingTarget
import it.danielebufarini.trenify.core.domain.CorrelateJourneyLeg
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.GeographyEvidence
import it.danielebufarini.trenify.core.domain.JourneyRepository
import it.danielebufarini.trenify.core.domain.LoadStrikes
import it.danielebufarini.trenify.core.domain.OperatorEvidence
import it.danielebufarini.trenify.core.domain.SearchJourneys
import it.danielebufarini.trenify.core.domain.ServiceStrikeWarning
import it.danielebufarini.trenify.core.domain.StrikeImpact
import it.danielebufarini.trenify.core.domain.StrikeImpactAssessment
import it.danielebufarini.trenify.core.domain.TemporalEvidence
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.ExternalStationRef
import it.danielebufarini.trenify.core.model.Journey
import it.danielebufarini.trenify.core.model.JourneyCoverage
import it.danielebufarini.trenify.core.model.JourneyLeg
import it.danielebufarini.trenify.core.model.JourneySearchRequest
import it.danielebufarini.trenify.core.model.JourneySearchResult
import it.danielebufarini.trenify.core.model.JourneySort
import it.danielebufarini.trenify.core.model.JourneySourceStatus
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.ProviderId
import it.danielebufarini.trenify.core.model.Station
import it.danielebufarini.trenify.core.model.StationId
import it.danielebufarini.trenify.core.model.TrainCategory
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.model.TrainRun
import it.danielebufarini.trenify.core.model.TrainRunId
import it.danielebufarini.trenify.core.model.TrainRunSummary
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.model.TrainStop
import it.danielebufarini.trenify.core.testing.FakeJourneyRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.journeyDestination
import it.danielebufarini.trenify.core.testing.journeyRequest
import it.danielebufarini.trenify.core.testing.journeyResult
import it.danielebufarini.trenify.core.testing.testJourney
import it.danielebufarini.trenify.core.testing.testStation
import it.danielebufarini.trenify.core.testing.testStrike
import it.danielebufarini.trenify.core.testing.testSummary
import it.danielebufarini.trenify.feature.journey.JourneyDetailComponent
import it.danielebufarini.trenify.feature.journey.JourneyResultsComponent
import it.danielebufarini.trenify.feature.journey.JourneyTabComponent
import it.danielebufarini.trenify.feature.journey.lookupKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NativeJourneyPresentationTest {
    @Test fun resultsProjectionPreservesSharedSemantics() = runTest {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            val facade = NativeJourneyResultsPresentation(
                JourneyResultsComponent(
                    DefaultComponentContext(lifecycle),
                    journeyRequest,
                    SearchJourneys(FakeJourneyRepository()),
                    {},
                    StandardTestDispatcher(testScheduler),
                ),
                NativeProjectionOwner(),
            )
            runCurrent()
            val state = facade.state.value
            assertEquals(testStation.name, state.originName)
            assertEquals(journeyDestination.name, state.destinationName)
            assertEquals(journeyRequest.departureFrom.epochSeconds, state.windowStartEpochSeconds)
            assertEquals(journeyRequest.departureUntil.epochSeconds, state.windowEndEpochSeconds)
            assertFalse(state.loading)
            assertNull(state.failure)
            assertTrue(state.hasContent)
            assertFalse(state.empty)
            assertFalse(state.partial)
            assertEquals(JourneySort.DEPARTURE, state.sort)
            val card = state.journeys.single()
            assertEquals(0, card.index)
            assertEquals(testJourney.departure.epochSeconds, card.departureEpochSeconds)
            assertEquals(testJourney.arrival.epochSeconds, card.arrivalEpochSeconds)
            assertEquals(180L, card.durationMinutes)
            assertEquals(0, card.changes)
            assertEquals(listOf("Frecciarossa 123"), card.trainIdentities)
            assertEquals(listOf("Trenitalia"), card.operatorNames)
            assertEquals(0, card.warningCount)
            assertFalse(card.confirmedWarning)
            val leg = card.legs.single()
            assertEquals(testStation.name, leg.originName)
            assertEquals(journeyDestination.name, leg.destinationName)
            assertEquals("Frecciarossa 123", leg.trainIdentity)
            assertEquals("Trenitalia", leg.operatorName)
            assertNull(leg.transferWaitMinutesAfterPrevious)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun resultsSortAndSelectForwardToSharedComponent() = runTest {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            var selected: Journey? = null
            val facade = NativeJourneyResultsPresentation(
                JourneyResultsComponent(
                    DefaultComponentContext(lifecycle),
                    journeyRequest,
                    SearchJourneys(FakeJourneyRepository()),
                    { selected = it },
                    StandardTestDispatcher(testScheduler),
                ),
                NativeProjectionOwner(),
            )
            runCurrent()
            facade.setSort(JourneySort.DURATION)
            runCurrent()
            assertEquals(JourneySort.DURATION, facade.state.value.sort)
            // Same single journey remains projected after the sort change.
            assertEquals(1, facade.state.value.journeys.size)
            facade.selectJourney(0)
            assertEquals(testJourney, selected)
            // Out-of-range selection is a no-op, never a second navigation.
            selected = null
            facade.selectJourney(7)
            assertNull(selected)
        } finally {
            lifecycle.destroy()
        }
    }

    /**
     * Presentation policy (pre-T8.13 corrective A-C, E): progressive,
     * partial, cached-refresh and failed-refresh states never project an
     * "outdated" warning, while internal freshness/partial/failure
     * distinctions stay intact for the content and retry paths.
     */
    private fun partialResult() = journeyResult.copy(coverage = journeyResult.coverage +
        JourneyCoverage(ProviderId("degraded"), emptyList(), emptyList(), JourneySourceStatus.UNAVAILABLE, false))

    @Test fun progressiveStalePartialProjectsNoOutdatedWarning() = runTest {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            val clock = MutableClock()
            val repository = FakeJourneyRepository()
            val facade = resultsFacade(lifecycle, StandardTestDispatcher(testScheduler),
                search = SearchJourneys(repository))
            runCurrent()
            // Progressive provider aggregation: partial by design, Stale by contract.
            repository.state.value = DataResult.Data(
                partialResult(),
                DataFreshness.Stale(clock.now(), 2.minutes, clock.now()),
            )
            runCurrent()
            val state = facade.state.value
            assertTrue(state.hasContent)
            assertTrue(state.partial)
            assertFalse(state.stale)
            assertEquals(1, state.journeys.size)
            assertNull(state.failure)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun freshPartialResultProjectsNoOutdatedWarning() = runTest {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            val clock = MutableClock()
            val repository = FakeJourneyRepository()
            val facade = resultsFacade(lifecycle, StandardTestDispatcher(testScheduler),
                search = SearchJourneys(repository))
            runCurrent()
            // Partial-but-current aggregation: the partial flag stays visible
            // to content logic while no outdated warning is projected.
            repository.state.value = DataResult.Data(
                partialResult(),
                DataFreshness.Fresh(clock.now(), null),
            )
            runCurrent()
            val state = facade.state.value
            assertTrue(state.hasContent)
            assertTrue(state.partial)
            assertFalse(state.stale)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun staleCachedContentProjectsNoOutdatedWarning() = runTest {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            val clock = MutableClock()
            val repository = FakeJourneyRepository()
            val facade = resultsFacade(lifecycle, StandardTestDispatcher(testScheduler),
                search = SearchJourneys(repository))
            runCurrent()
            // Cached complete content shown while a refresh runs: useful
            // content first, no outdated warning.
            repository.state.value = DataResult.Data(
                journeyResult,
                DataFreshness.Stale(clock.now(), 2.minutes, clock.now()),
            )
            runCurrent()
            val state = facade.state.value
            assertTrue(state.hasContent)
            assertFalse(state.partial)
            assertFalse(state.stale)
            assertEquals(1, state.journeys.size)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun failedRefreshWithCacheKeepsFailureSignalWithoutOutdatedWarning() = runTest {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            val clock = MutableClock()
            val repository = FakeJourneyRepository()
            val facade = resultsFacade(lifecycle, StandardTestDispatcher(testScheduler),
                search = SearchJourneys(repository))
            runCurrent()
            repository.state.value = DataResult.Data(
                journeyResult,
                DataFreshness.Stale(clock.now(), 2.minutes, clock.now()),
            )
            runCurrent()
            // The refresh now fails: the actionable failure-with-retry signal
            // is projected, the generic outdated warning is not.
            repository.state.value = DataResult.Failure(DomainFailure.TEMPORARY)
            runCurrent()
            val state = facade.state.value
            assertTrue(state.hasContent)
            assertFalse(state.stale)
            assertEquals(DomainFailure.TEMPORARY, state.failure)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun detailStaleJourneyFreshnessProjectsNoOutdatedWarning() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val repository = FakeJourneyRepository()
        repository.state.value = DataResult.Data(
            journeyResult,
            DataFreshness.Stale(clock.now(), 2.minutes, clock.now()),
        )
        val component = JourneyDetailComponent.restored(
            DefaultComponentContext(lifecycle),
            testJourney.lookupKey(journeyRequest),
            search = SearchJourneys(repository),
            correlate = null,
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = clock,
        )
        lifecycle.resume()
        try {
            runCurrent()
            val facade = NativeJourneyDetailPresentation(component, NativeProjectionOwner())
            runCurrent()
            // Internal Stale semantics intact, user-facing warning suppressed;
            // provenance timestamps are still projected.
            assertIs<DataFreshness.Stale>(component.state.value.journeyFreshness)
            assertFalse(facade.state.value.journeyStale)
            assertEquals(clock.now().epochSeconds, facade.state.value.journeyFetchedAtEpochSeconds)
            assertNotNull(facade.state.value.originName)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun detailProjectionPreservesLegsAndBookingPolicy() = runTest {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            val facade = NativeJourneyDetailPresentation(
                JourneyDetailComponent(
                    DefaultComponentContext(lifecycle),
                    journey = testJourney,
                    correlate = null,
                    onTrain = {},
                    dispatcher = StandardTestDispatcher(testScheduler),
                    canBook = { true },
                    openBooking = { BookingHandoffResult.Opened(BookingTarget("https://www.trenitalia.com", "Trenitalia")) },
                ),
                NativeProjectionOwner(),
            )
            runCurrent()
            val state = facade.state.value
            assertFalse(state.resolving)
            assertFalse(state.notFound)
            assertEquals(testStation.name, state.originName)
            assertEquals(journeyDestination.name, state.destinationName)
            assertEquals(180L, state.durationMinutes)
            assertEquals(0, state.changes)
            val leg = state.legs.single()
            assertEquals("Frecciarossa 123", leg.trainIdentity)
            assertEquals("Trenitalia", leg.operatorName)
            // No correlated realtime snapshot: unknown stays unknown.
            assertEquals(TrainStatus.UNKNOWN, leg.realtimeStatus)
            assertNull(leg.delayMinutes)
            assertFalse(leg.hasTrainAction)
            assertTrue(state.bookingAvailable)
            assertEquals("Trenitalia", state.bookingOperatorName)
            assertFalse(state.bookingInProgress)
            assertFalse(state.bookingFailed)
            facade.buy()
            runCurrent()
            assertFalse(facade.state.value.bookingInProgress)
            assertFalse(facade.state.value.bookingFailed)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun unknownOperatorRegionalLegProjectsCorrelatedRealtimeWithoutChangingJourneyIdentity() = runTest {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            val monza = Station(StationId("station:monza"), "Monza")
            val milano = Station(StationId("station:milano centrale"), "Milano Centrale")
            val tirano = Station(StationId("station:tirano"), "Tirano")
            val departure = Instant.parse("2026-09-18T06:27:00Z")
            val arrival = Instant.parse("2026-09-18T06:43:00Z")
            val leg = JourneyLeg(
                monza,
                milano,
                departure,
                arrival,
                TrainNumber("2815"),
                "Regionale",
                operator = null,
            )
            val journey = Journey(listOf(leg), setOf(ProviderId("trenitalia-journeys")))
            val id = TrainRunId(
                ProviderId("viaggiatreno"),
                TrainNumber("2815"),
                ExternalStationRef("S01440"),
                LocalDate.parse("2026-09-18"),
            )
            val run = TrainRun(
                TrainRunSummary(
                    id,
                    tirano,
                    milano.name,
                    status = TrainStatus.RUNNING,
                    delayMinutes = 7,
                    operator = Operator("Trenord"),
                    category = TrainCategory.REG,
                ),
                listOf(
                    TrainStop(tirano, scheduledDeparture = Instant.parse("2026-09-18T04:10:00Z")),
                    TrainStop(
                        monza,
                        scheduledDeparture = departure,
                        actualDeparture = departure + 7.minutes,
                    ),
                    TrainStop(
                        milano,
                        scheduledArrival = arrival,
                        actualArrival = arrival + 7.minutes,
                    ),
                ),
            )
            val trains = FakeRealtimeRepositories().apply {
                runsResult = DataResult.Data(listOf(run.summary), DataFreshness.Unknown)
                trainState.value = DataResult.Data(
                    run,
                    DataFreshness.Stale(MutableClock().now(), 2.hours, MutableClock().now() - 2.hours),
                )
            }
            val facade = NativeJourneyDetailPresentation(
                JourneyDetailComponent(
                    DefaultComponentContext(lifecycle),
                    journey = journey,
                    correlate = CorrelateJourneyLeg(trains),
                    onTrain = {},
                    dispatcher = StandardTestDispatcher(testScheduler),
                    trains = trains,
                ),
                NativeProjectionOwner(),
            )

            advanceUntilIdle()

            val state = facade.state.value
            assertFalse(state.correlating)
            val projected = state.legs.single()
            assertEquals(TrainStatus.RUNNING, projected.realtimeStatus)
            assertEquals(7, projected.delayMinutes)
            assertTrue(projected.hasTrainAction)
            assertTrue(projected.realtimeStale)
            assertEquals("ViaggiaTreno", projected.realtimeProvenance?.providerName)
            // Matrix B: unknown Journey operator + uniquely correlated run
            // with a known operator displays the realtime operator.
            assertEquals("Trenord", projected.operatorName)
            // Presentation enrichment only: the domain Journey still has no
            // operator.
            assertNull(journey.legs.single().operator)
            assertFalse(state.bookingAvailable)
            assertNull(state.bookingOperatorName)
            // Booking stays governed by the original Journey operator, not
            // the displayed fallback.
            assertNull(BookingLinkPolicy().target(journey))
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun knownOperatorKeepsPrecedenceOverCorrelatedRun() = runTest {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            // Matrix A: testJourney carries Trenitalia and the correlated run
            // carries the same operator, so the Journey value stays
            // authoritative for display.
            val facade = detailFacade(lifecycle, StandardTestDispatcher(testScheduler), stagedTrains())
            runCurrent()
            val leg = facade.state.value.legs.single()
            assertTrue(leg.hasTrainAction)
            assertEquals(TrainStatus.RUNNING, leg.realtimeStatus)
            assertEquals("Trenitalia", leg.operatorName)
            assertEquals(testJourney.legs.single().operator?.name, leg.operatorName)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun unknownOperatorAndUnknownRunOperatorStaysUnknown() = runTest {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            val journey = unknownOperator2815Journey()
            val run = correlated2815Run(journey, runOperator = null)
            val trains = correlated2815Trains(run)
            val facade = NativeJourneyDetailPresentation(
                JourneyDetailComponent(
                    DefaultComponentContext(lifecycle),
                    journey = journey,
                    correlate = CorrelateJourneyLeg(trains),
                    onTrain = {},
                    dispatcher = StandardTestDispatcher(testScheduler),
                    trains = trains,
                ),
                NativeProjectionOwner(),
            )
            advanceUntilIdle()
            val projected = facade.state.value.legs.single()
            // Matrix C: correlation succeeds (realtime enrichment is present)
            // but neither side knows the operator: unknown stays unknown and
            // nothing is guessed from category, number or route.
            assertEquals(TrainStatus.RUNNING, projected.realtimeStatus)
            assertEquals(7, projected.delayMinutes)
            assertTrue(projected.hasTrainAction)
            assertNull(projected.operatorName)
            assertNull(journey.legs.single().operator)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun unknownOperatorWithoutCorrelationStaysUnknown() = runTest {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            val journey = unknownOperator2815Journey()
            val facade = NativeJourneyDetailPresentation(
                JourneyDetailComponent(
                    DefaultComponentContext(lifecycle),
                    journey = journey,
                    correlate = null,
                    onTrain = {},
                    dispatcher = StandardTestDispatcher(testScheduler),
                ),
                NativeProjectionOwner(),
            )
            runCurrent()
            val projected = facade.state.value.legs.single()
            // Matrix D: no realtime correlation, so no second lookup is
            // attempted merely for display; unknown stays unknown.
            assertEquals(TrainStatus.UNKNOWN, projected.realtimeStatus)
            assertFalse(projected.hasTrainAction)
            assertNull(projected.realtimeProvenance)
            assertNull(projected.operatorName)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun detailProjectionExposesScheduledAndActualPlatforms() = runTest {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            val journey = unknownOperator2815Journey()
            val base = correlated2815Run(journey, runOperator = Operator("Trenord"))
            // A Journey platform is scoped to the boarding stop. Deliberately
            // keep different run-summary values so the test rejects the old
            // global-platform projection and proves Monza wins.
            val run = base.copy(
                summary = base.summary.copy(scheduledPlatform = "1", actualPlatform = "2"),
                stops = base.stops.mapIndexed { index, stop ->
                    when (index) {
                        0 -> stop.copy(scheduledPlatform = "3", actualPlatform = null)
                        1 -> stop.copy(scheduledPlatform = "7", actualPlatform = "8")
                        else -> stop.copy(scheduledPlatform = "9", actualPlatform = null)
                    }
                },
            )
            val trains = correlated2815Trains(run)
            val facade = NativeJourneyDetailPresentation(
                JourneyDetailComponent(
                    DefaultComponentContext(lifecycle),
                    journey = journey,
                    correlate = CorrelateJourneyLeg(trains),
                    onTrain = {},
                    dispatcher = StandardTestDispatcher(testScheduler),
                    trains = trains,
                ),
                NativeProjectionOwner(),
            )
            advanceUntilIdle()
            val projected = facade.state.value.legs.single()
            assertTrue(projected.hasTrainAction)
            assertEquals("Trenord", projected.operatorName)
            assertEquals("7", projected.scheduledPlatform)
            assertEquals("8", projected.actualPlatform)
        } finally {
            lifecycle.destroy()
        }
    }

    private fun unknownOperator2815Journey(): Journey {
        val monza = Station(StationId("station:monza"), "Monza")
        val milano = Station(StationId("station:milano centrale"), "Milano Centrale")
        return Journey(
            listOf(
                JourneyLeg(
                    monza,
                    milano,
                    Instant.parse("2026-09-18T06:27:00Z"),
                    Instant.parse("2026-09-18T06:43:00Z"),
                    TrainNumber("2815"),
                    "Regionale",
                    operator = null,
                ),
            ),
            setOf(ProviderId("trenitalia-journeys")),
        )
    }

    private fun correlated2815Run(journey: Journey, runOperator: Operator?): TrainRun {
        val leg = journey.legs.single()
        val tirano = Station(StationId("station:tirano"), "Tirano")
        return TrainRun(
            TrainRunSummary(
                TrainRunId(
                    ProviderId("viaggiatreno"),
                    TrainNumber("2815"),
                    ExternalStationRef("S01440"),
                    LocalDate.parse("2026-09-18"),
                ),
                tirano,
                leg.destination.name,
                status = TrainStatus.RUNNING,
                delayMinutes = 7,
                operator = runOperator,
                category = TrainCategory.REG,
            ),
            listOf(
                TrainStop(tirano, scheduledDeparture = Instant.parse("2026-09-18T04:10:00Z")),
                TrainStop(
                    leg.origin,
                    scheduledDeparture = leg.departure,
                    actualDeparture = leg.departure + 7.minutes,
                ),
                TrainStop(
                    leg.destination,
                    scheduledArrival = leg.arrival,
                    actualArrival = leg.arrival + 7.minutes,
                ),
            ),
        )
    }

    private fun correlated2815Trains(run: TrainRun): FakeRealtimeRepositories =
        FakeRealtimeRepositories().apply {
            runsResult = DataResult.Data(listOf(run.summary), DataFreshness.Unknown)
            trainState.value = DataResult.Data(run, DataFreshness.Fresh(MutableClock().now(), null))
        }

    @Test fun detailWithoutJourneyReportsNotFound() = runTest {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            val facade = NativeJourneyDetailPresentation(
                JourneyDetailComponent(
                    DefaultComponentContext(lifecycle),
                    journey = null,
                    correlate = null,
                    onTrain = {},
                    dispatcher = StandardTestDispatcher(testScheduler),
                ),
                NativeProjectionOwner(),
            )
            runCurrent()
            val state = facade.state.value
            assertTrue(state.notFound)
            assertFalse(state.resolving)
            assertNull(state.originName)
            assertTrue(state.legs.isEmpty())
            assertFalse(state.bookingAvailable)
            assertNull(state.bookingOperatorName)
        } finally {
            lifecycle.destroy()
        }
    }

    private fun resultsFacade(
        lifecycle: LifecycleRegistry,
        dispatcher: CoroutineDispatcher,
        strikes: LoadStrikes? = null,
        search: SearchJourneys? = null,
    ): NativeJourneyResultsPresentation = NativeJourneyResultsPresentation(
        JourneyResultsComponent(
            DefaultComponentContext(lifecycle),
            journeyRequest,
            search ?: SearchJourneys(FakeJourneyRepository()),
            {},
            dispatcher,
            strikes = strikes,
        ),
        NativeProjectionOwner(),
    )

    private fun overlappingStrike() =
        testStrike.copy(start = testJourney.legs.single().departure - 2.hours, end = testJourney.legs.single().arrival + 2.hours)

    @Test fun resultsWarningProjectionPreservesT712Semantics() = runTest {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            val strikes = FakeStrikeRepository()
            val strike = overlappingStrike()
            strikes.state.value = DataResult.Data(listOf(strike), DataFreshness.Fresh(MutableClock().now(), null))
            val facade = resultsFacade(lifecycle, StandardTestDispatcher(testScheduler), LoadStrikes(strikes))
            runCurrent()
            val card = facade.state.value.journeys.single()
            assertEquals(1, card.warningCount)
            assertFalse(card.confirmedWarning)
            val warning = card.warnings.single()
            assertEquals("mit-strikes:8479", warning.strikeId)
            assertEquals("Ferroviario", warning.sector)
            assertEquals(strike.start.epochSeconds, warning.startEpochSeconds)
            assertEquals(strike.end.epochSeconds, warning.endEpochSeconds)
            // Operator matches the leg operator: supported evidence, LIKELY.
            assertEquals(StrikeImpact.LIKELY, warning.impact)
            assertEquals("MIT", warning.sourceLabel)
            assertEquals("https://scioperi.mit.gov.it/8479", warning.sourceUrl)
            assertFalse(warning.partialContext)
            assertFalse(warning.officiallyConfirmed)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun resultsPartialWarningMarksUnconfirmedContext() = runTest {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            val strikes = FakeStrikeRepository()
            // No operator evidence: temporal overlap alone stays POTENTIAL
            // with explicit partial context.
            val strike = overlappingStrike().copy(operators = emptyList())
            strikes.state.value = DataResult.Data(listOf(strike), DataFreshness.Fresh(MutableClock().now(), null))
            val facade = resultsFacade(lifecycle, StandardTestDispatcher(testScheduler), LoadStrikes(strikes))
            runCurrent()
            val warning = facade.state.value.journeys.single().warnings.single()
            assertEquals(StrikeImpact.POTENTIAL, warning.impact)
            assertTrue(warning.partialContext)
            assertFalse(warning.officiallyConfirmed)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun confirmedWarningProjectionPreservesOfficialConfirmation() {
        val assessment = StrikeImpactAssessment(
            StrikeImpact.CONFIRMED_BY_OPERATOR,
            testStrike.id,
            TemporalEvidence.OVERLAP,
            OperatorEvidence.MATCH,
            GeographyEvidence.NATIONAL_COVERAGE,
            true,
            DataFreshness.Fresh(MutableClock().now(), null),
        )
        val warning = ServiceStrikeWarning(testStrike, assessment).projected()
        assertEquals("mit-strikes:8479", warning.strikeId)
        assertEquals(StrikeImpact.CONFIRMED_BY_OPERATOR, warning.impact)
        assertTrue(warning.officiallyConfirmed)
        assertFalse(warning.partialContext)
        assertEquals("MIT", warning.sourceLabel)
        assertEquals("https://scioperi.mit.gov.it/8479", warning.sourceUrl)
    }

    @Test fun resultsUnknownCoverageWithZeroWarningsStaysVisible() = runTest {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            val strikes = FakeStrikeRepository()
            strikes.state.value = DataResult.Data(emptyList(), DataFreshness.Unknown)
            val facade = resultsFacade(lifecycle, StandardTestDispatcher(testScheduler), LoadStrikes(strikes))
            runCurrent()
            val state = facade.state.value
            val card = state.journeys.single()
            assertTrue(card.warnings.isEmpty())
            assertEquals(0, card.warningCount)
            assertTrue(state.strikesUnknown)
            assertFalse(state.strikesStale)
            assertFalse(state.strikesFailed)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun resultsFailedCoverageWithZeroWarningsIsNotSilent() = runTest {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            val strikes = FakeStrikeRepository()
            strikes.state.value = DataResult.Data(
                emptyList(),
                DataFreshness.Fresh(MutableClock().now(), null),
                DomainFailure.TEMPORARY,
            )
            val facade = resultsFacade(lifecycle, StandardTestDispatcher(testScheduler), LoadStrikes(strikes))
            runCurrent()
            val state = facade.state.value
            assertTrue(state.journeys.single().warnings.isEmpty())
            assertTrue(state.strikesFailed)
            assertFalse(state.strikesUnknown)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun resultsFailedObservationRetainsWarnings() = runTest {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            val strikes = FakeStrikeRepository()
            strikes.state.value = DataResult.Data(
                listOf(overlappingStrike()),
                DataFreshness.Fresh(MutableClock().now(), null),
            )
            val facade = resultsFacade(lifecycle, StandardTestDispatcher(testScheduler), LoadStrikes(strikes))
            runCurrent()
            assertEquals(1, facade.state.value.journeys.single().warnings.size)
            // Coverage observation fails: retained warnings stay with
            // truthful failed/unknown qualifiers (never silently dropped).
            strikes.state.value = DataResult.Failure(DomainFailure.TEMPORARY)
            runCurrent()
            val state = facade.state.value
            assertEquals(1, state.journeys.single().warnings.size)
            assertTrue(state.strikesFailed)
            assertTrue(state.strikesUnknown)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun resultsJourneySourceAttributionNeverInvented() = runTest {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            // The canonical fake provider id is unknown: no source invented.
            val unknown = resultsFacade(lifecycle, StandardTestDispatcher(testScheduler))
            runCurrent()
            assertNull(unknown.state.value.journeys.single().sourceNames)
            // A genuinely known provider is attributed with shared semantics.
            val knownJourney = testJourney.copy(sources = setOf(ProviderId("viaggiatreno")))
            val repository = object : JourneyRepository by FakeJourneyRepository() {
                private val payload = DataResult.Data(
                    JourneySearchResult(
                        listOf(knownJourney),
                        journeyResult.coverage,
                        journeyRequest.departureFrom,
                        journeyRequest.departureUntil,
                    ),
                    DataFreshness.Fresh(MutableClock().now(), null),
                )
                override fun observe(request: JourneySearchRequest) = flowOf(payload)
                override suspend fun search(request: JourneySearchRequest, force: Boolean) = payload
            }
            val known = NativeJourneyResultsPresentation(
                JourneyResultsComponent(
                    DefaultComponentContext(lifecycle),
                    journeyRequest,
                    SearchJourneys(repository),
                    {},
                    StandardTestDispatcher(testScheduler),
                ),
                NativeProjectionOwner(),
            )
            runCurrent()
            assertEquals("ViaggiaTreno", known.state.value.journeys.single().sourceNames)
        } finally {
            lifecycle.destroy()
        }
    }

    private fun stagedTrains(
        fetchedAt: kotlin.time.Instant = MutableClock().now(),
        sourceTimestamp: kotlin.time.Instant? = fetchedAt,
        freshness: (kotlin.time.Instant, kotlin.time.Instant?) -> DataFreshness = { fetched, source ->
            DataFreshness.Fresh(fetched, source)
        },
    ): FakeRealtimeRepositories {
        val trains = FakeRealtimeRepositories()
        val leg = testJourney.legs.single()
        // The run id must stay the fake's candidate id: CorrelateJourneyLeg
        // only accepts a verified id match on number, operator and stops.
        // The "test" provider is genuinely unknown, so no source is named.
        trains.trainState.value = DataResult.Data(
            TrainRun(
                testSummary.copy(operator = leg.operator),
                listOf(
                    TrainStop(testStation, scheduledDeparture = leg.departure),
                    TrainStop(journeyDestination, scheduledArrival = leg.arrival),
                ),
            ),
            freshness(fetchedAt, sourceTimestamp),
        )
        return trains
    }

    private fun detailFacade(
        lifecycle: LifecycleRegistry,
        dispatcher: CoroutineDispatcher,
        trains: FakeRealtimeRepositories,
        favorites: FakeRealtimeRepositories? = null,
    ): NativeJourneyDetailPresentation = NativeJourneyDetailPresentation(
        JourneyDetailComponent(
            DefaultComponentContext(lifecycle),
            journey = testJourney,
            correlate = CorrelateJourneyLeg(trains),
            onTrain = {},
            dispatcher = dispatcher,
            favoritesRepository = favorites,
            trains = trains,
            // Fixed test clock: the fake instants (2026-09-05) must not age
            // against the real system clock during projection.
            clock = MutableClock(),
        ),
        NativeProjectionOwner(),
    )

    @Test fun detailProvenanceProjectsProviderAndTimestamps() = runTest {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            val fetchedAt = MutableClock().now()
            // Equal instants: the shared staleness policy uses
            // min(fetchedAt, sourceTimestamp) + ttl, so an older source
            // timestamp would truthfully project as stale.
            val sourceTimestamp = fetchedAt
            val facade = detailFacade(
                lifecycle,
                StandardTestDispatcher(testScheduler),
                stagedTrains(fetchedAt, sourceTimestamp),
            )
            runCurrent()
            val leg = facade.state.value.legs.single()
            assertTrue(leg.hasTrainAction)
            assertEquals(TrainStatus.RUNNING, leg.realtimeStatus)
            val provenance = assertNotNull(leg.realtimeProvenance)
            // The fake "test" provider is genuinely unknown: no source named.
            assertNull(provenance.providerName)
            assertEquals(fetchedAt.epochSeconds, provenance.fetchedAtEpochSeconds)
            assertEquals(sourceTimestamp.epochSeconds, provenance.sourceTimestampEpochSeconds)
            assertFalse(provenance.stale)
            assertFalse(provenance.degraded)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun detailUnknownFreshnessInventsNoTimestamp() = runTest {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            val trains = stagedTrains { _, _ -> DataFreshness.Unknown }
            val facade = detailFacade(lifecycle, StandardTestDispatcher(testScheduler), trains)
            runCurrent()
            val leg = facade.state.value.legs.single()
            // A non-Fresh snapshot never correlates: unknown stays visible as
            // unknown with no provenance invented and no train action.
            assertEquals(TrainStatus.UNKNOWN, leg.realtimeStatus)
            assertFalse(leg.hasTrainAction)
            assertNull(leg.realtimeProvenance)
            assertFalse(leg.realtimeFailed)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun detailRestoredProvenanceExposesGenuineTimestamps() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val repository = FakeJourneyRepository()
        val fetchedAt = clock.now()
        repository.state.value = DataResult.Data(journeyResult, DataFreshness.Fresh(fetchedAt, null))
        val component = JourneyDetailComponent.restored(
            DefaultComponentContext(lifecycle),
            testJourney.lookupKey(journeyRequest),
            search = SearchJourneys(repository),
            correlate = null,
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = clock,
        )
        lifecycle.resume()
        try {
            runCurrent()
            val facade = NativeJourneyDetailPresentation(component, NativeProjectionOwner())
            runCurrent()
            assertEquals(fetchedAt.epochSeconds, facade.state.value.journeyFetchedAtEpochSeconds)
            assertNull(facade.state.value.journeySourceTimestampEpochSeconds)
            assertFalse(facade.state.value.journeyStale)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun detailLivePathProvenanceInventsNoTimestamp() = runTest {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            val facade = detailFacade(
                lifecycle,
                StandardTestDispatcher(testScheduler),
                stagedTrains(),
            )
            runCurrent()
            // Live path carries Unknown journey freshness: no timestamps.
            assertNull(facade.state.value.journeyFetchedAtEpochSeconds)
            assertNull(facade.state.value.journeySourceTimestampEpochSeconds)
        } finally {
            lifecycle.destroy()
        }
    }

    private fun detailFacadeForSources(
        lifecycle: LifecycleRegistry,
        dispatcher: CoroutineDispatcher,
        sources: Set<ProviderId>,
    ): NativeJourneyDetailPresentation {
        val component = JourneyDetailComponent(
            DefaultComponentContext(lifecycle),
            journey = testJourney.copy(sources = sources),
            correlate = null,
            onTrain = {},
            dispatcher = dispatcher,
            clock = MutableClock(),
        )
        return NativeJourneyDetailPresentation(component, NativeProjectionOwner())
    }

    @Test fun detailJourneySourceNamesShowKnownSingleSource() = runTest {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            val facade = detailFacadeForSources(lifecycle, StandardTestDispatcher(testScheduler), setOf(ProviderId("viaggiatreno")))
            runCurrent()
            assertEquals("ViaggiaTreno", facade.state.value.journeySourceNames)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun detailJourneySourceNamesPreserveMultipleSources() = runTest {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            // Same label semantics as Results: every genuine source, sorted.
            val facade = detailFacadeForSources(
                lifecycle,
                StandardTestDispatcher(testScheduler),
                setOf(ProviderId("trenitalia-journeys"), ProviderId("viaggiatreno")),
            )
            runCurrent()
            assertEquals("Trenitalia, ViaggiaTreno", facade.state.value.journeySourceNames)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun detailJourneySourceNamesNeverInferredFromOperator() = runTest {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            // The leg operator (Trenitalia) is known but the provider is
            // genuinely unknown: no source is invented from the operator.
            val unknown = detailFacadeForSources(lifecycle, StandardTestDispatcher(testScheduler), setOf(ProviderId("test")))
            runCurrent()
            assertNull(unknown.state.value.journeySourceNames)
            val empty = detailFacadeForSources(lifecycle, StandardTestDispatcher(testScheduler), emptySet())
            runCurrent()
            assertNull(empty.state.value.journeySourceNames)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun detailFavoriteFailureSurvivesProjection() = runTest {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            val favorites = FakeRealtimeRepositories()
            favorites.favoriteFailure = IllegalStateException("boom")
            val facade = detailFacade(
                lifecycle,
                StandardTestDispatcher(testScheduler),
                FakeRealtimeRepositories(),
                favorites,
            )
            runCurrent()
            val favorite = assertNotNull(facade.favoriteRoute?.value)
            assertTrue(favorite.available)
            assertFalse(favorite.failed)
            facade.toggleFavoriteRoute()
            runCurrent()
            // Content stays visible with the failure flagged; nothing
            // optimistic is invented.
            val failed = assertNotNull(facade.favoriteRoute?.value)
            assertTrue(failed.available)
            assertTrue(failed.failed)
            assertFalse(failed.favorite)
            assertEquals(1, facade.state.value.legs.size)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun shellCarriesJourneyFacadesAcrossResultsDetailAndBack() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val root = DefaultRootComponent(
            DefaultComponentContext(lifecycle),
            AppComponentFactory(
                repositories, repositories, repositories, repositories,
                MutableStateFlow(false), StandardTestDispatcher(testScheduler),
                journeyRepository = FakeJourneyRepository(),
            ),
        )
        lifecycle.resume()
        val shell = createShellPresentation(root)
        try {
            val main = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
            val journey = assertIs<MainComponent.Child.Journey>(
                main.pages.value.items[MainTab.Journey.ordinal].instance,
            ).component
            val form = assertIs<JourneyTabComponent.Child.Search>(journey.stack.value.active.instance).component
            form.stationText(true, "Roma"); advanceTimeBy(276); runCurrent(); form.select(testStation)
            form.stationText(false, "Milano"); advanceTimeBy(276); runCurrent(); form.select(journeyDestination)
            form.date("2026-09-05"); form.time("10:00"); form.search(); runCurrent()
            assertIs<JourneyTabComponent.Child.Results>(journey.stack.value.active.instance)
            main.select(MainTab.Journey); runCurrent()

            val resultsEntry = shell.state.value.path.singleOrNull { it.destination == NativeDestination.JourneyResults }
            val resultsFacade = assertNotNull(resultsEntry?.journeyResults)
            assertNull(resultsEntry?.journeyDetail)
            assertNotNull(shell.journeyResults(resultsEntry!!.identity))
            assertEquals(testStation.name, resultsFacade.state.value.originName)
            assertEquals(1, resultsFacade.state.value.journeys.size)

            // Facade selection drives the SAME shared stack to Detail.
            resultsFacade.selectJourney(0); runCurrent()
            assertIs<JourneyTabComponent.Child.Detail>(journey.stack.value.active.instance)
            val detailEntry = shell.state.value.path.singleOrNull { it.destination == NativeDestination.JourneyDetail }
            val detailFacade = assertNotNull(detailEntry?.journeyDetail)
            assertNotNull(shell.journeyDetail(detailEntry!!.identity))
            assertEquals(testStation.name, detailFacade.state.value.originName)
            assertEquals(1, detailFacade.state.value.legs.size)

            // Back returns to the same Results facade instance with content intact.
            shell.back(); runCurrent()
            assertIs<JourneyTabComponent.Child.Results>(journey.stack.value.active.instance)
            val backEntry = shell.state.value.path.singleOrNull { it.destination == NativeDestination.JourneyResults }
            assertNotNull(backEntry?.journeyResults)
            assertEquals(resultsEntry.identity, backEntry!!.identity)
            assertEquals(1, backEntry.journeyResults!!.state.value.journeys.size)
        } finally {
            shell.close()
            lifecycle.destroy()
        }
    }

    @Test fun shellJourneyEntriesUseNativeBackControl() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val root = DefaultRootComponent(
            DefaultComponentContext(lifecycle),
            AppComponentFactory(
                repositories, repositories, repositories, repositories,
                MutableStateFlow(false), StandardTestDispatcher(testScheduler),
                journeyRepository = FakeJourneyRepository(),
            ),
        )
        lifecycle.resume()
        val shell = createShellPresentation(root)
        try {
            val main = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
            val journey = assertIs<MainComponent.Child.Journey>(
                main.pages.value.items[MainTab.Journey.ordinal].instance,
            ).component
            val form = assertIs<JourneyTabComponent.Child.Search>(journey.stack.value.active.instance).component
            form.stationText(true, "Roma"); advanceTimeBy(276); runCurrent(); form.select(testStation)
            form.stationText(false, "Milano"); advanceTimeBy(276); runCurrent(); form.select(journeyDestination)
            form.date("2026-09-05"); form.time("10:00"); form.search(); runCurrent()
            main.select(MainTab.Journey); runCurrent()
            // Native destinations use platform shell Back, which drives
            // shared Decompose Back.
            val results = shell.state.value.path.single { it.destination == NativeDestination.JourneyResults }
            assertNotNull(results.journeyResults)
            shell.journeyResults(results.identity)!!.selectJourney(0); runCurrent()
            val detail = shell.state.value.path.single { it.destination == NativeDestination.JourneyDetail }
            assertNotNull(detail.journeyDetail)
            // Native/system Back returns Detail -> Results -> Home.
            shell.back(); runCurrent()
            assertEquals(NativeDestination.JourneyResults, shell.state.value.active.destination)
            shell.back(); runCurrent()
            assertEquals(NativeDestination.Home, shell.state.value.active.destination)
            assertTrue(shell.state.value.path.isEmpty())
        } finally {
            shell.close()
            lifecycle.destroy()
        }
    }
}
