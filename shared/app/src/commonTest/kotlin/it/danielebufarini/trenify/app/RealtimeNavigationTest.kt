package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.testing.*
import it.danielebufarini.trenify.feature.journey.JourneyTabComponent
import it.danielebufarini.trenify.feature.station.StationsTabComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeNavigationTest {
    @Test fun favoritesDestinationOpensStationBoardAndReturnsToFavorites() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories().apply { favorites.value = listOf(testStation) }
        val root = DefaultRootComponent(
            DefaultComponentContext(lifecycle),
            AppComponentFactory(
                repositories,
                repositories,
                repositories,
                repositories,
                MutableStateFlow(false),
                StandardTestDispatcher(testScheduler),
            ),
        )
        lifecycle.resume()
        runCurrent()

        val main = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
        main.select(MainTab.Favorites)
        val favorites = assertIs<MainComponent.Child.Favorites>(
            main.pages.value.items[MainTab.Favorites.ordinal].instance,
        ).component
        favorites.open(testStation)
        runCurrent()
        assertEquals(
            testStation,
            assertIs<RootComponent.Child.StationBoard>(root.stack.value.active.instance).component.stationState.value.station,
        )

        root.back()
        assertIs<RootComponent.Child.Main>(root.stack.value.active.instance)
        assertEquals(MainTab.Favorites.ordinal, main.pages.value.selectedIndex)
        lifecycle.destroy()
    }

    @Test fun favoriteRouteLaunchesPopulatedJourneySearchAndReturnsToFavoritesAfterRemoval() = runTest {
        val lifecycle = LifecycleRegistry()
        val route = FavoriteRoute.create(testStation, journeyDestination)
        val repositories = FakeRealtimeRepositories().apply { favoriteRoutes.value = listOf(route) }
        val root = DefaultRootComponent(
            DefaultComponentContext(lifecycle),
            AppComponentFactory(
                repositories,
                repositories,
                repositories,
                repositories,
                MutableStateFlow(false),
                StandardTestDispatcher(testScheduler),
                journeyRepository = FakeJourneyRepository(),
            ),
        )
        lifecycle.resume()
        runCurrent()

        val main = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
        main.select(MainTab.Favorites)
        val favorites = assertIs<MainComponent.Child.Favorites>(
            main.pages.value.items[MainTab.Favorites.ordinal].instance,
        ).component
        favorites.open(route)
        runCurrent()

        assertEquals(MainTab.Journey.ordinal, main.pages.value.selectedIndex)
        val journey = assertIs<MainComponent.Child.Journey>(
            main.pages.value.items[MainTab.Journey.ordinal].instance,
        ).component
        val search = assertIs<JourneyTabComponent.Child.Search>(journey.stack.value.active.instance).component
        assertEquals(route.origin, search.state.value.origin)
        assertEquals(route.destination, search.state.value.destination)

        main.select(MainTab.Favorites)
        favorites.remove(route)
        runCurrent()
        assertTrue(favorites.state.value.routes.isEmpty())
        lifecycle.destroy()
    }

    @Test fun favoriteTrainLaunchesFreshLookupAndRequiresExplicitSelectionWhenAmbiguous() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val monitoring = FakeMonitoringRepository()
        val train = FavoriteTrain.create(
            TrainNumber("123"),
            originId = testStation.id,
            operator = Operator("Trenitalia"),
            originName = testStation.name,
        )
        repositories.favoriteTrains.value = listOf(train)
        val second = testSummary.copy(id = testRunId.copy(origin = ExternalStationRef("second-origin")))
        repositories.runsResult = DataResult.Data(listOf(testSummary, second), DataFreshness.Unknown)
        val root = DefaultRootComponent(
            DefaultComponentContext(lifecycle),
            AppComponentFactory(
                repositories,
                repositories,
                repositories,
                repositories,
                MutableStateFlow(false),
                StandardTestDispatcher(testScheduler),
                monitoringRepository = monitoring,
            ),
        )
        lifecycle.resume()
        runCurrent()

        val main = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
        main.select(MainTab.Favorites)
        val favorites = assertIs<MainComponent.Child.Favorites>(
            main.pages.value.items[MainTab.Favorites.ordinal].instance,
        ).component
        favorites.open(train)
        runCurrent()

        // Launch prefills the number and starts a fresh lookup, not a cached run.
        val search = assertIs<RootComponent.Child.Search>(root.stack.value.active.instance).component
        assertEquals("123", search.state.value.number)
        runCurrent()
        assertEquals(2, search.state.value.results.data?.size)
        // Ambiguous candidates never silently navigate to the wrong run.
        assertIs<RootComponent.Child.Search>(root.stack.value.active.instance)

        search.select(second)
        runCurrent()
        val detail = assertIs<RootComponent.Child.Detail>(root.stack.value.active.instance).component
        assertEquals(second.id, detail.id)

        // Saving from detail stores a recurring reference and never creates a monitor.
        detail.toggleFavoriteTrain()
        runCurrent()
        assertEquals(
            setOf(
                train,
                FavoriteTrain.create(
                    TrainNumber("123"),
                    originId = testStation.id,
                    operator = null,
                    originName = testStation.name,
                    destinationName = "Milano Centrale",
                ),
            ),
            repositories.favoriteTrains.value.toSet(),
        )
        assertTrue(monitoring.monitors.value.isEmpty())
        lifecycle.destroy()
    }

    @Test fun favoriteTrainLaunchWithSingleIncompatibleCandidateNeverOpensIt() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val train = FavoriteTrain.create(
            TrainNumber("123"),
            originId = testStation.id,
            operator = Operator("Trenitalia"),
            originName = testStation.name,
        )
        repositories.favoriteTrains.value = listOf(train)
        val napoli = Station(StationId("napoli-centrale"), "Napoli Centrale")
        repositories.runsResult = DataResult.Data(
            listOf(
                testSummary.copy(
                    id = testRunId.copy(origin = ExternalStationRef("napoli-origin")),
                    origin = napoli,
                    operator = Operator("Trenitalia"),
                ),
            ),
            DataFreshness.Unknown,
        )
        val root = DefaultRootComponent(
            DefaultComponentContext(lifecycle),
            AppComponentFactory(
                repositories,
                repositories,
                repositories,
                repositories,
                MutableStateFlow(false),
                StandardTestDispatcher(testScheduler),
            ),
        )
        lifecycle.resume()
        runCurrent()

        val main = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
        main.select(MainTab.Favorites)
        val favorites = assertIs<MainComponent.Child.Favorites>(
            main.pages.value.items[MainTab.Favorites.ordinal].instance,
        ).component
        favorites.open(train)
        runCurrent()

        // The Napoli run conflicts with the saved Roma discrimination: the
        // launch stays in a controlled search state instead of opening it.
        val search = assertIs<RootComponent.Child.Search>(root.stack.value.active.instance).component
        runCurrent()
        assertTrue(search.state.value.noCompatibleService)
        assertTrue(search.state.value.results.data?.isEmpty() == true)
        assertIs<RootComponent.Child.Search>(root.stack.value.active.instance)
        lifecycle.destroy()
    }

    @Test fun favoriteTrainLaunchWithNoCandidatesStaysInControlledSearchState() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val train = FavoriteTrain.create(
            TrainNumber("123"),
            originId = testStation.id,
            operator = Operator("Trenitalia"),
            originName = testStation.name,
        )
        repositories.favoriteTrains.value = listOf(train)
        repositories.runsResult = DataResult.Data(emptyList(), DataFreshness.Unknown)
        val root = DefaultRootComponent(
            DefaultComponentContext(lifecycle),
            AppComponentFactory(
                repositories,
                repositories,
                repositories,
                repositories,
                MutableStateFlow(false),
                StandardTestDispatcher(testScheduler),
            ),
        )
        lifecycle.resume()
        runCurrent()

        val main = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
        main.select(MainTab.Favorites)
        val favorites = assertIs<MainComponent.Child.Favorites>(
            main.pages.value.items[MainTab.Favorites.ordinal].instance,
        ).component
        favorites.open(train)
        runCurrent()

        val search = assertIs<RootComponent.Child.Search>(root.stack.value.active.instance).component
        runCurrent()
        assertTrue(search.state.value.results.data?.isEmpty() == true)
        assertIs<RootComponent.Child.Search>(root.stack.value.active.instance)
        lifecycle.destroy()
    }

    @Test fun trainHistoryRepeatPrefillsNumberDateAndDiscriminationForExplicitSearch() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val train = FavoriteTrain.create(
            TrainNumber("123"),
            originId = testStation.id,
            operator = Operator("Trenitalia"),
            originName = testStation.name,
        )
        repositories.favoriteTrains.value = listOf(train)
        val napoli = Station(StationId("napoli-centrale"), "Napoli Centrale")
        val napoliRun = testSummary.copy(
            id = testRunId.copy(origin = ExternalStationRef("napoli-origin")),
            origin = napoli,
            operator = Operator("Trenitalia"),
        )
        repositories.runsResult = DataResult.Data(listOf(testSummary, napoliRun), DataFreshness.Unknown)
        val root = DefaultRootComponent(
            DefaultComponentContext(lifecycle),
            AppComponentFactory(
                repositories,
                repositories,
                repositories,
                repositories,
                MutableStateFlow(false),
                StandardTestDispatcher(testScheduler),
                journeyRepository = FakeJourneyRepository(),
            ),
        )
        lifecycle.resume()
        runCurrent()

        val main = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
        main.select(MainTab.Favorites)
        val favorites = assertIs<MainComponent.Child.Favorites>(
            main.pages.value.items[MainTab.Favorites.ordinal].instance,
        ).component
        favorites.open(train)
        runCurrent()

        // The favorite launch auto-searches and records a discriminated entry.
        assertIs<RootComponent.Child.Search>(root.stack.value.active.instance)
        runCurrent()
        val recorded = assertIs<TrainSearchHistoryEntry>(repositories.observeSearchHistory().first().single())
        assertEquals(train.lookupIntent, recorded.lookupIntent())

        // Repeating from history prefills the existing train-search flow
        // without executing it and without reopening a dated run.
        root.back()
        runCurrent()
        assertIs<RootComponent.Child.Main>(root.stack.value.active.instance)
        main.select(MainTab.Journey)
        val journey = assertIs<MainComponent.Child.Journey>(
            main.pages.value.items[MainTab.Journey.ordinal].instance,
        ).component
        val form = assertIs<JourneyTabComponent.Child.Search>(journey.stack.value.active.instance).component
        form.history()
        runCurrent()
        val historyChild = assertIs<JourneyTabComponent.Child.History>(journey.stack.value.active.instance).component
        runCurrent()
        historyChild.repeat(recorded)
        runCurrent()
        val prefilled = assertIs<RootComponent.Child.Search>(root.stack.value.active.instance).component
        assertEquals("123", prefilled.state.value.number)
        assertEquals(recorded.serviceDate, prefilled.state.value.serviceDate)
        assertNull(prefilled.state.value.results.data)

        // The later explicit search re-applies the stored discrimination: a
        // conflicting single candidate is withheld instead of being opened.
        repositories.runsResult = DataResult.Data(listOf(napoliRun), DataFreshness.Unknown)
        prefilled.search()
        runCurrent()
        assertTrue(prefilled.state.value.noCompatibleService)
        assertTrue(prefilled.state.value.results.data?.isEmpty() == true)
        assertIs<RootComponent.Child.Search>(root.stack.value.active.instance)
        lifecycle.destroy()
    }

    // This exact scenario executes on Android/JVM and iOS against the same repositories.
    @Test fun sharedRootNavigatesSearchBoardPickerAndDetailAndStopsHiddenPolling() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle),
            AppComponentFactory(repositories, repositories, repositories, repositories,
                MutableStateFlow(true), StandardTestDispatcher(testScheduler)))
        lifecycle.resume()
        try {
            val main = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
            main.select(MainTab.Stations)
            val stations = assertIs<MainComponent.Child.Stations>(main.pages.value.items[MainTab.Stations.ordinal].instance).component
            val search = assertIs<StationsTabComponent.Child.Search>(stations.stack.value.active.instance).component
            search.query("Roma")
            advanceTimeBy(276)
            runCurrent()
            assertEquals(listOf(testStation), search.state.value.results.data)
            search.select(testStation)
            runCurrent()
            val board = assertIs<StationsTabComponent.Child.Board>(stations.stack.value.active.instance).component
            assertEquals(1, repositories.boardRefreshes)
            board.openTrain(testRunId)
            runCurrent()
            val detail = assertIs<RootComponent.Child.Detail>(root.stack.value.active.instance).component
            assertEquals(testRun, detail.state.value.data)
            advanceTimeBy(30_001)
            runCurrent()
            assertEquals(1, repositories.boardRefreshes)
            val detailCalls = repositories.trainRefreshes
            root.back()
            runCurrent()
            advanceTimeBy(30_001)
            runCurrent()
            assertEquals(detailCalls, repositories.trainRefreshes)
            stations.back()
            stations.searchTrains()
            val trainSearch = assertIs<RootComponent.Child.Search>(root.stack.value.active.instance).component
            val second = testSummary.copy(id = testRunId.copy(origin = ExternalStationRef("second-origin")))
            repositories.runsResult = DataResult.Data(listOf(testSummary, second), DataFreshness.Unknown)
            trainSearch.number("123")
            trainSearch.search()
            runCurrent()
            assertIs<RootComponent.Child.Search>(root.stack.value.active.instance)
            assertEquals(2, trainSearch.state.value.results.data?.size)
            trainSearch.select(second)
            runCurrent()
            assertEquals(second.id, assertIs<RootComponent.Child.Detail>(root.stack.value.active.instance).component.id)
        } finally {
            // A pre-destroy assertion failure must still cancel every
            // polling loop: otherwise runTest's closing drain reschedules
            // forever and hangs the worker instead of reporting the failure.
            lifecycle.destroy()
        }
    }
}
