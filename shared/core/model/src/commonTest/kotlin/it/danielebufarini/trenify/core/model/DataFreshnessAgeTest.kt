package it.danielebufarini.trenify.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Display-age recomputation for elapsed local time (T7.14-B): Fresh carries
 * the instants that prove its age, so observers re-derive Stale from the
 * clock without a fetch. Stale and Unknown never become Fresh through time.
 */
class DataFreshnessAgeTest {
    private val t0 = Instant.parse("2026-09-05T08:00:00Z")
    private val ttl = 30.seconds

    @Test fun freshStaysFreshBeforeTtl() {
        val fresh = DataFreshness.Fresh(t0, null)
        assertEquals(fresh, fresh.aged(t0 + 29.seconds, ttl))
        assertEquals(t0 + 30.seconds, fresh.staleTransitionAt(ttl))
    }

    @Test fun freshBecomesStaleAtTtlBoundary() {
        val fresh = DataFreshness.Fresh(t0, null)
        val stale = fresh.aged(t0 + 30.seconds, ttl)
        assertIs<DataFreshness.Stale>(stale)
        assertEquals(t0, stale.fetchedAt)
        assertEquals(30.seconds, stale.age)
    }

    @Test fun sourceTimestampDrivesAgeWhenOlderThanFetch() {
        val fresh = DataFreshness.Fresh(fetchedAt = t0, sourceTimestamp = t0 - 5.minutes)
        val stale = fresh.aged(t0, ttl)
        assertIs<DataFreshness.Stale>(stale)
        assertEquals(5.minutes, stale.age)
        assertEquals(t0 - 5.minutes + ttl, fresh.staleTransitionAt(ttl))
    }

    @Test fun staleAndUnknownNeverBecomeFresh() {
        val stale = DataFreshness.Stale(t0, 90.seconds, null)
        assertEquals(stale, stale.aged(t0 + 90.seconds, ttl))
        assertNull(stale.staleTransitionAt(ttl))
        assertEquals(DataFreshness.Unknown, DataFreshness.Unknown.aged(t0 + 90.seconds, ttl))
        assertNull(DataFreshness.Unknown.staleTransitionAt(ttl))
    }
}
