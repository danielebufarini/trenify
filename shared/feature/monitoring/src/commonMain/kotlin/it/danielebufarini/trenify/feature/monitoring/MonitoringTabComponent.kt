package it.danielebufarini.trenify.feature.monitoring

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.EvaluateServiceStrikeImpact
import it.danielebufarini.trenify.core.domain.LoadStrikes
import it.danielebufarini.trenify.core.domain.ObserveActiveMonitors
import it.danielebufarini.trenify.core.domain.ObserveEndedMonitors
import it.danielebufarini.trenify.core.domain.RemoveEndedMonitor
import it.danielebufarini.trenify.core.domain.ServiceStrikeWarning
import it.danielebufarini.trenify.core.domain.SetMonitorNotifications
import it.danielebufarini.trenify.core.domain.StopTrainMonitoring
import it.danielebufarini.trenify.core.domain.StrikePolicy
import it.danielebufarini.trenify.core.domain.TrainMonitor
import it.danielebufarini.trenify.core.domain.strikeContext
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.StopStatus
import it.danielebufarini.trenify.core.model.Strike
import it.danielebufarini.trenify.core.model.TrainRunId
import it.danielebufarini.trenify.core.domain.RealtimePolicy
import it.danielebufarini.trenify.core.model.aged
import it.danielebufarini.trenify.core.model.staleTransitionAt
import it.danielebufarini.trenify.core.ui.FreshnessWatcher
import it.danielebufarini.trenify.core.ui.componentScope
import it.danielebufarini.trenify.core.ui.ttlForTrain
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

data class MonitorCardState(
    val monitor: TrainMonitor,
    val nextStopName: String?,
    /**
     * Display copy of the persisted snapshot freshness (T7.14-B): aged by
     * elapsed local time without provider calls. Null while the snapshot
     * carries no freshness. Immediately Stale while the monitor carries a
     * recorded refresh failure (T7.14 corrective), even before the TTL.
     */
    val displayFreshness: DataFreshness? = null,
    /**
     * Typed failure of the latest refresh attempt that produced no new
     * accepted observation (T7.14 corrective): renders the FS §23
     * stale-with-warning wording beside the retained snapshot.
     */
    val refreshFailure: DomainFailure? = null,
    /**
     * Strike warnings from the monitor's already persisted/current local
     * snapshot (T7.12-C): scheduled-first normalized context, no
     * card-specific polling or provider access.
     */
    val strikeWarnings: List<ServiceStrikeWarning> = emptyList(),
    val strikesStale: Boolean = false,
    /**
     * No authoritative coverage exists for the card window (T7.12
     * corrective pass): never rendered as merely outdated.
     */
    val strikesUnknown: Boolean = false,
)

data class MonitoringState(
    val active: List<MonitorCardState> = emptyList(),
    /**
     * Recently ended monitors (T7.11): terminal ARRIVED/final CANCELLED rows
     * within the 24-hour retention window. Read-only: no polling, no
     * active-only controls, no Stop action; manual removal only.
     */
    val ended: List<MonitorCardState> = emptyList(),
    val loading: Boolean = true,
    val pendingNotifications: Set<String> = emptySet(),
    val failedNotificationMonitor: TrainRunId? = null,
)

interface MonitoringTabComponent {
    val state: Value<MonitoringState>
    fun stop(trainRunId: TrainRunId)
    fun open(trainRunId: TrainRunId)

    /**
     * Removes a retained ended monitor before its 24-hour deadline (T7.11).
     * The item is already stopped: this performs no Stop semantics, generates
     * no events and never restarts polling. Idempotent.
     */
    fun removeEnded(trainRunId: TrainRunId)

    /**
     * Mutes or unmutes train notifications without stopping the monitor.
     * Monitor evaluation continues while muted.
     */
    fun setMonitorNotifications(trainRunId: TrainRunId, enabled: Boolean)
    fun retryMonitorNotifications()
}

class DefaultMonitoringTabComponent(
    componentContext: ComponentContext,
    observeActiveMonitors: ObserveActiveMonitors? = null,
    private val stopMonitoring: StopTrainMonitoring? = null,
    private val onTrain: (TrainRunId) -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val setMonitorNotifications: SetMonitorNotifications? = null,
    observeEndedMonitors: ObserveEndedMonitors? = null,
    private val removeEndedMonitor: RemoveEndedMonitor? = null,
    private val strikes: LoadStrikes? = null,
    private val policy: RealtimePolicy = RealtimePolicy(),
    private val clock: Clock = Clock.System,
    private val strikePolicy: StrikePolicy = StrikePolicy(),
) : MonitoringTabComponent, ComponentContext by componentContext {
    private val scope = componentScope(lifecycle, dispatcher)
    private val freshnessWatcher = FreshnessWatcher(lifecycle, scope, clock)
    private val mutable = MutableValue(MonitoringState())
    override val state: Value<MonitoringState> = mutable
    private val strikeEvaluator = EvaluateServiceStrikeImpact()
    private var latestStrikes: List<Strike> = emptyList()
    private var latestStrikeFreshness: DataFreshness = DataFreshness.Unknown
    /**
     * Single union observation window over every card snapshot (T7.12-C):
     * one component-level observation plus one targeted stale refresh, never
     * card-specific polling or provider access.
     */
    private val strikeBounds = MutableStateFlow<Pair<Instant, Instant>?>(null)

    init {
        // Elapsed-time aging over every visible card snapshot plus the
        // strike coverage (T7.14-B, union deadline): one component-level
        // next-transition deadline for all cards and strikes, zero provider
        // calls, resume recomputes from the clock. Destroying the component
        // cancels the scheduling through the component scope. The strike
        // repository's local expiry ticker tracks strike end times, never
        // coverage freshness, so this watcher owns the strike staleness
        // boundary too.
        freshnessWatcher.observe(
            nextDeadline = { unionDeadline() },
            onTick = { ageCardsAndStrikes() },
        )
        if (observeActiveMonitors == null && observeEndedMonitors == null) {
            mutable.value = MonitoringState(loading = false)
        } else {
            scope.launch {
                combine(
                    observeActiveMonitors?.invoke() ?: flowOf(emptyList()),
                    observeEndedMonitors?.invoke() ?: flowOf(emptyList()),
                ) { active, ended -> active to ended }
                    .collect { (active, ended) ->
                        mutable.value = mutable.value.copy(
                            active = active.map { cardState(it, withNextStop = true) },
                            ended = ended.map { cardState(it, withNextStop = false) },
                            loading = false,
                        )
                        // New snapshots restart freshness boundary tracking.
                        freshnessWatcher.poke()
                        applyStrikeWarnings()
                        // A changed union (new/updated/removed snapshots)
                        // drives exactly one targeted stale refresh; the
                        // strike repository single flight shares it.
                        val bounds = unionStrikeBounds(active + ended)
                        if (bounds != strikeBounds.value) {
                            strikeBounds.value = bounds
                            bounds?.let { (from, to) -> refreshStrikeWindow(from, to) }
                        }
                    }
            }
            if (strikes != null) scope.launch {
                strikeBounds.flatMapLatest { bounds ->
                    if (bounds == null) flowOf(null)
                    else strikes.observe(bounds.first, bounds.second)
                }.distinctUntilChanged().collect { result ->
                    when (result) {
                        is DataResult.Data -> {
                            latestStrikes = result.value
                            latestStrikeFreshness = result.freshness
                        }
                        is DataResult.Failure, null -> {
                            latestStrikes = emptyList()
                            latestStrikeFreshness = DataFreshness.Unknown
                        }
                    }
                    applyStrikeWarnings()
                    // New strike truth restarts the union boundary tracking.
                    freshnessWatcher.poke()
                }
            }
        }
    }

    /**
     * TTL for one card snapshot (T7.14-B): ended monitors keep the
     * completed-train TTL, active cards mirror the repository train
     * classification by status.
     */
    private fun cardTtl(card: MonitorCardState): Duration =
        if (card.monitor.endedAt != null) policy.completedTrainTtl
        else policy.ttlForTrain(card.monitor.lastSnapshot?.train?.summary?.status)

    private fun cardDeadline(cards: List<MonitorCardState>): Instant? =
        cards.mapNotNull { card -> card.displayFreshness?.staleTransitionAt(cardTtl(card)) }.minOrNull()

    /**
     * Earliest freshness boundary across every card snapshot and the
     * strike coverage (T7.14 corrective): one component-level deadline,
     * never one timer per card and never a provider poll.
     */
    private fun unionDeadline(): Instant? = listOfNotNull(
        cardDeadline(mutable.value.active + mutable.value.ended),
        latestStrikeFreshness.staleTransitionAt(strikePolicy.cacheTtl),
    ).minOrNull()

    private fun ageCards() {
        val now = clock.now()
        fun aged(card: MonitorCardState): MonitorCardState {
            val base = card.displayFreshness ?: return card
            val next = base.aged(now, cardTtl(card))
            return if (next == base) card else card.copy(displayFreshness = next)
        }
        val current = mutable.value
        val active = current.active.map(::aged)
        val ended = current.ended.map(::aged)
        if (active != current.active || ended != current.ended) {
            mutable.value = current.copy(active = active, ended = ended)
        }
    }

    /**
     * Local tick (T7.14 corrective): re-derives every card snapshot and
     * the strike coverage from the injected clock with zero provider
     * calls, then re-evaluates card warnings against the aged strike
     * freshness. Repository truth is untouched.
     */
    private fun ageCardsAndStrikes() {
        ageCards()
        val agedStrikes = latestStrikeFreshness.aged(clock.now(), strikePolicy.cacheTtl)
        if (agedStrikes != latestStrikeFreshness) {
            latestStrikeFreshness = agedStrikes
            applyStrikeWarnings()
        }
    }

    private fun refreshStrikeWindow(from: Instant, to: Instant) {
        val load = strikes ?: return
        scope.launch { load(from, to) }
    }

    private fun applyStrikeWarnings() {
        val current = mutable.value
        fun warned(card: MonitorCardState): MonitorCardState {
            val train = card.monitor.lastSnapshot?.train
            val warnings = if (strikes == null || train == null || latestStrikes.isEmpty()) {
                emptyList()
            } else {
                strikeEvaluator.warnings(train.strikeContext(), latestStrikes, latestStrikeFreshness)
            }
            return card.copy(
                strikeWarnings = warnings,
                strikesStale = strikes != null && latestStrikeFreshness is DataFreshness.Stale,
                strikesUnknown = strikes != null && latestStrikeFreshness is DataFreshness.Unknown,
            )
        }
        mutable.value = current.copy(
            active = current.active.map(::warned),
            ended = current.ended.map(::warned),
        )
    }

    /**
     * Display card for one persisted monitor (T7.14 corrective): the
     * snapshot stays visible while a recorded refresh failure forces the
     * display freshness immediately Stale (zero-TTL aging preserves the
     * instants and leaves already-Stale/Unknown values untouched) and
     * surfaces the typed failure for the FS §23 wording.
     */
    private fun cardState(monitor: TrainMonitor, withNextStop: Boolean = true): MonitorCardState {
        val nextStop = when {
            !withNextStop -> null
            else -> monitor.lastSnapshot?.train?.stops?.firstOrNull {
                it.status !in setOf(StopStatus.COMPLETED, StopStatus.CANCELLED)
            }?.station?.name
        }
        val base = monitor.lastSnapshot?.freshness
        val display = if (monitor.refreshFailure != null && base != null) {
            base.aged(clock.now(), Duration.ZERO)
        } else {
            base
        }
        return MonitorCardState(monitor, nextStop, display, monitor.refreshFailure)
    }

    override fun stop(trainRunId: TrainRunId) {
        stopMonitoring?.let { stop -> scope.launch { stop(trainRunId) } }
    }

    override fun removeEnded(trainRunId: TrainRunId) {
        removeEndedMonitor?.let { remove -> scope.launch { remove(trainRunId) } }
    }

    override fun open(trainRunId: TrainRunId) = onTrain(trainRunId)

    private var lastNotificationRequest: Pair<TrainRunId, Boolean>? = null

    override fun setMonitorNotifications(trainRunId: TrainRunId, enabled: Boolean) {
        val set = setMonitorNotifications ?: return
        lastNotificationRequest = trainRunId to enabled
        mutable.value = mutable.value.copy(
            pendingNotifications = mutable.value.pendingNotifications + trainRunId.key,
            failedNotificationMonitor = null,
        )
        scope.launch {
            try {
                set(trainRunId, enabled)
                mutable.value = mutable.value.copy(
                    pendingNotifications = mutable.value.pendingNotifications - trainRunId.key,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutable.value = mutable.value.copy(
                    pendingNotifications = mutable.value.pendingNotifications - trainRunId.key,
                    failedNotificationMonitor = trainRunId,
                )
            }
        }
    }

    override fun retryMonitorNotifications() {
        val (id, enabled) = lastNotificationRequest ?: return
        if (mutable.value.failedNotificationMonitor != id) return
        setMonitorNotifications(id, enabled)
    }
}

/**
 * Union observation window over card snapshots (T7.12-C): the smallest
 * interval covering every snapshot's scheduled-first service times. Null
 * when no snapshot positions its train in time. A point-only snapshot
 * widens symmetrically by one minute for observation only — evaluation
 * still uses the exact context — so [LoadStrikes.observe] (which requires
 * `to > from`) can serve it.
 */
internal fun unionStrikeBounds(monitors: List<TrainMonitor>): Pair<Instant, Instant>? {
    val times = monitors.mapNotNull { it.lastSnapshot?.train?.strikeContext() }
        .flatMap { listOfNotNull(it.departure, it.arrival) }
    if (times.isEmpty()) return null
    val from = times.min()
    val to = times.max()
    return if (from < to) from to to else (from - 1.minutes) to (to + 1.minutes)
}
