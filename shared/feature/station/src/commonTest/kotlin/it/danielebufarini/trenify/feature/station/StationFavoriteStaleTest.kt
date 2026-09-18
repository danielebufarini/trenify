package it.danielebufarini.trenify.feature.station

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import it.danielebufarini.trenify.core.domain.FavoritesRepository
import it.danielebufarini.trenify.core.domain.SearchStations
import it.danielebufarini.trenify.core.model.FavoriteRoute
import it.danielebufarini.trenify.core.model.FavoriteTrain
import it.danielebufarini.trenify.core.model.Station
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.testStation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T7.8 corrective pass: a station save that committed before a completed
 * delete-all must not visually reappear merely because its controller
 * continuation resumes late. The save parks after its commit but before the
 * controller resumes; the deletion completes and its empty state is observed
 * meanwhile; releasing the parked continuation must leave the empty state
 * intact because no further emission will correct a late local union.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StationFavoriteStaleTest {
    @Test fun lateSaveContinuationCannotOverwriteCompletedDelete() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val gated = PostCommitGateFavorites(repositories)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val search = StationSearchComponent(
            DefaultComponentContext(lifecycle),
            SearchStations(repositories),
            gated,
            {},
            dispatcher,
            historyRepository = repositories,
        )
        runCurrent()

        val release = CompletableDeferred<Unit>()
        gated.afterCommit = { release.await() }
        // The save commits, then parks before the controller resumes.
        search.toggleFavorite(testStation)
        runCurrent()
        // Non-vacuity guard: the save really committed before the delete.
        assertEquals(listOf(testStation), repositories.observeFavoriteStations().first())

        // The completed delete-all empties the observation meanwhile. The
        // displayed ids still contain the station: that is the intended
        // pending feedback for the parked save, not stale state.
        repositories.clearAllFavorites()
        runCurrent()
        assertTrue(testStation.id in search.favoriteState.value.favoriteIds)

        // Releasing the parked continuation must not write the stale id back.
        release.complete(Unit)
        runCurrent()
        runCurrent()
        assertFalse(testStation.id in search.favoriteState.value.favoriteIds)
        assertTrue(repositories.observeFavoriteStations().first().isEmpty())
        assertTrue(search.favoriteState.value.failedIds.isEmpty())
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
