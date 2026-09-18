package it.danielebufarini.trenify.feature.settings

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import it.danielebufarini.trenify.core.domain.ClearAllFavorites
import it.danielebufarini.trenify.core.domain.ClearSearchHistoryAndRecency
import it.danielebufarini.trenify.core.domain.FavoritesRepository
import it.danielebufarini.trenify.core.domain.HistoryRepository
import it.danielebufarini.trenify.core.domain.ObserveNotificationSettings
import it.danielebufarini.trenify.core.domain.ObserveStrikeNotifications
import it.danielebufarini.trenify.core.domain.SetDefaultMonitorThresholds
import it.danielebufarini.trenify.core.domain.SetNotificationsEnabled
import it.danielebufarini.trenify.core.domain.SetStrikeNotifications
import it.danielebufarini.trenify.core.model.FavoriteRoute
import it.danielebufarini.trenify.core.model.FavoriteTrain
import it.danielebufarini.trenify.core.model.JourneySearchMode
import it.danielebufarini.trenify.core.model.JourneySearchRequest
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.Station
import it.danielebufarini.trenify.core.model.StationId
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.testing.FakeNotificationPermission
import it.danielebufarini.trenify.core.testing.FakeNotificationSettingsRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.testing.testStation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * T7.8 Settings delete-all actions: confirmation, cancellation, pending
 * coalescing, success, failure and retry for both destructive actions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsDeletionTest {
    private val otherStation = Station(StationId("other-station"), "Milano Centrale")

    private fun kotlinx.coroutines.test.TestScope.component(
        history: HistoryRepository,
        favorites: FavoritesRepository = history as? FavoritesRepository ?: FakeRealtimeRepositories(),
    ) = DefaultSettingsComponent(
        DefaultComponentContext(LifecycleRegistry()),
        ObserveNotificationSettings(FakeNotificationSettingsRepository()),
        SetNotificationsEnabled(FakeNotificationSettingsRepository()),
        SetDefaultMonitorThresholds(FakeNotificationSettingsRepository()),
        ObserveStrikeNotifications(FakeStrikeRepository()),
        SetStrikeNotifications(FakeStrikeRepository()),
        notificationPermission = FakeNotificationPermission(),
        requestNotificationPermission = { true },
        dispatcher = StandardTestDispatcher(testScheduler),
        clearSearchHistoryAndRecency = ClearSearchHistoryAndRecency(history),
        clearAllFavorites = ClearAllFavorites(favorites),
    )

    private suspend fun populate(repositories: FakeRealtimeRepositories) {
        repositories.record(testStation)
        repositories.recordSearch(JourneySearchRequest(testStation, otherStation, Clock.System.now(), JourneySearchMode.DEPART_AFTER))
        repositories.recordTrainSearch(TrainNumber("123"), serviceDate = null, expected = null)
        repositories.setFavorite(testStation, true)
        repositories.setFavorite(FavoriteRoute.create(testStation, otherStation), true)
        repositories.setFavorite(
            FavoriteTrain.create(TrainNumber("123"), testStation.id, Operator("Trenitalia"), testStation.name),
            true,
        )
    }

    private suspend fun assertHistoryPresent(repositories: FakeRealtimeRepositories) {
        assertEquals(listOf(testStation), repositories.observeRecentStations().first())
        assertEquals(2, repositories.observeSearchHistory().first().size)
    }

    private suspend fun assertFavoritesPresent(repositories: FakeRealtimeRepositories) {
        assertEquals(listOf(testStation), repositories.observeFavoriteStations().first())
        assertEquals(1, repositories.observeFavoriteRoutes().first().size)
        assertEquals(1, repositories.observeFavoriteTrains().first().size)
    }

    @Test fun historyConfirmDeletesAndSucceeds() = runTest {
        val repositories = FakeRealtimeRepositories()
        val component = component(repositories)
        populate(repositories)
        runCurrent()

        component.requestHistoryDeletion()
        assertEquals(PersonalDataDeletionStatus.Confirming, component.state.value.historyDeletion)
        component.confirmHistoryDeletion()
        runCurrent()

        assertEquals(PersonalDataDeletionStatus.Success, component.state.value.historyDeletion)
        assertEquals(1, repositories.clearHistoryCalls)
        assertTrue(repositories.observeRecentStations().first().isEmpty())
        assertTrue(repositories.observeSearchHistory().first().isEmpty())
        // The independent favorites action is untouched and its data survives.
        assertEquals(PersonalDataDeletionStatus.Idle, component.state.value.favoritesDeletion)
        assertFavoritesPresent(repositories)
    }

    @Test fun historyCancelIsANoOp() = runTest {
        val repositories = FakeRealtimeRepositories()
        val component = component(repositories)
        populate(repositories)
        runCurrent()

        component.requestHistoryDeletion()
        component.cancelHistoryDeletion()
        runCurrent()

        assertEquals(PersonalDataDeletionStatus.Idle, component.state.value.historyDeletion)
        assertEquals(0, repositories.clearHistoryCalls)
        assertHistoryPresent(repositories)
    }

    @Test fun historyConfirmWithoutRequestIsANoOp() = runTest {
        val repositories = FakeRealtimeRepositories()
        val component = component(repositories)
        populate(repositories)
        runCurrent()

        component.confirmHistoryDeletion()
        runCurrent()

        assertEquals(PersonalDataDeletionStatus.Idle, component.state.value.historyDeletion)
        assertEquals(0, repositories.clearHistoryCalls)
        assertHistoryPresent(repositories)
    }

    @Test fun historyRepeatedConfirmsWhilePendingRunOnce() = runTest {
        val repositories = FakeRealtimeRepositories()
        val gated = GatedClearHistory(repositories)
        val component = component(gated, repositories)
        populate(repositories)
        runCurrent()

        gated.gate = CompletableDeferred()
        component.requestHistoryDeletion()
        component.confirmHistoryDeletion()
        runCurrent()
        assertEquals(PersonalDataDeletionStatus.Pending, component.state.value.historyDeletion)
        // Repeated taps and re-requests while pending never start a second run.
        component.confirmHistoryDeletion()
        component.requestHistoryDeletion()
        component.confirmHistoryDeletion()
        runCurrent()
        assertEquals(PersonalDataDeletionStatus.Pending, component.state.value.historyDeletion)

        gated.gate?.complete(Unit)
        runCurrent()
        assertEquals(PersonalDataDeletionStatus.Success, component.state.value.historyDeletion)
        assertEquals(1, repositories.clearHistoryCalls)
        assertTrue(repositories.observeSearchHistory().first().isEmpty())
    }

    @Test fun historyFailureDoesNotClaimSuccessAndRetries() = runTest {
        val repositories = FakeRealtimeRepositories()
        val component = component(repositories)
        populate(repositories)
        runCurrent()

        repositories.clearHistoryFailure = IllegalStateException("disk")
        component.requestHistoryDeletion()
        component.confirmHistoryDeletion()
        runCurrent()

        assertEquals(PersonalDataDeletionStatus.Failed, component.state.value.historyDeletion)
        assertHistoryPresent(repositories)

        // Confirming again retries the same action without a new request.
        repositories.clearHistoryFailure = null
        component.confirmHistoryDeletion()
        runCurrent()
        assertEquals(PersonalDataDeletionStatus.Success, component.state.value.historyDeletion)
        assertEquals(2, repositories.clearHistoryCalls)
        assertTrue(repositories.observeSearchHistory().first().isEmpty())
    }

    @Test fun historyFailureRetriesThroughRetry() = runTest {
        val repositories = FakeRealtimeRepositories()
        val component = component(repositories)
        populate(repositories)
        runCurrent()

        repositories.clearHistoryFailure = IllegalStateException("disk")
        component.requestHistoryDeletion()
        component.confirmHistoryDeletion()
        runCurrent()
        assertEquals(PersonalDataDeletionStatus.Failed, component.state.value.historyDeletion)

        repositories.clearHistoryFailure = null
        component.retry()
        runCurrent()
        assertEquals(PersonalDataDeletionStatus.Success, component.state.value.historyDeletion)
    }

    @Test fun favoritesConfirmDeletesAndSucceeds() = runTest {
        val repositories = FakeRealtimeRepositories()
        val component = component(repositories)
        populate(repositories)
        runCurrent()

        component.requestFavoritesDeletion()
        assertEquals(PersonalDataDeletionStatus.Confirming, component.state.value.favoritesDeletion)
        component.confirmFavoritesDeletion()
        runCurrent()

        assertEquals(PersonalDataDeletionStatus.Success, component.state.value.favoritesDeletion)
        assertEquals(1, repositories.clearFavoritesCalls)
        assertTrue(repositories.observeFavoriteStations().first().isEmpty())
        assertTrue(repositories.observeFavoriteRoutes().first().isEmpty())
        assertTrue(repositories.observeFavoriteTrains().first().isEmpty())
        // The independent history action is untouched and its data survives.
        assertEquals(PersonalDataDeletionStatus.Idle, component.state.value.historyDeletion)
        assertHistoryPresent(repositories)
    }

    @Test fun favoritesCancelIsANoOp() = runTest {
        val repositories = FakeRealtimeRepositories()
        val component = component(repositories)
        populate(repositories)
        runCurrent()

        component.requestFavoritesDeletion()
        component.cancelFavoritesDeletion()
        runCurrent()

        assertEquals(PersonalDataDeletionStatus.Idle, component.state.value.favoritesDeletion)
        assertEquals(0, repositories.clearFavoritesCalls)
        assertFavoritesPresent(repositories)
    }

    @Test fun favoritesRepeatedConfirmsWhilePendingRunOnce() = runTest {
        val repositories = FakeRealtimeRepositories()
        val gated = GatedClearFavorites(repositories)
        val component = component(repositories, gated)
        populate(repositories)
        runCurrent()

        gated.gate = CompletableDeferred()
        component.requestFavoritesDeletion()
        component.confirmFavoritesDeletion()
        runCurrent()
        assertEquals(PersonalDataDeletionStatus.Pending, component.state.value.favoritesDeletion)
        component.confirmFavoritesDeletion()
        component.requestFavoritesDeletion()
        component.confirmFavoritesDeletion()
        runCurrent()
        assertEquals(PersonalDataDeletionStatus.Pending, component.state.value.favoritesDeletion)

        gated.gate?.complete(Unit)
        runCurrent()
        assertEquals(PersonalDataDeletionStatus.Success, component.state.value.favoritesDeletion)
        assertEquals(1, repositories.clearFavoritesCalls)
        assertTrue(repositories.observeFavoriteStations().first().isEmpty())
    }

    @Test fun favoritesFailureDoesNotClaimSuccessAndRetries() = runTest {
        val repositories = FakeRealtimeRepositories()
        val component = component(repositories)
        populate(repositories)
        runCurrent()

        repositories.clearFavoritesFailure = IllegalStateException("disk")
        component.requestFavoritesDeletion()
        component.confirmFavoritesDeletion()
        runCurrent()

        assertEquals(PersonalDataDeletionStatus.Failed, component.state.value.favoritesDeletion)
        assertFavoritesPresent(repositories)

        repositories.clearFavoritesFailure = null
        component.retry()
        runCurrent()
        assertEquals(PersonalDataDeletionStatus.Success, component.state.value.favoritesDeletion)
        assertEquals(2, repositories.clearFavoritesCalls)
        assertTrue(repositories.observeFavoriteTrains().first().isEmpty())
    }

    @Test fun oneDeletionPendingDoesNotBlockTheOther() = runTest {
        val repositories = FakeRealtimeRepositories()
        val gated = GatedClearHistory(repositories)
        val component = component(gated, repositories)
        populate(repositories)
        runCurrent()

        // Park the history deletion inside its persistence call.
        gated.gate = CompletableDeferred()
        component.requestHistoryDeletion()
        component.confirmHistoryDeletion()
        runCurrent()
        assertEquals(PersonalDataDeletionStatus.Pending, component.state.value.historyDeletion)

        // The independent favorites action still runs to success meanwhile.
        component.requestFavoritesDeletion()
        component.confirmFavoritesDeletion()
        runCurrent()
        assertEquals(PersonalDataDeletionStatus.Success, component.state.value.favoritesDeletion)
        assertTrue(repositories.observeFavoriteStations().first().isEmpty())

        gated.gate?.complete(Unit)
        runCurrent()
        assertEquals(PersonalDataDeletionStatus.Success, component.state.value.historyDeletion)
        assertTrue(repositories.observeSearchHistory().first().isEmpty())
    }

    @Test fun deletionSuccessSurvivesComponentRestart() = runTest {
        val repositories = FakeRealtimeRepositories()
        populate(repositories)
        runCurrent()

        val first = component(repositories)
        runCurrent()
        first.requestHistoryDeletion()
        first.confirmHistoryDeletion()
        runCurrent()
        assertEquals(PersonalDataDeletionStatus.Success, first.state.value.historyDeletion)

        // Persistence, not component state, owns the deletion: a restarted
        // component starts Idle but observes the same empty repositories.
        val restarted = component(repositories)
        runCurrent()
        assertEquals(PersonalDataDeletionStatus.Idle, restarted.state.value.historyDeletion)
        assertTrue(repositories.observeSearchHistory().first().isEmpty())
    }

    private class GatedClearHistory(
        private val delegate: FakeRealtimeRepositories,
    ) : HistoryRepository by delegate {
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun clearSearchHistoryAndRecency() {
            gate?.await()
            delegate.clearSearchHistoryAndRecency()
        }
    }

    private class GatedClearFavorites(
        private val delegate: FakeRealtimeRepositories,
    ) : FavoritesRepository by delegate {
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun clearAllFavorites() {
            gate?.await()
            delegate.clearAllFavorites()
        }
    }
}
