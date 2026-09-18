package it.danielebufarini.trenify.data

import it.danielebufarini.trenify.core.model.*
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable internal data class JourneyLegRecord(
    val origin: StationRecord, val destination: StationRecord, val departure: String, val arrival: String,
    val number: String?, val category: String?, val operator: String?,
) {
    fun model() = JourneyLeg(origin.model(), destination.model(), Instant.parse(departure), Instant.parse(arrival),
        number?.let(::TrainNumber), category, operator?.let(::Operator))
}
@Serializable internal data class JourneyRecord(val legs: List<JourneyLegRecord>, val sources: List<String>) {
    fun model() = Journey(legs.map { it.model() }, sources.map(::ProviderId).toSet())
}
@Serializable internal data class CoverageRecord(
    val source: String, val operators: List<String>, val services: List<String>, val status: String, val complete: Boolean,
) {
    fun model() = JourneyCoverage(ProviderId(source), operators.map(::Operator), services,
        JourneySourceStatus.entries.firstOrNull { it.name == status } ?: JourneySourceStatus.UNAVAILABLE, complete)
}
@Serializable internal data class JourneyResultRecord(
    val journeys: List<JourneyRecord>, val coverage: List<CoverageRecord>, val from: String, val until: String,
) {
    fun model() = JourneySearchResult(journeys.map { it.model() }, coverage.map { it.model() }, Instant.parse(from), Instant.parse(until))
}
internal fun JourneySearchResult.record() = JourneyResultRecord(
    journeys.map { journey -> JourneyRecord(journey.legs.map { leg -> JourneyLegRecord(
        StationRecord(leg.origin.id.value, leg.origin.name), StationRecord(leg.destination.id.value, leg.destination.name),
        leg.departure.toString(), leg.arrival.toString(), leg.number?.value, leg.category, leg.operator?.name,
    ) }, journey.sources.map { it.value }) },
    coverage.map { CoverageRecord(it.source.value, it.operators.map(Operator::name), it.services, it.status.name, it.complete) },
    departureFrom.toString(), departureUntil.toString(),
)
