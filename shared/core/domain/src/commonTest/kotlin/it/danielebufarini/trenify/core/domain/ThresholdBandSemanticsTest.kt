package it.danielebufarini.trenify.core.domain

import it.danielebufarini.trenify.core.model.*
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * T7.7 threshold-band semantics, kept unchanged: equality reaches the
 * threshold band and recovery back below it notifies again.
 */
class ThresholdBandSemanticsTest {
    private val id = TrainRunId(ProviderId("test"), TrainNumber("42"), ExternalStationRef("origin"), LocalDate.parse("2026-09-05"))
    private val origin = Station(StationId("origin"), "Roma Termini")
    private val base = TrainRun(
        TrainRunSummary(id, origin, "Milano", status = TrainStatus.RUNNING, delayMinutes = 5),
        listOf(TrainStop(origin, scheduledDeparture = Instant.parse("2026-09-05T08:00:00Z"))),
    )
    private val evaluate = EvaluateTrainChanges()

    private fun monitor(previous: TrainRun = base, threshold: Int = 15) = TrainMonitor(
        MonitorId(id.key), id, true, MonitorThresholds(delayMinutes = threshold),
        Instant.parse("2026-09-05T07:00:00Z"), null,
        MonitoredTrainSnapshot(previous, DataFreshness.Unknown, Instant.parse("2026-09-05T07:30:00Z")),
    )

    private fun delayed(minutes: Int) = base.copy(summary = base.summary.copy(delayMinutes = minutes))

    @Test fun reachingTheThresholdExactlyNotifies() {
        val event = evaluate(monitor(), delayed(15)).single()
        assertIs<TrainMonitorEvent.DelayThresholdCrossed>(event)
        assertEquals(5, event.previousMinutes)
        assertEquals(15, event.currentMinutes)
        assertEquals(15, event.thresholdMinutes)
    }

    @Test fun recoveryBelowTheThresholdNotifiesAgain() {
        val recovered = evaluate(monitor(delayed(20)), delayed(5)).single()
        assertIs<TrainMonitorEvent.DelayThresholdCrossed>(recovered)
        assertEquals(20, recovered.previousMinutes)
        assertEquals(5, recovered.currentMinutes)
    }

    @Test fun repeatedValuesInTheSameBandStaySilent() {
        assertTrue(evaluate(monitor(delayed(20)), delayed(25)).isEmpty())
        assertTrue(evaluate(monitor(), delayed(14)).isEmpty())
    }

    @Test fun unknownPreviousDelayNeverFabricatesACrossing() {
        // Unknown remains unknown: null -> 20 establishes no transition and
        // must never render as "from 0".
        val unknown = base.copy(summary = base.summary.copy(delayMinutes = null))
        assertTrue(evaluate(monitor(unknown), delayed(20)).isEmpty())
        assertTrue(evaluate(monitor(unknown), unknown).isEmpty())
    }

    @Test fun knownZeroPreviousDelayStillCrosses() {
        // A genuinely observed zero is a known value and keeps working.
        val onTime = base.copy(summary = base.summary.copy(delayMinutes = 0))
        val event = evaluate(monitor(onTime), delayed(20)).single()
        assertIs<TrainMonitorEvent.DelayThresholdCrossed>(event)
        assertEquals(0, event.previousMinutes)
        assertEquals(20, event.currentMinutes)
    }
}
