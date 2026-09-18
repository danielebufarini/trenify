package it.danielebufarini.trenify.app

import com.arkivanov.decompose.router.stack.items
import com.arkivanov.decompose.value.Value
import it.danielebufarini.trenify.feature.home.HomeComponent
import it.danielebufarini.trenify.feature.journey.JourneyDetailComponent
import it.danielebufarini.trenify.feature.journey.JourneyResultsComponent
import it.danielebufarini.trenify.feature.journey.JourneySearchComponent
import it.danielebufarini.trenify.feature.journey.JourneyTabComponent
import it.danielebufarini.trenify.feature.station.*
import it.danielebufarini.trenify.feature.train.*
import it.danielebufarini.trenify.feature.strikes.AlertsTabComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/** A visual route refers to an existing live child, never a persisted/native route. */
data class NativeShellEntry(
    val identity: Long,
    val destination: NativeDestination,
    val available: Boolean,
    /** Live Results facade for this route only; null on every other destination. */
    val journeyResults: NativeJourneyResultsPresentation? = null,
    /** Live Detail facade for this route only; null on every other destination. */
    val journeyDetail: NativeJourneyDetailPresentation? = null,
    val stationSearch: NativeStationSearchPresentation? = null,
    val stationBoard: NativeStationBoardPresentation? = null,
    val trainSearch: NativeTrainSearchPresentation? = null,
    val trainDetail: NativeTrainDetailPresentation? = null,
    val monitoring: NativeMonitoringPresentation? = null,
    /** Live Saved facade for this route only; null on every other destination. */
    val saved: NativeSavedPresentation? = null,
    /** Live History facade for this route only; null on every other destination. */
    val history: NativeHistoryPresentation? = null,
    /** Live Alerts facade for this route only; null on every other destination. */
    val alerts: NativeAlertsPresentation? = null,
    /** Selected strike identity for StrikeDetail entries; null on every other destination. */
    val alertStrikeId: String? = null,
    /** Live Settings facade for this route only; null on every other destination. */
    val settings: NativeSettingsPresentation? = null,
)

data class NativeShellState(
    val primaryArea: NativePrimaryArea,
    val base: NativeShellEntry,
    val path: List<NativeShellEntry>,
) {
    val active: NativeShellEntry get() = path.lastOrNull() ?: base
    val canGoBack: Boolean get() = path.isNotEmpty()
}

/** Additive T8.4 projection over the SAME Decompose tree. No component/graph construction.
 * Native path writes may only request removal of a matching authoritative prefix.
 */
class NativeShellPresentation internal constructor(
    private val root: RootComponent,
    parent: NativeProjectionOwner,
) {
    private val owner = parent.child()
    private val allocator = NativeIdentityAllocator()
    private val records = mutableListOf<ShellRecord>()
    private val journeyFacades = mutableListOf<NativeFacadeRecord>()
    private val stationTrainFacades = mutableListOf<NativeFacadeRecord>()
    private val watches = mutableListOf<Pair<Value<*>, NativeProjectionOwner>>()
    private var refreshing = false
    private val mutableState = MutableStateFlow(snapshot())
    val state: StateFlow<NativeShellState> = owner.bind(mutableState)

    init {
        refresh()
        owner.job.invokeOnCompletion { records.clear() }
    }

    private fun main(): MainComponent = root.stack.value.items.mapNotNull {
        (it.instance as? RootComponent.Child.Main)?.component
    }.first()

    fun select(area: NativePrimaryArea) {
        if (!owner.job.isActive) return
        returnToMain()
        main().select(area.mainTab())
    }

    fun openSettings() {
        if (!owner.job.isActive) return
        returnToMain()
        main().select(MainTab.Settings)
    }

    fun back() {
        if (!owner.job.isActive) return
        if (root.stack.value.items.size > 1) { root.back(); return }
        val main = main()
        val pages = main.pages.value
        val selected = pages.items[pages.selectedIndex]
        when (val child = selected.instance) {
            is MainComponent.Child.Journey -> if (child.component.stack.value.items.size > 1) child.component.back()
                else main.select(MainTab.Home)
            is MainComponent.Child.Stations -> if (child.component.stack.value.items.size > 1) child.component.back()
                else main.select(MainTab.Home)
            is MainComponent.Child.Alerts -> child.component.back()
            is MainComponent.Child.Settings -> main.select(main.settingsSource)
            else -> Unit
        }
    }

    /** Reject insertions, reorders, stale callbacks and arbitrary local destinations. */
    fun requestPath(area: NativePrimaryArea, identities: List<Long>, expectedIdentities: List<Long>) {
        if (!owner.job.isActive || state.value.primaryArea != area) return
        val current = state.value.path.map { it.identity }
        if (current != expectedIdentities) return
        if (identities.size >= current.size || current.take(identities.size) != identities) return
        repeat(current.size - identities.size) { back() }
    }

    fun close() = owner.close()

    /**
     * Android-owned native Home (T8.5) entry points. Hidden from Swift so the
     * iOS export stays narrow; Swift consumes the same live components through
     * the accepted T8.2 Main/Home/Journey facades. No new navigation state.
     */
    @OptIn(ExperimentalObjCRefinement::class)
    @HiddenFromObjC
    fun homeComponent(): HomeComponent? {
        if (!owner.job.isActive) return null
        val main = root.stack.value.items.mapNotNull {
            (it.instance as? RootComponent.Child.Main)?.component
        }.firstOrNull() ?: return null
        return main.pages.value.items.mapNotNull {
            (it.instance as? MainComponent.Child.Home)?.component
        }.firstOrNull()
    }

    @OptIn(ExperimentalObjCRefinement::class)
    @HiddenFromObjC
    fun journeySearchComponent(): JourneySearchComponent? {
        if (!owner.job.isActive) return null
        val main = root.stack.value.items.mapNotNull {
            (it.instance as? RootComponent.Child.Main)?.component
        }.firstOrNull() ?: return null
        val journey = main.pages.value.items.mapNotNull {
            (it.instance as? MainComponent.Child.Journey)?.component
        }.firstOrNull() ?: return null
        // The composer lives in the native Home base. Search is retained below
        // Results/History, so find it wherever it sits in the shared stack.
        return journey.stack.value.items.mapNotNull {
            (it.instance as? JourneyTabComponent.Child.Search)?.component
        }.firstOrNull()
    }

    /**
     * Android-owned native Journey Results/Detail (T8.6) entry points. Hidden
     * from Swift so the iOS export stays narrow; Swift consumes the same live
     * facades through the shell entries in its own snapshot. No new
     * navigation state.
     */
    @OptIn(ExperimentalObjCRefinement::class)
    @HiddenFromObjC
    fun journeyResults(identity: Long): NativeJourneyResultsPresentation? {
        if (!owner.job.isActive) return null
        return records.firstOrNull { it.entry.identity == identity && it.entry.available }?.entry?.journeyResults
    }

    @OptIn(ExperimentalObjCRefinement::class)
    @HiddenFromObjC
    fun journeyDetail(identity: Long): NativeJourneyDetailPresentation? {
        if (!owner.job.isActive) return null
        return records.firstOrNull { it.entry.identity == identity && it.entry.available }?.entry?.journeyDetail
    }

    private fun returnToMain() {
        while (root.stack.value.items.size > 1) root.back()
    }

    private fun refresh() {
        if (refreshing || !owner.job.isActive) return
        refreshing = true
        try {
            val sources = buildList<Value<*>> {
                add(root.stack)
                add(main().pages)
                main().pages.value.items.forEach {
                    when (val child = it.instance) {
                        is MainComponent.Child.Journey -> add(child.component.stack)
                        is MainComponent.Child.Stations -> add(child.component.stack)
                        is MainComponent.Child.Alerts -> add(child.component.stack)
                        else -> Unit
                    }
                }
            }
            watches.filter { watch -> sources.none { it === watch.first } }.toList().forEach {
                it.second.close(); watches.remove(it)
            }
            sources.filter { source -> watches.none { it.first === source } }.forEach { source ->
                val branch = owner.child()
                watches += source to branch
                @Suppress("UNCHECKED_CAST")
                branch.project(source as Value<Any>) { refresh(); Unit }
            }
            mutableState.value = snapshot()
        } finally { refreshing = false }
    }

    private fun snapshot(): NativeShellState {
        val live = mutableListOf<Any>()
        val liveJourneyComponents = mutableListOf<Any>()
        val liveStationTrainComponents = mutableListOf<Any>()
        fun <T : Any> facade(component: Any, create: (NativeProjectionOwner) -> T): T {
            liveStationTrainComponents += component
            stationTrainFacades.firstOrNull { it.component === component }?.let {
                @Suppress("UNCHECKED_CAST")
                return it.facade as T
            }
            val branch = owner.child()
            return create(branch).also { stationTrainFacades += NativeFacadeRecord(component, branch, it) }
        }
        fun entry(key: Any, destination: NativeDestination, available: Boolean = true,
                  journeyResults: NativeJourneyResultsPresentation? = null,
                  journeyDetail: NativeJourneyDetailPresentation? = null,
                  stationSearch: NativeStationSearchPresentation? = null,
                  stationBoard: NativeStationBoardPresentation? = null,
                  trainSearch: NativeTrainSearchPresentation? = null,
                  trainDetail: NativeTrainDetailPresentation? = null,
                  monitoring: NativeMonitoringPresentation? = null,
                  saved: NativeSavedPresentation? = null,
                  history: NativeHistoryPresentation? = null,
                  alerts: NativeAlertsPresentation? = null,
                  alertStrikeId: String? = null,
                  settings: NativeSettingsPresentation? = null): NativeShellEntry {
            live += key
            val previous = records.firstOrNull { it.key === key }
            val value = NativeShellEntry(previous?.entry?.identity ?: allocator.allocate(), destination,
                available,
                journeyResults = journeyResults ?: previous?.entry?.journeyResults,
                journeyDetail = journeyDetail ?: previous?.entry?.journeyDetail,
                stationSearch = stationSearch ?: previous?.entry?.stationSearch,
                stationBoard = stationBoard ?: previous?.entry?.stationBoard,
                trainSearch = trainSearch ?: previous?.entry?.trainSearch,
                trainDetail = trainDetail ?: previous?.entry?.trainDetail,
                monitoring = monitoring ?: previous?.entry?.monitoring,
                saved = saved ?: previous?.entry?.saved,
                history = history ?: previous?.entry?.history,
                alerts = alerts ?: previous?.entry?.alerts,
                alertStrikeId = alertStrikeId ?: previous?.entry?.alertStrikeId,
                settings = settings ?: previous?.entry?.settings)
            if (previous != null) records.remove(previous)
            records += ShellRecord(key, value)
            return value
        }
        fun resultsFacade(component: JourneyResultsComponent): NativeJourneyResultsPresentation {
            liveJourneyComponents += component
            journeyFacades.firstOrNull { it.component === component }?.let {
                return it.facade as NativeJourneyResultsPresentation
            }
            val branch = owner.child()
            return NativeJourneyResultsPresentation(component, branch).also {
                journeyFacades += NativeFacadeRecord(component, branch, it)
            }
        }
        fun detailFacade(component: JourneyDetailComponent): NativeJourneyDetailPresentation {
            liveJourneyComponents += component
            journeyFacades.firstOrNull { it.component === component }?.let {
                return it.facade as NativeJourneyDetailPresentation
            }
            val branch = owner.child()
            return NativeJourneyDetailPresentation(component, branch).also {
                journeyFacades += NativeFacadeRecord(component, branch, it)
            }
        }
        val mainChild = root.stack.value.items.first { it.instance is RootComponent.Child.Main }.instance
        val main = main()
        val chains = main.pages.value.items.associate { page ->
            val chain = when (val child = page.instance) {
                // T8.5: the journey composer lives in the native Home base.
                // The Search form is not a separate visual destination; only
                // Results/Detail/History appear above Home. The shared Journey
                // stack still retains Search below them; Back returns to Home.
                is MainComponent.Child.Journey -> child.component.stack.value.items.mapNotNull {
                    when (val instance = it.instance) {
                        is JourneyTabComponent.Child.Search -> null
                        // Native destinations own no legacy chrome: the
                        // platform shell Back (Android TopAppBar /
                        // iOS NavigationStack) drives shared Decompose Back.
                        is JourneyTabComponent.Child.Results -> entry(it.instance,
                            NativeDestination.JourneyResults,
                            journeyResults = resultsFacade(instance.component))
                        is JourneyTabComponent.Child.Detail -> entry(it.instance,
                            NativeDestination.JourneyDetail,
                            journeyDetail = detailFacade(instance.component))
                        is JourneyTabComponent.Child.History -> entry(it.instance,
                            NativeDestination.History,
                            history = facade(instance.component) {
                                NativeHistoryPresentation(instance.component, it)
                            })
                    }
                }
                is MainComponent.Child.Stations -> child.component.stack.value.items.map {
                    when (val station = it.instance) {
                        is StationsTabComponent.Child.Search -> entry(station, NativeDestination.StationSearch,
                            stationSearch = facade(station.component) {
                                NativeStationSearchPresentation(station.component, it, child.component::searchTrains)
                            })
                        is StationsTabComponent.Child.Board -> entry(station, NativeDestination.StationBoard,
                            stationBoard = facade(station.component) { NativeStationBoardPresentation(station.component, it) })
                    }
                }
                is MainComponent.Child.Alerts -> child.component.stack.value.items.map {
                    val alertsFacade: NativeAlertsPresentation = facade(child.component) {
                        NativeAlertsPresentation(child.component, it)
                    }
                    val strikeId = (it.instance as? AlertsTabComponent.Child.Detail)?.strikeId?.value
                    // T8.10: production Alerts overview/detail are native.
                    entry(if (it.instance == AlertsTabComponent.Child.Overview) child else it.instance,
                        if (it.instance == AlertsTabComponent.Child.Overview) NativeDestination.AlertsOverview
                            else NativeDestination.StrikeDetail,
                        alerts = alertsFacade, alertStrikeId = strikeId)
                }
                is MainComponent.Child.Home -> listOf(entry(child, NativeDestination.Home))
                is MainComponent.Child.Monitoring -> listOf(entry(child, NativeDestination.Monitoring,
                    monitoring = facade(child.component) { NativeMonitoringPresentation(child.component, it) }))
                is MainComponent.Child.Favorites -> listOf(entry(child, NativeDestination.Saved,
                    saved = facade(child.component) { NativeSavedPresentation(child.component, it) }))
                is MainComponent.Child.Settings -> listOf(entry(child, NativeDestination.Settings,
                    // T8.11: production Settings is native.
                    settings = facade(child.component) {
                        NativeSettingsPresentation(child.component, it)
                    }))
                null -> emptyList()
            }
            page.configuration to chain
        }
        val selected = main.pages.value.items[main.pages.value.selectedIndex].configuration
        val source = if (selected == MainTab.Settings) main.settingsSource else selected
        val area = source.shellPrimaryArea()
        val sourceChain = chains.getValue(source)
        val anchor = chains.getValue(MainTab.Home).firstOrNull()
            ?: entry(mainChild, NativeDestination.Home, available = false)
        val mainChain = if (source == MainTab.Journey || source == MainTab.Stations) listOf(anchor) + sourceChain
            else sourceChain.ifEmpty { listOf(entry(mainChild, source.destination(), available = false)) }
        val secondary = if (selected == MainTab.Settings) chains.getValue(MainTab.Settings) else emptyList()
        val outer = root.stack.value.items.filter { it.instance !is RootComponent.Child.Main }.map {
            when (val child = it.instance) {
                is RootComponent.Child.Search -> entry(child, NativeDestination.TrainSearch,
                    trainSearch = facade(child.component) { NativeTrainSearchPresentation(child.component, it) })
                is RootComponent.Child.Detail -> entry(child, NativeDestination.TrainDetail,
                    trainDetail = facade(child.component) { NativeTrainDetailPresentation(child.component, it) })
                is RootComponent.Child.StationBoard -> entry(child, NativeDestination.StationBoard,
                    stationBoard = facade(child.component) { NativeStationBoardPresentation(child.component, it) })
                is RootComponent.Child.Main -> error("Main is the visual base")
            }
        }
        records.removeAll { record -> live.none { it === record.key } }
        stationTrainFacades.filter { cached -> liveStationTrainComponents.none { it === cached.component } }.toList().forEach {
            it.owner.close(); stationTrainFacades.remove(it)
        }
        journeyFacades.filter { facade -> liveJourneyComponents.none { it === facade.component } }.toList().forEach {
            it.owner.close()
            journeyFacades.remove(it)
        }
        return NativeShellState(area, mainChain.first(), mainChain.drop(1) + secondary + outer)
    }
}

internal class ShellRecord(val key: Any, val entry: NativeShellEntry)
internal class NativeFacadeRecord(val component: Any, val owner: NativeProjectionOwner, val facade: Any)

internal fun NativePrimaryArea.mainTab(): MainTab = when (this) {
    NativePrimaryArea.Search -> MainTab.Home
    NativePrimaryArea.Monitoring -> MainTab.Monitoring
    NativePrimaryArea.Saved -> MainTab.Favorites
    NativePrimaryArea.Alerts -> MainTab.Alerts
}

internal fun MainTab.shellPrimaryArea(): NativePrimaryArea = when (this) {
    MainTab.Home, MainTab.Journey, MainTab.Stations, MainTab.Settings -> NativePrimaryArea.Search
    MainTab.Monitoring -> NativePrimaryArea.Monitoring
    MainTab.Favorites -> NativePrimaryArea.Saved
    MainTab.Alerts -> NativePrimaryArea.Alerts
}

private fun MainTab.destination(): NativeDestination = when (this) {
    MainTab.Home -> NativeDestination.Home
    MainTab.Journey -> NativeDestination.JourneySearch
    MainTab.Stations -> NativeDestination.StationSearch
    MainTab.Monitoring -> NativeDestination.Monitoring
    MainTab.Favorites -> NativeDestination.Saved
    MainTab.Alerts -> NativeDestination.AlertsOverview
    MainTab.Settings -> NativeDestination.Settings
}

/** Android borrows its Activity-owned root and owns only this projection lifetime. */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
fun createShellPresentation(root: RootComponent): NativeShellPresentation =
    NativeShellPresentation(root, NativeProjectionOwner())
