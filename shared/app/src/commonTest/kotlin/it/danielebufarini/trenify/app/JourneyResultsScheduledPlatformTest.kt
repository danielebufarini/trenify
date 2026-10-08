package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.testing.*
import it.danielebufarini.trenify.feature.journey.JourneyResultsComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class JourneyResultsScheduledPlatformTest {
    // Synthetic domain inputs validate presentation only, not live provider availability.
    @Test fun resultsPreserveEachOperatorsPlannedPlatformWithoutRealtime() = runTest {
        for (operator in listOf("Italo", "Trenitalia")) {
            val lifecycle = LifecycleRegistry()
            lifecycle.resume()
            try {
                val repository = FakeJourneyRepository()
                val component = JourneyResultsComponent(DefaultComponentContext(lifecycle), journeyRequest,
                    SearchJourneys(repository), {}, StandardTestDispatcher(testScheduler))
                val facade = NativeJourneyResultsPresentation(component, NativeProjectionOwner())
                for (planned in listOf("7", null)) {
                    val journey = testJourney.copy(legs = testJourney.legs.map {
                        it.copy(operator = Operator(operator), scheduledPlatform = planned)
                    })
                    repository.state.value = DataResult.Data(journeyResult.copy(journeys = listOf(journey)),
                        DataFreshness.Fresh(MutableClock().now(), null))
                    runCurrent()
                    val leg = facade.state.value.journeys.single().legs.single()
                    assertEquals(operator, leg.operatorName)
                    assertEquals(planned, leg.scheduledPlatform)
                    assertEquals(journey.departure.epochSeconds, leg.departureEpochSeconds)
                    assertEquals(journey.origin.name, leg.originName)
                }
            } finally {
                lifecycle.destroy()
            }
        }
    }
}
