package it.danielebufarini.trenify.core.testing

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import it.danielebufarini.trenify.core.network.createHttpClient
import it.danielebufarini.trenify.core.provider.api.*
import it.danielebufarini.trenify.provider.journey.*
import kotlinx.serialization.json.*
import kotlin.time.Clock

/**
 * Scripted candidate subsets exercise the provider protocols, not live search guarantees.
 * LeFrecce twin IDs/names are recorded real catalog rows. Italo twin codes are synthetic:
 * no duplicate-name Italo location has been observed in the recorded Italian responses.
 */
class HomonymousStationFixture(val italo: Boolean, clock: Clock) {
    val firstRef = if (italo) "TEST-A" else "810002129"
    val secondRef = if (italo) "TEST-B" else "810002133"
    val providerId = if (italo) "italo-journeys" else "trenitalia-journeys"
    val queries = mutableListOf<String>()
    val selectedRefs = mutableListOf<String>()
    var reverseExactResponse = false
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")
    private fun row(ref: String, name: String): JsonObject = buildJsonObject {
        if (italo) {
            put("stationCode", ref); put("isItaloStation", true)
        } else put("id", ref.toLong())
        put("name", name)
    }
    private val client = createHttpClient(MockEngine { request ->
        when {
            request.url.encodedPath.endsWith("login") -> respond("{}", headers = headersOf(
                HttpHeaders.SetCookie, "BIGSessionToken=synthetic-identity-test; Path=/; Secure"))
            request.url.encodedPath.endsWith("stations/list") -> respond("{}", headers = headers)
            request.url.encodedPath.endsWith("locations/search") || request.url.encodedPath.endsWith("stations") -> {
                val query = request.url.parameters[if (italo) "sn" else "name"]!!
                queries += query
                val a = row(firstRef, "Paternion-Feistritz")
                val b = row(secondRef, "Paternion-Feistritz")
                val rows = when (query.lowercase()) {
                    "paternion", "paternion-feistritz" -> if (reverseExactResponse) listOf(b, a) else listOf(a, b)
                    "feistritz" -> listOf(a)
                    "paternion f" -> listOf(b, a)
                    "roma termini" -> listOf(row(if (italo) "RMT" else "830008409", "Roma Termini"))
                    "milano centrale" -> listOf(row(if (italo) "MC_" else "830001700", "Milano Centrale"))
                    else -> emptyList()
                }
                val body = if (italo) buildJsonObject { put("stations", JsonArray(rows)); put("count", rows.size) }
                    else JsonArray(rows)
                respond(body.toString(), headers = headers)
            }
            request.url.encodedPath.endsWith("solutions") || request.url.encodedPath.endsWith("booking") -> {
                val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                selectedRefs += body.getValue(if (italo) "arrivalStation" else "arrivalLocationId").jsonPrimitive.content
                respond(if (italo) "{\"trips\":[]}" else "{\"solutions\":[]}", headers = headers)
            }
            else -> error("Unexpected identity fixture request: ${request.url.encodedPath}")
        }
    })
    val source: JourneySource = if (italo) ItaloJourneyAdapter(client, clock) else TrenitaliaJourneyAdapter(client, clock)
    val provider: JourneyProvider = MultiSourceJourneyProvider(listOf(source), clock)
    fun close() = client.close()
}
