package it.danielebufarini.trenify.feature.journey

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.model.JourneySearchIntent
import it.danielebufarini.trenify.core.model.JourneySearchMode
import it.danielebufarini.trenify.core.testing.FakeJourneyRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.journeyDestination
import it.danielebufarini.trenify.core.testing.testStation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class JourneySwapTest {
    @Test fun swapExchangesEndpointsAndTextsAtomically() = runTest {
        val lifecycle = LifecycleRegistry()
        val search = JourneySearchComponent(
            DefaultComponentContext(lifecycle),
            FakeJourneyRepository(),
            {},
            StandardTestDispatcher(testScheduler),
            MutableClock(),
            FakeRealtimeRepositories(),
            JourneySearchIntent(testStation, journeyDestination),
        )
        lifecycle.resume()
        runCurrent()
        assertEquals(testStation, search.state.value.origin)
        assertEquals(journeyDestination, search.state.value.destination)

        search.swap()
        assertEquals(journeyDestination, search.state.value.origin)
        assertEquals(testStation, search.state.value.destination)
        // Texts travel with their endpoints.
        assertEquals(journeyDestination.name, search.state.value.originText)
        assertEquals(testStation.name, search.state.value.destinationText)

        search.swap()
        assertEquals(testStation, search.state.value.origin)
        assertEquals(journeyDestination, search.state.value.destination)
        lifecycle.destroy()
    }

    @Test fun swapClearsSuggestionsAndInvalidWithoutSearching() = runTest {
        val lifecycle = LifecycleRegistry()
        val repository = FakeJourneyRepository()
        val search = JourneySearchComponent(
            DefaultComponentContext(lifecycle),
            repository,
            {},
            StandardTestDispatcher(testScheduler),
            MutableClock(),
        )
        lifecycle.resume()
        search.stationText(true, "Roma")
        advanceUntilIdle()
        assertTrue(search.state.value.suggestions.isNotEmpty())
        search.swap()
        assertTrue(search.state.value.suggestions.isEmpty())
        assertFalse(search.state.value.loading)
        assertFalse(search.state.value.invalid)
        assertNull(search.state.value.failure)
        assertEquals(0, repository.calls)
        lifecycle.destroy()
    }

    @Test fun swapPreservesDateTimeAndMode() = runTest {
        val lifecycle = LifecycleRegistry()
        val search = JourneySearchComponent(
            DefaultComponentContext(lifecycle),
            FakeJourneyRepository(),
            {},
            StandardTestDispatcher(testScheduler),
            MutableClock(),
            FakeRealtimeRepositories(),
            JourneySearchIntent(testStation, journeyDestination),
        )
        lifecycle.resume()
        runCurrent()
        val date = search.state.value.date
        val hour = search.state.value.timeHour
        val minute = search.state.value.timeMinute
        search.mode(JourneySearchMode.ARRIVE_BY)
        search.swap()
        assertEquals(date, search.state.value.date)
        assertEquals(hour, search.state.value.timeHour)
        assertEquals(minute, search.state.value.timeMinute)
        assertEquals(JourneySearchMode.ARRIVE_BY, search.state.value.mode)
        lifecycle.destroy()
    }
}
