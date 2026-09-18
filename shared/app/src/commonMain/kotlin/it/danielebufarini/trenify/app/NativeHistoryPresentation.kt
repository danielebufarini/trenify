package it.danielebufarini.trenify.app

import it.danielebufarini.trenify.core.model.JourneySearchHistoryEntry
import it.danielebufarini.trenify.core.model.TrainSearchHistoryEntry
import it.danielebufarini.trenify.feature.journey.SearchHistoryComponent
import kotlinx.coroutines.flow.StateFlow

/**
 * T8.9 corrective: semantic History snapshots over the existing
 * [SearchHistoryComponent] that backs the Journey-tab History child.
 * Ordering, recording, corrupt-row skipping, bad-date degradation and
 * repeat routing stay shared; platforms only project truth.
 *
 * Row shapes and stable entry-id identity are shared with the Saved
 * projection ([NativeJourneyHistoryRow]/[NativeTrainHistoryRow]); this
 * facade only scopes observation and actions to the History destination.
 * Its retry is inherently history-scoped (the component owns no
 * favorites state).
 */
data class NativeHistoryState(
    val loading: Boolean,
    val journeyHistory: List<NativeJourneyHistoryRow>,
    val trainHistory: List<NativeTrainHistoryRow>,
    val observationFailed: Boolean,
    val failedMutation: Boolean,
) {
    val journeyHistoryEmpty: Boolean get() = journeyHistory.isEmpty()
    val trainHistoryEmpty: Boolean get() = trainHistory.isEmpty()
    val historyEmpty: Boolean get() = journeyHistoryEmpty && trainHistoryEmpty
    /** Retained history stays visible while the failure flag shows degradation. */
    val hasRetainedHistory: Boolean get() = observationFailed && !historyEmpty
}

/** Typed actions over one existing History component; all semantics stay shared. */
class NativeHistoryPresentation internal constructor(
    private val component: SearchHistoryComponent,
    private val owner: NativeProjectionOwner,
) {
    val state: StateFlow<NativeHistoryState> = owner.project(component.state) { it.nativeHistory() }

    fun repeatJourney(entryId: String) = act {
        component.state.value.entries
            .firstOrNull { it.id.value == entryId && it is JourneySearchHistoryEntry }
            ?.let(component::repeat)
    }

    fun repeatTrain(entryId: String) = act {
        component.state.value.entries
            .firstOrNull { it.id.value == entryId && it is TrainSearchHistoryEntry }
            ?.let(component::repeat)
    }

    fun removeHistory(entryId: String) = act {
        component.state.value.entries.firstOrNull { it.id.value == entryId }?.let(component::remove)
    }

    fun clearHistory() = act(component::clear)

    fun retry() = act(component::retry)

    private fun act(action: () -> Unit) { if (owner.job.isActive) action() }
}

private fun it.danielebufarini.trenify.feature.journey.SearchHistoryState.nativeHistory(): NativeHistoryState {
    // Kind split preserves the already-newest-first merged repository
    // order within each kind; no platform sorting.
    val journeys = entries.filterIsInstance<JourneySearchHistoryEntry>()
    val trains = entries.filterIsInstance<TrainSearchHistoryEntry>()
    return NativeHistoryState(
        loading = loading,
        journeyHistory = journeys.map { it.nativeHistoryRow(pendingIds) },
        trainHistory = trains.map { it.nativeHistoryRow(pendingIds) },
        observationFailed = observationFailed,
        failedMutation = failedMutation,
    )
}
