package it.danielebufarini.trenify.feature.journey

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.testing.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

/**
 * Typed FS §23 failure presentation for journey results (T7.14-A): a
 * provider failure is a retryable typed error while a valid empty result
 * stays a distinct non-error empty state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JourneyTypedFailureTest {
    @Test fun providerFailureIsTypedAndDistinctFromValidEmpty() = runTest {
        val lifecycle = LifecycleRegistry()
        val repository = FakeJourneyRepository()
        repository.state.value = DataResult.Failure(DomainFailure.OFFLINE)
        val component = JourneyResultsComponent(
            DefaultComponentContext(lifecycle), journeyRequest, SearchJourneys(repository), {},
            StandardTestDispatcher(testScheduler),
        )
        try {
            runCurrent()
            assertEquals(DomainFailure.OFFLINE, component.state.value.results.failure)
            assertNull(component.state.value.results.data)
            // A valid empty result is not a failure: no retryable error.
            repository.state.value = DataResult.Data(
                JourneySearchResult(emptyList(), emptyList(), journeyRequest.departureFrom, journeyRequest.departureUntil),
                DataFreshness.Fresh(MutableClock().now(), null),
            )
            runCurrent()
            assertNull(component.state.value.results.failure)
            assertNotNull(component.state.value.results.data)
            assertTrue(component.state.value.journeys.isEmpty())
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun staleContentPlusTemporaryKeepsJourneysWithTypedWarning() = runTest {
        val lifecycle = LifecycleRegistry()
        val repository = FakeJourneyRepository()
        repository.state.value = DataResult.Data(
            journeyResult, DataFreshness.Stale(MutableClock().now(), testStaleAge(), null),
            DomainFailure.TEMPORARY,
        )
        val component = JourneyResultsComponent(
            DefaultComponentContext(lifecycle), journeyRequest, SearchJourneys(repository), {},
            StandardTestDispatcher(testScheduler),
        )
        try {
            runCurrent()
            assertEquals(listOf(testJourney), component.state.value.journeys)
            assertEquals(DomainFailure.TEMPORARY, component.state.value.results.failure)
            assertTrue(component.state.value.results.stale)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun suggestionFailureIsTyped() = runTest {
        val lifecycle = LifecycleRegistry()
        val repository = object : JourneyRepository {
            override suspend fun searchStations(query: String): DataResult<List<Station>> =
                DataResult.Failure(DomainFailure.OFFLINE)
            override fun observe(request: JourneySearchRequest): Flow<DataResult<JourneySearchResult>> =
                flowOf(DataResult.Failure(DomainFailure.OFFLINE))
            override suspend fun search(request: JourneySearchRequest, force: Boolean): DataResult<JourneySearchResult> =
                DataResult.Failure(DomainFailure.OFFLINE)
        }
        val component = JourneySearchComponent(
            DefaultComponentContext(lifecycle), repository, {},
            StandardTestDispatcher(testScheduler),
        )
        try {
            component.stationText(true, "rom")
            advanceTimeBy(300)
            runCurrent()
            assertEquals(DomainFailure.OFFLINE, component.state.value.failure)
            assertTrue(component.state.value.suggestions.isEmpty())
        } finally {
            lifecycle.destroy()
        }
    }
}

private fun testStaleAge(): kotlin.time.Duration = kotlin.time.Duration.parse("PT90S")
