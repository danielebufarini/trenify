package it.danielebufarini.trenify.feature.journey

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.*
import com.arkivanov.decompose.value.Value
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.network.RequestInstrumentation
import it.danielebufarini.trenify.core.model.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface JourneyTabComponent {
    val stack: Value<ChildStack<*, Child>>
    fun back()
    fun openSearch(intent: JourneySearchIntent)
    /**
     * History repeat from outside the tab: replaces the search form with the
     * full recorded request for explicit execution, like an in-tab repeat.
     */
    fun openSearch(request: JourneySearchRequest)
    sealed interface Child {
        data class Search(val component: JourneySearchComponent) : Child
        data class Results(val component: JourneyResultsComponent) : Child
        data class Detail(val component: JourneyDetailComponent) : Child
        data class History(val component: SearchHistoryComponent) : Child
    }
}

class DefaultJourneyTabComponent(
    componentContext: ComponentContext,
    repository: JourneyRepository? = null,
    trains: TrainRepository? = null,
    onTrain: (TrainRunId) -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    canBook: ((Journey) -> Boolean)? = null,
    openBooking: (suspend (Journey) -> BookingHandoffResult)? = null,
    favoritesRepository: FavoritesRepository? = null,
    historyRepository: HistoryRepository? = null,
    private val strikes: LoadStrikes? = null,
    /**
     * Train-history repeats leave the journey tab: the application layer
     * routes the shared entry to the existing train-search flow. The journey
     * feature never imports the train feature for this.
     */
    private val onTrainRepeat: (TrainSearchHistoryEntry) -> Unit = {},
    private val instrumentation: RequestInstrumentation = RequestInstrumentation.None,
) : JourneyTabComponent, ComponentContext by componentContext {
    private val navigation = StackNavigation<Route>()
    /**
     * Monotonic duplicate-push discriminator (T7.13-A), restored safely
     * (corrective pass): the next allocation must exceed every generation
     * already present in the restored stack, otherwise reopening History
     * after a restore would recreate `History(1)` and `pushNew` would
     * throw. The counter is persisted through the state keeper and
     * additionally derived from the restored configurations, so states
     * saved by builds without the persisted counter stay safe too.
     */
    private var navigationGeneration: Long =
        stateKeeper.consume(KEY_NAVIGATION_GENERATION, SavedNavigationGeneration.serializer())?.next ?: 0L

    override val stack: Value<ChildStack<*, JourneyTabComponent.Child>> = childStack(
        source = navigation,
        // Every configuration is serializable minimal criteria: endpoint
        // identities, request instants and the journey lookup key. Full
        // Journey snapshots never enter the saved state; restored details
        // re-resolve through the repository.
        serializer = Route.serializer(),
        initialConfiguration = Route.Search(),
        handleBackButton = true,
        childFactory = { route, context -> when (route) {
            is Route.Search -> JourneyTabComponent.Child.Search(JourneySearchComponent(
                context,
                repository,
                { request ->
                    val serial = request.serialized()
                    navigation.pushNew(Route.Results(serial))
                },
                dispatcher,
                favoritesRepository = favoritesRepository,
                initialIntent = route.intent?.intent(),
                historyRepository = historyRepository,
                initialRequest = route.prefill?.request(),
                // Stack configurations must be unique: every push carries a
                // fresh generation so history can be reopened after a repeat.
                onHistory = { historyRepository?.let {
                    navigationGeneration += 1
                    navigation.pushNew(Route.History(navigationGeneration))
                } },
                instrumentation = instrumentation,
            ))
            is Route.Results -> {
                val request = route.request.request()
                if (request == null) {
                    JourneyTabComponent.Child.Search(JourneySearchComponent(
                        context, repository, { serial ->
                            navigation.pushNew(Route.Results(serial.serialized()))
                        }, dispatcher, favoritesRepository = favoritesRepository,
                        historyRepository = historyRepository,
                        onHistory = { historyRepository?.let {
                            navigationGeneration += 1
                            navigation.pushNew(Route.History(navigationGeneration))
                        } },
                        instrumentation = instrumentation,
                    ))
                } else {
                    JourneyTabComponent.Child.Results(JourneyResultsComponent(context, request,
                        repository?.let(::SearchJourneys), { journey ->
                            navigation.pushNew(Route.Detail(journey.lookupKey(request)))
                        }, dispatcher, strikes, instrumentation = instrumentation))
                }
            }
            is Route.Detail -> JourneyTabComponent.Child.Detail(JourneyDetailComponent.restored(
                context, route.key,
                search = repository?.let(::SearchJourneys),
                correlate = trains?.let(::CorrelateJourneyLeg),
                onTrain = onTrain, dispatcher = dispatcher, canBook = canBook,
                openBooking = openBooking, favoritesRepository = favoritesRepository,
                strikes = strikes, trains = trains, instrumentation = instrumentation))
            is Route.History -> JourneyTabComponent.Child.History(SearchHistoryComponent(
                context,
                requireNotNull(historyRepository) { "Search history requires a HistoryRepository" },
                onRepeat = { entry ->
                    // Repeat populates a fresh search with the recorded
                    // criteria for explicit execution; it never reuses a
                    // cached result and never shifts an old travel date.
                    // Train entries never reach this callback: the component
                    // dispatches them to onTrainRepeat instead.
                    (entry as? JourneySearchHistoryEntry)?.let { journey ->
                        navigationGeneration += 1
                        navigation.pushNew(Route.Search(prefill = journey.request().serialized(), generation = navigationGeneration))
                    }
                },
                onTrainRepeat = onTrainRepeat,
                dispatcher,
            ))
        } },
    )
    init {
        stateKeeper.register(KEY_NAVIGATION_GENERATION, SavedNavigationGeneration.serializer()) {
            SavedNavigationGeneration(next = navigationGeneration)
        }
        val restoredMax = stack.value.items.mapNotNull {
            when (val route = it.configuration) {
                is Route.Search -> route.generation
                is Route.History -> route.generation
                else -> null
            }
        }.maxOrNull() ?: 0L
        if (restoredMax > navigationGeneration) navigationGeneration = restoredMax
    }

    override fun back() { navigation.pop() }
    override fun openSearch(intent: JourneySearchIntent) {
        navigationGeneration += 1
        navigation.replaceAll(Route.Search(intent = intent.serialized(), generation = navigationGeneration))
    }
    override fun openSearch(request: JourneySearchRequest) {
        navigationGeneration += 1
        navigation.replaceAll(Route.Search(prefill = request.serialized(), generation = navigationGeneration))
    }

    /**
     * Durable journey-tab route contract (T7.13-A): serializable minimal
     * IDs/criteria only. [Detail] carries the normalized [JourneyLookupKey]
     * for repository re-resolution, never a full [Journey] snapshot.
     */
    @Serializable
    sealed interface Route {
        @Serializable
        @SerialName("search")
        data class Search(
            val intent: SerializableJourneyIntent? = null,
            val prefill: SerializableJourneyRequest? = null,
            val generation: Long = 0L,
        ) : Route

        @Serializable
        @SerialName("results")
        data class Results(val request: SerializableJourneyRequest) : Route

        @Serializable
        @SerialName("detail")
        data class Detail(val key: JourneyLookupKey) : Route

        @Serializable
        @SerialName("history")
        data class History(val generation: Long) : Route
    }
}

/** Serializable endpoint-only journey intent (favorite-route semantics). */
@Serializable
data class SerializableJourneyIntent(
    val origin: SerializableStationRef,
    val destination: SerializableStationRef,
) {
    fun intent(): JourneySearchIntent? {
        val from = origin.station() ?: return null
        val to = destination.station() ?: return null
        if (from.id == to.id) return null
        return JourneySearchIntent(from, to)
    }
}

fun JourneySearchIntent.serialized(): SerializableJourneyIntent =
    SerializableJourneyIntent(origin.serialized(), destination.serialized())

private const val KEY_NAVIGATION_GENERATION = "journey-navigation-generation"

/** Persisted duplicate-push discriminator for journey search/history routes (T7.13-A corrective pass). */
@Serializable
private data class SavedNavigationGeneration(val next: Long = 0L)
