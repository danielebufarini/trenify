package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import it.danielebufarini.trenify.core.model.JourneySearchHistoryEntry
import it.danielebufarini.trenify.core.model.JourneySearchMode
import it.danielebufarini.trenify.core.model.JourneySearchRequest
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.TrainLookupIntent
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.journeyDestination
import it.danielebufarini.trenify.core.testing.testStation
import it.danielebufarini.trenify.feature.journey.JourneyTabComponent
import it.danielebufarini.trenify.feature.journey.SearchHistoryComponent
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
 * T8.9 corrective B2: the Journey-tab History child renders through the
 * native [NativeHistoryPresentation]. Ordering,
 * recording and repeat routing stay in [SearchHistoryComponent].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NativeHistoryPresentationTest {
    private val at = kotlin.time.Instant.parse("2026-09-14T09:00:00Z")

    private fun kotlinx.coroutines.test.TestScope.component(
        repository: FakeRealtimeRepositories,
    ): Pair<SearchHistoryComponent, LifecycleRegistry> {
        val life = LifecycleRegistry()
        val component = SearchHistoryComponent(
            DefaultComponentContext(life),
            repository,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        life.resume()
        return component to life
    }

    private fun kotlinx.coroutines.test.TestScope.facade(
        repository: FakeRealtimeRepositories,
    ): Triple<NativeHistoryPresentation, SearchHistoryComponent, LifecycleRegistry> {
        val (component, life) = component(repository)
        return Triple(NativeHistoryPresentation(component, NativeProjectionOwner()), component, life)
    }

    private fun kotlinx.coroutines.test.TestScope.root(
        repository: FakeRealtimeRepositories,
        keeper: StateKeeperDispatcher = StateKeeperDispatcher(null),
    ): Triple<DefaultRootComponent, LifecycleRegistry, NativeShellPresentation> {
        val life = LifecycleRegistry()
        val factory = AppComponentFactory(repository, repository, repository, repository,
            kotlinx.coroutines.flow.MutableStateFlow(false), StandardTestDispatcher(testScheduler))
        val root = DefaultRootComponent(DefaultComponentContext(life, keeper), factory)
        val shell = createShellPresentation(root)
        life.resume()
        return Triple(root, life, shell)
    }

    private fun mainOf(root: RootComponent): MainComponent {
        val items = root.stack.value.items
        return (items.first { it.instance is RootComponent.Child.Main }.instance as RootComponent.Child.Main).component
    }

    @Test fun historyProjectsNewestFirstPerKind() = runTest {
        val repository = FakeRealtimeRepositories()
        val journey = repository.recordSearch(JourneySearchRequest(testStation, journeyDestination, at,
            JourneySearchMode.DEPART_AFTER))
        val train = repository.recordTrainSearch(TrainNumber("8640"), LocalDate.parse("2026-09-10"),
            TrainLookupIntent(TrainNumber("8640"), originId = testStation.id,
                originName = testStation.name, operator = Operator("Trenitalia")))
        val (facade, _, life) = facade(repository)
        try {
            runCurrent()
            val state = facade.state.value
            assertFalse(state.loading)
            assertEquals(listOf(journey.id.value), state.journeyHistory.map { it.entryId })
            assertEquals(listOf(train.id.value), state.trainHistory.map { it.entryId })
            assertFalse(state.historyEmpty)
            assertFalse(state.observationFailed)
            assertFalse(state.failedMutation)
        } finally {
            life.destroy()
        }
    }

    @Test fun historyRepeatRemoveAndClearForward() = runTest {
        val repository = FakeRealtimeRepositories()
        val journey = repository.recordSearch(JourneySearchRequest(testStation, journeyDestination, at,
            JourneySearchMode.DEPART_AFTER))
        repository.recordTrainSearch(TrainNumber("55"), null, null)
        val (facade, component, life) = facade(repository)
        try {
            runCurrent()
            // Repeat routing is owned by the component callbacks; the facade
            // forwards the shared entry unchanged (covered end to end below).
            assertEquals(journey.request(), (component.state.value.entries
                .first { it.id == journey.id } as JourneySearchHistoryEntry).request())
            facade.removeHistory(journey.id.value)
            runCurrent()
            assertTrue(facade.state.value.journeyHistory.isEmpty())
            facade.clearHistory()
            runCurrent()
            assertTrue(facade.state.value.historyEmpty)
        } finally {
            life.destroy()
        }
    }

    @Test fun historyObservationFailureRetainsAndRecovers() = runTest {
        val repository = FakeRealtimeRepositories()
        val journey = repository.recordSearch(JourneySearchRequest(testStation, journeyDestination, at,
            JourneySearchMode.DEPART_AFTER))
        val (facade, _, life) = facade(repository)
        try {
            runCurrent()
            repository.failSearchHistoryObservation(IllegalStateException("offline"))
            runCurrent()
            val failed = facade.state.value
            assertTrue(failed.observationFailed)
            assertEquals(listOf(journey.id.value), failed.journeyHistory.map { it.entryId })
            assertTrue(failed.hasRetainedHistory)
            facade.retry()
            runCurrent()
            val recovered = facade.state.value
            assertFalse(recovered.observationFailed)
            assertEquals(listOf(journey.id.value), recovered.journeyHistory.map { it.entryId })
        } finally {
            life.destroy()
        }
    }

    @Test fun homeHistoryLandsOnNativeHistory() = runTest {
        val repository = FakeRealtimeRepositories()
        repository.recordSearch(JourneySearchRequest(testStation, journeyDestination, at,
            JourneySearchMode.DEPART_AFTER))
        val (root, life, shell) = root(repository)
        try {
            runCurrent()
            // Home -> History through the unchanged shared navigation intent.
            mainOf(root).openHistory()
            runCurrent()
            val journeyTab = assertIs<MainComponent.Child.Journey>(
                mainOf(root).pages.value.items[MainTab.Journey.ordinal].instance).component
            assertIs<JourneyTabComponent.Child.History>(journeyTab.stack.value.active.instance)
            // The History destination carries a live native facade ...
            val active = shell.state.value.active
            assertEquals(NativeDestination.History, active.destination)
            val history = checkNotNull(active.history)
            runCurrent()
            assertEquals(1, history.state.value.journeyHistory.size)
        } finally {
            shell.close()
            life.destroy()
        }
    }

    @Test fun restoredHistoryDestinationBindsNativeHistory() = runTest {
        val repository = FakeRealtimeRepositories()
        repository.recordSearch(JourneySearchRequest(testStation, journeyDestination, at,
            JourneySearchMode.DEPART_AFTER))
        val keeper = StateKeeperDispatcher(null)
        val (root, life, shell) = root(repository, keeper)
        var revived: Triple<DefaultRootComponent, LifecycleRegistry, NativeShellPresentation>? = null
        var setAside = false
        try {
            runCurrent()
            mainOf(root).openHistory()
            runCurrent()
            assertEquals(NativeDestination.History, shell.state.value.active.destination)
            val saved = keeper.save()
            shell.close()
            life.destroy()
            setAside = true
            revived = root(repository, StateKeeperDispatcher(saved))
            runCurrent()
            val active = revived.third.state.value.active
            assertEquals(NativeDestination.History, active.destination)
            val history = checkNotNull(active.history)
            runCurrent()
            assertEquals(1, history.state.value.journeyHistory.size)
        } finally {
            if (!setAside) {
                shell.close()
                life.destroy()
            }
            revived?.let {
                it.third.close()
                it.second.destroy()
            }
        }
    }

    @Test fun historyBackStaysDecomposeAuthoritative() = runTest {
        val repository = FakeRealtimeRepositories()
        val (root, life, shell) = root(repository)
        try {
            runCurrent()
            mainOf(root).openHistory()
            runCurrent()
            assertEquals(NativeDestination.History, shell.state.value.active.destination)
            // Platform Back drives the shared stack: History pops back to
            // the Journey Search it was pushed from.
            shell.back()
            runCurrent()
            val journeyTab = assertIs<MainComponent.Child.Journey>(
                mainOf(root).pages.value.items[MainTab.Journey.ordinal].instance).component
            assertIs<JourneyTabComponent.Child.Search>(journeyTab.stack.value.active.instance)
            assertEquals(NativeDestination.Home, shell.state.value.active.destination)
        } finally {
            shell.close()
            life.destroy()
        }
    }

    @Test fun historyRepeatRoutesThroughSharedNavigation() = runTest {
        val repository = FakeRealtimeRepositories()
        val journey = repository.recordSearch(JourneySearchRequest(testStation, journeyDestination, at,
            JourneySearchMode.DEPART_AFTER))
        val (root, life, shell) = root(repository)
        try {
            runCurrent()
            mainOf(root).openHistory()
            runCurrent()
            val history = checkNotNull(shell.state.value.active.history)
            runCurrent()
            history.repeatJourney(journey.id.value)
            runCurrent()
            // In-tab journey repeat pushes a prefilled Search above History
            // through shared navigation (no platform-local routing).
            val journeyTab = assertIs<MainComponent.Child.Journey>(
                mainOf(root).pages.value.items[MainTab.Journey.ordinal].instance).component
            val search = assertIs<JourneyTabComponent.Child.Search>(
                journeyTab.stack.value.active.instance).component
            assertEquals(testStation, search.state.value.origin)
            assertEquals(journeyDestination, search.state.value.destination)
        } finally {
            shell.close()
            life.destroy()
        }
    }
}
