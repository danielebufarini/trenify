package it.danielebufarini.trenify.core.model

/**
 * Cross-catalog comparison for a verified scheduled train stop. Persistent IDs
 * and search/cache normalization remain unchanged. A shared identity wins;
 * conflicting references within one named provider catalog veto text matching.
 * Opaque IDs from independent catalogs cannot be compared as provider IDs.
 */
fun Station.matchesRealtimeStation(other: Station): Boolean {
    if (id == other.id) return true
    val catalog = id.value.locationCatalog()
    if (catalog != null && catalog == other.id.value.locationCatalog()) return false
    return normalizeRealtimeStationName(name) == normalizeRealtimeStationName(other.name)
}

private fun String.locationCatalog(): String? =
    takeIf { it.startsWith("location:") }?.substringBeforeLast(':')

/**
 * Exact, bounded railway phrase aliases, not token initials or fuzzy names.
 * The public LeFrecce catalog uses "S. M. Novella"; the captured ViaggiaTreno
 * stop uses "SANTA MARIA NOVELLA" and its public UI also uses "S.M.N.".
 * Preserve the city, station qualifiers, and every character outside that
 * complete phrase. In particular S.M. alone is ambiguous and never expanded.
 */
fun normalizeRealtimeStationName(value: String): String = normalizeJourneyStationName(value)
    .replace(Regex("(?<![a-z])s\\.m\\.(?:novella|n\\.)(?![a-z])"), "santa maria novella")

fun JourneyLeg.matchesBoardingStop(stop: TrainStop): Boolean =
    origin.matchesRealtimeStation(stop.station) && departure == stop.scheduledDeparture

fun JourneyLeg.matchesAlightingStop(stop: TrainStop): Boolean =
    destination.matchesRealtimeStation(stop.station) && arrival == stop.scheduledArrival
