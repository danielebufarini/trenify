package it.danielebufarini.trenify.feature.home

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import it.danielebufarini.trenify.core.domain.CurrentStrikeWindow
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.FavoritesRepository
import it.danielebufarini.trenify.core.domain.HistoryRepository
import it.danielebufarini.trenify.core.domain.LoadStrikes
import it.danielebufarini.trenify.core.domain.ObserveActiveMonitors
import it.danielebufarini.trenify.core.domain.RealtimePolicy
import it.danielebufarini.trenify.core.domain.StrikePolicy
import it.danielebufarini.trenify.core.domain.TrainMonitor
import it.danielebufarini.trenify.core.domain.isRailwayRelevant
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.FavoriteRoute
import it.danielebufarini.trenify.core.model.FavoriteTrain
import it.danielebufarini.trenify.core.model.JourneySearchIntent
import it.danielebufarini.trenify.core.model.JourneySearchHistoryEntry
import it.danielebufarini.trenify.core.model.JourneySearchRequest
import it.danielebufarini.trenify.core.model.SearchHistoryEntry
import it.danielebufarini.trenify.core.model.Station
import it.danielebufarini.trenify.core.model.Strike
import it.danielebufarini.trenify.core.model.StrikeStatus
import it.danielebufarini.trenify.core.model.TrainLookupIntent
import it.danielebufarini.trenify.core.model.TrainRunId
import it.danielebufarini.trenify.core.model.TrainSearchHistoryEntry
import it.danielebufarini.trenify.core.model.aged
import it.danielebufarini.trenify.core.model.staleTransitionAt
import it.danielebufarini.trenify.core.ui.FreshnessWatcher
import it.danielebufarini.trenify.core.ui.RealtimeState
import it.danielebufarini.trenify.core.ui.agedDisplay
import it.danielebufarini.trenify.core.ui.componentScope
import it.danielebufarini.trenify.core.ui.ttlForTrain
import it.danielebufarini.trenify.core.ui.unlessNotFound
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** Visible recent searches; the full history stays on its own screen. */
const val HOME_RECENT_LIMIT = 5

data class HomeState(
    val recentSearches: List<SearchHistoryEntry> = emptyList(),
    val favoriteStations: List<Station> = emptyList(),
    val favoriteRoutes: List<FavoriteRoute> = emptyList(),
    val favoriteTrains: List<FavoriteTrain> = emptyList(),
    val monitors: List<TrainMonitor> = emptyList(),
    val strikes: RealtimeState<List<Strike>> = RealtimeState(loading = true),
    val referenceTime: Instant = Clock.System.now(),
    val observationFailed: Boolean = false,
    /**
     * Monitor ids whose persisted snapshot display-aged to Stale (T7.14-B).
     * Home summaries stay visibly distinguishable without per-card polling.
     */
    val staleMonitorIds: Set<String> = emptySet(),
)

/**
 * Local aggregate entry point.
 *
 * Home only observes repository flows and asks for a single targeted stale
 * strike refresh on entry plus explicit manual refreshes. It never
 * subscribes to a provider, polls on timers, or drives notifications: strike
 * loading works with the strike opt-in off, and viewing Home neither claims
 * nor dispatches notification events. All four aggregate inputs render from
 * local state first, so blocked network responses never hide cached content.
 */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
class HomeComponent(
    componentContext: ComponentContext,
    private val historyRepository: HistoryRepository,
    private val favoritesRepository: FavoritesRepository,
    private val observeMonitors: ObserveActiveMonitors? = null,
    private val loadStrikes: LoadStrikes? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val clock: Clock = Clock.System,
    private val policy: StrikePolicy = StrikePolicy(),
    private val realtimePolicy: RealtimePolicy = RealtimePolicy(),
    private val onJourneySearch: () -> Unit = {},
    private val onTrainSearch: () -> Unit = {},
    private val onStations: () -> Unit = {},
    private val onMonitoring: () -> Unit = {},
    private val onFavorites: () -> Unit = {},
    private val onAlerts: () -> Unit = {},
    private val onHistory: () -> Unit = {},
    private val onStation: (Station) -> Unit = {},
    private val onRoute: (JourneySearchIntent) -> Unit = {},
    private val onTrainLookup: (TrainLookupIntent) -> Unit = {},
    private val onTrain: (TrainRunId) -> Unit = {},
    private val onTrainRepeat: (TrainSearchHistoryEntry) -> Unit = {},
    /**
     * Journey-history repeat carrying the full recorded request. Favorite
     * routes keep using [onRoute] with endpoint-only intent semantics.
     */
    private val onJourneyRepeat: (JourneySearchRequest) -> Unit = {},
) : ComponentContext by componentContext {
    private val scope = componentScope(lifecycle, dispatcher)
    private val freshnessWatcher = FreshnessWatcher(lifecycle, scope, clock)
    private val mutable = MutableValue(HomeState())
    private var observation: Job? = null

    /**
     * Single source of truth for Home's active strike window. It is derived
     * from the clock at each explicit refresh (entry, manual, retry) and
     * drives both the provider fetch and the database observation, so the
     * two can never diverge. No polling or background refresh is introduced:
     * the window only advances when the user explicitly refreshes.
     */
    private val activeWindow: MutableStateFlow<CurrentStrikeWindow> =
        MutableStateFlow(policy.currentWindow(clock))

    val state: Value<HomeState> = mutable

    init {
        // Elapsed-time aging (T7.14-B): held strikes and monitor summaries
        // become Stale at their policy boundaries with zero provider calls;
        // resume recomputes from the clock. One component-level deadline for
        // strikes plus one union deadline for monitor snapshots.
        freshnessWatcher.observe(
            nextDeadline = { homeDeadline() },
            onTick = { ageHome() },
        )
        observeAggregates()
        // One targeted refresh on entry when the cached strikes are stale;
        // the repository skips the fetch entirely when they are fresh, and
        // shares one fetch with overlapping Alerts/coordinator requests.
        if (loadStrikes != null) refreshStrikes(force = false)
    }

    fun openJourneySearch() = onJourneySearch()
    fun openTrainSearch() = onTrainSearch()
    fun openStations() = onStations()
    fun openMonitoring() = onMonitoring()
    fun openFavorites() = onFavorites()
    fun openAlerts() = onAlerts()
    fun openHistory() = onHistory()

    fun openStation(station: Station) = onStation(station)
    fun openRoute(route: FavoriteRoute) = onRoute(route.searchIntent)
    fun openTrain(train: FavoriteTrain) = onTrainLookup(train.lookupIntent)
    fun openMonitor(trainRunId: TrainRunId) = onTrain(trainRunId)
    fun openStrike() = onAlerts()

    fun openRecent(entry: SearchHistoryEntry) {
        when (entry) {
            // History repeats reuse the recorded request exactly, like the
            // shared history flow; they never degrade to endpoint-only
            // favorite-route semantics.
            is JourneySearchHistoryEntry -> onJourneyRepeat(entry.request())
            is TrainSearchHistoryEntry -> onTrainRepeat(entry)
        }
    }

    /** Explicit manual refresh; always revalidates through the shared single-flight refresh. */
    fun refreshStrikes() = refreshStrikes(force = true)

    fun retry() {
        if (mutable.value.observationFailed) observeAggregates()
        else if (loadStrikes != null) refreshStrikes(force = true)
    }

    private fun refreshStrikes(force: Boolean) {
        val load = loadStrikes ?: return
        // One captured instant derives the fetch window and becomes the
        // active observation range, keeping refresh and observation aligned.
        val window = policy.currentWindow(clock)
        activeWindow.value = window
        mutable.value = mutable.value.copy(strikes = mutable.value.strikes.copy(loading = true, failure = null))
        scope.launch {
            try {
                val result = load(window, force)
                // The fetch result is authoritative for its own window: the
                // observation replay after the switch above may predate the
                // fetch (dropped) or already include it, in any order, so
                // the completed refresh publishes both status and data.
                // Later observation emissions refine the same state.
                val completedAt = clock.now()
                val fetched = (result as? DataResult.Data)?.value?.strikes
                mutable.value = mutable.value.copy(
                    strikes = mutable.value.strikes.copy(
                        loading = false,
                        data = fetched?.let { relevantStrikes(it, completedAt) }
                            ?: mutable.value.strikes.data,
                        failure = ((result as? DataResult.Failure)?.error ?: (result as? DataResult.Data)?.warning).unlessNotFound(),
                        stale = result is DataResult.Data && result.freshness !is DataFreshness.Fresh,
                        freshness = (result as? DataResult.Data)?.freshness
                            ?: mutable.value.strikes.freshness,
                    ),
                    referenceTime = completedAt,
                )
                // New repository truth restarts freshness boundary tracking.
                freshnessWatcher.poke()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutable.value = mutable.value.copy(strikes = mutable.value.strikes.copy(loading = false, failure = DomainFailure.TEMPORARY))
            }
        }
    }

    private fun observeAggregates() {
        if (observation?.isActive == true) return
        mutable.value = mutable.value.copy(observationFailed = false)
        observation = scope.launch {
            // The strike observation follows the active window: every
            // explicit refresh re-subscribes observation to the same window
            // the fetch just used, so newly fetched boundary strikes become
            // visible without any polling. The replay emitted right after a
            // switch predates the fetch that motivated it, so it is dropped:
            // applying it would clobber the refresh status written on
            // completion. Fresh post-switch emissions (the fetch's own
            // database writes and warning updates) flow through normally, as
            // does the initial subscription below.
            var primed = false
            val strikes = activeWindow.flatMapLatest { window ->
                val fresh = loadStrikes?.observe(window)
                    ?: flowOf<DataResult<List<Strike>>>(
                        DataResult.Data(emptyList<Strike>(), DataFreshness.Unknown),
                    )
                if (!primed) {
                    primed = true
                    fresh
                } else {
                    fresh.drop(1)
                }
            }
            // Flow combine supports five inputs; the strike flow joins in a
            // second step with the same semantics.
            combine(
                historyRepository.observeSearchHistory(),
                favoritesRepository.observeFavoriteStations(),
                favoritesRepository.observeFavoriteRoutes(),
                favoritesRepository.observeFavoriteTrains(),
                observeMonitors?.invoke() ?: flowOf<List<TrainMonitor>>(emptyList()),
            ) { history, stations, routes, trains, monitors ->
                LocalAggregates(history, stations, routes, trains, monitors)
            }
                .combine(strikes) { aggregates, result ->
                    aggregate(aggregates, result)
                }
                .catch { mutable.value = mutable.value.copy(observationFailed = true) }
                .collect { aggregated ->
                    mutable.value = aggregated
                    freshnessWatcher.poke()
                }
        }
    }

    private data class LocalAggregates(
        val history: List<SearchHistoryEntry>,
        val stations: List<Station>,
        val routes: List<FavoriteRoute>,
        val trains: List<FavoriteTrain>,
        val monitors: List<TrainMonitor>,
    )

    /**
     * Union aging deadline (T7.14-B): the strike-list boundary plus the
     * earliest monitor-snapshot boundary. Ended snapshots use the
     * completed-train TTL, active ones the monitor TTL by status.
     */
    private fun homeDeadline(): Instant? {
        val strike = mutable.value.strikes.freshness?.staleTransitionAt(policy.cacheTtl)
        val monitors = mutable.value.monitors.mapNotNull { monitor ->
            monitor.lastSnapshot?.freshness?.staleTransitionAt(homeMonitorTtl(monitor))
        }.minOrNull()
        return listOfNotNull(strike, monitors).minOrNull()
    }

    private fun homeMonitorTtl(monitor: TrainMonitor): Duration =
        if (monitor.endedAt != null) realtimePolicy.completedTrainTtl
        else realtimePolicy.ttlForTrain(monitor.lastSnapshot?.train?.summary?.status)

    /**
     * Monitor ids whose snapshot must render stale (T7.14 corrective): a
     * recorded refresh failure forces immediate staleness even before the
     * TTL, alongside the usual elapsed-time aging.
     */
    private fun staleMonitorIds(monitors: List<TrainMonitor>, now: Instant): Set<String> =
        monitors.filter { monitor ->
            if (monitor.refreshFailure != null) return@filter true
            val base = monitor.lastSnapshot?.freshness ?: return@filter false
            base.aged(now, homeMonitorTtl(monitor)) is DataFreshness.Stale
        }.map { it.id.value }.toSet()

    private fun ageHome() {
        val now = clock.now()
        val current = mutable.value
        val agedStrikes = current.strikes.agedDisplay(now, policy.cacheTtl)
        val staleIds = staleMonitorIds(current.monitors, now)
        if (agedStrikes !== current.strikes || staleIds != current.staleMonitorIds) {
            mutable.value = current.copy(strikes = agedStrikes, staleMonitorIds = staleIds)
        }
    }

    private fun relevantStrikes(strikes: List<Strike>, now: Instant): List<Strike> =
        strikes.filter {
            it.end > now && it.status !in setOf(StrikeStatus.REVOKED, StrikeStatus.COMPLETED) && it.isRailwayRelevant()
        }

    private fun aggregate(aggregates: LocalAggregates, strikes: DataResult<List<Strike>>): HomeState {
        val now = clock.now()
        val relevant = relevantStrikes((strikes as? DataResult.Data)?.value.orEmpty(), now)
        return mutable.value.copy(
            recentSearches = aggregates.history.take(HOME_RECENT_LIMIT),
            favoriteStations = aggregates.stations,
            favoriteRoutes = aggregates.routes,
            favoriteTrains = aggregates.trains,
            monitors = aggregates.monitors,
            // A recorded refresh failure is stale from the moment the new
            // monitor list arrives, without waiting for the next tick.
            staleMonitorIds = staleMonitorIds(aggregates.monitors, now),
            strikes = mutable.value.strikes.copy(
                data = relevant,
                failure = ((strikes as? DataResult.Failure)?.error ?: (strikes as? DataResult.Data)?.warning).unlessNotFound(),
                stale = strikes is DataResult.Data && strikes.freshness !is DataFreshness.Fresh,
                freshness = (strikes as? DataResult.Data)?.freshness ?: mutable.value.strikes.freshness,
            ),
            referenceTime = now,
            observationFailed = false,
        )
    }
}
