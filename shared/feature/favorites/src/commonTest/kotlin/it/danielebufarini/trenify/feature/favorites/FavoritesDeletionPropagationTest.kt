package it.danielebufarini.trenify.feature.favorites

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import it.danielebufarini.trenify.core.model.FavoriteRoute
import it.danielebufarini.trenify.core.model.FavoriteTrain
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.journeyDestination
import it.danielebufarini.trenify.core.testing.testStation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T7.8: the confirmed Settings favorites deletion propagates to Favorites
 * automatically through the repository flows; the component never writes
 * deleted entries back and new saves work normally afterwards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesDeletionPropagationTest {
    private val route = FavoriteRoute.create(testStation, journeyDestination)
    private val train = FavoriteTrain.create(
        TrainNumber("123"),
        testStation.id,
        Operator("Trenitalia"),
        testStation.name,
    )

    @Test fun settingsFavoritesDeletionEmptiesAllThreeKindsReactively() = runTest {
        val lifecycle = LifecycleRegistry()
        val repository = FakeRealtimeRepositories()
        repository.setFavorite(testStation, true)
        repository.setFavorite(route, true)
        repository.setFavorite(train, true)
        val component = FavoritesComponent(
            DefaultComponentContext(lifecycle),
            repository,
            {},
            StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        assertEquals(listOf(testStation), component.state.value.stations)
        assertEquals(listOf(route), component.state.value.routes)
        assertEquals(listOf(train), component.state.value.trains)

        // The confirmed Settings action clears all three kinds at once.
        repository.clearAllFavorites()
        runCurrent()

        assertTrue(component.state.value.stations.isEmpty())
        assertTrue(component.state.value.routes.isEmpty())
        assertTrue(component.state.value.trains.isEmpty())
        assertNull(component.state.value.failedMutation)
        assertNull(component.state.value.failedRouteMutation)
        assertNull(component.state.value.failedTrainMutation)

        // No write-back: retrying with no failure only re-observes and the
        // cleared flows stay empty.
        component.retry()
        runCurrent()
        assertTrue(component.state.value.stations.isEmpty())
        assertTrue(repository.observeFavoriteTrains().first().isEmpty())
        lifecycle.destroy()
    }

    @Test fun newFavoriteSaveAfterDeletionAppearsNormally() = runTest {
        val lifecycle = LifecycleRegistry()
        val repository = FakeRealtimeRepositories()
        val component = FavoritesComponent(
            DefaultComponentContext(lifecycle),
            repository,
            {},
            StandardTestDispatcher(testScheduler),
        )
        runCurrent()

        repository.clearAllFavorites()
        runCurrent()
        assertTrue(component.state.value.stations.isEmpty())

        // A new explicit save after the deletion is a new user action.
        repository.setFavorite(testStation, true)
        repository.setFavorite(train, true)
        runCurrent()
        assertEquals(listOf(testStation), component.state.value.stations)
        assertEquals(listOf(train), component.state.value.trains)
        assertTrue(component.state.value.routes.isEmpty())
        lifecycle.destroy()
    }
}
