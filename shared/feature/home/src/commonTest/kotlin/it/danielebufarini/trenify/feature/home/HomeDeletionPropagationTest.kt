package it.danielebufarini.trenify.feature.home

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.LoadStrikes
import it.danielebufarini.trenify.core.domain.MonitorThresholds
import it.danielebufarini.trenify.core.domain.ObserveActiveMonitors
import it.danielebufarini.trenify.core.domain.StrikeRefresh
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.FavoriteRoute
import it.danielebufarini.trenify.core.testing.FakeMonitoringRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.journeyDestination
import it.danielebufarini.trenify.core.testing.journeyRequest
import it.danielebufarini.trenify.core.testing.testRunId
import it.danielebufarini.trenify.core.testing.testStation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T7.8: confirmed Settings deletions empty the Home aggregates reactively
 * without reopening the screen, while monitors and unrelated state survive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeDeletionPropagationTest {
    @Test fun settingsDeletionsEmptyAggregatesButKeepMonitors() = runTest {
        val clock = MutableClock()
        val repositories = FakeRealtimeRepositories()
        val monitoring = FakeMonitoringRepository(clock)
        val strikes = FakeStrikeRepository()
        strikes.state.value = DataResult.Data(emptyList(), DataFreshness.Unknown)
        strikes.refreshResult = DataResult.Data(StrikeRefresh(emptyList()), DataFreshness.Unknown)
        repositories.recordSearch(journeyRequest)
        repositories.setFavorite(testStation, true)
        repositories.setFavorite(FavoriteRoute.create(testStation, journeyDestination), true)
        monitoring.createMonitor(testRunId, MonitorThresholds(), expiresAt = null)
        val lifecycle = LifecycleRegistry()
        val component = HomeComponent(
            DefaultComponentContext(lifecycle),
            repositories,
            repositories,
            ObserveActiveMonitors(monitoring),
            LoadStrikes(strikes),
            StandardTestDispatcher(testScheduler),
            clock = clock,
        )
        lifecycle.resume()
        try {
            runCurrent()
            assertEquals(1, component.state.value.recentSearches.size)
            assertEquals(listOf(testStation), component.state.value.favoriteStations)
            assertEquals(1, component.state.value.favoriteRoutes.size)
            assertEquals(1, component.state.value.monitors.size)

            // Both confirmed Settings actions propagate to Home at once.
            repositories.clearSearchHistoryAndRecency()
            repositories.clearAllFavorites()
            runCurrent()

            assertTrue(component.state.value.recentSearches.isEmpty())
            assertTrue(component.state.value.favoriteStations.isEmpty())
            assertTrue(component.state.value.favoriteRoutes.isEmpty())
            assertTrue(component.state.value.favoriteTrains.isEmpty())
            // Monitors are untouched by either deletion.
            assertEquals(1, component.state.value.monitors.size)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun newActionsAfterDeletionsReappearOnHome() = runTest {
        val clock = MutableClock()
        val repositories = FakeRealtimeRepositories()
        val strikes = FakeStrikeRepository()
        strikes.state.value = DataResult.Data(emptyList(), DataFreshness.Unknown)
        strikes.refreshResult = DataResult.Data(StrikeRefresh(emptyList()), DataFreshness.Unknown)
        val lifecycle = LifecycleRegistry()
        val component = HomeComponent(
            DefaultComponentContext(lifecycle),
            repositories,
            repositories,
            ObserveActiveMonitors(FakeMonitoringRepository(clock)),
            LoadStrikes(strikes),
            StandardTestDispatcher(testScheduler),
            clock = clock,
        )
        lifecycle.resume()
        try {
            runCurrent()
            repositories.clearSearchHistoryAndRecency()
            repositories.clearAllFavorites()
            runCurrent()
            assertTrue(component.state.value.recentSearches.isEmpty())

            // New explicit user actions after the deletions show up again.
            repositories.recordSearch(journeyRequest)
            repositories.setFavorite(testStation, true)
            runCurrent()
            assertEquals(1, component.state.value.recentSearches.size)
            assertEquals(listOf(testStation), component.state.value.favoriteStations)
        } finally {
            lifecycle.destroy()
        }
    }
}
