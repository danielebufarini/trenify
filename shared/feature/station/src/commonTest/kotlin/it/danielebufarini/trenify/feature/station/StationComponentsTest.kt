package it.danielebufarini.trenify.feature.station

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.testing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class StationComponentsTest {
    @Test fun searchDebouncesCancelsOldRequestsAndSupportsRetryAndSelection() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        var selected: Station? = null
        val component = StationSearchComponent(DefaultComponentContext(lifecycle), SearchStations(repositories),
            repositories, { selected = it }, StandardTestDispatcher(testScheduler))
        component.query("ro")
        advanceTimeBy(200)
        component.query("roma")
        advanceTimeBy(276)
        runCurrent()
        assertEquals(listOf("roma"), repositories.searches)
        assertEquals(listOf(testStation), component.state.value.results.data)
        repositories.stationResult = DataResult.Failure(DomainFailure.OFFLINE)
        component.query("milano")
        advanceTimeBy(276)
        runCurrent()
        assertTrue(component.state.value.results.failed)
        repositories.stationResult = DataResult.Data(listOf(testStation), DataFreshness.Unknown)
        component.retry()
        runCurrent()
        assertFalse(component.state.value.results.failed)
        component.select(testStation)
        assertEquals(testStation, selected)
        val gate = CompletableDeferred<Unit>()
        repositories.onSearch = { gate.await() }
        component.query("old")
        advanceTimeBy(276)
        runCurrent()
        component.query("")
        gate.complete(Unit)
        runCurrent()
        assertEquals("", component.state.value.query)
        assertNull(component.state.value.results.data)
        lifecycle.destroy()
    }

    @Test fun boardSwitchesDirectionAndOwnsPollingThroughLifecycle() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        var opened: TrainRunId? = null
        val component = StationBoardComponent(DefaultComponentContext(lifecycle), testStation.id,
            ObserveStation(repositories), LoadStationBoard(repositories), repositories, MutableStateFlow(true),
            { opened = it }, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertEquals(0, repositories.boardRefreshes)
        lifecycle.resume()
        runCurrent()
        assertEquals(BoardKind.DEPARTURES, component.state.value.data?.kind)
        component.select(BoardKind.ARRIVALS)
        runCurrent()
        assertEquals(BoardKind.ARRIVALS, component.state.value.data?.kind)
        assertEquals(2, repositories.boardRefreshes)
        assertEquals(0, repositories.trainRefreshes)
        component.openTrain(testRunId)
        assertEquals(testRunId, opened)
        lifecycle.stop()
        advanceTimeBy(100_000)
        runCurrent()
        assertEquals(2, repositories.boardRefreshes)
        lifecycle.destroy()
    }

    @Test fun recencySuggestsOpenedStationsAndSupportsRemoveOneAndClear() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val component = StationSearchComponent(DefaultComponentContext(lifecycle), SearchStations(repositories),
            repositories, {}, StandardTestDispatcher(testScheduler), historyRepository = repositories)
        lifecycle.resume()
        try {
            runCurrent()
            assertTrue(component.state.value.recent.isEmpty())
            assertFalse(component.state.value.recencyFailed)
            // Opened stations appear as recency suggestions, newest first,
            // without becoming search history.
            repositories.record(testStation)
            runCurrent()
            assertEquals(listOf(testStation), component.state.value.recent)
            assertTrue(repositories.observeSearchHistory().first().isEmpty())
            // Remove-one drops the suggestion; clear-all drops the rest.
            component.removeRecent(testStation)
            runCurrent()
            assertTrue(component.state.value.recent.isEmpty())
            repositories.record(testStation)
            runCurrent()
            assertEquals(listOf(testStation), component.state.value.recent)
            component.clearRecent()
            runCurrent()
            assertTrue(component.state.value.recent.isEmpty())
            // Recency operations never delete the searchable station itself.
            assertEquals(listOf(testStation), repositories.searchStations("roma").let {
                assertIs<DataResult.Data<List<Station>>>(it).value
            })
        } finally { lifecycle.destroy() }
    }

    @Test fun recencyFailureRetainsCachedRowsShowsFailureAndRecoversWithoutInventingContent() = runTest {
        val observingLifecycle = LifecycleRegistry()
        val observingRepositories = FakeRealtimeRepositories()
        observingRepositories.record(testStation)
        var observationSelected: Station? = null
        val observing = StationSearchComponent(DefaultComponentContext(observingLifecycle), SearchStations(observingRepositories),
            observingRepositories, { observationSelected = it }, StandardTestDispatcher(testScheduler),
            historyRepository = observingRepositories)
        observingLifecycle.resume()
        try {
            runCurrent()
            observingRepositories.failRecentObservation(IllegalStateException("recency observation unavailable"))
            runCurrent()
            assertTrue(observing.state.value.recencyFailed)
            assertEquals(listOf(testStation), observing.state.value.recent)
            observing.select(observing.state.value.recent.single())
            assertEquals(testStation, observationSelected)
        } finally { observingLifecycle.destroy() }

        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        repositories.record(testStation)
        var selected: Station? = null
        val component = StationSearchComponent(DefaultComponentContext(lifecycle), SearchStations(repositories),
            repositories, { selected = it }, StandardTestDispatcher(testScheduler), historyRepository = repositories)
        lifecycle.resume()
        try {
            runCurrent()
            assertEquals(listOf(testStation), component.state.value.recent)
            repositories.recentHistoryFailure = IllegalStateException("recency unavailable")
            component.clearRecent()
            runCurrent()
            assertTrue(component.state.value.recencyFailed)
            assertEquals(listOf(testStation), component.state.value.recent)
            component.select(component.state.value.recent.single())
            assertEquals(testStation, selected)

            repositories.recentHistoryFailure = null
            component.clearRecent()
            runCurrent()
            assertFalse(component.state.value.recencyFailed)
            assertTrue(component.state.value.recent.isEmpty())
        } finally { lifecycle.destroy() }

        val emptyLifecycle = LifecycleRegistry()
        val emptyRepositories = FakeRealtimeRepositories()
        val empty = StationSearchComponent(DefaultComponentContext(emptyLifecycle), SearchStations(emptyRepositories),
            emptyRepositories, {}, StandardTestDispatcher(testScheduler), historyRepository = emptyRepositories)
        emptyLifecycle.resume()
        try {
            runCurrent()
            emptyRepositories.failRecentObservation(IllegalStateException("recency observation unavailable"))
            runCurrent()
            assertTrue(empty.state.value.recencyFailed)
            assertTrue(empty.state.value.recent.isEmpty())
        } finally { emptyLifecycle.destroy() }
    }

    @Test fun searchAndBoardShareReactiveFavoriteStateAndRecoverFromFailure() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val search = StationSearchComponent(
            DefaultComponentContext(lifecycle),
            SearchStations(repositories),
            repositories,
            {},
            dispatcher,
        )
        val board = StationBoardComponent(
            DefaultComponentContext(lifecycle),
            testStation.id,
            ObserveStation(repositories),
            LoadStationBoard(repositories),
            repositories,
            MutableStateFlow(false),
            {},
            dispatcher,
        )
        runCurrent()

        search.toggleFavorite(testStation)
        runCurrent()
        assertTrue(testStation.id in search.favoriteState.value.favoriteIds)
        assertTrue(testStation.id in board.favoriteState.value.favoriteIds)

        repositories.favoriteFailure = IllegalStateException("write failed")
        board.toggleFavorite()
        runCurrent()
        assertTrue(testStation.id in board.favoriteState.value.favoriteIds)
        assertTrue(testStation.id in board.favoriteState.value.failedIds)

        repositories.favoriteFailure = null
        board.toggleFavorite()
        runCurrent()
        assertFalse(testStation.id in search.favoriteState.value.favoriteIds)
        assertFalse(testStation.id in board.favoriteState.value.favoriteIds)
        lifecycle.destroy()
    }
}
