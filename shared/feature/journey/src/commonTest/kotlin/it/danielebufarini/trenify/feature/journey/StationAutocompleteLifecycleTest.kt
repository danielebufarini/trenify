package it.danielebufarini.trenify.feature.journey

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.testing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class StationAutocompleteLifecycleTest {
    @Test fun debounceCancellationAndLateResponsesKeepTheCurrentQueryAuthoritative() = runTest {
        val lifecycle = LifecycleRegistry()
        val queries = mutableListOf<String>()
        val releaseOld = CompletableDeferred<Unit>()
        val terni = Station(StationId("station:terni"), "Terni")
        var oldCancelled = false
        val repository = object : JourneyRepository by FakeJourneyRepository() {
            override suspend fun searchStations(query: String): DataResult<List<Station>> {
                queries += query
                if (query == "roma") {
                    try {
                        // Deliberately non-cooperative source: stale-response protection must still work.
                        withContext(NonCancellable) { releaseOld.await() }
                        return DataResult.Data(listOf(testStation), DataFreshness.Unknown)
                    } finally { oldCancelled = !currentCoroutineContext().isActive }
                }
                return DataResult.Data(listOf(terni), DataFreshness.Unknown)
            }
        }
        val component = JourneySearchComponent(DefaultComponentContext(lifecycle), repository, {}, StandardTestDispatcher(testScheduler))
        lifecycle.resume()
        try {
            component.stationText(false, "ro")
            advanceTimeBy(150); runCurrent()
            component.stationText(false, "roma")
            advanceTimeBy(274); runCurrent()
            assertTrue(queries.isEmpty())
            advanceTimeBy(1); runCurrent()
            assertEquals(listOf("roma"), queries)
            assertTrue(component.state.value.loading)
            component.stationText(false, "terni")
            advanceTimeBy(275); runCurrent()
            assertEquals(listOf(terni), component.state.value.suggestions)
            releaseOld.complete(Unit); runCurrent()
            assertTrue(oldCancelled)
            assertEquals(listOf(terni), component.state.value.suggestions)
            component.select(testStation) // A stale suggestion cannot be selected.
            assertNull(component.state.value.destination)
            component.select(terni)
            assertEquals(terni.id, component.state.value.destination?.id)
            component.stationText(false, "t")
            advanceUntilIdle()
            assertEquals(listOf("roma", "terni"), queries)
            assertTrue(component.state.value.suggestions.isEmpty())
            assertFalse(component.state.value.loading)
        } finally { releaseOld.complete(Unit); lifecycle.destroy() }
    }
}
