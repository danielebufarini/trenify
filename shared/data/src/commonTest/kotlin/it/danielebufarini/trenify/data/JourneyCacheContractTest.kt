package it.danielebufarini.trenify.data

import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.provider.api.*
import it.danielebufarini.trenify.core.testing.*
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

/**
 * Persisted journey cache contract (T8 corrective): the v2 key isolates the
 * 8-hour horizon from pre-8h v1 rows, and the read path additionally rejects
 * any persisted window that does not equal the live request window.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JourneyCacheContractTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun payload(result: JourneySearchResult): String =
        json.encodeToString(JourneyResultRecord.serializer(), result.record())

    @Test fun oldV1RowIsNotReusedByCurrentContract() = runTest {
        val driver = createRepositoryTestDriver()
        try {
            val database = TrenifyDatabase(driver)
            val clock = MutableClock()
            val provider = FakeJourneyProvider(clock)
            val repository = SqlDelightJourneyRepository(database, provider, backgroundScope,
                clock = clock, dispatcher = StandardTestDispatcher(testScheduler))
            database.realtimeQueries.putCache("journey-v1:${journeyRequest.key}",
                payload(journeyResult), clock.now().toEpochMilliseconds(), null)
            val result = assertIs<DataResult.Data<JourneySearchResult>>(repository.search(journeyRequest))
            assertEquals(1, provider.calls)
            assertEquals(journeyResult, result.value)
            assertIs<DataFreshness.Fresh>(result.freshness)
        } finally { driver.close() }
    }

    @Test fun compatibleCurrentEntryIsStillReused() = runTest {
        val driver = createRepositoryTestDriver()
        try {
            val database = TrenifyDatabase(driver)
            val clock = MutableClock()
            val provider = FakeJourneyProvider(clock)
            val repository = SqlDelightJourneyRepository(database, provider, backgroundScope,
                clock = clock, dispatcher = StandardTestDispatcher(testScheduler))
            val first = assertIs<DataResult.Data<JourneySearchResult>>(repository.search(journeyRequest))
            val second = assertIs<DataResult.Data<JourneySearchResult>>(repository.search(journeyRequest))
            assertEquals(1, provider.calls)
            assertEquals(first, second)
            assertIs<DataFreshness.Fresh>(second.freshness)
        } finally { driver.close() }
    }

    @Test fun persistedRowWithMismatchingBoundsIsRejected() = runTest {
        val driver = createRepositoryTestDriver()
        try {
            val database = TrenifyDatabase(driver)
            val clock = MutableClock()
            val provider = FakeJourneyProvider(clock)
            val repository = SqlDelightJourneyRepository(database, provider, backgroundScope,
                clock = clock, dispatcher = StandardTestDispatcher(testScheduler))
            val foreign = journeyResult.copy(departureFrom = journeyRequest.departureUntil)
            database.realtimeQueries.putCache("journey-v2:${journeyRequest.key}",
                payload(foreign), clock.now().toEpochMilliseconds(), null)
            provider.result = ProviderResult.Unavailable(true, ProviderFailure.TRANSPORT)
            // The foreign window must not be served as a stale fallback.
            assertIs<DataResult.Failure>(repository.search(journeyRequest))
        } finally { driver.close() }
    }

    @Test fun staleV1PartialIsNotServedWhileV2PartialSemanticsHold() = runTest {
        val driver = createRepositoryTestDriver()
        try {
            val database = TrenifyDatabase(driver)
            val clock = MutableClock()
            val provider = FakeJourneyProvider(clock)
            val repository = SqlDelightJourneyRepository(database, provider, backgroundScope,
                clock = clock, dispatcher = StandardTestDispatcher(testScheduler))
            val partial = journeyResult.copy(coverage = journeyResult.coverage + JourneyCoverage(ProviderId("other"),
                emptyList(), emptyList(), JourneySourceStatus.UNAVAILABLE, false))
            database.realtimeQueries.putCache("journey-v1:${journeyRequest.key}",
                payload(partial), clock.now().toEpochMilliseconds(), null)
            provider.result = ProviderResult.Unavailable(true, ProviderFailure.TRANSPORT)
            assertIs<DataResult.Failure>(repository.search(journeyRequest))
            // Current-contract partials keep their stale-but-observable semantics.
            provider.result = ProviderResult.Success(partial, ProviderMetadata(provider.id, clock.now()))
            val served = assertIs<DataResult.Data<JourneySearchResult>>(repository.search(journeyRequest))
            assertTrue(served.value.partial)
            assertIs<DataFreshness.Stale>(served.freshness)
        } finally { driver.close() }
    }
}
