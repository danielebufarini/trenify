package it.danielebufarini.trenify.feature.journey

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * Locale-aware journey-search date/time presentation and parsing (T7.14
 * corrective).
 *
 * Railway interpretation is always Europe/Rome; only the field text follows
 * the platform locale. The durable/search truth stays typed ([LocalDate]
 * plus hour/minute) — a localized display string is never domain truth —
 * so state restoration, history repeat and locale switches preserve the
 * semantic Rome date/time exactly.
 *
 * Deterministic rules by language (from the BCP-47 [localeTag]):
 * - Italian (`it`): date `D/M/YYYY`, 24-hour time `H:mm`;
 * - anything else (including `en`): date `M/D/YYYY`, 12-hour time `h:mm AM/PM`.
 *
 * Parsing additionally accepts ISO `YYYY-MM-DD` and 24-hour `HH:mm` in
 * every locale so previously entered values, history-prefilled state and
 * tests stay deterministic. Single-digit days/months/hours are accepted;
 * out-of-range values return null instead of inventing a date/time.
 */
fun searchLanguage(localeTag: String): String =
    localeTag.substringBefore('-').substringBefore('_').lowercase()

fun formatSearchDate(date: LocalDate, localeTag: String): String =
    if (searchLanguage(localeTag) == "it") {
        "${date.dayOfMonth}/${date.monthNumber}/${date.year}"
    } else {
        "${date.monthNumber}/${date.dayOfMonth}/${date.year}"
    }

fun parseSearchDate(text: String, localeTag: String): LocalDate? {
    val trimmed = text.trim()
    // ISO is accepted in every locale as the stable interchange form.
    parseIsoDate(trimmed)?.let { return it }
    val parts = trimmed.split('/')
    if (parts.size != 3) return null
    val first = parts[0].toIntOrNull()
    val second = parts[1].toIntOrNull()
    val year = parts[2].toIntOrNull()
    if (first == null || second == null || year == null) return null
    return if (searchLanguage(localeTag) == "it") {
        // Italian D/M/YYYY: day first, month second.
        localDateOrNull(year, month = second, day = first)
    } else {
        // English M/D/YYYY: month first, day second.
        localDateOrNull(year, month = first, day = second)
    }
}

private fun parseIsoDate(text: String): LocalDate? {
    val parts = text.split('-')
    if (parts.size != 3) return null
    if (parts[0].length != 4) return null
    val year = parts[0].toIntOrNull()
    val month = parts[1].toIntOrNull()
    val day = parts[2].toIntOrNull()
    if (year == null || month == null || day == null) return null
    return localDateOrNull(year, month, day)
}

private fun localDateOrNull(year: Int, month: Int, day: Int): LocalDate? = try {
    LocalDate(year, month, day)
} catch (_: IllegalArgumentException) {
    null
}

fun formatSearchTime(hour: Int, minute: Int, localeTag: String): String {
    require(hour in 0..23 && minute in 0..59)
    return if (searchLanguage(localeTag) == "it") {
        "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
    } else {
        val suffix = if (hour < 12) "AM" else "PM"
        val twelve = when (val h = hour % 12) {
            0 -> 12
            else -> h
        }
        "$twelve:${minute.toString().padStart(2, '0')} $suffix"
    }
}

/**
 * Parses a time field into hour/minute (24-hour range). Accepts the locale
 * 12/24-hour form plus 24-hour `HH:mm` in every locale. Returns null for
 * anything else — including a bare hour without minutes.
 */
fun parseSearchTime(text: String, localeTag: String): Pair<Int, Int>? {
    val trimmed = text.trim()
    parseTwentyFourHour(trimmed)?.let { return it }
    if (searchLanguage(localeTag) != "it") {
        parseTwelveHour(trimmed)?.let { return it }
    }
    return null
}

private val TWENTY_FOUR_HOUR = Regex("""^(\d{1,2}):(\d{2})$""")

private fun parseTwentyFourHour(text: String): Pair<Int, Int>? {
    val match = TWENTY_FOUR_HOUR.matchEntire(text) ?: return null
    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].toInt()
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour to minute
}

private val TWELVE_HOUR = Regex("""^(\d{1,2}):(\d{2})\s*([AaPp])\.?\s*[Mm]\.?$""")

private fun parseTwelveHour(text: String): Pair<Int, Int>? {
    val match = TWELVE_HOUR.matchEntire(text) ?: return null
    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].toInt()
    if (hour !in 1..12 || minute !in 0..59) return null
    val pm = match.groupValues[3].lowercase() == "p"
    val twentyFour = when {
        hour == 12 && !pm -> 0
        hour == 12 -> 12
        pm -> hour + 12
        else -> hour
    }
    return twentyFour to minute
}
