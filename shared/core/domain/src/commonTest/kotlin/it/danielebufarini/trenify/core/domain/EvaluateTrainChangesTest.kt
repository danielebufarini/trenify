package it.danielebufarini.trenify.core.domain

import it.danielebufarini.trenify.core.model.*
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class EvaluateTrainChangesTest {
    private val id = TrainRunId(ProviderId("test"), TrainNumber("42"), ExternalStationRef("origin"), LocalDate.parse("2026-09-05"))
    private val origin = Station(StationId("origin"), "Roma Termini")
    private val destination = Station(StationId("destination"), "Milano Centrale")
    private val base = TrainRun(
        TrainRunSummary(id, origin, destination.name, status = TrainStatus.NOT_DEPARTED, delayMinutes = 5),
        listOf(TrainStop(origin, scheduledDeparture = Instant.parse("2026-09-05T08:00:00Z"), actualPlatform = "8")),
    )
    private fun monitor(previous: TrainRun = base) = TrainMonitor(
        MonitorId(id.key), id, true, MonitorThresholds(delayMinutes = 15),
        Instant.parse("2026-09-05T07:00:00Z"), null,
        MonitoredTrainSnapshot(previous, DataFreshness.Unknown, Instant.parse("2026-09-05T07:30:00Z")),
    )
    private val evaluate = EvaluateTrainChanges()

    @Test fun delayOnlyEmitsWhenThresholdBandIsCrossed() {
        assertTrue(evaluate(monitor(), base.copy(summary = base.summary.copy(delayMinutes = 6))).isEmpty())
        val event = evaluate(monitor(), base.copy(summary = base.summary.copy(delayMinutes = 20))).single()
        assertIs<TrainMonitorEvent.DelayThresholdCrossed>(event)
        assertEquals(20, event.currentMinutes)
        val same = base.copy(summary = base.summary.copy(delayMinutes = 20))
        assertTrue(evaluate(monitor(same), same).isEmpty())
    }

    @Test fun cancellationAndPartialCancellationAreDetectedOnce() {
        assertIs<TrainMonitorEvent.Cancelled>(
            evaluate(monitor(), base.copy(summary = base.summary.copy(status = TrainStatus.CANCELLED))).single(),
        )
        val partial = base.copy(stops = base.stops + TrainStop(destination, status = StopStatus.CANCELLED))
        assertIs<TrainMonitorEvent.PartiallyCancelled>(evaluate(monitor(), partial).single())
        assertTrue(evaluate(monitor(partial), partial).isEmpty())
    }

    @Test fun finalCancellationWithCancelledStopsEmitsCancelledOnly() {
        val terminal = base.copy(
            summary = base.summary.copy(status = TrainStatus.CANCELLED),
            stops = base.stops.map { it.copy(status = StopStatus.CANCELLED) },
        )
        val events = evaluate(monitor(), terminal)
        assertEquals(1, events.size)
        assertIs<TrainMonitorEvent.Cancelled>(events.single())
    }

    @Test fun partialThenFinalCancellationEmitsOneTerminalEvent() {
        val partial = base.copy(
            summary = base.summary.copy(status = TrainStatus.PARTIALLY_CANCELLED),
            stops = base.stops.map { it.copy(status = StopStatus.CANCELLED) },
        )
        assertIs<TrainMonitorEvent.PartiallyCancelled>(evaluate(monitor(), partial).single())
        val terminal = partial.copy(
            summary = partial.summary.copy(status = TrainStatus.CANCELLED),
            stops = partial.stops.map { it.copy(status = StopStatus.CANCELLED) },
        )
        val events = evaluate(monitor(partial), terminal)
        assertEquals(1, events.size)
        assertIs<TrainMonitorEvent.Cancelled>(events.single())
    }

    @Test fun platformAndScheduleChangesAreSemanticAndDeduplicated() {
        val platform = base.copy(stops = listOf(base.stops.single().copy(actualPlatform = "10")))
        assertIs<TrainMonitorEvent.PlatformChanged>(evaluate(monitor(), platform).single())
        assertTrue(evaluate(monitor(platform), platform).isEmpty())

        val schedule = base.copy(stops = listOf(base.stops.single().copy(
            scheduledDeparture = Instant.parse("2026-09-05T08:10:00Z"),
        )))
        assertIs<TrainMonitorEvent.ScheduleChanged>(evaluate(monitor(), schedule).single())
    }

    @Test fun departureAndArrivalTransitionsAreDetected() {
        val departed = base.copy(summary = base.summary.copy(status = TrainStatus.RUNNING))
        assertIs<TrainMonitorEvent.Departed>(evaluate(monitor(), departed).single())
        val arrived = departed.copy(summary = departed.summary.copy(status = TrainStatus.ARRIVED))
        assertIs<TrainMonitorEvent.Arrived>(evaluate(monitor(departed), arrived).single())
    }
}
