package it.danielebufarini.trenify.provider.journey

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.ContentType
import io.ktor.http.contentType
import it.danielebufarini.trenify.core.network.RequestInstrumentation
import it.danielebufarini.trenify.core.network.span
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.provider.api.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/** Unofficial LeFrecce consumer protocol. Wire IDs and session references never leave this file. */
class TrenitaliaJourneyAdapter(
    private val client: HttpClient,
    private val clock: Clock = Clock.System,
    private val instrumentation: RequestInstrumentation = RequestInstrumentation.None,
) : JourneySource {
    override val id = ProviderId("trenitalia-journeys")
    override val operators = listOf(Operator("Trenitalia"))
    override val services = listOf("Frecce", "Intercity", "Regional railway services returned by LeFrecce")
    /**
     * Provider-local station-resolution cache (T8 latency work): LeFrecce
     * location ids and names are stable, and a miss fails safe to
     * unsupported (never to wrong journeys), so both autocomplete and
     * journey origin/destination resolution share one bounded in-memory
     * cache. Positive resolutions live 24 hours; an empty successful
     * response ("unknown station") lives only 1 hour, so a transient empty
     * reply cannot hide a station for a day. Transport/parse failures throw
     * and are never cached. Wire ids never leave this file: hits rebuild
     * domain [Station] values through the same mapping as fresh fetches.
     */
    private val stationCache = ProviderLocalCache<List<LocationWire>>(
        clock, STATION_TTL, negativeTtl = STATION_NEGATIVE_TTL, isNegative = { it.isEmpty() },
    )

    override suspend fun searchStations(query: String): ProviderResult<List<Station>> = journeyRequest {
        ProviderResult.Success(locations(query, "station.search").map { station(it.name) }, ProviderMetadata(id, clock.now()))
    }

    override suspend fun search(request: JourneySearchRequest): ProviderResult<JourneySearchResult> =
        searchProgressively(request) { }

    override suspend fun searchProgressively(
        request: JourneySearchRequest,
        publish: suspend (ProviderResult.Success<JourneySearchResult>) -> Unit,
    ): ProviderResult<JourneySearchResult> = journeyRequest {
        val origin = locations(request.origin.name, "station.origin.resolve")
            .singleOrNull { station(it.name).normalizedName == request.origin.normalizedName }
        val destination = locations(request.destination.name, "station.destination.resolve")
            .singleOrNull { station(it.name).normalizedName == request.destination.normalizedName }
        if (origin == null || destination == null) return@journeyRequest ProviderResult.Unavailable(false, ProviderFailure.UNSUPPORTED)
        val journeys = mutableListOf<Journey>()
        var complete = false
        var rejected = false
        var previousPage: List<Journey>? = null
        for (page in 0 until MAX_PAGES) {
            val wire = instrumentation.span("solutions.page.$page", mapOf(
                "provider" to id.value,
                "page" to page.toString(),
            )) {
                val response = client.post("${BASE}ticket/solutions") {
                    contentType(ContentType.Application.Json)
                    // LeFrecce treats omitted default fields as a malformed request. The
                    // provider-local JSON configuration explicitly encodes the complete
                    // criteria object; the shared Ktor client configuration does not.
                    setBody(journeyJson.encodeToString(SearchWire(
                        origin.id,
                        destination.id,
                        request.departureFrom.toLocalDateTime(RailwayTime.zone).format(DEPARTURE_TIME_FORMAT),
                        criteria = CriteriaWire(offset = page * PAGE_SIZE),
                    )))
                }
                journeyJson.decodeFromString<SolutionsWire>(response.journeyBody())
            }
            val mapped = wire.solutions.mapNotNull { container ->
                try { journeyJson.decodeFromJsonElement<SolutionContainerWire>(container).solution?.let { TrenitaliaJourneyMapper.map(it, id) } }
                catch (_: IllegalArgumentException) { null }
            }
            rejected = rejected || mapped.size != wire.solutions.size
            journeys += mapped
            complete = wire.solutions.isEmpty() || wire.solutions.size < PAGE_SIZE ||
                mapped.any { it.departure > request.departureUntil }
            val update = result(request, mergeJourneys(journeys), complete && !rejected, clock.now())
            publish(update)
            if (complete || mapped == previousPage) break
            previousPage = mapped
        }
        if (journeys.isEmpty() && rejected) ProviderResult.Unavailable(false, ProviderFailure.PROTOCOL)
        else result(request, mergeJourneys(journeys), complete && !rejected, clock.now())
    }

    private suspend fun locations(query: String, operation: String): List<LocationWire> =
        instrumentation.span(operation, mapOf("provider" to id.value)) {
            stationCache.getOrFetch(normalizeStationQuery(query)) {
                journeyJson.decodeFromString<List<LocationWire>>(
                    client.get("${BASE}locations/search") { parameter("name", query); parameter("limit", 30) }.journeyBody(),
                ).filter { !it.multistation && it.name.isNotBlank() }
            }
        }

    private companion object {
        const val BASE = "https://www.lefrecce.it/Channels.Website.BFF.WEB/website/"
        const val PAGE_SIZE = 10
        const val MAX_PAGES = 8
        val STATION_TTL = 24.hours
        val STATION_NEGATIVE_TTL = 1.hours
        // LeFrecce rejects minute-only times; zero seconds must also be written.
        val DEPARTURE_TIME_FORMAT = LocalDateTime.Format {
            date(LocalDate.Formats.ISO)
            char('T')
            hour(); char(':'); minute(); char(':'); second()
        }
    }
}

@Serializable private data class LocationWire(val id: Long, val name: String, val multistation: Boolean = false)
@Serializable private data class SearchWire(
    val departureLocationId: Long,
    val arrivalLocationId: Long,
    val departureTime: String,
    val adults: Int = 1,
    val children: Int = 0,
    val criteria: CriteriaWire,
)
@Serializable private data class CriteriaWire(
    val offset: Int,
    val limit: Int = 10,
    val order: String = "DEPARTURE_DATE",
    val frecceOnly: Boolean = false,
    val regionalOnly: Boolean = false,
    val intercityOnly: Boolean = false,
    val noChanges: Boolean = false,
)
@Serializable private data class SolutionsWire(val solutions: List<JsonElement>)
@Serializable private data class SolutionContainerWire(val solution: SolutionWire? = null)
@Serializable private data class SolutionWire(val nodes: List<NodeWire> = emptyList())
@Serializable private data class NodeWire(
    val origin: String,
    val destination: String,
    val departureTime: String,
    val arrivalTime: String,
    val train: TrainWire,
    val blueJet: Boolean = false,
)
@Serializable private data class TrainWire(
    val name: String? = null,
    val trainCategory: String? = null,
    val acronym: String? = null,
    val urban: Boolean = false,
)

private object TrenitaliaJourneyMapper {
    fun map(wire: SolutionWire, source: ProviderId): Journey {
        val legs = wire.nodes.map { node ->
            require(!node.blueJet && !node.train.urban)
            val category = node.train.trainCategory?.trim()?.takeIf(String::isNotEmpty)
            require(category?.lowercase()?.let { "bus" in it || "traghetto" in it } != true)
            val number = node.train.name?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.let(::TrainNumber)
            val operator = if (node.train.acronym in setOf("FR", "FA", "FB", "IC", "ICN")) Operator("Trenitalia") else null
            JourneyLeg(station(node.origin), station(node.destination), wireTime(node.departureTime), wireTime(node.arrivalTime),
                number, category, operator)
        }
        return Journey(legs, setOf(source))
    }
}
