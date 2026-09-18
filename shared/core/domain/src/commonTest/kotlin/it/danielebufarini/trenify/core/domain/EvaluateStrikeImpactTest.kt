package it.danielebufarini.trenify.core.domain

import it.danielebufarini.trenify.core.model.*
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class EvaluateStrikeImpactTest {
    private val evaluate = EvaluateStrikeImpact()
    private val runId = TrainRunId(
        ProviderId("test"),
        TrainNumber("123"),
        ExternalStationRef("roma"),
        LocalDate.parse("2026-09-08"),
    )
    private val station = Station(StationId("roma"), "Roma Termini")
    private val testRun = TrainRun(TrainRunSummary(runId, station, "Milano Centrale"), emptyList())
    private val testStrike = Strike(
        StrikeId("mit-strikes:8479"),
        "8479",
        Instant.parse("2026-09-07T19:18:00Z"),
        Instant.parse("2026-09-08T19:00:00Z"),
        "Ferroviario",
        listOf("ORSA Ferrovie"),
        "Personale Trenitalia",
        listOf(Operator("Trenitalia")),
        StrikeGeography(StrikeRelevance.REGIONAL, listOf("Piemonte")),
        "24 ore",
        StrikeStatus.SCHEDULED,
        null,
        StrikeSource(ProviderId("mit-strikes"), "MIT", "https://scioperi.mit.gov.it/8479"),
        null,
        "fixture",
    )
    private val overlappingTrain = testRun.copy(
        summary = testRun.summary.copy(
            scheduledTime = Instant.parse("2026-09-08T08:00:00Z"),
            operator = Operator("Trenitalia"),
        ),
    )

    @Test
    fun outsideIntervalOrRevokedStrikeHasNoImpact() {
        val outside = overlappingTrain.copy(
            summary = overlappingTrain.summary.copy(scheduledTime = Instant.parse("2026-09-09T08:00:00Z")),
        )
        assertEquals(StrikeImpact.NONE, evaluate(testStrike, outside))
        assertEquals(StrikeImpact.NONE, evaluate(testStrike.copy(status = StrikeStatus.REVOKED), overlappingTrain))
    }

    @Test
    fun temporalOverlapAloneIsOnlyPotential() {
        val generic = testStrike.copy(
            operators = emptyList(),
            geography = StrikeGeography(StrikeRelevance.LOCAL),
        )
        assertEquals(StrikeImpact.POTENTIAL, evaluate(generic, overlappingTrain))
    }

    @Test
    fun matchingOperatorOrNationalScopeMakesImpactLikely() {
        assertEquals(StrikeImpact.LIKELY, evaluate(testStrike, overlappingTrain))
        assertEquals(
            StrikeImpact.LIKELY,
            evaluate(
                testStrike.copy(operators = emptyList(), geography = StrikeGeography(StrikeRelevance.NATIONAL)),
                overlappingTrain,
            ),
        )
    }

    @Test
    fun confirmationRequiresVerifiedOfficialEvidenceApplicableToTrainStrikeAndFreshData() {
        val fresh = DataFreshness.Fresh(Instant.parse("2026-09-08T08:00:00Z"), null)
        // HTTPS alone is never evidence: an unverified confirmation never
        // promotes impact, even on fresh data with matching ids.
        assertEquals(
            StrikeImpact.LIKELY,
            evaluate(
                testStrike,
                overlappingTrain,
                OperatorStrikeConfirmation(testStrike.id, overlappingTrain.summary.id, "https://www.trenitalia.com/info"),
                fresh,
            ),
        )
        assertEquals(
            StrikeImpact.LIKELY,
            evaluate(
                testStrike,
                overlappingTrain,
                OperatorStrikeConfirmation(
                    testStrike.id,
                    overlappingTrain.summary.id,
                    "https://scioperi.mit.gov.it/8479",
                    verified = true,
                ),
            ),
        )
        assertEquals(
            StrikeImpact.CONFIRMED_BY_OPERATOR,
            evaluate(
                testStrike,
                overlappingTrain,
                OperatorStrikeConfirmation(
                    testStrike.id,
                    overlappingTrain.summary.id,
                    "https://scioperi.mit.gov.it/8479",
                    verified = true,
                ),
                fresh,
            ),
        )
        assertEquals(
            StrikeImpact.LIKELY,
            evaluate(
                testStrike,
                overlappingTrain,
                OperatorStrikeConfirmation(testStrike.id, overlappingTrain.summary.id, "http://untrusted.example/info"),
                fresh,
            ),
        )
    }
}
