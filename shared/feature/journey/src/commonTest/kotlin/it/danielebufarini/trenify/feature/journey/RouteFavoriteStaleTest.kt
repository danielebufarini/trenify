package it.danielebufarini.trenify.feature.journey

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.domain.FavoritesRepository
import it.danielebufarini.trenify.core.model.FavoriteRoute
import it.danielebufarini.trenify.core.model.FavoriteTrain
import it.danielebufarini.trenify.core.model.Station
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.journeyDestination
import it.danielebufarini.trenify.core.testing.testJourney
import it.danielebufarini.trenify.core.testing.testStation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T7.8 corrective pass: a route save that committed before a completed
 * delete-all must not visually reappear merely because its controller
 * continuation resumes late. Same post-commit parking shape as the station
 * stale test: commit, park, delete completes and is observed, release, and
 * the empty state must survive because nothing will correct a late union.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteFavoriteStaleTest {
    @Test fun lateSaveContinuationCannotOverwriteCompletedDelete() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val gated = PostCommitGateFavorites(repositories)
        val component = JourneyDetailComponent(
            DefaultComponentContext(lifecycle),
            testJourney,
            null,
            {},
            StandardTestDispatcher(testScheduler),
            favoritesRepository = gated,
        )
        lifecycle.resume()
        runCurrent()

        val release = CompletableDeferred<Unit>()
        gated.afterCommit = { release.await() }
        // The save commits, then parks before the controller resumes.
        component.toggleFavoriteRoute()
        runCurrent()
        // Non-vacuity guard: the save really committed before the delete.
        val route = FavoriteRoute.create(testStation, journeyDestination)
        assertEquals(listOf(route), repositories.observeFavoriteRoutes().first())

        // The completed delete-all empties the observation meanwhile.
        repositories.clearAllFavorites()
        runCurrent()
        assertEquals(false, component.favoriteRouteState?.value?.favorite)

        // Releasing the parked continuation must not write the stale id back.
        release.complete(Unit)
        runCurrent()
        runCurrent()
        assertEquals(false, component.favoriteRouteState?.value?.favorite)
        assertEquals(false, component.favoriteRouteState?.value?.failed)
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
