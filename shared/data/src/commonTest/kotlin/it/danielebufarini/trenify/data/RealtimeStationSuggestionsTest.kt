package it.danielebufarini.trenify.data

import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.provider.api.*
import it.danielebufarini.trenify.core.testing.*
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.test.*
import kotlin.test.*

class RealtimeStationSuggestionsTest {
    @Test fun providerAndCacheRankingPreserveOpaqueSelectionMappingsAcrossRefreshAndReopen() = runTest {
        val driver = createRepositoryTestDriver()
        try {
            val database = TrenifyDatabase(driver)
            val clock = MutableClock()
            var calls = 0
            val candidates = listOf("Cisternino", "Terni", "Terni Cospea", "Ternitz").mapIndexed { index, name ->
                ProviderStation(ExternalStationRef("opaque-$index"), name)
            }
            val provider = object : TrainRealtimeProvider by FakeRealtimeProvider(clock) {
                override suspend fun searchStations(query: String, limit: Int): ProviderResult<List<ProviderStation>> {
                    calls++
                    assertEquals(100, limit)
                    return ProviderResult.Success(candidates, ProviderMetadata(id, clock.now()))
                }
            }
            fun repository(online: Boolean = true) = RealtimeRepositories(database, listOf(provider), backgroundScope,
                online = { online }, clock = clock, dispatcher = StandardTestDispatcher(testScheduler))
            val first = assertIs<DataResult.Data<List<Station>>>(repository().searchStations("terni")).value
            assertEquals(listOf("Terni", "Terni Cospea", "Ternitz", "Cisternino"), first.map { it.name })
            assertEquals("opaque-1", database.realtimeQueries.externalForStation(first.first().id.value, provider.id.value).executeAsOne())
            assertEquals(first, assertIs<DataResult.Data<List<Station>>>(repository().searchStations("TERNI")).value)
            assertEquals(1, calls)
            assertEquals(first, assertIs<DataResult.Data<List<Station>>>(repository(false).searchStations("terni")).value)
            assertEquals(first, assertIs<DataResult.Data<List<Station>>>(repository().searchStations("terni", force = true)).value)
            assertEquals(2, calls)
        } finally { driver.close() }
    }
    /** Synthetic overflow rows prove that SQL cannot cut off an exact minor-station match before ranking. */
    @Test fun offlineStationAutocompleteRanksAllLocalCandidatesBeforeAnyDisplayLimit() = runTest {
        val driver = createRepositoryTestDriver()
        try {
            val database = TrenifyDatabase(driver)
            repeat(40) { database.realtimeQueries.putStation("minor-$it", "Cisternino $it", "cisternino $it", 0) }
            database.realtimeQueries.putStation("terni-id", "Terni", "terni", 0)
            val repository = RealtimeRepositories(database, listOf(FakeRealtimeProvider()), backgroundScope,
                online = { false }, dispatcher = StandardTestDispatcher(testScheduler))
            val result = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("terni"))
            assertEquals(StationId("terni-id"), result.value.first().id)
            assertEquals(41, result.value.size)
            assertEquals(DomainFailure.OFFLINE, result.warning)
            // Preserve the existing bounded query and its audit contract for other callers.
            assertEquals(30, database.realtimeQueries.searchStations("terni").executeAsList().size)
        } finally { driver.close() }
    }
}
