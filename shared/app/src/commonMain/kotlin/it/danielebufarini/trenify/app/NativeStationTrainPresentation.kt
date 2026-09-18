package it.danielebufarini.trenify.app

import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.MonitorEventKind
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.ui.RealtimeState
import it.danielebufarini.trenify.feature.station.*
import it.danielebufarini.trenify.feature.train.*
import kotlinx.coroutines.flow.StateFlow

/** T8.7: semantic screen snapshots and actions over the existing Decompose components.
 * No rendering, provider selection, lookup algorithm, persistence or polling is owned here.
 * All instants are nullable epoch seconds; platforms format in Europe/Rome.
 */
enum class NativeRealtimeFreshness { Fresh, Stale, Unknown }

data class NativeRealtimeObservation(
    val loading: Boolean,
    val failure: DomainFailure?,
    val hasContent: Boolean,
    val empty: Boolean,
    val freshness: NativeRealtimeFreshness,
    val provenance: NativeRealtimeProvenancePresentation,
)

data class NativeStationRow(
    val stationId: String,
    val name: String,
    val favorite: Boolean,
    val pending: Boolean,
    val failed: Boolean,
)

data class NativeStationSearchState(
    val query: String,
    val observation: NativeRealtimeObservation,
    val results: List<NativeStationRow>,
    val recent: List<NativeStationRow>,
    val recencyFailed: Boolean,
)

class NativeStationSearchPresentation internal constructor(
    private val component: StationSearchComponent,
    private val owner: NativeProjectionOwner,
    private val onTrainSearch: () -> Unit,
) {
    val state: StateFlow<NativeStationSearchState> = owner.projectSnapshot(component.state, component.favoriteState) {
        val current = component.state.value
        val favorites = component.favoriteState.value
        NativeStationSearchState(current.query, current.results.observation(current.results.data?.isEmpty() == true),
            current.results.data.orEmpty().map { it.row(favorites) }, current.recent.map { it.row(favorites) }, current.recencyFailed)
    }
    fun editQuery(query: String) = act { if (query != component.state.value.query) component.query(query) }
    fun retry() = act(component::retry)
    fun selectStation(stationId: String) = act { station(stationId)?.let(component::select) }
    fun toggleFavorite(stationId: String) = act { station(stationId)?.let(component::toggleFavorite) }
    fun removeRecent(stationId: String) = act { component.state.value.recent.firstOrNull { it.id.value == stationId }?.let(component::removeRecent) }
    fun clearRecent() = act(component::clearRecent)
    fun searchTrains() = act(onTrainSearch)
    private fun station(id: String): Station? = (component.state.value.results.data.orEmpty() + component.state.value.recent)
        .firstOrNull { it.id.value == id }
    private fun act(action: () -> Unit) { if (owner.job.isActive) action() }
}

/** Stable row/run identity is the existing provider-scoped dated TrainRunId, never a label. */
data class NativeTrainRow(
    val identity: NativeTrainRunIdentity,
    val originId: String,
    val originName: String,
    val destinationName: String?,
    val operatorName: String?,
    val category: TrainCategory?,
    val serviceDate: String,
    val status: TrainStatus,
    val delayMinutes: Int?,
    /** Board-station scheduled event only; never reused as a terminal timestamp. */
    val eventEpochSeconds: Long?,
    val scheduledDepartureEpochSeconds: Long?,
    val scheduledArrivalEpochSeconds: Long?,
    val scheduledPlatform: String?,
    val actualPlatform: String?,
    val providerName: String?,
)

data class NativeStationBoardState(
    val stationId: String,
    val station: NativeStationRow?,
    val unavailable: Boolean,
    val direction: BoardKind,
    val observation: NativeRealtimeObservation,
    val trains: List<NativeTrainRow>,
)

class NativeStationBoardPresentation internal constructor(
    private val component: StationBoardComponent,
    private val owner: NativeProjectionOwner,
) {
    val state: StateFlow<NativeStationBoardState> = owner.projectSnapshot(
        component.state, component.stationState, component.kind, component.favoriteState,
    ) {
        val current = component.state.value
        val resolution = component.stationState.value
        NativeStationBoardState(component.stationId.value, resolution.station?.row(component.favoriteState.value),
            resolution.unavailable, component.kind.value, current.observation(current.data?.trains?.isEmpty() == true),
            current.data?.trains.orEmpty().map { it.nativeRow() })
    }
    fun setDirection(direction: BoardKind) = act { component.select(direction) }
    fun refresh() = act { component.refresh() }
    fun toggleFavorite() = act(component::toggleFavorite)
    fun openTrain(key: String) = act { component.state.value.data?.trains?.firstOrNull { it.id.key == key }?.let { component.openTrain(it.id) } }
    private fun act(action: () -> Unit) { if (owner.job.isActive) action() }
}

data class NativeTrainSearchState(
    val number: String,
    val serviceDate: String,
    /** Current shared carried input, not a platform-created filter. */
    val expectedOriginId: String?,
    val expectedOriginName: String?,
    val expectedOperatorName: String?,
    val invalidNumber: Boolean,
    val noCompatibleService: Boolean,
    val observation: NativeRealtimeObservation,
    /** Inline picker on the SAME Search route, including an unverifiable single run. */
    val runs: List<NativeTrainRow>,
)

class NativeTrainSearchPresentation internal constructor(
    private val component: TrainSearchComponent,
    private val owner: NativeProjectionOwner,
) {
    val state: StateFlow<NativeTrainSearchState> = owner.project(component.state) {
        val expected = component.lookupIntent()
        NativeTrainSearchState(it.number, it.serviceDate.toString(), expected?.originId?.value,
            expected?.originName, expected?.operator?.name, it.invalidNumber, it.noCompatibleService,
            it.results.observation(it.results.data?.isEmpty() == true), it.results.data.orEmpty().map { run -> run.nativeRow() })
    }
    fun editNumber(number: String) = act { if (number != component.state.value.number) component.number(number) }
    fun submit() = act(component::search)
    fun selectRun(key: String) = act { component.state.value.results.data?.firstOrNull { it.id.key == key }?.let(component::select) }
    private fun act(action: () -> Unit) { if (owner.job.isActive) action() }
}

data class NativeTrainStop(
    /** Run key + ordered stop occurrence + canonical station ID; repeated visits remain distinct. */
    val key: String,
    val stationId: String,
    val name: String,
    val progress: StopProgress,
    val status: StopStatus,
    val scheduledArrivalEpochSeconds: Long?,
    val actualArrivalEpochSeconds: Long?,
    val scheduledDepartureEpochSeconds: Long?,
    val actualDepartureEpochSeconds: Long?,
    val scheduledPlatform: String?,
    val actualPlatform: String?,
    val delayMinutes: Int?,
)

data class NativeTrainDetailState(
    val identity: NativeTrainRunIdentity,
    val summary: NativeTrainRow?,
    val observation: NativeRealtimeObservation,
    val stops: List<NativeTrainStop>,
    /** Complete qualified observation only; neither partial position nor fabricated time. */
    val positionStationName: String?,
    val positionObservedAtEpochSeconds: Long?,
    val favorite: TrainFavoriteState,
    val favoriteIdentity: String?,
    val lifecycleResolved: Boolean,
    val ended: Boolean,
    val monitored: Boolean,
    val notificationPermissionDenied: Boolean,
    val monitorNotificationsEnabled: Boolean?,
    val monitorPreferencesPending: Boolean,
    val monitorThresholdText: String,
    val monitorThresholdInvalid: Boolean,
    val monitorPreferencesFailed: Boolean,
    val notifyDelay: Boolean,
    val notifyPlatform: Boolean,
    val notifyCancellation: Boolean,
    val notifyDeparture: Boolean,
    val notifyArrival: Boolean,
    val warnings: List<NativeServiceStrikeWarningPresentation>,
    val strikesStale: Boolean,
    val strikesUnknown: Boolean,
) {
    val canRefresh: Boolean get() = lifecycleResolved && !ended
    val canToggleMonitoring: Boolean get() = lifecycleResolved && !ended
    val canEditMonitorPreferences: Boolean get() = lifecycleResolved && !ended && monitored
}

class NativeTrainDetailPresentation internal constructor(
    private val component: TrainDetailComponent,
    private val owner: NativeProjectionOwner,
) {
    val state: StateFlow<NativeTrainDetailState> = owner.projectSnapshot(
        *listOfNotNull(component.state, component.favoriteTrainState).toTypedArray(),
    ) { component.nativeDetail() }
    fun refresh() = act { if (state.value.canRefresh) component.refresh() }
    fun toggleFavorite() = act(component::toggleFavoriteTrain)
    fun toggleMonitoring() = act { if (state.value.canToggleMonitoring) component.toggleMonitoring() }
    fun removeEndedMonitor() = act { if (state.value.ended) component.removeEndedMonitor() }
    fun setMonitorNotifications(enabled: Boolean) = preferences { component.setMonitorNotificationsEnabled(enabled) }
    fun editMonitorThreshold(text: String) = preferences { component.editMonitorThreshold(text) }
    fun saveMonitorThreshold() = preferences(component::saveMonitorThreshold)
    fun setMonitorEvent(kind: MonitorEventKind, enabled: Boolean) = preferences { component.setMonitorEventFlag(kind, enabled) }
    fun retryMonitorPreferences() = preferences(component::retryMonitorPreferences)
    private fun preferences(action: () -> Unit) = act { if (state.value.canEditMonitorPreferences) action() }
    private fun act(action: () -> Unit) { if (owner.job.isActive) action() }
}

private fun Station.row(favorites: StationFavoriteState) = NativeStationRow(id.value, name,
    id in favorites.favoriteIds, id in favorites.pendingIds, id in favorites.failedIds)

internal fun TrainRunSummary.nativeRow() = NativeTrainRow(NativeSemanticIdentity.trainRun(id), origin.id.value,
    origin.name, destinationName, operator?.name, category, id.serviceDate.toString(), status, delayMinutes,
    scheduledTime?.epochSeconds, scheduledDeparture?.epochSeconds, scheduledArrival?.epochSeconds,
    scheduledPlatform, actualPlatform, trainSourceName(id.provider))

internal fun RealtimeState<*>.observation(empty: Boolean = false, providerName: String? = null): NativeRealtimeObservation {
    val fetched = when (val value = freshness) {
        is DataFreshness.Fresh -> value.fetchedAt.epochSeconds
        is DataFreshness.Stale -> value.fetchedAt.epochSeconds
        else -> null
    }
    val source = when (val value = freshness) {
        is DataFreshness.Fresh -> value.sourceTimestamp?.epochSeconds
        is DataFreshness.Stale -> value.sourceTimestamp?.epochSeconds
        else -> null
    }
    val classification = when {
        freshness is DataFreshness.Unknown && failure == null -> NativeRealtimeFreshness.Unknown
        stale || freshness is DataFreshness.Stale -> NativeRealtimeFreshness.Stale
        freshness is DataFreshness.Fresh -> NativeRealtimeFreshness.Fresh
        else -> NativeRealtimeFreshness.Unknown
    }
    return NativeRealtimeObservation(loading, failure, data != null, empty, classification,
        NativeRealtimeProvenancePresentation(providerName, fetched, source, stale || freshness is DataFreshness.Stale, failure != null))
}

private fun TrainDetailComponent.nativeDetail(): NativeTrainDetailState {
    val current = state.value
    val run = current.data
    val position = operationalPositionDisplay(run?.position) as? OperationalPositionDisplay.Known
    val progress = routeProgress(run?.stops.orEmpty())
    val monitor = current.monitor
    val thresholds = monitor?.thresholds
    return NativeTrainDetailState(
        identity = NativeSemanticIdentity.trainRun(id), summary = run?.summary?.nativeRow(),
        observation = current.realtime.observation(providerName = trainSourceName(id.provider)),
        stops = run?.stops.orEmpty().mapIndexed { index, stop ->
            NativeTrainStop("${id.key}:${index}:${stop.station.id.value}", stop.station.id.value, stop.station.name,
                progress[index], stop.status, stop.scheduledArrival?.epochSeconds, stop.actualArrival?.epochSeconds,
                stop.scheduledDeparture?.epochSeconds, stop.actualDeparture?.epochSeconds, stop.scheduledPlatform,
                stop.actualPlatform, stop.delayMinutes)
        },
        positionStationName = position?.stationName, positionObservedAtEpochSeconds = position?.observedAt?.epochSeconds,
        favorite = favoriteTrainState?.value ?: TrainFavoriteState(), favoriteIdentity = run?.summary?.toFavoriteTrain()?.id?.value,
        lifecycleResolved = current.monitorLifecycleResolved, ended = current.isEnded, monitored = current.isMonitored,
        notificationPermissionDenied = current.notificationPermissionGranted == false,
        monitorNotificationsEnabled = monitor?.notificationsEnabled, monitorPreferencesPending = current.monitorNotificationsPending,
        monitorThresholdText = current.monitorThresholdText, monitorThresholdInvalid = current.monitorThresholdInvalid,
        monitorPreferencesFailed = current.monitorPrefsError, notifyDelay = thresholds?.notifyDelay ?: false,
        notifyPlatform = thresholds?.notifyPlatform ?: false, notifyCancellation = thresholds?.notifyCancellation ?: false,
        notifyDeparture = thresholds?.notifyDeparture ?: false, notifyArrival = thresholds?.notifyArrival ?: false,
        warnings = current.strikeWarnings.map { it.projected() }, strikesStale = current.strikesStale,
        strikesUnknown = current.strikesUnknown,
    )
}
