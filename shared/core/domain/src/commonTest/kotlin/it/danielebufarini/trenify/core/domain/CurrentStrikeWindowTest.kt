package it.danielebufarini.trenify.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class CurrentStrikeWindowTest {
    private val policy = StrikePolicy()

    @Test
    fun windowDerivesBoundsFromASingleCapturedNow() {
        var reads = 0
        var tick = Instant.parse("2026-09-05T08:00:00Z")
        val clock = object : kotlin.time.Clock {
            override fun now(): Instant {
                reads++
                return tick.also { tick += 7.milliseconds }
            }
        }
        val window = policy.currentWindow(clock)
        // Exactly one clock read per logical refresh: a ticking clock can
        // never stretch the span past the policy duration.
        assertEquals(1, reads)
        assertEquals(policy.historyWindow + policy.futureWindow, window.to - window.from)
    }

    @Test
    fun refreshBucketIsStableInsideTheQuantumAndChangesAcrossIt() {
        val bucket = policy.refreshBucket(Instant.parse("2026-09-05T08:00:00Z"))
        // A bucket start is strictly inside its own quantum afterwards.
        assertEquals(bucket, policy.refreshBucket(bucket + 1.minutes))
        assertEquals(bucket, policy.refreshBucket(bucket + 44.minutes))
        assertNotEquals(bucket, policy.refreshBucket(bucket + 45.minutes))
    }
}
