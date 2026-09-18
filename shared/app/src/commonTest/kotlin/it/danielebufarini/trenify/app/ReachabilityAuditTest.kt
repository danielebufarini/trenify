package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import it.danielebufarini.trenify.core.model.FavoriteRoute
import it.danielebufarini.trenify.core.model.FavoriteTrain
import it.danielebufarini.trenify.core.model.JourneySearchIntent
import it.danielebufarini.trenify.core.testing.FakeJourneyRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.testing.journeyDestination
import it.danielebufarini.trenify.core.testing.journeyRequest
import it.danielebufarini.trenify.core.testing.testRunId
import it.danielebufarini.trenify.core.testing.testStation
import it.danielebufarini.trenify.core.testing.testStrike
import it.danielebufarini.trenify.feature.favorites.FavoritesComponent
import it.danielebufarini.trenify.feature.home.HomeComponent
import it.danielebufarini.trenify.feature.journey.JourneyTabComponent
import it.danielebufarini.trenify.feature.monitoring.MonitoringTabComponent
import it.danielebufarini.trenify.feature.settings.SettingsComponent
import it.danielebufarini.trenify.feature.station.StationsTabComponent
import it.danielebufarini.trenify.feature.strikes.AlertsTabComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Mandatory functional reachability audit (T7.13): every MVP destination is
 * reachable from Home/tabs/saved data/local notifications through
 * normalized identity/criteria (never saved full objects), and every
 * destination returns correctly.
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
class ReachabilityAuditTest {
    private class Fixtures(
        val realtime: FakeRealtimeRepositories = FakeRealtimeRepositories(),
        val journeys: FakeJourneyRepository = FakeJourneyRepository(),
        val strikes: FakeStrikeRepository = FakeStrikeRepository(),
    )

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

    /**
     * Selects the tab (Decompose instantiates page children on selection)
     * and returns its child.
     */
    private fun child(main: MainComponent, tab: MainTab): MainComponent.Child {
        main.select(tab)
        return main.pages.value.items[tab.ordinal].instance as MainComponent.Child
    }

    @Test
    fun everyTabIsReachableAndReturns() = runTest {
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(
            DefaultComponentContext(lifecycle, StateKeeperDispatcher(null)),
            factory(Fixtures()),
        )
        lifecycle.resume()
        try {
            val main = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
            assertIs<MainComponent.Child.Home>(child(main, MainTab.Home))
            main.select(MainTab.Journey)
            assertIs<MainComponent.Child.Journey>(child(main, MainTab.Journey))
            main.select(MainTab.Stations)
            assertIs<MainComponent.Child.Stations>(child(main, MainTab.Stations))
            main.select(MainTab.Favorites)
            assertIs<MainComponent.Child.Favorites>(child(main, MainTab.Favorites))
            main.select(MainTab.Monitoring)
            assertIs<MainComponent.Child.Monitoring>(child(main, MainTab.Monitoring))
            main.select(MainTab.Alerts)
            assertIs<MainComponent.Child.Alerts>(child(main, MainTab.Alerts))
            main.select(MainTab.Settings)
            assertIs<MainComponent.Child.Settings>(child(main, MainTab.Settings))
            // Root pushes always return to main.
            main.select(MainTab.Journey)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun homeEntriesReachEveryPrimaryFunction() = runTest {
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(
            DefaultComponentContext(lifecycle, StateKeeperDispatcher(null)),
            factory(Fixtures()),
        )
        lifecycle.resume()
        try {
            val main = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
            val home = assertIs<MainComponent.Child.Home>(child(main, MainTab.Home)).component

            home.openJourneySearch()
            assertEquals(MainTab.Journey.ordinal, main.pages.value.selectedIndex)
            home.openStations()
            assertEquals(MainTab.Stations.ordinal, main.pages.value.selectedIndex)
            home.openMonitoring()
            assertEquals(MainTab.Monitoring.ordinal, main.pages.value.selectedIndex)
            home.openFavorites()
            assertEquals(MainTab.Favorites.ordinal, main.pages.value.selectedIndex)
            home.openAlerts()
            assertEquals(MainTab.Alerts.ordinal, main.pages.value.selectedIndex)

            // Home train search opens the shared root search flow...
            home.openTrainSearch()
            runCurrent()
            assertIs<RootComponent.Child.Search>(root.stack.value.active.instance)
            root.back()
            // ...station entries open the shared board through identity...
            home.openStation(testStation)
            runCurrent()
            assertIs<RootComponent.Child.StationBoard>(root.stack.value.active.instance)
            root.back()
            // ...route entries land on the journey tab...
            home.openRoute(FavoriteRoute.create(testStation, journeyDestination))
            runCurrent()
            assertEquals(MainTab.Journey.ordinal, main.pages.value.selectedIndex)
            // ...and monitor entries open the normalized train detail.
            home.openMonitor(testRunId)
            runCurrent()
            assertEquals(testRunId, assertIs<RootComponent.Child.Detail>(
                root.stack.value.active.instance).component.id)
            root.back()
            assertIs<RootComponent.Child.Main>(root.stack.value.active.instance)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun savedDataEntriesRouteThroughNormalizedIdentity() = runTest {
        val fixtures = Fixtures()
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(
            DefaultComponentContext(lifecycle, StateKeeperDispatcher(null)),
            factory(fixtures),
        )
        lifecycle.resume()
        try {
            val main = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
            val favorites = assertIs<MainComponent.Child.Favorites>(
                child(main, MainTab.Favorites)).component

            // Favorite station -> shared board; favorite route -> journey
            // intent; favorite train -> fresh lookup flow (never a stored run).
            favorites.open(testStation)
            runCurrent()
            assertIs<RootComponent.Child.StationBoard>(root.stack.value.active.instance)
            root.back()
            favorites.open(FavoriteRoute.create(testStation, journeyDestination))
            runCurrent()
            assertEquals(MainTab.Journey.ordinal, main.pages.value.selectedIndex)
            favorites.open(FavoriteTrain.create(testRunId.number, testStation.id, null, testStation.name, null))
            advanceTimeBy(1.seconds)
            // The single compatible candidate auto-opens through the fresh
            // lookup flow (never a stored run); Back returns to the search.
            assertEquals(testRunId, assertIs<RootComponent.Child.Detail>(
                root.stack.value.active.instance).component.id)
            root.back()
            val search = assertIs<RootComponent.Child.Search>(root.stack.value.active.instance).component
            assertEquals(testRunId.number.value, search.state.value.number)
            root.back()

            // Monitoring cards open the normalized train detail.
            val monitoring = assertIs<MainComponent.Child.Monitoring>(
                child(main, MainTab.Monitoring)).component
            monitoring.open(testRunId)
            runCurrent()
            assertEquals(testRunId, assertIs<RootComponent.Child.Detail>(
                root.stack.value.active.instance).component.id)
            root.back()

            // Journey history repeats reuse recorded criteria explicitly.
            main.openJourneyRepeat(journeyRequest)
            runCurrent()
            assertEquals(MainTab.Journey.ordinal, main.pages.value.selectedIndex)
            val journey = assertIs<MainComponent.Child.Journey>(child(main, MainTab.Journey)).component
            val form = assertIs<JourneyTabComponent.Child.Search>(journey.stack.value.active.instance).component
            assertEquals(journeyRequest.origin, form.state.value.origin)
            assertEquals(journeyRequest.destination, form.state.value.destination)

            // Alerts strike detail opens and returns to the list.
            main.select(MainTab.Alerts)
            advanceTimeBy(1.seconds)
            val alerts = assertIs<MainComponent.Child.Alerts>(child(main, MainTab.Alerts)).component
            alerts.open(testStrike.id)
            runCurrent()
            assertIs<AlertsTabComponent.Child.Detail>(alerts.stack.value.active.instance)
            alerts.back()
            assertIs<AlertsTabComponent.Child.Overview>(alerts.stack.value.active.instance)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun journeySearchResultsDetailFlowReachesAndReturns() = runTest {
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(
            DefaultComponentContext(lifecycle, StateKeeperDispatcher(null)),
            factory(Fixtures()),
        )
        lifecycle.resume()
        try {
            val main = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
            main.openJourneySearch(JourneySearchIntent(testStation, journeyDestination))
            runCurrent()
            val journey = assertIs<MainComponent.Child.Journey>(child(main, MainTab.Journey)).component
            val form = assertIs<JourneyTabComponent.Child.Search>(journey.stack.value.active.instance).component
            form.date("2026-09-05")
            form.time("10:00")
            form.search()
            advanceTimeBy(1.seconds)
            assertIs<JourneyTabComponent.Child.Results>(journey.stack.value.active.instance)
            journey.back()
            assertIs<JourneyTabComponent.Child.Search>(journey.stack.value.active.instance)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun stationBoardAndTrainDetailReachAndReturn() = runTest {
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(
            DefaultComponentContext(lifecycle, StateKeeperDispatcher(null)),
            factory(Fixtures()),
        )
        lifecycle.resume()
        try {
            val main = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
            main.select(MainTab.Stations)
            runCurrent()
            val stations = assertIs<MainComponent.Child.Stations>(child(main, MainTab.Stations)).component
            val search = assertIs<StationsTabComponent.Child.Search>(stations.stack.value.active.instance).component
            search.select(testStation)
            advanceTimeBy(1.seconds)
            assertIs<StationsTabComponent.Child.Board>(stations.stack.value.active.instance)
            stations.back()
            assertIs<StationsTabComponent.Child.Search>(stations.stack.value.active.instance)
            // Train search entry from the stations tab reaches the shared flow.
            stations.searchTrains()
            runCurrent()
            assertIs<RootComponent.Child.Search>(root.stack.value.active.instance)
            root.back()
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun settingsAndHistoryEntriesAreReachable() = runTest {
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(
            DefaultComponentContext(lifecycle, StateKeeperDispatcher(null)),
            factory(Fixtures()),
        )
        lifecycle.resume()
        try {
            val main = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
            main.select(MainTab.Settings)
            runCurrent()
            assertIs<SettingsComponent>(assertIs<MainComponent.Child.Settings>(
                child(main, MainTab.Settings)).component)
            // History lives on the journey tab search screen.
            main.select(MainTab.Journey)
            runCurrent()
            val journey = assertIs<MainComponent.Child.Journey>(child(main, MainTab.Journey)).component
            val form = assertIs<JourneyTabComponent.Child.Search>(journey.stack.value.active.instance).component
            form.history()
            runCurrent()
            assertIs<JourneyTabComponent.Child.History>(journey.stack.value.active.instance)
            journey.back()
            assertIs<JourneyTabComponent.Child.Search>(journey.stack.value.active.instance)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun componentTypesAreSharedAcrossTabs() = runTest {
        val lifecycle = LifecycleRegistry()
        val root = DefaultRootComponent(
            DefaultComponentContext(lifecycle, StateKeeperDispatcher(null)),
            factory(Fixtures()),
        )
        lifecycle.resume()
        try {
            val main = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
            assertIs<HomeComponent>(assertIs<MainComponent.Child.Home>(child(main, MainTab.Home)).component)
            assertIs<JourneyTabComponent>(assertIs<MainComponent.Child.Journey>(
                child(main, MainTab.Journey)).component)
            assertIs<StationsTabComponent>(assertIs<MainComponent.Child.Stations>(
                child(main, MainTab.Stations)).component)
            assertIs<FavoritesComponent>(assertIs<MainComponent.Child.Favorites>(
                child(main, MainTab.Favorites)).component)
            assertIs<MonitoringTabComponent>(assertIs<MainComponent.Child.Monitoring>(
                child(main, MainTab.Monitoring)).component)
            assertIs<AlertsTabComponent>(assertIs<MainComponent.Child.Alerts>(
                child(main, MainTab.Alerts)).component)
        } finally {
            lifecycle.destroy()
        }
    }
}
