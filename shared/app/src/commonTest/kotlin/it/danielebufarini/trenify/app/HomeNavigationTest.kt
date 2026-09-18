package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.model.FavoriteRoute
import it.danielebufarini.trenify.core.model.JourneySearchMode
import it.danielebufarini.trenify.core.model.JourneySearchRequest
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.TrainLookupIntent
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.testing.FakeJourneyRepository
import it.danielebufarini.trenify.core.testing.FakeMonitoringRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.testing.journeyDestination
import it.danielebufarini.trenify.core.testing.journeyRequest
import it.danielebufarini.trenify.core.testing.testStation
import it.danielebufarini.trenify.core.testing.testStrike
import it.danielebufarini.trenify.feature.home.HomeComponent
import it.danielebufarini.trenify.feature.journey.JourneyTabComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class HomeNavigationTest {
    private fun homeOf(root: RootComponent): HomeComponent {
        val main = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
        return assertIs<MainComponent.Child.Home>(
            main.pages.value.items[MainTab.Home.ordinal].instance,
        ).component
    }

    private fun journeyOf(root: RootComponent): JourneyTabComponent {
        val main = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
        return assertIs<MainComponent.Child.Journey>(
            main.pages.value.items[MainTab.Journey.ordinal].instance,
        ).component
    }

    @Test fun homeDestinationPreservesAllFeatureTabsAndRoutesSixActions() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
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
                monitoringRepository = FakeMonitoringRepository(),
                strikeRepository = FakeStrikeRepository(),
            ),
        )
        lifecycle.resume()
        runCurrent()
        try {
            val main = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
            // All five feature destinations still exist alongside Home.
            assertEquals(MainTab.entries.size, main.pages.value.items.size)
            val home = homeOf(root)
            runCurrent()

            home.openJourneySearch()
            runCurrent()
            assertIs<JourneyTabComponent.Child.Search>(journeyOf(root).stack.value.active.instance)

            home.openTrainSearch()
            runCurrent()
            assertIs<RootComponent.Child.Search>(root.stack.value.active.instance)
            root.back()
            runCurrent()

            home.openStations()
            runCurrent()
            assertEquals(
                MainTab.Stations.ordinal,
                assertIs<RootComponent.Child.Main>(root.stack.value.active.instance)
                    .component.pages.value.selectedIndex,
            )

            home.openMonitoring()
            runCurrent()
            assertEquals(
                MainTab.Monitoring.ordinal,
                assertIs<RootComponent.Child.Main>(root.stack.value.active.instance)
                    .component.pages.value.selectedIndex,
            )
            home.openFavorites()
            runCurrent()
            assertEquals(
                MainTab.Favorites.ordinal,
                assertIs<RootComponent.Child.Main>(root.stack.value.active.instance)
                    .component.pages.value.selectedIndex,
            )
            home.openAlerts()
            runCurrent()
            val afterAlerts = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
            assertEquals(MainTab.Alerts.ordinal, afterAlerts.pages.value.selectedIndex)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun homeFavoriteRouteLaunchesPopulatedJourneySearch() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val route = FavoriteRoute.create(testStation, journeyDestination)
        repositories.setFavorite(route, true)
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
                monitoringRepository = FakeMonitoringRepository(),
                strikeRepository = FakeStrikeRepository(),
            ),
        )
        lifecycle.resume()
        runCurrent()
        try {
            val home = homeOf(root)
            runCurrent()
            assertEquals(listOf(route), home.state.value.favoriteRoutes)

            home.openRoute(route)
            runCurrent()
            val main = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
            assertEquals(MainTab.Journey.ordinal, main.pages.value.selectedIndex)
            val search = assertIs<JourneyTabComponent.Child.Search>(
                journeyOf(root).stack.value.active.instance,
            ).component
            assertEquals(testStation, search.state.value.origin)
            assertEquals(journeyDestination, search.state.value.destination)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun homeJourneyHistoryRepeatPreservesFullRequestThroughRoot() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
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
                monitoringRepository = FakeMonitoringRepository(),
                strikeRepository = FakeStrikeRepository(),
            ),
        )
        lifecycle.resume()
        runCurrent()
        try {
            val home = homeOf(root)
            runCurrent()
            // 18:30 Rome time on 2026-09-15 with ARRIVE_BY, recorded elsewhere.
            val entry = repositories.recordSearch(
                JourneySearchRequest(
                    testStation,
                    journeyDestination,
                    Instant.parse("2026-09-15T16:30:00Z"),
                    JourneySearchMode.ARRIVE_BY,
                ),
            )
            runCurrent()
            assertEquals(listOf(entry), home.state.value.recentSearches)

            home.openRecent(entry)
            runCurrent()
            val main = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
            assertEquals(MainTab.Journey.ordinal, main.pages.value.selectedIndex)
            val search = assertIs<JourneyTabComponent.Child.Search>(
                journeyOf(root).stack.value.active.instance,
            ).component
            assertEquals(testStation, search.state.value.origin)
            assertEquals(journeyDestination, search.state.value.destination)
            assertEquals(LocalDate.parse("2026-09-15"), search.state.value.date)
            assertEquals(18, search.state.value.timeHour)
            assertEquals(30, search.state.value.timeMinute)
            assertEquals(JourneySearchMode.ARRIVE_BY, search.state.value.mode)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun homeTrainHistoryRepeatPreservesNumberDateAndDiscrimination() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
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
                monitoringRepository = FakeMonitoringRepository(),
                strikeRepository = FakeStrikeRepository(),
            ),
        )
        lifecycle.resume()
        runCurrent()
        try {
            val home = homeOf(root)
            val entry = repositories.recordTrainSearch(
                TrainNumber("123"),
                LocalDate.parse("2026-09-05"),
                TrainLookupIntent(
                    TrainNumber("123"),
                    originId = testStation.id,
                    originName = testStation.name,
                    operator = Operator("Trenitalia"),
                ),
            )
            runCurrent()

            home.openRecent(entry)
            runCurrent()
            val search = assertIs<RootComponent.Child.Search>(root.stack.value.active.instance).component
            assertEquals("123", search.state.value.number)
            assertEquals(LocalDate.parse("2026-09-05"), search.state.value.serviceDate)
            assertNull(search.state.value.results.data)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun homeHistoryActionOpensSharedHistoryAndUpdatesReactively() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val strikes = FakeStrikeRepository()
        // Test-only wall-clock fix: the shared strike fixture is a fixed
        // September 2026 window that Home's relevance filter (end > now)
        // hides once the real clock moves past it. Re-anchor a copy of the
        // same strike around the actual now so the test stays deterministic
        // without touching product strike semantics.
        val liveStrike = testStrike.copy(
            start = kotlin.time.Clock.System.now() - kotlin.time.Duration.parse("1h"),
            end = kotlin.time.Clock.System.now() + kotlin.time.Duration.parse("24h"),
        )
        strikes.state.value = it.danielebufarini.trenify.core.domain.DataResult.Data(
            listOf(liveStrike),
            it.danielebufarini.trenify.core.model.DataFreshness.Unknown,
        )
        strikes.refreshResult = it.danielebufarini.trenify.core.domain.DataResult.Data(
            it.danielebufarini.trenify.core.domain.StrikeRefresh(listOf(liveStrike)),
            it.danielebufarini.trenify.core.model.DataFreshness.Unknown,
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
                journeyRepository = FakeJourneyRepository(),
                monitoringRepository = FakeMonitoringRepository(),
                strikeRepository = strikes,
            ),
        )
        lifecycle.resume()
        runCurrent()
        try {
            val home = homeOf(root)
            runCurrent()
            assertTrue(home.state.value.recentSearches.isEmpty())

            // A journey submitted elsewhere appears on Home without reopening it.
            val journey = repositories.recordSearch(journeyRequest)
            runCurrent()
            assertEquals(listOf(journey), home.state.value.recentSearches)
            assertEquals(
                listOf(liveStrike),
                home.state.value.strikes.data,
                "strikes load from local state with notifications disabled",
            )

            home.openHistory()
            runCurrent()
            assertIs<JourneyTabComponent.Child.History>(journeyOf(root).stack.value.active.instance)

            // Removing the entry updates Home reactively through the same flow.
            val history = assertIs<JourneyTabComponent.Child.History>(
                journeyOf(root).stack.value.active.instance,
            ).component
            history.remove(journey)
            runCurrent()
            assertTrue(home.state.value.recentSearches.isEmpty())
        } finally {
            lifecycle.destroy()
        }
    }
}
