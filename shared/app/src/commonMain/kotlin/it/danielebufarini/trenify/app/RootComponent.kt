package it.danielebufarini.trenify.app

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.*
import com.arkivanov.decompose.value.Value
import it.danielebufarini.trenify.core.model.ExternalStationRef
import it.danielebufarini.trenify.core.model.ProviderId
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.Station
import it.danielebufarini.trenify.core.model.StationId
import it.danielebufarini.trenify.core.model.StrikeId
import it.danielebufarini.trenify.core.model.TrainLookupIntent
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.model.TrainRunId
import it.danielebufarini.trenify.core.platform.NotificationDestination
import it.danielebufarini.trenify.feature.station.StationBoardComponent
import it.danielebufarini.trenify.feature.train.TrainDetailComponent
import it.danielebufarini.trenify.feature.train.TrainSearchComponent
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
interface RootComponent {
    val stack: Value<ChildStack<*, Child>>
    fun back()

    /**
     * One shared notification-routing entry point (T7.13-E): both platforms
     * ultimately invoke this same shared behavior with a validated typed
     * destination. The shared root translates it into navigation; native
     * hosts never push feature screens directly.
     *
     * Train/monitor destinations resolve through the existing shared
     * route/repository path (never number-only: the full normalized run
     * identity is required). Strike destinations select the Alerts tab and
     * open its detail through the shared tree. Invalid targets recover
     * safely through the detail's controlled not-found path; duplicate
     * callbacks for the already visible destination are ignored without
     * suppressing legitimate later navigation to the same target.
     *
     * Stack-existing targets never throw and never duplicate: train routing
     * moves an already-stacked target to the front (pushToFront), so Back
     * from a reordered A -> B -> A tap returns through B into the restored
     * tree. Strike routing applies the same semantic inside the Alerts tab.
     */
    fun onNotificationDestination(destination: NotificationDestination)

    sealed interface Child {
        data class Main(val component: MainComponent) : Child
        data class Search(val component: TrainSearchComponent) : Child
        data class Detail(val component: TrainDetailComponent) : Child
        data class StationBoard(val component: StationBoardComponent) : Child
    }
}

@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
class DefaultRootComponent(
    componentContext: ComponentContext,
    private val componentFactory: AppComponentFactory,
    /**
     * Cold-entry pending destination (T7.13-H): offered by the native host
     * before this tree existed. Restored navigation is applied first (by
     * construction, through the serializers below); the pending destination
     * is then routed on top, so Back from the notification target returns
     * into the restored tree. Consumed exactly once here.
     */
    pendingDestination: NotificationDestination? = null,
) : RootComponent, ComponentContext by componentContext {
    private val navigation = StackNavigation<Route>()
    private var main: MainComponent? = null
    override val stack: Value<ChildStack<*, RootComponent.Child>> = childStack(
        source = navigation,
        serializer = Route.serializer(),
        initialConfiguration = Route.Main,
        handleBackButton = true,
        childFactory = { route, context ->
            when (route) {
                Route.Main -> RootComponent.Child.Main(DefaultMainComponent(context, componentFactory,
                    onTrainSearch = { openSearch() }, onTrain = ::openTrain,
                    onStation = ::openStation, onTrainLookup = ::openTrainLookup,
                    onTrainPrefill = ::openTrainPrefill).also { main = it })
                is Route.Search -> RootComponent.Child.Search(
                    componentFactory.trainSearch(context, ::openTrain, route.number,
                        serviceDate = route.serviceDate(),
                        autoSearch = route.autoSearch, expected = route.expected()),
                )
                is Route.Detail -> {
                    // Malformed persisted routes must recover, never crash:
                    // an undecodable identity falls back to the main screen.
                    val id = route.trainRunId()
                    if (id == null) {
                        RootComponent.Child.Main(DefaultMainComponent(context, componentFactory,
                            onTrainSearch = { openSearch() }, onTrain = ::openTrain,
                            onStation = ::openStation, onTrainLookup = ::openTrainLookup,
                            onTrainPrefill = ::openTrainPrefill).also { main = it })
                    } else {
                        RootComponent.Child.Detail(componentFactory.trainDetail(context, id))
                    }
                }
                is Route.Board -> {
                    // Malformed persisted board routes recover to main, never
                    // crash and never invent a station: only the id travels
                    // in the route, and the live station resolves from the
                    // repository inside the board component.
                    val stationId = route.stationId()
                    if (stationId == null) {
                        RootComponent.Child.Main(DefaultMainComponent(context, componentFactory,
                            onTrainSearch = { openSearch() }, onTrain = ::openTrain,
                            onStation = ::openStation, onTrainLookup = ::openTrainLookup,
                            onTrainPrefill = ::openTrainPrefill).also { main = it })
                    } else {
                        RootComponent.Child.StationBoard(
                            componentFactory.stationBoard(context, stationId, ::openTrain),
                        )
                    }
                }
            }
        },
    )

    /**
     * Monotonic duplicate-push discriminator (T7.13-A), restored safely
     * (corrective pass): the next allocation must exceed every generation
     * already present in the restored stack, otherwise a post-restoration
     * push would recreate an equal configuration and `pushNew` would throw.
     * The counter is persisted through the state keeper and additionally
     * derived from the restored configurations, so states saved by builds
     * without the persisted counter stay safe too.
     */
    private var navigationGeneration: Long =
        stateKeeper.consume(KEY_NAVIGATION_GENERATION, SavedNavigationGeneration.serializer())?.next ?: 0L

    init {
        stateKeeper.register(KEY_NAVIGATION_GENERATION, SavedNavigationGeneration.serializer()) {
            SavedNavigationGeneration(next = navigationGeneration)
        }
        val restoredMax = stack.value.items.mapNotNull {
            (it.configuration as? Route.Search)?.generation
        }.maxOrNull() ?: 0L
        if (restoredMax > navigationGeneration) navigationGeneration = restoredMax
        // Cold entry is routed after restoration, on top of the restored tree.
        if (pendingDestination != null) onNotificationDestination(pendingDestination)
    }
    private fun openSearch() {
        navigationGeneration += 1
        navigation.pushNew(Route.Search(generation = navigationGeneration))
    }
    private fun openTrain(id: TrainRunId) { navigation.pushNew(Route.Detail.of(id)) }
    private fun openStation(station: Station) { navigation.pushNew(Route.Board.of(station)) }
    private fun openTrainLookup(intent: TrainLookupIntent) {
        // Fresh lookup with stable favorite criteria and an explicit current
        // service date; never a stored TrainRunId and never a monitor. The
        // expected discrimination travels with the route so a same-number but
        // incompatible run can never be silently opened.
        navigationGeneration += 1
        navigation.pushNew(Route.Search(number = intent.number.value, autoSearch = true, expected = intent.serialized(),
            generation = navigationGeneration))
    }
    /**
     * History repeat: prefills the existing train-search flow with the
     * recorded number, requested service date and stored discrimination for
     * explicit execution. Never auto-searches and never reopens a dated run;
     * the existing 0/1/N disambiguation applies to the later submission.
     */
    private fun openTrainPrefill(number: TrainNumber, serviceDate: LocalDate?, expected: TrainLookupIntent?) {
        navigationGeneration += 1
        navigation.pushNew(Route.Search(
            number = number.value,
            serviceDate = serviceDate?.toString(),
            autoSearch = false,
            expected = expected?.serialized(),
            generation = navigationGeneration,
        ))
    }

    override fun onNotificationDestination(destination: NotificationDestination) {
        when (destination) {
            is NotificationDestination.Train -> {
                // Never number-only: a destination without the full
                // normalized identity is ignored rather than guessed.
                val id = destination.trainRunId() ?: return
                val active = stack.value.active.instance
                // Duplicate OS callbacks for the already visible destination
                // are ignored; navigating away and tapping again still routes
                // because the top no longer matches.
                if (active is RootComponent.Child.Detail && active.component.id == id) return
                // pushToFront (never pushNew): the target may already sit
                // below another destination (A -> B -> A), and pushNew
                // throws on an equal back-stack configuration. The existing
                // entry moves to the front instead of duplicating, so Back
                // from the reordered target returns through the remaining
                // stack ([Main, A, B] + tap A -> [Main, B, A]; Back -> B).
                navigation.pushToFront(Route.Detail.of(id))
            }
            is NotificationDestination.Strike -> {
                val strikeId = runCatching { StrikeId(destination.strikeId) }.getOrNull() ?: return
                val current = main
                if (stack.value.active.instance !is RootComponent.Child.Main) {
                    // Return to the shared Main tree (preserving every tab's
                    // independent stack) and route the strike through it.
                    // No second navigation tree is ever created here.
                    navigation.popTo(index = 0)
                }
                // Duplicate callbacks for the already visible strike detail
                // are ignored inside routeStrikeIfNew; legitimate later
                // navigation still routes because the top no longer matches.
                current?.routeStrikeIfNew(strikeId)
            }
        }
    }

    override fun back() { navigation.pop() }

    /**
     * Durable root route contract (T7.13-A): every configuration is
     * serializable and carries minimal provider-neutral routing
     * information only — string identity parts and search criteria, never
     * full domain objects, snapshots, repository flows or provider wire
     * payloads.
     *
     * - `Detail` carries the normalized [TrainRunId] parts required by the
     *   repository path. The provider part is the normalized identity scope,
     *   not a wire DTO: without it the repository cannot select the adapter
     *   and number-only resolution would risk opening the wrong service.
     * - `StationBoard` carries only the stable internal [StationId]
     *   value. The display name is live domain state and is never
     *   persisted: the board resolves the current canonical [Station] from
     *   the repository by id, showing a controlled unavailable state when
     *   the station is gone.
     * - `Search` carries the train number, explicit service date and the
     *   stable favorite discrimination. [generation] keeps repeated pushes
     *   of identical criteria deterministic for Decompose's duplicate
     *   configuration handling.
     */
    @Serializable
    internal sealed interface Route {
        @Serializable
        @SerialName("main")
        data object Main : Route

        @Serializable
        @SerialName("search")
        data class Search(
            val number: String = "",
            /** ISO-8601 service date, null when the current Rome date applies. */
            val serviceDate: String? = null,
            val autoSearch: Boolean = false,
            val expected: SerializedTrainLookup? = null,
            val generation: Long = 0L,
        ) : Route {
            fun serviceDate(): LocalDate? =
                serviceDate?.let { iso -> runCatching { LocalDate.parse(iso) }.getOrNull() }

            fun expected(): TrainLookupIntent? = expected?.intent()
        }

        @Serializable
        @SerialName("detail")
        data class Detail(
            val provider: String,
            val number: String,
            val origin: String,
            /** ISO-8601 Europe/Rome service date. */
            val serviceDate: String,
        ) : Route {
            fun trainRunId(): TrainRunId? {
                if (provider.isBlank() || origin.isBlank()) return null
                if (number.isBlank() || !number.all(Char::isDigit)) return null
                val date = runCatching { LocalDate.parse(serviceDate) }.getOrNull() ?: return null
                return runCatching {
                    TrainRunId(ProviderId(provider), TrainNumber(number), ExternalStationRef(origin), date)
                }.getOrNull()
            }

            companion object {
                fun of(id: TrainRunId): Detail = Detail(
                    provider = id.provider.value,
                    number = id.number.value,
                    origin = id.origin.value,
                    serviceDate = id.serviceDate.toString(),
                )
            }
        }

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

/**
 * Serializable surrogate for [TrainLookupIntent]: the stable favorite
 * discrimination (number plus origin/operator signals) without any run
 * identity, service date or realtime state.
 */
@Serializable
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
data class SerializedTrainLookup(
    val number: String,
    val originId: String? = null,
    val originName: String? = null,
    val operator: String? = null,
) {
    fun intent(): TrainLookupIntent? {
        if (number.isBlank() || !number.all(Char::isDigit)) return null
        return TrainLookupIntent(
            number = TrainNumber(number),
            originId = originId?.takeIf { it.isNotBlank() }?.let(::StationId),
            originName = originName?.takeIf { it.isNotBlank() },
            operator = operator?.takeIf { it.isNotBlank() }?.let(::Operator),
        )
    }
}

fun TrainLookupIntent.serialized(): SerializedTrainLookup = SerializedTrainLookup(
    number = number.value,
    originId = originId?.value,
    originName = originName,
    operator = operator?.name,
)

private const val KEY_NAVIGATION_GENERATION = "root-navigation-generation"

/** Persisted duplicate-push discriminator for root search routes (T7.13-A corrective pass). */
@Serializable
private data class SavedNavigationGeneration(val next: Long = 0L)

fun NotificationDestination.Train.trainRunId(): TrainRunId? {
    if (provider.isBlank() || origin.isBlank()) return null
    if (number.isBlank() || !number.all(Char::isDigit)) return null
    val date = runCatching { LocalDate.parse(serviceDate) }.getOrNull() ?: return null
    return runCatching {
        TrainRunId(ProviderId(provider), TrainNumber(number), ExternalStationRef(origin), date)
    }.getOrNull()
}
