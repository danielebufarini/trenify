package it.danielebufarini.trenify.app

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

import app.cash.sqldelight.db.SqlDriver
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.Lifecycle
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import it.danielebufarini.trenify.core.database.createDatabase
import it.danielebufarini.trenify.core.domain.OpenBookingLink
import it.danielebufarini.trenify.core.domain.OpenStrikeReference
import it.danielebufarini.trenify.core.domain.StrikeNotificationRepository
import it.danielebufarini.trenify.core.network.NetworkConfig
import it.danielebufarini.trenify.core.network.RequestInstrumentation
import it.danielebufarini.trenify.core.network.createHttpClient
import it.danielebufarini.trenify.core.platform.ApplicationState
import it.danielebufarini.trenify.core.platform.NotificationDestination
import it.danielebufarini.trenify.core.platform.NotificationEntryHandoff
import it.danielebufarini.trenify.core.platform.PlatformServices
import it.danielebufarini.trenify.database.TrenifyDatabase
import it.danielebufarini.trenify.core.platform.ConnectivityStatus
import it.danielebufarini.trenify.data.RealtimeRepositories
import it.danielebufarini.trenify.data.SqlDelightMonitoringRepository
import it.danielebufarini.trenify.data.SqlDelightNotificationSettingsRepository
import it.danielebufarini.trenify.data.SqlDelightStrikeRepository
import it.danielebufarini.trenify.data.SqlDelightJourneyRepository
import it.danielebufarini.trenify.provider.journey.MultiSourceJourneyProvider
import it.danielebufarini.trenify.provider.journey.TrenitaliaJourneyAdapter
import it.danielebufarini.trenify.provider.journey.ItaloJourneyAdapter
import it.danielebufarini.trenify.provider.mit.strikes.MitStrikeAdapter
import it.danielebufarini.trenify.provider.viaggiatreno.ViaggiaTrenoAdapter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
class AppGraph(
    internal val database: TrenifyDatabase,
    private val httpClient: HttpClient,
    private val platformServices: PlatformServices,
    private val sqlDriver: SqlDriver,
    private val instrumentation: RequestInstrumentation = RequestInstrumentation.None,
    componentFactory: AppComponentFactory? = null,
    workerContext: CoroutineContext = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + workerContext)
    private val available = combine(platformServices.connectivity.status(), platformServices.applicationState.state()) { connection, app ->
        connection == ConnectivityStatus.Available && app == ApplicationState.Foreground
    }.stateIn(scope, SharingStarted.Eagerly, false)
    private val repositories = RealtimeRepositories(database, listOf(ViaggiaTrenoAdapter(httpClient, instrumentation = instrumentation)), scope,
        instrumentation = instrumentation,
        online = { platformServices.connectivity.status().value != ConnectivityStatus.Unavailable })
    private val monitoringRepository = SqlDelightMonitoringRepository(database)
    private val notificationSettingsRepository = SqlDelightNotificationSettingsRepository(database)
    private val journeyProvider = MultiSourceJourneyProvider(listOf(
        TrenitaliaJourneyAdapter(httpClient, instrumentation = instrumentation),
        ItaloJourneyAdapter(httpClient, instrumentation = instrumentation),
    ), instrumentation = instrumentation)
    private val journeyRepository = SqlDelightJourneyRepository(database, journeyProvider, scope,
        instrumentation = instrumentation,
        online = { platformServices.connectivity.status().value != ConnectivityStatus.Unavailable })
    private val strikeRepository = SqlDelightStrikeRepository(
        database,
        MitStrikeAdapter(httpClient),
        scope,
        online = { platformServices.connectivity.status().value != ConnectivityStatus.Unavailable },
    )
    // One booking handoff instance drives both availability and execution, so the UI
    // can never advertise a channel the opener would reject. The T0.9 platform launcher
    // is adapted to the domain URL-opening port; booking still knows no platform type.
    private val bookingHandoff = OpenBookingLink(openUrl = platformServices.externalUrlLauncher::open)
    // Official strike/operator-information handoff (T7.12-D): dedicated
    // information policy, never the booking allowlist, through the same
    // injected T0.9 launcher abstraction.
    private val strikeReference = OpenStrikeReference(openUrl = platformServices.externalUrlLauncher::open)
    private val monitoringCoordinator = MonitoringCoordinator(
        monitoringRepository,
        repositories,
        platformServices,
        notificationSettingsRepository,
        scope,
    )
    private val strikeCoordinator = StrikeCoordinator(
        strikeRepository,
        strikeRepository,
        platformServices,
        notificationSettingsRepository,
        scope,
    )
    private val componentFactory = componentFactory ?: AppComponentFactory(
        repositories,
        repositories,
        repositories,
        repositories,
        available,
        monitoringRepository = monitoringRepository,
        notificationSettingsRepository = notificationSettingsRepository,
        notificationPermission = platformServices.notificationPermission,
        strikeRepository = strikeRepository,
        strikeNotificationRepository = strikeRepository,
        requestNotificationPermission = {
            platformServices.notificationPermission.isGranted() || platformServices.notificationPermission.request()
        },
        journeyRepository = journeyRepository,
        bookingAvailability = { journey -> bookingHandoff.canBook(journey) },
        openBookingLink = { journey -> bookingHandoff(journey) },
        openStrikeReference = strikeReference,
        instrumentation = instrumentation,
    )

    /**
     * Cold-entry handoff buffer (T7.13-H), owned by this graph — never a
     * global. Native notification callbacks offer validated destinations
     * here when no root exists yet; the next created root consumes the
     * pending destination exactly once. Destroy/recreate never leaks the
     * previous root: attachment is cleared on destroy below.
     */
    val notificationHandoff = NotificationEntryHandoff()
    private var attachedRoot: RootComponent? = null

    internal fun createRootComponent(
        componentContext: ComponentContext,
        pendingDestination: NotificationDestination? = null,
    ): RootComponent {
        pendingDestination?.let(notificationHandoff::offer)
        // Precedence (T7.13-H): ordinary saved navigation restores first
        // inside the new tree; the consumed pending destination is then
        // routed on top of the restored tree, so Back returns into it.
        val root = DefaultRootComponent(
            componentContext = componentContext,
            componentFactory = componentFactory,
            pendingDestination = notificationHandoff.consume(),
        )
        attachedRoot = root
        componentContext.lifecycle.subscribe(object : Lifecycle.Callbacks {
            override fun onDestroy() {
                if (attachedRoot === root) attachedRoot = null
            }
        })
        return root
    }

    /**
     * Native notification entry for warm roots (T7.13-E/H): forwards the
     * validated destination to the same shared routing entry Android cold
     * starts and iOS callbacks use. When no root is attached yet (cold
     * entry) the destination is buffered in [notificationHandoff] for the
     * next root instead. Malformed payloads never reach here: hosts decode
     * with [NotificationDestinationCodec] and drop nulls.
     */
    fun deliverNotificationDestination(destination: NotificationDestination) {
        val root = attachedRoot
        if (root == null) notificationHandoff.offer(destination)
        else root.onNotificationDestination(destination)
    }

    /**
     * Foreground local-notification presentation eligibility (T7.13-G):
     * the host decides platform presentation mechanics, but event/business
     * eligibility stays shared and follows the saved effective preferences
     * — never unconditional. The installation switch gates everything;
     * strike destinations additionally require the strike opt-in. Monitor
     * destinations need no per-tap monitor check here: dispatch already
     * claimed the event under the persisted preference state.
     */
    suspend fun shouldPresentForegroundNotification(destination: NotificationDestination?): Boolean =
        ForegroundNotificationPolicy(
            notificationSettingsRepository,
            strikeRepository as? StrikeNotificationRepository,
            platformServices.notificationPermission,
        ).shouldPresent(destination)

    internal fun onApplicationStateChanged(state: ApplicationState) {
        platformServices.lifecycle.setApplicationState(state)
    }

    suspend fun performBestEffortBackgroundRefresh() {
        monitoringCoordinator.performBackgroundRefresh()
        strikeCoordinator.performBackgroundRefresh()
    }

    fun close() {
        scope.cancel()
        platformServices.connectivity.close()
        httpClient.close()
        sqlDriver.close()
    }

    companion object {
        fun create(
            sqlDriver: SqlDriver,
            httpClientEngine: HttpClientEngine,
            platformServices: PlatformServices,
            networkConfig: NetworkConfig = NetworkConfig(),
            instrumentation: RequestInstrumentation = RequestInstrumentation.None,
            componentFactory: AppComponentFactory? = null,
            workerContext: CoroutineContext = Dispatchers.Default,
        ): AppGraph = AppGraph(
            database = createDatabase(sqlDriver),
            httpClient = createHttpClient(httpClientEngine, networkConfig, instrumentation),
            platformServices = platformServices,
            sqlDriver = sqlDriver,
            instrumentation = instrumentation,
            componentFactory = componentFactory,
            workerContext = workerContext,
        )
    }
}

/**
 * JobService outcome mapping for one background-refresh attempt (T7.11 final
 * pass). Coroutine cancellation is never swallowed into a success/failure
 * result: it propagates so a stopped job reports no finished outcome and
 * leaks no scope. Operational failures map to false (ask for a retry);
 * success maps to true. Used by the Android JobService; unit-tested here
 * because a JobService is not directly unit-testable.
 */
internal suspend fun runBackgroundRefreshOutcome(block: suspend () -> Unit): Boolean {
    try {
        block()
        return true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        return false
    }
}
