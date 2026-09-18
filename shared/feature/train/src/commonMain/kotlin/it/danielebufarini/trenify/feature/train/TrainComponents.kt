package it.danielebufarini.trenify.feature.train

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.network.RequestInstrumentation
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

data class TrainSearchState(
    val number: String = "",
    val serviceDate: LocalDate = RailwayTime.serviceDate(Clock.System.now()),
    val results: RealtimeState<List<TrainRunSummary>> = RealtimeState(),
    val invalidNumber: Boolean = false,
    /**
     * True when a favorite launch returned exactly one same-number candidate
     * that conflicts with the stored discrimination. The run is withheld from
     * automatic navigation and from the picker; the UI shows a controlled
     * no-compatible-service state instead.
     */
    val noCompatibleService: Boolean = false,
)
/**
 * Durable train-search input (T7.13-B): the entered number and explicit
 * service date required to continue the flow after save/destroy/recreate.
 * Results, loading flags and transient errors re-derive from a fresh
 * search; the favorite discrimination stays in the route that launched it.
 */
@Serializable
private data class SavedTrainSearchInput(
    val number: String = "",
    /** ISO-8601 service date, null when the current Rome date applies. */
    val serviceDate: String? = null,
)

private const val KEY_TRAIN_SEARCH = "train-search-input"

class TrainSearchComponent(
    componentContext: ComponentContext,
    private val find: FindTrainRuns,
    private val onTrain: (TrainRunId) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    initialNumber: String = "",
    serviceDate: LocalDate? = null,
    autoSearch: Boolean = false,
    /**
     * Stable discrimination carried from a recurring favorite launch, if any.
     * A plain manual search passes null and keeps the established single-result
     * behavior. A favorite launch never auto-selects an incompatible or
     * unverifiable candidate and never auto-selects among several candidates.
     */
    private val expected: TrainLookupIntent? = null,
    private val historyRepository: HistoryRepository? = null,
    private val policy: RealtimePolicy = RealtimePolicy(),
    private val clock: Clock = Clock.System,
    private val instrumentation: RequestInstrumentation = RequestInstrumentation.None,
) : ComponentContext by componentContext {
    private val scope = componentScope(lifecycle, dispatcher)
    private val freshnessWatcher = FreshnessWatcher(lifecycle, scope, clock)
    private val savedInput = stateKeeper.consume(KEY_TRAIN_SEARCH, SavedTrainSearchInput.serializer())
    private val mutable = MutableValue(TrainSearchState(
        number = savedInput?.number ?: initialNumber,
        serviceDate = savedInput?.serviceDate?.let { iso -> runCatching { LocalDate.parse(iso) }.getOrNull() }
            ?: serviceDate ?: RailwayTime.serviceDate(Clock.System.now()),
    ))
    val state: Value<TrainSearchState> = mutable
    private var request: Job? = null

    /** Read-only carried discrimination; editing away from its number suspends its applicability. */
    @OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)
    @kotlin.native.HiddenFromObjC
    fun lookupIntent(): TrainLookupIntent? = expected?.takeIf { it.number.value == mutable.value.number.trim() }

    init {
        stateKeeper.register(KEY_TRAIN_SEARCH, SavedTrainSearchInput.serializer()) {
            val current = mutable.value
            SavedTrainSearchInput(
                number = current.number,
                serviceDate = current.serviceDate.toString(),
            )
        }
        if (autoSearch && mutable.value.number.isNotBlank()) search()
        // Elapsed-time aging (T7.14-B): candidate freshness becomes Stale
        // at the policy boundary with zero provider calls.
        freshnessWatcher.observe(
            nextDeadline = { mutable.value.results.freshness?.staleTransitionAt(policy.trainTtl) },
            onTick = {
                val aged = mutable.value.results.agedDisplay(clock.now(), policy.trainTtl)
                if (aged !== mutable.value.results) mutable.value = mutable.value.copy(results = aged)
            },
        )
    }
    fun number(value: String) {
        request?.cancel()
        mutable.value = mutable.value.copy(
            number = value,
            results = RealtimeState(),
            invalidNumber = false,
            noCompatibleService = false,
        )
    }
    fun search() {
        request?.cancel()
        val number = mutable.value.number.trim()
        if (number.isEmpty() || !number.all(Char::isDigit)) {
            mutable.value = mutable.value.copy(invalidNumber = true)
            return
        }
        // Exactly one durable entry per validated submission, whatever the
        // later lookup outcome is. Selection, observation and keystrokes never
        // reach this path, so they are never recorded.
        val submittedNumber = TrainNumber(number)
        instrumentation.event("train.submit")
        // The carried discrimination belongs to the prefilled number: after
        // an edit it applies only when the submitted number still matches,
        // so a stale intent can neither reject the new lookup nor leak into
        // the new history entry. The stored intent itself is kept, so editing
        // back restores its validity.
        val submittedExpected = expected?.takeIf { it.number == submittedNumber }
        historyRepository?.let { history ->
            val submittedDate = mutable.value.serviceDate
            scope.launch {
                try {
                    history.recordTrainSearch(submittedNumber, submittedDate, submittedExpected)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // History is best-effort personal data: a persistence
                    // failure must never block the validated train search.
                }
            }
        }
        mutable.value = mutable.value.copy(
            results = RealtimeState(loading = true),
            invalidNumber = false,
            noCompatibleService = false,
        )
        request = scope.launch {
            // The applicable service date is always explicit. It is resolved to the
            // current Rome date at lookup time, never taken from a stored favorite.
            val result = find(TrainNumber(number), mutable.value.serviceDate)
            currentCoroutineContext().ensureActive()
            when (result) {
                is DataResult.Data -> {
                    val runs = result.value
                    val single = runs.singleOrNull()
                    val compatibility = if (submittedExpected == null || single == null) null
                    else submittedExpected.compatibility(single)
                    // Train number alone never proves recurring-train identity:
                    // a favorite launch navigates directly only for one candidate
                    // whose known discrimination is fully verified. Anything else
                    // stays for explicit selection, and a conflicting single run
                    // is withheld entirely behind a controlled unavailable state.
                    val blocked = single != null &&
                        compatibility == TrainCandidateCompatibility.INCOMPATIBLE
                    mutable.value = mutable.value.copy(
                        results = RealtimeState(
                            if (blocked) emptyList() else runs,
                            failure = result.warning,
                            stale = result.freshness !is DataFreshness.Fresh,
                            freshness = result.freshness,
                        ),
                        noCompatibleService = blocked,
                    )
                    if (single != null && !blocked &&
                        (submittedExpected == null || compatibility == TrainCandidateCompatibility.COMPATIBLE)
                    ) {
                        onTrain(single.id)
                    }
                }
                is DataResult.Failure -> mutable.value = mutable.value.copy(
                    results = RealtimeState(failure = result.error),
                    noCompatibleService = false,
                )
            }
        }
    }
    fun select(run: TrainRunSummary) {
        if (run in mutable.value.results.data.orEmpty()) {
            instrumentation.event("train.selection")
            onTrain(run.id)
        }
    }
}

data class TrainDetailState(
    val realtime: RealtimeState<TrainRun> = RealtimeState(),
    val isMonitored: Boolean = false,
    val notificationPermissionGranted: Boolean? = null,
    val monitor: TrainMonitor? = null,
    val monitorNotificationsPending: Boolean = false,
    val monitorThresholdText: String = "",
    val monitorThresholdInvalid: Boolean = false,
    val monitorPrefsError: Boolean = false,
    /**
     * True once the persisted monitor lifecycle has been observed at least
     * once (T7.11 corrective pass 2). While false with a monitor source
     * present, the detail must not expose Start/Stop, manual Refresh,
     * active-monitor preferences or the ended Remove: an ended route would
     * otherwise briefly render active controls before the first emission
     * resolves its read-only lifecycle. Without a monitor source the
     * lifecycle concept does not apply and this stays true.
     */
    val monitorLifecycleResolved: Boolean = true,
    /**
     * Strike warnings from the observed local snapshot (T7.12-C/D):
     * scheduled-first context, read-only evaluation. Ended details keep
     * showing their persisted snapshot's warnings without restarting polling.
     */
    val strikeWarnings: List<ServiceStrikeWarning> = emptyList(),
    val strikesStale: Boolean = false,
    /**
     * No authoritative coverage exists for the detail window (T7.12
     * corrective pass): never rendered as merely outdated.
     */
    val strikesUnknown: Boolean = false,
) {
    val data get() = realtime.data
    val loading get() = realtime.loading
    val failed get() = realtime.failed
    val stale get() = realtime.stale
    val freshness get() = realtime.freshness

    /**
     * Persisted terminal lifecycle (T7.11 corrective): the monitor row's
     * endedAt is authoritative. The mutable train cache may still look
     * non-terminal when stale, so ended state is never derived from it.
     */
    val isEnded: Boolean get() = monitor?.endedAt != null
}

class TrainDetailComponent(
    componentContext: ComponentContext,
    val id: TrainRunId,
    private val observe: ObserveTrainRun,
    available: StateFlow<Boolean>,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val policy: RealtimePolicy = RealtimePolicy(),
    private val clock: Clock = Clock.System,
    observeMonitor: ObserveTrainMonitor? = null,
    private val startMonitoring: StartTrainMonitoring? = null,
    private val stopMonitoring: StopTrainMonitoring? = null,
    private val requestNotificationPermission: suspend () -> Boolean = { false },
    favoritesRepository: FavoritesRepository? = null,
    private val observeNotificationSettings: ObserveNotificationSettings? = null,
    private val setMonitorNotifications: SetMonitorNotifications? = null,
    private val updateMonitorThresholds: UpdateMonitorThresholds? = null,
    private val removeEndedMonitor: RemoveEndedMonitor? = null,
    private val strikes: LoadStrikes? = null,
    private val strikePolicy: StrikePolicy = StrikePolicy(),
    private val instrumentation: RequestInstrumentation = RequestInstrumentation.None,
) : ComponentContext by componentContext {
    private val scope = componentScope(lifecycle, dispatcher)
    private val hasMonitorSource = observeMonitor != null
    private val freshnessWatcher = FreshnessWatcher(lifecycle, scope, clock)
    private val mutable = MutableValue(TrainDetailState(monitorLifecycleResolved = !hasMonitorSource))
    val state: Value<TrainDetailState> = mutable
    private var committedMonitorThreshold: String? = null
    private var lastMonitorPreferenceRequest: (suspend () -> Unit)? = null
    private val strikeEvaluator = EvaluateServiceStrikeImpact()
    private var latestStrikes: List<Strike> = emptyList()
    private var latestStrikeFreshness: DataFreshness = DataFreshness.Unknown
    private var firstUsefulContent = false
    /**
     * Strike observation window from the snapshot's scheduled times
     * (T7.12-C): one detail-level observation plus one targeted stale
     * refresh per stable interval. Snapshot realtime drift never moves it,
     * and strike evaluation never issues train provider access.
     */
    private val strikeBounds = MutableStateFlow<Pair<Instant, Instant>?>(null)
    private val poller: VisibleRefresh
    private val trainFavorite = favoritesRepository?.let { favorites ->
        FavoriteTrainController(componentContext, favorites,
            { mutable.value.data?.summary?.toFavoriteTrain() }, dispatcher)
    }

    val favoriteTrainState: Value<TrainFavoriteState>? = trainFavorite?.state

    init {
        // Elapsed-time aging (T7.14-B, union deadline): the held snapshot
        // becomes Stale at the policy boundary and the strike coverage at
        // the strike cache TTL, both with zero provider calls; resume
        // recomputes from the clock. Repository emissions overwrite the
        // display copy. The strike repository's local expiry ticker tracks
        // strike end times, never coverage freshness, so this watcher owns
        // the strike staleness boundary too.
        freshnessWatcher.observe(
            nextDeadline = { trainStrikeDeadline() },
            onTick = { ageTrainAndStrikes() },
        )
        scope.launch {
            observe(id).collect { result ->
                mutable.value = when (result) {
                    is DataResult.Data -> mutable.value.copy(realtime = RealtimeState(result.value, mutable.value.loading,
                        result.warning, result.freshness !is DataFreshness.Fresh, result.freshness))
                    is DataResult.Failure -> mutable.value.copy(
                        realtime = mutable.value.realtime.copy(
                            failure = result.error.takeUnless { mutable.value.loading },
                            // A failure arriving over visible cached data
                            // marks it Stale (T7.14 final pass): the
                            // snapshot is retained but no longer confirmed
                            // fresh — the FS §23 stale-content-plus-error
                            // presentation. Bare failures stay unstale.
                            stale = mutable.value.realtime.stale ||
                                mutable.value.realtime.data != null,
                        ),
                    )
                }
                if (result is DataResult.Data && !firstUsefulContent) {
                    firstUsefulContent = true
                    instrumentation.event("train.observation_to_presentable_content")
                }
                // New repository truth restarts freshness boundary tracking.
                freshnessWatcher.poke()
                trainFavorite?.trainChanged()
                // Snapshot-driven strike window: scheduled times only, so
                // realtime updates cannot churn the observation. Updated
                // locally accepted snapshots recompute warnings below.
                (result as? DataResult.Data)?.value?.let { run ->
                    val context = run.strikeContext()
                    val bounds = scheduledStrikeBounds(context.departure, context.arrival)
                    if (bounds != strikeBounds.value) {
                        strikeBounds.value = bounds
                        bounds?.let { (from, to) -> refreshStrikeWindow(from, to) }
                    }
                }
                applyStrikeWarnings()
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
        observeMonitor?.let { observe ->
            scope.launch {
                observe(id).collect { monitor ->
                    val persisted = monitor?.thresholds?.delayMinutes?.toString().orEmpty()
                    val text = if (committedMonitorThreshold == null || committedMonitorThreshold != persisted) {
                        committedMonitorThreshold = persisted.takeIf { it.isNotEmpty() }
                        persisted
                    } else {
                        mutable.value.monitorThresholdText
                    }
                    mutable.value = mutable.value.copy(
                        isMonitored = monitor != null && monitor.endedAt == null,
                        monitor = monitor,
                        monitorThresholdText = text,
                        monitorThresholdInvalid = false,
                        monitorLifecycleResolved = true,
                    )
                }
            }
        }

        poller = VisibleRefresh(lifecycle, scope, available, policy.pollingInterval, policy.maximumBackoff,
            terminal = {
                // Persisted lifecycle first (authoritative even when the
                // cache is stale), provider-neutral cache status second.
                mutable.value.monitor?.endedAt != null ||
                    mutable.value.data?.summary?.status in setOf(TrainStatus.ARRIVED, TrainStatus.CANCELLED)
            },
        ) { force ->
            // Lifecycle gate before the first provider call: an ended monitor
            // never triggers provider access from this detail, even when the
            // cached train still looks non-terminal. The persisted monitor is
            // resolved first so a not-yet-loaded monitor cannot slip through.
            val lifecycle = observeMonitor?.invoke(id)?.first()
            if (lifecycle?.endedAt != null) return@VisibleRefresh true
            mutable.value = mutable.value.copy(realtime = mutable.value.realtime.copy(loading = true, failure = null))
            instrumentation.event("train.request_start")
            val result = observe.refresh(id, force)
            // Data comes only from the reactive database observation, including after manual refresh.
            mutable.value = mutable.value.copy(realtime = mutable.value.realtime.copy(loading = false,
                failure = (result as? DataResult.Failure)?.error ?: (result as? DataResult.Data)?.warning))
            freshnessWatcher.poke()
            result is DataResult.Data && result.warning == null
        }
    }
    fun refresh() {
        // Ended monitors are read-only: neither automatic nor manual detail
        // polling may issue provider access for them. Before lifecycle
        // resolution the poller action itself resolves the persisted
        // lifecycle first, so an ended route still issues zero provider
        // calls; the UI additionally hides the control until resolved.
        if (mutable.value.monitor?.endedAt != null) return
        poller.refresh()
    }

    private fun refreshStrikeWindow(from: Instant, to: Instant) {
        val load = strikes ?: return
        scope.launch { load(from, to) }
    }

    private fun applyStrikeWarnings() {
        val run = mutable.value.data
        val warnings = if (strikes == null || run == null || latestStrikes.isEmpty()) {
            emptyList()
        } else {
            strikeEvaluator.warnings(run.strikeContext(), latestStrikes, latestStrikeFreshness)
        }
        mutable.value = mutable.value.copy(
            strikeWarnings = warnings,
            strikesStale = strikes != null && latestStrikeFreshness is DataFreshness.Stale,
            strikesUnknown = strikes != null && latestStrikeFreshness is DataFreshness.Unknown,
        )
    }

    /**
     * Earliest freshness boundary across the held train snapshot and the
     * strike coverage (T7.14 corrective): one component-level deadline,
     * never a provider poll.
     */
    private fun trainStrikeDeadline(): Instant? = listOfNotNull(
        mutable.value.realtime.freshness?.staleTransitionAt(
            policy.ttlForTrain(mutable.value.data?.summary?.status),
        ),
        latestStrikeFreshness.staleTransitionAt(strikePolicy.cacheTtl),
    ).minOrNull()

    /**
     * Local tick (T7.14 corrective): re-derives the snapshot and the strike
     * coverage from the injected clock with zero provider calls, then
     * re-evaluates warning presentation against the aged strike freshness.
     * Repository truth is untouched.
     */
    private fun ageTrainAndStrikes() {
        val now = clock.now()
        val aged = mutable.value.realtime.agedDisplay(
            now,
            policy.ttlForTrain(mutable.value.data?.summary?.status),
        )
        if (aged !== mutable.value.realtime) mutable.value = mutable.value.copy(realtime = aged)
        val agedStrikes = latestStrikeFreshness.aged(now, strikePolicy.cacheTtl)
        if (agedStrikes != latestStrikeFreshness) {
            latestStrikeFreshness = agedStrikes
            applyStrikeWarnings()
        }
    }

    /**
     * Manual early removal of this retained ended monitor (T7.11 corrective).
     * No-op unless the persisted lifecycle is ended.
     */
    fun removeEndedMonitor() {
        val remove = removeEndedMonitor ?: return
        if (mutable.value.monitor?.endedAt == null) return
        scope.launch { remove(id) }
    }

    fun toggleFavoriteTrain() {
        trainFavorite?.toggle()
    }

    fun toggleMonitoring() {
        val start = startMonitoring ?: return
        val stop = stopMonitoring ?: return
        // Ended monitors expose Remove, never Stop/Start: toggling must not
        // reactivate or delete through the active-monitor path. Toggling
        // before lifecycle resolution stays safe because creation for a
        // retained ended monitor returns it unchanged (never reactivates);
        // the UI additionally hides the control until resolved.
        if (mutable.value.monitor?.endedAt != null) return
        scope.launch {
            if (mutable.value.isMonitored) {
                stop(id)
            } else {
                // New monitors inherit the authoritative installation
                // defaults resolved here, never a cached fallback: starting
                // before the first observation still reads the persisted
                // values. Later default changes never rewrite this monitor.
                val defaults = observeNotificationSettings?.invoke()?.first()?.defaultThresholds
                    ?: MonitorThresholds()
                start(id, defaults)
                mutable.value = mutable.value.copy(
                    notificationPermissionGranted = requestNotificationPermission(),
                )
            }
        }
    }

    /**
     * Mutes or unmutes this train without stopping its monitor. Evaluation
     * continues while muted.
     */
    fun setMonitorNotificationsEnabled(enabled: Boolean) {
        val set = setMonitorNotifications ?: return
        if (mutable.value.monitor?.endedAt != null) return
        lastMonitorPreferenceRequest = { set(id, enabled) }
        mutateMonitorPreferences { set(id, enabled) }
    }

    fun editMonitorThreshold(text: String) {
        mutable.value = mutable.value.copy(
            monitorThresholdText = text,
            monitorThresholdInvalid = text.toIntOrNull()?.takeIf { it > 0 } == null,
        )
    }

    fun saveMonitorThreshold() {
        val update = updateMonitorThresholds ?: return
        if (mutable.value.monitor?.endedAt != null) return
        val minutes = mutable.value.monitorThresholdText.toIntOrNull()?.takeIf { it > 0 }
        if (minutes == null) {
            mutable.value = mutable.value.copy(monitorThresholdInvalid = true)
            return
        }
        val thresholds = (mutable.value.monitor?.thresholds ?: MonitorThresholds()).copy(delayMinutes = minutes)
        committedMonitorThreshold = minutes.toString()
        lastMonitorPreferenceRequest = { update(id, thresholds) }
        mutateMonitorPreferences { update(id, thresholds) }
    }

    fun setMonitorEventFlag(kind: MonitorEventKind, enabled: Boolean) {
        val update = updateMonitorThresholds ?: return
        if (mutable.value.monitor?.endedAt != null) return
        val current = mutable.value.monitor?.thresholds ?: return
        val thresholds = when (kind) {
            MonitorEventKind.DELAY -> current.copy(notifyDelay = enabled)
            MonitorEventKind.PLATFORM -> current.copy(notifyPlatform = enabled)
            MonitorEventKind.CANCELLATION,
            MonitorEventKind.PARTIAL_CANCELLATION,
            -> current.copy(notifyCancellation = enabled)
            MonitorEventKind.DEPARTURE -> current.copy(notifyDeparture = enabled)
            MonitorEventKind.ARRIVAL -> current.copy(notifyArrival = enabled)
            // Schedule, status and route-change events have no dedicated
            // installation flag (T7.7/T7.11): nothing to edit per monitor.
            MonitorEventKind.SCHEDULE, MonitorEventKind.STATUS, MonitorEventKind.ROUTE_CHANGED -> return
        }
        lastMonitorPreferenceRequest = { update(id, thresholds) }
        mutateMonitorPreferences { update(id, thresholds) }
    }

    fun retryMonitorPreferences() {
        val request = lastMonitorPreferenceRequest ?: return
        if (!mutable.value.monitorPrefsError) return
        mutateMonitorPreferences(request)
    }

    private fun mutateMonitorPreferences(block: suspend () -> Unit) {
        mutable.value = mutable.value.copy(monitorNotificationsPending = true, monitorPrefsError = false)
        scope.launch {
            try {
                block()
                mutable.value = mutable.value.copy(monitorNotificationsPending = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutable.value = mutable.value.copy(
                    monitorNotificationsPending = false,
                    monitorPrefsError = true,
                )
            }
        }
    }
}

/**
 * Strike observation window from scheduled snapshot times (T7.12-C): the
 * exact scheduled service interval when both ends are known, a symmetric
 * one-minute widening around a single known time (evaluation still uses the
 * exact context), and null when the snapshot positions nothing in time.
 */
internal fun scheduledStrikeBounds(departure: Instant?, arrival: Instant?): Pair<Instant, Instant>? {
    if (departure == null && arrival == null) return null
    val from = minOf(departure ?: arrival!!, arrival ?: departure!!)
    val to = maxOf(departure ?: arrival!!, arrival ?: departure!!)
    return if (from < to) from to to else (from - 1.minutes) to (to + 1.minutes)
}
