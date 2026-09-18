package it.danielebufarini.trenify.feature.journey

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import it.danielebufarini.trenify.core.domain.HistoryRepository
import it.danielebufarini.trenify.core.model.SearchHistoryEntry
import it.danielebufarini.trenify.core.model.SearchHistoryEntryId
import it.danielebufarini.trenify.core.model.TrainSearchHistoryEntry
import it.danielebufarini.trenify.core.ui.componentScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class SearchHistoryState(
    val entries: List<SearchHistoryEntry> = emptyList(),
    val loading: Boolean = true,
    val pendingIds: Set<SearchHistoryEntryId> = emptySet(),
    val failedMutation: Boolean = false,
    val observationFailed: Boolean = false,
)

/**
 * Durable search history owned by the journey feature.
 *
 * Repeat never executes a search itself: journey entries are emitted through
 * [onRepeat] and train entries through [onTrainRepeat] so the caller can
 * populate the existing search input for explicit execution, keeping the
 * original requested criteria visible and editable. The component never
 * depends on the train feature: train repeats travel as shared model entries
 * and the application layer routes them. Remove-one and clear-all act through
 * the repository and update list state immediately after persistence without
 * any network involved.
 */
class SearchHistoryComponent(
    componentContext: ComponentContext,
    private val history: HistoryRepository,
    private val onRepeat: (SearchHistoryEntry) -> Unit = {},
    private val onTrainRepeat: (TrainSearchHistoryEntry) -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ComponentContext by componentContext {
    private val scope = componentScope(lifecycle, dispatcher)
    private val mutable = MutableValue(SearchHistoryState())
    private var observation: Job? = null

    val state: Value<SearchHistoryState> = mutable

    init {
        observe()
    }

    fun repeat(entry: SearchHistoryEntry) {
        if (entry.id in mutable.value.pendingIds) return
        (entry as? TrainSearchHistoryEntry)?.let(onTrainRepeat) ?: onRepeat(entry)
    }

    fun remove(entry: SearchHistoryEntry) {
        val id = entry.id
        if (id in mutable.value.pendingIds) return
        mutable.value = mutable.value.copy(pendingIds = mutable.value.pendingIds + id, failedMutation = false)
        scope.launch {
            try {
                history.removeSearch(id)
                mutable.value = mutable.value.copy(entries = mutable.value.entries.filterNot { it.id == id })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutable.value = mutable.value.copy(failedMutation = true)
            } finally {
                mutable.value = mutable.value.copy(pendingIds = mutable.value.pendingIds - id)
            }
        }
    }

    fun clear() {
        if (mutable.value.entries.isEmpty()) return
        scope.launch {
            try {
                history.clearSearchHistory()
                mutable.value = mutable.value.copy(entries = emptyList(), failedMutation = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutable.value = mutable.value.copy(failedMutation = true)
            }
        }
    }

    fun retry() {
        mutable.value = mutable.value.copy(failedMutation = false)
        observe()
    }

    private fun observe() {
        if (observation?.isActive == true) return
        mutable.value = mutable.value.copy(loading = true, observationFailed = false)
        observation = scope.launch {
            history.observeSearchHistory()
                .catch { mutable.value = mutable.value.copy(loading = false, observationFailed = true) }
                .collect { entries ->
                    mutable.value = mutable.value.copy(entries = entries, loading = false, observationFailed = false)
                }
        }
    }
}
