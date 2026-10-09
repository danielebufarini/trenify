package it.danielebufarini.trenify.core.testing

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.network.createHttpClient
import it.danielebufarini.trenify.core.provider.api.*
import it.danielebufarini.trenify.provider.journey.*
import kotlinx.serialization.json.*
import kotlin.time.Clock

/** Recorded HTTP responses through the actual adapters and aggregator; test dependency only. */
class StationAutocompleteFixture(clock: Clock = Clock.System, includeItalo: Boolean = true) {
    val locationQueries = mutableListOf<String>()
    val italoQueries = mutableListOf<String>()
    val selectionBodies = mutableListOf<JsonObject>()
    val italoSelectionBodies = mutableListOf<JsonObject>()
    val arrivalLocationIds: List<Long> get() = selectionBodies.map { it.getValue("arrivalLocationId").jsonPrimitive.long }
    val italoArrivalStationCodes: List<String> get() = italoSelectionBodies.map { it.getValue("arrivalStation").jsonPrimitive.content }
    var failTrenitalia = false
    var failItalo = false
    var beforeLocationResponse: suspend (String) -> Unit = {}
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val client = createHttpClient(MockEngine { request ->
        when {
            request.url.encodedPath.endsWith("locations/search") -> {
                val query = normalizeStationQuery(request.url.parameters["name"]!!)
                locationQueries += query
                beforeLocationResponse(query)
                if (failTrenitalia) respond("unavailable", HttpStatusCode.ServiceUnavailable)
                else {
                    // Full observed responses retain their original order. Honor the real API limit.
                    val body = StationAutocompleteCatalog.trenitalia[query]?.let(Json::parseToJsonElement)?.jsonArray
                        ?: JsonArray(StationAutocompleteCatalog.trenitalia.values.flatMap {
                            Json.parseToJsonElement(it).jsonArray
                        }.filter { normalizeStationQuery(it.jsonObject.getValue("name").jsonPrimitive.content) == query }
                            .distinctBy { it.jsonObject.getValue("id") })
                    respond(JsonArray(body.take(request.url.parameters["limit"]!!.toInt())).toString(), headers = jsonHeaders)
                }
            }
            request.url.encodedPath.endsWith("login") -> respond("{}", headers = headersOf(
                HttpHeaders.SetCookie, "BIGSessionToken=synthetic-station-test; Path=/; Secure",
            ))
            request.url.encodedPath.endsWith("stations") -> {
                val query = normalizeStationQuery(request.url.parameters["sn"]!!)
                italoQueries += query
                if (failItalo) respond("unavailable", HttpStatusCode.ServiceUnavailable)
                else respond(StationAutocompleteCatalog.italo[query]
                    ?: "{\"stations\":[],\"count\":0}", headers = jsonHeaders)
            }
            request.url.encodedPath.endsWith("stations/list") -> respond("{}", headers = jsonHeaders)
            request.url.encodedPath.endsWith("booking") -> {
                italoSelectionBodies += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                respond("{\"trips\":[]}", headers = jsonHeaders)
            }
            request.url.encodedPath.endsWith("solutions") -> {
                selectionBodies += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                respond("{\"solutions\":[]}", headers = jsonHeaders)
            }
            else -> error("Unexpected station fixture HTTP request: ${request.url.encodedPath}")
        }
    })
    val provider: JourneyProvider = MultiSourceJourneyProvider(
        listOf(TrenitaliaJourneyAdapter(client, clock)) + if (includeItalo) listOf(ItaloJourneyAdapter(client, clock)) else emptyList(), clock,
    )
    fun close() = client.close()
}
