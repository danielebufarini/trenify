package it.danielebufarini.trenify.feature.train

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import it.danielebufarini.trenify.core.domain.FavoritesRepository
import it.danielebufarini.trenify.core.model.FavoriteTrain
import it.danielebufarini.trenify.core.model.FavoriteTrainId
import it.danielebufarini.trenify.core.ui.componentScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class TrainFavoriteState(
    val available: Boolean = false,
    val favorite: Boolean = false,
    val pending: Boolean = false,
    val failed: Boolean = false,
)

internal class FavoriteTrainController(
    componentContext: ComponentContext,
    private val repository: FavoritesRepository,
    private val train: () -> FavoriteTrain?,
    dispatcher: CoroutineDispatcher,
) {
    private val scope = componentScope(componentContext.lifecycle, dispatcher)
    private val mutable = MutableValue(TrainFavoriteState())
    private var favoriteIds: Set<FavoriteTrainId> = emptySet()
    private var pendingId: FavoriteTrainId? = null

    val state: Value<TrainFavoriteState> = mutable

    init {
        scope.launch {
            repository.observeFavoriteTrains()
                .catch { mutable.value = mutable.value.copy(failed = true) }
                .collect { trains ->
                    favoriteIds = trains.map(FavoriteTrain::id).toSet()
                    update()
                }
        }
        update()
    }

    fun trainChanged() {
        update(failed = false)
    }

    fun toggle() {
        val selected = train() ?: return
        if (pendingId != null) return
        val favorite = selected.id !in favoriteIds
        pendingId = selected.id
        update(failed = false)
        scope.launch {
            try {
                // Saving or removing a recurring reference never creates a monitor
                // and never stores the dated run's realtime state.
                repository.setFavorite(selected, favorite)
                // No optimistic union here: the observation owns favoriteIds,
                // so a continuation resuming after a completed delete-all can
                // never write a stale id back over the newer empty state.
                update(failed = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                update(failed = true)
            } finally {
                pendingId = null
                update()
            }
        }
    }

    private fun update(failed: Boolean = mutable.value.failed) {
        val selected = train()
        mutable.value = TrainFavoriteState(
            available = selected != null,
            favorite = selected?.id in favoriteIds,
            pending = selected?.id == pendingId,
            failed = failed,
        )
    }
}
