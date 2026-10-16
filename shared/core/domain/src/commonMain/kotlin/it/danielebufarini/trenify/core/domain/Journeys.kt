package it.danielebufarini.trenify.core.domain

import it.danielebufarini.trenify.core.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

data class JourneyPolicy(val ttl: Duration = 3.minutes, val stationTtl: Duration = 1.days)

interface JourneyRepository {
    suspend fun searchStations(query: String): DataResult<List<Station>>
    fun observe(request: JourneySearchRequest): Flow<DataResult<JourneySearchResult>>
    suspend fun search(request: JourneySearchRequest, force: Boolean = false): DataResult<JourneySearchResult>
}

class SearchJourneys(private val repository: JourneyRepository) {
    fun valid(request: JourneySearchRequest): Boolean = request.origin.name.isNotBlank() &&
        request.destination.name.isNotBlank() && request.origin.id.value.isNotBlank() &&
        request.destination.id.value.isNotBlank() && request.origin.normalizedName != request.destination.normalizedName

    fun observe(request: JourneySearchRequest) = repository.observe(request)
    suspend operator fun invoke(request: JourneySearchRequest, force: Boolean = false): DataResult<JourneySearchResult> =
        if (valid(request)) repository.search(request, force) else DataResult.Failure(DomainFailure.INVALID_REQUEST)
}

enum class JourneyCorrelationStatus { RESOLVED, NO_DATA, UNRESOLVED, AMBIGUOUS, REJECTED, PROVIDER_FAILED }

data class JourneyLegCorrelation(
    val status: JourneyCorrelationStatus,
    val runId: TrainRunId? = null,
    val failure: DomainFailure? = null,
)

/** Correlate using verified stop schedules. A boarding station is not necessarily the run origin. */
class CorrelateJourneyLeg(private val trains: TrainRepository) {
    suspend operator fun invoke(leg: JourneyLeg, onScheduledPlatform: (String) -> Unit = {}): TrainRunId? =
        resolve(leg, onScheduledPlatform).runId

    suspend fun resolve(leg: JourneyLeg, onScheduledPlatform: (String) -> Unit = {}): JourneyLegCorrelation {
        val number = leg.number ?: return JourneyLegCorrelation(JourneyCorrelationStatus.UNRESOLVED)
        val expectedOperator = leg.operator
        val day = RailwayTime.serviceDate(leg.departure)
        val serviceDates = listOf(day, day - DatePeriod(days = 1))
        val searches = serviceDates.map { trains.findTrainRuns(number, it) }
        // Missing candidate coverage is not evidence of uniqueness.
        searches.forEach { result ->
            val failure = when (result) {
                is DataResult.Failure -> result.error
                is DataResult.Data -> result.warning
            }
            if (failure != null) return JourneyLegCorrelation(JourneyCorrelationStatus.PROVIDER_FAILED, failure = failure)
        }
        val candidates = searches.filterIsInstance<DataResult.Data<List<TrainRunSummary>>>()
            .flatMap { it.value }.filter { it.id.number == number && it.id.serviceDate in serviceDates }.distinctBy { it.id }
        val matches = mutableListOf<TrainRunId>()
        val scheduledBoardings = mutableListOf<String?>()
        if (candidates.isEmpty()) return JourneyLegCorrelation(JourneyCorrelationStatus.NO_DATA)
        for (candidate in candidates) {
            val result = trains.refreshTrain(candidate.id)
            if (result is DataResult.Failure) return JourneyLegCorrelation(
                if (result.error == DomainFailure.NOT_FOUND && candidates.size == 1) JourneyCorrelationStatus.NO_DATA
                else JourneyCorrelationStatus.PROVIDER_FAILED,
                failure = result.error,
            )
            result as DataResult.Data
            val run = result.value
            val boarding = run.takeIf { it.summary.id == candidate.id &&
                (expectedOperator == null || it.summary.operator?.name?.let(::normalizeStationQuery) ==
                    normalizeStationQuery(expectedOperator.name))
            }?.stops?.withIndex()?.filter { (_, stop) ->
                leg.matchesBoardingStop(stop)
            }?.singleOrNull()
            // A warning-free provider refresh can carry an older source observation:
            // staleness affects presentation, not the authoritative planned route
            // used for identity. Warning-backed fallback and unknown provenance are
            // still insufficient evidence for uniqueness.
            if (result.warning != null || result.freshness is DataFreshness.Unknown) {
                // A cached planned platform survives a failed realtime refresh.
                // With incomplete snapshot coverage, only a single candidate
                // and its exact verified boarding schedule can supply it.
                if (candidates.size == 1) boarding?.value?.scheduledPlatform?.let(onScheduledPlatform)
                return JourneyLegCorrelation(
                    if (result.warning != null) JourneyCorrelationStatus.PROVIDER_FAILED else JourneyCorrelationStatus.UNRESOLVED,
                    failure = result.warning,
                )
            }
            if (boarding == null) continue
            scheduledBoardings += boarding.value.scheduledPlatform
            if (run.stops.drop(boarding.index + 1).any {
                leg.matchesAlightingStop(it)
            }) matches += candidate.id
        }
        // Planned departure data belongs to the uniquely verified boarding
        // stop. Destination correlation governs realtime navigation separately.
        scheduledBoardings.singleOrNull()?.let(onScheduledPlatform)
        return when (matches.size) {
            0 -> JourneyLegCorrelation(JourneyCorrelationStatus.REJECTED)
            1 -> JourneyLegCorrelation(JourneyCorrelationStatus.RESOLVED, matches.single())
            else -> JourneyLegCorrelation(JourneyCorrelationStatus.AMBIGUOUS)
        }
    }
}
