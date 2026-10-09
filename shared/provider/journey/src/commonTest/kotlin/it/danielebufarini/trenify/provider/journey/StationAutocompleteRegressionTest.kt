package it.danielebufarini.trenify.provider.journey

import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.provider.api.*
import it.danielebufarini.trenify.core.testing.StationAutocompleteFixture
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class StationAutocompleteRegressionTest {
    @Test fun recordedRomaResponseKeepsRailwaySuggestionsInTheRenderedFive() = runBlocking {
        val fixture = StationAutocompleteFixture(clock)
        try {
            val stations = assertIs<ProviderResult.Success<List<Station>>>(fixture.provider.searchStations("roma")).value
            assertEquals(listOf("Roma Termini", "Roma Tiburtina"), stations.take(2).map { it.name })
            assertTrue(stations.any { it.name == "CASIGNANA VILLA ROMANA" })
            assertTrue(stations.any { it.name == "Fabrica Di Roma" }, "Candidate after the old provider limit must survive retrieval")
        } finally { fixture.close() }
    }

    @Test fun recordedAcceptanceQueriesPreserveCoverageCategoryAndOrdering() = runBlocking {
        val fixture = StationAutocompleteFixture(clock)
        try {
            val expected = mapOf(
                "roma" to listOf("Roma Termini", "Roma Tiburtina"),
                "milano" to listOf("Milano Centrale", "Milano Porta Garibaldi", "Milano Rho Fiera", "Milano Rogoredo"),
                "firenze" to listOf("FIRENZE", "Firenze S.M.Novella"), // Exact match remains first, even with unknown category.
                "torino" to listOf("Torino Porta Nuova", "Torino Porta Susa"),
                "terni" to listOf("Terni", "Terni Cospea", "Ternitz"),
                "tiburtina" to listOf("Roma Tiburtina"),
                "villa romana" to listOf("CASIGNANA VILLA ROMANA"),
                "roma tib" to listOf("Roma Tiburtina"),
                "spoleto" to listOf("Spoleto", "SPOLETO CENTRO", "Baiano Di Spoleto"),
                "narni" to listOf("Narni-Amelia", "NARNI CENTRO"),
                "castelfranco" to listOf("CASTELFRANCO DI SOPRA", "CASTELFRANCO DI SOTTO", "Castelfranco Emilia", "Castelfranco Veneto"),
            )
            for ((query, first) in expected) {
                val stations = assertIs<ProviderResult.Success<List<Station>>>(fixture.provider.searchStations(query)).value
                assertEquals(first, stations.take(first.size).map { it.name }, query)
                println("CATALOG $query (${stations.size}): ${stations.take(5).map { it.name }}")
            }
            val roma = assertIs<ProviderResult.Success<List<Station>>>(fixture.provider.searchStations("roma")).value
            for (query in listOf("ROMA", "  roma  ")) {
                assertEquals(roma, assertIs<ProviderResult.Success<List<Station>>>(fixture.provider.searchStations(query)).value)
            }
            assertEquals(StationSearchCategory.RAILWAY, roma.first().searchCategory)
            assertEquals(StationSearchCategory.UNKNOWN, roma.single { it.name == "Roma Anagnina" }.searchCategory)
            assertEquals(87, roma.size) // 85 LeFrecce locations + two distinct Italo catalog references.
            val terni = assertIs<ProviderResult.Success<List<Station>>>(fixture.provider.searchStations("terni")).value
            assertEquals(terni, assertIs<ProviderResult.Success<List<Station>>>(fixture.provider.searchStations(" TE\u0300RNI ")).value)
            val centrale = assertIs<ProviderResult.Success<List<Station>>>(fixture.provider.searchStations("centrale")).value
            assertTrue(centrale.take(5).all { "Centrale" in it.name })
            assertTrue(centrale.any { it.name == "Porto Empedocle Centrale" })
        } finally { fixture.close() }
    }

    @Test fun realCatalogSelectionUsesTheSelectedProviderLocationId() = runBlocking {
        for ((query, name, wireId) in listOf(
            Triple("roma", "Roma Termini", 830008409L), Triple("roma", "Roma Tiburtina", 830008217L),
            Triple("terni", "Terni", 830007226L), Triple("narni", "Narni-Amelia", 830007230L),
        )) {
            val fixture = StationAutocompleteFixture(clock)
            try {
                val destination = assertIs<ProviderResult.Success<List<Station>>>(fixture.provider.searchStations(query)).value.single { it.id == StationId("location:trenitalia-journeys:$wireId") }
                assertEquals(name, destination.name)
                assertEquals(StationId("location:trenitalia-journeys:$wireId"), destination.id)
                assertIs<ProviderResult.Success<JourneySearchResult>>(fixture.provider.search(
                    JourneySearchRequest(station("Milano Centrale"), destination, at)))
                assertEquals(wireId, fixture.selectionBodies.single().getValue("arrivalLocationId").jsonPrimitive.long)
            } finally { fixture.close() }
        }
    }

    @Test fun sameNameDistinctProviderLocationsRemainSelectableAndUnambiguous() = runBlocking {
        val fixture = StationAutocompleteFixture(clock)
        try {
            val twins = assertIs<ProviderResult.Success<List<Station>>>(fixture.provider.searchStations("terni")).value.filter { it.name == "Paternion-Feistritz" }
            assertEquals(2, twins.size)
            assertEquals(2, twins.map { it.id }.distinct().size)
            for (destination in twins) {
                assertIs<ProviderResult.Success<JourneySearchResult>>(fixture.provider.search(JourneySearchRequest(station("Milano Centrale"), destination, at)))
                assertEquals(destination.id.value.substringAfterLast(':').toLong(), fixture.selectionBodies.last().getValue("arrivalLocationId").jsonPrimitive.long)
            }
        } finally { fixture.close() }
    }

    @Test fun partialFailurePreservesCandidatesWithoutInventingRailwayOrImportanceMetadata() = runBlocking {
        val fixture = StationAutocompleteFixture(clock)
        fixture.failItalo = true
        try {
            val stations = assertIs<ProviderResult.Success<List<Station>>>(fixture.provider.searchStations("roma")).value
            assertTrue(stations.take(5).all { it.name.startsWith("Roma ") })
            assertTrue(stations.any { it.name == "Roma Termini" })
            assertTrue(stations.any { it.name == "Roma Tiburtina" })
            assertTrue(stations.all { it.searchCategory == StationSearchCategory.UNKNOWN })
        } finally { fixture.close() }
        val failed = StationAutocompleteFixture(clock)
        failed.failItalo = true; failed.failTrenitalia = true
        try { assertIs<ProviderResult.Unavailable>(failed.provider.searchStations("roma")) }
        finally { failed.close() }
        Unit
    }

    @Test fun emptyAndCachedPunctuationVariantsKeepFallbackRequestsBounded() = runBlocking {
        val fixture = StationAutocompleteFixture(clock)
        try {
            fixture.provider.searchStations("narni")
            val variant = assertIs<ProviderResult.Success<List<Station>>>(fixture.provider.searchStations("narni amelia")).value
            assertEquals("Narni-Amelia", variant.single().name)
            assertEquals(listOf("narni", "narni amelia"), fixture.locationQueries)
            assertTrue(assertIs<ProviderResult.Success<List<Station>>>(fixture.provider.searchStations("unknown place")).value.isEmpty())
            fixture.provider.searchStations("unknown place")
            assertEquals(listOf("narni", "narni amelia", "unknown place", "unknown"), fixture.locationQueries)
        } finally { fixture.close() }
    }
}
