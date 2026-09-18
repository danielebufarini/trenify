package it.danielebufarini.trenify.feature.station

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.testing.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/**
 * Typed FS §23 failure presentation for station search and board (T7.14-A):
 * repositories' [DomainFailure] values must reach component state
 * undifferentiated-boolean-free, stale content must survive refresh
 * failures, and retry must recover.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StationTypedFailureTest {
    // Corrective T8.9-B1: the station favorites observer degrades like
    // every sibling (Home, route/train controllers, Saved) — an observation
    // failure never kills the collector, and retained ids stay usable.
    @Test fun favoritesObservationFailureRetainsIdsWithoutKillingCollector() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        repositories.favorites.value = listOf(testStation)
        val component = StationSearchComponent(
            DefaultComponentContext(lifecycle), SearchStations(repositories),
            repositories, {}, StandardTestDispatcher(testScheduler),
        )
        try {
            lifecycle.resume()
            runCurrent()
            assertEquals(setOf(testStation.id), component.favoriteState.value.favoriteIds)
            repositories.failFavoritesObservation(IllegalStateException("offline"))
            runCurrent()
            // Collector survives (no uncaught failure) with retained ids.
            // (Post-outage toggles reconcile through the live observation
            // like every sibling controller; the dead flow owns truth until
            // the owning component re-observes.)
            assertEquals(setOf(testStation.id), component.favoriteState.value.favoriteIds)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun searchOfflineFailureIsTypedAndRetryRecovers() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val component = StationSearchComponent(
            DefaultComponentContext(lifecycle), SearchStations(repositories),
            repositories, {}, StandardTestDispatcher(testScheduler),
        )
        try {
            repositories.stationResult = DataResult.Failure(DomainFailure.OFFLINE)
            component.query("roma")
            advanceTimeBy(276)
            runCurrent()
            assertEquals(DomainFailure.OFFLINE, component.state.value.results.failure)
            assertTrue(component.state.value.results.failed)
            assertNull(component.state.value.results.data)
            repositories.stationResult = DataResult.Data(listOf(testStation), DataFreshness.Unknown)
            component.retry()
            runCurrent()
            assertNull(component.state.value.results.failure)
            assertEquals(listOf(testStation), component.state.value.results.data)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun searchWarningKeepsSuggestionsWithTypedFailure() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val component = StationSearchComponent(
            DefaultComponentContext(lifecycle), SearchStations(repositories),
            repositories, {}, StandardTestDispatcher(testScheduler),
        )
        try {
            repositories.stationResult = DataResult.Data(
                listOf(testStation), DataFreshness.Stale(MutableClock().now(), 90.seconds, null),
                DomainFailure.TEMPORARY,
            )
            component.query("roma")
            advanceTimeBy(276)
            runCurrent()
            assertEquals(DomainFailure.TEMPORARY, component.state.value.results.failure)
            assertEquals(listOf(testStation), component.state.value.results.data)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun boardStaleContentPlusOfflineKeepsDataWithTypedWarning() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val board = StationBoard(testStation, BoardKind.DEPARTURES, listOf(testSummary))
        repositories.boardState.value = DataResult.Data(
            board, DataFreshness.Stale(MutableClock().now(), 90.seconds, null), DomainFailure.OFFLINE)
        val component = StationBoardComponent(
            DefaultComponentContext(lifecycle), testStation.id,
            ObserveStation(repositories), LoadStationBoard(repositories), repositories, MutableStateFlow(false),
            {}, StandardTestDispatcher(testScheduler),
        )
        try {
            lifecycle.resume()
            runCurrent()
            assertEquals(board, component.state.value.data)
            assertEquals(DomainFailure.OFFLINE, component.state.value.failure)
            assertTrue(component.state.value.stale)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun boardNoCacheOfflineAndTemporaryAreTyped() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        repositories.boardState.value = DataResult.Failure(DomainFailure.OFFLINE)
        val component = StationBoardComponent(
            DefaultComponentContext(lifecycle), testStation.id,
            ObserveStation(repositories), LoadStationBoard(repositories), repositories, MutableStateFlow(false),
            {}, StandardTestDispatcher(testScheduler),
        )
        try {
            lifecycle.resume()
            runCurrent()
            assertNull(component.state.value.data)
            assertEquals(DomainFailure.OFFLINE, component.state.value.failure)
            repositories.boardState.value = DataResult.Failure(DomainFailure.TEMPORARY)
            runCurrent()
            assertEquals(DomainFailure.TEMPORARY, component.state.value.failure)
            repositories.boardState.value = DataResult.Data(
                StationBoard(testStation, BoardKind.DEPARTURES, listOf(testSummary)),
                DataFreshness.Fresh(MutableClock().now(), null),
            )
            runCurrent()
            assertNull(component.state.value.failure)
            assertEquals(1, component.state.value.data?.trains?.size)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun boardNoCacheNotFoundRendersEmptyInsteadOfFailure() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        repositories.boardState.value = DataResult.Failure(DomainFailure.NOT_FOUND)
        val component = StationBoardComponent(
            DefaultComponentContext(lifecycle), testStation.id,
            ObserveStation(repositories), LoadStationBoard(repositories), repositories, MutableStateFlow(false),
            {}, StandardTestDispatcher(testScheduler),
        )
        try {
            lifecycle.resume()
            runCurrent()
            // The never-fetched sentinel is not a reportable failure: the
            // board renders its empty state while refresh resolves.
            assertNull(component.state.value.data)
            assertNull(component.state.value.failure)
        } finally {
            lifecycle.destroy()
        }
    }
}
