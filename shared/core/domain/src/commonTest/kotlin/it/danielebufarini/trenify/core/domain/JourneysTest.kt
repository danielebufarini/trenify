package it.danielebufarini.trenify.core.domain

import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.testing.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class JourneysTest {
    private val regionalDate = LocalDate.parse("2026-09-18")
    private val previousRegionalDate = LocalDate.parse("2026-09-17")
    private val regionalDeparture = Instant.parse("2026-09-18T06:27:00Z")
    private val regionalArrival = Instant.parse("2026-09-18T06:43:00Z")
    private val monza = Station(StationId("station:monza"), "Monza")
    private val milano = Station(StationId("station:milano centrale"), "Milano Centrale")
    private val tirano = Station(StationId("station:tirano"), "Tirano")

    private fun regionalLeg(
        origin: Station = monza,
        destination: Station = milano,
        departure: Instant = regionalDeparture,
        arrival: Instant = regionalArrival,
        operator: Operator? = null,
    ) = JourneyLeg(
        origin,
        destination,
        departure,
        arrival,
        TrainNumber("2815"),
        "Regionale",
        operator,
    )

    private fun regionalId(
        origin: String = "S01440",
        serviceDate: LocalDate = regionalDate,
    ) = TrainRunId(
        ProviderId("viaggiatreno"),
        TrainNumber("2815"),
        ExternalStationRef(origin),
        serviceDate,
    )

    private fun regionalRun(
        id: TrainRunId = regionalId(),
        runOrigin: Station = tirano,
        boarding: Station = monza,
        alighting: Station = milano,
        scheduledBoarding: Instant = regionalDeparture,
        scheduledAlighting: Instant = regionalArrival,
        operator: Operator? = Operator("Trenord"),
    ) = TrainRun(
        TrainRunSummary(
            id,
            runOrigin,
            alighting.name,
            status = TrainStatus.RUNNING,
            delayMinutes = 7,
            operator = operator,
            category = TrainCategory.REG,
        ),
        listOf(
            TrainStop(runOrigin, scheduledDeparture = Instant.parse("2026-09-18T04:10:00Z")),
            TrainStop(Station(StationId("station:lecco"), "Lecco")),
            TrainStop(
                boarding,
                scheduledDeparture = scheduledBoarding,
                actualDeparture = scheduledBoarding + 7.minutes,
            ),
            TrainStop(
                alighting,
                scheduledArrival = scheduledAlighting,
                actualArrival = scheduledAlighting + 7.minutes,
            ),
        ),
    )

    @Test fun validationRejectsSameStationAndUseCasePreservesRepositoryResultAndWarnings() = runTest {
        val repository = FakeJourneyRepository()
        val search = SearchJourneys(repository)
        assertEquals(DataResult.Failure(DomainFailure.INVALID_REQUEST), search(journeyRequest.copy(destination = testStation)))
        assertEquals(0, repository.calls)
        repository.state.value = DataResult.Data(journeyResult, DataFreshness.Unknown, DomainFailure.TEMPORARY)
        assertEquals(repository.state.value, search(journeyRequest))
        assertEquals(1, repository.calls)
    }

    @Test fun sortingAndTransfersUseCompleteScheduledLegs() {
        val slower = testJourney.copy(legs = testJourney.legs.map { it.copy(arrival = it.arrival + 2.hours) })
        assertEquals(listOf(testJourney, slower), listOf(slower, testJourney).sortedBy(JourneySort.DURATION))
        assertEquals(listOf(testJourney, slower), listOf(slower, testJourney).sortedBy(JourneySort.ARRIVAL))
        assertEquals(0, testJourney.changes)
        assertFailsWith<IllegalArgumentException> { Journey(testJourney.legs + testJourney.legs, emptySet()) }
    }

    @Test fun correlationRequiresUniqueRunWithVerifiedOperatorAndBothStopTimes() = runTest {
        val repository = FakeRealtimeRepositories()
        val leg = testJourney.legs.single()
        val run = TrainRun(testSummary.copy(operator = Operator("Trenitalia")), listOf(
            TrainStop(testStation, scheduledDeparture = leg.departure), TrainStop(journeyDestination, scheduledArrival = leg.arrival)))
        repository.trainState.value = DataResult.Data(run, DataFreshness.Fresh(MutableClock().now(), null))
        val correlate = CorrelateJourneyLeg(repository)
        assertEquals(testRunId, correlate(leg))
        assertNull(correlate(leg.copy(operator = Operator("Italo"))))
        assertNull(correlate(leg.copy(departure = leg.departure - 1.hours)))
        assertNull(correlate(leg.copy(number = null)))
        repository.trainState.value = DataResult.Data(run, DataFreshness.Unknown)
        assertNull(correlate(leg))
        repository.trainState.value = DataResult.Data(
            run,
            DataFreshness.Stale(MutableClock().now(), 2.hours, MutableClock().now() - 2.hours),
        )
        assertEquals(testRunId, correlate(leg))
        repository.trainState.value = DataResult.Data(
            run,
            DataFreshness.Stale(MutableClock().now(), 2.hours, MutableClock().now() - 2.hours),
            DomainFailure.TEMPORARY,
        )
        assertNull(correlate(leg))
    }

    @Test fun ambiguousCandidatesAndUnavailableCandidateCoverageNeverGuess() = runTest {
        val base = FakeRealtimeRepositories()
        val other = testRunId.copy(origin = ExternalStationRef("different-origin"))
        val leg = testJourney.legs.single()
        val repository = object : TrainRepository by base {
            override suspend fun refreshTrain(id: TrainRunId, force: Boolean) = DataResult.Data(
                TrainRun(testSummary.copy(id = id, operator = leg.operator), listOf(
                    TrainStop(testStation, scheduledDeparture = leg.departure), TrainStop(journeyDestination, scheduledArrival = leg.arrival))),
                DataFreshness.Fresh(MutableClock().now(), null))
        }
        base.runsResult = DataResult.Data(listOf(testSummary, testSummary.copy(id = other)), DataFreshness.Unknown)
        assertNull(CorrelateJourneyLeg(repository)(leg))
        base.runsResult = DataResult.Failure(DomainFailure.OFFLINE)
        assertNull(CorrelateJourneyLeg(repository)(leg))
    }

    @Test fun unknownOperatorCanCorrelateAUniqueExactIntermediateSegment() = runTest {
        val leg = regionalLeg()
        val run = regionalRun()
        val base = FakeRealtimeRepositories().apply {
            runsResult = DataResult.Data(listOf(run.summary), DataFreshness.Unknown)
            trainState.value = DataResult.Data(run, DataFreshness.Fresh(MutableClock().now(), null))
        }
        var candidateQueries = 0
        val repository = object : TrainRepository by base {
            override suspend fun findTrainRuns(number: TrainNumber, date: LocalDate?): DataResult<List<TrainRunSummary>> {
                candidateQueries++
                return base.findTrainRuns(number, date)
            }
        }

        val correlated = CorrelateJourneyLeg(repository)(leg)
        assertEquals(2, candidateQueries)
        assertEquals(run.summary.id, correlated)
        assertNotEquals(run.stops[2].scheduledDeparture, run.stops[2].actualDeparture)
        assertNotEquals(leg.origin, run.summary.origin)
    }

    @Test fun knownOperatorRemainsMandatoryWhileUnknownOperatorDoesNotGuessIt() = runTest {
        val matching = regionalRun(operator = Operator("Trenord"))
        val base = FakeRealtimeRepositories().apply {
            runsResult = DataResult.Data(listOf(matching.summary), DataFreshness.Unknown)
            trainState.value = DataResult.Data(matching, DataFreshness.Fresh(MutableClock().now(), null))
        }
        val correlate = CorrelateJourneyLeg(base)

        assertEquals(matching.summary.id, correlate(regionalLeg(operator = Operator(" trenord "))))
        assertNull(correlate(regionalLeg(operator = Operator("Trenitalia"))))
        val unknownCandidateOperator = regionalRun(operator = null)
        base.runsResult = DataResult.Data(listOf(unknownCandidateOperator.summary), DataFreshness.Unknown)
        base.trainState.value = DataResult.Data(
            unknownCandidateOperator,
            DataFreshness.Fresh(MutableClock().now(), null),
        )
        assertNull(correlate(regionalLeg(operator = Operator("Trenord"))))
        assertEquals(matching.summary.id, correlate(regionalLeg(operator = null)))
    }

    @Test fun unknownOperatorRejectsAmbiguousExactCandidatesAcrossOriginsOperatorsAndDates() = runTest {
        val current = regionalRun()
        val previousDate = regionalRun(
            id = regionalId(origin = "S00001", serviceDate = previousRegionalDate),
            runOrigin = Station(StationId("station:chiavenna"), "Chiavenna"),
            operator = Operator("Trenitalia"),
        )
        val base = FakeRealtimeRepositories()
        val details = mapOf(current.summary.id to current, previousDate.summary.id to previousDate)
        val repository = object : TrainRepository by base {
            override suspend fun findTrainRuns(number: TrainNumber, date: LocalDate?) = DataResult.Data(
                details.values.filter { it.summary.id.serviceDate == date }.map(TrainRun::summary),
                DataFreshness.Unknown,
            )

            override suspend fun refreshTrain(id: TrainRunId, force: Boolean) = DataResult.Data(
                requireNotNull(details[id]),
                DataFreshness.Fresh(MutableClock().now(), null),
            )
        }

        assertNull(CorrelateJourneyLeg(repository)(regionalLeg()))
    }

    @Test fun exactSegmentRequiresBothStationsInOrderAndBothScheduledTimes() = runTest {
        val wrongMonza = Station(StationId("station:monza sobborghi"), "Monza Sobborghi")
        val wrongMilano = Station(StationId("station:milano porta garibaldi"), "Milano Porta Garibaldi")
        val mismatches = listOf(
            regionalRun(boarding = wrongMonza),
            regionalRun(alighting = wrongMilano),
            regionalRun(scheduledBoarding = regionalDeparture + 1.minutes),
            regionalRun(scheduledAlighting = regionalArrival + 1.minutes),
        )

        mismatches.forEachIndexed { index, run ->
            val candidate = run.copy(summary = run.summary.copy(id = regionalId(origin = "mismatch-$index")))
            val base = FakeRealtimeRepositories().apply {
                runsResult = DataResult.Data(listOf(candidate.summary), DataFreshness.Unknown)
                trainState.value = DataResult.Data(candidate, DataFreshness.Fresh(MutableClock().now(), null))
            }
            assertNull(CorrelateJourneyLeg(base)(regionalLeg()), "mismatch $index must not correlate")
        }
    }

    @Test fun sameNumberCollisionsDoNotHideTheSingleExactSegment() = runTest {
        val exact = regionalRun()
        val wrongRoute = regionalRun(
            id = regionalId(origin = "wrong-route"),
            boarding = Station(StationId("station:como"), "Como S. Giovanni"),
        )
        val wrongTimes = regionalRun(
            id = regionalId(origin = "wrong-times", serviceDate = previousRegionalDate),
            scheduledBoarding = regionalDeparture - 1.hours,
            scheduledAlighting = regionalArrival - 1.hours,
        )
        val runs = listOf(wrongRoute, wrongTimes, exact)
        val details = runs.associateBy { it.summary.id }
        val base = FakeRealtimeRepositories()
        val repository = object : TrainRepository by base {
            override suspend fun findTrainRuns(number: TrainNumber, date: LocalDate?) = DataResult.Data(
                runs.filter { it.summary.id.serviceDate == date }.map(TrainRun::summary),
                DataFreshness.Unknown,
            )

            override suspend fun refreshTrain(id: TrainRunId, force: Boolean) = DataResult.Data(
                requireNotNull(details[id]),
                DataFreshness.Fresh(MutableClock().now(), null),
            )
        }

        assertEquals(exact.summary.id, CorrelateJourneyLeg(repository)(regionalLeg()))
    }

    @Test fun correlationPropagatesCancellation() = runTest {
        val base = FakeRealtimeRepositories()
        val repository = object : TrainRepository by base {
            override suspend fun findTrainRuns(number: TrainNumber, date: LocalDate?): DataResult<List<TrainRunSummary>> =
                throw CancellationException("cancel correlation")
        }

        assertFailsWith<CancellationException> { CorrelateJourneyLeg(repository)(regionalLeg()) }
    }
}
