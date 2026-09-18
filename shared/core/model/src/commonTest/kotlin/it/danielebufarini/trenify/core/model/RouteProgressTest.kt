package it.danielebufarini.trenify.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// T7.10: evidence-aware route progress. NEXT is a qualified "next stop"
// marker derived from stop order and observations — never a current-station
// observation and never wall-clock timetable comparison.
class RouteProgressTest {
    private val milano = Station(StationId("s-milano"), "Milano Centrale")
    private val bologna = Station(StationId("s-bologna"), "Bologna Centrale")
    private val firenze = Station(StationId("s-firenze"), "Firenze S.M.N.")
    private val roma = Station(StationId("s-roma"), "Roma Termini")

    private fun stop(station: Station, status: StopStatus) = TrainStop(station, status = status)

    @Test fun emptyRouteHasNoProgress() {
        assertTrue(routeProgress(emptyList()).isEmpty())
    }

    @Test fun runningTrainDistinguishesCompletedNextAndFuture() {
        val progress = routeProgress(listOf(
            stop(milano, StopStatus.COMPLETED),
            stop(bologna, StopStatus.COMPLETED),
            stop(firenze, StopStatus.SCHEDULED),
            stop(roma, StopStatus.SCHEDULED),
        ))
        assertEquals(
            listOf(StopProgress.COMPLETED, StopProgress.COMPLETED, StopProgress.NEXT, StopProgress.FUTURE),
            progress,
        )
    }

    @Test fun notDepartedTrainMarksOriginNextWithoutClockEvidence() {
        val progress = routeProgress(listOf(
            stop(milano, StopStatus.SCHEDULED),
            stop(bologna, StopStatus.SCHEDULED),
        ))
        assertEquals(listOf(StopProgress.NEXT, StopProgress.FUTURE), progress)
    }

    @Test fun cancellationStaysDistinctFromDelayFutureAndUnknown() {
        val progress = routeProgress(listOf(
            stop(milano, StopStatus.COMPLETED),
            stop(bologna, StopStatus.CANCELLED),
            stop(firenze, StopStatus.SCHEDULED),
            stop(roma, StopStatus.UNKNOWN),
        ))
        assertEquals(
            listOf(StopProgress.COMPLETED, StopProgress.CANCELLED, StopProgress.NEXT, StopProgress.UNKNOWN),
            progress,
        )
    }

    @Test fun contradictoryOrderingResolvesToUnknownInsteadOfGuessing() {
        val progress = routeProgress(listOf(
            stop(milano, StopStatus.SCHEDULED),
            stop(bologna, StopStatus.COMPLETED),
            stop(firenze, StopStatus.SCHEDULED),
        ))
        assertEquals(listOf(StopProgress.UNKNOWN, StopProgress.COMPLETED, StopProgress.NEXT), progress)
    }

    @Test fun fullyCompletedRouteHasNoNextStop() {
        val progress = routeProgress(listOf(
            stop(milano, StopStatus.COMPLETED),
            stop(roma, StopStatus.COMPLETED),
        ))
        assertEquals(listOf(StopProgress.COMPLETED, StopProgress.COMPLETED), progress)
    }

    @Test fun allUnknownStopsStayUnknown() {
        val progress = routeProgress(listOf(
            stop(milano, StopStatus.UNKNOWN),
            stop(roma, StopStatus.UNKNOWN),
        ))
        assertEquals(listOf(StopProgress.UNKNOWN, StopProgress.UNKNOWN), progress)
    }

    @Test fun unknownBarrierVetoesLaterNext() {
        // COMPLETED, UNKNOWN, SCHEDULED: the scheduled stop past the
        // uninterpretable evidence stays FUTURE, never NEXT.
        assertEquals(
            listOf(StopProgress.COMPLETED, StopProgress.UNKNOWN, StopProgress.FUTURE),
            routeProgress(listOf(
                stop(milano, StopStatus.COMPLETED),
                stop(bologna, StopStatus.UNKNOWN),
                stop(firenze, StopStatus.SCHEDULED),
            )),
        )
    }

    @Test fun leadingUnknownBarrierVetoesNext() {
        // UNKNOWN, SCHEDULED: with no completed stop and an unresolved first
        // stop, the scheduled stop cannot be proven next.
        assertEquals(
            listOf(StopProgress.UNKNOWN, StopProgress.FUTURE),
            routeProgress(listOf(
                stop(milano, StopStatus.UNKNOWN),
                stop(bologna, StopStatus.SCHEDULED),
            )),
        )
    }

    @Test fun unknownBarrierVetoesNextAcrossLongerRoutes() {
        // COMPLETED, UNKNOWN, SCHEDULED, SCHEDULED: nothing past the barrier
        // is NEXT, however many scheduled stops follow it.
        assertEquals(
            listOf(StopProgress.COMPLETED, StopProgress.UNKNOWN, StopProgress.FUTURE, StopProgress.FUTURE),
            routeProgress(listOf(
                stop(milano, StopStatus.COMPLETED),
                stop(bologna, StopStatus.UNKNOWN),
                stop(firenze, StopStatus.SCHEDULED),
                stop(roma, StopStatus.SCHEDULED),
            )),
        )
    }

    @Test fun cancelledStopsDoNotBlockNext() {
        // Cancelled stops are skipped by the train, so they are not evidence
        // barriers: the first scheduled stop after them is still NEXT.
        assertEquals(
            listOf(StopProgress.COMPLETED, StopProgress.CANCELLED, StopProgress.NEXT, StopProgress.FUTURE),
            routeProgress(listOf(
                stop(milano, StopStatus.COMPLETED),
                stop(bologna, StopStatus.CANCELLED),
                stop(firenze, StopStatus.SCHEDULED),
                stop(roma, StopStatus.SCHEDULED),
            )),
        )
    }
}
