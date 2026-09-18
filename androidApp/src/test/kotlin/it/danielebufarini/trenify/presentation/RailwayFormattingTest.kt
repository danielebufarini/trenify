package it.danielebufarini.trenify.presentation

import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T8.12: railway date/time interpretation stays Europe/Rome even when the
 * host timezone differs, while day/month words follow the device locale
 * (mirroring iOS `.autoupdatingCurrent`). Oracles are explicit-offset ISO
 * instants, never the formatter under test.
 */
class RailwayFormattingTest {
    private fun <T> withHostZoneAndLocale(zone: String, locale: Locale, block: () -> T): T {
        val priorZone = TimeZone.getDefault()
        val priorLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        Locale.setDefault(locale)
        try {
            return block()
        } finally {
            TimeZone.setDefault(priorZone)
            Locale.setDefault(priorLocale)
        }
    }

    @Test fun timesRenderRomeWallTimeUnderForeignHostZone() {
        val epoch = Instant.parse("2026-01-15T14:30:00+01:00").epochSecond
        withHostZoneAndLocale("America/New_York", Locale.US) {
            assertEquals("14:30", railwayTime(epoch))
        }
    }

    @Test fun cestWallTimeUnderForeignHostZone() {
        val epoch = Instant.parse("2026-07-15T14:30:00+02:00").epochSecond
        withHostZoneAndLocale("Pacific/Auckland", Locale.US) {
            assertEquals("14:30", railwayTime(epoch))
        }
    }

    @Test fun romeMidnightBoundarySurvivesUtcDateShift() {
        // Rome midnight 2026-03-01 is 2026-02-28T23:00Z: the host UTC
        // calendar day differs from the Rome wall date.
        val epoch = Instant.parse("2026-03-01T00:00:00+01:00").epochSecond
        withHostZoneAndLocale("UTC", Locale.US) {
            assertEquals("00:00", railwayTime(epoch))
            assertEquals("1 Mar", railwayDate(epoch))
        }
    }

    @Test fun monthAbbreviationFollowsDeviceLocale() {
        val epoch = Instant.parse("2026-09-05T10:00:00+02:00").epochSecond
        val english = withHostZoneAndLocale("Europe/Rome", Locale.US) { railwayDate(epoch) }
        val italian = withHostZoneAndLocale("Europe/Rome", Locale.ITALIAN) { railwayDate(epoch) }
        assertEquals("5 Sep", english)
        assertEquals("5 set", italian)
    }
}
