package it.danielebufarini.trenify.core.domain

import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.testing.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes

class ScheduledPlatformCorrelationTest {
    private val leg = testJourney.legs.single()
    private fun run(destination: Station = leg.destination) = TrainRun(
        testSummary.copy(operator = leg.operator), listOf(
            TrainStop(leg.origin, scheduledDeparture = leg.departure, scheduledPlatform = "7"),
            TrainStop(destination, scheduledArrival = leg.arrival),
        ),
    )

    @Test fun exactBoardingScheduleSuppliesPlannedPlatformWithoutDestinationCorrelation() = runTest {
        val trains = FakeRealtimeRepositories().apply {
            trainState.value = DataResult.Data(run(Station(StationId("other-provider-destination"), "Different destination label")),
                DataFreshness.Fresh(MutableClock().now(), null))
        }
        var planned: String? = null
        assertNull(CorrelateJourneyLeg(trains)(leg) { planned = it })
        assertEquals("7", planned)
        assertEquals(1, trains.trainRefreshes)
    }

    @Test fun validRealtimeCorrelationRetainsItsExistingRunIdentityAndPlatform() = runTest {
        val trains = FakeRealtimeRepositories().apply {
            trainState.value = DataResult.Data(run(), DataFreshness.Fresh(MutableClock().now(), null))
        }
        var planned: String? = null
        assertEquals(testRunId, CorrelateJourneyLeg(trains)(leg) { planned = it })
        assertEquals("7", planned)
    }

    @Test fun wrongRunIdentityCannotSupplyPlannedPlatform() = runTest {
        val trains = FakeRealtimeRepositories().apply {
            val wrong = run().let { it.copy(summary = it.summary.copy(id = testRunId.copy(number = TrainNumber("999")))) }
            trainState.value = DataResult.Data(wrong, DataFreshness.Fresh(MutableClock().now(), null))
        }
        var planned: String? = null
        assertNull(CorrelateJourneyLeg(trains)(leg) { planned = it })
        assertNull(planned)
    }

    @Test fun wrongBoardingTimeCannotSupplyPlannedPlatform() = runTest {
        val trains = FakeRealtimeRepositories().apply {
            val wrong = run().let { it.copy(stops = it.stops.map { stop ->
                if (stop.scheduledDeparture != null) stop.copy(scheduledDeparture = leg.departure + 1.minutes) else stop
            }) }
            trainState.value = DataResult.Data(wrong, DataFreshness.Fresh(MutableClock().now(), null))
        }
        var planned: String? = null
        assertNull(CorrelateJourneyLeg(trains)(leg) { planned = it })
        assertNull(planned)
    }
}
