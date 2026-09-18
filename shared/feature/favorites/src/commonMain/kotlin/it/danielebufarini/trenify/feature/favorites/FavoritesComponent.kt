package it.danielebufarini.trenify.feature.favorites

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import it.danielebufarini.trenify.core.domain.FavoritesRepository
import it.danielebufarini.trenify.core.domain.HistoryRepository
import it.danielebufarini.trenify.core.model.FavoriteRoute
import it.danielebufarini.trenify.core.model.FavoriteRouteId
import it.danielebufarini.trenify.core.model.FavoriteTrain
import it.danielebufarini.trenify.core.model.FavoriteTrainId
import it.danielebufarini.trenify.core.model.JourneySearchHistoryEntry
import it.danielebufarini.trenify.core.model.JourneySearchIntent
import it.danielebufarini.trenify.core.model.JourneySearchRequest
import it.danielebufarini.trenify.core.model.SearchHistoryEntry
import it.danielebufarini.trenify.core.model.SearchHistoryEntryId
import it.danielebufarini.trenify.core.model.Station
import it.danielebufarini.trenify.core.model.StationId
import it.danielebufarini.trenify.core.model.TrainLookupIntent
import it.danielebufarini.trenify.core.model.TrainSearchHistoryEntry
import it.danielebufarini.trenify.core.ui.componentScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class FavoriteMutation(
    val station: Station,
    val favorite: Boolean,
)

data class FavoriteRouteMutation(
    val route: FavoriteRoute,
    val favorite: Boolean,
)

data class FavoriteTrainMutation(
    val train: FavoriteTrain,
    val favorite: Boolean,
)

data class FavoritesState(
    val stations: List<Station> = emptyList(),
    val routes: List<FavoriteRoute> = emptyList(),
    val trains: List<FavoriteTrain> = emptyList(),
    val loading: Boolean = true,
    val pendingStationIds: Set<StationId> = emptySet(),
    val pendingRouteIds: Set<FavoriteRouteId> = emptySet(),
    val pendingTrainIds: Set<FavoriteTrainId> = emptySet(),
    val failedMutation: FavoriteMutation? = null,
    val failedRouteMutation: FavoriteRouteMutation? = null,
    val failedTrainMutation: FavoriteTrainMutation? = null,
    val observationFailed: Boolean = false,
    /**
     * T8.9 Saved aggregation: merged newest-first search history observed
     * from the shared [HistoryRepository]. Ordering, corrupt-row skipping
     * and bad-date degradation are inherited from the repository; this
     * component never reorders, parses or fabricates history state.
     * Null repository (legacy tests) means history stays empty/loaded.
     */
    val historyEntries: List<SearchHistoryEntry> = emptyList(),
    val historyLoading: Boolean = false,
    val historyPendingIds: Set<SearchHistoryEntryId> = emptySet(),
    val historyFailedMutation: Boolean = false,
    val historyObservationFailed: Boolean = false,
)

class FavoritesComponent(
    componentContext: ComponentContext,
    private val repository: FavoritesRepository,
    private val onStation: (Station) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val onRoute: (JourneySearchIntent) -> Unit = {},
    private val onTrainLookup: (TrainLookupIntent) -> Unit = {},
    private val historyRepository: HistoryRepository? = null,
    private val onJourneyRepeat: (JourneySearchRequest) -> Unit = {},
    private val onTrainRepeat: (TrainSearchHistoryEntry) -> Unit = {},
) : ComponentContext by componentContext {
    private val scope = componentScope(lifecycle, dispatcher)
    private val mutable = MutableValue(
        FavoritesState(historyLoading = historyRepository != null),
    )
    private var observation: Job? = null
    private var historyObservation: Job? = null

    val state: Value<FavoritesState> = mutable

    init {
        observeFavorites()
        observeHistory()
    }

    fun open(station: Station) {
        onStation(station)
    }

    fun open(route: FavoriteRoute) {
        onRoute(route.searchIntent)
    }

    fun open(train: FavoriteTrain) {
        // A fresh lookup with stable criteria only; never a cached TrainRunId.
        onTrainLookup(train.lookupIntent)
    }

    fun remove(station: Station) {
        mutate(FavoriteMutation(station, favorite = false))
    }

    fun remove(route: FavoriteRoute) {
        mutate(FavoriteRouteMutation(route, favorite = false))
    }

    fun remove(train: FavoriteTrain) {
        mutate(FavoriteTrainMutation(train, favorite = false))
    }

    fun retry() {
        when {
            mutable.value.failedMutation != null -> mutate(requireNotNull(mutable.value.failedMutation))
            mutable.value.failedRouteMutation != null -> mutate(requireNotNull(mutable.value.failedRouteMutation))
            mutable.value.failedTrainMutation != null -> mutate(requireNotNull(mutable.value.failedTrainMutation))
            mutable.value.historyFailedMutation -> {
                mutable.value = mutable.value.copy(historyFailedMutation = false)
                observeHistory()
            }
            else -> {
                observeFavorites()
                observeHistory()
            }
        }
    }

    /**
     * Failure-scoped retries for the native Saved sections. Each retries
     * only its own area's failed mutation (or re-observes its own area
     * when nothing is pending), so a Favorites Retry never consumes a
     * History failure and vice versa. Mutation logic stays here; platforms
     * only select the scope.
     */
    fun retryFavorites() {
        when {
            mutable.value.failedMutation != null -> mutate(requireNotNull(mutable.value.failedMutation))
            mutable.value.failedRouteMutation != null -> mutate(requireNotNull(mutable.value.failedRouteMutation))
            mutable.value.failedTrainMutation != null -> mutate(requireNotNull(mutable.value.failedTrainMutation))
            else -> observeFavorites()
        }
    }

    fun retryHistory() {
        if (mutable.value.historyFailedMutation) {
            mutable.value = mutable.value.copy(historyFailedMutation = false)
        }
        observeHistory()
    }

    /**
     * History repeat from Saved: journey entries populate the existing
     * search flow with the full recorded request for explicit execution
     * (original date/time preserved, never shifted to now); train entries
     * prefill the train-search flow with number/date/discrimination.
     * Never executes a search and never reopens a cached result.
     */
    fun repeatHistory(entry: SearchHistoryEntry) {
        if (entry.id in mutable.value.historyPendingIds) return
        val train = entry as? TrainSearchHistoryEntry
        if (train != null) onTrainRepeat(train)
        else (entry as? JourneySearchHistoryEntry)?.let { onJourneyRepeat(it.request()) }
    }

    fun removeHistory(entry: SearchHistoryEntry) {
        val history = historyRepository ?: return
        val id = entry.id
        if (id in mutable.value.historyPendingIds) return
        mutable.value = mutable.value.copy(
            historyPendingIds = mutable.value.historyPendingIds + id,
            historyFailedMutation = false,
        )
        scope.launch {
            try {
                history.removeSearch(id)
                mutable.value = mutable.value.copy(
                    historyEntries = mutable.value.historyEntries.filterNot { it.id == id },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutable.value = mutable.value.copy(historyFailedMutation = true)
            } finally {
                mutable.value = mutable.value.copy(
                    historyPendingIds = mutable.value.historyPendingIds - id,
                )
            }
        }
    }

    fun clearHistory() {
        val history = historyRepository ?: return
        if (mutable.value.historyEntries.isEmpty()) return
        scope.launch {
            try {
                history.clearSearchHistory()
                mutable.value = mutable.value.copy(historyEntries = emptyList(), historyFailedMutation = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutable.value = mutable.value.copy(historyFailedMutation = true)
            }
        }
    }

    private fun observeHistory() {
        val history = historyRepository ?: return
        if (historyObservation?.isActive == true) return
        mutable.value = mutable.value.copy(historyLoading = true, historyObservationFailed = false)
        historyObservation = scope.launch {
            history.observeSearchHistory()
                .catch {
                    mutable.value = mutable.value.copy(historyLoading = false, historyObservationFailed = true)
                }
                .collect { entries ->
                    mutable.value = mutable.value.copy(
                        historyEntries = entries,
                        historyLoading = false,
                        historyObservationFailed = false,
                    )
                }
        }
    }

    private fun observeFavorites() {
        if (observation?.isActive == true) return
        mutable.value = mutable.value.copy(loading = true, observationFailed = false)
        observation = scope.launch {
            combine(
                repository.observeFavoriteStations(),
                repository.observeFavoriteRoutes(),
                repository.observeFavoriteTrains(),
            ) { stations, routes, trains -> Triple(stations, routes, trains) }
                .catch {
                    mutable.value = mutable.value.copy(loading = false, observationFailed = true)
                }
                .collect { (stations, routes, trains) ->
                    mutable.value = mutable.value.copy(
                        stations = stations,
                        routes = routes,
                        trains = trains,
                        loading = false,
                        observationFailed = false,
                    )
                }
        }
    }

    private fun mutate(mutation: FavoriteTrainMutation) {
        val trainId = mutation.train.id
        if (trainId in mutable.value.pendingTrainIds) return
        mutable.value = mutable.value.copy(
            pendingTrainIds = mutable.value.pendingTrainIds + trainId,
            failedMutation = null,
            failedRouteMutation = null,
            failedTrainMutation = null,
        )
        scope.launch {
            try {
                repository.setFavorite(mutation.train, mutation.favorite)
                mutable.value = mutable.value.copy(
                    trains = if (mutation.favorite) {
                        (mutable.value.trains + mutation.train).distinctBy(FavoriteTrain::id)
                    } else {
                        mutable.value.trains.filterNot { it.id == trainId }
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutable.value = mutable.value.copy(failedTrainMutation = mutation)
            } finally {
                mutable.value = mutable.value.copy(
                    pendingTrainIds = mutable.value.pendingTrainIds - trainId,
                )
            }
        }
    }

    private fun mutate(mutation: FavoriteMutation) {
        val stationId = mutation.station.id
        if (stationId in mutable.value.pendingStationIds) return
        mutable.value = mutable.value.copy(
            pendingStationIds = mutable.value.pendingStationIds + stationId,
            failedMutation = null,
            failedRouteMutation = null,
            failedTrainMutation = null,
        )
        scope.launch {
            try {
                repository.setFavorite(mutation.station, mutation.favorite)
                mutable.value = mutable.value.copy(
                    stations = if (mutation.favorite) {
                        (mutable.value.stations + mutation.station).distinctBy(Station::id)
                    } else {
                        mutable.value.stations.filterNot { it.id == stationId }
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutable.value = mutable.value.copy(failedMutation = mutation)
            } finally {
                mutable.value = mutable.value.copy(
                    pendingStationIds = mutable.value.pendingStationIds - stationId,
                )
            }
        }
    }

    private fun mutate(mutation: FavoriteRouteMutation) {
        val routeId = mutation.route.id
        if (routeId in mutable.value.pendingRouteIds) return
        mutable.value = mutable.value.copy(
            pendingRouteIds = mutable.value.pendingRouteIds + routeId,
            failedMutation = null,
            failedRouteMutation = null,
            failedTrainMutation = null,
        )
        scope.launch {
            try {
                repository.setFavorite(mutation.route, mutation.favorite)
                mutable.value = mutable.value.copy(
                    routes = if (mutation.favorite) {
                        (mutable.value.routes + mutation.route).distinctBy(FavoriteRoute::id)
                    } else {
                        mutable.value.routes.filterNot { it.id == routeId }
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutable.value = mutable.value.copy(failedRouteMutation = mutation)
            } finally {
                mutable.value = mutable.value.copy(
                    pendingRouteIds = mutable.value.pendingRouteIds - routeId,
                )
            }
        }
    }
}
