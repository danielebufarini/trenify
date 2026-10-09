package it.danielebufarini.trenify.data

import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.testing.*
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.*
import kotlin.test.*

class StationSuggestionCacheTest {
    @Test fun priorCategoryAwareCacheMustUpgradeResultSetDependentIdentityBeforeFreshReads() = runBlocking {
        val driver = createRepositoryTestDriver()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = MutableClock()
        val fixture = HomonymousStationFixture(false, clock)
        try {
            val database = TrenifyDatabase(driver)
            // The reviewed implementation wrote category metadata but a name ID for a sole twin.
            val old = StationRecord("station:paternion-feistritz", "Paternion-Feistritz", "UNKNOWN")
            database.realtimeQueries.putCache("journey-stations-v1:paternion", Json.encodeToString(listOf(old)),
                clock.now().toEpochMilliseconds(), null)
            val repository = SqlDelightJourneyRepository(database, fixture.provider, scope, clock = clock)
            val upgraded = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("paternion")).value
            assertEquals(listOf("location:trenitalia-journeys:810002129", "location:trenitalia-journeys:810002133"), upgraded.map { it.id.value })
            assertEquals(listOf("paternion"), fixture.queries)
            assertEquals(upgraded, assertIs<DataResult.Data<List<Station>>>(repository.searchStations("paternion")).value)
            assertEquals(listOf("paternion"), fixture.queries, "Only the first online upgrade may fetch")
        } finally { scope.cancel(); fixture.close(); driver.close() }
    }

    @Test fun onlineLegacyCacheRefreshRecoversCategoryAndPreviouslyTruncatedCandidates() = runBlocking {
        val driver = createRepositoryTestDriver()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fixture = it.danielebufarini.trenify.core.testing.StationAutocompleteFixture()
        try {
            val database = TrenifyDatabase(driver)
            val stored = listOf("CASIGNANA VILLA ROMANA", "Roma Anagnina", "Roma Termini", "Roma Tiburtina")
                .map { StationRecord("station:${normalizeStationQuery(it)}", it) }
            database.realtimeQueries.putCache("journey-stations-v1:roma", Json.encodeToString(stored),
                kotlin.time.Clock.System.now().toEpochMilliseconds(), null)
            val repository = SqlDelightJourneyRepository(database, fixture.provider, scope)
            val stations = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("roma")).value
            assertEquals(listOf("Roma Termini", "Roma Tiburtina"), stations.take(2).map { it.name })
            assertEquals(87, stations.size)
            assertEquals(listOf("roma"), fixture.locationQueries)
            assertEquals(stations, assertIs<DataResult.Data<List<Station>>>(repository.searchStations("roma")).value)
            assertEquals(listOf("roma"), fixture.locationQueries)
        } finally { scope.cancel(); fixture.close(); driver.close() }
    }
    @Test fun existingCacheRanksCityPrefixBeforeUnrelatedSubstring() = runTest {
        val driver = createRepositoryTestDriver()
        try {
            val database = TrenifyDatabase(driver)
            val clock = MutableClock()
            val names = listOf("CASIGNANA VILLA ROMANA", "CASTEL ROMANO OUTLET", "Cesano Di Roma", "Roma Termini", "Roma Tiburtina")
            val stored = names.map { StationRecord("station:${normalizeStationQuery(it)}", it) }
            database.realtimeQueries.putCache("journey-stations-v1:roma", Json.encodeToString(stored), clock.now().toEpochMilliseconds(), null)
            val repository = SqlDelightJourneyRepository(database, FakeJourneyProvider(clock), backgroundScope,
                online = { false }, clock = clock, dispatcher = StandardTestDispatcher(testScheduler))
            val stations = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("roma")).value
            assertEquals(listOf("Roma Termini", "Roma Tiburtina"), stations.take(2).map { it.name })
            assertEquals(names.toSet(), stations.map { it.name }.toSet())
        } finally { driver.close() }
    }
    @Test fun existingAlphabeticalCacheKeepsExactStationVisibleOnlineAndOffline() = runTest {
        val driver = createRepositoryTestDriver()
        try {
            val database = TrenifyDatabase(driver)
            val clock = MutableClock()
            val stored = listOf("Breternitz", "Cisternino", "CISTERNINO CITTA'", "Markt Paternion",
                "Osternienburg", "Paternion-Feistritz", "Terni", "Terni Cospea", "Ternitz")
                .map { StationRecord("station:${normalizeStationQuery(it)}", it) }
            database.realtimeQueries.putCache("journey-stations-v1:terni", Json.encodeToString(stored),
                clock.now().toEpochMilliseconds(), null)
            for (online in listOf(true, false)) {
                val repository = SqlDelightJourneyRepository(database, FakeJourneyProvider(clock), backgroundScope,
                    online = { online }, clock = clock, dispatcher = StandardTestDispatcher(testScheduler))
                val result = assertIs<DataResult.Data<List<Station>>>(repository.searchStations(" Terni "))
                assertEquals(StationId("station:terni"), result.value.first().id)
                assertTrue(result.value.take(5).any { it.id == StationId("station:terni") })
                assertEquals(stored.map(StationRecord::model).toSet(), result.value.toSet())
            }
        } finally {
            driver.close()
        }
    }
}
