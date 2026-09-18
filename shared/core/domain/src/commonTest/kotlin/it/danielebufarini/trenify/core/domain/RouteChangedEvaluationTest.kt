package it.danielebufarini.trenify.core.domain

import it.danielebufarini.trenify.core.model.*
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * T7.11 route-change evaluation: structural added/removed/reordered stops
 * emit RouteChanged from stable station IDs; presentation-only differences
 * never do; cancellation evidence stays with cancellation events.
 */
class RouteChangedEvaluationTest {
    private val id = TrainRunId(ProviderId("test"), TrainNumber("42"), ExternalStationRef("origin"), LocalDate.parse("2026-09-05"))
    private val roma = Station(StationId("roma"), "Roma Termini")
    private val firenze = Station(StationId("firenze"), "Firenze S.M.N.")
    private val bologna = Station(StationId("bologna"), "Bologna Centrale")
    private val milano = Station(StationId("milano"), "Milano Centrale")

    private fun stop(station: Station) = TrainStop(station, scheduledDeparture = Instant.parse("2026-09-05T08:00:00Z"))
    private val base = TrainRun(
        TrainRunSummary(id, roma, milano.name, status = TrainStatus.RUNNING, delayMinutes = 5),
        listOf(stop(roma), stop(firenze), stop(bologna)),
    )

    private fun monitor(previous: TrainRun = base) = TrainMonitor(
        MonitorId(id.key), id, true, MonitorThresholds(delayMinutes = 15),
        Instant.parse("2026-09-05T07:00:00Z"), null,
        MonitoredTrainSnapshot(previous, DataFreshness.Unknown, Instant.parse("2026-09-05T07:30:00Z")),
    )

    private val evaluate = EvaluateTrainChanges()

    @Test fun addedStopEmitsSingleRouteChanged() {
        val current = base.copy(stops = base.stops + stop(milano))
        val event = evaluate(monitor(), current).single()
        assertIs<TrainMonitorEvent.RouteChanged>(event)
        assertEquals(listOf("milano"), event.addedStationIds)
        assertTrue(event.removedStationIds.isEmpty())
        // Repeating the identical snapshot emits nothing.
        assertTrue(evaluate(monitor(current), current).isEmpty())
    }

    @Test fun removedStopEmitsSingleRouteChanged() {
        val current = base.copy(stops = listOf(stop(roma), stop(bologna)))
        val event = evaluate(monitor(), current).single()
        assertIs<TrainMonitorEvent.RouteChanged>(event)
        assertTrue(event.addedStationIds.isEmpty())
        assertEquals(listOf("firenze"), event.removedStationIds)
        assertTrue(evaluate(monitor(current), current).isEmpty())
    }

    @Test fun reorderedStopsEmitRouteChanged() {
        val current = base.copy(stops = listOf(stop(roma), stop(bologna), stop(firenze)))
        val event = evaluate(monitor(), current).single()
        assertIs<TrainMonitorEvent.RouteChanged>(event)
        assertTrue(evaluate(monitor(current), current).isEmpty())
    }

    @Test fun timestampDelayAndPlatformOnlyDifferencesAreNotRouteChanges() {
        val retimed = base.copy(
            stops = base.stops.map { it.copy(scheduledDeparture = Instant.parse("2026-09-05T08:10:00Z")) },
        )
        assertTrue(evaluate(monitor(), retimed).none { it is TrainMonitorEvent.RouteChanged })

        val delayed = base.copy(summary = base.summary.copy(delayMinutes = 40))
        assertTrue(evaluate(monitor(), delayed).none { it is TrainMonitorEvent.RouteChanged })

        val platforms = base.copy(stops = base.stops.map { it.copy(actualPlatform = "9") })
        assertTrue(evaluate(monitor(), platforms).none { it is TrainMonitorEvent.RouteChanged })

        // Provider response reorder without route-semantic change is
        // impossible to distinguish from a reorder by order alone, but a pure
        // observation-timestamp refresh never is: same ids, same order.
        assertTrue(evaluate(monitor(), base).isEmpty())
    }

    @Test fun displayRenameWithStableIdIsNotARouteChange() {
        val renamed = base.copy(
            stops = base.stops.map { if (it.station.id == firenze.id) it.copy(station = Station(firenze.id, "Firenze Santa Maria Novella")) else it },
        )
        assertTrue(evaluate(monitor(), renamed).isEmpty())
    }

    @Test fun newlyCancelledStopIsPartialCancellationNotARouteChange() {
        val current = base.copy(
            stops = base.stops + TrainStop(milano, status = StopStatus.CANCELLED),
        )
        val events = evaluate(monitor(), current)
        assertEquals(1, events.size)
        assertIs<TrainMonitorEvent.PartiallyCancelled>(events.single())
    }

    @Test fun inPlaceCancellationIsPartialCancellationNotARouteChange() {
        val current = base.copy(
            summary = base.summary.copy(status = TrainStatus.PARTIALLY_CANCELLED),
            stops = base.stops.map { if (it.station.id == bologna.id) it.copy(status = StopStatus.CANCELLED) else it },
        )
        val events = evaluate(monitor(), current)
        assertEquals(1, events.size)
        assertIs<TrainMonitorEvent.PartiallyCancelled>(events.single())
    }

    @Test fun prunedCancelledStopIsNotARouteChange() {
        val previous = base.copy(
            stops = base.stops + TrainStop(milano, status = StopStatus.CANCELLED),
        )
        // The provider drops an already-cancelled stop from the list: pure
        // cancellation bookkeeping, not a served-route change.
        assertTrue(evaluate(monitor(previous), base).none { it is TrainMonitorEvent.RouteChanged })
    }

    @Test fun routeChangePlusReschedulingEmitsBothIndependentEvents() {
        val current = base.copy(
            summary = base.summary.copy(status = TrainStatus.RESCHEDULED),
            stops = base.stops + stop(milano),
        )
        val events = evaluate(monitor(), current)
        assertIs<TrainMonitorEvent.RouteChanged>(events.single { it is TrainMonitorEvent.RouteChanged })
        assertIs<TrainMonitorEvent.StatusChanged>(events.single { it is TrainMonitorEvent.StatusChanged })
        assertEquals(2, events.size)
    }

    @Test fun partialCancellationPlusRouteChangeEmitsBothIndependentEvents() {
        val current = base.copy(
            summary = base.summary.copy(status = TrainStatus.PARTIALLY_CANCELLED),
            stops = base.stops + stop(milano),
        )
        val events = evaluate(monitor(), current)
        assertIs<TrainMonitorEvent.PartiallyCancelled>(events.single { it is TrainMonitorEvent.PartiallyCancelled })
        assertIs<TrainMonitorEvent.RouteChanged>(events.single { it is TrainMonitorEvent.RouteChanged })
        assertEquals(2, events.size)
    }

    @Test fun unknownEvidenceGeneratesNoKnownEvents() {
        val unknown = base.copy(
            summary = base.summary.copy(status = TrainStatus.UNKNOWN, delayMinutes = null),
            stops = base.stops.map { it.copy(status = StopStatus.UNKNOWN, delayMinutes = null) },
        )
        // Status UNKNOWN transitions are not DIVERTED/RESCHEDULED, cancelled
        // stops are untouched, ids are unchanged: nothing known to report.
        val fromRunning = evaluate(monitor(), unknown)
        assertTrue(fromRunning.isEmpty())
        assertTrue(evaluate(monitor(unknown), unknown).isEmpty())
    }
}
