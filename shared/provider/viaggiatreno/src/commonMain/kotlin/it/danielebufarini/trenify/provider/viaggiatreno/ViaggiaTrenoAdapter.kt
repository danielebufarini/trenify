package it.danielebufarini.trenify.provider.viaggiatreno

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLPathPart
import io.ktor.http.HttpHeaders
import it.danielebufarini.trenify.core.network.RequestInstrumentation
import it.danielebufarini.trenify.core.network.span
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.provider.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.offsetAt
import kotlinx.serialization.json.*
import kotlin.time.Clock
import kotlin.time.Instant

class ViaggiaTrenoAdapter(
    private val client: HttpClient,
    private val clock: Clock = Clock.System,
    private val instrumentation: RequestInstrumentation = RequestInstrumentation.None,
    // HTTPS endpoints redirect to HTTP (verified 2026-09-05). Native exceptions are host-scoped.
    private val baseUrl: String = "http://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno",
) : TrainRealtimeProvider {
    override val id = ProviderId("viaggiatreno")
    override val capabilities = ProviderCapabilities()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    override suspend fun searchStations(query: String, limit: Int): ProviderResult<List<ProviderStation>> =
        request(listOf("autocompletaStazione", query)) { body ->
            lines(body).map { line ->
                val fields = line.split('|')
                require(fields.size == 2 && fields.all { it.isNotBlank() })
                ProviderStation(ExternalStationRef(fields[1]), fields[0])
            }.distinctBy { it.ref }.take(limit.coerceIn(1, 100))
        }

    override suspend fun findTrainCandidates(
        number: TrainNumber,
        serviceDate: LocalDate?,
    ): ProviderResult<List<ProviderTrainCandidate>> = request(
        listOf("cercaNumeroTrenoTrenoAutocomplete", number.value),
    ) { body ->
        lines(body).map { line ->
            val fields = line.split('|')
            require(fields.size == 2)
            val identity = fields[1].split('-')
            require(identity.size == 3)
            val actualNumber = TrainNumber(identity[0])
            require(actualNumber == number)
            val origin = ExternalStationRef(identity[1])
            val date = RailwayTime.serviceDate(Instant.fromEpochMilliseconds(identity[2].toLong()))
            ProviderTrainCandidate(
                ref = ProviderTrainRunRef(actualNumber, origin, date, ExternalRunRef(fields[1])),
                origin = ProviderStation(origin, fields[0].substringAfter(" - ").substringBeforeLast(" - ")),
            )
        }.filter { serviceDate == null || it.ref.serviceDate == serviceDate }.distinctBy { it.ref }
    }

    override suspend fun departures(station: ExternalStationRef, at: Instant) = board(station, at, false)
    override suspend fun arrivals(station: ExternalStationRef, at: Instant) = board(station, at, true)

    private suspend fun board(station: ExternalStationRef, at: Instant, arrivals: Boolean) =
        request(listOf(if (arrivals) "arrivi" else "partenze", station.value, boardDate(at))) { body ->
            if (body.isBlank() || body.trim() == "null") emptyList()
            else json.parseToJsonElement(body).jsonArray.map { candidate(it.jsonObject, arrivals = arrivals) }
        }

    override suspend fun getTrainSnapshot(ref: ProviderTrainRunRef): ProviderResult<ProviderTrainSnapshot> =
        request(
            listOf("andamentoTreno", ref.origin.value, ref.number.value, RailwayTime.startOfDay(ref.serviceDate).toEpochMilliseconds().toString()),
            sourceTimestamp = { snapshot -> snapshot.stops.mapNotNull { it.actualDeparture ?: it.actualArrival }.maxOrNull() },
        ) { body ->
            val dto = json.parseToJsonElement(body).jsonObject
            require(dto.string("numeroTreno") != null)
            val train = candidate(dto, ref, detail = true)
            require(train.ref.number == ref.number && train.ref.origin == ref.origin && train.ref.serviceDate == ref.serviceDate)
            ProviderTrainSnapshot(train, dto.array("fermate").map { stop(it.jsonObject) }, position(dto))
        }

    private fun candidate(
        dto: JsonObject,
        fallback: ProviderTrainRunRef? = null,
        arrivals: Boolean = false,
        detail: Boolean = false,
    ): ProviderTrainCandidate {
        val number = dto.string("numeroTreno")?.let(::TrainNumber) ?: fallback?.number ?: error("Missing train number")
        val origin = dto.string("idOrigine") ?: dto.string("codOrigine") ?: fallback?.origin?.value ?: error("Missing origin")
        val date = dto.instant("dataPartenzaTreno")?.let(RailwayTime::serviceDate)
            ?: dto.string("dataPartenzaTrenoAsDate")?.let(LocalDate::parse)
            ?: fallback?.serviceDate ?: error("Missing service date")
        val direction = if (arrivals) "Arrivo" else "Partenza"
        return ProviderTrainCandidate(
            ref = ProviderTrainRunRef(number, ExternalStationRef(origin), date),
            origin = ProviderStation(ExternalStationRef(origin), dto.string("origine") ?: ""),
            destinationName = dto.string("destinazione"),
            // Board rows carry only the station event time (departure from / arrival
            // at the board station). Terminal scheduled times come exclusively
            // from detail snapshots; board rows keep them null (T7.10, no N+1).
            scheduledTime = dto.instant("orario$direction"),
            scheduledDeparture = dto.instant("orarioPartenza").takeIf { detail },
            scheduledArrival = dto.instant("orarioArrivo").takeIf { detail },
            status = status(dto, detail),
            delayMinutes = dto.int("ritardo"),
            scheduledPlatform = dto.string("binarioProgrammato${direction}Descrizione"),
            actualPlatform = dto.string("binarioEffettivo${direction}Descrizione"),
            operator = when (dto.int("codiceCliente")) {
                1 -> Operator("Trenitalia")
                18 -> Operator("Trenord")
                else -> null
            },
            category = trainCategory(dto),
        )
    }

    /**
     * Source-backed train category (T7.10).
     *
     * Evidence (published andamentoTreno/partenze/arrivi schemas):
     * - `categoria` holds the coarse service designation (REG, IC, EC, …);
     *   it is empty for Frecce services;
     * - `categoriaDescrizione` is documented as more detailed than
     *   `categoria` and carries the display designation with a realized
     *   leading space (` FR`, ` FA`, ` FB`); empty for unknown, including
     *   observed cancelled-Frecce cases;
     * - `tipoProdotto` 100/101/102 identifies Frecciarossa/Frecciargento/
     *   Frecciabianca and combines with `categoria` into the display name;
     * - `compNumeroTreno` is a display derivative computed from those fields
     *   (e.g. `REG 2328`, ` FA 8852`); it carries no independent semantics
     *   and is never consulted.
     *
     * Precedence follows information strength with veto: `categoriaDescrizione`,
     * then `categoria`, then `tipoProdotto`. A higher-priority field that is
     * absent or empty defers to the next field; a higher-priority field that
     * is present but unsupported, composite or contradictory yields unknown
     * without falling through, so weaker evidence can never override it.
     * A composite display designation (`EC FR`) is not a supported
     * provider-neutral category: it stays unknown rather than being reduced
     * to one half. Unlisted values (e.g. `RV`, `TS`) stay unknown; raw source
     * strings never leave the adapter.
     *
     * `compNumeroTreno` stays unread: the schema describes it as a display
     * name computed from categoria/numeroTreno/tipoProdotto, so it is a
     * derivative with no independent semantics, and the only
     * source-evidenced location for composite designations is
     * `categoriaDescrizione` itself (which vetoes above). Parsing display
     * text would invert the evidence hierarchy.
     */
    private fun trainCategory(dto: JsonObject): TrainCategory? {
        dto.string("categoriaDescrizione")?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { detailed ->
            if (' ' in detailed) return null
            return TrainCategory.entries.firstOrNull { it.code == detailed }
        }
        dto.string("categoria")?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { code ->
            return TrainCategory.entries.firstOrNull { it.code == code }
        }
        return when (dto.string("tipoProdotto")) {
            "100" -> TrainCategory.FR
            "101" -> TrainCategory.FA
            "102" -> TrainCategory.FB
            else -> null
        }
    }

    /**
     * Source-backed operational position (T7.10, FR-POSITION-001/002).
     *
     * `stazioneUltimoRilevamento` names the last-detection station; the source
     * uses `--` for unknown. `oraUltimoRilevamento` is its own observation
     * instant, never fetch time. Null unless at least one of them is known.
     */
    private fun position(dto: JsonObject): ProviderOperationalPosition? {
        val station = dto.string("stazioneUltimoRilevamento")?.takeIf { it != "--" }
        val observedAt = dto.instant("oraUltimoRilevamento")
        return if (station == null && observedAt == null) null
        else ProviderOperationalPosition(station, observedAt)
    }

    private fun stop(dto: JsonObject): ProviderTrainStop = ProviderTrainStop(
        station = ProviderStation(
            ExternalStationRef(dto.string("id") ?: error("Missing stop identity")),
            dto.string("stazione") ?: "",
        ),
        scheduledArrival = dto.instant("arrivo_teorico"),
        scheduledDeparture = dto.instant("partenza_teorica"),
        actualArrival = dto.instant("arrivoReale"),
        actualDeparture = dto.instant("partenzaReale"),
        scheduledPlatform = dto.string("binarioProgrammatoPartenzaDescrizione") ?: dto.string("binarioProgrammatoArrivoDescrizione"),
        actualPlatform = dto.string("binarioEffettivoPartenzaDescrizione") ?: dto.string("binarioEffettivoArrivoDescrizione"),
        delayMinutes = dto.int("ritardo"),
        // Accepted T1 rule: the type marker governs. 1 marks a passed stop, 3
        // a suppressed one; 2 (extraordinary stop) has no provider-neutral
        // state and stays unknown, as does any unlisted value — even when
        // actual timestamps are present, mirroring how uninterpretable train
        // codes veto status inference. Actual times are still mapped and remain
        // available to consumers (e.g. departure detection).
        status = when (dto.int("actualFermataType")) {
            0 -> StopStatus.SCHEDULED
            1 -> StopStatus.COMPLETED
            3 -> StopStatus.CANCELLED
            else -> StopStatus.UNKNOWN
        },
    )

    private fun status(dto: JsonObject, detail: Boolean = false): TrainStatus = when {
        dto.int("provvedimento") == 1 || dto.string("tipoTreno") == "ST" -> TrainStatus.CANCELLED
        dto.string("tipoTreno") == "DV" || dto.int("provvedimento") == 3 -> TrainStatus.DIVERTED
        dto.int("provvedimento") == 2 || dto.string("tipoTreno") in listOf("PP", "SI", "SF", "SM") ||
            dto.array("fermateSoppresse").isNotEmpty() -> TrainStatus.PARTIALLY_CANCELLED
        dto.int("provvedimento") !in listOf(null, 0) -> TrainStatus.UNKNOWN
        dto.string("tipoTreno") in listOf("VO", "VD") -> TrainStatus.DIVERTED
        dto.string("tipoTreno") !in listOf(null, "PG") -> TrainStatus.UNKNOWN
        // riprogrammazione=Y documents a reprogrammed train departing from a
        // different station. It is positive evidence for RESCHEDULED, checked
        // after the uninterpretable-code vetoes above so unknown codes still
        // degrade to UNKNOWN. Delay or time inequality alone never maps here.
        //
        // Dimensional note: reprogramming state and operational state are
        // independent source dimensions collapsed into one TrainStatus.
        // An uninterpretable riprogrammazione value carries no operational
        // meaning, so unlike unknown provvedimento/tipoTreno codes it vetoes
        // nothing: it can never produce RESCHEDULED itself, and any known
        // status alongside it must come from independent explicit evidence
        // (e.g. circolante). Precedence is therefore CANCELLED, then
        // PARTIALLY_CANCELLED, then unknown-code vetoes, then DIVERTED, then
        // RESCHEDULED on exact "Y", then arrival/departure/running evidence.
        dto.string("riprogrammazione") == "Y" -> TrainStatus.RESCHEDULED
        // Board `arrivato` is scoped to the board station ("already arrived at
        // the station of this board"), not to the train journey: on board rows
        // it proves circulation (RUNNING), while detail `arrivato` proves
        // destination arrival (ARRIVED).
        dto.bool("arrivato") == true -> if (detail) TrainStatus.ARRIVED else TrainStatus.RUNNING
        dto.bool("nonPartito") == true -> TrainStatus.NOT_DEPARTED
        dto.bool("circolante") == true || dto.instant("oraUltimoRilevamento") != null -> TrainStatus.RUNNING
        else -> TrainStatus.UNKNOWN
    }

    private suspend fun <T> request(
        segments: List<String>,
        sourceTimestamp: (T) -> Instant? = { null },
        parse: (String) -> T,
    ): ProviderResult<T> {
        val endpoint = segments.first()
        val response = try {
            instrumentation.span("train.provider_request", mapOf("provider" to id.value, "endpoint" to endpoint)) {
                client.get(baseUrl.trimEnd('/') + "/" + segments.joinToString("/") { it.encodeURLPathPart() }) {
                    headers[HttpHeaders.Accept] = if (endpoint in setOf("autocompletaStazione", "cercaNumeroTrenoTrenoAutocomplete"))
                        "text/plain" else "application/json"
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HttpRequestTimeoutException) {
            return ProviderResult.Unavailable(true, ProviderFailure.TIMEOUT)
        } catch (_: Exception) {
            return ProviderResult.Unavailable(true, ProviderFailure.TRANSPORT)
        }
        return when (response.status.value) {
            404, 204 -> ProviderResult.NotFound
            429, in 500..599 -> ProviderResult.Unavailable(true, ProviderFailure.UNAVAILABLE)
            !in 200..299 -> ProviderResult.Unavailable(false, ProviderFailure.PROTOCOL)
            else -> {
                val body = try {
                    response.bodyAsText()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return ProviderResult.Unavailable(true, ProviderFailure.TRANSPORT)
                }
                if (body.trimStart().startsWith("<")) return ProviderResult.Unavailable(false, ProviderFailure.PROTOCOL)
                try {
                    val value = instrumentation.span("train.parse", mapOf("provider" to id.value, "endpoint" to endpoint)) {
                        parse(body)
                    }
                    // The source observation is different from the time we fetched the response.
                    val source = if (segments.first() == "andamentoTreno") {
                        json.parseToJsonElement(body).jsonObject.instant("oraUltimoRilevamento") ?: sourceTimestamp(value)
                    } else sourceTimestamp(value)
                    ProviderResult.Success(value, ProviderMetadata(id, clock.now(), source))
                } catch (_: IllegalArgumentException) {
                    ProviderResult.Unavailable(false, ProviderFailure.PARSING)
                } catch (_: IllegalStateException) {
                    ProviderResult.Unavailable(false, ProviderFailure.PARSING)
                }
            }
        }
    }

    private fun lines(body: String) = if (body.trim() == "null") emptyList() else body.lineSequence()
        .map(String::trim).filter(String::isNotEmpty).toList()

    private fun boardDate(at: Instant): String {
        val local = at.toLocalDateTime(RailwayTime.zone)
        val day = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[local.dayOfWeek.ordinal]
        val month = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")[local.month.ordinal]
        val offset = RailwayTime.zone.offsetAt(at).totalSeconds / 60
        val offsetText = (if (offset >= 0) "+" else "-") + (kotlin.math.abs(offset) / 60).toString().padStart(2, '0') +
            (kotlin.math.abs(offset) % 60).toString().padStart(2, '0')
        val time = listOf(local.hour, local.minute, local.second).joinToString(":") { it.toString().padStart(2, '0') }
        return "$day $month ${local.day.toString().padStart(2, '0')} ${local.year} $time GMT$offsetText"
    }
}

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
private fun JsonObject.int(key: String): Int? = string(key)?.toIntOrNull()
private fun JsonObject.bool(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.instant(key: String): Instant? = string(key)?.toLongOrNull()?.takeIf { it > 0 }?.let(Instant::fromEpochMilliseconds)
private fun JsonObject.array(key: String): JsonArray = get(key) as? JsonArray ?: JsonArray(emptyList())
