package it.danielebufarini.trenify.app

import it.danielebufarini.trenify.core.ui.formatRailwayDate
import it.danielebufarini.trenify.core.ui.formatRailwayDateTime
import it.danielebufarini.trenify.core.ui.formatRailwayTime
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Locale-aware railway date/time formatting (T7.14-C): railway instants are
 * always interpreted in Europe/Rome while ordering, month names and
 * separators follow the locale tag. Explicit tags keep the test
 * deterministic on both legs without touching the platform locale.
 */
class RailwayDateTimeFormatTest {
    @Test fun englishAndItalianDifferButShareRomeWallTime() {
        val instant = Instant.parse("2026-09-05T08:00:00Z")
        val en = formatRailwayDateTime(instant, "en")
        val it = formatRailwayDateTime(instant, "it")
        // Rome is UTC+2 in September: 08:00Z is 10:00 local.
        assertTrue(en.contains("2026") && en.contains("Sep") && en.contains("10:00"), en)
        assertTrue(it.contains("2026") && it.contains("set") && it.contains("10:00"), it)
        assertNotEquals(en, it)
    }

    @Test fun springForwardSkipsTheMissingHour() {
        // 2026-03-29: at 02:00 local, clocks jump to 03:00 (UTC+1 -> UTC+2).
        val before = formatRailwayTime(Instant.parse("2026-03-29T00:30:00Z"), "it")
        val after = formatRailwayTime(Instant.parse("2026-03-29T01:30:00Z"), "it")
        assertTrue(before.contains("1:30"), before)
        assertTrue(after.contains("3:30"), after)
        val beforeEn = formatRailwayTime(Instant.parse("2026-03-29T00:30:00Z"), "en")
        assertTrue(beforeEn.contains("1:30"), beforeEn)
    }

    @Test fun fallBackRepeatsTheFoldedHour() {
        // 2026-10-25: at 03:00 local, clocks return to 02:00 (UTC+2 -> UTC+1).
        val first = formatRailwayTime(Instant.parse("2026-10-25T00:30:00Z"), "it")
        val second = formatRailwayTime(Instant.parse("2026-10-25T01:30:00Z"), "it")
        assertTrue(first.contains("2:30"), first)
        assertTrue(second.contains("2:30"), second)
    }

    @Test fun serviceDatesAreLocaleAware() {
        val date = LocalDate(2026, 9, 5)
        val en = formatRailwayDate(date, "en")
        val it = formatRailwayDate(date, "it")
        assertTrue(en.contains("2026") && en.contains("Sep"), en)
        assertTrue(it.contains("2026") && it.contains("set"), it)
        assertNotEquals(en, it)
    }
}
