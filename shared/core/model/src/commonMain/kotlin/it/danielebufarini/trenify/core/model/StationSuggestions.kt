package it.danielebufarini.trenify.core.model

enum class StationSearchCategory { RAILWAY, UNKNOWN }

/** Normalize user input for provider lookup while preserving punctuation used by their catalogs. */
fun normalizeStationSearchQuery(value: String): String = normalizeStationQuery(value).mapNotNull { char ->
    when {
        char in '\u0300'..'\u036f' -> null
        char in "’‘" -> '\''
        char in "–—" -> '-'
        else -> char
    }
}.joinToString("")

/** Search spelling only. Persistent station identity continues to use normalizeStationQuery. */
fun stationSearchText(value: String): String = normalizeStationSearchQuery(value).mapNotNull { char ->
    when {
        char in '\u0300'..'\u036f' -> null // Canonically decomposed diacritics.
        char == 'ç' -> 'c'
        char == 'ñ' -> 'n'
        char.isLetterOrDigit() -> char
        else -> ' '
    }
}.joinToString("").trim().replace(Regex("\\s+"), " ")

/**
 * Lexicographic precedence: exact search spelling; name starts with complete query tokens;
 * name starts with query token prefixes; complete tokens elsewhere; token prefixes elsewhere;
 * internal substring; provider alias (no literal match). Within each tier, confirmed railway
 * stations precede unknown categories, then search spelling, stable ID and original spelling.
 * No importance is inferred, no candidate/display limit is applied, and names never deduplicate IDs.
 */
fun rankStationSuggestions(query: String, candidates: List<Station>): List<Station> {
    val text = stationSearchText(query)
    if (text.isEmpty()) return emptyList()
    val tokens = text.split(' ')
    data class Ranked(val station: Station, val name: String, val relevance: Int)
    return candidates.map { station ->
        val name = stationSearchText(station.name)
        val words = name.split(' ')
        fun matches(start: Int, partial: Boolean): Boolean = tokens.indices.all { index ->
            val word = words[start + index]
            if (partial) word.startsWith(tokens[index]) else word == tokens[index]
        }
        val starts = if (words.size >= tokens.size) 0..(words.size - tokens.size) else IntRange.EMPTY
        val relevance = when {
            name == text -> 0
            0 in starts && matches(0, false) -> 1
            0 in starts && matches(0, true) -> 2
            starts.any { matches(it, false) } -> 3
            starts.any { matches(it, true) } -> 4
            text in name -> 5
            else -> 6 // Preserve provider-supported alternate spellings and aliases.
        }
        Ranked(station, name, relevance)
    }.sortedWith(compareBy<Ranked> { it.relevance }
        .thenBy { it.station.searchCategory != StationSearchCategory.RAILWAY }
        .thenBy { it.name }.thenBy { it.station.id.value }.thenBy { it.station.name })
        .distinctBy { it.station.id }.map { it.station }
}
