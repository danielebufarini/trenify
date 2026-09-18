package it.danielebufarini.trenify.app

import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.items
import com.arkivanov.decompose.value.Value
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.feature.home.HomeComponent
import it.danielebufarini.trenify.feature.home.HomeState
import it.danielebufarini.trenify.feature.journey.JourneySearchComponent
import it.danielebufarini.trenify.feature.journey.RouteFavoriteState
import it.danielebufarini.trenify.feature.journey.JourneyTabComponent
import it.danielebufarini.trenify.feature.station.StationsTabComponent
import it.danielebufarini.trenify.feature.strikes.AlertsTabComponent
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate

/** Semantic destination identities, independent of platform containers and wording. */
enum class NativeDestination {
    Main, Home, TrainSearch, TrainDetail, StationSearch, StationBoard,
    JourneySearch, JourneyResults, JourneyDetail, History, Monitoring, Saved,
    AlertsOverview, StrikeDetail, Settings,
}

enum class NativePrimaryArea { Search, Monitoring, Saved, Alerts }

/** Identity is stable for this session, including retained children and notification reordering.
 * It is not a second persisted route; Decompose owns serialization and restoration.
 */
data class NativeNavigationEntry(
    val identity: Long,
    val destination: NativeDestination,
    val trainRunId: TrainRunId? = null,
    val stationId: String? = null,
    val strikeId: String? = null,
)

data class NativeNavigationState(
    val entries: List<NativeNavigationEntry>,
    val journeySearch: NativeJourneySearchPresentation? = null,
) {
    val active: NativeNavigationEntry get() = entries.last()
    val canGoBack: Boolean get() = entries.size > 1
}

data class NativeRootState(
    val navigation: NativeNavigationState,
    val main: NativeMainPresentation?,
)

data class NativeMainEntry(val identity: Long, val destination: MainTab, val available: Boolean)

data class NativeMainState(
    val selectedDestination: MainTab,
    val primaryArea: NativePrimaryArea?,
    val entries: List<NativeMainEntry>,
    val home: NativeHomePresentation?,
    val journey: NativeNavigationPresentation?,
    val stations: NativeNavigationPresentation?,
    val alerts: NativeNavigationPresentation?,
)

/** Typed actions over one existing Home, with all semantics in that component. */
class NativeHomePresentation internal constructor(
    private val component: HomeComponent,
    private val owner: NativeProjectionOwner,
) {
    val state: StateFlow<HomeState> = owner.project(component.state) { it }
    fun openJourneySearch() = act(component::openJourneySearch)
    fun openTrainSearch() = act(component::openTrainSearch)
    fun openStations() = act(component::openStations)
    fun openMonitoring() = act(component::openMonitoring)
    fun openSaved() = act(component::openFavorites)
    fun openAlerts() = act(component::openAlerts)
    fun openHistory() = act(component::openHistory)
    fun openStation(station: Station) = act { component.openStation(station) }
    fun openRoute(route: FavoriteRoute) = act { component.openRoute(route) }
    fun openTrain(train: FavoriteTrain) = act { component.openTrain(train) }
    fun openMonitor(trainRunId: TrainRunId) = act { component.openMonitor(trainRunId) }
    fun openRecent(entry: SearchHistoryEntry) = act { component.openRecent(entry) }
    fun refreshStrikes() = act(component::refreshStrikes)
    fun retry() = act(component::retry)
    private fun act(action: () -> Unit) { if (owner.job.isActive) action() }
}

/** Typed input truth. Legacy locale/editor buffers stay inside the existing component. */
data class NativeJourneyInputState(
    val originText: String,
    val destinationText: String,
    val origin: Station?,
    val destination: Station?,
    val suggestions: List<Station>,
    val editingOrigin: Boolean,
    val date: LocalDate,
    val timeHour: Int,
    val timeMinute: Int,
    val mode: JourneySearchMode,
    val loading: Boolean,
    val failure: it.danielebufarini.trenify.core.domain.DomainFailure?,
    val invalid: Boolean,
)

class NativeJourneySearchPresentation internal constructor(
    private val component: JourneySearchComponent,
    private val owner: NativeProjectionOwner,
) {
    val state: StateFlow<NativeJourneyInputState> = owner.project(component.state) {
        NativeJourneyInputState(it.originText, it.destinationText, it.origin, it.destination,
            it.suggestions, it.editingOrigin, it.date, it.timeHour, it.timeMinute, it.mode,
            it.loading, it.failure, it.invalid)
    }
    val favoriteRoute: StateFlow<RouteFavoriteState>? = component.favoriteRouteState?.let { value ->
        owner.project(value) { it }
    }
    fun stationText(origin: Boolean, text: String) = act { component.stationText(origin, text) }
    fun selectStation(station: Station) = act { component.select(station) }
    fun swap() = act(component::swap)
    fun setDate(date: LocalDate) = act { component.date(date.toString()) }
    fun setTime(hour: Int, minute: Int) = act { component.time("${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}") }
    fun setMode(mode: JourneySearchMode) = act { component.mode(mode) }
    fun search() = act(component::search)
    fun toggleFavoriteRoute() = act(component::toggleFavoriteRoute)
    fun openHistory() = act(component::history)
    private fun act(action: () -> Unit) { if (owner.job.isActive) action() }
}

/** Reusable typed subnavigation surface; no Value or configuration callbacks cross into Swift. */
class NativeNavigationPresentation internal constructor(
    val state: StateFlow<NativeNavigationState>,
    private val owner: NativeProjectionOwner,
    private val onBack: () -> Unit,
) {
    fun back() { if (owner.job.isActive) onBack() }
}

class NativeMainPresentation internal constructor(
    private val component: MainComponent,
    private val owner: NativeProjectionOwner,
    private val identities: NativeIdentityAllocator,
) {
    private val pageIdentities = NativeChildIdentities(identities)
    private val children = mutableListOf<OwnedNativeChild>()
    val state: StateFlow<NativeMainState> = owner.project(component.pages) { pages ->
        val instances = pages.items.mapNotNull { it.instance }
        pageIdentities.retain(pages.items.map { it.instance ?: it.configuration })
        children.filter { owned -> instances.none { it === owned.component } }.toList().forEach {
            it.owner.close()
            children.remove(it)
        }
        val home = instances.filterIsInstance<MainComponent.Child.Home>().firstOrNull()?.let { child ->
            facade(child) { branch -> NativeHomePresentation(child.component, branch) } as NativeHomePresentation
        }
        val journey = instances.filterIsInstance<MainComponent.Child.Journey>().firstOrNull()?.let { child ->
            facade(child) { branch -> navigation(branch, child.component.stack, child.component::back) {
                when (it) {
                    is JourneyTabComponent.Child.Search -> NativeDestination.JourneySearch
                    is JourneyTabComponent.Child.Results -> NativeDestination.JourneyResults
                    is JourneyTabComponent.Child.Detail -> NativeDestination.JourneyDetail
                    is JourneyTabComponent.Child.History -> NativeDestination.History
                }
            } } as NativeNavigationPresentation
        }
        val stations = instances.filterIsInstance<MainComponent.Child.Stations>().firstOrNull()?.let { child ->
            facade(child) { branch -> navigation(branch, child.component.stack, child.component::back) {
                when (it) {
                    is StationsTabComponent.Child.Search -> NativeDestination.StationSearch
                    is StationsTabComponent.Child.Board -> NativeDestination.StationBoard
                }
            } } as NativeNavigationPresentation
        }
        val alerts = instances.filterIsInstance<MainComponent.Child.Alerts>().firstOrNull()?.let { child ->
            facade(child) { branch -> navigation(branch, child.component.stack, child.component::back) {
                when (it) {
                    AlertsTabComponent.Child.Overview -> NativeDestination.AlertsOverview
                    is AlertsTabComponent.Child.Detail -> NativeDestination.StrikeDetail
                }
            } } as NativeNavigationPresentation
        }
        val selected = pages.items[pages.selectedIndex].configuration
        NativeMainState(selected, selected.primaryArea(), pages.items.map {
            NativeMainEntry(pageIdentities.of(it.instance ?: it.configuration), it.configuration, it.instance != null)
        }, home, journey, stations, alerts)
    }

    fun select(area: NativePrimaryArea) = selectDestination(when (area) {
        NativePrimaryArea.Search -> MainTab.Home
        NativePrimaryArea.Monitoring -> MainTab.Monitoring
        NativePrimaryArea.Saved -> MainTab.Favorites
        NativePrimaryArea.Alerts -> MainTab.Alerts
    })
    fun selectDestination(destination: MainTab) { if (owner.job.isActive) component.select(destination) }
    fun openHistory() { if (owner.job.isActive) component.openHistory() }
    fun openJourneySearch(intent: JourneySearchIntent) { if (owner.job.isActive) component.openJourneySearch(intent) }
    fun openJourneyRepeat(request: JourneySearchRequest) { if (owner.job.isActive) component.openJourneyRepeat(request) }

    private fun facade(component: Any, create: (NativeProjectionOwner) -> Any): Any {
        children.firstOrNull { it.component === component }?.let { return it.facade }
        val branch = owner.child()
        return create(branch).also { children += OwnedNativeChild(component, branch, it) }
    }

    private fun <C : Any, T : Any> navigation(
        branch: NativeProjectionOwner,
        stack: Value<ChildStack<C, T>>,
        back: () -> Unit,
        destination: (T) -> NativeDestination,
    ): NativeNavigationPresentation {
        val childIdentities = NativeChildIdentities(identities)
        var search: OwnedNativeChild? = null
        return NativeNavigationPresentation(branch.project(stack) { value ->
            childIdentities.retain(value.items.map { it.instance })
            val searchComponent = (value.active.instance as? JourneyTabComponent.Child.Search)?.component
            if (search?.component !== searchComponent) {
                search?.owner?.close()
                search = searchComponent?.let { component ->
                    val searchOwner = branch.child()
                    OwnedNativeChild(component, searchOwner, NativeJourneySearchPresentation(component, searchOwner))
                }
            }
            NativeNavigationState(value.items.map { child ->
                NativeNavigationEntry(childIdentities.of(child.instance), destination(child.instance),
                    stationId = (child.instance as? StationsTabComponent.Child.Board)?.component?.stationId?.value,
                    strikeId = (child.instance as? AlertsTabComponent.Child.Detail)?.strikeId?.value)
            }, search?.facade as? NativeJourneySearchPresentation)
        }, branch, back)
    }
}

private fun MainTab.primaryArea(): NativePrimaryArea? = when (this) {
    MainTab.Home, MainTab.Journey, MainTab.Stations -> NativePrimaryArea.Search
    MainTab.Monitoring -> NativePrimaryArea.Monitoring
    MainTab.Favorites -> NativePrimaryArea.Saved
    MainTab.Alerts -> NativePrimaryArea.Alerts
    MainTab.Settings -> null
}

internal class NativeRootPresentation(
    root: RootComponent,
    owner: NativeProjectionOwner,
) {
    private val identities = NativeIdentityAllocator()
    private val rootIdentities = NativeChildIdentities(identities)
    private val mains = mutableListOf<OwnedNativeChild>()
    val state: StateFlow<NativeRootState> = owner.project(root.stack) { stack ->
        rootIdentities.retain(stack.items.map { it.instance })
        val mainChildren = stack.items.mapNotNull { it.instance as? RootComponent.Child.Main }
        mains.filter { owned -> mainChildren.none { it === owned.component } }.toList().forEach {
            it.owner.close()
            mains.remove(it)
        }
        val main = mainChildren.firstOrNull()?.let { child ->
            val existing = mains.firstOrNull { it.component === child }
            if (existing != null) existing.facade as NativeMainPresentation
            else {
                val branch = owner.child()
                NativeMainPresentation(child.component, branch, identities).also {
                    mains += OwnedNativeChild(child, branch, it)
                }
            }
        }
        NativeRootState(NativeNavigationState(stack.items.map { child ->
            when (val instance = child.instance) {
                is RootComponent.Child.Main -> NativeNavigationEntry(rootIdentities.of(child.instance), NativeDestination.Main)
                is RootComponent.Child.Search -> NativeNavigationEntry(rootIdentities.of(child.instance), NativeDestination.TrainSearch)
                is RootComponent.Child.Detail -> NativeNavigationEntry(rootIdentities.of(child.instance), NativeDestination.TrainDetail,
                    trainRunId = instance.component.id)
                is RootComponent.Child.StationBoard -> NativeNavigationEntry(rootIdentities.of(child.instance), NativeDestination.StationBoard,
                    stationId = instance.component.stationId.value)
            }
        }), main)
    }
}

/** Tokens identify live component instances, including reorder, rather than historical routes.
 * Removed instances are forgotten; reconstructing the same route allocates a new token.
 */
internal class NativeIdentityAllocator {
    private var next = 0L
    fun allocate(): Long = ++next
}

private class NativeChildIdentities(private val allocator: NativeIdentityAllocator) {
    private val values = mutableListOf<Pair<Any, Long>>()
    fun retain(instances: List<Any>) { values.removeAll { entry -> instances.none { it === entry.first } } }
    fun of(instance: Any): Long = values.firstOrNull { it.first === instance }?.second
        ?: allocator.allocate().also { values += instance to it }
}

private class OwnedNativeChild(val component: Any, val owner: NativeProjectionOwner, val facade: Any)
