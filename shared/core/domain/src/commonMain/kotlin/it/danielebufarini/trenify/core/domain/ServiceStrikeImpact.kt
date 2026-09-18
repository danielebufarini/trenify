package it.danielebufarini.trenify.core.domain

import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.JourneyLeg
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.Strike
import it.danielebufarini.trenify.core.model.StrikeId
import it.danielebufarini.trenify.core.model.StrikeRelevance
import it.danielebufarini.trenify.core.model.StrikeStatus
import it.danielebufarini.trenify.core.model.strikeIntervalsOverlap
import it.danielebufarini.trenify.core.model.TrainRun
import it.danielebufarini.trenify.core.model.TrainRunId
import kotlin.time.Instant

/**
 * Provider-neutral scheduled-service context for strike-impact evaluation
 * (T7.12-B/C).
 *
 * This is the neutral interval/context input journey and monitoring flows
 * evaluate against cached strikes: plain instants plus a normalized operator,
 * never a realtime correlation result. A warning never requires a successful
 * realtime lookup, and evaluating one never mutates journey/train service
 * status.
 */
data class ServiceStrikeContext(
    val departure: Instant?,
    val arrival: Instant?,
    val operator: Operator?,
    val trainRunId: TrainRunId? = null,
)

/** Scheduled journey-leg interval with its declared operator, directly. */
fun JourneyLeg.strikeContext(): ServiceStrikeContext = ServiceStrikeContext(departure, arrival, operator)

/**
 * Monitored-run context from an already persisted/current local snapshot
 * (T7.12-C).
 *
 * Scheduled service times position the train; actuals are only a fallback
 * when a snapshot carries no scheduled times at all, so realtime drift can
 * never silently move the evaluated interval. Times never flow back into the
 * snapshot: evaluation is read-only.
 */
fun TrainRun.strikeContext(): ServiceStrikeContext {
    val scheduled = buildList {
        summary.scheduledTime?.let(::add)
        summary.scheduledDeparture?.let(::add)
        summary.scheduledArrival?.let(::add)
        stops.forEach { stop ->
            stop.scheduledArrival?.let(::add)
            stop.scheduledDeparture?.let(::add)
        }
    }
    val fallback = buildList {
        stops.forEach { stop ->
            stop.actualArrival?.let(::add)
            stop.actualDeparture?.let(::add)
        }
    }
    val times = scheduled.ifEmpty { fallback }
    return ServiceStrikeContext(times.minOrNull(), times.maxOrNull(), summary.operator, summary.id)
}

/** Temporal evidence dimension: kept distinct, never collapsed to a boolean. */
enum class TemporalEvidence { OVERLAP, NO_OVERLAP, INCOMPLETE }

/** Operator evidence dimension. */
enum class OperatorEvidence { MATCH, MISMATCH, UNKNOWN }

/**
 * Geography evidence dimension.
 *
 * Only [NATIONAL_COVERAGE] is supported evidence: a national strike covers
 * every train. Every other relevance (including regional/provincial/local
 * with region/province names) is [UNKNOWN] — unknown geography remains
 * unknown, and station-name substring matching is never evidence that a
 * train lies within a strike's geographic scope.
 */
enum class GeographyEvidence { NATIONAL_COVERAGE, UNKNOWN }

/**
 * One strike assessment against one service context (T7.12 impact semantics).
 *
 * [impact] is NONE for revoked/completed/non-railway strikes and whenever
 * temporal evidence is not [TemporalEvidence.OVERLAP]. Temporal overlap
 * alone yields at most [StrikeImpact.POTENTIAL] and is never proof of
 * cancellation. [officiallyConfirmed] is true only for
 * [StrikeImpact.CONFIRMED_BY_OPERATOR], which additionally requires fresh
 * backing data: a stale warning never implies fresh operator confirmation.
 */
data class StrikeImpactAssessment(
    val impact: StrikeImpact,
    val strikeId: StrikeId,
    val temporal: TemporalEvidence,
    val operator: OperatorEvidence,
    val geography: GeographyEvidence,
    val officiallyConfirmed: Boolean,
    val freshness: DataFreshness,
)

/**
 * One displayable warning: the supporting strike plus its assessment.
 * Presentation must include the strike identity, interval, source when
 * actually available, and freshness/staleness from [assessment].
 */
data class ServiceStrikeWarning(
    val strike: Strike,
    val assessment: StrikeImpactAssessment,
)

class EvaluateServiceStrikeImpact(
    private val informationPolicy: StrikeInformationPolicy = StrikeInformationPolicy(),
) {
    operator fun invoke(
        context: ServiceStrikeContext,
        strike: Strike,
        freshness: DataFreshness = DataFreshness.Unknown,
        confirmation: OperatorStrikeConfirmation? = null,
    ): StrikeImpactAssessment {
        val temporal = temporalEvidence(context, strike)
        val operator = operatorEvidence(context.operator, strike)
        val geography = geographyEvidence(strike)
        val confirmed = confirmation?.let {
            it.verified && it.strikeId == strike.id && it.trainRunId == context.trainRunId &&
                informationPolicy.officialReferenceUsable(it.officialSourceUrl)
        } == true
        val impact = when {
            strike.status in setOf(StrikeStatus.REVOKED, StrikeStatus.COMPLETED) ||
                !strike.isRailwayRelevant() || temporal != TemporalEvidence.OVERLAP -> StrikeImpact.NONE
            // A stale warning must not imply fresh operator confirmation:
            // confirmation is honored only on fresh backing data.
            confirmed && freshness is DataFreshness.Fresh -> StrikeImpact.CONFIRMED_BY_OPERATOR
            operator == OperatorEvidence.MATCH || geography == GeographyEvidence.NATIONAL_COVERAGE ->
                StrikeImpact.LIKELY
            else -> StrikeImpact.POTENTIAL
        }
        return StrikeImpactAssessment(
            impact,
            strike.id,
            temporal,
            operator,
            geography,
            officiallyConfirmed = impact == StrikeImpact.CONFIRMED_BY_OPERATOR,
            freshness,
        )
    }

    /**
     * Warnings for every overlapping strike, preserving NONE/POTENTIAL/
     * LIKELY/CONFIRMED_BY_OPERATOR. Revoked/completed/non-overlapping strikes
     * contribute nothing; failures and staleness travel on [freshness].
     */
    fun warnings(
        context: ServiceStrikeContext,
        strikes: List<Strike>,
        freshness: DataFreshness = DataFreshness.Unknown,
        confirmations: Map<StrikeId, OperatorStrikeConfirmation> = emptyMap(),
    ): List<ServiceStrikeWarning> = strikes.mapNotNull { strike ->
        val assessment = invoke(context, strike, freshness, confirmations[strike.id])
        if (assessment.impact == StrikeImpact.NONE) null else ServiceStrikeWarning(strike, assessment)
    }

    private fun temporalEvidence(context: ServiceStrikeContext, strike: Strike): TemporalEvidence {
        val departure = context.departure
        val arrival = context.arrival
        if (departure == null && arrival == null) return TemporalEvidence.INCOMPLETE
        // Endpoint semantics (T7.12-B): endpoints are normalized with min/max
        // so an inverted interval still denotes its span, then evaluated with
        // the single shared strikeIntervalsOverlap definition (inclusive
        // boundaries: a service touching the strike interval exactly at one
        // instant still overlaps — conservative: at most POTENTIAL/LIKELY,
        // never certainty of cancellation). A single known time is a point
        // check.
        val from = minOf(departure ?: arrival!!, arrival ?: departure!!)
        val to = maxOf(departure ?: arrival!!, arrival ?: departure!!)
        return if (strikeIntervalsOverlap(from, to, strike.start, strike.end)) {
            TemporalEvidence.OVERLAP
        } else {
            TemporalEvidence.NO_OVERLAP
        }
    }

    private fun operatorEvidence(operator: Operator?, strike: Strike): OperatorEvidence {
        val trainOperator = operator?.name?.normalizedName()?.takeIf(String::isNotEmpty) ?: return OperatorEvidence.UNKNOWN
        if (strike.operators.isEmpty()) return OperatorEvidence.UNKNOWN
        return if (strike.operators.any { it.name.normalizedName() == trainOperator }) {
            OperatorEvidence.MATCH
        } else {
            OperatorEvidence.MISMATCH
        }
    }

    private fun geographyEvidence(strike: Strike): GeographyEvidence =
        if (strike.geography.relevance == StrikeRelevance.NATIONAL) GeographyEvidence.NATIONAL_COVERAGE
        else GeographyEvidence.UNKNOWN
}

private fun String.normalizedName(): String = trim().lowercase().replace(Regex("\\s+"), " ")
