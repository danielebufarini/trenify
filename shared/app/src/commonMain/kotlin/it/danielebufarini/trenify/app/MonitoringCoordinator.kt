package it.danielebufarini.trenify.app

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.platform.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration

@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
class MonitoringCoordinator(
    private val monitoringRepository: MonitoringRepository,
    private val trainRepository: TrainRepository,
    private val platformServices: PlatformServices,
    private val notificationSettings: NotificationSettingsRepository,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val policy: MonitoringPolicy = MonitoringPolicy(),
    private val evaluate: EvaluateTrainChanges = EvaluateTrainChanges(),
    private val localizer: NotificationLocalizer = ResourceNotificationLocalizer(),
) {
    init {
        platformServices.backgroundScheduler.register(BACKGROUND_TASK_ID, ::performBackgroundRefresh)
        scope.launch {
            // Startup cleanup: a retention-expired ended monitor must be
            // eligible for removal even when no active monitor keeps the
            // refresh loop alive. Best-effort (T7.11 corrective): a cleanup
            // failure must not kill monitoring; it retries on later
            // opportunities below.
            containOperational { monitoringRepository.cleanupExpiredEnded(clock.now()) }
            combine(
                monitoringRepository.observeActiveMonitors()
                    .map { monitors -> monitors.map { it.id } }
                    .distinctUntilChanged(),
                platformServices.applicationState.state(),
                platformServices.connectivity.status(),
            ) { monitorIds, applicationState, connectivity -> Triple(monitorIds, applicationState, connectivity) }
                .collectLatest { (monitorIds, applicationState, connectivity) ->
                    // Retention cleanup on every orchestration transition
                    // (foreground/background/connectivity/active-set change) so
                    // an expiry that passes while the app runs is picked up
                    // without waiting for the next poll cycle.
                    containOperational { monitoringRepository.cleanupExpiredEnded(clock.now()) }
                    when {
                        monitorIds.isEmpty() -> platformServices.backgroundScheduler.cancel(BACKGROUND_TASK_ID)
                        applicationState == ApplicationState.Foreground && connectivity != ConnectivityStatus.Unavailable -> {
                            platformServices.backgroundScheduler.cancel(BACKGROUND_TASK_ID)
                            while (true) {
                                refreshOnce()
                                delay(policy.foregroundRefreshInterval)
                            }
                        }
                        applicationState == ApplicationState.Background -> scheduleBackgroundRefresh()
                    }
                }
        }
        // Deadline-aware retention watcher (T7.11 corrective): a
        // continuously-subscribed observer must see an ended monitor disappear
        // at exactly endedAt + 24h without any unrelated DB write. The watcher
        // delays until the next relevant expiry (no busy loop, no backend,
        // injected clock) and then runs the normal best-effort cleanup, whose
        // deletion reactively updates every observer.
        scope.launch {
            monitoringRepository.observeEndedMonitors().collectLatest { ended ->
                val nextExpiry = ended.mapNotNull { it.endedAt }.minOrNull()
                    ?.plus(MONITOR_POST_TERMINAL_RETENTION)
                if (nextExpiry != null) {
                    delay((nextExpiry - clock.now()).coerceAtLeast(Duration.ZERO))
                    containOperational { monitoringRepository.cleanupExpiredEnded(clock.now()) }
                }
            }
        }
    }

    /**
     * Refreshes every active monitor once. Returns the notification-delivery
     * attempts created by this refresh (T7.11 corrective pass 2): the
     * foreground loop ignores them (delivery stays decoupled from polling),
     * while the one-shot background path joins them before reporting the job
     * complete so closing the graph cannot destroy a pending terminal
     * delivery.
     */
    suspend fun refreshOnce(): List<Job> {
        // Fail fast when the coordinator itself is shut down; the caller's
        // context is checked below so external cancellation also propagates.
        scope.ensureActive()
        currentCoroutineContext().ensureActive()
        containOperational {
            monitoringRepository.disableExpired(clock.now())
            monitoringRepository.cleanupExpiredEnded(clock.now())
        }
        val monitors = monitoringRepository.observeActiveMonitors().first().distinctBy { it.trainRunId }
        val deliveries = mutableListOf<Job>()
        monitors.forEach { monitor ->
            currentCoroutineContext().ensureActive()
            try {
                refreshMonitor(monitor)?.let(deliveries::add)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // One monitor's failure (provider, persistence) must not
                // destroy monitoring for every other active monitor. Failed
                // commits change nothing, so the next scheduled refresh
                // retries truthfully; no tight loop is created here.
            }
        }
        return deliveries
    }

    private suspend fun refreshMonitor(monitor: TrainMonitor): Job? {
        val result = trainRepository.refreshTrain(monitor.trainRunId, force = true)
        // T7.11 ordering rule (unchanged): failed refreshes are never
        // evaluated as new train observations. T7.14 adds presentation
        // truth: the failure is recorded as refresh-health metadata on the
        // retained snapshot so it renders stale-with-warning instead of
        // silently staying Fresh. This changes no snapshot, event, version
        // or ordering generation.
        if (result is DataResult.Failure) {
            recordRefreshHealth(monitor, result.error)
            return null
        }
        if (result !is DataResult.Data) return null
        // Failed-provider fallbacks are display truth, never monitoring
        // observations (T7.11 final pass): a warning-backed result replays an
        // old cache without ordering evidence, so evaluating it could replace
        // a newer accepted snapshot with stale content through the legacy
        // version-only path. Monitoring evaluation requires a clean result
        // with explicit accepted ordering evidence.
        val warning = result.warning
        if (warning != null) {
            recordRefreshHealth(monitor, warning)
            return null
        }
        if (result.refreshGeneration == null) return null
        val evaluatedAt = clock.now()
        // Persistence-shared refresh ordering (T7.11 corrective pass 2):
        // the returned observation is the currently accepted one with its
        // invocation generation — never an older provider response rebranded
        // as current merely because it completed later.
        val observationOrder = result.refreshGeneration
        // The fetch above may have suspended (offline wait, slow
        // provider) while the user edited preferences. Re-read the
        // persisted monitor so a stale copy can never override a newly
        // saved mute, threshold or flag through evaluation or delivery.
        // The re-read also observes a terminal transition committed by
        // a concurrent refresh: ended monitors are skipped here, and
        // the persistence guards below make a stale continuation that
        // passed this check harmless as well.
        val current = monitoringRepository.observeMonitor(monitor.trainRunId).first()
            ?: return null
        // Removed monitors observe as null and disabled ones never resolve
        // here: neither path below may resurrect them.
        if (!current.enabled) return null
        val snapshot = MonitoredTrainSnapshot(result.value, result.freshness, evaluatedAt)
        val status = result.value.summary.status
        if (isTerminalStatus(status)) {
            // Terminal path (T7.11 final pass): the first accepted terminal
            // observation establishes endedAt (ACTIVE → ENDED); strictly
            // newer accepted terminal observations correct the retained final
            // snapshot while endedAt and ENDED stay monotonic. The repository
            // orders by the persistence-shared observation generation, so this
            // path must run even when the reread monitor already shows
            // endedAt != null — gating on isActive alone would drop valid
            // newer terminal corrections and leave cache and monitor in
            // disagreement. Corrections never reactivate polling, move
            // endedAt, or resurrect removed/disabled monitors.
            val events = withTerminalEvent(current, result.value, evaluate(current, result.value))
            val newEvents = monitoringRepository.completeTerminally(
                current.id, snapshot, events, evaluatedAt, observationOrder,
            )
            return deliverAsync(current.trainRunId, newEvents, evaluatedAt)
        } else {
            // Non-terminal observations never touch an ended monitor.
            if (current.endedAt != null) return null
            return commitEvaluation(current, snapshot, result.value, observationOrder)
        }
    }

    /**
     * Presentation-only refresh-health write (T7.14 corrective): records the
     * typed failure of an attempted refresh that produced no new accepted
     * observation. Best-effort — a persistence failure here must not fail
     * the refresh loop; the next refresh retries truthfully. Ended monitors
     * stay read-only: the repository call is a no-op for them.
     */
    private suspend fun recordRefreshHealth(monitor: TrainMonitor, failure: DomainFailure) {
        try {
            monitoringRepository.recordRefreshFailure(monitor.id, failure, clock.now())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Best-effort presentation metadata only.
        }
    }

    private suspend fun commitEvaluation(
        monitor: TrainMonitor,
        snapshot: MonitoredTrainSnapshot,
        observation: it.danielebufarini.trenify.core.model.TrainRun,
        observationOrder: Long?,
    ): Job? {
        when (val commit = monitoringRepository.persistEvaluation(
            monitor.id, snapshot, evaluate(monitor, observation), monitor.snapshotVersion, observationOrder,
        )) {
            is EvaluationCommit.Committed -> return deliverAsync(monitor.trainRunId, commit.inserted, snapshot.evaluatedAt)
            EvaluationCommit.StaleBase -> {
                // Bounded single retry (T7.11 corrective pass 2): the
                // retried observation is the accepted one carried from
                // refreshTrain — never an older provider response — and the
                // repository additionally drops it when a newer observation
                // generation was accepted meanwhile. A genuinely newer
                // observation that lost only the version race is re-evaluated
                // once against the fresh base; a superseded one is dropped.
                // A second contention defers to the next scheduled refresh;
                // no tight loop is created here.
                val fresh = monitoringRepository.observeMonitor(monitor.trainRunId).first()
                    ?: return null
                if (!fresh.enabled || fresh.endedAt != null) return null
                when (val retry = monitoringRepository.persistEvaluation(
                    fresh.id, snapshot, evaluate(fresh, observation), fresh.snapshotVersion, observationOrder,
                )) {
                    is EvaluationCommit.Committed -> return deliverAsync(fresh.trainRunId, retry.inserted, snapshot.evaluatedAt)
                    else -> return null
                }
            }
            EvaluationCommit.NotActive -> return null
        }
    }

    /**
     * Decoupled delivery (T7.11 corrective): the terminal commit removes the
     * monitor from active observation, which cancels/replaces the polling
     * collector running this refresh. Launching the post-commit delivery
     * attempt in the coordinator scope keeps that collector's self-
     * cancellation from silently dropping an already committed terminal
     * event. The scope is the application coordinator scope, so normal
     * shutdown semantics stay controlled, and the idempotent claim policy
     * keeps the attempt exactly-once per event.
     *
     * Returns the delivery attempt so the one-shot background path can await
     * the attempts its own refresh created (T7.11 corrective pass 2). The
     * foreground loop ignores the return and stays decoupled.
     */
    private fun deliverAsync(
        trainRunId: it.danielebufarini.trenify.core.model.TrainRunId,
        events: List<TrainMonitorEvent>,
        emittedAt: kotlin.time.Instant,
    ): Job? {
        if (events.isEmpty()) return null
        return scope.launch {
            containOperational { dispatch(trainRunId, events, emittedAt) }
        }
    }

    /**
     * Narrow operational-error boundary (T7.11 corrective): best-effort
     * orchestration work (cleanup, delivery) must never terminate monitoring.
     * Cancellation always propagates; other failures are contained and
     * retried on later normal opportunities.
     */
    private suspend fun containOperational(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Deliberately best-effort: failed commits change nothing, so
            // truthful state is preserved for the next opportunity.
        }
    }

    /**
     * Guarantees the terminal observation carries its terminal event even
     * when the previous snapshot is absent (first refresh already terminal)
     * or already matches: evaluation alone reports transitions, and without
     * a previous snapshot there is no transition to report. The event is
     * source-backed by the terminal provider-neutral status.
     */
    private fun withTerminalEvent(
        monitor: TrainMonitor,
        current: it.danielebufarini.trenify.core.model.TrainRun,
        events: List<TrainMonitorEvent>,
    ): List<TrainMonitorEvent> {
        val terminal = when (current.summary.status) {
            TrainStatus.ARRIVED -> TrainMonitorEvent.Arrived(monitor.trainRunId)
            TrainStatus.CANCELLED -> TrainMonitorEvent.Cancelled(monitor.trainRunId)
            else -> return events
        }
        return if (events.any { it.kind == terminal.kind }) events else events + terminal
    }

    /**
     * One-shot background refresh (T7.11 corrective pass 2): the background
     * graph closes right after this returns, so it awaits the delivery
     * attempts its own refresh created before reporting the job complete —
     * otherwise closing would cancel a pending terminal delivery it just
     * committed. Cancelling this operation cancels the pending deliveries
     * instead of leaking them. Provider/network work is untouched (still
     * cancellable, no NonCancellable, no global scope, no arbitrary delay).
     */
    suspend fun performBackgroundRefresh() {
        val deliveries = refreshOnce()
        try {
            deliveries.joinAll()
        } catch (cancelled: CancellationException) {
            deliveries.forEach { it.cancel() }
            throw cancelled
        }
        if (monitoringRepository.observeActiveMonitors().first().isNotEmpty()) scheduleBackgroundRefresh()
    }

    /**
     * Delivery precedence (T7.7): a train event is delivered only when the
     * installation switch, that train's switch, the applicable event flag and
     * the effective OS permission all allow it at claim time. Preferences are
     * resolved fresh here so a mute saved after the fetch still prevents
     * delivery. The installation switch gates delivery only: it never stops
     * polling, disables monitors, or deletes snapshots/history.
     *
     * At-most-once claim policy (T7.11 corrective): [claimNotification] marks
     * the persisted event before [NotificationPresenter.show], so a crash or
     * presenter failure after the claim can lose one best-effort delivery but
     * can never duplicate it. True exactly-once external delivery is not
     * possible for a client-only app; duplicates would be user-visible spam,
     * while a lost window is re-covered by the next polled state. No
     * distributed outbox is introduced for this.
     */
    private suspend fun dispatch(
        trainRunId: it.danielebufarini.trenify.core.model.TrainRunId,
        events: List<TrainMonitorEvent>,
        emittedAt: kotlin.time.Instant,
    ) {
        if (events.isEmpty()) return
        if (!notificationSettings.observe().first().notificationsEnabled) return
        if (platformServices.notificationPermission.effective() != EffectiveNotificationPermission.GRANTED) return
        val fresh = monitoringRepository.observeMonitor(trainRunId).first() ?: return
        if (!fresh.notificationsEnabled) return
        events.forEach { event ->
            if (!NotificationDelivery.eventFlagEnabled(fresh.thresholds, event)) return@forEach
            if (monitoringRepository.claimNotification(event, emittedAt, policy.notificationCooldown)) {
                platformServices.notifications.show(event.notification(fresh, localizer))
            }
        }
    }

    private suspend fun scheduleBackgroundRefresh() {
        platformServices.backgroundScheduler.schedule(
            BackgroundTask(
                id = BACKGROUND_TASK_ID,
                earliestStartEpochMillis = (clock.now() + policy.backgroundRefreshInterval).toEpochMilliseconds(),
            ),
        )
    }

    companion object {
        const val BACKGROUND_TASK_ID = "it.danielebufarini.trenify.monitoring.refresh"
    }
}

private suspend fun TrainMonitorEvent.notification(
    monitor: TrainMonitor,
    localizer: NotificationLocalizer,
): NotificationMessage {
    val train = monitor.lastSnapshot?.train?.summary
    val title = localizer.trainTitle(train?.id?.number?.value ?: trainRunId.number.value)
    val body = when (this) {
        // A null previous delay can only come from rows persisted before
        // unknown-delay evidence was excluded: it renders truthfully as
        // unknown, never as a fabricated zero.
        is TrainMonitorEvent.DelayThresholdCrossed -> localizer.delayChanged(previousMinutes, currentMinutes)
        is TrainMonitorEvent.Cancelled -> localizer.trainCancelled()
        is TrainMonitorEvent.PartiallyCancelled -> localizer.trainPartiallyCancelled(cancelledStops)
        is TrainMonitorEvent.PlatformChanged -> localizer.platformChanged(stationName, currentPlatform)
        is TrainMonitorEvent.ScheduleChanged -> localizer.scheduleChanged(stationName)
        is TrainMonitorEvent.StatusChanged -> localizer.statusChanged(current)
        is TrainMonitorEvent.Departed -> localizer.trainDeparted()
        is TrainMonitorEvent.Arrived -> localizer.trainArrived()
        is TrainMonitorEvent.RouteChanged -> localizer.trainRouteChanged()
    }
    return NotificationMessage(
        // The fingerprint (hence the anti-spam id) is intentionally unchanged
        // by routing metadata: the typed destination travels alongside it.
        id = "${trainRunId.key}:$fingerprint",
        title = title,
        body = body,
        destination = NotificationDestination.Train(
            provider = trainRunId.provider.value,
            number = trainRunId.number.value,
            origin = trainRunId.origin.value,
            serviceDate = trainRunId.serviceDate.toString(),
        ),
    )
}
