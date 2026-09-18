package it.danielebufarini.trenify.core.model

import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

enum class JourneySearchMode { DEPART_AFTER, ARRIVE_BY }
enum class JourneySort { DEPARTURE, ARRIVAL, DURATION, CHANGES }

@JvmInline value class FavoriteRouteId(val value: String)

data class FavoriteRoute(
    val id: FavoriteRouteId,
    val origin: Station,
    val destination: Station,
) {
    init {
        require(origin.id != destination.id)
        require(id == idFor(origin, destination))
    }

    val searchIntent: JourneySearchIntent get() = JourneySearchIntent(origin, destination)

    companion object {
        fun create(origin: Station, destination: Station): FavoriteRoute =
            FavoriteRoute(idFor(origin, destination), origin, destination)

        fun idFor(origin: Station, destination: Station): FavoriteRouteId = FavoriteRouteId(
            listOf(origin.id.value, destination.id.value).joinToString("") { "${it.length}:$it" },
        )
    }
}

data class JourneySearchIntent(
    val origin: Station,
    val destination: Station,
) {
    init { require(origin.id != destination.id) }
}

data class JourneySearchRequest(
    val origin: Station,
    val destination: Station,
    val at: Instant,
    val mode: JourneySearchMode = JourneySearchMode.DEPART_AFTER,
) {
    // An explicit search horizon, also returned as coverage and shown by the UI.
    val departureFrom: Instant get() = if (mode == JourneySearchMode.ARRIVE_BY) at - horizon else at
    val departureUntil: Instant get() = if (mode == JourneySearchMode.ARRIVE_BY) at else at + horizon
    val key: String get() = listOf(origin.normalizedName, destination.normalizedName, at.toString(), mode.name)
        .joinToString("") { "${it.length}:$it" }

    companion object {
        /**
         * Product journey-search window (T8 latency decision 2026-09-18): 8
         * hours from the requested instant cover the normal Trenify use case
         * and bound provider pagination/aggregation work. Instant arithmetic
         * applies, so windows crossing midnight behave identically.
         */
        val horizon: Duration = 8.hours
    }
}

data class JourneyLeg(
    val origin: Station,
    val destination: Station,
    val departure: Instant,
    val arrival: Instant,
    val number: TrainNumber?,
    val category: String?,
    val operator: Operator?,
) {
    init { require(arrival > departure) }
    val duration: Duration get() = arrival - departure
}

data class Journey(val legs: List<JourneyLeg>, val sources: Set<ProviderId>) {
    init {
        require(legs.isNotEmpty())
        require(legs.zipWithNext().all { (before, after) ->
            before.destination.normalizedName == after.origin.normalizedName && before.arrival <= after.departure
        })
    }
    val origin: Station get() = legs.first().origin
    val destination: Station get() = legs.last().destination
    val departure: Instant get() = legs.first().departure
    val arrival: Instant get() = legs.last().arrival
    val duration: Duration get() = arrival - departure
    val changes: Int get() = legs.size - 1
    val operators: List<Operator> get() = legs.mapNotNull(JourneyLeg::operator).distinct()
}

enum class JourneySourceStatus { AVAILABLE, UNAVAILABLE, UNSUPPORTED }
data class JourneyCoverage(
    val source: ProviderId,
    val operators: List<Operator>,
    val services: List<String>,
    val status: JourneySourceStatus,
    val complete: Boolean,
)
data class JourneySearchResult(
    val journeys: List<Journey>,
    val coverage: List<JourneyCoverage>,
    val departureFrom: Instant,
    val departureUntil: Instant,
) {
    val partial: Boolean get() = coverage.any { it.status != JourneySourceStatus.AVAILABLE || !it.complete }
}

fun Journey.matches(request: JourneySearchRequest): Boolean =
    origin.normalizedName == request.origin.normalizedName && destination.normalizedName == request.destination.normalizedName &&
        departure >= request.departureFrom && departure <= request.departureUntil &&
        (request.mode != JourneySearchMode.ARRIVE_BY || arrival <= request.at)

fun List<Journey>.sortedBy(sort: JourneySort): List<Journey> = sortedWith(
    when (sort) {
        JourneySort.DEPARTURE -> compareBy<Journey> { it.departure }
        JourneySort.ARRIVAL -> compareBy { it.arrival }
        JourneySort.DURATION -> compareBy { it.duration }
        JourneySort.CHANGES -> compareBy { it.changes }
    }.thenBy { it.departure }.thenBy { it.arrival },
)
