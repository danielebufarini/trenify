package it.danielebufarini.trenify.core.domain

import it.danielebufarini.trenify.core.model.*
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * T7.12-B neutral interval/context strike-impact semantics: true interval
 * overlap (never timestamp membership), explicit endpoint/incomplete-time
 * rules, Europe/Rome DST cases, operator/geography evidence, revocation,
 * staleness and the never-cancelled guarantee.
 */
class ServiceStrikeImpactTest {
    private val evaluate = EvaluateServiceStrikeImpact()

    private val strikeStart = Instant.parse("2026-09-08T04:00:00Z")
    private val strikeEnd = Instant.parse("2026-09-08T12:00:00Z")
    private val strike = Strike(
        StrikeId("mit-strikes:1"),
        "1",
        strikeStart,
        strikeEnd,
        "Ferroviario",
        listOf("ORSA Ferrovie"),
        "Personale Trenitalia",
        listOf(Operator("Trenitalia")),
        StrikeGeography(StrikeRelevance.REGIONAL, regions = listOf("Lazio")),
        "8 ore",
        StrikeStatus.SCHEDULED,
        null,
        StrikeSource(ProviderId("mit-strikes"), "MIT", "https://scioperi.mit.gov.it/1"),
        null,
        "fingerprint",
    )
    private val fresh = DataFreshness.Fresh(strikeStart, null)
    private val stale = DataFreshness.Stale(strikeStart, 2.hours, null)

    private fun context(departure: Instant?, arrival: Instant?, operator: Operator? = Operator("Trenitalia")) =
        ServiceStrikeContext(departure, arrival, operator)

    @Test
    fun noOverlapYieldsNone() {
        val before = context(strikeStart - 3.hours, strikeStart - 1.hours)
        val after = context(strikeEnd + 1.hours, strikeEnd + 3.hours)
        assertEquals(StrikeImpact.NONE, evaluate(before, strike, fresh).impact)
        assertEquals(TemporalEvidence.NO_OVERLAP, evaluate(before, strike, fresh).temporal)
        assertEquals(StrikeImpact.NONE, evaluate(after, strike, fresh).impact)
    }

    @Test
    fun partialOverlapYieldsLikelyForKnownOperator() {
        val startsBefore = context(strikeStart - 2.hours, strikeStart + 1.hours)
        val endsAfter = context(strikeEnd - 1.hours, strikeEnd + 2.hours)
        assertEquals(StrikeImpact.LIKELY, evaluate(startsBefore, strike, fresh).impact)
        assertEquals(TemporalEvidence.OVERLAP, evaluate(endsAfter, strike, fresh).temporal)
        assertEquals(StrikeImpact.LIKELY, evaluate(endsAfter, strike, fresh).impact)
    }

    @Test
    fun journeyIntervalFullySpanningStrikeIsDetected() {
        // Neither endpoint lies "inside" the strike in the old
        // timestamp-membership sense, yet the service runs through it.
        val spanning = context(strikeStart - 4.hours, strikeEnd + 4.hours)
        val assessment = evaluate(spanning, strike, fresh)
        assertEquals(TemporalEvidence.OVERLAP, assessment.temporal)
        assertEquals(StrikeImpact.LIKELY, assessment.impact)
    }

    @Test
    fun strikeIntervalFullySpanningJourneyIsDetected() {
        val inside = context(strikeStart + 1.hours, strikeEnd - 1.hours)
        val assessment = evaluate(inside, strike, fresh)
        assertEquals(TemporalEvidence.OVERLAP, assessment.temporal)
        assertEquals(StrikeImpact.LIKELY, assessment.impact)
    }

    @Test
    fun exactBoundaryInstantCountsAsOverlapConservatively() {
        val touchingStart = context(strikeStart - 2.hours, strikeStart)
        val touchingEnd = context(strikeEnd, strikeEnd + 2.hours)
        assertEquals(TemporalEvidence.OVERLAP, evaluate(touchingStart, strike, fresh).temporal)
        assertEquals(TemporalEvidence.OVERLAP, evaluate(touchingEnd, strike, fresh).temporal)
        // Conservative, never certainty of cancellation.
        assertTrue(evaluate(touchingStart, strike, fresh).impact != StrikeImpact.CONFIRMED_BY_OPERATOR)
    }

    @Test
    fun singleKnownTimeIsAPointCheck() {
        val atStart = context(strikeStart, null)
        val outside = context(strikeEnd + 1.hours, null)
        val arrivalOnly = context(null, strikeStart + 1.hours)
        assertEquals(TemporalEvidence.OVERLAP, evaluate(atStart, strike, fresh).temporal)
        assertEquals(StrikeImpact.LIKELY, evaluate(atStart, strike, fresh).impact)
        assertEquals(TemporalEvidence.NO_OVERLAP, evaluate(outside, strike, fresh).temporal)
        assertEquals(TemporalEvidence.OVERLAP, evaluate(arrivalOnly, strike, fresh).temporal)
    }

    @Test
    fun incompleteIntervalYieldsNoneWithIncompleteEvidence() {
        val assessment = evaluate(context(null, null), strike, fresh)
        assertEquals(TemporalEvidence.INCOMPLETE, assessment.temporal)
        assertEquals(StrikeImpact.NONE, assessment.impact)
    }

    @Test
    fun overnightJourneyOverlapsCorrectly() {
        val nightStrike = strike.copy(
            start = Instant.parse("2026-09-08T20:00:00Z"),
            end = Instant.parse("2026-09-09T04:00:00Z"),
        )
        val overnight = context(Instant.parse("2026-09-08T21:30:00Z"), Instant.parse("2026-09-09T02:15:00Z"))
        assertEquals(StrikeImpact.LIKELY, evaluate(overnight, nightStrike, fresh).impact)
        val eveningBefore = context(Instant.parse("2026-09-08T17:00:00Z"), Instant.parse("2026-09-08T19:30:00Z"))
        assertEquals(StrikeImpact.NONE, evaluate(eveningBefore, nightStrike, fresh).impact)
    }

    @Test
    fun autumnDstFallbackIntervalsUseTrueInstants() {
        // Europe/Rome falls back on 2026-10-25: 03:00+02:00 becomes 03:00+01:00.
        // A journey from 01:30 to the second 03:30 local spans 3 elapsed hours.
        val departure = LocalDateTime(2026, 10, 25, 1, 30).toInstant(RailwayTime.zone)
        val arrival = LocalDateTime(2026, 10, 25, 3, 30).toInstant(RailwayTime.zone)
        assertEquals(3.hours, arrival - departure)
        val dstStrike = strike.copy(start = departure + 1.hours, end = departure + 2.hours)
        assertEquals(StrikeImpact.LIKELY, evaluate(context(departure, arrival), dstStrike, fresh).impact)
        val disjoint = strike.copy(start = arrival + 1.hours, end = arrival + 2.hours)
        assertEquals(StrikeImpact.NONE, evaluate(context(departure, arrival), disjoint, fresh).impact)
    }

    @Test
    fun springDstForwardIntervalsUseTrueInstants() {
        // Europe/Rome springs forward on 2026-03-29: 02:00–02:59 local does not exist.
        val departure = LocalDateTime(2026, 3, 29, 1, 30).toInstant(RailwayTime.zone)
        val arrival = LocalDateTime(2026, 3, 29, 3, 30).toInstant(RailwayTime.zone)
        assertEquals(1.hours, arrival - departure)
        val dstStrike = strike.copy(start = departure, end = arrival)
        assertEquals(StrikeImpact.LIKELY, evaluate(context(departure, arrival), dstStrike, fresh).impact)
    }

    @Test
    fun knownOperatorMismatchStaysPotentialAndUnknownOperatorIsConservative() {
        val mismatch = context(strikeStart + 1.hours, strikeEnd - 1.hours, Operator("Italo"))
        val mismatchAssessment = evaluate(mismatch, strike, fresh)
        assertEquals(OperatorEvidence.MISMATCH, mismatchAssessment.operator)
        assertEquals(StrikeImpact.POTENTIAL, mismatchAssessment.impact)
        val unknown = context(strikeStart + 1.hours, strikeEnd - 1.hours, null)
        assertEquals(OperatorEvidence.UNKNOWN, evaluate(unknown, strike, fresh).operator)
        assertEquals(StrikeImpact.POTENTIAL, evaluate(unknown, strike, fresh).impact)
        // A strike naming no operator cannot match or mismatch.
        val anonymousStrike = strike.copy(operators = emptyList())
        val anonymous = evaluate(context(strikeStart + 1.hours, strikeEnd - 1.hours), anonymousStrike, fresh)
        assertEquals(OperatorEvidence.UNKNOWN, anonymous.operator)
        assertEquals(StrikeImpact.POTENTIAL, anonymous.impact)
    }

    @Test
    fun nationalScopeIsLikelyButUnknownGeographyStaysUnknown() {
        val national = strike.copy(operators = emptyList(), geography = StrikeGeography(StrikeRelevance.NATIONAL))
        val nationalAssessment = evaluate(context(strikeStart + 1.hours, strikeEnd - 1.hours, null), national, fresh)
        assertEquals(GeographyEvidence.NATIONAL_COVERAGE, nationalAssessment.geography)
        assertEquals(StrikeImpact.LIKELY, nationalAssessment.impact)
        val regional = strike.copy(operators = emptyList())
        val regionalAssessment = evaluate(context(strikeStart + 1.hours, strikeEnd - 1.hours, null), regional, fresh)
        assertEquals(GeographyEvidence.UNKNOWN, regionalAssessment.geography)
        assertEquals(StrikeImpact.POTENTIAL, regionalAssessment.impact)
    }

    @Test
    fun stationNameSubstringMatchingIsNeverGeographyEvidence() {
        // The old implementation promoted this to LIKELY because the region
        // name appears inside a station name. Unknown geography stays unknown.
        val tricky = strike.copy(operators = emptyList(), geography = StrikeGeography(StrikeRelevance.REGIONAL, regions = listOf("Roma")))
        val assessment = evaluate(context(strikeStart + 1.hours, strikeEnd - 1.hours, null), tricky, fresh)
        assertEquals(GeographyEvidence.UNKNOWN, assessment.geography)
        assertEquals(StrikeImpact.POTENTIAL, assessment.impact)
    }

    @Test
    fun revokedCompletedAndNonRailwayStrikesHaveNoImpact() {
        val inside = context(strikeStart + 1.hours, strikeEnd - 1.hours)
        assertEquals(StrikeImpact.NONE, evaluate(inside, strike.copy(status = StrikeStatus.REVOKED), fresh).impact)
        assertEquals(StrikeImpact.NONE, evaluate(inside, strike.copy(status = StrikeStatus.COMPLETED), fresh).impact)
        assertEquals(StrikeImpact.NONE, evaluate(inside, strike.copy(sector = "Aereo", workforce = null, operators = emptyList()), fresh).impact)
    }

    @Test
    fun staleWarningNeverImpliesFreshOperatorConfirmation() {
        val confirmation = OperatorStrikeConfirmation(
            strike.id,
            TrainRunId(
                ProviderId("test"),
                TrainNumber("123"),
                ExternalStationRef("roma"),
                kotlinx.datetime.LocalDate(2026, 9, 8),
            ),
            "https://scioperi.mit.gov.it/1",
            verified = true,
        )
        val journeyContext = context(strikeStart + 1.hours, strikeEnd - 1.hours).copy(trainRunId = confirmation.trainRunId)
        val confirmed = evaluate(journeyContext, strike, fresh, confirmation)
        assertEquals(StrikeImpact.CONFIRMED_BY_OPERATOR, confirmed.impact)
        assertTrue(confirmed.officiallyConfirmed)
        val staleAssessment = evaluate(journeyContext, strike, stale, confirmation)
        assertEquals(StrikeImpact.LIKELY, staleAssessment.impact)
        assertEquals(false, staleAssessment.officiallyConfirmed)
        val unknownAssessment = evaluate(journeyContext, strike, DataFreshness.Unknown, confirmation)
        assertEquals(StrikeImpact.LIKELY, unknownAssessment.impact)
    }

    @Test
    fun impactAssessmentNeverMutatesServiceStatus() {
        val running = TrainRun(
            TrainRunSummary(
                TrainRunId(
                    ProviderId("test"),
                    TrainNumber("123"),
                    ExternalStationRef("roma"),
                    kotlinx.datetime.LocalDate(2026, 9, 8),
                ),
                Station(StationId("roma"), "Roma Termini"),
                "Milano Centrale",
                scheduledDeparture = strikeStart + 1.hours,
                scheduledArrival = strikeEnd - 1.hours,
                status = TrainStatus.RUNNING,
                operator = Operator("Trenitalia"),
            ),
        )
        val assessment = evaluate(running.strikeContext(), strike, fresh)
        assertTrue(assessment.impact == StrikeImpact.LIKELY)
        assertEquals(TrainStatus.RUNNING, running.summary.status)
    }

    @Test
    fun warningsKeepOnlyOverlappingStrikesWithIdentityIntervalSourceAndFreshness() {
        val inside = context(strikeStart + 1.hours, strikeEnd - 1.hours)
        val outside = strike.copy(
            id = StrikeId("mit-strikes:2"),
            start = strikeEnd + 1.hours,
            end = strikeEnd + 3.hours,
        )
        val warnings = evaluate.warnings(inside, listOf(strike, outside, strike.copy(status = StrikeStatus.REVOKED)), fresh)
        assertEquals(listOf(strike.id), warnings.map { it.strike.id })
        val warning = warnings.single()
        assertEquals(strikeStart, warning.strike.start)
        assertEquals(strikeEnd, warning.strike.end)
        assertEquals("https://scioperi.mit.gov.it/1", warning.strike.source.url)
        assertEquals(fresh, warning.assessment.freshness)
    }
}
