package it.danielebufarini.trenify.core.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.jvm.JvmInline

@JvmInline value class StationId(val value: String)
@JvmInline value class TrainNumber(val value: String) {
    init { require(value.isNotBlank() && value.all(Char::isDigit)) }
}
@JvmInline value class ProviderId(val value: String)
@JvmInline value class ExternalStationRef(val value: String)
@JvmInline value class ExternalRunRef(val value: String)

data class TrainRunId(
    val provider: ProviderId,
    val number: TrainNumber,
    val origin: ExternalStationRef,
    val serviceDate: LocalDate,
) {
    // Length prefixes make the persistent key unambiguous even for opaque references.
    val key: String get() = listOf(provider.value, number.value, origin.value, serviceDate.toString())
        .joinToString("") { "${it.length}:$it" }
}

object RailwayTime {
    val zone: TimeZone = TimeZone.of("Europe/Rome")
    fun serviceDate(instant: Instant): LocalDate = instant.toLocalDateTime(zone).date
    fun startOfDay(date: LocalDate): Instant = date.atStartOfDayIn(zone)
}

fun normalizeStationQuery(value: String): String = value.trim().lowercase()
    .map { char ->
        when (char) {
            'à', 'á', 'â', 'ä' -> 'a'
            'è', 'é', 'ê', 'ë' -> 'e'
            'ì', 'í', 'î', 'ï' -> 'i'
            'ò', 'ó', 'ô', 'ö' -> 'o'
            'ù', 'ú', 'û', 'ü' -> 'u'
            else -> char
        }
    }.joinToString("").replace(Regex("\\s+"), " ")

data class Station(val id: StationId, val name: String) {
    val normalizedName: String get() = normalizeStationQuery(name)
}
data class Operator(val name: String)
enum class TrainStatus {
    NOT_DEPARTED, RUNNING, ARRIVED, CANCELLED, PARTIALLY_CANCELLED, DIVERTED, RESCHEDULED, UNKNOWN
}
enum class StopStatus { SCHEDULED, COMPLETED, CANCELLED, UNKNOWN }
enum class BoardKind { DEPARTURES, ARRIVALS }

/**
 * Provider-neutral train category (T7.10).
 *
 * Entries are the public railway service designations shared across operators
 * (also printed on tickets as e.g. `REG 2328` or `FR 9624`), not wire codes:
 * the adapter allowlists source values into these entries and anything absent
 * or unrecognized stays null, shown as unavailable. No full-name expansion is
 * stored here; presentation maps only the expansions backed by source evidence
 * and otherwise shows [code].
 */
enum class TrainCategory(val code: String) {
    REG("REG"),
    MET("MET"),
    IR("IR"),
    IC("IC"),
    ICN("ICN"),
    EC("EC"),
    EN("EN"),
    EXP("EXP"),
    NCL("NCL"),
    FR("FR"),
    FA("FA"),
    FB("FB"),
}

data class TrainRunSummary(
    val id: TrainRunId,
    val origin: Station,
    val destinationName: String?,
    /**
     * The station-board event time for board rows: scheduled departure from the
     * board station on a departures board, scheduled arrival at the board
     * station on an arrivals board. For detail snapshots this carries the
     * origin departure (see [scheduledDeparture]). It is never an origin or
     * destination terminal time for board rows.
     */
    val scheduledTime: Instant? = null,
    /**
     * Scheduled departure at the origin terminal. Detail snapshots only;
     * always null for board rows. Never copied from the board event time;
     * null when the source omits it.
     */
    val scheduledDeparture: Instant? = null,
    /**
     * Scheduled arrival at the destination terminal. Detail snapshots only;
     * always null for board rows. Never copied from the board event time;
     * null when the source omits it.
     */
    val scheduledArrival: Instant? = null,
    val status: TrainStatus = TrainStatus.UNKNOWN,
    val delayMinutes: Int? = null,
    val scheduledPlatform: String? = null,
    val actualPlatform: String? = null,
    val operator: Operator? = null,
    /** Source-backed category; null when the provider supplies none (T7.10). */
    val category: TrainCategory? = null,
)

data class TrainStop(
    val station: Station,
    val scheduledArrival: Instant? = null,
    val scheduledDeparture: Instant? = null,
    val actualArrival: Instant? = null,
    val actualDeparture: Instant? = null,
    val scheduledPlatform: String? = null,
    val actualPlatform: String? = null,
    val delayMinutes: Int? = null,
    val status: StopStatus = StopStatus.UNKNOWN,
)

data class TrainRun(
    val summary: TrainRunSummary,
    val stops: List<TrainStop> = emptyList(),
    /** Source-backed operational position; null when the provider supplies none (T7.10). */
    val position: OperationalPosition? = null,
)

/**
 * A source-backed operational position observation (T7.10, FR-POSITION-001/002).
 *
 * This is a qualified route-progress observation, never GPS: [stationName] is
 * the last-detection station name exactly as reported by the source (null when
 * the source reports its unknown marker or nothing), and [observedAt] is the
 * source observation instant (null when the source supplies none — never fetch,
 * cache or render time). A null [OperationalPosition] on [TrainRun] means no
 * position information is available at all.
 */
data class OperationalPosition(
    val stationName: String?,
    val observedAt: Instant?,
)

/**
 * Presentation readiness of an operational position (T7.10, FR-POSITION-002).
 *
 * [Known] only when the source supplies both the reported station and its own
 * observation timestamp: a position indication without its timestamp is not a
 * complete observation and must be presented as [Unavailable]. The timestamp
 * is never fabricated from fetch, cache, scheduled or current time.
 */
sealed interface OperationalPositionDisplay {
    data class Known(val stationName: String, val observedAt: Instant) : OperationalPositionDisplay
    data object Unavailable : OperationalPositionDisplay
}

fun operationalPositionDisplay(position: OperationalPosition?): OperationalPositionDisplay {
    val stationName = position?.stationName
    val observedAt = position?.observedAt
    return if (stationName != null && observedAt != null) OperationalPositionDisplay.Known(stationName, observedAt)
    else OperationalPositionDisplay.Unavailable
}

/**
 * Evidence-aware route-stop progress marker (T7.10, FR-ROUTE-002).
 *
 * Unlike [StopStatus], which records the per-stop source observation, this
 * describes the stop's position in the journey: [NEXT] is the first scheduled
 * stop after the last completed one — a "next stop" indication, never a claim
 * that the train is currently at that station. [UNKNOWN] marks stops whose
 * progress cannot be resolved from evidence (including contradictory ordering).
 */
enum class StopProgress { COMPLETED, NEXT, FUTURE, CANCELLED, UNKNOWN }

/**
 * Derives qualified per-stop route progress from ordered stop observations.
 *
 * Uses only stop order and [StopStatus] evidence; the clock is never
 * consulted, so timetable comparison alone can never mark a stop completed.
 * Cancellation stays distinct from delay and unknown; contradictory ordering
 * (a scheduled stop before a completed one) resolves to [StopProgress.UNKNOWN]
 * rather than guessing.
 *
 * An [StopStatus.UNKNOWN] stop is an intentional evidence barrier: progress
 * inference must not skip over it, so no later stop can be marked [NEXT]
 * unless every stop between the last completed one and the candidate is
 * itself resolved (completed stops before it, cancelled stops which the train
 * skips). Stops past the barrier stay [FUTURE] at most — never [NEXT].
 */
fun routeProgress(stops: List<TrainStop>): List<StopProgress> {
    val lastCompleted = stops.indexOfLast { it.status == StopStatus.COMPLETED }
    val nextIndex = stops.withIndex()
        .indexOfFirst { (index, stop) -> index > lastCompleted && stop.status == StopStatus.SCHEDULED }
        .takeIf { it >= 0 }
        // The NEXT claim must not cross uninterpretable evidence: any UNKNOWN
        // stop strictly between the last completed stop and the candidate
        // vetoes it. Cancelled stops do not block (the train skips them).
        .takeUnless { candidate ->
            candidate != null && stops.subList(lastCompleted + 1, candidate)
                .any { it.status == StopStatus.UNKNOWN }
        }
    return stops.mapIndexed { index, stop ->
        when (stop.status) {
            StopStatus.CANCELLED -> StopProgress.CANCELLED
            StopStatus.COMPLETED -> StopProgress.COMPLETED
            StopStatus.UNKNOWN -> StopProgress.UNKNOWN
            StopStatus.SCHEDULED -> when {
                nextIndex != null && index == nextIndex -> StopProgress.NEXT
                index > lastCompleted -> StopProgress.FUTURE
                else -> StopProgress.UNKNOWN
            }
        }
    }
}

data class StationBoard(val station: Station, val kind: BoardKind, val trains: List<TrainRunSummary>)

sealed interface DataFreshness {
    data class Fresh(val fetchedAt: Instant, val sourceTimestamp: Instant?) : DataFreshness
    data class Stale(val fetchedAt: Instant, val age: Duration, val sourceTimestamp: Instant?) : DataFreshness
    data object Unknown : DataFreshness
}

/**
 * Display-age recomputation for elapsed local time (T7.14-B).
 *
 * A [DataFreshness.Fresh] value carries the instants that prove its age; any
 * observer holding those instants plus an injected clock can re-derive the
 * current classification without a new fetch. [aged] turns Fresh into Stale
 * once the TTL passes; [staleTransitionAt] is the next boundary a local
 * freshness watcher must re-evaluate at (null when no transition exists).
 * Stale and Unknown never become Fresh through time alone.
 */
fun DataFreshness.aged(now: Instant, ttl: Duration): DataFreshness = when (this) {
    is DataFreshness.Fresh -> {
        val age = (now - (sourceTimestamp ?: fetchedAt)).coerceAtLeast(Duration.ZERO)
        if (age < ttl && now - fetchedAt < ttl) this
        else DataFreshness.Stale(fetchedAt, age, sourceTimestamp)
    }
    else -> this
}

fun DataFreshness.staleTransitionAt(ttl: Duration): Instant? = when (this) {
    is DataFreshness.Fresh -> minOf(fetchedAt + ttl, (sourceTimestamp ?: fetchedAt) + ttl)
    else -> null
}
