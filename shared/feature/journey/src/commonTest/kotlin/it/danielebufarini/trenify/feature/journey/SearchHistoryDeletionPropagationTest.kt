package it.danielebufarini.trenify.feature.journey

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.journeyRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T7.8: the confirmed Settings search-history deletion propagates to the
 * History screen automatically through the repository flow; the component
 * never writes deleted entries back and new submissions work normally
 * afterwards. Per-item removal keeps working on the remaining entries.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchHistoryDeletionPropagationTest {
    @Test fun settingsHistoryDeletionEmptiesHistoryReactively() = runTest {
        val lifecycle = LifecycleRegistry()
        val history = FakeRealtimeRepositories()
        history.recordSearch(journeyRequest)
        history.recordTrainSearch(TrainNumber("456"), LocalDate.parse("2026-09-06"), expected = null)
        val component = SearchHistoryComponent(
            DefaultComponentContext(lifecycle),
            history,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        lifecycle.resume()
        try {
            runCurrent()
            assertEquals(2, component.state.value.entries.size)

            // The confirmed Settings action clears journey entries, train
            // entries and recency at once.
            history.clearSearchHistoryAndRecency()
            runCurrent()

            assertTrue(component.state.value.entries.isEmpty())
            assertTrue(history.observeSearchHistory().first().isEmpty())

            // No write-back: a new explicit submission after the deletion is
            // recorded and observed normally.
            val after = history.recordSearch(journeyRequest)
            runCurrent()
            assertEquals(listOf(after), component.state.value.entries)

            // Per-item removal still works on the new entry.
            component.remove(after)
            runCurrent()
            assertTrue(component.state.value.entries.isEmpty())
            assertTrue(history.observeSearchHistory().first().isEmpty())
        } finally {
            lifecycle.destroy()
        }
    }
}
