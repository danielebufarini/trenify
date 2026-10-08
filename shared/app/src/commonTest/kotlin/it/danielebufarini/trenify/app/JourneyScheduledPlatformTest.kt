package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.testing.*
import it.danielebufarini.trenify.feature.journey.JourneyDetailComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class JourneyScheduledPlatformTest {
    private fun TestScope.verify(scheduled: String?, actual: String?, realtime: Boolean, failed: Boolean = false) {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            val leg = testJourney.legs.single().copy(scheduledPlatform = scheduled)
            val journey = testJourney.copy(legs = listOf(leg))
            val trains = FakeRealtimeRepositories().apply {
                trainState.value = if (!realtime) DataResult.Failure(if (failed) DomainFailure.TEMPORARY else DomainFailure.NOT_FOUND)
                else DataResult.Data(
                    TrainRun(testSummary.copy(operator = leg.operator), listOf(
                        TrainStop(leg.origin, scheduledDeparture = leg.departure, actualPlatform = actual),
                        TrainStop(leg.destination, scheduledArrival = leg.arrival),
                    )), DataFreshness.Fresh(MutableClock().now(), null),
                )
            }
            val component = JourneyDetailComponent(DefaultComponentContext(lifecycle), journey,
                CorrelateJourneyLeg(trains), {}, StandardTestDispatcher(testScheduler), trains = trains)
            val facade = NativeJourneyDetailPresentation(component, NativeProjectionOwner())
            advanceUntilIdle()
            val projected = facade.state.value.legs.single()
            assertEquals(scheduled, projected.scheduledPlatform)
            assertEquals(if (realtime) actual else null, projected.actualPlatform)
            assertEquals(scheduled, component.state.value.resolved!!.legs.single().scheduledPlatform)
            assertEquals(realtime, projected.hasTrainAction)
            if (realtime) {
                // Realtime refresh failure must remove actual enrichment only.
                trains.trainState.value = DataResult.Failure(DomainFailure.TEMPORARY)
                runCurrent()
                assertEquals(scheduled, facade.state.value.legs.single().scheduledPlatform)
                assertNull(facade.state.value.legs.single().actualPlatform)
                assertTrue(facade.state.value.legs.single().realtimeFailed)
            }
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun scheduledOnlyWithoutRealtimeSnapshot() = runTest { verify("7", null, realtime = false) }
    @Test fun scheduledSurvivesFailedRealtimeRequest() = runTest { verify("7", null, realtime = false, failed = true) }
    @Test fun scheduledWithRealtimeButMissingPlatform() = runTest { verify("7", null, realtime = true) }
    @Test fun scheduledAndDifferentActualStayIndependent() = runTest { verify("7", "8", realtime = true) }
    @Test fun realtimeOnlyDoesNotInventScheduledPlatform() = runTest { verify(null, "8", realtime = true) }
    @Test fun neitherSourceHasAPlatform() = runTest { verify(null, null, realtime = false) }
    @Test fun identicalScheduledAndActualRetainBothValues() = runTest { verify("7", "7", realtime = true) }
}
