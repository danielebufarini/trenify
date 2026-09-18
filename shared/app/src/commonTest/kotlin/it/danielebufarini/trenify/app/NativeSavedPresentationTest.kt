package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.model.FavoriteRoute
import it.danielebufarini.trenify.core.model.FavoriteTrain
import it.danielebufarini.trenify.core.model.JourneySearchHistoryEntry
import it.danielebufarini.trenify.core.model.JourneySearchMode
import it.danielebufarini.trenify.core.model.JourneySearchRequest
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.Station
import it.danielebufarini.trenify.core.model.StationId
import it.danielebufarini.trenify.core.model.TrainLookupIntent
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.model.TrainSearchHistoryEntry
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.journeyDestination
import it.danielebufarini.trenify.core.testing.testStation
import it.danielebufarini.trenify.feature.favorites.FavoritesComponent
import it.danielebufarini.trenify.feature.journey.JourneyTabComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T8.9 native Saved projection: semantic snapshots over [FavoritesComponent].
 * Favorite identity, history ordering/recording, repeat routing and
 * corrupt/bad-date handling stay shared; this suite proves the projection
 * never invents identity, never reorders, and forwards actions intact.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NativeSavedPresentationTest {
    private val at = kotlin.time.Instant.parse("2026-09-14T09:00:00Z")

    private fun kotlinx.coroutines.test.TestScope.component(
        repository: FakeRealtimeRepositories,
        onJourneyRepeat: (JourneySearchRequest) -> Unit = {},
        onTrainRepeat: (TrainSearchHistoryEntry) -> Unit = {},
        onTrainLookup: (TrainLookupIntent) -> Unit = {},
    ): Pair<FavoritesComponent, LifecycleRegistry> {
        val life = LifecycleRegistry()
        val component = FavoritesComponent(
            DefaultComponentContext(life),
            repository,
            {},
            StandardTestDispatcher(testScheduler),
            onRoute = {},
            onTrainLookup = onTrainLookup,
            historyRepository = repository,
            onJourneyRepeat = onJourneyRepeat,
            onTrainRepeat = onTrainRepeat,
        )
        life.resume()
        return component to life
    }

    private fun kotlinx.coroutines.test.TestScope.facade(
        repository: FakeRealtimeRepositories,
        onJourneyRepeat: (JourneySearchRequest) -> Unit = {},
        onTrainRepeat: (TrainSearchHistoryEntry) -> Unit = {},
        onTrainLookup: (TrainLookupIntent) -> Unit = {},
    ): Triple<NativeSavedPresentation, FavoritesComponent, LifecycleRegistry> {
        val (component, life) = component(repository, onJourneyRepeat, onTrainRepeat, onTrainLookup)
        return Triple(NativeSavedPresentation(component, NativeProjectionOwner()), component, life)
    }

    @Test fun emptyProjectsEmptySections() = runTest {
        val repository = FakeRealtimeRepositories()
        val (facade, _, life) = facade(repository)
        try {
            runCurrent()
            val state = facade.state.value
            assertFalse(state.loading)
            assertFalse(state.historyLoading)
            assertTrue(state.stations.isEmpty())
            assertTrue(state.routes.isEmpty())
            assertTrue(state.trains.isEmpty())
            assertTrue(state.journeyHistory.isEmpty())
            assertTrue(state.trainHistory.isEmpty())
            assertTrue(state.savedEmpty)
            assertFalse(state.observationFailed)
            assertFalse(state.historyObservationFailed)
        } finally {
            life.destroy()
        }
    }

    @Test fun favoriteProjectionUsesStableSemanticIdentity() = runTest {
        val roma = Station(testStation.id, "Roma Termini")
        val milano = Station(StationId("milano-centrale"), "Milano Centrale")
        val route = FavoriteRoute.create(roma, milano)
        val train = FavoriteTrain.create(
            TrainNumber("123"),
            originId = roma.id,
            operator = Operator("Trenitalia"),
            originName = roma.name,
            destinationName = "Milano Centrale",
        )
        val repository = FakeRealtimeRepositories().apply {
            favorites.value = listOf(roma)
            favoriteRoutes.value = listOf(route)
            favoriteTrains.value = listOf(train)
        }
        val (facade, _, life) = facade(repository)
        try {
            runCurrent()
            val state = facade.state.value
            assertEquals(listOf(roma.id.value), state.stations.map { it.stationId })
            assertEquals(listOf(route.id.value), state.routes.map { it.identity })
            assertEquals(roma.id.value, state.routes.single().originId)
            assertEquals(milano.id.value, state.routes.single().destinationId)
            val row = state.trains.single()
            // Semantic identity, never display text or position.
            assertEquals(train.id.value, row.identity)
            assertEquals("123", row.number)
            assertEquals(roma.id.value, row.originId)
            assertEquals("Roma Termini", row.originName)
            assertEquals("Trenitalia", row.operatorName)
            assertEquals("Milano Centrale", row.destinationName)
            assertTrue(row.canOpen)
        } finally {
            life.destroy()
        }
    }

    @Test fun sameNumberDifferentOriginRemainsDistinguishable() = runTest {
        val roma = Station(testStation.id, "Roma Termini")
        val napoli = Station(StationId("napoli-centrale"), "Napoli Centrale")
        val a = FavoriteTrain.create(TrainNumber("123"), originId = roma.id,
            operator = Operator("Trenitalia"), originName = roma.name)
        val b = FavoriteTrain.create(TrainNumber("123"), originId = napoli.id,
            operator = Operator("Trenitalia"), originName = napoli.name)
        val repository = FakeRealtimeRepositories().apply { favoriteTrains.value = listOf(a, b) }
        val (facade, _, life) = facade(repository)
        try {
            runCurrent()
            val identities = facade.state.value.trains.map { it.identity }
            assertEquals(2, identities.distinct().size)
            assertEquals(setOf(a.id.value, b.id.value), identities.toSet())
            assertEquals(setOf(roma.id.value, napoli.id.value),
                facade.state.value.trains.map { it.originId }.toSet())
        } finally {
            life.destroy()
        }
    }

    @Test fun sameNumberDifferentOperatorRemainsDistinguishable() = runTest {
        val roma = Station(testStation.id, "Roma Termini")
        val a = FavoriteTrain.create(TrainNumber("123"), originId = roma.id,
            operator = Operator("Trenitalia"), originName = roma.name)
        val b = FavoriteTrain.create(TrainNumber("123"), originId = roma.id,
            operator = Operator("Italo"), originName = roma.name)
        val repository = FakeRealtimeRepositories().apply { favoriteTrains.value = listOf(a, b) }
        val (facade, _, life) = facade(repository)
        try {
            runCurrent()
            val rows = facade.state.value.trains
            assertEquals(2, rows.map { it.identity }.distinct().size)
            assertEquals(setOf("Trenitalia", "Italo"), rows.map { it.operatorName }.toSet())
        } finally {
            life.destroy()
        }
    }

    @Test fun unknownOriginOperatorProjectsNullsWithoutFabrication() = runTest {
        val train = FavoriteTrain.create(TrainNumber("123"), originId = null, operator = null)
        val repository = FakeRealtimeRepositories().apply { favoriteTrains.value = listOf(train) }
        val (facade, _, life) = facade(repository)
        try {
            runCurrent()
            val row = facade.state.value.trains.single()
            assertEquals(train.id.value, row.identity)
            assertNull(row.originId)
            assertNull(row.originName)
            assertNull(row.operatorName)
            assertNull(row.destinationName)
        } finally {
            life.destroy()
        }
    }

    @Test fun removeTrainForwardsSharedIdempotentRemoval() = runTest {
        val roma = Station(testStation.id, "Roma Termini")
        val train = FavoriteTrain.create(TrainNumber("123"), originId = roma.id,
            operator = Operator("Trenitalia"), originName = roma.name)
        val repository = FakeRealtimeRepositories().apply { favoriteTrains.value = listOf(train) }
        val (facade, _, life) = facade(repository)
        try {
            runCurrent()
            facade.removeTrain(train.id.value)
            runCurrent()
            assertTrue(facade.state.value.trains.isEmpty())
            assertTrue(repository.favoriteTrains.value.isEmpty())
            // Unknown identity is a no-op, never a crash.
            facade.removeTrain("missing")
            runCurrent()
        } finally {
            life.destroy()
        }
    }

    @Test fun openTrainForwardsFullLookupDiscrimination() = runTest {
        val roma = Station(testStation.id, "Roma Termini")
        val napoli = Station(StationId("napoli-centrale"), "Napoli Centrale")
        val a = FavoriteTrain.create(TrainNumber("123"), originId = roma.id,
            operator = Operator("Trenitalia"), originName = roma.name)
        val b = FavoriteTrain.create(TrainNumber("123"), originId = napoli.id,
            operator = Operator("Italo"), originName = napoli.name)
        val repository = FakeRealtimeRepositories().apply { favoriteTrains.value = listOf(a, b) }
        var launched: TrainLookupIntent? = null
        val (facade, _, life) = facade(repository, onTrainLookup = { launched = it })
        try {
            runCurrent()
            facade.openTrain(a.id.value)
            // Full discrimination travels; never number-only.
            assertEquals(a.lookupIntent, launched)
            assertEquals(roma.id, launched?.originId)
            assertEquals(Operator("Trenitalia"), launched?.operator)
            facade.openTrain(b.id.value)
            assertEquals(napoli.id, launched?.originId)
        } finally {
            life.destroy()
        }
    }

    @Test fun journeyHistoryProjectsNewestFirstWithOriginalCriteria() = runTest {
        val repository = FakeRealtimeRepositories()
        val first = repository.recordSearch(JourneySearchRequest(testStation, journeyDestination, at,
            JourneySearchMode.DEPART_AFTER))
        val second = repository.recordSearch(JourneySearchRequest(journeyDestination, testStation,
            at + kotlin.time.Duration.parse("26h"), JourneySearchMode.ARRIVE_BY))
        val (facade, _, life) = facade(repository)
        try {
            runCurrent()
            val rows = facade.state.value.journeyHistory
            assertEquals(2, rows.size)
            // Newest-first merged observation.
            assertEquals(second.id.value, rows[0].entryId)
            assertEquals(first.id.value, rows[1].entryId)
            // Original intended values preserved, never replaced with now.
            assertEquals(journeyDestination.id.value, rows[0].originId)
            assertEquals(testStation.id.value, rows[0].destinationId)
            assertEquals((at + kotlin.time.Duration.parse("26h")).toEpochMilliseconds() / 1000,
                rows[0].atEpochSeconds)
            assertEquals(JourneySearchMode.ARRIVE_BY, rows[0].mode)
            assertEquals(testStation.id.value, rows[1].originId)
            assertEquals(at.toEpochMilliseconds() / 1000, rows[1].atEpochSeconds)
        } finally {
            life.destroy()
        }
    }

    @Test fun repeatJourneyForwardsOriginalRequestUnchanged() = runTest {
        val repository = FakeRealtimeRepositories()
        val entry = repository.recordSearch(JourneySearchRequest(testStation, journeyDestination, at,
            JourneySearchMode.DEPART_AFTER))
        var repeated: JourneySearchRequest? = null
        val (facade, _, life) = facade(repository, onJourneyRepeat = { repeated = it })
        try {
            runCurrent()
            facade.repeatJourney(entry.id.value)
            assertEquals(entry.request(), repeated)
            assertEquals(at, repeated?.at)
            assertEquals(testStation, repeated?.origin)
            assertEquals(journeyDestination, repeated?.destination)
        } finally {
            life.destroy()
        }
    }

    @Test fun trainHistoryProjectsNumberDateAndDiscrimination() = runTest {
        val repository = FakeRealtimeRepositories()
        val roma = Station(testStation.id, "Roma Termini")
        val date = LocalDate.parse("2026-09-10")
        val entry = repository.recordTrainSearch(TrainNumber("8640"), date,
            TrainLookupIntent(TrainNumber("8640"), originId = roma.id, originName = roma.name,
                operator = Operator("Trenitalia")))
        val numberOnly = repository.recordTrainSearch(TrainNumber("55"), null, null)
        val (facade, _, life) = facade(repository)
        try {
            runCurrent()
            val rows = facade.state.value.trainHistory
            assertEquals(2, rows.size)
            assertEquals(numberOnly.id.value, rows[0].entryId)
            assertEquals(entry.id.value, rows[1].entryId)
            val discriminated = rows[1]
            assertEquals("8640", discriminated.number)
            assertEquals(date.toString(), discriminated.serviceDateString)
            assertEquals(roma.id.value, discriminated.originId)
            assertEquals("Roma Termini", discriminated.originName)
            assertEquals("Trenitalia", discriminated.operatorName)
            // Number-only search invents no route.
            assertNull(rows[0].serviceDateString)
            assertNull(rows[0].originId)
            assertNull(rows[0].originName)
            assertNull(rows[0].operatorName)
        } finally {
            life.destroy()
        }
    }

    @Test fun repeatTrainForwardsServiceDateAndDiscrimination() = runTest {
        val repository = FakeRealtimeRepositories()
        val roma = Station(testStation.id, "Roma Termini")
        val date = LocalDate.parse("2026-09-10")
        val expected = TrainLookupIntent(TrainNumber("8640"), originId = roma.id,
            originName = roma.name, operator = Operator("Trenitalia"))
        val entry = repository.recordTrainSearch(TrainNumber("8640"), date, expected)
        var repeated: TrainSearchHistoryEntry? = null
        val (facade, _, life) = facade(repository, onTrainRepeat = { repeated = it })
        try {
            runCurrent()
            facade.repeatTrain(entry.id.value)
            assertEquals(entry.id, repeated?.id)
            assertEquals(date, repeated?.serviceDate)
            assertEquals(roma.id, repeated?.originId)
            assertEquals(Operator("Trenitalia"), repeated?.operator)
            assertEquals(expected, repeated?.lookupIntent())
        } finally {
            life.destroy()
        }
    }

    @Test fun removeHistoryAndClearHistoryForward() = runTest {
        val repository = FakeRealtimeRepositories()
        val entry = repository.recordSearch(JourneySearchRequest(testStation, journeyDestination, at,
            JourneySearchMode.DEPART_AFTER))
        repository.recordTrainSearch(TrainNumber("55"), null, null)
        val (facade, _, life) = facade(repository)
        try {
            runCurrent()
            assertEquals(1, facade.state.value.journeyHistory.size)
            assertEquals(1, facade.state.value.trainHistory.size)
            facade.removeHistory(entry.id.value)
            runCurrent()
            assertTrue(facade.state.value.journeyHistory.isEmpty())
            assertEquals(1, facade.state.value.trainHistory.size)
            facade.clearHistory()
            runCurrent()
            assertTrue(facade.state.value.trainHistory.isEmpty())
            assertTrue(facade.state.value.savedEmpty)
        } finally {
            life.destroy()
        }
    }

    @Test fun favoriteFailureAndHistoryFailureAreExposedSeparately() = runTest {
        val train = FavoriteTrain.create(TrainNumber("123"), originId = testStation.id,
            operator = Operator("Trenitalia"), originName = testStation.name)
        val repository = FakeRealtimeRepositories().apply {
            favoriteTrains.value = listOf(train)
            favoriteFailure = IllegalStateException("disk")
        }
        val (facade, _, life) = facade(repository)
        try {
            runCurrent()
            facade.removeTrain(train.id.value)
            runCurrent()
            assertTrue(facade.state.value.favoriteFailed)
            assertFalse(facade.state.value.historyFailed)
        } finally {
            life.destroy()
        }
    }

    @Test fun shellCachesSavedFacadePerLiveFavoritesComponent() = runTest {
        val repository = FakeRealtimeRepositories()
        val life = LifecycleRegistry()
        val factory = AppComponentFactory(repository, repository, repository, repository,
            kotlinx.coroutines.flow.MutableStateFlow(false), StandardTestDispatcher(testScheduler))
        val root = DefaultRootComponent(DefaultComponentContext(life), factory)
        val shell = createShellPresentation(root)
        try {
            life.resume()
            runCurrent()
            shell.select(NativePrimaryArea.Saved)
            runCurrent()
            val saved = shell.state.value.base.saved
            assertTrue(saved != null)
            // Restored shell over the same root reuses the cached facade identity.
            val again = shell.state.value.base.saved
            assertTrue(saved === again)
        } finally {
            shell.close()
            life.destroy()
        }
    }

    @Test fun savedRepeatRoutesThroughSharedNavigation() = runTest {
        val repository = FakeRealtimeRepositories()
        val life = LifecycleRegistry()
        val factory = AppComponentFactory(repository, repository, repository, repository,
            kotlinx.coroutines.flow.MutableStateFlow(false), StandardTestDispatcher(testScheduler))
        val root = DefaultRootComponent(DefaultComponentContext(life), factory)
        val shell = createShellPresentation(root)
        try {
            life.resume()
            runCurrent()
            // Decompose pages destroy off-screen tab children, so each leg
            // below rebinds the CURRENT Saved facade like production native
            // hosts do; no facade is held across a tab switch.
            shell.select(NativePrimaryArea.Saved)
            runCurrent()
            val journey = repository.recordSearch(JourneySearchRequest(testStation, journeyDestination, at,
                JourneySearchMode.DEPART_AFTER))
            val train = repository.recordTrainSearch(TrainNumber("8640"),
                LocalDate.parse("2026-09-10"),
                TrainLookupIntent(TrainNumber("8640"), originId = testStation.id,
                    originName = testStation.name, operator = Operator("Trenitalia")))
            runCurrent()
            checkNotNull(shell.state.value.base.saved).repeatJourney(journey.id.value)
            runCurrent()
            // Journey repeat lands on the shared Journey search through the
            // existing Main authority (no platform-local navigation state).
            val main = assertIs<MainComponent.Child.Journey>(
                mainOf(root).pages.value.items[MainTab.Journey.ordinal].instance,
            )
            assertIs<JourneyTabComponent.Child.Search>(main.component.stack.value.active.instance)
            // Returning to Saved materializes a fresh Favorites child and a
            // fresh cached facade over the same repositories.
            shell.select(NativePrimaryArea.Saved)
            runCurrent()
            checkNotNull(shell.state.value.base.saved).repeatTrain(train.id.value)
            runCurrent()
            // Train repeat enters the shared Root train-search flow.
            val search = assertIs<RootComponent.Child.Search>(root.stack.value.active.instance)
            assertEquals("8640", search.component.state.value.number)
            assertEquals(LocalDate.parse("2026-09-10"), search.component.state.value.serviceDate)
        } finally {
            shell.close()
            life.destroy()
        }
    }

    @Test fun favoriteOpenRoutesThroughSharedTrainLookup() = runTest {
        val repository = FakeRealtimeRepositories()
        val life = LifecycleRegistry()
        val factory = AppComponentFactory(repository, repository, repository, repository,
            kotlinx.coroutines.flow.MutableStateFlow(false), StandardTestDispatcher(testScheduler))
        val root = DefaultRootComponent(DefaultComponentContext(life), factory)
        val shell = createShellPresentation(root)
        try {
            life.resume()
            runCurrent()
            val train = FavoriteTrain.create(TrainNumber("123"), originId = testStation.id,
                operator = Operator("Trenitalia"), originName = testStation.name)
            repository.favoriteTrains.value = listOf(train)
            shell.select(NativePrimaryArea.Saved)
            runCurrent()
            val saved = checkNotNull(shell.state.value.base.saved)
            saved.openTrain(train.id.value)
            runCurrent()
            // Favorite opens a fresh discriminated lookup, never a dated run.
            val active = root.stack.value.active.instance
            assertIs<RootComponent.Child.Search>(active)
            assertEquals("123", active.component.state.value.number)
        } finally {
            shell.close()
            life.destroy()
        }
    }

    // Corrective B1: retained favorites stay projected during observation
    // failure (observation failure is distinct from mutation failure).
    @Test fun favoritesObservationFailureRetainsProjectedContent() = runTest {
        val train = FavoriteTrain.create(TrainNumber("123"), originId = testStation.id,
            operator = Operator("Trenitalia"), originName = testStation.name)
        val repository = FakeRealtimeRepositories().apply {
            favorites.value = listOf(testStation)
            favoriteTrains.value = listOf(train)
        }
        val (facade, _, life) = facade(repository)
        try {
            runCurrent()
            assertEquals(1, facade.state.value.trains.size)
            assertFalse(facade.state.value.observationFailed)
            repository.failFavoritesObservation(IllegalStateException("offline"))
            runCurrent()
            // Retained rows remain visible with the failure flagged; rows
            // stay actionable through shared capabilities.
            val failed = facade.state.value
            assertTrue(failed.observationFailed)
            assertEquals(listOf(testStation.id.value), failed.stations.map { it.stationId })
            assertEquals(listOf(train.id.value), failed.trains.map { it.identity })
            assertTrue(failed.hasRetainedFavorites)
        } finally {
            life.destroy()
        }
    }

    @Test fun favoritesObservationFailureWithoutContentProjectsNoRows() = runTest {
        val repository = FakeRealtimeRepositories()
        val (facade, _, life) = facade(repository)
        try {
            runCurrent()
            assertFalse(facade.state.value.observationFailed)
            repository.failFavoritesObservation(IllegalStateException("offline"))
            runCurrent()
            val failed = facade.state.value
            assertTrue(failed.observationFailed)
            assertTrue(failed.favoritesEmpty)
            assertFalse(failed.hasRetainedFavorites)
        } finally {
            life.destroy()
        }
    }

    @Test fun favoritesObservationRecoveryClearsFailureAndKeepsContent() = runTest {
        val train = FavoriteTrain.create(TrainNumber("123"), originId = testStation.id,
            operator = Operator("Trenitalia"), originName = testStation.name)
        val repository = FakeRealtimeRepositories().apply { favoriteTrains.value = listOf(train) }
        val (facade, _, life) = facade(repository)
        try {
            runCurrent()
            repository.failFavoritesObservation(IllegalStateException("offline"))
            runCurrent()
            assertTrue(facade.state.value.observationFailed)
            facade.retryFavorites()
            runCurrent()
            val recovered = facade.state.value
            assertFalse(recovered.observationFailed)
            assertFalse(recovered.loading)
            assertEquals(listOf(train.id.value), recovered.trains.map { it.identity })
        } finally {
            life.destroy()
        }
    }

    // Corrective B1: retained history stays projected during observation failure.
    @Test fun historyObservationFailureRetainsProjectedContent() = runTest {
        val repository = FakeRealtimeRepositories()
        val journey = repository.recordSearch(JourneySearchRequest(testStation, journeyDestination, at,
            JourneySearchMode.DEPART_AFTER))
        val trainEntry = repository.recordTrainSearch(TrainNumber("55"), null, null)
        val (facade, _, life) = facade(repository)
        try {
            runCurrent()
            assertEquals(1, facade.state.value.journeyHistory.size)
            repository.failSearchHistoryObservation(IllegalStateException("offline"))
            runCurrent()
            val failed = facade.state.value
            assertTrue(failed.historyObservationFailed)
            assertEquals(listOf(journey.id.value), failed.journeyHistory.map { it.entryId })
            assertEquals(listOf(trainEntry.id.value), failed.trainHistory.map { it.entryId })
            assertTrue(failed.hasRetainedHistory)
        } finally {
            life.destroy()
        }
    }

    @Test fun historyObservationFailureWithoutContentProjectsNoRows() = runTest {
        val repository = FakeRealtimeRepositories()
        val (facade, _, life) = facade(repository)
        try {
            runCurrent()
            repository.failSearchHistoryObservation(IllegalStateException("offline"))
            runCurrent()
            val failed = facade.state.value
            assertTrue(failed.historyObservationFailed)
            assertTrue(failed.journeyHistoryEmpty)
            assertTrue(failed.trainHistoryEmpty)
            assertFalse(failed.hasRetainedHistory)
        } finally {
            life.destroy()
        }
    }

    @Test fun historyObservationRecoveryClearsFailureAndKeepsContent() = runTest {
        val repository = FakeRealtimeRepositories()
        val journey = repository.recordSearch(JourneySearchRequest(testStation, journeyDestination, at,
            JourneySearchMode.DEPART_AFTER))
        val (facade, _, life) = facade(repository)
        try {
            runCurrent()
            repository.failSearchHistoryObservation(IllegalStateException("offline"))
            runCurrent()
            assertTrue(facade.state.value.historyObservationFailed)
            facade.retryHistory()
            runCurrent()
            val recovered = facade.state.value
            assertFalse(recovered.historyObservationFailed)
            assertFalse(recovered.historyLoading)
            assertEquals(listOf(journey.id.value), recovered.journeyHistory.map { it.entryId })
        } finally {
            life.destroy()
        }
    }

    // Corrective C4: scoped retries touch only their own failure.
    @Test fun favoritesRetryRetriesFavoriteFailureOnly() = runTest {
        val train = FavoriteTrain.create(TrainNumber("123"), originId = testStation.id,
            operator = Operator("Trenitalia"), originName = testStation.name)
        val repository = FakeRealtimeRepositories().apply {
            favoriteTrains.value = listOf(train)
            favoriteFailure = IllegalStateException("disk")
        }
        val (facade, _, life) = facade(repository)
        try {
            runCurrent()
            facade.removeTrain(train.id.value)
            runCurrent()
            assertTrue(facade.state.value.favoriteFailed)
            repository.favoriteFailure = null
            facade.retryFavorites()
            runCurrent()
            assertFalse(facade.state.value.favoriteFailed)
            assertTrue(facade.state.value.trains.isEmpty())
        } finally {
            life.destroy()
        }
    }

    @Test fun historyRetryRetriesHistoryFailureOnly() = runTest {
        val repository = FakeRealtimeRepositories()
        val entry = repository.recordSearch(JourneySearchRequest(testStation, journeyDestination, at,
            JourneySearchMode.DEPART_AFTER))
        repository.removeSearchFailure = IllegalStateException("disk")
        val (facade, _, life) = facade(repository)
        try {
            runCurrent()
            facade.removeHistory(entry.id.value)
            runCurrent()
            assertTrue(facade.state.value.historyFailed)
            repository.removeSearchFailure = null
            facade.retryHistory()
            runCurrent()
            // Retry clears the history failure; the entry itself was never
            // removed (the failed mutation did not persist), matching the
            // shared component contract.
            assertFalse(facade.state.value.historyFailed)
            assertEquals(listOf(entry.id.value), facade.state.value.journeyHistory.map { it.entryId })
        } finally {
            life.destroy()
        }
    }

    @Test fun simultaneousFailuresRetryIndependently() = runTest {
        val train = FavoriteTrain.create(TrainNumber("123"), originId = testStation.id,
            operator = Operator("Trenitalia"), originName = testStation.name)
        val repository = FakeRealtimeRepositories().apply { favoriteTrains.value = listOf(train) }
        val entry = repository.recordSearch(JourneySearchRequest(testStation, journeyDestination, at,
            JourneySearchMode.DEPART_AFTER))
        repository.favoriteFailure = IllegalStateException("disk")
        repository.removeSearchFailure = IllegalStateException("disk")
        val (facade, _, life) = facade(repository)
        try {
            runCurrent()
            facade.removeTrain(train.id.value)
            facade.removeHistory(entry.id.value)
            runCurrent()
            assertTrue(facade.state.value.favoriteFailed)
            assertTrue(facade.state.value.historyFailed)
            // History Retry retries HISTORY only: the favorite failure
            // remains pending even after the history failure is cleared.
            repository.removeSearchFailure = null
            facade.retryHistory()
            runCurrent()
            assertFalse(facade.state.value.historyFailed)
            assertTrue(facade.state.value.favoriteFailed)
            // Favorites Retry retries FAVORITES only: the history failure
            // stays cleared and is not re-armed by the favorites retry.
            repository.favoriteFailure = null
            facade.retryFavorites()
            runCurrent()
            assertFalse(facade.state.value.favoriteFailed)
            assertFalse(facade.state.value.historyFailed)
            assertTrue(facade.state.value.trains.isEmpty())
            assertEquals(listOf(entry.id.value), facade.state.value.journeyHistory.map { it.entryId })
        } finally {
            life.destroy()
        }
    }
}

private fun mainOf(root: RootComponent): MainComponent {
    val items = root.stack.value.items
    return (items.first { it.instance is RootComponent.Child.Main }.instance as RootComponent.Child.Main).component
}
