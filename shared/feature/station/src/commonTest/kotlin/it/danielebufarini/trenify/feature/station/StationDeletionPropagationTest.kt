package it.danielebufarini.trenify.feature.station

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.domain.SearchStations
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.testStation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T7.8: the confirmed Settings search-history deletion also clears the
 * user-visible recent-station suggestions observed on the Stations screen;
 * the component never writes deleted recency back and new openings work
 * normally afterwards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StationDeletionPropagationTest {
    @Test fun settingsHistoryDeletionEmptiesRecencySuggestionsReactively() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val component = StationSearchComponent(
            DefaultComponentContext(lifecycle),
            SearchStations(repositories),
            repositories,
            {},
            StandardTestDispatcher(testScheduler),
            historyRepository = repositories,
        )
        lifecycle.resume()
        try {
            runCurrent()
            repositories.record(testStation)
            runCurrent()
            assertEquals(listOf(testStation), component.state.value.recent)

            // The confirmed Settings action clears recency together with
            // both search-history kinds.
            repositories.clearSearchHistoryAndRecency()
            runCurrent()

            assertTrue(component.state.value.recent.isEmpty())

            // A new explicit station opening after the deletion suggests it
            // again normally.
            repositories.record(testStation)
            runCurrent()
            assertEquals(listOf(testStation), component.state.value.recent)
        } finally {
            lifecycle.destroy()
        }
    }
}
