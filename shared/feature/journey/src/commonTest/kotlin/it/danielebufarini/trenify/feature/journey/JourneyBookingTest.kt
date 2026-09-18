package it.danielebufarini.trenify.feature.journey

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.testing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class JourneyBookingTest {
    private fun detail(
        lifecycle: LifecycleRegistry,
        journey: Journey = testJourney,
        canBook: ((Journey) -> Boolean)? = null,
        openBooking: (suspend (Journey) -> BookingHandoffResult)? = null,
        testScope: TestScope,
    ) = JourneyDetailComponent(
        DefaultComponentContext(lifecycle), journey, null, {},
        StandardTestDispatcher(testScope.testScheduler), canBook = canBook, openBooking = openBooking,
    )

    @Test fun detailWithoutOpenerHasNoBookingAction() = runTest {
        val lifecycle = LifecycleRegistry()
        val component = detail(lifecycle, testScope = this)
        lifecycle.resume()
        try {
            assertFalse(component.state.value.bookingAvailable)
            component.buy()
            runCurrent()
            assertFalse(component.state.value.bookingInProgress)
            assertFalse(component.state.value.bookingFailed)
        } finally { lifecycle.destroy() }
    }

    @Test fun availabilityRequiresBothHandoffLambdas() = runTest {
        val lifecycle = LifecycleRegistry()
        val component = detail(lifecycle, testScope = this,
            canBook = { true },
            openBooking = null)
        lifecycle.resume()
        try {
            assertFalse(component.state.value.bookingAvailable)
            component.buy()
            runCurrent()
            assertFalse(component.state.value.bookingInProgress)
        } finally { lifecycle.destroy() }
    }

    @Test fun availabilityFollowsInjectedHandoffNotOperator() = runTest {
        val lifecycle = LifecycleRegistry()
        var calls = 0
        // testJourney carries a known Trenitalia operator, yet booking stays unavailable
        // because the injected handoff — the same instance that would execute — rejects it.
        val component = detail(lifecycle, testScope = this,
            canBook = { false },
            openBooking = {
                calls++
                BookingHandoffResult.Opened(BookingTarget("https://www.trenitalia.com", "Trenitalia"))
            })
        lifecycle.resume()
        try {
            assertFalse(component.state.value.bookingAvailable)
            component.buy()
            runCurrent()
            assertEquals(0, calls)
        } finally { lifecycle.destroy() }
    }

    @Test fun buyOpensApprovedChannelAndClearsProgress() = runTest {
        val lifecycle = LifecycleRegistry()
        val requested = mutableListOf<Journey>()
        val component = detail(lifecycle, testScope = this,
            canBook = { true },
            openBooking = {
                requested += it
                BookingHandoffResult.Opened(BookingTarget("https://www.trenitalia.com", "Trenitalia"))
            })
        lifecycle.resume()
        try {
            assertTrue(component.state.value.bookingAvailable)
            component.buy()
            assertTrue(component.state.value.bookingInProgress)
            advanceUntilIdle()
            assertEquals(listOf(testJourney), requested)
            assertFalse(component.state.value.bookingInProgress)
            assertFalse(component.state.value.bookingFailed)
        } finally { lifecycle.destroy() }
    }

    @Test fun failedHandoffSurfacesFailureWithoutProgress() = runTest {
        val lifecycle = LifecycleRegistry()
        var attempts = 0
        val component = detail(lifecycle, testScope = this,
            canBook = { true },
            openBooking = {
                attempts++
                if (attempts == 1) BookingHandoffResult.Failed(BookingTarget("https://www.trenitalia.com", "Trenitalia"))
                else BookingHandoffResult.Opened(BookingTarget("https://www.trenitalia.com", "Trenitalia"))
            })
        lifecycle.resume()
        try {
            component.buy()
            advanceUntilIdle()
            assertFalse(component.state.value.bookingInProgress)
            assertTrue(component.state.value.bookingFailed)
            component.buy()
            advanceUntilIdle()
            assertEquals(2, attempts)
            assertFalse(component.state.value.bookingFailed)
        } finally { lifecycle.destroy() }
    }

    @Test fun tabThreadsHandoffThroughToDetail() = runTest {
        val lifecycle = LifecycleRegistry()
        val opened = mutableListOf<String>()
        val handoff = OpenBookingLink { opened += it; true }
        val tab = DefaultJourneyTabComponent(DefaultComponentContext(lifecycle), FakeJourneyRepository(),
            dispatcher = StandardTestDispatcher(testScheduler),
            canBook = handoff::canBook, openBooking = handoff::invoke)
        lifecycle.resume()
        try {
            val form = assertIs<JourneyTabComponent.Child.Search>(tab.stack.value.active.instance).component
            form.stationText(true, "Roma"); advanceUntilIdle(); form.select(testStation)
            form.stationText(false, "Milano"); advanceUntilIdle(); form.select(journeyDestination)
            form.date("2026-09-05"); form.time("10:00"); form.search()
            val results = assertIs<JourneyTabComponent.Child.Results>(tab.stack.value.active.instance).component
            runCurrent()
            results.select(testJourney)
            val detail = assertIs<JourneyTabComponent.Child.Detail>(tab.stack.value.active.instance).component
            // Tab detail navigation re-resolves through the repository
            // (T7.13): the handoff is available once resolution completes.
            advanceUntilIdle()
            assertTrue(detail.state.value.bookingAvailable)
            detail.buy(); advanceUntilIdle()
            assertEquals(listOf("https://www.trenitalia.com"), opened)
            assertFalse(detail.state.value.bookingFailed)
        } finally { lifecycle.destroy() }
    }
}
