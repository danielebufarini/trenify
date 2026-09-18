package it.danielebufarini.trenify.provider.journey

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import it.danielebufarini.trenify.core.network.RequestInstrumentation
import it.danielebufarini.trenify.core.network.span
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.provider.api.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Unofficial consumer search. Guest cookies, operation IDs and station codes are adapter-local. */
class ItaloJourneyAdapter(
    private val client: HttpClient,
    private val clock: Clock = Clock.System,
    private val instrumentation: RequestInstrumentation = RequestInstrumentation.None,
) : JourneySource {
    override val id = ProviderId("italo-journeys")
    override val operators = listOf(Operator("Italo"))
    override val services = listOf("Italo high-speed and railway connections returned by Italo")
    private val mutex = Mutex()
    private var stationSession: GuestSession? = null
    /**
     * Provider-local caches (T8 latency work), both in-memory and bounded:
     *
     * - Station resolution is shared by autocomplete and journey search.
     *   Positive resolutions live 24 hours (station codes are stable); an
     *   empty resolution ("unknown to Italo") lives only 1 hour, so a false
     *   negative can hide this provider for at most that long and is never
     *   persisted.
     * - The station catalogue is quasi-static and lives 24 hours.
     *
     * Wire codes never leave this file: hits rebuild domain [Station] values
     * through the same mapping as fresh fetches.
     */
    private val stationCache = ProviderLocalCache<List<ItaloStationWire>>(
        clock, STATION_TTL, negativeTtl = STATION_NEGATIVE_TTL, isNegative = { it.isEmpty() },
    )
    private val catalogueCache = ProviderLocalCache<Map<String, StationNameWire>>(clock, CATALOGUE_TTL, maxEntries = 4)

    override suspend fun searchStations(query: String): ProviderResult<List<Station>> = journeyRequest {
        val session = instrumentation.span("guest.session", mapOf("provider" to id.value, "purpose" to "station_search")) {
            mutex.withLock {
                stationSession?.takeIf { clock.now() - it.created < 5.minutes }
                    ?: guest().also { stationSession = it }
            }
        }
        ProviderResult.Success(resolve(query, session, "station.search").map { station(it.name) }, ProviderMetadata(id, clock.now()))
    }

    override suspend fun search(request: JourneySearchRequest): ProviderResult<JourneySearchResult> =
        searchProgressively(request) { }

    override suspend fun searchProgressively(
        request: JourneySearchRequest,
        publish: suspend (ProviderResult.Success<JourneySearchResult>) -> Unit,
    ): ProviderResult<JourneySearchResult> = journeyRequest {
        // Search sessions stay isolated per invocation: concurrent searches
        // cannot replace each other's planning state. The session is created
        // lazily so a fully cached (including unsupported) resolution issues
        // zero HTTP calls.
        var session: GuestSession? = null
        suspend fun session(): GuestSession = session
            ?: instrumentation.span("guest.session", mapOf("provider" to id.value, "purpose" to "journey_search")) { guest() }
                .also { session = it }
        suspend fun resolveCached(query: String, operation: String): List<ItaloStationWire> =
            stationCache.getOrFetch(normalizeStationQuery(query)) { locations(query, session(), operation) }
        val origin = resolveCached(request.origin.name, "station.origin.resolve")
            .singleOrNull { station(it.name).normalizedName == request.origin.normalizedName }
        val destination = resolveCached(request.destination.name, "station.destination.resolve")
            .singleOrNull { station(it.name).normalizedName == request.destination.normalizedName }
        if (origin == null || destination == null) return@journeyRequest ProviderResult.Unavailable(false, ProviderFailure.UNSUPPORTED)
        val live = session()
        val catalogue = catalogueCache.getOrFetch(CATALOGUE_KEY) {
            instrumentation.span("station.catalogue", mapOf("provider" to id.value)) {
                journeyJson.decodeFromString<Map<String, StationNameWire>>(
                    client.post("${API}stations/list") {
                        authenticate(live)
                        contentType(ContentType.Application.Json)
                        setBody("{\"culture\":\"it-IT\"}")
                    }.journeyBody(),
                )
            }
        }
        val journeys = mutableListOf<Journey>()
        var rejected = false
        var date = request.departureFrom.toLocalDateTime(RailwayTime.zone).date
        val last = request.departureUntil.toLocalDateTime(RailwayTime.zone).date
        while (date <= last) {
            val search = instrumentation.span("booking.search", mapOf("provider" to id.value)) {
                client.post("${API}booking") {
                    authenticate(live)
                    contentType(ContentType.Application.Json)
                    setBody(journeyJson.encodeToString(ItaloSearchWire(origin.stationCode, destination.stationCode, date.toString())))
                }
            }
            val completed = completeSearch(search, live)
            val wire = journeyJson.decodeFromString<ItaloSearchResponse>(completed.journeyBody())
            val solutions = wire.trips.filter { it.direction == "forward" }.flatMap { it.travelSolutions }
            val mapped = solutions.mapNotNull { solution ->
                try { ItaloJourneyMapper.map(journeyJson.decodeFromJsonElement<ItaloSolutionWire>(solution), catalogue, id) }
                catch (_: IllegalArgumentException) { null }
            }
            rejected = rejected || mapped.size != solutions.size
            journeys += mapped
            date += DatePeriod(days = 1)
        }
        if (journeys.isEmpty() && rejected) ProviderResult.Unavailable(false, ProviderFailure.PROTOCOL)
        else {
            val finalResult = result(request, mergeJourneys(journeys), !rejected, clock.now())
            publish(finalResult)
            finalResult
        }
    }

    private suspend fun guest(): GuestSession {
        val workingId = Uuid.random().toString()
        val response = client.post("https://biglietti.italotreno.com/api/login") {
            contentType(ContentType.Application.Json)
            header("X-Anonymous-User", "true")
            setBody(journeyJson.encodeToString(GuestRequest(workingId = workingId)))
        }
        response.journeyBody()
        val token = response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
            .map(::parseServerSetCookieHeader).singleOrNull { it.name == "BIGSessionToken" }?.value
            ?: throw JourneyWireFailure(ProviderFailure.UNAVAILABLE, true)
        return GuestSession(token, workingId, clock.now())
    }

    private suspend fun resolve(query: String, session: GuestSession, operation: String): List<ItaloStationWire> =
        stationCache.getOrFetch(normalizeStationQuery(query)) { locations(query, session, operation) }

    private suspend fun locations(query: String, session: GuestSession, operation: String): List<ItaloStationWire> =
        instrumentation.span(operation, mapOf("provider" to id.value)) {
            journeyJson.decodeFromString<ItaloStationsWire>(client.get("${API}stations") {
                authenticate(session)
                parameter("sn", query); parameter("onlyitalo", true); parameter("exItabus", true); parameter("exb", true)
                parameter("culture", "it-IT"); parameter("pn", 1); parameter("ps", 30)
            }.journeyBody()).stations.filter { !it.isMAC && !it.isItabusStation && !it.isPort && it.name.isNotBlank() }
        }

    private suspend fun completeSearch(initial: HttpResponse, session: GuestSession): HttpResponse {
        var response = initial
        repeat(MAX_POLLS) {
            if (response.status.value != 202) return response
            val pending = journeyJson.decodeFromString<PendingWire>(response.journeyBody())
            require(pending.operationId.isNotBlank())
            response = instrumentation.span("poll.$it", mapOf("provider" to id.value, "poll" to it.toString())) {
                delay((pending.retryAfter ?: pending.pollAfter ?: 1000).coerceIn(250, 5000))
                client.get("${API}booking/status/${pending.operationId.encodeURLPathPart()}") { authenticate(session) }
            }
        }
        throw JourneyWireFailure(ProviderFailure.TIMEOUT, true)
    }

    private fun HttpRequestBuilder.authenticate(session: GuestSession) {
        header(HttpHeaders.Authorization, "Bearer ${session.token}")
        header("X-BIG-working-session-id", session.workingId)
    }
    private companion object {
        const val API = "https://api-biglietti.italotreno.com/api/v1/"
        const val CATALOGUE_KEY = "stations-catalogue"
        val STATION_TTL = 24.hours
        val STATION_NEGATIVE_TTL = 1.hours
        val CATALOGUE_TTL = 24.hours
        const val MAX_POLLS = 10
    }
}

private data class GuestSession(val token: String, val workingId: String, val created: Instant)
@Serializable private data class GuestRequest(val isAnonymous: Boolean = true, val workingId: String)
@Serializable private data class ItaloStationsWire(val stations: List<ItaloStationWire>)
@Serializable private data class ItaloStationWire(
    val stationCode: String, val name: String, val isMAC: Boolean = false,
    val isItabusStation: Boolean = false, val isPort: Boolean = false,
)
@Serializable private data class StationNameWire(val name: String)
@Serializable private data class ItaloSearchWire(
    val departureStation: String, val arrivalStation: String, val departureDate: String,
    val isRoundTrip: Boolean = false, val culture: String = "it-IT", val showPrivateOffers: Boolean = false,
    val showBestPrices: Boolean = false, val adultPassengers: Int = 1, val youngPassengers: Int = 0,
    val childPassengers: Int = 0, val seniorPassengers: Int = 0, val hasPet: Boolean = false,
    val promoCode: String = "", val portalType: String = "B2C",
)
@Serializable private data class PendingWire(val operationId: String, val pollAfter: Long? = null, val retryAfter: Long? = null)
@Serializable private data class ItaloSearchResponse(val trips: List<ItaloTripWire>)
@Serializable private data class ItaloTripWire(val direction: String, val travelSolutions: List<JsonElement>)
@Serializable private data class ItaloSolutionWire(val journeys: List<ItaloJourneyWire>)
@Serializable private data class ItaloJourneyWire(val serviceProvider: String, val sequence: Int, val segments: List<ItaloSegmentWire>)
@Serializable private data class ItaloSegmentWire(
    val departureStation: String, val arrivalStation: String, val std: String, val sta: String,
    val trainNumber: String? = null, val carrierCode: String? = null,
)

private object ItaloJourneyMapper {
    fun map(wire: ItaloSolutionWire, stations: Map<String, StationNameWire>, source: ProviderId): Journey = Journey(
        wire.journeys.sortedBy { it.sequence }.flatMap { journey ->
            val operator = when (journey.serviceProvider.uppercase()) {
                "ITALO" -> Operator("Italo")
                "TRENITALIA", "INTERCITY" -> Operator("Trenitalia")
                "TRENORD" -> Operator("Trenord")
                "TPER" -> Operator("Trenitalia Tper")
                else -> throw IllegalArgumentException("Unsupported service")
            }
            journey.segments.map { segment ->
                require(segment.carrierCode != "BS")
                val number = segment.trainNumber?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.let(::TrainNumber)
                val category = when {
                    journey.serviceProvider.uppercase() == "ITALO" -> "Italo AV"
                    journey.serviceProvider.uppercase() == "INTERCITY" -> "Intercity"
                    else -> null
                }
                JourneyLeg(station(requireNotNull(stations[segment.departureStation]).name),
                    station(requireNotNull(stations[segment.arrivalStation]).name), wireTime(segment.std), wireTime(segment.sta),
                    number, category, operator)
            }
        }, setOf(source),
    )
}
