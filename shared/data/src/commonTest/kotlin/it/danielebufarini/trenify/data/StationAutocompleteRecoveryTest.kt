package it.danielebufarini.trenify.data

import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.testing.*
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.Duration.Companion.hours

class StationAutocompleteRecoveryTest {
    @Test fun degradedSuggestionsNeverCreatePersistentSnapshot() = runBlocking {
        withRepository { database, fixture, repository, _ ->
            fixture.failItalo = true
            val degraded = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("roma"))
            assertEquals(85, degraded.value.size)
            assertNull(database.realtimeQueries.cacheByKey(key("roma")).executeAsOneOrNull(),
                "A successful source cannot certify completeness for a failed source")
        }
    }

    @Test fun trenitaliaFailureRecoversWithoutRepeatingSuccessfulItaloRequest() = runBlocking {
        withRepository { database, fixture, repository, _ ->
            fixture.failTrenitalia = true
            val degraded = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("roma"))
            assertEquals(2, degraded.value.size)
            assertNull(database.realtimeQueries.cacheByKey(key("roma")).executeAsOneOrNull())
            fixture.failTrenitalia = false
            val recovered = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("roma"))
            assertEquals(87, recovered.value.size)
            assertIs<DataFreshness.Fresh>(recovered.freshness)
            assertEquals(recovered.value, read(database, "roma"))
            assertEquals(listOf("roma", "roma", "roma", "roma"), fixture.locationQueries)
            assertEquals(listOf("roma"), fixture.italoQueries)
        }
    }

    @Test fun emptyPersistentAndAdapterCachesResolveEveryAcceptanceQuery() = runBlocking {
        for (query in listOf("narni amelia", "Narni-Amelia", "roma tib")) {
            withRepository { database, fixture, repository, _ ->
                assertNull(database.realtimeQueries.cacheByKey(key(query)).executeAsOneOrNull())
                val result = assertIs<DataResult.Data<List<Station>>>(repository.searchStations(query))
                assertIs<DataFreshness.Fresh>(result.freshness)
                assertEquals(if (query == "roma tib") "Roma Tiburtina" else "Narni-Amelia", result.value.first().name)
                assertTrue(result.value.any { it.id == StationId(if (query == "roma tib") "location:trenitalia-journeys:830008217" else "location:trenitalia-journeys:830007230") })
                val expected = if (query == "narni amelia") listOf(query, "narni") else listOf(normalizeStationSearchQuery(query))
                assertEquals(expected, fixture.locationQueries)
                assertEquals(expected, fixture.italoQueries)
                assertEquals(result.value, assertIs<DataResult.Data<List<Station>>>(repository.searchStations(query)).value)
                assertEquals(expected, fixture.locationQueries)
                assertEquals(expected, fixture.italoQueries)
                assertNotNull(database.realtimeQueries.cacheByKey(key(query)).executeAsOneOrNull())
            }
        }
    }

    @Test fun previouslyPersistedEmptySnapshotCannotBlockMultiwordRecovery() = runBlocking {
        withRepository { database, fixture, repository, clock ->
            database.realtimeQueries.putCache(key("narni amelia"), "[]", clock.now().toEpochMilliseconds(), null)
            val result = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("narni amelia"))
            assertEquals(listOf(StationId("location:trenitalia-journeys:830007230")), result.value.map { it.id })
            assertEquals(listOf("narni amelia", "narni"), fixture.locationQueries)
            assertEquals(result.value, read(database, "narni amelia"))
        }
    }

    @Test fun degradedSnapshotIsUsableAndRecoveryIsPersistedBeforeTtlExpires() = runBlocking {
        withRepository { database, fixture, repository, _ ->
            fixture.failItalo = true
            val degraded = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("roma"))
            assertEquals(85, degraded.value.size)
            assertEquals(DomainFailure.TEMPORARY, degraded.warning)
            assertFalse(degraded.freshness is DataFreshness.Fresh)
            assertNull(database.realtimeQueries.cacheByKey(key("roma")).executeAsOneOrNull())
            fixture.failItalo = false
            val recovered = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("roma"))
            assertEquals(87, recovered.value.size)
            assertEquals(listOf("Roma Termini", "Roma Tiburtina"), recovered.value.take(2).map { it.name })
            assertNull(recovered.warning)
            assertIs<DataFreshness.Fresh>(recovered.freshness)
            assertEquals(recovered.value, read(database, "roma"))
            assertEquals(listOf("roma"), fixture.locationQueries, "Successful provider results are reused")
            assertEquals(listOf("roma", "roma", "roma", "roma"), fixture.italoQueries,
                "Existing HTTP policy: three failed GET attempts, then one recovered GET")
            assertEquals(recovered, repository.searchStations("roma"))
            assertEquals(4, fixture.italoQueries.size)
        }
    }

    @Test fun degradedResponseCannotOverwriteExistingCompleteSnapshot() = runBlocking {
        withRepository { database, fixture, repository, clock ->
            val complete = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("roma"))
            val saved = database.realtimeQueries.cacheByKey(key("roma")).executeAsOne()
            clock.instant += 25.hours
            fixture.failItalo = true
            val degraded = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("roma"))
            assertEquals(85, degraded.value.size)
            assertEquals(DomainFailure.TEMPORARY, degraded.warning)
            assertEquals(saved, database.realtimeQueries.cacheByKey(key("roma")).executeAsOne())
            assertEquals(complete.value, read(database, "roma"))
            fixture.failItalo = false
            assertEquals(87, assertIs<DataResult.Data<List<Station>>>(repository.searchStations("roma")).value.size)
        }
    }

    @Test fun oldIdentityVersionTwoSnapshotIsRevalidatedForCompletenessWithoutChangingIds() = runBlocking {
        withRepository { database, fixture, repository, clock ->
            val old = StationRecord("location:trenitalia-journeys:830008409", "Roma Termini", "UNKNOWN", 2)
            database.realtimeQueries.putCache(key("roma"), Json.encodeToString(listOf(old)), clock.now().toEpochMilliseconds(), null)
            val result = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("roma"))
            assertEquals(87, result.value.size)
            assertTrue(result.value.any { it.id == old.model().id })
            assertEquals(listOf("roma"), fixture.locationQueries)
            assertEquals(result.value, read(database, "roma"))
        }
    }

    @Test fun completeEmptyResponseIsReturnedButNeverPersistedAsDayLongNegative() = runBlocking {
        withRepository { database, fixture, repository, _ ->
            val result = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("unknown place"))
            assertTrue(result.value.isEmpty())
            assertNull(result.warning)
            assertIs<DataFreshness.Fresh>(result.freshness)
            assertNull(database.realtimeQueries.cacheByKey(key("unknown place")).executeAsOneOrNull())
            assertEquals(listOf("unknown place", "unknown"), fixture.locationQueries)
            assertEquals(fixture.locationQueries, fixture.italoQueries)
            repository.searchStations("unknown place")
            assertEquals(2, fixture.locationQueries.size, "Adapter negative TTL still bounds repeated HTTP")
            assertEquals(2, fixture.italoQueries.size)
        }
    }

    @Test fun allFailedProvidersReturnFailureAndPreserveCompleteCache() = runBlocking {
        withRepository { database, fixture, repository, clock ->
            fixture.failTrenitalia = true; fixture.failItalo = true
            assertEquals(DataResult.Failure(DomainFailure.TEMPORARY), repository.searchStations("roma"))
            assertNull(database.realtimeQueries.cacheByKey(key("roma")).executeAsOneOrNull())
            fixture.failTrenitalia = false; fixture.failItalo = false
            val complete = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("roma"))
            val saved = database.realtimeQueries.cacheByKey(key("roma")).executeAsOne()
            clock.instant += 25.hours
            fixture.failTrenitalia = true; fixture.failItalo = true
            val stale = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("roma"))
            assertEquals(complete.value, stale.value)
            assertIs<DataFreshness.Stale>(stale.freshness)
            assertEquals(DomainFailure.TEMPORARY, stale.warning)
            assertEquals(saved, database.realtimeQueries.cacheByKey(key("roma")).executeAsOne())
        }
    }

    private suspend fun withRepository(block: suspend (TrenifyDatabase, StationAutocompleteFixture, SqlDelightJourneyRepository, MutableClock) -> Unit) {
        val driver = createRepositoryTestDriver()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = MutableClock()
        val fixture = StationAutocompleteFixture(clock)
        try {
            val database = TrenifyDatabase(driver)
            block(database, fixture, SqlDelightJourneyRepository(database, fixture.provider, scope, clock = clock), clock)
        } finally { scope.cancel(); fixture.close(); driver.close() }
    }

    private fun key(query: String) = "journey-stations-v1:${normalizeStationSearchQuery(query)}"
    private fun read(database: TrenifyDatabase, query: String) = Json.decodeFromString<List<StationRecord>>(
        database.realtimeQueries.cacheByKey(key(query)).executeAsOne().payload).map(StationRecord::model)
}
