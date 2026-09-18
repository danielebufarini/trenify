package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.SearchJourneys
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Journey
import it.danielebufarini.trenify.core.model.JourneyLeg
import it.danielebufarini.trenify.core.model.JourneySearchResult
import it.danielebufarini.trenify.core.model.JourneySort
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.ProviderId
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.testing.FakeJourneyRepository
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.journeyDestination
import it.danielebufarini.trenify.core.testing.journeyRequest
import it.danielebufarini.trenify.core.testing.journeyResult
import it.danielebufarini.trenify.core.testing.testStation
import it.danielebufarini.trenify.feature.journey.JourneyResultsComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.hours

/**
 * T8.14-C1 guard: journey result rows keep a stable content-derived [key]
 * across user re-sorts, while the positional [index] remains the selection
 * key. Native lists must key rows by [key], never by [index].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NativeJourneyStableKeyTest {
    @Test fun journeyCardKeysAreUniqueAndStableAcrossSort() = runTest {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        try {
            val at = journeyRequest.at
            // Slow departs first but takes longer; DEPARTURE and DURATION
            // orders disagree, so a re-sort observably reorders the rows.
            val slow = Journey(
                listOf(JourneyLeg(testStation, journeyDestination, at, at + 3.hours,
                    TrainNumber("123"), "Regionale", Operator("Trenitalia"))),
                setOf(ProviderId("test")),
            )
            val fast = Journey(
                listOf(JourneyLeg(testStation, journeyDestination, at + 1.hours, at + 3.hours,
                    TrainNumber("456"), "Frecciarossa", Operator("Trenitalia"))),
                setOf(ProviderId("test")),
            )
            val repository = FakeJourneyRepository()
            repository.state.value = DataResult.Data(
                JourneySearchResult(listOf(fast, slow), journeyResult.coverage,
                    journeyRequest.departureFrom, journeyRequest.departureUntil),
                DataFreshness.Fresh(MutableClock().now(), null),
            )
            var selected: Journey? = null
            val facade = NativeJourneyResultsPresentation(
                JourneyResultsComponent(
                    DefaultComponentContext(lifecycle),
                    journeyRequest,
                    SearchJourneys(repository),
                    { selected = it },
                    StandardTestDispatcher(testScheduler),
                ),
                NativeProjectionOwner(),
            )
            runCurrent()
            val departureOrder = facade.state.value.journeys
            assertEquals(
                listOf(slow.departure.epochSeconds, fast.departure.epochSeconds),
                departureOrder.map { it.departureEpochSeconds },
            )
            assertEquals(2, departureOrder.map { it.key }.toSet().size)

            facade.setSort(JourneySort.DURATION)
            runCurrent()
            val durationOrder = facade.state.value.journeys
            // DURATION puts the fast journey first: the row order flipped.
            assertEquals(
                listOf(fast.departure.epochSeconds, slow.departure.epochSeconds),
                durationOrder.map { it.departureEpochSeconds },
            )
            // The same journeys keep the same keys: identity follows content,
            // not position.
            assertEquals(departureOrder.map { it.key }.toSet(), durationOrder.map { it.key }.toSet())
            val keyByDeparture = (departureOrder + durationOrder).associate { it.departureEpochSeconds to it.key }
            assertEquals(2, keyByDeparture.values.toSet().size)

            // Positional selection is unchanged: index still addresses the
            // row currently at that position.
            facade.selectJourney(0)
            assertEquals(fast, selected)
            facade.setSort(JourneySort.DEPARTURE)
            runCurrent()
            facade.selectJourney(0)
            assertEquals(slow, selected)
            assertNotEquals(departureOrder[0].key, departureOrder[1].key)
        } finally {
            lifecycle.destroy()
        }
    }
}
