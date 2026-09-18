package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.testing.*
import it.danielebufarini.trenify.feature.journey.JourneyTabComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.datetime.LocalDate
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class JourneyNavigationTest {
    @Test fun journeyDetailNavigatesThroughExistingRootToVerifiedRealtimeRun() = runTest {
        val lifecycle = LifecycleRegistry()
        val trains = FakeRealtimeRepositories()
        val leg = testJourney.legs.single()
        trains.trainState.value = DataResult.Data(TrainRun(testSummary.copy(operator = leg.operator), listOf(
            TrainStop(testStation, scheduledDeparture = leg.departure), TrainStop(journeyDestination, scheduledArrival = leg.arrival))),
            DataFreshness.Fresh(MutableClock().now(), null))
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle), AppComponentFactory(trains, trains, trains,
            trains, MutableStateFlow(false), StandardTestDispatcher(testScheduler), journeyRepository = FakeJourneyRepository()))
        lifecycle.resume()
        try {
            val main = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
            val journey = assertIs<MainComponent.Child.Journey>(main.pages.value.items[MainTab.Journey.ordinal].instance).component
            val form = assertIs<JourneyTabComponent.Child.Search>(journey.stack.value.active.instance).component
            form.stationText(true, "Roma"); advanceTimeBy(276); runCurrent(); form.select(testStation)
            form.stationText(false, "Milano"); advanceTimeBy(276); runCurrent(); form.select(journeyDestination)
            form.date("2026-09-05"); form.time("10:00"); form.search(); runCurrent()
            assertIs<JourneyTabComponent.Child.Results>(journey.stack.value.active.instance).component.select(testJourney)
            runCurrent()
            val detail = assertIs<JourneyTabComponent.Child.Detail>(journey.stack.value.active.instance).component
            detail.realtime(0); runCurrent()
            assertEquals(testRunId, assertIs<RootComponent.Child.Detail>(root.stack.value.active.instance).component.id)
            root.back(); runCurrent()
            assertIs<RootComponent.Child.Main>(root.stack.value.active.instance)
            assertIs<JourneyTabComponent.Child.Detail>(journey.stack.value.active.instance)
        } finally { lifecycle.destroy() }
    }

    @Test fun journeySearchHistoryOpensRepeatsRemovesAndClearsThroughRoot() = runTest {
        val lifecycle = LifecycleRegistry()
        val history = FakeRealtimeRepositories()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle), AppComponentFactory(history, history, history,
            history, MutableStateFlow(false), StandardTestDispatcher(testScheduler), journeyRepository = FakeJourneyRepository()))
        lifecycle.resume()
        try {
            val main = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
            val journey = assertIs<MainComponent.Child.Journey>(main.pages.value.items[MainTab.Journey.ordinal].instance).component
            val form = assertIs<JourneyTabComponent.Child.Search>(journey.stack.value.active.instance).component
            form.stationText(true, "Roma"); advanceTimeBy(276); runCurrent(); form.select(testStation)
            form.stationText(false, "Milano"); advanceTimeBy(276); runCurrent(); form.select(journeyDestination)
            form.date("2026-09-05"); form.time("10:00"); form.search(); runCurrent()
            assertIs<JourneyTabComponent.Child.Results>(journey.stack.value.active.instance)
            assertEquals(1, history.observeSearchHistory().first().size)
            journey.back(); runCurrent()
            val reopened = assertIs<JourneyTabComponent.Child.Search>(journey.stack.value.active.instance).component
            reopened.history(); runCurrent()
            val historyChild = assertIs<JourneyTabComponent.Child.History>(journey.stack.value.active.instance).component
            runCurrent()
            // Repeat reuses the recorded criteria through the existing search flow.
            historyChild.repeat(historyChild.state.value.entries.single()); runCurrent()
            val prefilled = assertIs<JourneyTabComponent.Child.Search>(journey.stack.value.active.instance).component
            assertEquals(LocalDate.parse("2026-09-05"), prefilled.state.value.date)
            assertEquals("10:00 AM", prefilled.state.value.timeText)
            prefilled.search(); runCurrent()
            assertIs<JourneyTabComponent.Child.Results>(journey.stack.value.active.instance)
            assertEquals(2, history.observeSearchHistory().first().size)
            // Removal and clear-all are reachable local operations.
            journey.back(); runCurrent()
            assertIs<JourneyTabComponent.Child.Search>(journey.stack.value.active.instance).component.history()
            runCurrent()
            val reopenedHistory = assertIs<JourneyTabComponent.Child.History>(journey.stack.value.active.instance).component
            runCurrent()
            reopenedHistory.remove(reopenedHistory.state.value.entries.first()); runCurrent()
            assertEquals(1, reopenedHistory.state.value.entries.size)
            reopenedHistory.clear(); runCurrent()
            assertTrue(reopenedHistory.state.value.entries.isEmpty())
            journey.back(); runCurrent()
            assertIs<JourneyTabComponent.Child.Search>(journey.stack.value.active.instance)
        } finally { lifecycle.destroy() }
    }
}
