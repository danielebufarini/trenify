package it.danielebufarini.trenify.feature.home

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.domain.CurrentStrikeWindow
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.LoadStrikes
import it.danielebufarini.trenify.core.domain.ObserveActiveMonitors
import it.danielebufarini.trenify.core.domain.StrikePolicy
import it.danielebufarini.trenify.core.domain.StrikeRefresh
import it.danielebufarini.trenify.core.domain.StrikeRepository
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.FavoriteRoute
import it.danielebufarini.trenify.core.model.FavoriteTrain
import it.danielebufarini.trenify.core.model.JourneySearchHistoryEntry
import it.danielebufarini.trenify.core.model.JourneySearchIntent
import it.danielebufarini.trenify.core.model.JourneySearchMode
import it.danielebufarini.trenify.core.model.JourneySearchRequest
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.SearchHistoryEntryId
import it.danielebufarini.trenify.core.model.Station
import it.danielebufarini.trenify.core.model.StationId
import it.danielebufarini.trenify.core.model.Strike
import it.danielebufarini.trenify.core.model.StrikeId
import it.danielebufarini.trenify.core.model.StrikeStatus
import it.danielebufarini.trenify.core.model.TrainLookupIntent
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.model.TrainRunId
import it.danielebufarini.trenify.core.model.TrainSearchHistoryEntry
import it.danielebufarini.trenify.core.testing.FakeMonitoringRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.domain.MonitoredTrainSnapshot
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.TickingClock
import it.danielebufarini.trenify.core.testing.journeyDestination
import it.danielebufarini.trenify.core.testing.journeyRequest
import it.danielebufarini.trenify.core.testing.testRun
import it.danielebufarini.trenify.core.testing.testRunId
import it.danielebufarini.trenify.core.testing.testStation
import it.danielebufarini.trenify.core.testing.testStrike
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class HomeComponentsTest {
    @Test fun emptyHomeShowsUsablePromptsWithoutProviderAccess() = runTest {
        val history = FakeRealtimeRepositories()
        val strikes = FakeStrikeRepository()
        strikes.state.value = DataResult.Data(emptyList(), DataFreshness.Unknown)
        strikes.refreshResult = DataResult.Data(StrikeRefresh(emptyList()), DataFreshness.Unknown)
        val lifecycle = LifecycleRegistry()
        val component = HomeComponent(
            DefaultComponentContext(lifecycle),
            history,
            history,
            ObserveActiveMonitors(FakeMonitoringRepository()),
            LoadStrikes(strikes),
            StandardTestDispatcher(testScheduler),
            clock = MutableClock(),
        )
        lifecycle.resume()
        try {
            runCurrent()
            val state = component.state.value
            assertTrue(state.recentSearches.isEmpty())
            assertTrue(state.favoriteStations.isEmpty())
            assertTrue(state.favoriteRoutes.isEmpty())
            assertTrue(state.favoriteTrains.isEmpty())
            assertTrue(state.monitors.isEmpty())
            assertTrue(state.strikes.data.orEmpty().isEmpty())
            assertFalse(state.observationFailed)
            // Exactly one targeted entry refresh; observation itself fetches nothing.
            assertEquals(1, strikes.refreshes)
        } finally { lifecycle.destroy() }
    }

    @Test fun aggregatesAllFourInputsReactivelyWithoutReopening() = runTest {
        val clock = MutableClock()
        val history = FakeRealtimeRepositories()
        val monitoring = FakeMonitoringRepository(clock)
        val strikes = FakeStrikeRepository()
        val lifecycle = LifecycleRegistry()
        val component = HomeComponent(
            DefaultComponentContext(lifecycle),
            history,
            history,
            ObserveActiveMonitors(monitoring),
            LoadStrikes(strikes),
            StandardTestDispatcher(testScheduler),
            clock = clock,
        )
        lifecycle.resume()
        try {
            runCurrent()
            val journey = history.recordSearch(journeyRequest)
            val train = history.recordTrainSearch(TrainNumber("123"), null, expected = null)
            history.setFavorite(testStation, true)
            val route = FavoriteRoute.create(testStation, journeyDestination)
            history.setFavorite(route, true)
            val favoriteTrain = FavoriteTrain.create(TrainNumber("456"), originId = testStation.id, operator = Operator("Trenitalia"))
            history.setFavorite(favoriteTrain, true)
            val monitor = monitoring.createMonitor(testRunId, expiresAt = clock.now() + 1.hours)
            monitoring.persistEvaluation(
                monitor.id,
                MonitoredTrainSnapshot(testRun, DataFreshness.Unknown, clock.now()),
                emptyList(),
                monitor.snapshotVersion,
                0L,
            )
            runCurrent()

            val state = component.state.value
            assertEquals(listOf(train, journey), state.recentSearches)
            assertEquals(listOf(testStation), state.favoriteStations)
            assertEquals(listOf(route), state.favoriteRoutes)
            assertEquals(listOf(favoriteTrain), state.favoriteTrains)
            assertEquals(testRun, state.monitors.single().lastSnapshot?.train)
            assertEquals(listOf(testStrike), state.strikes.data)
            // Populating favorites and monitors triggers no extra strike fetch.
            assertEquals(1, strikes.refreshes)
        } finally { lifecycle.destroy() }
    }

    @Test fun recentSearchesAreCappedWhileFullHistoryStaysIntact() = runTest {
        val history = FakeRealtimeRepositories()
        val lifecycle = LifecycleRegistry()
        val component = HomeComponent(
            DefaultComponentContext(lifecycle),
            history,
            history,
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = MutableClock(),
        )
        lifecycle.resume()
        try {
            repeat(HOME_RECENT_LIMIT + 2) { history.recordSearch(journeyRequest) }
            runCurrent()
            assertEquals(HOME_RECENT_LIMIT, component.state.value.recentSearches.size)
            assertEquals(HOME_RECENT_LIMIT + 2, history.observeSearchHistory().first().size)
        } finally { lifecycle.destroy() }
    }

    @Test fun primaryActionsAndItemLaunchesReachTheirDestinations() = runTest {
        val history = FakeRealtimeRepositories()
        val journey = history.recordSearch(journeyRequest)
        val train = history.recordTrainSearch(TrainNumber("123"), null, expected = null)
        history.setFavorite(testStation, true)
        val route = FavoriteRoute.create(testStation, journeyDestination)
        history.setFavorite(route, true)
        val favoriteTrain = FavoriteTrain.create(TrainNumber("456"), originId = testStation.id, operator = Operator("Trenitalia"))
        history.setFavorite(favoriteTrain, true)
        val actions = mutableListOf<String>()
        val routes = mutableListOf<JourneySearchIntent>()
        val journeyRepeats = mutableListOf<JourneySearchRequest>()
        val lookups = mutableListOf<TrainLookupIntent>()
        val stations = mutableListOf<Station>()
        val trains = mutableListOf<TrainRunId>()
        val repeats = mutableListOf<TrainSearchHistoryEntry>()
        val lifecycle = LifecycleRegistry()
        val component = HomeComponent(
            DefaultComponentContext(lifecycle),
            history,
            history,
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = MutableClock(),
            onJourneySearch = { actions += "journeys" },
            onTrainSearch = { actions += "trains" },
            onStations = { actions += "stations" },
            onMonitoring = { actions += "monitoring" },
            onFavorites = { actions += "favorites" },
            onAlerts = { actions += "alerts" },
            onHistory = { actions += "history" },
            onStation = { stations += it },
            onRoute = { routes += it },
            onTrainLookup = { lookups += it },
            onTrain = { trains += it },
            onTrainRepeat = { repeats += it },
            onJourneyRepeat = { journeyRepeats += it },
        )
        lifecycle.resume()
        try {
            runCurrent()
            component.openJourneySearch()
            component.openTrainSearch()
            component.openStations()
            component.openMonitoring()
            component.openFavorites()
            component.openAlerts()
            component.openHistory()
            assertEquals(
                listOf("journeys", "trains", "stations", "monitoring", "favorites", "alerts", "history"),
                actions,
            )

            // Journey-history repeats carry the full recorded request, while
            // favorite routes keep endpoint-only intent semantics.
            component.openRecent(journey)
            assertEquals(listOf(journey.request()), journeyRepeats)
            assertTrue(routes.isEmpty())
            component.openRoute(route)
            assertEquals(listOf(route.searchIntent), routes)
            assertEquals(listOf(journey.request()), journeyRepeats)
            component.openRecent(train)
            assertEquals(listOf(train), repeats)
            component.openStation(testStation)
            assertEquals(listOf(testStation), stations)
            component.openTrain(favoriteTrain)
            assertEquals(listOf(favoriteTrain.lookupIntent), lookups)
            component.openMonitor(testRunId)
            assertEquals(listOf(testRunId), trains)
            component.openStrike()
            assertEquals(listOf("journeys", "trains", "stations", "monitoring", "favorites", "alerts", "history", "alerts"), actions)
        } finally { lifecycle.destroy() }
    }

    @Test fun entryRefreshEmitsNoNotificationsWhenOptedOut() = runTest {
        val strikes = FakeStrikeRepository()
        strikes.notificationsEnabled.value = false
        val lifecycle = LifecycleRegistry()
        val component = HomeComponent(
            DefaultComponentContext(lifecycle),
            FakeRealtimeRepositories(),
            FakeRealtimeRepositories(),
            loadStrikes = LoadStrikes(strikes),
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = MutableClock(),
        )
        lifecycle.resume()
        try {
            runCurrent()
            // Strikes still load with the opt-in off, without any
            // notification side effect: nothing pending, nothing claimed.
            assertEquals(listOf(testStrike), component.state.value.strikes.data)
            assertEquals(1, strikes.refreshes)
            assertTrue(strikes.pending.isEmpty())
            assertTrue(strikes.claimed.isEmpty())
            assertFalse(strikes.notificationsEnabled.value)
        } finally { lifecycle.destroy() }
    }

    @Test fun manualRefreshRevalidatesAndFailureRecoversThroughRetry() = runTest {
        val strikes = FakeStrikeRepository()
        val lifecycle = LifecycleRegistry()
        val component = HomeComponent(
            DefaultComponentContext(lifecycle),
            FakeRealtimeRepositories(),
            FakeRealtimeRepositories(),
            loadStrikes = LoadStrikes(strikes),
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = MutableClock(),
        )
        lifecycle.resume()
        try {
            runCurrent()
            assertEquals(1, strikes.refreshes)
            component.refreshStrikes()
            runCurrent()
            assertEquals(2, strikes.refreshes)
            assertFalse(component.state.value.strikes.failed)

            strikes.refreshResult = DataResult.Failure(DomainFailure.OFFLINE)
            component.refreshStrikes()
            runCurrent()
            assertTrue(component.state.value.strikes.failed)
            // Cached content stays visible while the failure is shown.
            assertEquals(listOf(testStrike), component.state.value.strikes.data)

            strikes.refreshResult = DataResult.Data(StrikeRefresh(listOf(testStrike)), DataFreshness.Unknown)
            component.retry()
            runCurrent()
            assertFalse(component.state.value.strikes.failed)
            assertEquals(4, strikes.refreshes)
        } finally { lifecycle.destroy() }
    }

    @Test fun expiredAndRevokedStrikesStayOutOfHome() = runTest {
        val strikes = FakeStrikeRepository()
        val expired = testStrike.copy(
            id = StrikeId("mit-strikes:expired"),
            start = MutableClock().now() - 3.hours,
            end = MutableClock().now() - 1.hours,
        )
        val revoked = testStrike.copy(
            id = StrikeId("mit-strikes:revoked"),
            status = StrikeStatus.REVOKED,
        )
        strikes.state.value = DataResult.Data(listOf(testStrike, expired, revoked), DataFreshness.Unknown)
        strikes.refreshResult = DataResult.Data(StrikeRefresh(listOf(testStrike, expired, revoked)), DataFreshness.Unknown)
        val lifecycle = LifecycleRegistry()
        val component = HomeComponent(
            DefaultComponentContext(lifecycle),
            FakeRealtimeRepositories(),
            FakeRealtimeRepositories(),
            loadStrikes = LoadStrikes(strikes),
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = MutableClock(),
        )
        lifecycle.resume()
        try {
            runCurrent()
            assertEquals(listOf(testStrike), component.state.value.strikes.data)
        } finally { lifecycle.destroy() }
    }

    @Test fun journeyHistoryRepeatPreservesOriginDestinationAtAndMode() = runTest {
        val milano = Station(StationId("milano-centrale"), "Milano Centrale")
        val roma = Station(StationId("roma-termini"), "Roma Termini")
        val at = Instant.parse("2026-09-15T18:30:00Z")
        val arriveBy = JourneySearchHistoryEntry(
            SearchHistoryEntryId("history-arrive"),
            milano,
            roma,
            at,
            JourneySearchMode.ARRIVE_BY,
            submittedAt = MutableClock().now(),
        )
        val departAfter = arriveBy.copy(
            id = SearchHistoryEntryId("history-depart"),
            mode = JourneySearchMode.DEPART_AFTER,
        )
        val repeated = mutableListOf<JourneySearchRequest>()
        val lifecycle = LifecycleRegistry()
        val component = HomeComponent(
            DefaultComponentContext(lifecycle),
            FakeRealtimeRepositories(),
            FakeRealtimeRepositories(),
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = MutableClock(),
            onJourneyRepeat = { repeated += it },
        )
        lifecycle.resume()
        try {
            runCurrent()
            component.openRecent(arriveBy)
            component.openRecent(departAfter)
            // All four semantic fields round-trip exactly; a historical
            // search never degrades to current-time DEPART_AFTER prefill.
            assertEquals(
                listOf(
                    JourneySearchRequest(milano, roma, at, JourneySearchMode.ARRIVE_BY),
                    JourneySearchRequest(milano, roma, at, JourneySearchMode.DEPART_AFTER),
                ),
                repeated,
            )
        } finally { lifecycle.destroy() }
    }

    @Test fun manualRefreshDerivesCurrentWindowWithoutPolling() = runTest {
        val clock = MutableClock(Instant.parse("2026-09-07T08:00:00Z"))
        val policy = StrikePolicy()
        val strikes = FakeStrikeRepository()
        val lifecycle = LifecycleRegistry()
        val component = HomeComponent(
            DefaultComponentContext(lifecycle),
            FakeRealtimeRepositories(),
            FakeRealtimeRepositories(),
            loadStrikes = LoadStrikes(strikes),
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = clock,
            policy = policy,
        )
        lifecycle.resume()
        try {
            runCurrent()
            assertEquals(1, strikes.refreshes)
            val createdAt = Instant.parse("2026-09-07T08:00:00Z")
            assertEquals(
                (createdAt - policy.historyWindow) to (createdAt + policy.futureWindow),
                strikes.refreshWindows.single(),
            )
            // Time passing alone triggers no refresh: no polling is added.
            clock.instant = Instant.parse("2026-09-09T08:00:00Z")
            runCurrent()
            assertEquals(1, strikes.refreshes)
            component.refreshStrikes()
            runCurrent()
            assertEquals(2, strikes.refreshes)
            val refreshedAt = Instant.parse("2026-09-09T08:00:00Z")
            assertEquals(
                (refreshedAt - policy.historyWindow) to (refreshedAt + policy.futureWindow),
                strikes.refreshWindows.last(),
            )
        } finally { lifecycle.destroy() }
    }

    @Test fun refreshWindowUsesASingleCapturedNow() = runTest {
        val clock = TickingClock(Instant.parse("2026-09-07T08:00:00Z"))
        val policy = StrikePolicy()
        val strikes = FakeStrikeRepository()
        val lifecycle = LifecycleRegistry()
        val component = HomeComponent(
            DefaultComponentContext(lifecycle),
            FakeRealtimeRepositories(),
            FakeRealtimeRepositories(),
            loadStrikes = LoadStrikes(strikes),
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = clock,
            policy = policy,
        )
        lifecycle.resume()
        try {
            runCurrent()
            component.refreshStrikes()
            runCurrent()
            // Every recorded refresh spans exactly the policy duration: with
            // two independent now() reads a ticking clock would stretch it.
            assertTrue(strikes.refreshWindows.isNotEmpty())
            strikes.refreshWindows.forEach { (from, to) ->
                assertEquals(policy.historyWindow + policy.futureWindow, to - from)
            }
        } finally { lifecycle.destroy() }
    }

    @Test fun manualRefreshAdvancesObservationToNewlyFetchedBoundaryStrike() = runTest {
        val policy = StrikePolicy()
        val createdAt = Instant.parse("2026-09-07T08:00:00Z")
        val refreshedAt = Instant.parse("2026-09-10T08:00:00Z")
        val clock = MutableClock(createdAt)
        // Visible only in the T2 window: outside [T1 - history, T1 + future],
        // inside [T2 - history, T2 + future].
        val boundary = testStrike.copy(
            id = StrikeId("mit-strikes:boundary"),
            start = Instant.parse("2026-12-07T00:00:00Z"),
            end = Instant.parse("2026-12-08T00:00:00Z"),
        )
        val strikes = WindowedFakeStrikeRepository()
        val lifecycle = LifecycleRegistry()
        val component = HomeComponent(
            DefaultComponentContext(lifecycle),
            FakeRealtimeRepositories(),
            FakeRealtimeRepositories(),
            loadStrikes = LoadStrikes(strikes),
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = clock,
            policy = policy,
        )
        lifecycle.resume()
        try {
            runCurrent()
            val initialWindow = policy.currentWindow(createdAt)
            assertEquals(listOf(initialWindow.from to initialWindow.to), strikes.observed)
            assertTrue(component.state.value.strikes.data.isNullOrEmpty())

            // The boundary strike only reaches the database through the T2
            // fetch, mirroring how a real provider fetch persists new rows.
            strikes.fetchedOnRefresh = listOf(boundary)
            clock.instant = refreshedAt
            component.refreshStrikes()
            runCurrent()
            val refreshedWindow = policy.currentWindow(refreshedAt)
            // The same T2 window drives the fetch and the observation.
            assertEquals(refreshedWindow.from to refreshedWindow.to, strikes.refreshes.last())
            assertEquals(
                listOf(initialWindow.from to initialWindow.to, refreshedWindow.from to refreshedWindow.to),
                strikes.observed,
            )
            assertEquals(listOf(boundary), component.state.value.strikes.data)

            // Time passing alone fetches and re-observes nothing: no polling.
            clock.instant = Instant.parse("2026-09-12T08:00:00Z")
            runCurrent()
            assertEquals(2, strikes.refreshes.size)
            assertEquals(2, strikes.observed.size)
        } finally { lifecycle.destroy() }
    }

    /**
     * Strike repository double that honors `(from, to)` bounds on
     * observation, persists fetched rows like a real fetch, and records
     * every refresh and observation window, unlike [FakeStrikeRepository]
     * which ignores ranges.
     */
    private class WindowedFakeStrikeRepository : StrikeRepository {
        val all = MutableStateFlow<List<Strike>>(emptyList())
        var fetchedOnRefresh: List<Strike> = emptyList()
        val refreshes = mutableListOf<Pair<Instant, Instant>>()
        val observed = mutableListOf<Pair<Instant, Instant>>()

        override fun observeStrikes(from: Instant, to: Instant, includeRevoked: Boolean): Flow<DataResult<List<Strike>>> {
            observed += from to to
            return all.map { strikes ->
                DataResult.Data(
                    strikes.filter { it.start < to && it.end > from },
                    DataFreshness.Unknown,
                )
            }
        }

        override fun observeStrikes(window: CurrentStrikeWindow, includeRevoked: Boolean): Flow<DataResult<List<Strike>>> =
            observeStrikes(window.from, window.to, includeRevoked)

        override suspend fun refresh(from: Instant, to: Instant, force: Boolean): DataResult<StrikeRefresh> {
            refreshes += from to to
            all.value = (all.value + fetchedOnRefresh).distinctBy { it.id }
            val overlapping = all.value.filter { it.start < to && it.end > from }
            return DataResult.Data(StrikeRefresh(overlapping), DataFreshness.Unknown)
        }

        override suspend fun refresh(window: CurrentStrikeWindow, force: Boolean): DataResult<StrikeRefresh> =
            refresh(window.from, window.to, force)
    }
}
