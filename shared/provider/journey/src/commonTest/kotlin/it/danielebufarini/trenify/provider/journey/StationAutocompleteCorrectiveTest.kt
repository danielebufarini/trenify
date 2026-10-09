package it.danielebufarini.trenify.provider.journey

import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.provider.api.*
import it.danielebufarini.trenify.core.testing.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class StationAutocompleteCorrectiveTest {
    @Test fun coldMultiwordSearchResolvesWithoutPriorPrefixLookup() = runBlocking {
        val fixture = StationAutocompleteFixture(MutableClock())
        try {
            val result = assertIs<ProviderResult.Success<List<Station>>>(fixture.provider.searchStations("narni amelia"))
            assertEquals(listOf("Narni-Amelia"), result.value.map { it.name })
            assertEquals(listOf(StationId("location:trenitalia-journeys:830007230")), result.value.map { it.id })
            assertTrue(result.complete)
            assertEquals(listOf("narni amelia", "narni"), fixture.locationQueries)
            assertEquals(listOf("narni amelia", "narni"), fixture.italoQueries)
            assertEquals(result.value, assertIs<ProviderResult.Success<List<Station>>>(fixture.provider.searchStations("narni amelia")).value)
            assertEquals(2, fixture.locationQueries.size)
            assertEquals(2, fixture.italoQueries.size)
        } finally { fixture.close() }
    }

    @Test fun successfulColdPrimaryQueriesNeverFetchFirstToken() = runBlocking {
        for (query in listOf("Narni-Amelia", "roma tib")) {
            val fixture = StationAutocompleteFixture(MutableClock())
            try {
                val result = assertIs<ProviderResult.Success<List<Station>>>(fixture.provider.searchStations(query))
                if (query == "Narni-Amelia") {
                    assertEquals(listOf(StationId("location:trenitalia-journeys:830007230")), result.value.map { it.id })
                } else {
                    assertEquals("Roma Tiburtina", result.value.first().name)
                    assertEquals(StationId("location:italo-journeys:RTB"), result.value.first().id)
                    assertTrue(result.value.any { it.id == StationId("location:trenitalia-journeys:830008217") })
                    assertEquals(3, result.value.size)
                }
                assertTrue(result.complete)
                assertEquals(listOf(normalizeStationSearchQuery(query)), fixture.locationQueries)
                assertEquals(listOf(normalizeStationSearchQuery(query)), fixture.italoQueries)
            } finally { fixture.close() }
        }
    }

    @Test fun fallbackUsesStrictOrderedTokenPrefixesAndPreservesHomonymousIdentities() = runBlocking {
        for (query in listOf("narni am", "narni-am", "NARNI—AM", "  narni   am  ")) {
            val a = source("trenitalia", listOf(
                Station(StationId("location:trenitalia-journeys:1"), "Narni-Amelia"),
                Station(StationId("location:trenitalia-journeys:2"), "Narni-Amelia"),
                station("Narni Centro"), station("Amelia Narni"), station("Narni unrelated Amelia"),
                station("Narnish unrelated"), station("Other Amelia"),
            ), onlyQuery = "narni")
            val b = source("italo", emptyList())
            val result = assertIs<ProviderResult.Success<List<Station>>>(MultiSourceJourneyProvider(listOf(a, b)).searchStations(query))
            assertEquals(listOf("location:trenitalia-journeys:1", "location:trenitalia-journeys:2"), result.value.map { it.id.value })
            assertEquals(listOf(query, "narni"), a.queries)
            assertEquals(listOf(query, "narni"), b.queries)
        }
    }

    @Test fun unmatchedFallbackIsBoundedAndSingleTokenNeverExpands() = runBlocking {
        val a = source("trenitalia", listOf(station("Narni Centro")), onlyQuery = "narni")
        val b = source("italo", emptyList())
        val provider = MultiSourceJourneyProvider(listOf(a, b))
        assertTrue(assertIs<ProviderResult.Success<List<Station>>>(provider.searchStations("narni nowhere else")).value.isEmpty())
        assertEquals(listOf("narni nowhere else", "narni"), a.queries)
        assertEquals(a.queries, b.queries)
        provider.searchStations("unknown")
        provider.searchStations("a unknown")
        assertEquals(listOf("narni nowhere else", "narni", "unknown", "a unknown"), a.queries)
    }

    @Test fun aggregationMatrixIncludesSuccessfulEmptyAndIncompleteResponses() = runBlocking {
        for (aSuccess in listOf(true, false)) for (bSuccess in listOf(true, false)) {
            for (empty in listOf(true, false)) {
                val a = source("trenitalia", if (empty) emptyList() else listOf(station("Roma A")), failed = !aSuccess)
                val b = source("italo", if (empty) emptyList() else listOf(station("Roma B")), failed = !bSuccess)
                val result = MultiSourceJourneyProvider(listOf(a, b)).searchStations("roma")
                if (!aSuccess && !bSuccess) {
                    assertEquals(ProviderResult.Unavailable(true, ProviderFailure.UNAVAILABLE), result)
                } else {
                    val success = assertIs<ProviderResult.Success<List<Station>>>(result)
                    assertEquals(aSuccess && bSuccess, success.complete, "Trenitalia=$aSuccess Italo=$bSuccess empty=$empty")
                    assertEquals(if (empty) emptyList() else listOfNotNull(
                        "Roma A".takeIf { aSuccess }, "Roma B".takeIf { bSuccess },
                    ), success.value.map { it.name })
                }
                assertEquals(listOf("roma"), a.queries)
                assertEquals(listOf("roma"), b.queries)
            }
        }
        val a = source("trenitalia", listOf(station("Roma A")), complete = false)
        val b = source("italo", emptyList())
        assertFalse(assertIs<ProviderResult.Success<List<Station>>>(MultiSourceJourneyProvider(listOf(a, b)).searchStations("roma")).complete)
    }

    @Test fun fallbackFailureRemainsDegradedAndAllFailuresRemainFailures() = runBlocking {
        val a = source("trenitalia", listOf(station("Narni-Amelia")), onlyQuery = "narni")
        val b = source("italo", emptyList(), failed = true)
        val result = assertIs<ProviderResult.Success<List<Station>>>(MultiSourceJourneyProvider(listOf(a, b)).searchStations("narni amelia"))
        assertEquals(listOf("Narni-Amelia"), result.value.map { it.name })
        assertFalse(result.complete)
        assertEquals(listOf("narni amelia", "narni"), a.queries)
        // A failed primary is not immediately retried with another spelling.
        assertEquals(listOf("narni amelia"), b.queries)
        val failedA = source("trenitalia", emptyList(), failed = true)
        val failedB = source("italo", emptyList(), failed = true)
        assertIs<ProviderResult.Unavailable>(MultiSourceJourneyProvider(listOf(failedA, failedB)).searchStations("narni amelia"))
        assertEquals(listOf("narni amelia"), failedA.queries)
        assertEquals(failedA.queries, failedB.queries)
    }

    @Test fun successfulEmptyPrimarySurvivesFallbackFailureAsDegradedSuccess() = runBlocking {
        for (bFailed in listOf(false, true)) {
            val a = source("trenitalia", emptyList(), failedQueries = setOf("narni"))
            val b = source("italo", emptyList(), failed = bFailed, failedQueries = setOf("narni"))
            val result = assertIs<ProviderResult.Success<List<Station>>>(
                MultiSourceJourneyProvider(listOf(a, b)).searchStations("narni nowhere"))
            assertTrue(result.value.isEmpty())
            assertFalse(result.complete, "A valid empty primary remains available, but failed fallback cannot certify absence")
            assertEquals(listOf("narni nowhere", "narni"), a.queries)
            assertEquals(if (bFailed) listOf("narni nowhere") else a.queries, b.queries)
        }
    }

    private fun source(name: String, stations: List<Station>, onlyQuery: String? = null, failed: Boolean = false,
        complete: Boolean = true, failedQueries: Set<String> = emptySet()) =
        object : JourneySource {
            override val id = ProviderId(name)
            override val operators = listOf(Operator(name))
            override val services = listOf("Rail")
            val queries = mutableListOf<String>()
            override suspend fun searchStations(query: String): ProviderResult<List<Station>> {
                queries += query
                return if (failed || query in failedQueries) ProviderResult.Unavailable(true, ProviderFailure.UNAVAILABLE)
                else ProviderResult.Success(if (onlyQuery == null || query == onlyQuery) stations else emptyList(),
                    ProviderMetadata(id, MutableClock().now()), complete)
            }
            override suspend fun search(request: JourneySearchRequest): ProviderResult<JourneySearchResult> = ProviderResult.NotFound
        }
}
