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

/** Correlate using verified stop schedules. A boarding station is not necessarily the run origin. */
class CorrelateJourneyLeg(private val trains: TrainRepository) {
    suspend operator fun invoke(leg: JourneyLeg, onScheduledPlatform: (String) -> Unit = {}): TrainRunId? {
        val number = leg.number ?: return null
        val expectedOperator = leg.operator
        val day = RailwayTime.serviceDate(leg.departure)
        val serviceDates = listOf(day, day - DatePeriod(days = 1))
        val searches = serviceDates.map { trains.findTrainRuns(number, it) }
        // Missing candidate coverage is not evidence of uniqueness.
        if (searches.any { it is DataResult.Failure || (it is DataResult.Data && it.warning != null) }) return null
        val candidates = searches.filterIsInstance<DataResult.Data<List<TrainRunSummary>>>()
            .flatMap { it.value }.filter { it.id.number == number && it.id.serviceDate in serviceDates }.distinctBy { it.id }
        val matches = mutableListOf<TrainRunId>()
        val scheduledBoardings = mutableListOf<String?>()
        for (candidate in candidates) {
            val result = trains.refreshTrain(candidate.id)
            if (result !is DataResult.Data) return null
            val run = result.value
            val boarding = run.takeIf { it.summary.id == candidate.id &&
                (expectedOperator == null || it.summary.operator?.name?.let(::normalizeStationQuery) ==
                    normalizeStationQuery(expectedOperator.name))
            }?.stops?.withIndex()?.filter { (_, stop) ->
                stop.station.normalizedName == leg.origin.normalizedName && stop.scheduledDeparture == leg.departure
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
                return null
            }
            if (boarding == null) continue
            scheduledBoardings += boarding.value.scheduledPlatform
            if (run.stops.drop(boarding.index + 1).any {
                it.station.normalizedName == leg.destination.normalizedName && it.scheduledArrival == leg.arrival
            }) matches += candidate.id
        }
        // Planned departure data belongs to the uniquely verified boarding
        // stop. Destination correlation governs realtime navigation separately.
        scheduledBoardings.singleOrNull()?.let(onScheduledPlatform)
        return matches.singleOrNull()
    }
}
