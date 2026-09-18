package it.danielebufarini.trenify.data

import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.provider.api.*
import it.danielebufarini.trenify.core.testing.*
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class SqlDelightJourneyRepositoryTest {
    @Test fun persistedResultsReopenWithCorrectFreshnessAndReactiveFailureFallback() = runTest {
        val driver = createRepositoryTestDriver()
        try {
            val database = TrenifyDatabase(driver)
            val clock = MutableClock()
            val provider = FakeJourneyProvider(clock)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repository = SqlDelightJourneyRepository(database, provider, backgroundScope, clock = clock, dispatcher = dispatcher)
            assertEquals(journeyResult, assertIs<DataResult.Data<JourneySearchResult>>(repository.search(journeyRequest)).value)
            repository.search(journeyRequest)
            assertEquals(1, provider.calls)
            val reopened = SqlDelightJourneyRepository(database, provider, backgroundScope, clock = clock, dispatcher = dispatcher)
            assertEquals(journeyResult, assertIs<DataResult.Data<JourneySearchResult>>(reopened.observe(journeyRequest).first()).value)
            clock.instant += 4.minutes
            provider.result = ProviderResult.Unavailable(true, ProviderFailure.TRANSPORT)
            val result = assertIs<DataResult.Data<JourneySearchResult>>(repository.search(journeyRequest))
            assertEquals(journeyResult, result.value)
            assertIs<DataFreshness.Stale>(result.freshness)
            assertEquals(DomainFailure.TEMPORARY, result.warning)
            assertEquals(result, repository.observe(journeyRequest).first())
        } finally { driver.close() }
    }

    @Test fun concurrentForcedSearchesShareOneRequestAndCancellationDoesNotCancelOtherWaiters() = runTest {
        val driver = createRepositoryTestDriver()
        try {
            val provider = FakeJourneyProvider()
            val gate = CompletableDeferred<Unit>()
            provider.beforeSearch = { gate.await() }
            val repository = SqlDelightJourneyRepository(TrenifyDatabase(driver), provider, backgroundScope,
                dispatcher = StandardTestDispatcher(testScheduler))
            val first = async { repository.search(journeyRequest, true) }
            val second = async { repository.search(journeyRequest, true) }
            runCurrent()
            assertEquals(1, provider.calls)
            first.cancel()
            gate.complete(Unit)
            assertIs<DataResult.Data<JourneySearchResult>>(second.await())
        } finally { driver.close() }
    }

    @Test fun partialSuccessIsCachedAndOfflineFailureDoesNotEraseIt() = runTest {
        val driver = createRepositoryTestDriver()
        try {
            val database = TrenifyDatabase(driver)
            val provider = FakeJourneyProvider()
            val partial = journeyResult.copy(coverage = journeyResult.coverage + JourneyCoverage(ProviderId("other"),
                emptyList(), emptyList(), JourneySourceStatus.UNAVAILABLE, false))
            provider.result = ProviderResult.Success(partial, ProviderMetadata(provider.id, MutableClock().now()))
            var online = true
            val repository = SqlDelightJourneyRepository(database, provider, backgroundScope, online = { online }, clock = MutableClock(),
                dispatcher = StandardTestDispatcher(testScheduler))
            val successful = assertIs<DataResult.Data<JourneySearchResult>>(repository.search(journeyRequest))
            assertTrue(successful.value.partial)
            assertIs<DataFreshness.Stale>(successful.freshness)
            assertNull(successful.warning)
            assertEquals(successful, repository.observe(journeyRequest).first())
            val refreshedPartial = assertIs<DataResult.Data<JourneySearchResult>>(repository.search(journeyRequest))
            assertEquals(partial, refreshedPartial.value)
            assertIs<DataFreshness.Stale>(refreshedPartial.freshness)
            runCurrent()
            assertEquals(2, provider.calls)
            provider.result = ProviderResult.Unavailable(true, ProviderFailure.TRANSPORT)
            val failedRefresh = assertIs<DataResult.Data<JourneySearchResult>>(repository.search(journeyRequest, true))
            assertEquals(partial, failedRefresh.value)
            assertEquals(DomainFailure.TEMPORARY, failedRefresh.warning)
            assertIs<DataFreshness.Stale>(failedRefresh.freshness)
            assertEquals(failedRefresh, repository.observe(journeyRequest).first())
            online = false
            val offline = assertIs<DataResult.Data<JourneySearchResult>>(repository.search(journeyRequest, true))
            assertEquals(partial, offline.value)
            assertEquals(DomainFailure.OFFLINE, offline.warning)
            assertIs<DataFreshness.Stale>(offline.freshness)
            assertEquals(DataResult.Failure(DomainFailure.OFFLINE), repository.search(journeyRequest.copy(mode = JourneySearchMode.ARRIVE_BY)))
        } finally { driver.close() }
    }

    @Test fun duplicateJourneyCallbacksRecordNothingWhileSeparateSubmissionsStayDistinct() = runTest {
        val driver = createRepositoryTestDriver()
        try {
            val database = TrenifyDatabase(driver)
            val clock = MutableClock()
            val provider = FakeJourneyProvider(clock)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val journeys = SqlDelightJourneyRepository(database, provider, backgroundScope, clock = clock, dispatcher = dispatcher)
            val history = RealtimeRepositories(database, listOf(FakeRealtimeProvider(clock)), backgroundScope,
                clock = clock, dispatcher = dispatcher)
            // Duplicate concurrent callbacks share one fetch and record
            // nothing: history comes only from explicit validated submissions.
            val gate = CompletableDeferred<Unit>()
            provider.beforeSearch = { gate.await() }
            val first = async { journeys.search(journeyRequest, true) }
            val second = async { journeys.search(journeyRequest, true) }
            runCurrent()
            assertEquals(1, provider.calls)
            gate.complete(Unit)
            assertIs<DataResult.Data<JourneySearchResult>>(first.await())
            assertIs<DataResult.Data<JourneySearchResult>>(second.await())
            assertTrue(history.observeSearchHistory().first().isEmpty())
            // A failed search outcome records nothing by itself either.
            provider.result = ProviderResult.Unavailable(true, ProviderFailure.TRANSPORT)
            journeys.search(journeyRequest, true)
            assertTrue(history.observeSearchHistory().first().isEmpty())
            // Separate explicit submissions each produce a distinct entry.
            val one = history.recordSearch(journeyRequest)
            clock.instant += 1.minutes
            val two = history.recordSearch(journeyRequest)
            assertNotEquals(one.id, two.id)
            assertEquals(listOf(two, one), history.observeSearchHistory().first())
            assertEquals(one, history.lookupSearch(one.id))
        } finally { driver.close() }
    }

    @Test fun mismatchedResponseAndMalformedPersistedDataCannotBecomeSuccessfulJourneys() = runTest {
        val driver = createRepositoryTestDriver()
        try {
            val database = TrenifyDatabase(driver)
            val provider = FakeJourneyProvider()
            provider.result = ProviderResult.Success(journeyResult.copy(departureFrom = journeyRequest.departureUntil),
                ProviderMetadata(provider.id, MutableClock().now()))
            val repository = SqlDelightJourneyRepository(database, provider, backgroundScope, dispatcher = StandardTestDispatcher(testScheduler))
            assertEquals(DataResult.Failure(DomainFailure.INVALID_RESPONSE), repository.search(journeyRequest))
            database.realtimeQueries.putCache("journey-v2:${journeyRequest.key}", "broken-json", 0L, null)
            assertIs<DataResult.Failure>(repository.observe(journeyRequest).first())
            assertEquals(DataResult.Failure(DomainFailure.INVALID_REQUEST), repository.search(journeyRequest.copy(destination = testStation)))
        } finally { driver.close() }
    }
}
