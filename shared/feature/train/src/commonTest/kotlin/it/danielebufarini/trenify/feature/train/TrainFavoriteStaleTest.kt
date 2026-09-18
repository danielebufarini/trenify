package it.danielebufarini.trenify.feature.train

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import it.danielebufarini.trenify.core.domain.FavoritesRepository
import it.danielebufarini.trenify.core.domain.ObserveTrainMonitor
import it.danielebufarini.trenify.core.domain.ObserveTrainRun
import it.danielebufarini.trenify.core.domain.StartTrainMonitoring
import it.danielebufarini.trenify.core.domain.StopTrainMonitoring
import it.danielebufarini.trenify.core.model.FavoriteRoute
import it.danielebufarini.trenify.core.model.FavoriteTrain
import it.danielebufarini.trenify.core.model.Station
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.testing.FakeMonitoringRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.testRunId
import it.danielebufarini.trenify.core.testing.testStation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T7.8 corrective pass: a recurring-train save that committed before a
 * completed delete-all must not visually reappear merely because its
 * controller continuation resumes late. Same post-commit parking shape as
 * the station stale test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainFavoriteStaleTest {
    @Test fun lateSaveContinuationCannotOverwriteCompletedDelete() = runTest {
        val lifecycle = LifecycleRegistry()
        val realtime = FakeRealtimeRepositories()
        val gated = PostCommitGateFavorites(realtime)
        val monitoring = FakeMonitoringRepository()
        val component = TrainDetailComponent(
            DefaultComponentContext(lifecycle),
            testRunId,
            ObserveTrainRun(realtime),
            MutableStateFlow(false),
            StandardTestDispatcher(testScheduler),
            observeMonitor = ObserveTrainMonitor(monitoring),
            startMonitoring = StartTrainMonitoring(monitoring),
            stopMonitoring = StopTrainMonitoring(monitoring),
            favoritesRepository = gated,
        )
        runCurrent()

        val release = CompletableDeferred<Unit>()
        gated.afterCommit = { release.await() }
        // The save commits, then parks before the controller resumes.
        component.toggleFavoriteTrain()
        runCurrent()
        // Non-vacuity guard: the save really committed before the delete.
        val expected = FavoriteTrain.create(
            TrainNumber("123"),
            originId = testStation.id,
            operator = null,
            originName = "Roma Termini",
            destinationName = "Milano Centrale",
        )
        assertEquals(listOf(expected), realtime.observeFavoriteTrains().first())

        // The completed delete-all empties the observation meanwhile.
        realtime.clearAllFavorites()
        runCurrent()
        assertEquals(false, component.favoriteTrainState?.value?.favorite)

        // Releasing the parked continuation must not write the stale id back.
        release.complete(Unit)
        runCurrent()
        runCurrent()
        assertEquals(false, component.favoriteTrainState?.value?.favorite)
        assertEquals(false, component.favoriteTrainState?.value?.failed)
        lifecycle.destroy()
    }

    private class PostCommitGateFavorites(
        private val delegate: FakeRealtimeRepositories,
    ) : FavoritesRepository by delegate {
        var afterCommit: (suspend () -> Unit)? = null

        override suspend fun setFavorite(station: Station, favorite: Boolean) {
            delegate.setFavorite(station, favorite)
            afterCommit?.invoke()
        }

        override suspend fun setFavorite(route: FavoriteRoute, favorite: Boolean) {
            delegate.setFavorite(route, favorite)
            afterCommit?.invoke()
        }

        override suspend fun setFavorite(train: FavoriteTrain, favorite: Boolean) {
            delegate.setFavorite(train, favorite)
            afterCommit?.invoke()
        }
    }
}
