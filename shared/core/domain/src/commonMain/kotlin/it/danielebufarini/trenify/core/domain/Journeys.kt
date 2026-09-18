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
    suspend operator fun invoke(leg: JourneyLeg): TrainRunId? {
        val number = leg.number ?: return null
        val expectedOperator = leg.operator
        val day = RailwayTime.serviceDate(leg.departure)
        val searches = listOf(day, day - DatePeriod(days = 1)).map { trains.findTrainRuns(number, it) }
        // Missing candidate coverage is not evidence of uniqueness.
        if (searches.any { it is DataResult.Failure || (it is DataResult.Data && it.warning != null) }) return null
        val candidates = searches.filterIsInstance<DataResult.Data<List<TrainRunSummary>>>()
            .flatMap { it.value }.filter { it.id.number == number }.distinctBy { it.id }
        val matches = mutableListOf<TrainRunId>()
        for (candidate in candidates) {
            val result = trains.refreshTrain(candidate.id)
            // A warning-free provider refresh can carry an older source observation:
            // staleness affects presentation, not the authoritative planned route
            // used for identity. Warning-backed fallback and unknown provenance are
            // still insufficient evidence for uniqueness.
            if (result !is DataResult.Data || result.warning != null || result.freshness is DataFreshness.Unknown) return null
            val run = result.value
            if (run.summary.id != candidate.id) continue
            if (expectedOperator != null && run.summary.operator?.name?.let(::normalizeStationQuery) !=
                normalizeStationQuery(expectedOperator.name)) continue
            val departures = run.stops.withIndex().filter { (_, stop) ->
                stop.station.normalizedName == leg.origin.normalizedName && stop.scheduledDeparture == leg.departure
            }
            if (departures.size != 1) continue
            if (run.stops.drop(departures.single().index + 1).any {
                it.station.normalizedName == leg.destination.normalizedName && it.scheduledArrival == leg.arrival
            }) matches += candidate.id
        }
        return matches.singleOrNull()
    }
}
