package it.danielebufarini.trenify.core.domain

import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.StopStatus
import it.danielebufarini.trenify.core.model.TrainRun
import it.danielebufarini.trenify.core.model.TrainRunId
import it.danielebufarini.trenify.core.model.TrainStatus
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.jvm.JvmInline

@JvmInline
value class MonitorId(val value: String)

data class MonitorThresholds(
    val delayMinutes: Int? = 15,
    val notifyDelay: Boolean = true,
    val notifyPlatform: Boolean = true,
    val notifyCancellation: Boolean = true,
    val notifyDeparture: Boolean = true,
    val notifyArrival: Boolean = true,
) {
    init {
        require(delayMinutes == null || delayMinutes > 0)
    }
}

data class MonitoredTrainSnapshot(
    val train: TrainRun,
    val freshness: DataFreshness,
    val evaluatedAt: Instant,
)

/**
 * Post-terminal retention for naturally ended monitors (T7.11, G-MVP-RETENTION).
 *
 * Exactly 24 elapsed hours from [TrainMonitor.endedAt]. Instant/Duration
 * arithmetic is used so Europe/Rome DST transitions cannot shorten or extend
 * the window to 23/25 hours.
 */
val MONITOR_POST_TERMINAL_RETENTION: Duration = 24.hours

/**
 * Stable terminal states (T7.11, FR-MONITOR-002). Only these end active
 * monitoring. PARTIALLY_CANCELLED, DIVERTED, RESCHEDULED, ROUTE_CHANGED,
 * DELAYED, temporary provider failure and unknown status are not terminal.
 *
 * A whole-train [TrainStatus.CANCELLED] is the final/definitive cancellation:
 * the provider-neutral model carries no provisional whole-train cancellation,
 * so any CANCELLED summary status is terminal. Partial cancellation has its
 * own status and never terminates.
 */
fun isTerminalStatus(status: TrainStatus): Boolean =
    status == TrainStatus.ARRIVED || status == TrainStatus.CANCELLED

/**
 * Monitor lifecycle (T7.11), stored as [TrainMonitor.endedAt]:
 *
 * - ACTIVE: row exists, enabled, `endedAt == null`; eligible for polling.
 * - ENDED / RECENTLY_ENDED: row exists, enabled, `endedAt != null`; retained
 *   read-only while `now < endedAt + 24h`, never polled.
 * - EXPIRED: retention deadline passed; excluded from visible state, eligible
 *   for cleanup on the next normal client opportunity.
 * - REMOVED: no row (manual Stop, manual early removal, or cleanup).
 *
 * The two-day [TrainMonitor.expiresAt] safety lifetime limits ACTIVE monitors
 * only and is unrelated to post-terminal retention. `endedAt` is written once
 * at the first committed terminal transition and never moves.
 */
data class TrainMonitor(
    val id: MonitorId,
    val trainRunId: TrainRunId,
    val enabled: Boolean,
    val thresholds: MonitorThresholds,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val lastSnapshot: MonitoredTrainSnapshot? = null,
    val lastEvent: TrainMonitorEvent? = null,
    /**
     * Per-train notification delivery switch (T7.7). Muting gates delivery
     * only: monitor evaluation and snapshot progression continue while muted,
     * and unmuting never replays changes from the muted period.
     */
    val notificationsEnabled: Boolean = true,
    /**
     * Terminal transition instant (T7.11). Null while ACTIVE; set once when a
     * stable ARRIVED/final CANCELLED state commits. Never reset, never moved
     * by repeated terminal observations or restart.
     */
    val endedAt: Instant? = null,
    /**
     * Optimistic-concurrency version of the accepted monitor snapshot (T7.11
     * corrective). Bumped on every accepted snapshot commit; evaluations
     * commit against the version they were based on so stale refreshes are
     * rejected instead of duplicating events or regressing newer state.
     */
    val snapshotVersion: Long = 0,
    /**
     * Presentation-only refresh health for the retained snapshot (T7.14
     * corrective): the typed failure of the latest attempted refresh that
     * produced no new accepted train observation (offline fallback or
     * warning-backed stale replay), with the instant of that attempt. Null
     * after any clean accepted refresh. Never a new observation: it changes
     * no snapshot, event, delay/platform/status, endedAt, version or
     * ordering generation, and ended monitors never carry it.
     */
    val refreshFailure: DomainFailure? = null,
    val refreshFailedAt: Instant? = null,
)

/** True once the stable terminal transition has committed. Monotonic. */
val TrainMonitor.isEnded: Boolean get() = endedAt != null

/** Retention deadline: `endedAt + 24h`, or null while active. */
fun TrainMonitor.visibleUntil(): Instant? = endedAt?.plus(MONITOR_POST_TERMINAL_RETENTION)

/** True when an ended monitor must no longer be exposed to the user. */
fun TrainMonitor.isRetentionExpired(now: Instant): Boolean {
    val until = visibleUntil() ?: return false
    return now >= until
}

enum class MonitorEventKind {
    DELAY,
    CANCELLATION,
    PARTIAL_CANCELLATION,
    PLATFORM,
    SCHEDULE,
    STATUS,
    DEPARTURE,
    ARRIVAL,
    ROUTE_CHANGED,
}

sealed interface TrainMonitorEvent {
    val trainRunId: TrainRunId
    val kind: MonitorEventKind
    val fingerprint: String

    data class DelayThresholdCrossed(
        override val trainRunId: TrainRunId,
        val previousMinutes: Int?,
        val currentMinutes: Int,
        val thresholdMinutes: Int,
    ) : TrainMonitorEvent {
        override val kind = MonitorEventKind.DELAY
        // A null previous delay only survives in rows persisted before
        // unknown-delay evidence was excluded: it fingerprints in the current
        // band rather than fabricating a zero band.
        override val fingerprint = "delay:${delayBand(previousMinutes ?: currentMinutes, thresholdMinutes)}:${delayBand(currentMinutes, thresholdMinutes)}"
    }

    data class Cancelled(override val trainRunId: TrainRunId) : TrainMonitorEvent {
        override val kind = MonitorEventKind.CANCELLATION
        override val fingerprint = "cancelled"
    }

    data class PartiallyCancelled(
        override val trainRunId: TrainRunId,
        val cancelledStops: List<String>,
    ) : TrainMonitorEvent {
        override val kind = MonitorEventKind.PARTIAL_CANCELLATION
        override val fingerprint = "partial:${cancelledStops.sorted().joinToString(",")}"
    }

    data class PlatformChanged(
        override val trainRunId: TrainRunId,
        val stationName: String?,
        val previousPlatform: String?,
        val currentPlatform: String,
    ) : TrainMonitorEvent {
        override val kind = MonitorEventKind.PLATFORM
        override val fingerprint = "platform:${stationName.orEmpty()}:${previousPlatform.orEmpty()}:$currentPlatform"
    }

    data class ScheduleChanged(
        override val trainRunId: TrainRunId,
        val stationName: String,
    ) : TrainMonitorEvent {
        override val kind = MonitorEventKind.SCHEDULE
        override val fingerprint = "schedule:$stationName"
    }

    data class StatusChanged(
        override val trainRunId: TrainRunId,
        val previous: TrainStatus,
        val current: TrainStatus,
    ) : TrainMonitorEvent {
        override val kind = MonitorEventKind.STATUS
        override val fingerprint = "status:$previous:$current"
    }

    data class Departed(override val trainRunId: TrainRunId) : TrainMonitorEvent {
        override val kind = MonitorEventKind.DEPARTURE
        override val fingerprint = "departed"
    }

    data class Arrived(override val trainRunId: TrainRunId) : TrainMonitorEvent {
        override val kind = MonitorEventKind.ARRIVAL
        override val fingerprint = "arrived"
    }

    /**
     * Structural route change (T7.11): added, removed or reordered stops.
     *
     * Identity uses stable station IDs, never display strings. Stops whose
     * status is CANCELLED are excluded from the comparison: a newly cancelled
     * stop is partial-cancellation evidence reported via
     * [PartiallyCancelled], not a route change. Presentation-only differences
     * (timestamps, delay, platform, observation time, response order without
     * route-semantic change) never produce this event.
     */
    data class RouteChanged(
        override val trainRunId: TrainRunId,
        val addedStationIds: List<String>,
        val removedStationIds: List<String>,
    ) : TrainMonitorEvent {
        override val kind = MonitorEventKind.ROUTE_CHANGED
        override val fingerprint =
            "route:${addedStationIds.sorted().joinToString(",")}:${removedStationIds.sorted().joinToString(",")}"
    }
}

private fun delayBand(delay: Int, threshold: Int): Int = when {
    delay < threshold -> 0
    else -> delay / threshold
}

interface MonitoringRepository {
    suspend fun createMonitor(
        trainRunId: TrainRunId,
        thresholds: MonitorThresholds = MonitorThresholds(),
        expiresAt: Instant? = null,
    ): TrainMonitor

    suspend fun removeMonitor(trainRunId: TrainRunId)

    /**
     * Explicit per-monitor preference edits (T7.7). Both preserve monitor
     * identity, enabled state, creation metadata, expiry, snapshots and event
     * claims without recreation, reset or synthetic change events.
     */
    suspend fun updateMonitorPreferences(trainRunId: TrainRunId, thresholds: MonitorThresholds)
    suspend fun setMonitorNotificationsEnabled(trainRunId: TrainRunId, enabled: Boolean)

    fun observeActiveMonitors(): Flow<List<TrainMonitor>>

    /**
     * Recently ended monitors (T7.11): terminal ARRIVED/final CANCELLED rows
     * within the 24-hour retention window. Never polled, read-only except for
     * manual early removal.
     */
    fun observeEndedMonitors(): Flow<List<TrainMonitor>>
    fun observeMonitor(trainRunId: TrainRunId): Flow<TrainMonitor?>
    /**
     * Versioned non-terminal commit (T7.11 corrective). The evaluation must
     * have been performed against the accepted snapshot carrying
     * [expectedVersion] ([TrainMonitor.snapshotVersion], 0 before the first
     * accepted snapshot): the commit is atomically rejected with
     * [EvaluationCommit.StaleBase] when the persisted version no longer
     * matches, so a stale refresh can neither duplicate a logical event nor
     * regress a newer accepted snapshot — even when the competing snapshots
     * are content-identical. Callers re-evaluate explicitly against the fresh
     * base instead of committing blindly.
     *
     * [observationOrder] is the persistence-shared refresh invocation
     * generation backing [snapshot] (T7.11 corrective pass 2, carried from
     * [DataResult.Data.refreshGeneration]): the commit is additionally
     * rejected when a newer observation generation was already accepted, so
     * a superseded observation can never be rebranded as current by a CAS
     * retry — even when the snapshot version it retries against still
     * matches. Null means no ordering evidence (pre-ordering callers): only
     * the version check applies.
     */
    suspend fun persistEvaluation(
        monitorId: MonitorId,
        snapshot: MonitoredTrainSnapshot,
        events: List<TrainMonitorEvent>,
        expectedVersion: Long,
        observationOrder: Long?,
    ): EvaluationCommit

    /**
     * Observation-ordered terminal commit (T7.11 final pass). The first
     * accepted terminal observation establishes `endedAt` (ACTIVE → ENDED);
     * strictly newer accepted terminal observations may correct the retained
     * final snapshot while `endedAt` and the ENDED lifecycle stay monotonic.
     *
     * [observationOrder] is the persistence-shared refresh invocation
     * generation backing [snapshot] (carried from
     * [DataResult.Data.refreshGeneration]): the commit obeys
     * `incomingOrder < accepted → reject as stale`,
     * `incomingOrder == accepted → idempotent (no duplicate transition)`,
     * `incomingOrder > accepted → accept correction, even when
     * `endedAt != null`. Null means no ordering evidence (legacy callers):
     * only the first ACTIVE → ENDED transition applies, later calls are
     * no-ops. Atomically coordinates the final snapshot,
     * `accepted_refresh_generation`, terminal event state and new event
     * claims while preserving the original `endedAt`. A call for a
     * removed/disabled monitor is a no-op and never resurrects it. Returns
     * the newly claimed events. At-most-once notification policy is
     * preserved: same-state corrections claim nothing new; different-state
     * corrections may claim the new terminal event; already displayed
     * notifications are never retracted.
     */
    suspend fun completeTerminally(
        monitorId: MonitorId,
        snapshot: MonitoredTrainSnapshot,
        events: List<TrainMonitorEvent>,
        endedAt: Instant,
        observationOrder: Long?,
    ): List<TrainMonitorEvent>

    /**
     * Manual early removal of a retained ended monitor (T7.11). Removes the
     * row and its state/events, idempotently. Never touches active monitors.
     */
    suspend fun removeEndedMonitor(trainRunId: TrainRunId)

    /**
     * Best-effort cleanup of retention-expired ended monitors (T7.11).
     * Idempotent; never touches active monitors or unrelated state.
     */
    suspend fun cleanupExpiredEnded(now: Instant)

    suspend fun disableExpired(now: Instant)
    suspend fun disableMonitor(monitorId: MonitorId)
    suspend fun claimNotification(event: TrainMonitorEvent, emittedAt: Instant, cooldown: Duration): Boolean

    /**
     * Records presentation-only refresh health for an ACTIVE monitor (T7.14
     * corrective): the latest attempted refresh produced no new accepted
     * train observation ([DataResult.Failure] or warning-backed fallback).
     * Updates only the refresh-health metadata of the retained snapshot —
     * never the snapshot, events, version, ordering generation or endedAt —
     * and is a no-op for missing, disabled or ended monitors. Cleared by any
     * later clean accepted snapshot commit.
     */
    suspend fun recordRefreshFailure(monitorId: MonitorId, failure: DomainFailure, failedAt: Instant)
}

class StartTrainMonitoring(
    private val repository: MonitoringRepository,
    private val clock: Clock = Clock.System,
    private val defaultLifetime: Duration = 2.days,
) {
    suspend operator fun invoke(
        trainRunId: TrainRunId,
        thresholds: MonitorThresholds = MonitorThresholds(),
        expiresAt: Instant? = clock.now() + defaultLifetime,
    ) = repository.createMonitor(trainRunId, thresholds, expiresAt)
}

class StopTrainMonitoring(private val repository: MonitoringRepository) {
    suspend operator fun invoke(trainRunId: TrainRunId) = repository.removeMonitor(trainRunId)
}

/**
 * Outcome of a versioned [MonitoringRepository.persistEvaluation] commit.
 */
sealed interface EvaluationCommit {
    /** The evaluation committed against the expected base; lists newly claimed events. */
    data class Committed(val inserted: List<TrainMonitorEvent>) : EvaluationCommit

    /**
     * The persisted snapshot moved after the evaluation base was read: the
     * commit changed nothing. The caller must re-read and re-evaluate
     * explicitly (bounded retry) instead of overwriting newer accepted state.
     */
    data object StaleBase : EvaluationCommit

    /** The monitor is missing, disabled or already ended: nothing committed. */
    data object NotActive : EvaluationCommit
}

class ObserveActiveMonitors(private val repository: MonitoringRepository) {
    operator fun invoke() = repository.observeActiveMonitors()
}

class ObserveEndedMonitors(private val repository: MonitoringRepository) {
    operator fun invoke() = repository.observeEndedMonitors()
}

class RemoveEndedMonitor(private val repository: MonitoringRepository) {
    suspend operator fun invoke(trainRunId: TrainRunId) = repository.removeEndedMonitor(trainRunId)
}

class ObserveTrainMonitor(private val repository: MonitoringRepository) {
    operator fun invoke(trainRunId: TrainRunId) = repository.observeMonitor(trainRunId)
}

class EvaluateTrainChanges {
    operator fun invoke(monitor: TrainMonitor, current: TrainRun): List<TrainMonitorEvent> {
        val previous = monitor.lastSnapshot?.train ?: return emptyList()
        val id = monitor.trainRunId
        val events = buildList {
            delayChange(monitor, previous, current)?.let(::add)
            if (monitor.thresholds.notifyCancellation) {
                if (previous.summary.status != TrainStatus.CANCELLED && current.summary.status == TrainStatus.CANCELLED) {
                    // Full cancellation subsumes stop-level evidence for this
                    // same terminal snapshot: exactly one Cancelled event, no
                    // accompanying PARTIALLY_CANCELLED for the same transition.
                    add(TrainMonitorEvent.Cancelled(id))
                } else {
                    partialCancellation(previous, current)?.let(::add)
                }
            }
            if (monitor.thresholds.notifyPlatform) addAll(platformChanges(previous, current))
            scheduleChange(previous, current)?.let(::add)
            routeChange(previous, current)?.let(::add)
            if (monitor.thresholds.notifyDeparture && !previous.hasDeparted() && current.hasDeparted()) {
                add(TrainMonitorEvent.Departed(id))
            }
            if (monitor.thresholds.notifyArrival && previous.summary.status != TrainStatus.ARRIVED &&
                current.summary.status == TrainStatus.ARRIVED
            ) {
                add(TrainMonitorEvent.Arrived(id))
            }
            if (previous.summary.status != current.summary.status && current.summary.status in
                setOf(TrainStatus.DIVERTED, TrainStatus.RESCHEDULED)
            ) {
                add(TrainMonitorEvent.StatusChanged(id, previous.summary.status, current.summary.status))
            }
        }
        return events.distinctBy { it.fingerprint }
    }

    private fun delayChange(monitor: TrainMonitor, previous: TrainRun, current: TrainRun): TrainMonitorEvent? {
        val threshold = monitor.thresholds.delayMinutes ?: return null
        if (!monitor.thresholds.notifyDelay) return null
        // Unknown remains unknown: without a known prior value there is no
        // truthful transition, and zero must never be fabricated as the
        // previous delay. A crossing is established only between known values.
        val old = previous.summary.delayMinutes ?: return null
        val new = current.summary.delayMinutes ?: return null
        return if (delayBand(old, threshold) != delayBand(new, threshold)) {
            TrainMonitorEvent.DelayThresholdCrossed(monitor.trainRunId, old, new, threshold)
        } else null
    }

    private fun partialCancellation(previous: TrainRun, current: TrainRun): TrainMonitorEvent? {
        val oldCancelled = previous.stops.filter { it.status == StopStatus.CANCELLED }.map { it.station.id }.toSet()
        val newlyCancelled = current.stops.filter { it.status == StopStatus.CANCELLED && it.station.id !in oldCancelled }
            .map { it.station.name }
        val becamePartial = previous.summary.status != TrainStatus.PARTIALLY_CANCELLED &&
            current.summary.status == TrainStatus.PARTIALLY_CANCELLED
        return if (becamePartial || newlyCancelled.isNotEmpty()) {
            TrainMonitorEvent.PartiallyCancelled(current.summary.id, newlyCancelled)
        } else null
    }

    private fun platformChanges(previous: TrainRun, current: TrainRun): List<TrainMonitorEvent> {
        val id = current.summary.id
        val summary = current.summary.actualPlatform?.takeIf { it != previous.summary.actualPlatform }?.let {
            TrainMonitorEvent.PlatformChanged(id, null, previous.summary.actualPlatform, it)
        }
        val oldStops = previous.stops.associateBy { it.station.id }
        val stops = current.stops.mapNotNull { stop ->
            val old = oldStops[stop.station.id]?.actualPlatform
            stop.actualPlatform?.takeIf { it != old }?.let {
                TrainMonitorEvent.PlatformChanged(id, stop.station.name, old, it)
            }
        }
        return listOfNotNull(summary) + stops
    }

    private fun scheduleChange(previous: TrainRun, current: TrainRun): TrainMonitorEvent? {
        val oldStops = previous.stops.associateBy { it.station.id }
        val changed = current.stops.firstOrNull { stop ->
            oldStops[stop.station.id]?.let { old ->
                old.scheduledArrival != stop.scheduledArrival || old.scheduledDeparture != stop.scheduledDeparture
            } ?: false
        }
        return changed?.let { TrainMonitorEvent.ScheduleChanged(current.summary.id, it.station.name) }
    }

    private fun routeChange(previous: TrainRun, current: TrainRun): TrainMonitorEvent? {
        // Route identity is the served-stop sequence (stable station IDs).
        // Cancelled stops are cancellation evidence reported via
        // Cancelled/PartiallyCancelled, so a stop that turns cancelled in
        // place (still listed) or appears already cancelled is not an added
        // or removed route stop for the same underlying transition.
        val oldServed = previous.stops.filter { it.status != StopStatus.CANCELLED }.map { it.station.id.value }
        val newServed = current.stops.filter { it.status != StopStatus.CANCELLED }.map { it.station.id.value }
        if (oldServed == newServed) return null
        val newAll = current.stops.map { it.station.id.value }.toSet()
        val added = (newServed.toSet() - oldServed.toSet()).toList()
        val removed = (oldServed.toSet() - newServed.toSet() - newAll).toList()
        if (added.isEmpty() && removed.isEmpty()) {
            // Same served set in a different order is a reordered route.
            // Otherwise the delta is purely in-place cancellation bookkeeping
            // (the stop is still listed as cancelled): no route event.
            if (oldServed.toSet() == newServed.toSet()) {
                return TrainMonitorEvent.RouteChanged(current.summary.id, emptyList(), emptyList())
            }
            return null
        }
        return TrainMonitorEvent.RouteChanged(current.summary.id, added, removed)
    }

    private fun TrainRun.hasDeparted(): Boolean = summary.status in setOf(TrainStatus.RUNNING, TrainStatus.ARRIVED) ||
        stops.any { it.status == StopStatus.COMPLETED || it.actualDeparture != null }
}

data class MonitoringPolicy(
    val foregroundRefreshInterval: Duration = 60.seconds,
    val notificationCooldown: Duration = 5.minutes,
    val backgroundRefreshInterval: Duration = 15.minutes,
)
