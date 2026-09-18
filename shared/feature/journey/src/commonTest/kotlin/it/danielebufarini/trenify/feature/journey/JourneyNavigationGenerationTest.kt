package it.danielebufarini.trenify.feature.journey

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import it.danielebufarini.trenify.core.testing.FakeJourneyRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.journeyRequest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Restore-safe navigation-generation semantics (T7.13 corrective pass,
 * blocker 3).
 *
 * The History/Search generation discriminator must stay unique after a
 * save/destroy/recreate cycle through the real Decompose state keeper —
 * never a transient counter restarting from zero while restored
 * configurations still carry previous values.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JourneyNavigationGenerationTest {
    private fun kotlinx.coroutines.test.TestScope.tab(
        lifecycle: LifecycleRegistry,
        keeper: StateKeeperDispatcher,
        history: FakeRealtimeRepositories,
        journeys: FakeJourneyRepository = FakeJourneyRepository(),
    ) = DefaultJourneyTabComponent(
        DefaultComponentContext(lifecycle, keeper),
        repository = journeys,
        historyRepository = history,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun searchOf(tab: JourneyTabComponent): JourneySearchComponent =
        assertIs<JourneyTabComponent.Child.Search>(tab.stack.value.active.instance).component

    private fun historyOf(tab: JourneyTabComponent): SearchHistoryComponent =
        assertIs<JourneyTabComponent.Child.History>(tab.stack.value.active.instance).component

    private fun generationOf(route: DefaultJourneyTabComponent.Route): Long = when (route) {
        is DefaultJourneyTabComponent.Route.Search -> route.generation
        is DefaultJourneyTabComponent.Route.History -> route.generation
        else -> -1L
    }

    @Test
    fun historyRepeatRestoreThenHistoryAgainNeverRegeneratesHistory1() = runTest {
        val history = FakeRealtimeRepositories()
        val lifecycle = LifecycleRegistry()
        val keeper = StateKeeperDispatcher(null)
        val tab = tab(lifecycle, keeper, history)
        lifecycle.resume()
        try {
            // Search -> History(1) -> Repeat -> Search(2).
            searchOf(tab).history()
            runCurrent()
            assertEquals(1L, assertIs<DefaultJourneyTabComponent.Route.History>(
                tab.stack.value.active.configuration).generation)
            val entry = history.recordSearch(journeyRequest)
            runCurrent()
            historyOf(tab).repeat(entry)
            runCurrent()
            assertEquals(
                listOf(0L, 1L, 2L),
                tab.stack.value.items.map { generationOf(it.configuration as DefaultJourneyTabComponent.Route) },
            )

            val saved = keeper.save()
            lifecycle.destroy()

            // Recreate from the saved state with fresh repositories.
            val freshHistory = FakeRealtimeRepositories()
            val freshEntry = freshHistory.recordSearch(journeyRequest)
            val revivedLifecycle = LifecycleRegistry()
            val revived = tab(revivedLifecycle, StateKeeperDispatcher(saved), freshHistory)
            revivedLifecycle.resume()
            try {
                runCurrent()
                assertEquals(3, revived.stack.value.items.size)
                // Opening History again must allocate History(3) — never
                // regenerate History(1) — and must not throw.
                searchOf(revived).history()
                runCurrent()
                val configs = revived.stack.value.items.map {
                    it.configuration as DefaultJourneyTabComponent.Route
                }
                assertEquals(4, configs.size)
                assertEquals(3L, assertIs<DefaultJourneyTabComponent.Route.History>(configs.last()).generation)
                // Back ordering stays correct: H3 -> S2 -> H1 -> S0.
                revived.back()
                assertEquals(2L, assertIs<DefaultJourneyTabComponent.Route.Search>(
                    revived.stack.value.active.configuration).generation)
                revived.back()
                assertEquals(1L, assertIs<DefaultJourneyTabComponent.Route.History>(
                    revived.stack.value.active.configuration).generation)
                revived.back()
                assertEquals(0L, assertIs<DefaultJourneyTabComponent.Route.Search>(
                    revived.stack.value.active.configuration).generation)
                // Another post-restoration navigation creating generated
                // configurations (History then a repeat Search) stays
                // unique and never throws: [S0] -> [S0, H4] -> [S0, H4, S5].
                searchOf(revived).history()
                runCurrent()
                assertEquals(4L, assertIs<DefaultJourneyTabComponent.Route.History>(
                    revived.stack.value.active.configuration).generation)
                historyOf(revived).repeat(freshEntry)
                runCurrent()
                val after = revived.stack.value.items.map {
                    it.configuration as DefaultJourneyTabComponent.Route
                }
                assertEquals(3, after.size)
                assertEquals(5L, assertIs<DefaultJourneyTabComponent.Route.Search>(after.last()).generation)
                val searchGenerations = after
                    .filterIsInstance<DefaultJourneyTabComponent.Route.Search>()
                    .map { it.generation }
                assertEquals(searchGenerations.size, searchGenerations.toSet().size)
            } finally {
                revivedLifecycle.destroy()
            }
        } finally {
            runCatching { lifecycle.destroy() }
        }
    }
}
