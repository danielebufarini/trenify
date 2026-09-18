package it.danielebufarini.trenify.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

class SqlDelightMonitoringRepository(
    private val database: TrenifyDatabase,
    private val clock: Clock = Clock.System,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MonitoringRepository {
    private val queries = database.realtimeQueries
    private val json = cacheJson()

    override suspend fun createMonitor(
        trainRunId: TrainRunId,
        thresholds: MonitorThresholds,
        expiresAt: Instant?,
    ): TrainMonitor {
        val id = MonitorId(trainRunId.key)
        val now = clock.now()
        // Monotonic lifecycle (T7.11 corrective): a retained ended monitor is
        // returned unchanged, never reactivated. A genuinely new ACTIVE row is
        // possible only after durable removal/expiry of the old one.
        val retained = database.transactionWithResult {
            queries.monitorLifecycleByTrain(trainRunId.key).executeAsOneOrNull()
                ?.takeIf { it.ended_at_epoch_ms != null }
        }
        if (retained != null) {
            return observeMonitor(trainRunId).firstValue() ?: error("Monitor was not persisted")
        }
        database.transaction {
            queries.insertActiveMonitor(
                id.value,
                trainRunId.key,
                trainRunId.provider.value,
                trainRunId.number.value,
                trainRunId.origin.value,
                trainRunId.serviceDate.toString(),
                1,
                thresholds.delayMinutes?.toLong(),
                thresholds.notifyDelay.long,
                thresholds.notifyPlatform.long,
                thresholds.notifyCancellation.long,
                thresholds.notifyDeparture.long,
                thresholds.notifyArrival.long,
                now.toEpochMilliseconds(),
                expiresAt?.toEpochMilliseconds(),
            )
            queries.updateActiveMonitor(
                1,
                thresholds.delayMinutes?.toLong(),
                thresholds.notifyDelay.long,
                thresholds.notifyPlatform.long,
                thresholds.notifyCancellation.long,
                thresholds.notifyDeparture.long,
                thresholds.notifyArrival.long,
                expiresAt?.toEpochMilliseconds(),
                id.value,
            )
        }
        return observeMonitor(trainRunId).firstValue()
            ?: error("Monitor was not persisted")
    }

    override suspend fun removeMonitor(trainRunId: TrainRunId) {
        database.transaction {
            queries.deleteMonitorEventsByTrain(trainRunId.key)
            queries.deleteMonitorStateByTrain(trainRunId.key)
            queries.deleteMonitorByTrain(trainRunId.key)
        }
    }

    override suspend fun updateMonitorPreferences(trainRunId: TrainRunId, thresholds: MonitorThresholds) {
        queries.updateMonitorPreferences(
            thresholds.delayMinutes?.toLong(),
            thresholds.notifyDelay.long,
            thresholds.notifyPlatform.long,
            thresholds.notifyCancellation.long,
            thresholds.notifyDeparture.long,
            thresholds.notifyArrival.long,
            trainRunId.key,
        )
    }

    override suspend fun setMonitorNotificationsEnabled(trainRunId: TrainRunId, enabled: Boolean) {
        queries.setMonitorNotifications(enabled.long, trainRunId.key)
    }

    override fun observeActiveMonitors(): Flow<List<TrainMonitor>> =
        queries.activeMonitors(clock.now().toEpochMilliseconds(), mapper = ::monitor)
            .asFlow()
            .mapToList(dispatcher)

    override fun observeEndedMonitors(): Flow<List<TrainMonitor>> =
        queries.allEndedMonitors(mapper = ::monitor)
            .asFlow()
            .mapToList(dispatcher)
            .transformLatest { all ->
                // Retention visibility is a logical time policy, not a
                // function of successful physical cleanup (T7.11 corrective
                // pass 2): the same continuous subscription hides an item
                // once now >= endedAt + 24h even when cleanupExpiredEnded
                // throws and no unrelated DB write ever arrives. The raw
                // query carries no time bound (a bound captured at subscribe
                // time would freeze); filtering uses the injected clock and
                // the flow wakes exactly at the next relevant deadline — no
                // busy loop, multiple deadlines via the minimum, timers
                // cancelled with the observer. Physical cleanup stays a
                // separate best-effort operation.
                while (true) {
                    val now = clock.now()
                    emit(all.filter { !it.isRetentionExpired(now) })
                    val nextExpiry = all.mapNotNull { it.visibleUntil() }
                        .filter { it > now }
                        .minOrNull() ?: return@transformLatest
                    // Re-read the clock: emit() may have suspended, and a
                    // negative delay would fail the observation.
                    delay((nextExpiry - clock.now()).coerceAtLeast(Duration.ZERO))
                }
            }

    override fun observeMonitor(trainRunId: TrainRunId): Flow<TrainMonitor?> =
        queries.monitorByTrain(trainRunId.key, mapper = ::monitor)
            .asFlow()
            .mapToOneOrNull(dispatcher)

    override suspend fun persistEvaluation(
        monitorId: MonitorId,
        snapshot: MonitoredTrainSnapshot,
        events: List<TrainMonitorEvent>,
        expectedVersion: Long,
        observationOrder: Long?,
    ): EvaluationCommit = database.transactionWithResult {
        // Stale-safe: a refresh that resumes after the terminal commit, after
        // removal, or after disable must not overwrite the final snapshot,
        // resurrect the monitor, or leave orphan state rows.
        if (!isActiveLocked(monitorId)) return@transactionWithResult EvaluationCommit.NotActive
        val last = events.lastOrNull()
        val previousState = queries.monitorStateById(monitorId.value).executeAsOneOrNull()
        val encodedSnapshot = json.encodeToString(snapshot.train.record())
        val freshness = snapshot.freshness.record()
        // An event-less commit refreshes snapshot metadata only: the recorded
        // last event is preserved, never wiped.
        val lastEventType = last?.kind?.name ?: previousState?.last_event_type
        val lastEventPayload = last?.let { json.encodeToString(EventRecord.from(it)) }
            ?: previousState?.last_event_payload
        val acceptedOrder = previousState?.accepted_refresh_generation ?: 0L
        // Evidence-aware ordering (T7.11 corrective pass 2): a superseded
        // observation is dropped even when the snapshot version still
        // matches, so a CAS retry can never rebrand older provider data as
        // current. A genuinely newer observation that lost only the version
        // race retries normally against the fresh base.
        if (observationOrder != null && observationOrder < acceptedOrder) {
            return@transactionWithResult EvaluationCommit.StaleBase
        }
        val committed = if (expectedVersion == 0L && previousState == null) {
            // First accepted snapshot: atomic single-statement insert; a
            // concurrent first writer wins and this caller retries explicitly.
            queries.insertMonitorState(
                monitorId.value,
                encodedSnapshot,
                freshness.kind,
                freshness.fetchedAt,
                freshness.sourceTimestamp,
                freshness.age,
                snapshot.evaluatedAt.toEpochMilliseconds(),
                lastEventType,
                lastEventPayload,
                1L,
                observationOrder ?: 0L,
            )
            queries.monitorStateWriteCount().executeAsOne() == 1L
        } else if (observationOrder == null) {
            // Pre-ordering callers: version check only, exactly as before.
            // Optimistic versioned commit: the single conditional UPDATE is
            // the linearization point. A concurrent committer that moved the
            // version matches zero rows here, so no stale snapshot or
            // duplicate logical event can slip through, even across
            // independent repository instances on separate connections.
            queries.updateMonitorStateVersioned(
                encodedSnapshot,
                freshness.kind,
                freshness.fetchedAt,
                freshness.sourceTimestamp,
                freshness.age,
                snapshot.evaluatedAt.toEpochMilliseconds(),
                lastEventType,
                lastEventPayload,
                expectedVersion + 1,
                monitorId.value,
                expectedVersion,
            )
            queries.monitorStateWriteCount().executeAsOne() == 1L
        } else {
            // Ordered commit: version AND observation generation are the
            // joint linearization point in one conditional UPDATE.
            queries.updateMonitorStateOrdered(
                encodedSnapshot,
                freshness.kind,
                freshness.fetchedAt,
                freshness.sourceTimestamp,
                freshness.age,
                snapshot.evaluatedAt.toEpochMilliseconds(),
                lastEventType,
                lastEventPayload,
                expectedVersion + 1,
                observationOrder,
                monitorId.value,
                expectedVersion,
            )
            queries.monitorStateWriteCount().executeAsOne() == 1L
        }
        if (!committed) return@transactionWithResult EvaluationCommit.StaleBase
        // A clean accepted refresh clears any recorded refresh-health warning.
        queries.clearMonitorRefreshFailure(monitorId.value)
        EvaluationCommit.Committed(insertEventsLocked(monitorId, snapshot, events))
    }

    override suspend fun completeTerminally(
        monitorId: MonitorId,
        snapshot: MonitoredTrainSnapshot,
        events: List<TrainMonitorEvent>,
        endedAt: Instant,
        observationOrder: Long?,
    ): List<TrainMonitorEvent> = database.transactionWithResult {
        // Observation-ordered terminal commit (T7.11 final pass): the first
        // accepted terminal observation establishes endedAt (ACTIVE → ENDED);
        // strictly newer accepted terminal observations correct the retained
        // final snapshot while endedAt and the ENDED lifecycle stay monotonic.
        // Lifecycle termination (first commit) and snapshot correction (later
        // strictly newer commits) are modelled explicitly: isActiveLocked is
        // not the sole gate. A removed/disabled monitor never resurrects.
        val lifecycle = queries.monitorLifecycleById(monitorId.value).executeAsOneOrNull()
            ?: return@transactionWithResult emptyList()
        if (lifecycle.enabled == 0L) return@transactionWithResult emptyList()
        val isEnded = lifecycle.ended_at_epoch_ms != null
        var previousState = queries.monitorStateById(monitorId.value).executeAsOneOrNull()
        var accepted = previousState?.accepted_refresh_generation ?: 0L
        // Fast-path order gate; the conditional UPDATEs below enforce the
        // same condition transactionally at the persistence boundary.
        if (observationOrder != null) {
            if (observationOrder < accepted) return@transactionWithResult emptyList()
            if (observationOrder == accepted && isEnded) return@transactionWithResult emptyList()
        } else if (isEnded) {
            return@transactionWithResult emptyList()
        }

        fun encoded(snapshot: MonitoredTrainSnapshot) = json.encodeToString(snapshot.train.record())
        fun lastType(previous: String?): String? = events.lastOrNull()?.kind?.name ?: previous
        fun lastPayload(previous: String?): String? =
            events.lastOrNull()?.let { json.encodeToString(EventRecord.from(it)) } ?: previous

        if (!isEnded) {
            // First accepted terminal observation: ACTIVE → ENDED. Establishes
            // endedAt only if currently null; advances the accepted generation
            // to the incoming order when one is carried.
            val newAccepted = observationOrder ?: accepted
            if (previousState == null) {
                if (observationOrder != null) {
                    val freshness = snapshot.freshness.record()
                    queries.insertMonitorState(
                        monitorId.value,
                        encoded(snapshot),
                        freshness.kind,
                        freshness.fetchedAt,
                        freshness.sourceTimestamp,
                        freshness.age,
                        snapshot.evaluatedAt.toEpochMilliseconds(),
                        lastType(null),
                        lastPayload(null),
                        1L,
                        newAccepted,
                    )
                    if (queries.monitorStateWriteCount().executeAsOne() == 1L) {
                        val inserted = insertEventsLocked(monitorId, snapshot, events)
                        queries.completeTerminalMonitor(endedAt.toEpochMilliseconds(), monitorId.value)
                        return@transactionWithResult inserted
                    }
                    // Lost the insert race to a concurrent first writer:
                    // re-read inside the same transaction and fall through to
                    // the ordered row-exists path below.
                    previousState = queries.monitorStateById(monitorId.value).executeAsOneOrNull()
                    accepted = previousState?.accepted_refresh_generation ?: 0L
                    val freshLifecycle = queries.monitorLifecycleById(monitorId.value).executeAsOneOrNull()
                        ?: return@transactionWithResult emptyList()
                    if (observationOrder < accepted) return@transactionWithResult emptyList()
                    if (observationOrder == accepted && freshLifecycle.ended_at_epoch_ms != null) {
                        return@transactionWithResult emptyList()
                    }
                    if (freshLifecycle.ended_at_epoch_ms != null) {
                        return@transactionWithResult correctEndedLocked(
                            monitorId, snapshot, events, observationOrder,
                        )
                    }
                } else {
                    val freshness = snapshot.freshness.record()
                    queries.putMonitorState(
                        monitorId.value,
                        encoded(snapshot),
                        freshness.kind,
                        freshness.fetchedAt,
                        freshness.sourceTimestamp,
                        freshness.age,
                        snapshot.evaluatedAt.toEpochMilliseconds(),
                        lastType(null),
                        lastPayload(null),
                        1L,
                        accepted,
                    )
                    val inserted = insertEventsLocked(monitorId, snapshot, events)
                    queries.completeTerminalMonitor(endedAt.toEpochMilliseconds(), monitorId.value)
                    queries.clearMonitorRefreshFailure(monitorId.value)
                    return@transactionWithResult inserted
                }
            }
            // Row exists and the monitor is still ACTIVE: guard the write
            // with WHERE accepted <= incoming so a concurrent newer commit
            // that landed between the read and the write wins instead.
            if (observationOrder != null) {
                val freshness = snapshot.freshness.record()
                queries.updateMonitorStateTerminalInitial(
                    encoded(snapshot),
                    freshness.kind,
                    freshness.fetchedAt,
                    freshness.sourceTimestamp,
                    freshness.age,
                    snapshot.evaluatedAt.toEpochMilliseconds(),
                    lastType(previousState?.last_event_type),
                    lastPayload(previousState?.last_event_payload),
                    observationOrder,
                    monitorId.value,
                )
                if (queries.monitorStateWriteCount().executeAsOne() != 1L) {
                    return@transactionWithResult emptyList()
                }
                val inserted = insertEventsLocked(monitorId, snapshot, events)
                queries.completeTerminalMonitor(endedAt.toEpochMilliseconds(), monitorId.value)
                queries.clearMonitorRefreshFailure(monitorId.value)
                return@transactionWithResult inserted
            }
            val freshness = snapshot.freshness.record()
            queries.putMonitorState(
                monitorId.value,
                encoded(snapshot),
                freshness.kind,
                freshness.fetchedAt,
                freshness.sourceTimestamp,
                freshness.age,
                snapshot.evaluatedAt.toEpochMilliseconds(),
                lastType(previousState?.last_event_type),
                lastPayload(previousState?.last_event_payload),
                (previousState?.snapshot_version ?: 0L) + 1,
                accepted,
            )
            val inserted = insertEventsLocked(monitorId, snapshot, events)
            queries.completeTerminalMonitor(endedAt.toEpochMilliseconds(), monitorId.value)
            queries.clearMonitorRefreshFailure(monitorId.value)
            return@transactionWithResult inserted
        }
        // Already ENDED with a strictly newer incoming terminal observation:
        // snapshot correction only — endedAt untouched, no reactivation, no
        // polling restart. The SQL guard enforces
        // WHERE incoming > accepted atomically.
        return@transactionWithResult correctEndedLocked(monitorId, snapshot, events, observationOrder)
    }

    private fun correctEndedLocked(
        monitorId: MonitorId,
        snapshot: MonitoredTrainSnapshot,
        events: List<TrainMonitorEvent>,
        observationOrder: Long?,
    ): List<TrainMonitorEvent> {
        // Caller guarantees an already-ended row with a strictly newer
        // incoming order, or a null order that was already rejected. Guard
        // again for the race-fallthrough path above.
        val order = observationOrder ?: return emptyList()
        val previousState = queries.monitorStateById(monitorId.value).executeAsOneOrNull()
        // State-first ordering: the conditional snapshot/generation write is
        // the linearization point. Events are claimed only after it succeeds,
        // so a superseded correction can never leave an orphan event claim
        // without its snapshot, nor a snapshot without its generation.
        val freshness = snapshot.freshness.record()
        if (previousState == null) {
            queries.insertMonitorState(
                monitorId.value,
                json.encodeToString(snapshot.train.record()),
                freshness.kind,
                freshness.fetchedAt,
                freshness.sourceTimestamp,
                freshness.age,
                snapshot.evaluatedAt.toEpochMilliseconds(),
                events.lastOrNull()?.kind?.name,
                events.lastOrNull()?.let { json.encodeToString(EventRecord.from(it)) },
                1L,
                order,
            )
            if (queries.monitorStateWriteCount().executeAsOne() != 1L) return emptyList()
        } else {
            queries.updateMonitorStateTerminalCorrection(
                json.encodeToString(snapshot.train.record()),
                freshness.kind,
                freshness.fetchedAt,
                freshness.sourceTimestamp,
                freshness.age,
                snapshot.evaluatedAt.toEpochMilliseconds(),
                events.lastOrNull()?.kind?.name ?: previousState.last_event_type,
                events.lastOrNull()?.let { json.encodeToString(EventRecord.from(it)) }
                    ?: previousState.last_event_payload,
                order,
                monitorId.value,
            )
            if (queries.monitorStateWriteCount().executeAsOne() != 1L) return emptyList()
        }
        // Same terminal state re-observed with a newer order refreshes the
        // retained snapshot but claims nothing new (stable event identity
        // dedupes); a different terminal state claims its new terminal event.
        // At-most-once notification policy holds; already displayed external
        // notifications are never retracted.
        return insertEventsLocked(monitorId, snapshot, events)
    }

    override suspend fun removeEndedMonitor(trainRunId: TrainRunId) {
        database.transaction {
            queries.deleteEndedMonitorEventsByTrain(trainRunId.key)
            queries.deleteEndedMonitorStateByTrain(trainRunId.key)
            queries.deleteEndedMonitorByTrain(trainRunId.key)
        }
    }

    override suspend fun cleanupExpiredEnded(now: Instant) {
        val expiredBefore = visibleAfter(now).toEpochMilliseconds()
        database.transaction {
            queries.deleteExpiredEndedMonitorEvents(expiredBefore)
            queries.deleteExpiredEndedMonitorState(expiredBefore)
            queries.cleanupExpiredEndedMonitors(expiredBefore)
        }
    }

    private fun isActiveLocked(monitorId: MonitorId): Boolean {
        val lifecycle = queries.monitorLifecycleById(monitorId.value).executeAsOneOrNull() ?: return false
        return lifecycle.enabled != 0L && lifecycle.ended_at_epoch_ms == null
    }

    private fun insertEventsLocked(
        monitorId: MonitorId,
        snapshot: MonitoredTrainSnapshot,
        events: List<TrainMonitorEvent>,
    ): List<TrainMonitorEvent> {
        val inserted = mutableListOf<TrainMonitorEvent>()
        events.forEach { event ->
            val key = eventKey(monitorId, event, snapshot.evaluatedAt)
            if (queries.eventByKey(key).executeAsOneOrNull() == null) {
                queries.putMonitorEvent(
                    key,
                    monitorId.value,
                    event.kind.name,
                    json.encodeToString(EventRecord.from(event)),
                    snapshot.evaluatedAt.toEpochMilliseconds(),
                )
                inserted += event
            }
        }
        return inserted
    }

    override suspend fun disableExpired(now: Instant) {
        queries.disableExpiredMonitors(now.toEpochMilliseconds())
    }

    override suspend fun disableMonitor(monitorId: MonitorId) {
        queries.disableMonitor(monitorId.value)
    }

    override suspend fun claimNotification(
        event: TrainMonitorEvent,
        emittedAt: Instant,
        cooldown: Duration,
    ): Boolean = database.transactionWithResult {
        val monitorId = MonitorId(event.trainRunId.key)
        val key = eventKey(monitorId, event, emittedAt)
        val stored = queries.eventByKey(key).executeAsOneOrNull() ?: return@transactionWithResult false
        if (stored.notified_at_epoch_ms != null) return@transactionWithResult false
        val last = queries.lastNotifiedEvent(monitorId.value, event.kind.name).executeAsOneOrNull()
        if (last?.notified_at_epoch_ms?.let { emittedAt.toEpochMilliseconds() - it < cooldown.inWholeMilliseconds } == true) {
            return@transactionWithResult false
        }
        queries.markEventNotified(emittedAt.toEpochMilliseconds(), key)
        true
    }

    @Suppress("LongParameterList")
    private fun monitor(
        id: String,
        trainRunId: String,
        providerId: String,
        trainNumber: String,
        originRef: String,
        serviceDate: String,
        enabled: Long,
        delayThresholdMinutes: Long?,
        notifyDelay: Long,
        notifyPlatform: Long,
        notifyCancellation: Long,
        notifyDeparture: Long,
        notifyArrival: Long,
        createdAt: Long,
        expiresAt: Long?,
        notifyEnabled: Long,
        endedAt: Long?,
        refreshFailure: String?,
        refreshFailedAt: Long?,
        snapshot: String?,
        freshnessKind: String?,
        fetchedAt: Long?,
        sourceTimestamp: Long?,
        freshnessAge: Long?,
        evaluatedAt: Long?,
        lastEventType: String?,
        lastEventPayload: String?,
        snapshotVersion: Long?,
    ): TrainMonitor {
        @Suppress("UNUSED_VARIABLE") val persistentTrainRunId = trainRunId
        @Suppress("UNUSED_VARIABLE") val persistentLastEventType = lastEventType
        val runId = TrainRunId(
            ProviderId(providerId),
            TrainNumber(trainNumber),
            ExternalStationRef(originRef),
            LocalDate.parse(serviceDate),
        )
        val lastSnapshot = snapshot?.let { payload ->
            MonitoredTrainSnapshot(
                json.decodeFromString<TrainSnapshotRecord>(payload).model(),
                freshness(freshnessKind, fetchedAt, sourceTimestamp, freshnessAge),
                Instant.fromEpochMilliseconds(requireNotNull(evaluatedAt)),
            )
        }
        val lastEvent = lastEventPayload?.let { json.decodeFromString<EventRecord>(it).model(runId) }
        return TrainMonitor(
            MonitorId(id),
            runId,
            enabled != 0L,
            MonitorThresholds(
                delayThresholdMinutes?.toInt(),
                notifyDelay != 0L,
                notifyPlatform != 0L,
                notifyCancellation != 0L,
                notifyDeparture != 0L,
                notifyArrival != 0L,
            ),
            Instant.fromEpochMilliseconds(createdAt),
            expiresAt?.let(Instant::fromEpochMilliseconds),
            lastSnapshot,
            lastEvent,
            notificationsEnabled = notifyEnabled != 0L,
            endedAt = endedAt?.let(Instant::fromEpochMilliseconds),
            snapshotVersion = snapshotVersion ?: 0L,
            refreshFailure = refreshFailure?.let(DomainFailure::valueOf),
            refreshFailedAt = refreshFailedAt?.let(Instant::fromEpochMilliseconds),
        )
    }

    override suspend fun recordRefreshFailure(monitorId: MonitorId, failure: DomainFailure, failedAt: Instant) {
        // Presentation-only metadata (T7.14 corrective): the guarded UPDATE
        // is a no-op for missing, disabled or ended monitors, and it never
        // touches the snapshot, events, version or ordering generation.
        queries.recordMonitorRefreshFailure(failure.name, failedAt.toEpochMilliseconds(), monitorId.value)
    }

    private fun eventKey(monitorId: MonitorId, event: TrainMonitorEvent, emittedAt: Instant) =
        // Terminal events use stable identity so a repeated ARRIVED/final
        // CANCELLED observation (or a restart) can never duplicate the event
        // or extend retention. Non-terminal events keep the evaluated-at
        // qualifier so genuine recurring transitions can still re-notify.
        when (event) {
            is TrainMonitorEvent.Arrived, is TrainMonitorEvent.Cancelled ->
                "${monitorId.value}:${event.fingerprint}"
            else -> "${monitorId.value}:${emittedAt.toEpochMilliseconds()}:${event.fingerprint}"
        }
}

/**
 * Retention visibility cutoff: an ended monitor is visible while
 * `endedAt > now - 24h`, i.e. `now < endedAt + 24h`. Instant arithmetic keeps
 * exactly 24 elapsed hours across DST changes.
 */
private fun visibleAfter(now: Instant): Instant = now - MONITOR_POST_TERMINAL_RETENTION

private suspend fun <T> Flow<T>.firstValue(): T = first()
private val Boolean.long: Long get() = if (this) 1 else 0

private data class FreshnessRecord(
    val kind: String,
    val fetchedAt: Long?,
    val sourceTimestamp: Long?,
    val age: Long?,
)

private fun DataFreshness.record() = when (this) {
    is DataFreshness.Fresh -> FreshnessRecord("FRESH", fetchedAt.toEpochMilliseconds(), sourceTimestamp?.toEpochMilliseconds(), null)
    is DataFreshness.Stale -> FreshnessRecord("STALE", fetchedAt.toEpochMilliseconds(), sourceTimestamp?.toEpochMilliseconds(), age.inWholeMilliseconds)
    DataFreshness.Unknown -> FreshnessRecord("UNKNOWN", null, null, null)
}

private fun freshness(kind: String?, fetchedAt: Long?, sourceTimestamp: Long?, age: Long?): DataFreshness = when (kind) {
    "FRESH" -> DataFreshness.Fresh(
        Instant.fromEpochMilliseconds(requireNotNull(fetchedAt)),
        sourceTimestamp?.let(Instant::fromEpochMilliseconds),
    )
    "STALE" -> DataFreshness.Stale(
        Instant.fromEpochMilliseconds(requireNotNull(fetchedAt)),
        Duration.parse("${requireNotNull(age)}ms"),
        sourceTimestamp?.let(Instant::fromEpochMilliseconds),
    )
    else -> DataFreshness.Unknown
}

@Serializable
private data class EventRecord(
    val kind: String,
    val previousDelay: Int? = null,
    val currentDelay: Int? = null,
    val threshold: Int? = null,
    val station: String? = null,
    val previousPlatform: String? = null,
    val currentPlatform: String? = null,
    val cancelledStops: List<String> = emptyList(),
    val previousStatus: String? = null,
    val currentStatus: String? = null,
    val routeAdded: List<String> = emptyList(),
    val routeRemoved: List<String> = emptyList(),
) {
    fun model(id: TrainRunId): TrainMonitorEvent = when (MonitorEventKind.valueOf(kind)) {
        MonitorEventKind.DELAY -> TrainMonitorEvent.DelayThresholdCrossed(
            id, previousDelay, requireNotNull(currentDelay), requireNotNull(threshold),
        )
        MonitorEventKind.CANCELLATION -> TrainMonitorEvent.Cancelled(id)
        MonitorEventKind.PARTIAL_CANCELLATION -> TrainMonitorEvent.PartiallyCancelled(id, cancelledStops)
        MonitorEventKind.PLATFORM -> TrainMonitorEvent.PlatformChanged(
            id, station, previousPlatform, requireNotNull(currentPlatform),
        )
        MonitorEventKind.SCHEDULE -> TrainMonitorEvent.ScheduleChanged(id, requireNotNull(station))
        MonitorEventKind.STATUS -> TrainMonitorEvent.StatusChanged(
            id,
            TrainStatus.valueOf(requireNotNull(previousStatus)),
            TrainStatus.valueOf(requireNotNull(currentStatus)),
        )
        MonitorEventKind.DEPARTURE -> TrainMonitorEvent.Departed(id)
        MonitorEventKind.ARRIVAL -> TrainMonitorEvent.Arrived(id)
        MonitorEventKind.ROUTE_CHANGED -> TrainMonitorEvent.RouteChanged(id, routeAdded, routeRemoved)
    }

    companion object {
        fun from(event: TrainMonitorEvent): EventRecord = when (event) {
            is TrainMonitorEvent.DelayThresholdCrossed -> EventRecord(
                event.kind.name, event.previousMinutes, event.currentMinutes, event.thresholdMinutes,
            )
            is TrainMonitorEvent.Cancelled -> EventRecord(event.kind.name)
            is TrainMonitorEvent.PartiallyCancelled -> EventRecord(event.kind.name, cancelledStops = event.cancelledStops)
            is TrainMonitorEvent.PlatformChanged -> EventRecord(
                event.kind.name,
                station = event.stationName,
                previousPlatform = event.previousPlatform,
                currentPlatform = event.currentPlatform,
            )
            is TrainMonitorEvent.ScheduleChanged -> EventRecord(event.kind.name, station = event.stationName)
            is TrainMonitorEvent.StatusChanged -> EventRecord(
                event.kind.name,
                previousStatus = event.previous.name,
                currentStatus = event.current.name,
            )
            is TrainMonitorEvent.Departed -> EventRecord(event.kind.name)
            is TrainMonitorEvent.Arrived -> EventRecord(event.kind.name)
            is TrainMonitorEvent.RouteChanged -> EventRecord(
                event.kind.name,
                routeAdded = event.addedStationIds,
                routeRemoved = event.removedStationIds,
            )
        }
    }
}
