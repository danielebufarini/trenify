package it.danielebufarini.trenify.feature.station

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.*
import com.arkivanov.decompose.value.Value
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.ui.componentScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface StationsTabComponent {
    val stack: Value<ChildStack<*, Child>>
    fun back()
    fun searchTrains()
    sealed interface Child {
        data class Search(val component: StationSearchComponent) : Child
        data class Board(val component: StationBoardComponent) : Child
    }
}

class DefaultStationsTabComponent(
    componentContext: ComponentContext,
    stationRepository: StationRepository,
    trainRepository: TrainRepository,
    historyRepository: HistoryRepository,
    favoritesRepository: FavoritesRepository,
    available: StateFlow<Boolean>,
    private val onTrainSearch: () -> Unit,
    onTrain: (TrainRunId) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : StationsTabComponent, ComponentContext by componentContext {
    private val scope = componentScope(lifecycle, dispatcher)
    private val navigation = StackNavigation<Route>()
    override val stack: Value<ChildStack<*, StationsTabComponent.Child>> = childStack(
        source = navigation,
        // The board route carries only the stable station id, never a
        // display-name snapshot. The live station resolves from the
        // repository inside the board; the board kind is durable component
        // state restored through the state keeper.
        serializer = Route.serializer(),
        initialConfiguration = Route.Search,
        handleBackButton = true,
        childFactory = { route, context ->
            when (route) {
                Route.Search -> StationsTabComponent.Child.Search(StationSearchComponent(
                    context, SearchStations(stationRepository), favoritesRepository, onSelect = { station ->
                        scope.launch {
                            try {
                                historyRepository.record(station)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Throwable) {
                                // Recency is best-effort personal data: a failure
                                // (including a write superseded by a completed
                                // delete-all) must never block opening the board.
                            }
                        }
                        navigation.pushNew(Route.Board.of(station))
                    }, dispatcher,
                    historyRepository = historyRepository,
                ))
                is Route.Board -> {
                    // Malformed persisted board routes recover to search,
                    // never crash and never invent a station: only the id
                    // travels in the route, and the live station resolves
                    // from the repository inside the board component.
                    val stationId = route.stationId()
                    if (stationId == null) {
                        StationsTabComponent.Child.Search(StationSearchComponent(
                            context, SearchStations(stationRepository), favoritesRepository, onSelect = { selected ->
                                scope.launch {
                                    try {
                                        historyRepository.record(selected)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Throwable) {
                                    }
                                }
                                navigation.pushNew(Route.Board.of(selected))
                            }, dispatcher,
                            historyRepository = historyRepository,
                        ))
                    } else {
                        StationsTabComponent.Child.Board(StationBoardComponent(
                            context, stationId, ObserveStation(stationRepository),
                            LoadStationBoard(trainRepository), favoritesRepository,
                            available, onTrain, dispatcher,
                        ))
                    }
                }
            }
        },
    )
    override fun back() { navigation.pop() }
    override fun searchTrains() = onTrainSearch()

    /**
     * Durable stations route contract (T7.13-A): serializable station
     * identity only.
     */
    @Serializable
    sealed interface Route {
        @Serializable
        @SerialName("search")
        data object Search : Route

        @Serializable
        @SerialName("board")
        data class Board(val stationId: String) : Route {
            fun stationId(): StationId? {
                if (stationId.isBlank()) return null
                return StationId(stationId)
            }

            companion object {
                fun of(station: Station): Board = Board(station.id.value)
            }
        }
    }
}
