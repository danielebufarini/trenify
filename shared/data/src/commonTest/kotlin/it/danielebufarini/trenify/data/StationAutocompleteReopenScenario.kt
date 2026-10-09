package it.danielebufarini.trenify.data

import app.cash.sqldelight.db.SqlDriver
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.testing.*
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours

internal suspend fun verifyStationAutocompleteNegativeAfterDriverReopen(open: (create: Boolean) -> SqlDriver) {
    val clock = MutableClock()
    for (stage in 0..2) {
        val driver = open(stage == 0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fixture = StationAutocompleteFixture(clock)
        try {
            val database = TrenifyDatabase(driver)
            if (stage == 0) {
                database.realtimeQueries.putCache("journey-stations-v1:narni amelia", "[]", clock.now().toEpochMilliseconds(), null)
            } else {
                val repository = SqlDelightJourneyRepository(database, fixture.provider, scope, clock = clock)
                val result = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("narni amelia"))
                assertEquals(listOf(StationId("location:trenitalia-journeys:830007230")), result.value.map { it.id })
                assertEquals(listOf("Narni-Amelia"), result.value.map { it.name })
                assertIs<DataFreshness.Fresh>(result.freshness)
                val expected = if (stage == 1) listOf("narni amelia", "narni") else emptyList()
                assertEquals(expected, fixture.locationQueries)
                assertEquals(expected, fixture.italoQueries)
            }
        } finally { scope.cancel(); fixture.close(); driver.close() }
    }
}

/** Real file drivers, fresh adapters and repositories at each restart; the database is never cleared. */
internal suspend fun verifyStationAutocompleteRecoveryAfterDriverReopen(open: (create: Boolean) -> SqlDriver) {
    val clock = MutableClock()
    var narni = emptyList<Station>()
    var roma = emptyList<Station>()
    for (stage in 0..3) {
        val driver = open(stage == 0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fixture = StationAutocompleteFixture(clock)
        try {
            val database = TrenifyDatabase(driver)
            val repository = SqlDelightJourneyRepository(database, fixture.provider, scope,
                online = { stage != 3 }, clock = clock)
            when (stage) {
                0 -> {
                    database.realtimeQueries.putCache("journey-stations-v1:narni amelia", "[]", clock.now().toEpochMilliseconds(), null)
                    fixture.failItalo = true
                    val degraded = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("roma"))
                    assertEquals(85, degraded.value.size)
                    assertEquals(DomainFailure.TEMPORARY, degraded.warning)
                    assertNull(database.realtimeQueries.cacheByKey("journey-stations-v1:roma").executeAsOneOrNull())
                }
                1 -> {
                    val result = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("narni amelia"))
                    narni = result.value
                    assertEquals(listOf(StationId("location:trenitalia-journeys:830007230")), narni.map { it.id })
                    assertEquals(listOf("narni amelia", "narni"), fixture.locationQueries)
                    assertEquals(fixture.locationQueries, fixture.italoQueries)
                    val recovered = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("roma"))
                    roma = recovered.value
                    assertEquals(87, roma.size)
                    assertTrue(roma.any { it.id == StationId("location:italo-journeys:RMT") })
                    assertIs<DataFreshness.Fresh>(recovered.freshness)
                    assertNull(recovered.warning)
                    assertEquals("roma", fixture.italoQueries.last())
                    assertNotNull(database.realtimeQueries.cacheByKey("journey-stations-v1:roma").executeAsOneOrNull())
                }
                2 -> {
                    fixture.failTrenitalia = true; fixture.failItalo = true
                    for ((query, expected) in listOf("narni amelia" to narni, "roma" to roma)) {
                        val cached = assertIs<DataResult.Data<List<Station>>>(repository.searchStations(query))
                        assertEquals(expected, cached.value)
                        assertIs<DataFreshness.Fresh>(cached.freshness)
                        assertNull(cached.warning)
                    }
                    assertTrue(fixture.locationQueries.isEmpty())
                    assertTrue(fixture.italoQueries.isEmpty())
                    val saved = database.realtimeQueries.cacheByKey("journey-stations-v1:roma").executeAsOne()
                    clock.instant += 25.hours
                    fixture.failTrenitalia = false
                    val degraded = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("roma"))
                    assertEquals(85, degraded.value.size)
                    assertEquals(DomainFailure.TEMPORARY, degraded.warning)
                    assertEquals(saved, database.realtimeQueries.cacheByKey("journey-stations-v1:roma").executeAsOne())
                }
                3 -> {
                    val retained = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("roma"))
                    assertEquals(roma, retained.value)
                    assertIs<DataFreshness.Stale>(retained.freshness)
                    assertEquals(DomainFailure.OFFLINE, retained.warning)
                    assertEquals(narni, assertIs<DataResult.Data<List<Station>>>(repository.searchStations("narni amelia")).value)
                    assertTrue(fixture.locationQueries.isEmpty())
                    assertTrue(fixture.italoQueries.isEmpty())
                }
            }
        } finally { scope.cancel(); fixture.close(); driver.close() }
    }
}
