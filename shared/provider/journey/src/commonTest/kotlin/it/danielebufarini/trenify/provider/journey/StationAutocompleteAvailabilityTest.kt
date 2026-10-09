package it.danielebufarini.trenify.provider.journey

import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.provider.api.*
import it.danielebufarini.trenify.core.testing.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import kotlin.time.Duration.Companion.hours

class StationAutocompleteAvailabilityTest {
    @Test fun recordedColdMultiwordResponsesResolveWithBoundedFallback() = runBlocking {
        for (query in listOf("narni amelia", "Narni-Amelia", "roma tib")) {
            val clock = MutableClock()
            val fixture = StationAutocompleteFixture(clock)
            try {
                suspend fun lookup() = assertIs<ProviderResult.Success<List<Station>>>(fixture.provider.searchStations(query)).value
                val cold = lookup()
                when (query) {
                    "narni amelia", "Narni-Amelia" -> assertEquals(listOf(StationId("location:trenitalia-journeys:830007230")), cold.map { it.id })
                    "roma tib" -> {
                        assertEquals(3, cold.size)
                        assertEquals("Roma Tiburtina", cold.first().name)
                        assertEquals(StationId("location:italo-journeys:RTB"), cold.first().id)
                        assertTrue(cold.any { it.id == StationId("location:trenitalia-journeys:830008217") })
                    }
                }
                assertEquals(cold, lookup())
                val requests = if (query == "narni amelia") 2 else 1
                assertEquals(requests, fixture.locationQueries.size)
                assertEquals(requests, fixture.italoQueries.size)
                clock.instant += 25.hours
                assertEquals(cold, lookup(), "Expiry must not manufacture different query support or station identities")
                assertEquals(2 * requests, fixture.locationQueries.size)
                assertEquals(2 * requests, fixture.italoQueries.size)
                if (query != "roma tib") {
                    assertIs<ProviderResult.Success<JourneySearchResult>>(fixture.provider.search(
                        JourneySearchRequest(Station(StationId("station:milano centrale"), "Milano Centrale"), cold.single(), clock.now())))
                    assertEquals(830007230L, fixture.arrivalLocationIds.single())
                }
            } finally { fixture.close() }
        }
    }

    @Test fun cachedFirstTokenFallbackIsBoundedAndExpiresWithoutStaleCandidates() = runBlocking {
        val clock = MutableClock()
        val fixture = StationAutocompleteFixture(clock)
        try {
            suspend fun lookup(query: String) = assertIs<ProviderResult.Success<List<Station>>>(fixture.provider.searchStations(query)).value
            val cold = lookup("narni amelia")
            val full = lookup("narni").single { it.name == "Narni-Amelia" }
            assertEquals(listOf(full), cold)
            assertEquals(listOf(full), lookup("narni amelia"))
            assertEquals(listOf("narni amelia", "narni"), fixture.locationQueries)
            assertIs<ProviderResult.Success<JourneySearchResult>>(fixture.provider.search(
                JourneySearchRequest(Station(StationId("station:milano centrale"), "Milano Centrale"), full, clock.now())))
            assertEquals(830007230L, fixture.arrivalLocationIds.single())
            clock.instant += 25.hours
            assertEquals(listOf(full), lookup("narni amelia"), "Expired token data must be refetched with stable identities")
            assertEquals(listOf("narni amelia", "narni", "milano centrale", "narni-amelia", "narni amelia", "narni"), fixture.locationQueries)
        } finally { fixture.close() }
    }

    @Test fun providerFailureMatrixKeepsTextualRankingCoverageMetadataAndSelection() = runBlocking {
        for ((failTrenitalia, failItalo) in listOf(false to false, false to true, true to false, true to true)) {
            val clock = MutableClock()
            val fixture = StationAutocompleteFixture(clock)
            fixture.failTrenitalia = failTrenitalia; fixture.failItalo = failItalo
            try {
                val response = fixture.provider.searchStations("roma")
                if (failTrenitalia && failItalo) {
                    assertIs<ProviderResult.Unavailable>(response)
                    continue
                }
                val stations = assertIs<ProviderResult.Success<List<Station>>>(response).value
                val expectedCount = if (failTrenitalia) 2 else if (failItalo) 85 else 87
                assertEquals(expectedCount, stations.size)
                val termini = stations.filter { it.name == "Roma Termini" }
                val tiburtina = stations.filter { it.name == "Roma Tiburtina" }
                assertTrue(termini.isNotEmpty()); assertTrue(tiburtina.isNotEmpty())
                val destination = termini.first()
                if (failItalo) {
                    assertTrue(stations.all { it.searchCategory == StationSearchCategory.UNKNOWN })
                    assertEquals("Roma Anagnina", stations.first().name)
                    assertTrue(stations.indexOfFirst { it.name == "Roma Termini" } > 4,
                        "Without category evidence do not fake a privileged major-station ordering")
                    assertTrue(stations.take(5).all { it.name.startsWith("Roma ") })
                    assertTrue(stations.indexOfFirst { it.name.startsWith("Cesano") } > stations.indexOf(destination))
                    assertTrue(stations.any { it.name == "Fabrica Di Roma" })
                    assertTrue(stations.any { it.name == "CASIGNANA VILLA ROMANA" })
                } else {
                    assertEquals(listOf("Roma Termini", "Roma Tiburtina"), stations.take(2).map { it.name })
                    assertTrue(stations.take(2).all { it.searchCategory == StationSearchCategory.RAILWAY })
                    if (failTrenitalia) assertTrue(stations.all { it.searchCategory == StationSearchCategory.RAILWAY })
                }
                assertIs<ProviderResult.Success<JourneySearchResult>>(fixture.provider.search(
                    JourneySearchRequest(Station(StationId("station:milano centrale"), "Milano Centrale"), destination, clock.now())))
                if (!failTrenitalia) assertEquals(830008409L, fixture.arrivalLocationIds.single())
                if (!failItalo) assertEquals("RMT", fixture.italoArrivalStationCodes.single())
                println("AVAILABILITY trenitalia=${!failTrenitalia} italo=${!failItalo}: ${stations.take(5).map { it.name }}; Termini position=${stations.indexOf(destination) + 1}")
            } finally { fixture.close() }
        }
    }
}
