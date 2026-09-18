package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import it.danielebufarini.trenify.core.model.BoardKind
import it.danielebufarini.trenify.core.model.Station
import it.danielebufarini.trenify.core.testing.FakeJourneyRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.testing.testStation
import it.danielebufarini.trenify.feature.station.StationsTabComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Station-board identity-only restoration (T7.13 corrective pass,
 * blocker 4) through the real Decompose state keeper.
 *
 * Durable board routes carry only the stable [StationId]; the board
 * displays the repository-resolved canonical station. A rename after the
 * save shows the current name (never the serialized old name); a deleted
 * station reaches the controlled unavailable state with usable Back. No
 * Station is ever manufactured from route text, and identity resolution
 * issues no provider search. Covers both the root station-board entry and
 * the stations-tab board.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StationBoardRestoreTest {
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

    private fun mainOf(root: RootComponent): MainComponent =
        root.stack.value.items.map { it.instance }
            .filterIsInstance<RootComponent.Child.Main>().single().component

    private fun stationsTabOf(main: MainComponent) =
        assertIs<MainComponent.Child.Stations>(
            main.pages.value.items[MainTab.Stations.ordinal].instance,
        ).component

    private fun favoritesOf(main: MainComponent) =
        assertIs<MainComponent.Child.Favorites>(
            main.pages.value.items[MainTab.Favorites.ordinal].instance,
        ).component

    @Test
    fun rootBoardRestoresCanonicalNameAfterRenameAndKeepsBoardKind() = runTest {
        val fixtures = Fixtures()
        fixtures.realtime.favorites.value = listOf(testStation)
        val lifecycle = LifecycleRegistry()
        val keeper = StateKeeperDispatcher(null)
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle, keeper), factory(fixtures))
        lifecycle.resume()
        val saved = try {
            mainOf(root).select(MainTab.Favorites)
            runCurrent()
            favoritesOf(mainOf(root)).open(testStation)
            runCurrent()
            val board = assertIs<RootComponent.Child.StationBoard>(
                root.stack.value.active.instance).component
            board.select(BoardKind.ARRIVALS)
            advanceTimeBy(1.seconds)
            assertEquals(testStation, board.stationState.value.station)
            keeper.save()
        } finally {
            lifecycle.destroy()
        }

        // The canonical station was renamed after the save; the route holds
        // only the id, so restoration must show the current name.
        val renamed = Station(testStation.id, "Roma Termini Nuova")
        val fresh = Fixtures()
        fresh.realtime.stationDirectory.value = mapOf(testStation.id to renamed)
        val revivedLifecycle = LifecycleRegistry()
        val revived = DefaultRootComponent(
            DefaultComponentContext(revivedLifecycle, StateKeeperDispatcher(saved)),
            factory(fresh),
        )
        revivedLifecycle.resume()
        try {
            advanceTimeBy(1.seconds)
            runCurrent()
            val revivedBoard = assertIs<RootComponent.Child.StationBoard>(
                revived.stack.value.active.instance).component
            assertEquals(renamed, revivedBoard.stationState.value.station)
            assertFalse(revivedBoard.stationState.value.unavailable)
            assertEquals(BoardKind.ARRIVALS, revivedBoard.kind.value)
            // Identity resolution never searches the provider network.
            assertTrue(fresh.realtime.searches.isEmpty())
            revived.back()
            assertIs<RootComponent.Child.Main>(revived.stack.value.active.instance)
        } finally {
            revivedLifecycle.destroy()
        }
    }

    @Test
    fun rootBoardShowsUnavailableWhenStationDeleted() = runTest {
        val fixtures = Fixtures()
        fixtures.realtime.favorites.value = listOf(testStation)
        val lifecycle = LifecycleRegistry()
        val keeper = StateKeeperDispatcher(null)
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle, keeper), factory(fixtures))
        lifecycle.resume()
        val saved = try {
            mainOf(root).select(MainTab.Favorites)
            runCurrent()
            favoritesOf(mainOf(root)).open(testStation)
            runCurrent()
            assertIs<RootComponent.Child.StationBoard>(root.stack.value.active.instance)
            keeper.save()
        } finally {
            lifecycle.destroy()
        }

        // The station no longer resolves: no Station is manufactured from
        // route text; the board exposes unavailable with usable Back.
        val fresh = Fixtures()
        fresh.realtime.stationDirectory.value = emptyMap()
        val revivedLifecycle = LifecycleRegistry()
        val revived = DefaultRootComponent(
            DefaultComponentContext(revivedLifecycle, StateKeeperDispatcher(saved)),
            factory(fresh),
        )
        revivedLifecycle.resume()
        try {
            advanceTimeBy(1.seconds)
            runCurrent()
            val revivedBoard = assertIs<RootComponent.Child.StationBoard>(
                revived.stack.value.active.instance).component
            assertNull(revivedBoard.stationState.value.station)
            assertTrue(revivedBoard.stationState.value.unavailable)
            assertTrue(fresh.realtime.searches.isEmpty())
            revived.back()
            assertIs<RootComponent.Child.Main>(revived.stack.value.active.instance)
        } finally {
            revivedLifecycle.destroy()
        }
    }

    @Test
    fun stationsTabBoardRestoresCanonicalNameAndSurvivesDeletion() = runTest {
        val fixtures = Fixtures()
        val lifecycle = LifecycleRegistry()
        val keeper = StateKeeperDispatcher(null)
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle, keeper), factory(fixtures))
        lifecycle.resume()
        val saved = try {
            val main = mainOf(root)
            main.select(MainTab.Stations)
            runCurrent()
            val tab = stationsTabOf(main)
            val search = assertIs<StationsTabComponent.Child.Search>(tab.stack.value.active.instance).component
            search.query("Roma")
            advanceTimeBy(1.seconds)
            search.select(testStation)
            advanceTimeBy(1.seconds)
            val board = assertIs<StationsTabComponent.Child.Board>(tab.stack.value.active.instance).component
            board.select(BoardKind.ARRIVALS)
            advanceTimeBy(1.seconds)
            assertEquals(testStation, board.stationState.value.station)
            keeper.save()
        } finally {
            lifecycle.destroy()
        }

        val renamed = Station(testStation.id, "Roma Termini Nuova")
        val fresh = Fixtures()
        fresh.realtime.stationDirectory.value = mapOf(testStation.id to renamed)
        val revivedLifecycle = LifecycleRegistry()
        val revived = DefaultRootComponent(
            DefaultComponentContext(revivedLifecycle, StateKeeperDispatcher(saved)),
            factory(fresh),
        )
        revivedLifecycle.resume()
        try {
            advanceTimeBy(1.seconds)
            runCurrent()
            val revivedMain = mainOf(revived)
            assertEquals(MainTab.Stations.ordinal, revivedMain.pages.value.selectedIndex)
            val revivedTab = stationsTabOf(revivedMain)
            val revivedBoard = assertIs<StationsTabComponent.Child.Board>(
                revivedTab.stack.value.active.instance).component
            assertEquals(renamed, revivedBoard.stationState.value.station)
            assertFalse(revivedBoard.stationState.value.unavailable)
            assertEquals(BoardKind.ARRIVALS, revivedBoard.kind.value)
            assertTrue(fresh.realtime.searches.isEmpty())
            // Deleting the canonical station afterwards flips the same
            // restored board into the unavailable state; Back still works.
            fresh.realtime.stationDirectory.value = emptyMap()
            runCurrent()
            assertNull(revivedBoard.stationState.value.station)
            assertTrue(revivedBoard.stationState.value.unavailable)
            revivedTab.back()
            assertIs<StationsTabComponent.Child.Search>(revivedTab.stack.value.active.instance)
        } finally {
            revivedLifecycle.destroy()
        }
    }
}
