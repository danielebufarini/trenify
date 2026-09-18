package it.danielebufarini.trenify.app

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

import com.arkivanov.decompose.ComponentContext
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.network.RequestInstrumentation
import it.danielebufarini.trenify.core.model.Journey
import it.danielebufarini.trenify.core.model.JourneySearchIntent
import it.danielebufarini.trenify.core.model.JourneySearchRequest
import it.danielebufarini.trenify.core.model.Station
import it.danielebufarini.trenify.core.model.StationId
import it.danielebufarini.trenify.core.model.TrainLookupIntent
import it.danielebufarini.trenify.core.model.TrainRunId
import it.danielebufarini.trenify.core.model.TrainSearchHistoryEntry
import kotlinx.datetime.LocalDate
import it.danielebufarini.trenify.core.platform.NotificationPermission
import it.danielebufarini.trenify.feature.home.HomeComponent
import it.danielebufarini.trenify.feature.journey.*
import it.danielebufarini.trenify.feature.favorites.*
import it.danielebufarini.trenify.feature.monitoring.*
import it.danielebufarini.trenify.feature.settings.*
import it.danielebufarini.trenify.feature.station.*
import it.danielebufarini.trenify.feature.strikes.*
import it.danielebufarini.trenify.feature.train.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
open class AppComponentFactory(
    private val stationRepository: StationRepository,
    private val trainRepository: TrainRepository,
    private val historyRepository: HistoryRepository,
    private val favoritesRepository: FavoritesRepository,
    private val available: StateFlow<Boolean>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val monitoringRepository: MonitoringRepository? = null,
    private val notificationSettingsRepository: NotificationSettingsRepository? = null,
    private val notificationPermission: NotificationPermission? = null,
    private val strikeRepository: StrikeRepository? = null,
    private val strikeNotificationRepository: StrikeNotificationRepository? = null,
    private val requestNotificationPermission: suspend () -> Boolean = { false },
    private val journeyRepository: JourneyRepository? = null,
    private val bookingAvailability: ((Journey) -> Boolean)? = null,
    private val openBookingLink: (suspend (Journey) -> BookingHandoffResult)? = null,
    private val openStrikeReference: OpenStrikeReference? = null,
    private val instrumentation: RequestInstrumentation = RequestInstrumentation.None,
) {
    open fun home(
        context: ComponentContext,
        onJourneySearch: () -> Unit = {},
        onTrainSearch: () -> Unit = {},
        onStations: () -> Unit = {},
        onMonitoring: () -> Unit = {},
        onFavorites: () -> Unit = {},
        onAlerts: () -> Unit = {},
        onHistory: () -> Unit = {},
        onStation: (Station) -> Unit = {},
        onRoute: (JourneySearchIntent) -> Unit = {},
        onTrainLookup: (TrainLookupIntent) -> Unit = {},
        onTrain: (TrainRunId) -> Unit = {},
        onTrainRepeat: (TrainSearchHistoryEntry) -> Unit = {},
        onJourneyRepeat: (JourneySearchRequest) -> Unit = {},
    ): HomeComponent = HomeComponent(
        context,
        historyRepository,
        favoritesRepository,
        monitoringRepository?.let(::ObserveActiveMonitors),
        strikeRepository?.let(::LoadStrikes),
        dispatcher,
        onJourneySearch = onJourneySearch,
        onTrainSearch = onTrainSearch,
        onStations = onStations,
        onMonitoring = onMonitoring,
        onFavorites = onFavorites,
        onAlerts = onAlerts,
        onHistory = onHistory,
        onStation = onStation,
        onRoute = onRoute,
        onTrainLookup = onTrainLookup,
        onTrain = onTrain,
        onTrainRepeat = onTrainRepeat,
        onJourneyRepeat = onJourneyRepeat,
    )

    open fun journey(
        context: ComponentContext,
        onTrain: (TrainRunId) -> Unit = {},
        onTrainRepeat: (TrainSearchHistoryEntry) -> Unit = {},
    ): JourneyTabComponent =
        DefaultJourneyTabComponent(context, journeyRepository, trainRepository, onTrain, dispatcher,
            canBook = bookingAvailability, openBooking = openBookingLink, favoritesRepository = favoritesRepository,
            historyRepository = historyRepository, strikes = strikeRepository?.let(::LoadStrikes),
            onTrainRepeat = onTrainRepeat, instrumentation = instrumentation)

    open fun stations(context: ComponentContext, onTrainSearch: () -> Unit, onTrain: (TrainRunId) -> Unit): StationsTabComponent =
        DefaultStationsTabComponent(context, stationRepository, trainRepository, historyRepository,
            favoritesRepository, available, onTrainSearch, onTrain, dispatcher)

    open fun favorites(
        context: ComponentContext,
        onStation: (Station) -> Unit,
        onRoute: (JourneySearchIntent) -> Unit,
        onTrainLookup: (TrainLookupIntent) -> Unit = {},
        onJourneyRepeat: (JourneySearchRequest) -> Unit = {},
        onTrainRepeat: (TrainSearchHistoryEntry) -> Unit = {},
    ): FavoritesComponent = FavoritesComponent(
        context,
        favoritesRepository,
        onStation,
        dispatcher,
        onRoute,
        onTrainLookup,
        historyRepository,
        onJourneyRepeat,
        onTrainRepeat,
    )

    open fun stationBoard(
        context: ComponentContext,
        stationId: StationId,
        onTrain: (TrainRunId) -> Unit,
    ): StationBoardComponent = StationBoardComponent(
        context,
        stationId,
        ObserveStation(stationRepository),
        LoadStationBoard(trainRepository),
        favoritesRepository,
        available,
        onTrain,
        dispatcher,
    )

    open fun monitoring(context: ComponentContext, onTrain: (TrainRunId) -> Unit = {}): MonitoringTabComponent =
        DefaultMonitoringTabComponent(
            context,
            monitoringRepository?.let(::ObserveActiveMonitors),
            monitoringRepository?.let(::StopTrainMonitoring),
            onTrain,
            dispatcher,
            setMonitorNotifications = monitoringRepository?.let(::SetMonitorNotifications),
            observeEndedMonitors = monitoringRepository?.let(::ObserveEndedMonitors),
            removeEndedMonitor = monitoringRepository?.let(::RemoveEndedMonitor),
            strikes = strikeRepository?.let(::LoadStrikes),
        )

    open fun settings(context: ComponentContext): SettingsComponent = DefaultSettingsComponent(
        context,
        notificationSettingsRepository?.let(::ObserveNotificationSettings),
        notificationSettingsRepository?.let(::SetNotificationsEnabled),
        notificationSettingsRepository?.let(::SetDefaultMonitorThresholds),
        strikeNotificationRepository?.let(::ObserveStrikeNotifications),
        strikeNotificationRepository?.let(::SetStrikeNotifications),
        notificationPermission = notificationPermission,
        requestNotificationPermission = requestNotificationPermission,
        dispatcher = dispatcher,
        clearSearchHistoryAndRecency = ClearSearchHistoryAndRecency(historyRepository),
        clearAllFavorites = ClearAllFavorites(favoritesRepository),
    )
    open fun alerts(context: ComponentContext): AlertsTabComponent = DefaultAlertsTabComponent(
        context,
        strikeRepository?.let(::LoadStrikes),
        strikeNotificationRepository?.let(::ObserveStrikeNotifications),
        strikeNotificationRepository?.let(::SetStrikeNotifications),
        requestNotificationPermission,
        dispatcher = dispatcher,
        openStrikeReference = openStrikeReference,
    )

    open fun trainSearch(
        context: ComponentContext,
        onTrain: (TrainRunId) -> Unit,
        initialNumber: String = "",
        serviceDate: LocalDate? = null,
        autoSearch: Boolean = false,
        expected: TrainLookupIntent? = null,
    ) = TrainSearchComponent(context, FindTrainRuns(trainRepository), onTrain, dispatcher,
        initialNumber, serviceDate, autoSearch, expected, historyRepository = historyRepository,
        instrumentation = instrumentation)

    open fun trainDetail(context: ComponentContext, id: TrainRunId) = TrainDetailComponent(
        context,
        id,
        ObserveTrainRun(trainRepository),
        available,
        dispatcher,
        observeMonitor = monitoringRepository?.let(::ObserveTrainMonitor),
        startMonitoring = monitoringRepository?.let(::StartTrainMonitoring),
        stopMonitoring = monitoringRepository?.let(::StopTrainMonitoring),
        removeEndedMonitor = monitoringRepository?.let(::RemoveEndedMonitor),
        requestNotificationPermission = requestNotificationPermission,
        favoritesRepository = favoritesRepository,
        observeNotificationSettings = notificationSettingsRepository?.let(::ObserveNotificationSettings),
        setMonitorNotifications = monitoringRepository?.let(::SetMonitorNotifications),
        updateMonitorThresholds = monitoringRepository?.let(::UpdateMonitorThresholds),
        strikes = strikeRepository?.let(::LoadStrikes),
        instrumentation = instrumentation,
    )
}
