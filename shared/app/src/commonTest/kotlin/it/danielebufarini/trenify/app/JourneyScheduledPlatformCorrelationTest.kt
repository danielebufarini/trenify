package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.testing.*
import it.danielebufarini.trenify.feature.journey.JourneyDetailComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.datetime.LocalDate
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class JourneyScheduledPlatformCorrelationTest {
    // Source-backed 9611 schedule observed on 2026-10-08. LeFrecce calls
    // the destination Bologna Centrale; ViaggiaTreno calls it BOLOGNA C.LE/AV.
    private val milano = Station(StationId("station:milano centrale"), "Milano Centrale")
    private val bologna = Station(StationId("station:bologna centrale"), "Bologna Centrale")
    private val departure = Instant.parse("2026-10-08T06:00:00Z")
    private val leg = JourneyLeg(milano, bologna, departure, departure + 64.minutes,
        TrainNumber("9611"), "Frecciarossa", Operator("Trenitalia"))
    private val id = TrainRunId(ProviderId("viaggiatreno"), leg.number!!,
        ExternalStationRef("S00219"), LocalDate.parse("2026-10-08"))

    private fun TestScope.verify(alias: Boolean = false, warning: Boolean = false, wrongBoardingTime: Boolean = false) {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            val summary = TrainRunSummary(id, Station(StationId("torino"), "Torino Porta Nuova"),
                "Napoli Centrale", operator = leg.operator, status = TrainStatus.NOT_DEPARTED)
            val run = TrainRun(summary, listOf(
                TrainStop(milano, scheduledDeparture = if (wrongBoardingTime) departure + 1.minutes else departure,
                    scheduledPlatform = "9", actualPlatform = null),
                TrainStop(if (alias) Station(StationId("station:bologna c.le/av"), "BOLOGNA C.LE/AV") else bologna,
                    scheduledArrival = leg.arrival),
            ))
            val trains = FakeRealtimeRepositories().apply {
                runsResult = DataResult.Data(listOf(summary), DataFreshness.Unknown)
                trainState.value = DataResult.Data(run, DataFreshness.Fresh(MutableClock().now(), null),
                    if (warning) DomainFailure.TEMPORARY else null)
            }
            val component = JourneyDetailComponent(DefaultComponentContext(lifecycle),
                Journey(listOf(leg), setOf(ProviderId("trenitalia-journeys"))), CorrelateJourneyLeg(trains), {},
                StandardTestDispatcher(testScheduler), trains = trains)
            val facade = NativeJourneyDetailPresentation(component, NativeProjectionOwner())
            advanceUntilIdle()
            val projected = facade.state.value.legs.single()
            assertEquals(if (wrongBoardingTime) null else "9", projected.scheduledPlatform)
            assertNull(projected.actualPlatform)
            assertFalse(projected.hasTrainAction)
            assertEquals(TrainStatus.UNKNOWN, projected.realtimeStatus)
            assertEquals(1, trains.trainRefreshes, "Scheduled platform recovery must reuse the existing lookup")
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun plannedDeparturePlatformDoesNotRequireRealtimeDestinationCorrelation() = runTest { verify(alias = true) }
    @Test fun plannedDeparturePlatformSurvivesWarningBackedRealtimeFailure() = runTest { verify(warning = true) }
    @Test fun aDifferentBoardingScheduleCannotSupplyAPlatform() = runTest { verify(alias = true, wrongBoardingTime = true) }
}
