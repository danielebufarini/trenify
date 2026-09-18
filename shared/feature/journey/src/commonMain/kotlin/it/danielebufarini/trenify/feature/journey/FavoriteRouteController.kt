package it.danielebufarini.trenify.feature.journey

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import it.danielebufarini.trenify.core.domain.FavoritesRepository
import it.danielebufarini.trenify.core.model.FavoriteRoute
import it.danielebufarini.trenify.core.model.FavoriteRouteId
import it.danielebufarini.trenify.core.model.Station
import it.danielebufarini.trenify.core.ui.componentScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class RouteFavoriteState(
    val available: Boolean = false,
    val favorite: Boolean = false,
    val pending: Boolean = false,
    val failed: Boolean = false,
)

internal fun favoriteRoute(origin: Station?, destination: Station?): FavoriteRoute? =
    if (origin != null && destination != null && origin.id != destination.id &&
        origin.normalizedName != destination.normalizedName) {
        FavoriteRoute.create(origin, destination)
    } else {
        null
    }

internal class FavoriteRouteController(
    componentContext: ComponentContext,
    private val repository: FavoritesRepository,
    private val route: () -> FavoriteRoute?,
    dispatcher: CoroutineDispatcher,
) {
    private val scope = componentScope(componentContext.lifecycle, dispatcher)
    private val mutable = MutableValue(RouteFavoriteState())
    private var favoriteIds: Set<FavoriteRouteId> = emptySet()
    private var pendingId: FavoriteRouteId? = null

    val state: Value<RouteFavoriteState> = mutable

    init {
        scope.launch {
            repository.observeFavoriteRoutes()
                .catch { mutable.value = mutable.value.copy(failed = true) }
                .collect { routes ->
                    favoriteIds = routes.map(FavoriteRoute::id).toSet()
                    update()
                }
        }
        update()
    }

    fun routeChanged() {
        update(failed = false)
    }

    fun toggle() {
        val selected = route() ?: return
        if (pendingId != null) return
        val favorite = selected.id !in favoriteIds
        pendingId = selected.id
        update(failed = false)
        scope.launch {
            try {
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
        val selected = route()
        mutable.value = RouteFavoriteState(
            available = selected != null,
            favorite = selected?.id in favoriteIds,
            pending = selected?.id == pendingId,
            failed = failed,
        )
    }
}
