package it.danielebufarini.trenify.feature.favorites

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.journeyDestination
import it.danielebufarini.trenify.core.testing.testStation
import it.danielebufarini.trenify.core.model.FavoriteRoute
import it.danielebufarini.trenify.core.model.FavoriteTrain
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.Station
import it.danielebufarini.trenify.core.model.StationId
import it.danielebufarini.trenify.core.model.TrainLookupIntent
import it.danielebufarini.trenify.core.model.TrainNumber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesComponentTest {
    @Test fun observesOpensAndRemovesFavoriteStations() = runTest {
        val lifecycle = LifecycleRegistry()
        val repository = FakeRealtimeRepositories().apply { favorites.value = listOf(testStation) }
        var opened = false
        val component = FavoritesComponent(
            DefaultComponentContext(lifecycle),
            repository,
            { opened = it == testStation },
            StandardTestDispatcher(testScheduler),
        )
        runCurrent()

        assertEquals(listOf(testStation), component.state.value.stations)
        assertFalse(component.state.value.loading)
        component.open(testStation)
        assertTrue(opened)

        component.remove(testStation)
        runCurrent()
        assertTrue(component.state.value.stations.isEmpty())
        assertTrue(component.state.value.pendingStationIds.isEmpty())
        lifecycle.destroy()
    }

    @Test fun observesLaunchesAndRemovesFavoriteRoutesWithoutChangingStationFavorites() = runTest {
        val lifecycle = LifecycleRegistry()
        val route = FavoriteRoute.create(testStation, journeyDestination)
        val repository = FakeRealtimeRepositories().apply {
            favorites.value = listOf(testStation)
            favoriteRoutes.value = listOf(route)
        }
        var opened: it.danielebufarini.trenify.core.model.JourneySearchIntent? = null
        val component = FavoritesComponent(
            DefaultComponentContext(lifecycle),
            repository,
            {},
            StandardTestDispatcher(testScheduler),
            onRoute = { opened = it },
        )
        lifecycle.resume()
        runCurrent()

        assertEquals(listOf(route), component.state.value.routes)
        component.open(route)
        assertEquals(route.searchIntent, opened)
        component.remove(route)
        runCurrent()

        assertTrue(component.state.value.routes.isEmpty())
        assertEquals(listOf(testStation), component.state.value.stations)
        assertTrue(component.state.value.pendingRouteIds.isEmpty())
        lifecycle.destroy()
    }

    @Test fun observesLaunchesAndRemovesFavoriteTrainsWithStableLookupCriteria() = runTest {
        val lifecycle = LifecycleRegistry()
        val roma = Station(testStation.id, "Roma Termini")
        val napoli = Station(StationId("napoli-centrale"), "Napoli Centrale")
        val train = FavoriteTrain.create(
            TrainNumber("123"),
            originId = roma.id,
            operator = Operator("Trenitalia"),
            originName = roma.name,
        )
        val sameNumberOtherOrigin = FavoriteTrain.create(
            TrainNumber("123"),
            originId = napoli.id,
            operator = Operator("Trenitalia"),
            originName = napoli.name,
        )
        val repository = FakeRealtimeRepositories().apply {
            favorites.value = listOf(testStation)
            favoriteTrains.value = listOf(train, sameNumberOtherOrigin)
        }
        var launched: TrainLookupIntent? = null
        val component = FavoritesComponent(
            DefaultComponentContext(lifecycle),
            repository,
            {},
            StandardTestDispatcher(testScheduler),
            onRoute = {},
            onTrainLookup = { launched = it },
        )
        lifecycle.resume()
        runCurrent()

        assertEquals(listOf(train, sameNumberOtherOrigin), component.state.value.trains)
        assertEquals(listOf(testStation), component.state.value.stations)
        component.open(train)
        // Launch carries the stable discrimination, never a dated TrainRunId.
        assertEquals(train.lookupIntent, launched)
        assertEquals(
            TrainLookupIntent(
                number = TrainNumber("123"),
                originId = roma.id,
                originName = roma.name,
                operator = Operator("Trenitalia"),
            ),
            launched,
        )
        component.remove(train)
        runCurrent()

        assertEquals(listOf(sameNumberOtherOrigin), component.state.value.trains)
        assertEquals(listOf(testStation), component.state.value.stations)
        assertTrue(component.state.value.pendingTrainIds.isEmpty())
        lifecycle.destroy()
    }

    @Test fun exposesRetryableMutationFailureAndCancelsPendingWorkWithLifecycle() = runTest {
        val lifecycle = LifecycleRegistry()
        lifecycle.resume()
        val repository = FakeRealtimeRepositories().apply {
            favorites.value = listOf(testStation)
            favoriteFailure = IllegalStateException("write failed")
        }
        val component = FavoritesComponent(
            DefaultComponentContext(lifecycle),
            repository,
            {},
            StandardTestDispatcher(testScheduler),
        )
        runCurrent()

        component.remove(testStation)
        runCurrent()
        assertEquals(testStation, component.state.value.failedMutation?.station)

        repository.favoriteFailure = null
        component.retry()
        runCurrent()
        assertTrue(component.state.value.stations.isEmpty())
        assertEquals(null, component.state.value.failedMutation)

        repository.favorites.value = listOf(testStation)
        val gate = CompletableDeferred<Unit>()
        repository.beforeFavoriteMutation = { gate.await() }
        runCurrent()
        component.remove(testStation)
        runCurrent()
        assertTrue(component.state.value.pendingStationIds.isNotEmpty())
        lifecycle.destroy()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(testStation), repository.favorites.value)
    }
}
