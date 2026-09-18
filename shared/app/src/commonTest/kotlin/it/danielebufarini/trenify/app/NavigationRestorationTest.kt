package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import it.danielebufarini.trenify.core.model.BoardKind
import it.danielebufarini.trenify.core.model.JourneySearchIntent
import it.danielebufarini.trenify.core.model.JourneySort
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.testing.FakeJourneyRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.testing.journeyDestination
import it.danielebufarini.trenify.core.testing.testJourney
import it.danielebufarini.trenify.core.testing.testRunId
import it.danielebufarini.trenify.core.testing.testStation
import it.danielebufarini.trenify.core.testing.testStrike
import it.danielebufarini.trenify.feature.journey.JourneyTabComponent
import it.danielebufarini.trenify.feature.strikes.AlertsTabComponent
import it.danielebufarini.trenify.feature.station.StationsTabComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runCurrent
import kotlinx.datetime.LocalDate
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * End-to-end save/destroy/recreate restoration (T7.13-B/I) through the real
 * Decompose state-keeper mechanism: navigate through multiple tabs/stacks,
 * populate durable inputs, save, destroy the tree, recreate from the saved
 * state and verify selected tab, independent stacks, durable inputs and
 * Back behavior. No hand-written data-class serialization stands in for
 * this; the old tree must not remain alive afterwards.
 */
/**
 * Virtual-time discipline for these suites: every wait is a bounded
 * [advanceTimeBy], never a bare advanceUntilIdle. Screen-level polling
 * (VisibleRefresh) reschedules itself forever on resumed lifecycles, so an
 * unbounded drain would never terminate — it would even starve the runTest
 * timeout scheduled on the same dispatcher. One virtual second flushes all
 * immediate repository/UI work and short debounces without reaching the
 * 30s poll intervals; longer bounded advances cover the polling
 * assertions themselves.
 */
class NavigationRestorationTest {
    private class Fixtures {
        val realtime = FakeRealtimeRepositories()
        val journeys = FakeJourneyRepository()
        val strikes = FakeStrikeRepository()
    }

    private fun kotlinx.coroutines.test.TestScope.factory(fixtures: Fixtures) = AppComponentFactory(
        fixtures.realtime,
        fixtures.realtime,
        fixtures.realtime,
        fixtures.realtime,
        MutableStateFlow(true),
        StandardTestDispatcher(testScheduler),
        journeyRepository = fixtures.journeys,
        strikeRepository = fixtures.strikes,
        strikeNotificationRepository = fixtures.strikes,
    )

    private fun mainOf(root: RootComponent): MainComponent =
        assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component

    /** Finds the Main child anywhere in the root stack (not only on top). */
    private fun mainInStack(root: RootComponent): MainComponent =
        root.stack.value.items.map { it.instance }
            .filterIsInstance<RootComponent.Child.Main>().single().component

    private fun journeyTabOf(main: MainComponent): JourneyTabComponent =
        assertIs<MainComponent.Child.Journey>(
            main.pages.value.items[MainTab.Journey.ordinal].instance,
        ).component

    private fun stationsTabOf(main: MainComponent): StationsTabComponent =
        assertIs<MainComponent.Child.Stations>(
            main.pages.value.items[MainTab.Stations.ordinal].instance,
        ).component

    /**
     * Drives the full pre-save navigation: journey Search -> Results ->
     * Detail, stations Search -> Board, alerts Overview -> Detail, plus a
     * root train-search/detail pair and durable inputs on every screen.
     */
    private suspend fun kotlinx.coroutines.test.TestScope.populate(root: RootComponent) {
        val main = mainOf(root)

        // Journey: intent entry, explicit date/time, search, sort, detail.
        main.openJourneySearch(JourneySearchIntent(testStation, journeyDestination))
        runCurrent()
        val journeyTab = journeyTabOf(main)
        val form = assertIs<JourneyTabComponent.Child.Search>(journeyTab.stack.value.active.instance).component
        form.date("2026-09-05")
        form.time("10:00")
        form.search()
        advanceTimeBy(1.seconds)
        val results = assertIs<JourneyTabComponent.Child.Results>(journeyTab.stack.value.active.instance).component
        results.sort(JourneySort.ARRIVAL)
        results.select(testJourney)
        advanceTimeBy(1.seconds)
        assertEquals(testJourney, assertIs<JourneyTabComponent.Child.Detail>(
            journeyTab.stack.value.active.instance).component.journey)

        // Stations: searchable query, then board with arrivals kind.
        main.select(MainTab.Stations)
        runCurrent()
        val stationsTab = stationsTabOf(main)
        val stationSearch = assertIs<StationsTabComponent.Child.Search>(
            stationsTab.stack.value.active.instance).component
        stationSearch.query("Roma")
        advanceTimeBy(1.seconds)
        stationSearch.select(testStation)
        advanceTimeBy(1.seconds)
        val board = assertIs<StationsTabComponent.Child.Board>(
            stationsTab.stack.value.active.instance).component
        board.select(BoardKind.ARRIVALS)
        advanceTimeBy(1.seconds)

        // Alerts: open the loaded strike.
        main.select(MainTab.Alerts)
        advanceTimeBy(1.seconds)
        val alerts = assertIs<MainComponent.Child.Alerts>(
            main.pages.value.items[MainTab.Alerts.ordinal].instance,
        ).component
        alerts.open(testStrike.id)
        runCurrent()

        // Back to stations as the selected tab, then root-level train flow:
        // prefilled search with durable input plus a monitor-style detail.
        main.select(MainTab.Stations)
        runCurrent()
        main.openTrainSearch(TrainNumber("123"), serviceDate = testRunId.serviceDate, expected = null)
        runCurrent()
        val search = assertIs<RootComponent.Child.Search>(root.stack.value.active.instance).component
        search.number("789")
        root.onNotificationDestination(testRunId.notificationDestination())
        advanceTimeBy(1.seconds)
        assertIs<RootComponent.Child.Detail>(root.stack.value.active.instance)
    }

    @Test
    fun saveDestroyRecreateRestoresTabsStacksInputsAndBack() = runTest {
        val fixtures = Fixtures()
        val lifecycle = LifecycleRegistry()
        val keeper = StateKeeperDispatcher(null)
        val root = DefaultRootComponent(
            DefaultComponentContext(lifecycle, keeper),
            factory(fixtures),
        )
        lifecycle.resume()
        populate(root)
        runCurrent()

        // Sanity on the pre-save tree: independent stacks per tab below the
        // root train flow, with stations as the selected tab.
        val main = mainInStack(root)
        assertEquals(MainTab.Stations.ordinal, main.pages.value.selectedIndex)
        assertEquals(3, journeyTabOf(main).stack.value.items.size)
        assertEquals(2, stationsTabOf(main).stack.value.items.size)

        val saved = keeper.save()
        val trainsBefore = fixtures.realtime.trainRefreshes
        val boardsBefore = fixtures.realtime.boardRefreshes
        lifecycle.destroy()

        // The destroyed tree performs no further polling work.
        advanceTimeBy(10.minutes)
        assertEquals(trainsBefore, fixtures.realtime.trainRefreshes)
        assertEquals(boardsBefore, fixtures.realtime.boardRefreshes)

        // Recreate from the saved state with fresh repositories: no full
        // domain object travels across; everything re-resolves.
        val fresh = Fixtures()
        val revivedLifecycle = LifecycleRegistry()
        val revived = DefaultRootComponent(
            DefaultComponentContext(revivedLifecycle, StateKeeperDispatcher(saved)),
            factory(fresh),
        )
        revivedLifecycle.resume()
        advanceTimeBy(1.seconds)
        runCurrent()

        // Root stack restores with the train detail on top.
        val revivedDetail = assertIs<RootComponent.Child.Detail>(revived.stack.value.active.instance)
        assertEquals(testRunId, revivedDetail.component.id)
        // Back returns into the restored tree: search keeps durable input...
        revived.back()
        assertEquals("789", assertIs<RootComponent.Child.Search>(
            revived.stack.value.active.instance).component.state.value.number)
        // ...then the main screen with the selected tab restored.
        revived.back()
        val revivedMain = mainOf(revived)
        assertEquals(MainTab.Stations.ordinal, revivedMain.pages.value.selectedIndex)

        // Stations stack restores independently: board with arrivals kind.
        val revivedStations = stationsTabOf(revivedMain)
        assertEquals(2, revivedStations.stack.value.items.size)
        val revivedBoard = assertIs<StationsTabComponent.Child.Board>(
            revivedStations.stack.value.active.instance).component
        assertEquals(testStation.id, revivedBoard.stationState.value.station?.id)
        assertEquals(BoardKind.ARRIVALS, revivedBoard.kind.value)
        revivedStations.back()
        assertEquals("Roma", assertIs<StationsTabComponent.Child.Search>(
            revivedStations.stack.value.active.instance).component.state.value.query)

        // Journey stack restores independently: detail re-resolved from the
        // repository, durable inputs and sort intact, Back order preserved.
        revivedMain.select(MainTab.Journey)
        runCurrent()
        val revivedJourney = journeyTabOf(revivedMain)
        assertEquals(3, revivedJourney.stack.value.items.size)
        val revivedJourneyDetail = assertIs<JourneyTabComponent.Child.Detail>(
            revivedJourney.stack.value.active.instance).component
        assertEquals(testJourney, revivedJourneyDetail.journey)
        revivedJourney.back()
        val revivedResults = assertIs<JourneyTabComponent.Child.Results>(
            revivedJourney.stack.value.active.instance).component
        assertEquals(JourneySort.ARRIVAL, revivedResults.state.value.sort)
        revivedJourney.back()
        val revivedForm = assertIs<JourneyTabComponent.Child.Search>(
            revivedJourney.stack.value.active.instance).component
        assertEquals(testStation, revivedForm.state.value.origin)
        assertEquals(journeyDestination, revivedForm.state.value.destination)
        assertEquals(LocalDate.parse("2026-09-05"), revivedForm.state.value.date)
        assertEquals("10:00 AM", revivedForm.state.value.timeText)

        // Alerts stack restores independently with the selected strike.
        revivedMain.select(MainTab.Alerts)
        advanceTimeBy(1.seconds)
        val revivedAlerts = assertIs<MainComponent.Child.Alerts>(
            revivedMain.pages.value.items[MainTab.Alerts.ordinal].instance,
        ).component
        val strikeDetail = assertIs<AlertsTabComponent.Child.Detail>(
            revivedAlerts.stack.value.active.instance)
        assertEquals(testStrike.id, strikeDetail.strikeId)

        // Navigating tabs never flattens another tab's restored history:
        // journey and stations show our own backs, while the untouched
        // alerts stack keeps its restored depth.
        revivedMain.select(MainTab.Journey)
        runCurrent()
        assertEquals(1, journeyTabOf(revivedMain).stack.value.items.size)
        revivedMain.select(MainTab.Stations)
        runCurrent()
        assertEquals(1, stationsTabOf(revivedMain).stack.value.items.size)
        revivedMain.select(MainTab.Alerts)
        runCurrent()
        assertEquals(2, revivedAlerts.stack.value.items.size)

        revivedLifecycle.destroy()
    }

    @Test
    fun historyRouteRestoresAndReturnsToSearch() = runTest {
        val fixtures = Fixtures()
        val lifecycle = LifecycleRegistry()
        val keeper = StateKeeperDispatcher(null)
        val root = DefaultRootComponent(
            DefaultComponentContext(lifecycle, keeper),
            factory(fixtures),
        )
        lifecycle.resume()
        val main = mainInStack(root)
        val journeyTab = journeyTabOf(main)
        val form = assertIs<JourneyTabComponent.Child.Search>(journeyTab.stack.value.active.instance).component
        form.history()
        runCurrent()
        assertIs<JourneyTabComponent.Child.History>(journeyTab.stack.value.active.instance)
        val saved = keeper.save()
        lifecycle.destroy()

        val fresh = Fixtures()
        val revivedLifecycle = LifecycleRegistry()
        val revived = DefaultRootComponent(
            DefaultComponentContext(revivedLifecycle, StateKeeperDispatcher(saved)),
            factory(fresh),
        )
        revivedLifecycle.resume()
        runCurrent()
        val revivedJourney = journeyTabOf(mainInStack(revived))
        assertEquals(2, revivedJourney.stack.value.items.size)
        assertIs<JourneyTabComponent.Child.History>(revivedJourney.stack.value.active.instance)
        revivedJourney.back()
        assertIs<JourneyTabComponent.Child.Search>(revivedJourney.stack.value.active.instance)
        revivedLifecycle.destroy()
    }

    @Test
    fun restoredHiddenDetailDoesNotPollUntilSelected() = runTest {
        val fixtures = Fixtures()
        val lifecycle = LifecycleRegistry()
        val keeper = StateKeeperDispatcher(null)
        val root = DefaultRootComponent(
            DefaultComponentContext(lifecycle, keeper),
            factory(fixtures),
        )
        lifecycle.resume()
        val main = mainOf(root)
        // Board hidden behind the journey tab must not poll.
        main.select(MainTab.Stations)
        runCurrent()
        val stationsTab = stationsTabOf(main)
        val stationSearch = assertIs<StationsTabComponent.Child.Search>(
            stationsTab.stack.value.active.instance).component
        stationSearch.select(testStation)
        advanceTimeBy(1.seconds)
        main.select(MainTab.Journey)
        runCurrent()
        val hiddenBoards = fixtures.realtime.boardRefreshes
        advanceTimeBy(5.minutes)
        assertEquals(hiddenBoards, fixtures.realtime.boardRefreshes)

        // Save/destroy/recreate with the board still hidden.
        val saved = keeper.save()
        lifecycle.destroy()
        val fresh = Fixtures()
        val revivedLifecycle = LifecycleRegistry()
        val revived = DefaultRootComponent(
            DefaultComponentContext(revivedLifecycle, StateKeeperDispatcher(saved)),
            factory(fresh),
        )
        revivedLifecycle.resume()
        advanceTimeBy(1.seconds)
        // Neither the dead tree nor the restored hidden board polls.
        advanceTimeBy(5.minutes)
        assertEquals(0, fresh.realtime.boardRefreshes)
        // Selecting the tab resumes exactly the intended work.
        mainOf(revived).select(MainTab.Stations)
        advanceTimeBy(5.minutes)
        assertTrue(fresh.realtime.boardRefreshes > 0)
        revivedLifecycle.destroy()
    }
}

private fun it.danielebufarini.trenify.core.model.TrainRunId.notificationDestination():
    it.danielebufarini.trenify.core.platform.NotificationDestination =
    it.danielebufarini.trenify.core.platform.NotificationDestination.Train(
        provider = provider.value,
        number = number.value,
        origin = origin.value,
        serviceDate = serviceDate.toString(),
    )
