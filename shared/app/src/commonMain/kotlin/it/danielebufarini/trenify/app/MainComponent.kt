package it.danielebufarini.trenify.app

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.pages.ChildPages
import com.arkivanov.decompose.router.pages.Pages
import com.arkivanov.decompose.router.pages.PagesNavigation
import com.arkivanov.decompose.router.pages.childPages
import com.arkivanov.decompose.router.pages.select
import com.arkivanov.decompose.value.Value
import it.danielebufarini.trenify.feature.home.HomeComponent
import it.danielebufarini.trenify.feature.journey.JourneyTabComponent
import it.danielebufarini.trenify.feature.favorites.FavoritesComponent
import it.danielebufarini.trenify.feature.monitoring.MonitoringTabComponent
import it.danielebufarini.trenify.feature.settings.SettingsComponent
import it.danielebufarini.trenify.feature.station.StationsTabComponent
import it.danielebufarini.trenify.feature.strikes.AlertsTabComponent
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.model.TrainRunId
import it.danielebufarini.trenify.core.model.Station
import it.danielebufarini.trenify.core.model.JourneySearchIntent
import it.danielebufarini.trenify.core.model.JourneySearchRequest
import it.danielebufarini.trenify.core.model.StrikeId
import it.danielebufarini.trenify.core.model.TrainLookupIntent
import it.danielebufarini.trenify.core.model.TrainSearchHistoryEntry
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable
enum class MainTab {
    Home,
    Journey,
    Stations,
    Favorites,
    Monitoring,
    Alerts,
    Settings,
}

@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
interface MainComponent {
    val pages: Value<ChildPages<MainTab, Child>>
    /** Secondary Settings returns to the same shared destination; never a fifth primary area. */
    val settingsSource: MainTab get() = MainTab.Home

    fun select(tab: MainTab)
    fun openJourneySearch(intent: JourneySearchIntent)
    /**
     * Routes a journey-history repeat to the existing journey-search flow
     * with the full recorded request for explicit execution.
     */
    fun openJourneyRepeat(request: JourneySearchRequest)
    /**
     * Routes a train-history repeat to the application train-search flow
     * with the recorded criteria for explicit execution.
     */
    fun openTrainSearch(number: TrainNumber, serviceDate: LocalDate?, expected: TrainLookupIntent?)
    /**
     * Opens the shared search-history screen on the Journey tab when it is
     * showing its search form; a no-op otherwise.
     */
    fun openHistory()

    /**
     * Shared strike routing (T7.13-E): selects the Alerts destination and
     * opens its strike detail through the shared tree. Returns true when a
     * navigation was performed; returns false (no duplicate stack entry)
     * when the requested strike detail is already the visible destination.
     * Unknown or deleted strikes still open the detail, which exposes the
     * controlled not-found path — a missing target is never replaced by a
     * similar-looking strike.
     */
    fun routeStrikeIfNew(strikeId: StrikeId): Boolean

    sealed interface Child {
        data class Home(val component: HomeComponent) : Child
        data class Journey(val component: JourneyTabComponent) : Child
        data class Stations(val component: StationsTabComponent) : Child
        data class Favorites(val component: FavoritesComponent) : Child
        data class Monitoring(val component: MonitoringTabComponent) : Child
        data class Alerts(val component: AlertsTabComponent) : Child
        data class Settings(val component: SettingsComponent) : Child
    }
}

@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
class DefaultMainComponent(
    componentContext: ComponentContext,
    private val componentFactory: AppComponentFactory,
    private val onTrainSearch: () -> Unit = {},
    private val onTrain: (TrainRunId) -> Unit = {},
    private val onStation: (Station) -> Unit = {},
    private val onTrainLookup: (TrainLookupIntent) -> Unit = {},
    private val onTrainPrefill: (TrainNumber, LocalDate?, TrainLookupIntent?) -> Unit = { _, _, _ -> },
) : MainComponent, ComponentContext by componentContext {
    private val navigation = PagesNavigation<MainTab>()
    override var settingsSource: MainTab =
        stateKeeper.consume("settings-source", MainTab.serializer())?.takeUnless { it == MainTab.Settings }
            ?: MainTab.Home
        private set

    init {
        stateKeeper.register("settings-source", MainTab.serializer()) { settingsSource }
    }

    override val pages: Value<ChildPages<MainTab, MainComponent.Child>> = childPages(
        source = navigation,
        // The selected main destination restores; every tab keeps its own
        // independent child state through its own serializer below.
        serializer = MainTab.serializer(),
        initialPages = {
            Pages(
                items = MainTab.entries,
                selectedIndex = MainTab.Journey.ordinal,
            )
        },
        childFactory = ::createChild,
    )

    override fun select(tab: MainTab) {
        if (tab == MainTab.Settings) {
            val current = pages.value.items[pages.value.selectedIndex].configuration
            if (current != MainTab.Settings) settingsSource = current
        }
        navigation.select(tab.ordinal)
    }

    override fun openJourneySearch(intent: JourneySearchIntent) {
        navigation.select(MainTab.Journey.ordinal)
        val journey = (pages.value.items[MainTab.Journey.ordinal].instance as? MainComponent.Child.Journey)?.component
            ?: return
        journey.openSearch(intent)
    }

    override fun openJourneyRepeat(request: JourneySearchRequest) {
        navigation.select(MainTab.Journey.ordinal)
        val journey = (pages.value.items[MainTab.Journey.ordinal].instance as? MainComponent.Child.Journey)?.component
            ?: return
        journey.openSearch(request)
    }

    override fun openTrainSearch(number: TrainNumber, serviceDate: LocalDate?, expected: TrainLookupIntent?) {
        onTrainPrefill(number, serviceDate, expected)
    }

    override fun openHistory() {
        navigation.select(MainTab.Journey.ordinal)
        val journey = (pages.value.items[MainTab.Journey.ordinal].instance as? MainComponent.Child.Journey)?.component
            ?: return
        (journey.stack.value.active.instance as? JourneyTabComponent.Child.Search)?.component?.history()
    }

    override fun routeStrikeIfNew(strikeId: StrikeId): Boolean {
        navigation.select(MainTab.Alerts.ordinal)
        val alerts = (pages.value.items[MainTab.Alerts.ordinal].instance as? MainComponent.Child.Alerts)?.component
            ?: return false
        return alerts.openStrikeIfNew(strikeId)
    }

    private fun onHistoryTrainRepeat(entry: TrainSearchHistoryEntry) {
        openTrainSearch(entry.number, entry.serviceDate, entry.lookupIntent())
    }

    private fun createChild(
        tab: MainTab,
        componentContext: ComponentContext,
    ): MainComponent.Child = when (tab) {
        MainTab.Home -> MainComponent.Child.Home(
            componentFactory.home(
                componentContext,
                onJourneySearch = { select(MainTab.Journey) },
                onTrainSearch = onTrainSearch,
                onStations = { select(MainTab.Stations) },
                onMonitoring = { select(MainTab.Monitoring) },
                onFavorites = { select(MainTab.Favorites) },
                onAlerts = { select(MainTab.Alerts) },
                onHistory = ::openHistory,
                onStation = onStation,
                onRoute = ::openJourneySearch,
                onTrainLookup = onTrainLookup,
                onTrain = onTrain,
                onTrainRepeat = ::onHistoryTrainRepeat,
                onJourneyRepeat = ::openJourneyRepeat,
            ),
        )
        MainTab.Journey -> MainComponent.Child.Journey(
            componentFactory.journey(componentContext, onTrain, onTrainRepeat = ::onHistoryTrainRepeat),
        )
        MainTab.Stations -> MainComponent.Child.Stations(componentFactory.stations(componentContext, onTrainSearch, onTrain))
        MainTab.Favorites -> MainComponent.Child.Favorites(
            componentFactory.favorites(componentContext, onStation, ::openJourneySearch, onTrainLookup,
                ::openJourneyRepeat, ::onHistoryTrainRepeat),
        )
        MainTab.Monitoring -> MainComponent.Child.Monitoring(componentFactory.monitoring(componentContext, onTrain))
        MainTab.Alerts -> MainComponent.Child.Alerts(componentFactory.alerts(componentContext))
        MainTab.Settings -> MainComponent.Child.Settings(componentFactory.settings(componentContext))
    }
}
