package it.danielebufarini.trenify.provider.journey

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.network.createHttpClient
import it.danielebufarini.trenify.core.provider.api.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class StationDestinationResolutionTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    // Reduced locations/search response observed on 2026-10-08. Its unrelated
    // substring matches sort before Terni and used to fill all five UI slots.
    private val locations = """[
        {"id":830007226,"name":"Terni"},
        {"id":830007430,"name":"Terni Cospea"},
        {"id":810003131,"name":"Ternitz"},
        {"id":830011131,"name":"Cisternino"},
        {"id":830041030,"name":"CISTERNINO CITTA'"},
        {"id":800016335,"name":"Breternitz"},
        {"id":810002132,"name":"Markt Paternion"},
        {"id":800024191,"name":"Osternienburg"},
        {"id":810002129,"name":"Paternion-Feistritz"},
        {"id":810002133,"name":"Paternion-Feistritz"}
    ]""".trimIndent()

    private fun client(onSearch: (JsonObject) -> Unit = {}) = createHttpClient(MockEngine { request ->
        if (request.url.encodedPath.endsWith("locations/search")) {
            respond(when (normalizeStationQuery(request.url.parameters["name"]!!)) {
                "terni" -> locations
                "roma termini" -> """[{"id":830008409,"name":"Roma Termini"}]"""
                "milano centrale" -> """[{"id":830001700,"name":"Milano Centrale"}]"""
                else -> "[]"
            }, headers = jsonHeaders)
        } else {
            onSearch(Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject)
            respond("""{"solutions":[]}""", headers = jsonHeaders)
        }
    })

    @Test fun exactDestinationIsVisibleAmongTheFiveRenderedSuggestions() = runBlocking {
        client().use { client ->
            val provider = MultiSourceJourneyProvider(listOf(TrenitaliaJourneyAdapter(client, clock)), clock)
            val stations = assertIs<ProviderResult.Success<List<Station>>>(provider.searchStations(" TÈRNI ")).value
            assertEquals(StationId("station:terni"), stations.first().id)
            assertTrue(stations.take(5).any { it.id == StationId("station:terni") })
        }
    }

    @Test fun selectedDestinationKeepsCanonicalIdentityAndMapsToItsProviderLocation() = runBlocking {
        var arrival: Long? = null
        client { arrival = it.getValue("arrivalLocationId").jsonPrimitive.long }.use { client ->
            val provider = MultiSourceJourneyProvider(listOf(TrenitaliaJourneyAdapter(client, clock)), clock)
            val destination = assertIs<ProviderResult.Success<List<Station>>>(provider.searchStations("Terni"))
                .value.single { it.id == StationId("station:terni") }
            assertEquals("Terni", destination.name)
            val request = JourneySearchRequest(station("Roma Termini"), destination, at)
            assertIs<ProviderResult.Success<JourneySearchResult>>(provider.search(request))
            assertEquals(StationId("station:terni"), request.destination.id)
            assertEquals(830007226L, arrival)
        }
    }

    @Test fun previouslyWorkingDestinationStillResolves() = runBlocking {
        var arrival: Long? = null
        client { arrival = it.getValue("arrivalLocationId").jsonPrimitive.long }.use { client ->
            val provider = MultiSourceJourneyProvider(listOf(TrenitaliaJourneyAdapter(client, clock)), clock)
            val destination = assertIs<ProviderResult.Success<List<Station>>>(provider.searchStations("Milano Centrale")).value.single()
            assertEquals(StationId("station:milano centrale"), destination.id)
            assertIs<ProviderResult.Success<JourneySearchResult>>(provider.search(JourneySearchRequest(station("Roma Termini"), destination, at)))
            assertEquals(830001700L, arrival)
        }
    }

    @Test fun nonexistentStationHasNoSuggestionsAndCannotIssueASolutionsRequest() = runBlocking {
        client { fail("An unknown station must not issue a solutions request") }.use { client ->
            val provider = MultiSourceJourneyProvider(listOf(TrenitaliaJourneyAdapter(client, clock)), clock)
            assertTrue(assertIs<ProviderResult.Success<List<Station>>>(provider.searchStations("zzzzinesistentezzzz")).value.isEmpty())
            assertEquals(ProviderResult.Unavailable(false, ProviderFailure.UNSUPPORTED),
                provider.search(JourneySearchRequest(station("Roma Termini"), station("zzzzinesistentezzzz"), at)))
        }
    }

    @Test fun sharedLookupAlsoResolvesOriginToItsCorrectProviderLocation() = runBlocking {
        var departure: Long? = null
        client { departure = it.getValue("departureLocationId").jsonPrimitive.long }.use { client ->
            val provider = MultiSourceJourneyProvider(listOf(TrenitaliaJourneyAdapter(client, clock)), clock)
            val origin = assertIs<ProviderResult.Success<List<Station>>>(provider.searchStations("Terni")).value.first()
            assertEquals(StationId("station:terni"), origin.id)
            assertIs<ProviderResult.Success<JourneySearchResult>>(provider.search(JourneySearchRequest(origin, station("Roma Termini"), at)))
            assertEquals(830007226L, departure)
        }
    }
}
