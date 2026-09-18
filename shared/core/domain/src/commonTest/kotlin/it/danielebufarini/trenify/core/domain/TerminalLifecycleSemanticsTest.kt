package it.danielebufarini.trenify.core.domain

import it.danielebufarini.trenify.core.model.TrainStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * T7.11 terminal versus non-terminal states and 24-hour retention semantics.
 */
class TerminalLifecycleSemanticsTest {

    @Test fun onlyArrivedAndFinalCancelledAreTerminal() {
        assertTrue(isTerminalStatus(TrainStatus.ARRIVED))
        assertTrue(isTerminalStatus(TrainStatus.CANCELLED))
        assertFalse(isTerminalStatus(TrainStatus.NOT_DEPARTED))
        assertFalse(isTerminalStatus(TrainStatus.RUNNING))
        assertFalse(isTerminalStatus(TrainStatus.PARTIALLY_CANCELLED))
        assertFalse(isTerminalStatus(TrainStatus.DIVERTED))
        assertFalse(isTerminalStatus(TrainStatus.RESCHEDULED))
        assertFalse(isTerminalStatus(TrainStatus.UNKNOWN))
    }

    @Test fun retentionIsExactly24ElapsedHours() {
        assertEquals(24.hours, MONITOR_POST_TERMINAL_RETENTION)
    }

    @Test fun endedAtDerivesVisibleUntilAndExpiry() {
        val endedAt = Instant.parse("2026-09-05T08:00:00Z")
        val monitor = endedMonitor(endedAt)
        assertTrue(monitor.isEnded)
        assertEquals(endedAt + 24.hours, monitor.visibleUntil())
        assertFalse(monitor.isRetentionExpired(endedAt))
        assertFalse(monitor.isRetentionExpired(endedAt + 24.hours - kotlin.time.Duration.parse("1ms")))
        assertFalse(monitor.isRetentionExpired(endedAt + 23.hours + kotlin.time.Duration.parse("59m")))
        // At exactly the deadline the monitor must no longer be exposed.
        assertTrue(monitor.isRetentionExpired(endedAt + 24.hours))
        assertTrue(monitor.isRetentionExpired(endedAt + 25.hours))
    }

    @Test fun activeMonitorHasNoDeadlineAndNeverExpires() {
        val monitor = activeMonitor()
        assertFalse(monitor.isEnded)
        assertNull(monitor.visibleUntil())
        assertFalse(monitor.isRetentionExpired(Instant.parse("2030-01-01T00:00:00Z")))
    }

    @Test fun retentionIsDurationSemanticsAcrossRomeDstTransitions() {
        // Europe/Rome fall-back 2026-10-25 03:00 CEST -> 02:00 CET: 24 elapsed
        // hours end at a different local wall-clock time, proving retention
        // is not "the same local time tomorrow" (which would be 25 hours).
        val fallBack = Instant.parse("2026-10-25T00:30:00Z")
        assertEquals(Instant.parse("2026-10-26T00:30:00Z"), endedMonitor(fallBack).visibleUntil())
        assertTrue(endedMonitor(fallBack).isRetentionExpired(Instant.parse("2026-10-26T00:30:00Z")))
        assertFalse(endedMonitor(fallBack).isRetentionExpired(Instant.parse("2026-10-26T00:29:59.999Z")))

        // Europe/Rome spring-forward 2026-03-29 02:00 CET -> 03:00 CEST.
        val springForward = Instant.parse("2026-03-28T12:00:00Z")
        assertEquals(Instant.parse("2026-03-29T12:00:00Z"), endedMonitor(springForward).visibleUntil())
        assertTrue(endedMonitor(springForward).isRetentionExpired(Instant.parse("2026-03-29T12:00:00Z")))
        assertFalse(endedMonitor(springForward).isRetentionExpired(Instant.parse("2026-03-29T11:59:59.999Z")))
    }

    private fun endedMonitor(endedAt: Instant) = TrainMonitor(
        MonitorId("m"),
        it.danielebufarini.trenify.core.testing.testRunId,
        true,
        MonitorThresholds(),
        Instant.parse("2026-09-05T07:00:00Z"),
        null,
        endedAt = endedAt,
    )

    private fun activeMonitor() = endedMonitor(Instant.parse("2026-09-05T08:00:00Z")).copy(endedAt = null)
}
