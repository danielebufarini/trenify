package it.danielebufarini.trenify.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Product journey-search window (T8 latency decision 2026-09-18): the search
 * horizon is 8 hours of instant arithmetic from the requested instant, not a
 * calendar day. Providers scope pagination to [departureFrom]/[departureUntil].
 */
class JourneySearchWindowTest {
    private val origin = Station(StationId("test:monza"), "Monza")
    private val destination = Station(StationId("test:milano-centrale"), "Milano Centrale")

    private fun journey(departure: Instant, arrival: Instant = departure + 30.minutes) = Journey(
        listOf(JourneyLeg(origin, destination, departure, arrival, null, null, null)),
        emptySet(),
    )

    @Test fun horizonIsEightHours() {
        assertEquals(8.hours, JourneySearchRequest.horizon)
    }

    @Test fun departAfterWindowStartsAtRequestAndSpansEightHours() {
        val at = Instant.parse("2026-09-18T10:00:00Z")
        val request = JourneySearchRequest(origin, destination, at, JourneySearchMode.DEPART_AFTER)
        assertEquals(at, request.departureFrom)
        assertEquals(at + 8.hours, request.departureUntil)
    }

    @Test fun arriveByWindowEndsAtRequestAndLooksBackEightHours() {
        val at = Instant.parse("2026-09-18T10:00:00Z")
        val request = JourneySearchRequest(origin, destination, at, JourneySearchMode.ARRIVE_BY)
        assertEquals(at - 8.hours, request.departureFrom)
        assertEquals(at, request.departureUntil)
    }

    @Test fun boundaryDeparturesAreIncludedAndOutsideAreExcluded() {
        val at = Instant.parse("2026-09-18T10:00:00Z")
        val request = JourneySearchRequest(origin, destination, at, JourneySearchMode.DEPART_AFTER)
        assertTrue(journey(request.departureFrom).matches(request))
        assertTrue(journey(request.departureUntil).matches(request))
        assertFalse(journey(request.departureFrom - 1.seconds).matches(request))
        assertFalse(journey(request.departureUntil + 1.seconds).matches(request))
    }

    @Test fun midnightCrossingUsesInstantArithmeticRatherThanCalendarDay() {
        // 22:00 Rome, mid-September (UTC+2): the 8-hour window ends at 06:00
        // Rome the next calendar day. A journey after midnight still matches;
        // "8 hours" is not "same calendar day".
        val at = Instant.parse("2026-09-18T20:00:00Z")
        val request = JourneySearchRequest(origin, destination, at, JourneySearchMode.DEPART_AFTER)
        assertEquals(Instant.parse("2026-09-19T04:00:00Z"), request.departureUntil)
        assertTrue(journey(Instant.parse("2026-09-19T03:00:00Z")).matches(request))
        assertFalse(journey(Instant.parse("2026-09-19T05:00:00Z")).matches(request))
    }

    @Test fun arriveByExcludesLateArrivalsEvenWhenDepartureIsInWindow() {
        val at = Instant.parse("2026-09-18T10:00:00Z")
        val request = JourneySearchRequest(origin, destination, at, JourneySearchMode.ARRIVE_BY)
        assertTrue(journey(at - 2.hours, at - 1.hours).matches(request))
        assertFalse(journey(at - 2.hours, at + 1.minutes).matches(request))
    }
}
