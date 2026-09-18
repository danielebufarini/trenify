package it.danielebufarini.trenify.app

import it.danielebufarini.trenify.core.domain.BookingLinkPolicy
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.GeographyEvidence
import it.danielebufarini.trenify.core.domain.OperatorEvidence
import it.danielebufarini.trenify.core.domain.ServiceStrikeWarning
import it.danielebufarini.trenify.core.domain.StrikeImpact
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Journey
import it.danielebufarini.trenify.core.model.JourneyLeg
import it.danielebufarini.trenify.core.model.JourneySort
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.model.TrainRun
import it.danielebufarini.trenify.core.model.TrainStop
import it.danielebufarini.trenify.core.model.journeySourceNames
import it.danielebufarini.trenify.core.model.trainSourceName
import it.danielebufarini.trenify.feature.journey.JourneyDetailComponent
import it.danielebufarini.trenify.feature.journey.JourneyDetailState
import it.danielebufarini.trenify.feature.journey.JourneyResultsComponent
import it.danielebufarini.trenify.feature.journey.JourneyResultsState
import it.danielebufarini.trenify.feature.journey.RouteFavoriteState
import kotlinx.coroutines.flow.StateFlow

/**
 * Native Journey Results/Detail presentation (T8.6).
 *
 * Semantic structured projections over the live [JourneyResultsComponent] /
 * [JourneyDetailComponent]. Platforms render these and forward the explicit
 * actions; no provider, repository, persistence or realtime-fetch behavior
 * crosses this boundary. Times are epoch seconds (platforms format them in
 * Europe/Rome with native formatters); durations are whole minutes.
 * No prices, fares, classes, availability or purchase concepts exist here.
 */

/** One leg as shared truth: endpoints, scheduled times, train/operator identity. */
data class NativeJourneyLegPresentation(
    val originName: String,
    val destinationName: String,
    val departureEpochSeconds: Long,
    val arrivalEpochSeconds: Long,
    val durationMinutes: Long,
    /** e.g. "FR 9516"; null when the provider supplies neither category nor number. */
    val trainIdentity: String?,
    /** null when the provider supplies no operator. Unknown stays unknown. */
    val operatorName: String?,
    /** Scheduled dwell between the previous leg arrival and this departure; null for the first leg. */
    val transferWaitMinutesAfterPrevious: Long?,
)

/**
 * One service strike warning as shared semantic truth (T7.12, non-rendering).
 * Carries the supporting strike identity, sector, scheduled interval,
 * semantic impact, source label
 * and URL when genuinely available, partial/unconfirmed context, and
 * officially-confirmed state. A warning never implies train cancellation;
 * impact is at most the conservative assessed level.
 */
data class NativeServiceStrikeWarningPresentation(
    /** Stable strike identity (`StrikeId.value`). */
    val strikeId: String,
    val sector: String,
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
    val impact: StrikeImpact,
    /** Strike source label; null when genuinely absent/blank. */
    val sourceLabel: String?,
    /** Strike source URL; present only when genuinely part of the warning data. */
    val sourceUrl: String?,
    /**
     * True when neither the operator nor national geography counts as
     * supporting evidence — temporal overlap alone, explicitly unconfirmed
     * (same rule as the established partial-context wording).
     */
    val partialContext: Boolean,
    val officiallyConfirmed: Boolean,
)

/**
 * FR-DATA-001 provenance for one correlated realtime leg (non-rendering).
 * Genuine provider identity plus the observation's own fetch/source-update
 * timestamps; Unknown freshness carries no timestamps (never invented).
 */
data class NativeRealtimeProvenancePresentation(
    /** Genuine provider name via shared provider-neutral semantics; null when unknown. */
    val providerName: String?,
    /**
     * Local fetch timestamp of this observation. Null when the freshness is
     * Unknown — timestamps are never invented.
     */
    val fetchedAtEpochSeconds: Long?,
    /** Source observation/update timestamp when genuinely available. */
    val sourceTimestampEpochSeconds: Long?,
    /** The observation aged past its TTL. */
    val stale: Boolean,
    /** Warning-backed replay: degraded while preserving genuine timestamps. */
    val degraded: Boolean,
)

/** One result card as shared truth; realtime status lives on the Detail legs. */
data class NativeJourneyCardPresentation(
    /** Position in the currently sorted list; the selection key for [NativeJourneyResultsPresentation.selectJourney]. */
    val index: Int,
    /**
     * Stable list identity derived from journey content (T8.14-C1).
     * [index] moves when the user re-sorts; this key does not. Native lists
     * must use [key] as row identity and keep [index] for selection only.
     */
    val key: String,
    val originName: String,
    val destinationName: String,
    val departureEpochSeconds: Long,
    val arrivalEpochSeconds: Long,
    val durationMinutes: Long,
    val changes: Int,
    /** Non-empty per-leg identities in leg order; empty when none is supplied. */
    val trainIdentities: List<String>,
    /** Distinct operator names in leg order; empty when none is supplied. */
    val operatorNames: List<String>,
    val legs: List<NativeJourneyLegPresentation>,
    /** FR-DATA-001 journey sources (T7.14): genuinely contributing providers, never inferred. Null when none known. */
    val sourceNames: String?,
    val warningCount: Int,
    val confirmedWarning: Boolean,
    /** Full T7.12 warning semantics for this journey, in shared evaluation order. */
    val warnings: List<NativeServiceStrikeWarningPresentation>,
)

data class NativeJourneyResultsState(
    val originName: String,
    val destinationName: String,
    val windowStartEpochSeconds: Long,
    val windowEndEpochSeconds: Long,
    val loading: Boolean,
    val failure: DomainFailure?,
    /** A repository payload is held (possibly stale); distinguishes content states from initial load. */
    val hasContent: Boolean,
    /** A payload is held but carries no journeys. */
    val empty: Boolean,
    /**
     * Presentation policy (pre-T8.13 corrective): technical non-Fresh state
     * (progressive partial aggregation, cached content shown while a refresh
     * runs) is normal operation, never an "outdated" warning. The actionable
     * failed-refresh state keeps its own signal via [failure] with retry.
     * Always projected false; internal freshness semantics are unchanged.
     */
    val stale: Boolean,
    /** At least one source is unavailable or incomplete. */
    val partial: Boolean,
    val strikesStale: Boolean,
    val strikesFailed: Boolean,
    val strikesUnknown: Boolean,
    val sort: JourneySort,
    val journeys: List<NativeJourneyCardPresentation>,
)

/** Typed actions over one existing Results component; all semantics stay in that component. */
class NativeJourneyResultsPresentation internal constructor(
    private val component: JourneyResultsComponent,
    private val owner: NativeProjectionOwner,
) {
    val state: StateFlow<NativeJourneyResultsState> = owner.project(component.state) { component.projected(it) }
    fun refresh() = act(component::refresh)
    fun setSort(sort: JourneySort) = act { component.sort(sort) }
    fun selectJourney(index: Int) = act {
        val journey = component.state.value.journeys.getOrNull(index) ?: return@act
        component.select(journey)
    }
    private fun act(action: () -> Unit) { if (owner.job.isActive) action() }
}

/** One Detail leg: scheduled shared truth plus already-correlated realtime enrichment. */
data class NativeLegDetailPresentation(
    val originName: String,
    val destinationName: String,
    val departureEpochSeconds: Long,
    val arrivalEpochSeconds: Long,
    val durationMinutes: Long,
    val trainIdentity: String?,
    /**
     * Display operator for this leg (presentation fallback only): the Journey
     * operator when the provider supplied one, else the uniquely correlated
     * realtime run's operator when known, else null (unknown stays unknown).
     * The domain Journey operator is never mutated or enriched.
     */
    val operatorName: String?,
    val transferWaitMinutesAfterPrevious: Long?,
    /** Correlated enrichment status; UNKNOWN when no realtime snapshot is held. Unknown stays unknown. */
    val realtimeStatus: TrainStatus,
    val delayMinutes: Int?,
    val scheduledPlatform: String?,
    val actualPlatform: String?,
    val realtimeStale: Boolean,
    val realtimeFailed: Boolean,
    /** The leg resolved to a known train run, so the train action is available. */
    val hasTrainAction: Boolean,
    /** FR-DATA-001 correlated-run provenance; null when no realtime snapshot is held. */
    val realtimeProvenance: NativeRealtimeProvenancePresentation?,
    val warningCount: Int,
    val confirmedWarning: Boolean,
    /** Full T7.12 warning semantics for this leg, in shared evaluation order. */
    val warnings: List<NativeServiceStrikeWarningPresentation>,
)

data class NativeJourneyDetailState(
    /** A restored detail is re-resolving through the repository. */
    val resolving: Boolean,
    /** The restored target is gone; the UI shows not-found with Back. Nothing is substituted. */
    val notFound: Boolean,
    val originName: String?,
    val destinationName: String?,
    val departureEpochSeconds: Long?,
    val arrivalEpochSeconds: Long?,
    val durationMinutes: Long?,
    val changes: Int?,
    /**
     * Presentation policy (pre-T8.13 corrective): same rule as results
     * [NativeJourneyResultsState.stale] — a technically Stale journey
     * freshness (notably the intentional partial state) is not user-facing
     * "outdated". Leg-level realtime/failed/strike states remain the
     * actionable signals. Always projected false; internal freshness
     * semantics are unchanged.
     */
    val journeyStale: Boolean,
    /**
     * Restored-detail provenance timestamps (T7.14-B): the re-resolution
     * freshness instants where known. Unknown freshness yields nulls —
     * timestamps are never invented.
     */
    val journeyFetchedAtEpochSeconds: Long?,
    val journeySourceTimestampEpochSeconds: Long?,
    /**
     * FR-DATA-001 journey sources (T7.14): genuinely contributing providers
     * from the shared Journey source set, same label semantics as Results.
     * Null when none known; the train operator is never used as a source.
     */
    val journeySourceNames: String?,
    val strikesStale: Boolean,
    val strikesFailed: Boolean,
    val strikesUnknown: Boolean,
    val correlating: Boolean,
    val legs: List<NativeLegDetailPresentation>,
    val bookingAvailable: Boolean,
    val bookingInProgress: Boolean,
    val bookingFailed: Boolean,
    /** Operator display name for the booking disclosure; null when booking is unavailable. */
    val bookingOperatorName: String?,
)

/** Typed actions over one existing Detail component; all semantics stay in that component. */
class NativeJourneyDetailPresentation internal constructor(
    private val component: JourneyDetailComponent,
    private val owner: NativeProjectionOwner,
) {
    val state: StateFlow<NativeJourneyDetailState> = owner.project(component.state) { it.projected() }
    fun openTrain(legIndex: Int) = act { component.realtime(legIndex) }
    fun buy() = act(component::buy)
    fun refreshStrikes() = act(component::refreshStrikes)
    fun toggleFavoriteRoute() = act(component::toggleFavoriteRoute)
    val favoriteRoute: StateFlow<RouteFavoriteState>? = component.favoriteRouteState?.let { value ->
        owner.project(value) { it }
    }
    private fun act(action: () -> Unit) { if (owner.job.isActive) action() }
}

private fun JourneyLeg.projected(transferWaitMinutesAfterPrevious: Long?): NativeJourneyLegPresentation =
    NativeJourneyLegPresentation(
        originName = origin.name,
        destinationName = destination.name,
        departureEpochSeconds = departure.epochSeconds,
        arrivalEpochSeconds = arrival.epochSeconds,
        durationMinutes = duration.inWholeMinutes,
        trainIdentity = trainIdentity(),
        operatorName = operator?.name,
        transferWaitMinutesAfterPrevious = transferWaitMinutesAfterPrevious,
    )

private fun JourneyLeg.trainIdentity(): String? =
    listOfNotNull(category, number?.value).joinToString(" ").takeIf { it.isNotEmpty() }

/**
 * A journey leg boards at its own scheduled stop. The summary platform is a
 * run-level field and is therefore never a valid fallback for Journey Detail.
 * Correlation already requires this same station/time pair, so this lookup
 * adds no identity or provider call and cannot select Tirano/Milano instead.
 */
private fun TrainRun.boardingStopFor(leg: JourneyLeg): TrainStop? =
    stops.firstOrNull {
        it.station.normalizedName == leg.origin.normalizedName &&
            it.scheduledDeparture == leg.departure
    }

private fun List<ServiceStrikeWarning>.warningCount(): Int = size

private fun List<ServiceStrikeWarning>.confirmed(): Boolean = any { it.assessment.officiallyConfirmed }

/**
 * Internal for direct projection tests (same module); never exported to
 * Swift. The field mapping is the T7.12 contract the platforms render.
 */
internal fun ServiceStrikeWarning.projected(): NativeServiceStrikeWarningPresentation =
    NativeServiceStrikeWarningPresentation(
        strikeId = strike.id.value,
        sector = strike.sector,
        startEpochSeconds = strike.start.epochSeconds,
        endEpochSeconds = strike.end.epochSeconds,
        impact = assessment.impact,
        sourceLabel = strike.source.label.takeIf { it.isNotBlank() },
        sourceUrl = strike.source.url.takeIf { it.isNotBlank() },
        partialContext = assessment.operator != OperatorEvidence.MATCH &&
            assessment.geography != GeographyEvidence.NATIONAL_COVERAGE,
        officiallyConfirmed = assessment.officiallyConfirmed,
    )

private fun List<ServiceStrikeWarning>.projected(): List<NativeServiceStrikeWarningPresentation> = map { it.projected() }

private fun DataFreshness.fetchedAtEpochSeconds(): Long? = when (this) {
    is DataFreshness.Fresh -> fetchedAt.epochSeconds
    is DataFreshness.Stale -> fetchedAt.epochSeconds
    DataFreshness.Unknown -> null
}

private fun DataFreshness.sourceTimestampEpochSeconds(): Long? = when (this) {
    is DataFreshness.Fresh -> sourceTimestamp?.epochSeconds
    is DataFreshness.Stale -> sourceTimestamp?.epochSeconds
    DataFreshness.Unknown -> null
}

/**
 * Content-derived list identity for one journey card (T8.14-C1).
 * Endpoints, scheduled times, change count and per-leg train identities in
 * leg order: invariant under result re-sorting, distinct for distinct
 * itineraries. Presentation identity only; domain identity is unchanged.
 */
internal fun Journey.stableKey(): String = buildList {
    add(origin.id.value)
    add(destination.id.value)
    add(departure.epochSeconds.toString())
    add(arrival.epochSeconds.toString())
    add(changes.toString())
    addAll(legs.map { it.trainIdentity() ?: "${it.origin.id.value}>${it.destination.id.value}@${it.departure.epochSeconds}" })
}.joinToString("|")

private fun JourneyResultsComponent.projected(state: JourneyResultsState): NativeJourneyResultsState {
    val journeys = state.journeys
    return NativeJourneyResultsState(
        originName = request.origin.name,
        destinationName = request.destination.name,
        windowStartEpochSeconds = request.departureFrom.epochSeconds,
        windowEndEpochSeconds = request.departureUntil.epochSeconds,
        loading = state.results.loading,
        failure = state.results.failure,
        hasContent = state.results.data != null,
        empty = state.results.data != null && journeys.isEmpty(),
        // Presentation policy: never surface technical Stale as "outdated".
        stale = false,
        partial = state.results.data?.partial == true,
        strikesStale = state.strikesStale,
        strikesFailed = state.strikesFailed,
        strikesUnknown = state.strikesUnknown,
        sort = state.sort,
        journeys = journeys.mapIndexed { index, journey ->
            val warnings = state.strikeWarnings[journey].orEmpty()
            NativeJourneyCardPresentation(
                index = index,
                key = journey.stableKey(),
                originName = journey.origin.name,
                destinationName = journey.destination.name,
                departureEpochSeconds = journey.departure.epochSeconds,
                arrivalEpochSeconds = journey.arrival.epochSeconds,
                durationMinutes = journey.duration.inWholeMinutes,
                changes = journey.changes,
                trainIdentities = journey.legs.mapNotNull { it.trainIdentity() },
                operatorNames = journey.operators.map { it.name },
                legs = journey.legs.mapIndexed { legIndex, leg ->
                    leg.projected(
                        transferWaitMinutesAfterPrevious = if (legIndex == 0) null
                        else (leg.departure - journey.legs[legIndex - 1].arrival).inWholeMinutes,
                    )
                },
                sourceNames = journeySourceNames(journey.sources),
                warningCount = warnings.warningCount(),
                confirmedWarning = warnings.confirmed(),
                warnings = warnings.projected(),
            )
        },
    )
}

private fun JourneyDetailState.projected(): NativeJourneyDetailState {
    val journey = resolved
    return NativeJourneyDetailState(
        resolving = resolving,
        notFound = notFound,
        originName = journey?.origin?.name,
        destinationName = journey?.destination?.name,
        departureEpochSeconds = journey?.departure?.epochSeconds,
        arrivalEpochSeconds = journey?.arrival?.epochSeconds,
        durationMinutes = journey?.duration?.inWholeMinutes,
        changes = journey?.changes,
        // Presentation policy: never surface technical Stale as "outdated".
        journeyStale = false,
        journeyFetchedAtEpochSeconds = journeyFreshness.fetchedAtEpochSeconds(),
        journeySourceTimestampEpochSeconds = journeyFreshness.sourceTimestampEpochSeconds(),
        journeySourceNames = journey?.let { journeySourceNames(it.sources) },
        strikesStale = strikesStale,
        strikesFailed = strikesFailed,
        strikesUnknown = strikesUnknown,
        correlating = correlating,
        legs = journey?.legs?.mapIndexed { index, leg ->
            val correlatedData = correlatedRuns[index] as? DataResult.Data
            val correlated = correlatedData?.value
            val failed = correlatedRuns[index] is DataResult.Failure
            val correlatedFreshness = correlatedData?.freshness
            val warnings = strikeWarnings[index].orEmpty()
            val boardingStop = correlated?.boardingStopFor(leg)
            NativeLegDetailPresentation(
                originName = leg.origin.name,
                destinationName = leg.destination.name,
                departureEpochSeconds = leg.departure.epochSeconds,
                arrivalEpochSeconds = leg.arrival.epochSeconds,
                durationMinutes = leg.duration.inWholeMinutes,
                trainIdentity = leg.trainIdentity(),
                // Presentation fallback only: a known Journey operator keeps
                // precedence; an unknown one falls back to the operator of
                // the same correlated run already accepted by the realtime
                // path (unique match, no second lookup). Domain identity and
                // booking policy keep using leg.operator and are untouched.
                operatorName = leg.operator?.name ?: correlated?.summary?.operator?.name,
                transferWaitMinutesAfterPrevious = if (index == 0) null
                else (leg.departure - journey.legs[index - 1].arrival).inWholeMinutes,
                realtimeStatus = correlated?.summary?.status ?: TrainStatus.UNKNOWN,
                delayMinutes = correlated?.summary?.delayMinutes,
                scheduledPlatform = boardingStop?.scheduledPlatform,
                actualPlatform = boardingStop?.actualPlatform,
                realtimeStale = correlatedFreshness is DataFreshness.Stale,
                realtimeFailed = failed || correlatedData?.warning != null,
                hasTrainAction = index in trainRuns,
                realtimeProvenance = correlatedData?.let { data ->
                    NativeRealtimeProvenancePresentation(
                        providerName = trainSourceName(data.value.summary.id.provider),
                        fetchedAtEpochSeconds = data.freshness.fetchedAtEpochSeconds(),
                        sourceTimestampEpochSeconds = data.freshness.sourceTimestampEpochSeconds(),
                        stale = data.freshness is DataFreshness.Stale,
                        degraded = data.warning != null,
                    )
                },
                warningCount = warnings.warningCount(),
                confirmedWarning = warnings.confirmed(),
                warnings = warnings.projected(),
            )
        }.orEmpty(),
        bookingAvailable = bookingAvailable,
        bookingInProgress = bookingInProgress,
        bookingFailed = bookingFailed,
        // Display name only, from the same shared policy the component uses
        // for availability; the URL itself never enters presentation state.
        bookingOperatorName = journey?.takeIf { bookingAvailable }
            ?.let { BookingLinkPolicy().target(it)?.operatorName },
    )
}
