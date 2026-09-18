package it.danielebufarini.trenify.app

import it.danielebufarini.trenify.core.model.FavoriteRoute
import it.danielebufarini.trenify.core.model.FavoriteTrain
import it.danielebufarini.trenify.core.model.JourneySearchHistoryEntry
import it.danielebufarini.trenify.core.model.JourneySearchMode
import it.danielebufarini.trenify.core.model.Station
import it.danielebufarini.trenify.core.model.TrainSearchHistoryEntry
import it.danielebufarini.trenify.feature.favorites.FavoritesComponent
import kotlinx.coroutines.flow.StateFlow

/**
 * T8.9: semantic Saved snapshots over the existing [FavoritesComponent].
 * No rendering, persistence, ordering, recording or navigation logic is
 * owned here. Favorite identity, history ordering, corrupt-row skipping,
 * bad-date degradation and repeat/prefill routing stay in the shared
 * component/repository layers; platforms only project truth.
 *
 * Stable list identity is always the shared semantic identity: station id,
 * route id, [FavoriteTrain.id] (number + origin + operator, never display
 * text or position) and history entry id. Platforms must use these keys
 * for Lazy list keys / ForEach IDs.
 */
data class NativeFavoriteStationRow(
    val stationId: String,
    val name: String,
    val pending: Boolean,
    val failed: Boolean,
)

data class NativeFavoriteRouteRow(
    val identity: String,
    val originId: String,
    val originName: String,
    val destinationId: String,
    val destinationName: String,
    val pending: Boolean,
    val failed: Boolean,
)

data class NativeFavoriteTrainRow(
    val identity: String,
    val number: String,
    val originId: String?,
    val originName: String?,
    val operatorName: String?,
    val destinationName: String?,
    val pending: Boolean,
    val failed: Boolean,
) {
    val canOpen: Boolean get() = true
    val canRemove: Boolean get() = !pending
}

data class NativeJourneyHistoryRow(
    val entryId: String,
    val originId: String,
    val originName: String,
    val destinationId: String,
    val destinationName: String,
    val atEpochSeconds: Long,
    val mode: JourneySearchMode,
    val submittedAtEpochSeconds: Long,
    val pending: Boolean,
)

data class NativeTrainHistoryRow(
    val entryId: String,
    val number: String,
    val serviceDateString: String?,
    val originId: String?,
    val originName: String?,
    val operatorName: String?,
    val destinationName: String?,
    val submittedAtEpochSeconds: Long,
    val pending: Boolean,
)

data class NativeSavedState(
    val loading: Boolean,
    val historyLoading: Boolean,
    val stations: List<NativeFavoriteStationRow>,
    val routes: List<NativeFavoriteRouteRow>,
    val trains: List<NativeFavoriteTrainRow>,
    val journeyHistory: List<NativeJourneyHistoryRow>,
    val trainHistory: List<NativeTrainHistoryRow>,
    val observationFailed: Boolean,
    val historyObservationFailed: Boolean,
    val favoriteFailed: Boolean,
    val historyFailed: Boolean,
) {
    val favoritesEmpty: Boolean get() = stations.isEmpty() && routes.isEmpty() && trains.isEmpty()
    val journeyHistoryEmpty: Boolean get() = journeyHistory.isEmpty()
    val trainHistoryEmpty: Boolean get() = trainHistory.isEmpty()
    val savedEmpty: Boolean get() = favoritesEmpty && journeyHistoryEmpty && trainHistoryEmpty
    /** Retained favorites content stays visible while the failure flag shows degradation. */
    val hasRetainedFavorites: Boolean get() = observationFailed && !favoritesEmpty
    val hasRetainedHistory: Boolean get() = historyObservationFailed &&
        (journeyHistory.isNotEmpty() || trainHistory.isNotEmpty())
}

/** Typed actions over one existing Saved (Favorites) component; all semantics stay shared. */
class NativeSavedPresentation internal constructor(
    private val component: FavoritesComponent,
    private val owner: NativeProjectionOwner,
) {
    val state: StateFlow<NativeSavedState> = owner.project(component.state) { it.nativeSaved() }

    fun openStation(stationId: String) = act {
        component.state.value.stations.firstOrNull { it.id.value == stationId }?.let(component::open)
    }

    fun openRoute(identity: String) = act {
        component.state.value.routes.firstOrNull { it.id.value == identity }?.let(component::open)
    }

    fun openTrain(identity: String) = act {
        component.state.value.trains.firstOrNull { it.id.value == identity }?.let(component::open)
    }

    fun removeStation(stationId: String) = act {
        component.state.value.stations.firstOrNull { it.id.value == stationId }?.let(component::remove)
    }

    fun removeRoute(identity: String) = act {
        component.state.value.routes.firstOrNull { it.id.value == identity }?.let(component::remove)
    }

    fun removeTrain(identity: String) = act {
        component.state.value.trains.firstOrNull { it.id.value == identity }?.let(component::remove)
    }

    fun repeatJourney(entryId: String) = act {
        component.state.value.historyEntries
            .firstOrNull { it.id.value == entryId && it is JourneySearchHistoryEntry }
            ?.let(component::repeatHistory)
    }

    fun repeatTrain(entryId: String) = act {
        component.state.value.historyEntries
            .firstOrNull { it.id.value == entryId && it is TrainSearchHistoryEntry }
            ?.let(component::repeatHistory)
    }

    fun removeHistory(entryId: String) = act {
        component.state.value.historyEntries.firstOrNull { it.id.value == entryId }?.let(component::removeHistory)
    }

    fun clearHistory() = act(component::clearHistory)

    fun retry() = act(component::retry)

    /** Favorites-section Retry: scoped to favorites failures only. */
    fun retryFavorites() = act(component::retryFavorites)

    /** History-section Retry: scoped to history failures only. */
    fun retryHistory() = act(component::retryHistory)

    private fun act(action: () -> Unit) { if (owner.job.isActive) action() }
}

private fun it.danielebufarini.trenify.feature.favorites.FavoritesState.nativeSaved(): NativeSavedState {
    // History kind split preserves repository order within each kind:
    // the shared observation is already newest-first merged, so filtering
    // by kind keeps newest-first per section without platform sorting.
    val journeys = historyEntries.filterIsInstance<JourneySearchHistoryEntry>()
    val trains = historyEntries.filterIsInstance<TrainSearchHistoryEntry>()
    return NativeSavedState(
        loading = loading,
        historyLoading = historyLoading,
        stations = stations.map { it.nativeRow(pendingStationIds, failedMutation?.station == it) },
        routes = routes.map { it.nativeRow(pendingRouteIds, failedRouteMutation?.route == it) },
        trains = this.trains.map { it.nativeRow(pendingTrainIds, failedTrainMutation?.train == it) },
        journeyHistory = journeys.map { it.nativeHistoryRow(historyPendingIds) },
        trainHistory = trains.map { it.nativeHistoryRow(historyPendingIds) },
        observationFailed = observationFailed,
        historyObservationFailed = historyObservationFailed,
        favoriteFailed = failedMutation != null || failedRouteMutation != null || failedTrainMutation != null,
        historyFailed = historyFailedMutation,
    )
}

private fun Station.nativeRow(
    pending: Set<it.danielebufarini.trenify.core.model.StationId>,
    failed: Boolean,
) = NativeFavoriteStationRow(id.value, name, id in pending, failed)

private fun FavoriteRoute.nativeRow(
    pending: Set<it.danielebufarini.trenify.core.model.FavoriteRouteId>,
    failed: Boolean,
) = NativeFavoriteRouteRow(id.value, origin.id.value, origin.name, destination.id.value, destination.name,
    id in pending, failed)

private fun FavoriteTrain.nativeRow(
    pending: Set<it.danielebufarini.trenify.core.model.FavoriteTrainId>,
    failed: Boolean,
) = NativeFavoriteTrainRow(id.value, number.value, originId?.value, originName, operator?.name,
    destinationName, id in pending, failed)

internal fun JourneySearchHistoryEntry.nativeHistoryRow(
    pending: Set<it.danielebufarini.trenify.core.model.SearchHistoryEntryId>,
) = NativeJourneyHistoryRow(id.value, origin.id.value, origin.name, destination.id.value, destination.name,
    at.toEpochMilliseconds() / 1000, mode, submittedAt.toEpochMilliseconds() / 1000, id in pending)

internal fun TrainSearchHistoryEntry.nativeHistoryRow(
    pending: Set<it.danielebufarini.trenify.core.model.SearchHistoryEntryId>,
) = NativeTrainHistoryRow(id.value, number.value, serviceDate?.toString(), originId?.value, originName,
    operator?.name, destinationName, submittedAt.toEpochMilliseconds() / 1000, id in pending)
