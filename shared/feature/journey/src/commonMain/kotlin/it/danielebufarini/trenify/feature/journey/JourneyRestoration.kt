package it.danielebufarini.trenify.feature.journey

import it.danielebufarini.trenify.core.model.Journey
import it.danielebufarini.trenify.core.model.JourneyLeg
import it.danielebufarini.trenify.core.model.JourneySearchMode
import it.danielebufarini.trenify.core.model.JourneySearchRequest
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.Station
import it.danielebufarini.trenify.core.model.StationId
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.model.normalizeStationQuery
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Minimal normalized journey lookup identity (T7.13-C).
 *
 * A restored journey detail must never resurrect a stale serialized domain
 * snapshot as live data: it re-resolves the journey through the repository
 * and only presents repository-observed content. This key is the smallest
 * normalized identity that makes that re-resolution deterministic:
 * - the originating [JourneySearchRequest] criteria (endpoint identities,
 *   requested Rome instant, mode);
 * - one exact descriptor per leg (endpoint identities, scheduled instants,
 *   train number, operator).
 *
 * No provider wire IDs travel here: stations use the normalized internal
 * [StationId] plus the display name (names alone are compared normalized,
 * so same-looking stations with different identities never match), and the
 * operator travels as its normalized display name. Times are epoch millis
 * of the scheduled instants, keeping railway Europe/Rome interpretation in
 * the domain layer.
 *
 * Matching ([matches]) requires the exact leg count and every leg to agree;
 * an ambiguous or missing match resolves to no journey and the detail shows
 * the controlled not-found path instead of a similar-looking service.
 */
@Serializable
data class SerializableStationRef(
    val id: String,
    val name: String,
)

@Serializable
data class SerializableJourneyRequest(
    val origin: SerializableStationRef,
    val destination: SerializableStationRef,
    val atEpochMillis: Long,
    val mode: String,
)

@Serializable
data class SerializableJourneyLeg(
    val origin: SerializableStationRef,
    val destination: SerializableStationRef,
    val departureEpochMillis: Long,
    val arrivalEpochMillis: Long,
    val number: String? = null,
    val operator: String? = null,
)

@Serializable
data class JourneyLookupKey(
    val request: SerializableJourneyRequest,
    val legs: List<SerializableJourneyLeg>,
)

fun Station.serialized(): SerializableStationRef = SerializableStationRef(id.value, name)

fun SerializableStationRef.station(): Station? {
    if (id.isBlank() || name.isBlank()) return null
    return Station(StationId(id), name)
}

fun JourneySearchRequest.serialized(): SerializableJourneyRequest = SerializableJourneyRequest(
    origin = origin.serialized(),
    destination = destination.serialized(),
    atEpochMillis = at.toEpochMilliseconds(),
    mode = mode.name,
)

fun SerializableJourneyRequest.request(): JourneySearchRequest? {
    val originStation = origin.station() ?: return null
    val destinationStation = destination.station() ?: return null
    if (originStation.id == destinationStation.id) return null
    val modeValue = runCatching { JourneySearchMode.valueOf(mode) }.getOrNull() ?: return null
    return JourneySearchRequest(
        origin = originStation,
        destination = destinationStation,
        at = Instant.fromEpochMilliseconds(atEpochMillis),
        mode = modeValue,
    )
}

fun JourneyLeg.serialized(): SerializableJourneyLeg = SerializableJourneyLeg(
    origin = origin.serialized(),
    destination = destination.serialized(),
    departureEpochMillis = departure.toEpochMilliseconds(),
    arrivalEpochMillis = arrival.toEpochMilliseconds(),
    number = number?.value,
    operator = operator?.name,
)

/** Normalized lookup key for re-resolution; never presented as journey data. */
fun Journey.lookupKey(request: JourneySearchRequest): JourneyLookupKey =
    JourneyLookupKey(request.serialized(), legs.map { it.serialized() })

private fun SerializableJourneyLeg.matches(leg: JourneyLeg): Boolean {
    val originStation = origin.station() ?: return false
    val destinationStation = destination.station() ?: return false
    if (originStation.id != leg.origin.id || destinationStation.id != leg.destination.id) return false
    if (normalizeStationQuery(originStation.name) != leg.origin.normalizedName) return false
    if (normalizeStationQuery(destinationStation.name) != leg.destination.normalizedName) return false
    if (leg.departure.toEpochMilliseconds() != departureEpochMillis) return false
    if (leg.arrival.toEpochMilliseconds() != arrivalEpochMillis) return false
    if (number != leg.number?.value) return false
    if (operator != leg.operator?.name) return false
    return true
}

/**
 * True only for the exact journey this key was derived from: same leg count
 * and every leg matching. Same-looking but different services (different
 * times, numbers or station identities) never match.
 */
fun JourneyLookupKey.matches(journey: Journey): Boolean {
    if (legs.size != journey.legs.size) return false
    return legs.zip(journey.legs).all { (key, leg) -> key.matches(leg) }
}

/** Decoded originating criteria, or null when the persisted form is malformed. */
fun JourneyLookupKey.searchRequest(): JourneySearchRequest? = request.request()

/** Finds the uniquely matching journey, or null when missing/ambiguous. */
fun JourneyLookupKey.resolveIn(journeys: List<Journey>): Journey? {
    val matches = journeys.filter(::matches)
    return matches.singleOrNull()
}
