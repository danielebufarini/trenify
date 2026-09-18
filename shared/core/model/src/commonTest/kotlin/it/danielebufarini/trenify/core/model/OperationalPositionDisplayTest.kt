package it.danielebufarini.trenify.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

// T7.10 FR-POSITION-002: only a station together with its own source
// observation timestamp is a complete position indication. Every incomplete
// combination renders as a neutral unavailable state; timestamps are never
// fabricated from fetch, cache, scheduled or current time.
class OperationalPositionDisplayTest {
    private val observed = Instant.parse("2026-09-05T05:38:00Z")

    @Test fun stationAndTimestampAreComplete() {
        assertEquals(
            OperationalPositionDisplay.Known("BOLOGNA CENTRALE", observed),
            operationalPositionDisplay(OperationalPosition("BOLOGNA CENTRALE", observed)),
        )
    }

    @Test fun stationWithoutTimestampIsUnavailable() {
        assertIs<OperationalPositionDisplay.Unavailable>(
            operationalPositionDisplay(OperationalPosition("BOLOGNA CENTRALE", null)),
        )
    }

    @Test fun timestampWithoutStationIsUnavailable() {
        assertIs<OperationalPositionDisplay.Unavailable>(
            operationalPositionDisplay(OperationalPosition(null, observed)),
        )
    }

    @Test fun absentPositionIsUnavailable() {
        assertIs<OperationalPositionDisplay.Unavailable>(operationalPositionDisplay(null))
        assertIs<OperationalPositionDisplay.Unavailable>(
            operationalPositionDisplay(OperationalPosition(null, null)),
        )
    }
}
