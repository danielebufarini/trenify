package it.danielebufarini.trenify.data

import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.testing.*
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class StationSuggestionCacheTest {
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
