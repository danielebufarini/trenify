package it.danielebufarini.trenify.data

import app.cash.sqldelight.db.SqlDriver
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.provider.api.*
import it.danielebufarini.trenify.core.testing.*
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

/** Both native/JVM tests close the real file and restart the driver, repository and adapter. */
internal suspend fun verifyStationIdentityAfterDriverReopen(open: (italo: Boolean, create: Boolean) -> SqlDriver) {
    for (italo in listOf(false, true)) {
        val clock = MutableClock()
        val origin = Station(StationId("station:milano centrale"), "Milano Centrale")
        val legacy = Station(StationId("station:roma termini"), "Roma Termini")
        val legacyRequest = JourneySearchRequest(origin, legacy, clock.now())
        val ambiguous = Station(StationId("station:paternion-feistritz"), "Paternion-Feistritz")
        val ambiguousRequest = JourneySearchRequest(origin, ambiguous, clock.now())
        val firstDriver = open(italo, true)
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstFixture = HomonymousStationFixture(italo, clock)
        val twins: List<Station>
        try {
            val database = TrenifyDatabase(firstDriver)
            // Pre-existing name-derived IDs remain readable, without rewriting stored selections.
            database.realtimeQueries.putCache("journey-stations-v1:roma termini",
                Json.encodeToString(listOf(StationRecord(legacy.id.value, legacy.name, "UNKNOWN"))),
                clock.now().toEpochMilliseconds(), null)
            var online = false
            val repository = SqlDelightJourneyRepository(database, firstFixture.provider, firstScope,
                online = { online }, clock = clock)
            assertEquals(listOf(legacy), assertIs<DataResult.Data<List<Station>>>(repository.searchStations("Roma Termini")).value)
            online = true
            assertIs<DataResult.Data<JourneySearchResult>>(repository.search(legacyRequest))
            twins = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("paternion")).value
            assertEquals(2, twins.size)
            assertNotEquals(twins[0].id, twins[1].id)
            for (destination in twins) {
                val result = assertIs<DataResult.Data<JourneySearchResult>>(repository.search(JourneySearchRequest(origin, destination, clock.now())))
                assertFalse(result.value.partial)
            }
            assertEquals(listOf(if (italo) "RMT" else "830008409", firstFixture.firstRef, firstFixture.secondRef), firstFixture.selectedRefs)
            assertEquals(DomainFailure.UNSUPPORTED, assertIs<DataResult.Failure>(repository.search(ambiguousRequest)).error)
            assertEquals(3, firstFixture.selectedRefs.size)
        } finally { firstScope.cancel(); firstFixture.close(); firstDriver.close() }

        val secondDriver = open(italo, false)
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val restartedFixture = HomonymousStationFixture(italo, clock)
        var online = false
        try {
            val database = TrenifyDatabase(secondDriver)
            val repository = SqlDelightJourneyRepository(database, restartedFixture.provider, secondScope,
                online = { online }, clock = clock)
            val offline = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("paternion"))
            assertEquals(twins, offline.value)
            assertEquals(DomainFailure.OFFLINE, offline.warning)
            assertEquals(listOf(legacy), assertIs<DataResult.Data<List<Station>>>(repository.searchStations("Roma Termini")).value)
            assertIs<DataResult.Data<JourneySearchResult>>(repository.search(legacyRequest))
            assertTrue(restartedFixture.queries.isEmpty())
            assertTrue(restartedFixture.selectedRefs.isEmpty())
            online = true
            assertEquals(twins, assertIs<DataResult.Data<List<Station>>>(repository.searchStations("paternion")).value)
            assertTrue(restartedFixture.queries.isEmpty())
            val only = assertIs<ProviderResult.Success<List<Station>>>(restartedFixture.provider.searchStations("feistritz")).value.single()
            assertEquals(twins.first().id, only.id)
            val requests = listOf(only, twins.last()).map { JourneySearchRequest(origin, it, clock.now()) }
            assertNotEquals(requests[0].key, requests[1].key)
            assertNotEquals(ambiguousRequest.key, requests[0].key)
            for (request in requests) assertIs<DataResult.Data<JourneySearchResult>>(repository.search(request))
            assertTrue(restartedFixture.selectedRefs.isEmpty(), "Reopened complete caches must issue no journey requests")
            for (request in requests) assertIs<DataResult.Data<JourneySearchResult>>(repository.search(request, force = true))
            assertEquals(listOf(restartedFixture.firstRef, restartedFixture.secondRef), restartedFixture.selectedRefs)
            assertEquals(DomainFailure.UNSUPPORTED, assertIs<DataResult.Failure>(repository.search(ambiguousRequest)).error)
            assertEquals(2, restartedFixture.selectedRefs.size, "No ambiguous legacy cache or arbitrary provider selection")
        } finally { secondScope.cancel(); restartedFixture.close(); secondDriver.close() }
    }
}
