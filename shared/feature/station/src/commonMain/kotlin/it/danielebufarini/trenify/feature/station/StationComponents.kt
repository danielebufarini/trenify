package it.danielebufarini.trenify.feature.station

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.Lifecycle
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlin.time.Clock

data class StationFavoriteState(
    val favoriteIds: Set<StationId> = emptySet(),
    val pendingIds: Set<StationId> = emptySet(),
    val failedIds: Set<StationId> = emptySet(),
)

data class StationSearchState(
    val query: String = "",
    val results: RealtimeState<List<Station>> = RealtimeState(),
    /**
     * Station recency suggestions, newest first. This is recent-station
     * recency, explicitly distinct from durable search history: it tracks
     * opened stations only and has its own remove-one/clear operations that
     * never touch the canonical station rows, provider mappings, favorites,
     * monitors or search history.
     */
    val recent: List<Station> = emptyList(),
    /**
     * Independent failure overlay for recency observation/mutation. Existing
     * [recent] rows remain authoritative cached content and stay usable; this
     * flag prevents that retained content from being presented as current.
     */
    val recencyFailed: Boolean = false,
)

private class StationFavoriteController(
    lifecycle: Lifecycle,
    private val repository: FavoritesRepository,
    dispatcher: CoroutineDispatcher,
) {
    private val scope = componentScope(lifecycle, dispatcher)
    private val mutable = MutableValue(StationFavoriteState())
    private var observedFavoriteIds: Set<StationId> = emptySet()
    private var pendingFavorites: Map<StationId, Boolean> = emptyMap()

    val state: Value<StationFavoriteState> = mutable

    init {
        scope.launch {
            repository.observeFavoriteStations()
                // Corrective T8.9-B1: every sibling favorites observer
                // degrades through catch (Home, route/train controllers,
                // Saved) so an observation failure can never kill the
                // collector mid-tree. Retained ids stay usable; no new
                // state and no production behavior change (the SQL flow
                // never throws).
                .catch { }
                .collect { stations ->
                    observedFavoriteIds = stations.mapTo(mutableSetOf(), Station::id)
                    mutable.value = mutable.value.copy(favoriteIds = displayedFavoriteIds())
                }
        }
    }

    fun toggle(station: Station) {
        val stationId = station.id
        val current = mutable.value
        if (stationId in current.pendingIds) return
        val favorite = stationId !in current.favoriteIds
        pendingFavorites += stationId to favorite
        mutable.value = current.copy(
            favoriteIds = displayedFavoriteIds(),
            pendingIds = current.pendingIds + stationId,
            failedIds = current.failedIds - stationId,
        )
        scope.launch {
            try {
                repository.setFavorite(station, favorite)
                // No optimistic union here: the observation above owns
                // observedFavoriteIds, so a continuation resuming after a
                // completed delete-all can never write a stale id back over
                // the newer empty state. Immediate feedback still comes from
                // the pending fold while the save is in flight.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                pendingFavorites -= stationId
                mutable.value = mutable.value.copy(
                    favoriteIds = displayedFavoriteIds(),
                    failedIds = mutable.value.failedIds + stationId,
                )
            } finally {
                pendingFavorites -= stationId
                mutable.value = mutable.value.copy(
                    favoriteIds = displayedFavoriteIds(),
                    pendingIds = mutable.value.pendingIds - stationId,
                )
            }
        }
    }

    private fun displayedFavoriteIds(): Set<StationId> = pendingFavorites.entries.fold(observedFavoriteIds) { ids, entry ->
        if (entry.value) ids + entry.key else ids - entry.key
    }
}

/**
 * Repository-resolved board identity (T7.13 corrective pass): the live
 * canonical station plus the controlled unavailable flag. Decompose values
 * cannot hold nullable types, so both travel in this holder.
 */
data class StationResolution(
    val station: Station? = null,
    val unavailable: Boolean = false,
)

/** Durable station-search query (T7.13-B); results re-derive from a fresh search. */
@Serializable
private data class SavedStationQuery(val query: String = "")

class StationSearchComponent(
    componentContext: ComponentContext,
    private val search: SearchStations,
    favoritesRepository: FavoritesRepository,
    private val onSelect: (Station) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val policy: RealtimePolicy = RealtimePolicy(),
    private val clock: Clock = Clock.System,
    private val historyRepository: HistoryRepository? = null,
) : ComponentContext by componentContext {
    private val scope = componentScope(lifecycle, dispatcher)
    private val freshnessWatcher = FreshnessWatcher(lifecycle, scope, clock)
    private val mutable = MutableValue(StationSearchState(
        query = stateKeeper.consume(KEY_STATION_QUERY, SavedStationQuery.serializer())?.query.orEmpty(),
    ))
    val state: Value<StationSearchState> = mutable
    private val favorites = StationFavoriteController(lifecycle, favoritesRepository, dispatcher)
    val favoriteState: Value<StationFavoriteState> = favorites.state
    private var request: Job? = null

    init {
        stateKeeper.register(KEY_STATION_QUERY, SavedStationQuery.serializer()) {
            SavedStationQuery(query = mutable.value.query)
        }
    }

    init {
        // Elapsed-time aging (T7.14-B): suggestion freshness becomes Stale
        // at the policy boundary with zero provider calls.
        freshnessWatcher.observe(
            nextDeadline = { mutable.value.results.freshness?.staleTransitionAt(policy.stationTtl) },
            onTick = {
                val aged = mutable.value.results.agedDisplay(clock.now(), policy.stationTtl)
                if (aged !== mutable.value.results) mutable.value = mutable.value.copy(results = aged)
            },
        )
        historyRepository?.let { history ->
            scope.launch {
                history.observeRecentStations()
                    .catch { mutable.value = mutable.value.copy(recencyFailed = true) }
                    .collect { stations ->
                        mutable.value = mutable.value.copy(recent = stations, recencyFailed = false)
                    }
            }
        }
    }

    fun query(value: String) {
        request?.cancel()
        mutable.value = mutable.value.copy(query = value, results = RealtimeState(loading = value.isNotBlank()))
        if (value.isBlank()) return
        request = scope.launch {
            delay(policy.searchDebounce)
            load(value, false)
        }
    }
    fun retry() {
        request?.cancel()
        val query = mutable.value.query
        if (query.isBlank()) return
        request = scope.launch { load(query, true) }
    }
    private suspend fun load(query: String, force: Boolean) {
        mutable.value = mutable.value.copy(results = mutable.value.results.copy(loading = true, failure = null))
        val result = search(query, force)
        // A cancelled old query can never replace the latest search results.
        currentCoroutineContext().ensureActive()
        if (mutable.value.query != query) return
        mutable.value = mutable.value.copy(results = when (result) {
            is DataResult.Data -> RealtimeState(result.value, failure = result.warning,
                stale = result.freshness !is DataFreshness.Fresh, freshness = result.freshness)
            is DataResult.Failure -> RealtimeState(failure = result.error)
        })
        freshnessWatcher.poke()
    }
    fun select(station: Station) { onSelect(station) }
    fun toggleFavorite(station: Station) { favorites.toggle(station) }
    /**
     * Removes one station from the recency suggestions. The canonical
     * station row and its provider mappings are never deleted.
     */
    fun removeRecent(station: Station) {
        val history = historyRepository ?: return
        scope.launch {
            try {
                history.removeRecentStation(station)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutable.value = mutable.value.copy(recencyFailed = true)
            }
        }
    }
    /** Clears recency suggestions only; search history and all other data survive. */
    fun clearRecent() {
        val history = historyRepository ?: return
        scope.launch {
            try {
                history.clearHistory()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutable.value = mutable.value.copy(recencyFailed = true)
            }
        }
    }
}

private const val KEY_STATION_QUERY = "station-search-query"
private const val KEY_BOARD_KIND = "station-board-kind"

/** Durable board kind (T7.13-B); board content re-observes the repository. */
@Serializable
private data class SavedBoardKind(val kind: String = "DEPARTURES") {
    fun kindValue(): BoardKind? = runCatching { BoardKind.valueOf(kind) }.getOrNull()
}

class StationBoardComponent(
    componentContext: ComponentContext,
    /**
     * Stable station identity (T7.13 corrective pass): the durable route
     * carries only this id, never a serialized display-name snapshot. The
     * live [Station] for display, favorites and board refreshes is resolved
     * from the repository through [observeStation] below.
     */
    val stationId: StationId,
    private val observeStation: ObserveStation,
    private val load: LoadStationBoard,
    favoritesRepository: FavoritesRepository,
    private val available: StateFlow<Boolean>,
    private val onTrain: (TrainRunId) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val policy: RealtimePolicy = RealtimePolicy(),
    private val clock: Clock = Clock.System,
) : ComponentContext by componentContext {
    private val scope = componentScope(lifecycle, dispatcher)
    private val freshnessWatcher = FreshnessWatcher(lifecycle, scope, clock)
    private val restoredKind =
        stateKeeper.consume(KEY_BOARD_KIND, SavedBoardKind.serializer())?.kindValue() ?: BoardKind.DEPARTURES
    private val kindFlow = MutableStateFlow(restoredKind)
    private val mutableKind = MutableValue(restoredKind)
    val kind: Value<BoardKind> = mutableKind
    private val mutable = MutableValue(RealtimeState<StationBoard>())
    val state: Value<RealtimeState<StationBoard>> = mutable
    /**
     * Canonical live station resolved by id (T7.13 corrective pass):
     * [StationResolution.station] is null while the local resolution is
     * pending. Display, favorites and board refreshes always use this
     * value — never route text — so a rename after state was saved shows
     * the current canonical name. [StationResolution.unavailable] is the
     * controlled unavailable state: true once the local resolution
     * completed with no resolvable station (deleted or unknown id). The
     * board then shows its not-found path with usable Back and never
     * manufactures a Station from old route text.
     */
    private val stationFlow = MutableStateFlow<Station?>(null)
    private val mutableStation = MutableValue(StationResolution())
    val stationState: Value<StationResolution> = mutableStation
    private val favorites = StationFavoriteController(lifecycle, favoritesRepository, dispatcher)
    val favoriteState: Value<StationFavoriteState> = favorites.state
    /**
     * Visibility polling, created lazily on the first successful identity
     * resolution (T7.13 corrective pass). Starting the loop only once the
     * station is known gives a single freshness-gated initial load: a
     * separate resolve-triggered refresh would race the loop's first check
     * and double-load (observed as two board refreshes on open). Hidden,
     * offline or unresolvable boards create no polling work and issue no
     * provider calls; the loop follows the component lifecycle afterwards.
     */
    private var poller: VisibleRefresh? = null

    init {
        // Elapsed-time aging (T7.14-B): the held board becomes Stale at the
        // policy boundary with zero provider calls; resume recomputes from
        // the clock. Repository emissions overwrite the display copy.
        freshnessWatcher.observe(
            nextDeadline = { mutable.value.freshness?.staleTransitionAt(policy.boardTtl) },
            onTick = {
                val aged = mutable.value.agedDisplay(clock.now(), policy.boardTtl)
                if (aged !== mutable.value) mutable.value = aged
            },
        )
        stateKeeper.register(KEY_BOARD_KIND, SavedBoardKind.serializer()) {
            SavedBoardKind(kind = kindFlow.value.name)
        }
        scope.launch {
            observeStation(stationId).collect { station -> onStationResolved(station) }
        }
        scope.launch {
            combine(kindFlow, stationFlow) { kind, station -> kind to station }
                .collectLatest { (kind, station) ->
                    if (station == null) {
                        mutable.value = RealtimeState()
                    } else {
                        load.observe(station, kind).collect { result ->
                            mutable.value = when (result) {
                                is DataResult.Data -> RealtimeState(result.value, mutable.value.loading,
                                    result.warning, result.freshness !is DataFreshness.Fresh, result.freshness)
                                // A bare NOT_FOUND with no cached board is the
                                // never-fetched sentinel, not a reportable
                                // failure: the board renders its empty state
                                // while the freshness-gated refresh resolves.
                                is DataResult.Failure ->
                                    if (result.error == DomainFailure.NOT_FOUND && mutable.value.data == null) {
                                        RealtimeState(loading = mutable.value.loading)
                                    } else {
                                        mutable.value.copy(failure = result.error.takeUnless { mutable.value.loading })
                                    }
                            }
                            freshnessWatcher.poke()
                        }
                    }
                }
        }
    }

    private fun onStationResolved(station: Station?) {
        stationFlow.value = station
        mutableStation.value = StationResolution(station = station, unavailable = station == null)
        if (station != null && poller == null) {
            poller = VisibleRefresh(lifecycle, scope, available, policy.pollingInterval, policy.maximumBackoff) { force ->
                refresh(kindFlow.value, force)
            }
        }
    }

    private suspend fun refresh(kind: BoardKind, force: Boolean): Boolean {
        // Identity resolution is strictly local: without a resolved station
        // there is nothing to refresh and no network call is issued.
        val station = stationFlow.value ?: return true
        mutable.value = mutable.value.copy(loading = true, failure = null)
        val result = load(station, kind, force)
        if (kindFlow.value == kind) {
            mutable.value = when (result) {
                is DataResult.Data -> RealtimeState(result.value, failure = result.warning, stale = result.freshness !is DataFreshness.Fresh, freshness = result.freshness)
                // A provider 404 with no cached board means no board content:
                // the empty state stays truthful and the visible Refresh
                // control remains the retry path.
                is DataResult.Failure ->
                    if (result.error == DomainFailure.NOT_FOUND && mutable.value.data == null) {
                        RealtimeState()
                    } else {
                        mutable.value.copy(loading = false, failure = result.error)
                    }
            }
            freshnessWatcher.poke()
        }
        return result is DataResult.Data && result.warning == null
    }
    fun select(kind: BoardKind) {
        if (kind == kindFlow.value) return
        mutable.value = RealtimeState(loading = true)
        mutableKind.value = kind
        kindFlow.value = kind
        poller?.refresh(replace = true)
    }
    fun refresh() = poller?.refresh()
    fun openTrain(id: TrainRunId) = onTrain(id)
    fun toggleFavorite() { stationFlow.value?.let(favorites::toggle) }
}
