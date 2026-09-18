package it.danielebufarini.trenify.home

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class DatePickerDatesTest {
    @Test fun utcMidnightRepresentsTheSameCalendarDayInCet() {
        // 2026-01-15 is CET (UTC+1): Rome midnight is 2026-01-14T23:00Z, so it
        // would display the previous day. The UTC-date convention is exact.
        val date = LocalDate(2026, 1, 15)
        val millis = datePickerMillis(date)
        assertEquals(date, semanticDateFromPicker(millis))
        assertEquals(LocalDate(2026, 1, 15), semanticDateFromPicker(millis))
    }

    @Test fun utcMidnightRepresentsTheSameCalendarDayInCest() {
        // 2026-07-15 is CEST (UTC+2): Rome midnight is 2026-07-14T22:00Z.
        val date = LocalDate(2026, 7, 15)
        assertEquals(date, semanticDateFromPicker(datePickerMillis(date)))
    }

    @Test fun monthBoundaryRoundTripsInBothOffsets() {
        // 2026-03-01 is CET: Rome midnight is 2026-02-28T23:00Z.
        assertEquals(LocalDate(2026, 3, 1), semanticDateFromPicker(datePickerMillis(LocalDate(2026, 3, 1))))
        // 2026-09-01 is CEST: Rome midnight is 2026-08-31T22:00Z.
        assertEquals(LocalDate(2026, 9, 1), semanticDateFromPicker(datePickerMillis(LocalDate(2026, 9, 1))))
    }

    @Test fun dstTransitionDaysRoundTrip() {
        // Spring forward (2026-03-29) and fall back (2026-10-25) in Rome.
        assertEquals(LocalDate(2026, 3, 29), semanticDateFromPicker(datePickerMillis(LocalDate(2026, 3, 29))))
        assertEquals(LocalDate(2026, 10, 25), semanticDateFromPicker(datePickerMillis(LocalDate(2026, 10, 25))))
    }

    @Test fun pickerMillisIsUtcMidnightOfTheSameDate() {
        // 2026-03-01T00:00Z, independent of any zone database lookup for Rome.
        assertEquals(1772323200000L, datePickerMillis(LocalDate(2026, 3, 1)))
    }
}
