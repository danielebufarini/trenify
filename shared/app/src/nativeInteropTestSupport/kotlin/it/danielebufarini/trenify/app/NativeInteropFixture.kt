package it.danielebufarini.trenify.app

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.arkivanov.decompose.ComponentContext
import kotlinx.datetime.LocalDate
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import com.arkivanov.decompose.router.stack.items
import io.ktor.client.engine.mock.MockEngine
import platform.Foundation.NSRecursiveLock
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.testing.FakeMonitoringRepository
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.HistoryRepository
import it.danielebufarini.trenify.core.domain.JourneyRepository
import it.danielebufarini.trenify.core.domain.OpenBookingLink
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Journey
import it.danielebufarini.trenify.core.model.JourneyLeg
import it.danielebufarini.trenify.core.model.FavoriteRoute
import it.danielebufarini.trenify.core.model.FavoriteTrain
import it.danielebufarini.trenify.core.model.JourneySearchHistoryEntry
import it.danielebufarini.trenify.core.model.JourneySearchRequest
import it.danielebufarini.trenify.core.model.TrainLookupIntent
import it.danielebufarini.trenify.core.model.TrainSearchHistoryEntry
import it.danielebufarini.trenify.core.model.JourneySearchResult
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.ProviderId
import it.danielebufarini.trenify.core.model.SearchHistoryEntry
import it.danielebufarini.trenify.core.model.SearchHistoryEntryId
import it.danielebufarini.trenify.core.model.Station
import it.danielebufarini.trenify.core.model.StationId
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.model.TrainRun
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.model.TrainStop
import it.danielebufarini.trenify.core.platform.*
import it.danielebufarini.trenify.core.testing.FakeJourneyRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.testing.journeyDestination
import it.danielebufarini.trenify.core.testing.journeyRequest
import it.danielebufarini.trenify.core.testing.journeyResult
import it.danielebufarini.trenify.core.testing.testJourney
import it.danielebufarini.trenify.core.testing.testStation
import it.danielebufarini.trenify.core.testing.testStrike
import it.danielebufarini.trenify.core.testing.testSummary
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import it.danielebufarini.trenify.data.SqlDelightNotificationSettingsRepository
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Only compiled with -Ptrenify.nativeInteropTests=true, never a production fallback.
 * Real AppGraph/session/Decompose/Home with deterministic repositories and an in-memory driver.
 */
class NativeInteropFixture private constructor(
    pendingDestination: NotificationDestination?,
    failingJourneyStations: Boolean,
    failingHomeAggregates: Boolean,
    bookingHandoffEnabled: Boolean,
    restoredStationTrainDestination: NativeDestination? = null,
    settingsCorrective: Boolean = false,
    settingsCorrectiveThresholdMinutes: Int = 42,
) {
    constructor(pendingDestination: NotificationDestination?) : this(pendingDestination, false, false, false)
    constructor() : this(null, false, false, false)
    constructor(failingJourneyStations: Boolean, failingHomeAggregates: Boolean) :
        this(null, failingJourneyStations, failingHomeAggregates, false)
    constructor(bookingHandoffEnabled: Boolean) : this(null, false, false, bookingHandoffEnabled)
    constructor(restoredStationTrainDestination: NativeDestination) : this(null, false, false, false, restoredStationTrainDestination)
    constructor(settingsCorrectiveThresholdMinutes: Int, delayedPermission: Boolean) :
        this(null, false, false, false, settingsCorrective = delayedPermission,
            settingsCorrectiveThresholdMinutes = settingsCorrectiveThresholdMinutes)
    val stationTrainAt = kotlin.time.Instant.parse("2026-09-14T09:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repositories = FakeRealtimeRepositories()
    private val monitoring = FakeMonitoringRepository()
    private val trainRepository = object : TrainRepository by repositories {
        override fun observeTrain(id: TrainRunId): Flow<DataResult<TrainRun>> = repositories.trainState.map { result ->
            when (result) {
                is DataResult.Failure -> result
                is DataResult.Data -> {
                    val candidate = (repositories.runsResult as? DataResult.Data)?.value?.firstOrNull { it.id == id }
                    result.copy(value = result.value.copy(summary = result.value.summary.copy(id = id,
                        origin = candidate?.origin ?: result.value.summary.origin,
                        operator = candidate?.operator ?: result.value.summary.operator)))
                }
            }
        }
    }
    /**
     * Strike coverage for journey warning presentation (T8.6). The default
     * payload does not overlap the canonical fake journey legs, so warnings
     * stay empty unless [stageStrikeWarning] is called; wiring itself adds
     * no provider calls.
     */
    private val strikeRepository = FakeStrikeRepository()
    private val journeyRepository: JourneyRepository = if (failingJourneyStations) {
        object : JourneyRepository {
            override suspend fun searchStations(query: String) =
                DataResult.Failure(DomainFailure.TEMPORARY)
            override fun observe(request: JourneySearchRequest) =
                kotlinx.coroutines.flow.flowOf<DataResult<JourneySearchResult>>(
                    DataResult.Failure(DomainFailure.TEMPORARY),
                )
            override suspend fun search(request: JourneySearchRequest, force: Boolean) =
                DataResult.Failure(DomainFailure.TEMPORARY)
        }
    } else {
        FakeJourneyRepository()
    }
    var driverCloses: Int = 0
        private set
    var connectivityCloses: Int = 0
        private set
    var networkRequests: Int = 0
        private set
    var homeObservations: Int = 0
        private set
    private val history = object : HistoryRepository by repositories {
        override fun observeSearchHistory(): Flow<List<SearchHistoryEntry>> {
            homeObservations++
            if (!failingHomeAggregates) return repositories.observeSearchHistory()
            // One cached row survives before the failure, so the native Home
            // must keep rendering it next to the failure indication.
            return kotlinx.coroutines.flow.flow {
                emit(listOf(
                    JourneySearchHistoryEntry(
                        SearchHistoryEntryId("t85-cached-recent"),
                        testStation,
                        journeyDestination,
                        journeyRequest.at,
                        submittedAt = Clock.System.now(),
                    ),
                ))
                throw RuntimeException("T8.5 corrective test failure")
            }
        }
    }
    private val nativeDriver = NativeSqliteDriver(TrenifyDatabase.Schema, ":memory:")
    /**
     * Serializing driver gate (test harness only; production lifetime untouched).
     *
     * Production repositories run transactions on background dispatchers
     * (`flowOn(Dispatchers.Default)`), while `AppGraph.close()` cancels its
     * scope and closes the driver synchronously with no join: an in-flight
     * transaction can mutate the native pool's connection map while
     * `Pool.close()` iterates it (`ConcurrentModificationException` in
     * `ThreadConnection.cleanUp`). Every driver entry — including `close` —
     * takes this one recursive lock, so close waits for in-flight calls and
     * no two threads are ever inside the pool together. Recursive (not
     * spinlock): a listener callback that re-enters the driver on the same
     * thread during close cannot self-deadlock. Calls after close fail with
     * `CancellationException`: every legitimate caller is already cancelled
     * by then, so that is their expected cooperative outcome, and nothing
     * is swallowed — the signal propagates to the caller.
     */
    private val driverGate = NSRecursiveLock()
    private var driverClosed = false
    private val driver = object : SqlDriver {
        override fun close() = gated {
            if (!driverClosed) {
                driverClosed = true
                driverCloses++
                nativeDriver.close()
            }
        }

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> = guarded { nativeDriver.executeQuery(identifier, sql, mapper, parameters, binders) }

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> = guarded { nativeDriver.execute(identifier, sql, parameters, binders) }

        override fun newTransaction(): QueryResult<Transacter.Transaction> =
            guarded { nativeDriver.newTransaction() }

        override fun currentTransaction(): Transacter.Transaction? =
            guarded { nativeDriver.currentTransaction() }

        override fun addListener(vararg queryKeys: String, listener: Query.Listener) =
            guarded { nativeDriver.addListener(*queryKeys, listener = listener) }

        override fun removeListener(vararg queryKeys: String, listener: Query.Listener) =
            guarded { nativeDriver.removeListener(*queryKeys, listener = listener) }

        override fun notifyListeners(vararg queryKeys: String) =
            guarded { nativeDriver.notifyListeners(*queryKeys) }

        private fun <T> guarded(block: () -> T): T = gated {
            if (driverClosed) throw CancellationException("NativeInteropFixture driver is closed")
            block()
        }

        private fun <T> gated(block: () -> T): T {
            driverGate.lock()
            try {
                return block()
            } finally {
                driverGate.unlock()
            }
        }
    }
    private val gatedSettingsPermission = if (settingsCorrective) GatedSettingsPermission() else null
    private val defaultServices = PlatformServices.defaults()
    private val services = defaultServices.copy(connectivity = object : Connectivity {
        override fun status(): StateFlow<ConnectivityStatus> = MutableStateFlow(ConnectivityStatus.Unavailable)
        override fun close() { connectivityCloses++ }
    }, notificationPermission = gatedSettingsPermission ?: defaultServices.notificationPermission)
    // T8.11: persisted installation defaults for native Settings tests.
    // Declared after the gated driver: a second database instance over the
    // same in-memory driver stays consistent with the graph's own instance.
    // NOTE: on the iOS simulator this name resolves to a FILE-backed
    // database that survives reinstalls, so every fixture resets the
    // installation settings below; no Settings test may assume pristine
    // global state.
    private val notificationSettings =
        SqlDelightNotificationSettingsRepository(TrenifyDatabase(driver))

    init {
        // Hermetic installation defaults per fixture (see note above):
        // restore the documented defaults through the public API so no
        // Settings test observes another run's persisted choices.
        runBlocking {
            notificationSettings.setNotificationsEnabled(!settingsCorrective)
            notificationSettings.setDefaultThresholds(
                if (settingsCorrective) {
                    MonitorThresholds(
                        delayMinutes = settingsCorrectiveThresholdMinutes,
                        notifyDelay = false,
                        notifyPlatform = true,
                        notifyCancellation = false,
                        notifyDeparture = true,
                        notifyArrival = false,
                    )
                } else {
                    MonitorThresholds()
                },
            )
        }
    }
    /**
     * Production booking wiring with a test URL opener (T8.6): the real
     * [OpenBookingLink] policy decides availability and the target, so the
     * native handoff exercises the approved allowlist path. Disabled by
     * default; existing fixtures keep the unwired behavior.
     */
    private val bookingHandoff = OpenBookingLink(openUrl = { true })
    private val graph = AppGraph.create(driver, MockEngine {
        networkRequests++
        error("Native interop tests must not call providers")
    }, services, componentFactory = object : AppComponentFactory(
        repositories, trainRepository, history, repositories, MutableStateFlow(false),
        journeyRepository = journeyRepository,
        monitoringRepository = monitoring,
        // T8.11: Settings needs the same persisted installation defaults as
        // production (real in-memory database behind the gated driver), so
        // native Settings tests exercise true save/flag/deletion round-trips.
        notificationSettingsRepository = notificationSettings,
        notificationPermission = services.notificationPermission,
        bookingAvailability = if (bookingHandoffEnabled) bookingHandoff::canBook else null,
        openBookingLink = if (bookingHandoffEnabled) ({ journey -> bookingHandoff(journey) }) else null,
        strikeRepository = strikeRepository,
        strikeNotificationRepository = strikeRepository) {
            override fun trainSearch(context: ComponentContext, onTrain: (TrainRunId) -> Unit,
                initialNumber: String, serviceDate: LocalDate?, autoSearch: Boolean, expected: TrainLookupIntent?) =
                super.trainSearch(context, onTrain, initialNumber, serviceDate ?: RailwayTime.serviceDate(stationTrainAt), autoSearch, expected)
        },
        // This fixture runs and closes on the main actor. Serial workers avoid racing
        // the native in-memory driver close with cancelled coordinator DB work.
        workerContext = Dispatchers.Main.immediate)
    val session: NativeApplicationSession
    init {
        pendingDestination?.let {
            IosNotificationLaunch.install()
            (IosNotificationLaunch.retainedDelegateForTest() as IosNotificationCenterDelegate)
                .handleResponse(NotificationTap.extras(it).mapKeys { field -> field.key as Any? }) {}
        }
        val keeper = restoredStationTrainDestination?.let { destination ->
            val life = LifecycleRegistry()
            val original = StateKeeperDispatcher()
            val root = graph.createRootComponent(DefaultComponentContext(life, original))
            val main = (root.stack.value.active.instance as RootComponent.Child.Main).component
            when (destination) {
                NativeDestination.StationSearch, NativeDestination.StationBoard -> {
                    main.select(MainTab.Stations)
                    val stations = (main.pages.value.items.first { it.instance is MainComponent.Child.Stations }.instance as MainComponent.Child.Stations).component
                    val search = (stations.stack.value.active.instance as it.danielebufarini.trenify.feature.station.StationsTabComponent.Child.Search).component
                    search.query("Roma")
                    if (destination == NativeDestination.StationBoard) {
                        search.select(testStation)
                        (stations.stack.value.active.instance as it.danielebufarini.trenify.feature.station.StationsTabComponent.Child.Board).component.select(BoardKind.ARRIVALS)
                    }
                }
                NativeDestination.TrainSearch, NativeDestination.TrainDetail -> {
                    main.openTrainSearch(TrainNumber("123"), testSummary.id.serviceDate,
                        TrainLookupIntent(TrainNumber("123"), testStation.id, testStation.name, Operator("Trenitalia")))
                    if (destination == NativeDestination.TrainDetail) {
                        root.onNotificationDestination(NotificationDestination.Train(testSummary.id.provider.value, testSummary.id.number.value,
                            testSummary.id.origin.value, testSummary.id.serviceDate.toString()))
                    }
                }
                NativeDestination.Monitoring -> main.select(MainTab.Monitoring)
                NativeDestination.Saved -> main.select(MainTab.Favorites)
                else -> error("T8.7 restoration fixture only supports Station/Train/Monitoring/Saved")
            }
            val saved = original.save()
            life.destroy() // No simultaneous second active root or session.
            StateKeeperDispatcher(saved)
        } ?: StateKeeperDispatcher()
        session = createIosNativeSession(graph, keeper)
    }
    private val originalRoot = session.root
    val sameRoot: Boolean get() = session.root === originalRoot
    fun openHistory() = session.state.value.main!!.openHistory()
    /**
     * T8.10: Alerts overview/detail are native. Strikes are staged by
     * default ([FakeStrikeRepository] serves [testStrike]). T8.11:
     * Settings is native too.
     */
    fun openStrikeDetail(): Boolean {
        val main = (session.root.stack.value.active.instance as RootComponent.Child.Main).component
        return main.routeStrikeIfNew(testStrike.id)
    }

    fun openSettings() = session.shell.openSettings()

    fun releaseSettingsPermission() {
        gatedSettingsPermission?.release?.complete(EffectiveNotificationPermission.DENIED)
    }

    /**
     * T8.10 fixture-only staging: a future-dated strike visible in the
     * native overview (the default [testStrike] ended 2026-09-08 and is
     * hidden by the shared upcoming predicate under a real clock).
     * Never compiled into the normal export.
     */
    fun stageFutureAlert(id: String = "alert-1", status: StrikeStatus = StrikeStatus.SCHEDULED) {
        val now = Clock.System.now()
        val strike = testStrike.copy(
            id = StrikeId(id),
            externalId = id,
            start = now + 1.hours,
            end = now + 25.hours,
            status = status,
            contentFingerprint = "fp-$id-${status.name}",
        )
        strikeRepository.state.value = DataResult.Data(listOf(strike), DataFreshness.Fresh(now, null))
        strikeRepository.refreshResult = DataResult.Data(StrikeRefresh(listOf(strike)), DataFreshness.Fresh(now, null))
    }

    fun stageAlertsEmpty() {
        val now = Clock.System.now()
        strikeRepository.state.value = DataResult.Data(emptyList(), DataFreshness.Fresh(now, null))
        strikeRepository.refreshResult = DataResult.Data(StrikeRefresh(emptyList()), DataFreshness.Fresh(now, null))
    }

    fun stageAlertsFailure() {
        strikeRepository.state.value = DataResult.Failure(DomainFailure.OFFLINE)
    }
    fun sharedSelect(area: NativePrimaryArea) = session.state.value.main!!.select(area)
    fun warmNotification(destination: NotificationDestination) {
        IosNotificationLaunch.install()
        (IosNotificationLaunch.retainedDelegateForTest() as IosNotificationCenterDelegate)
            .handleResponse(NotificationTap.extras(destination).mapKeys { it.key as Any? }) {}
    }
    val collectorCounts: StateFlow<Int> = session.projections.probes.collectors.asStateFlow()
    val collectorCount: Int get() = session.projections.probes.collectors.value
    val projectionCount: Int get() = session.projections.probes.subscriptions.value
    val homeMatchesSharedComponent: Boolean get() {
        val main = session.root.stack.value.items.first().instance as RootComponent.Child.Main
        val component = (main.component.pages.value.items[MainTab.Home.ordinal].instance as? MainComponent.Child.Home)?.component
        return component?.state?.value == session.state.value.main?.state?.value?.home?.state?.value
    }

    fun setFavoriteStation(favorite: Boolean) {
        scope.launch { repositories.setFavorite(testStation, favorite) }
    }

    /**
     * Stages the correlated realtime run for the canonical fake journey leg
     * (T8.6): the operator-carrying summary plus the exact scheduled stop
     * times [CorrelateJourneyLeg] requires, with Fresh provenance so the
     * detail resolves its train action and enrichment like production.
     */
    /**
     * Swaps the journey payload for a two-leg transfer journey (T8.6):
     * Roma Termini → Bologna Centrale → Milano Centrale with a 15-minute
     * scheduled interchange. Search/observe semantics are unchanged; only
     * the returned journeys differ.
     */
    /**
     * Stages [count] deterministic single-leg journeys for long-list rendering
     * audits (T8.14-R3): departures one minute apart, distinct train numbers,
     * all inside the fake request window. Follows the stageTransferJourney style.
     */
    fun stageManyJourneys(count: Int = 200) {
        val fake = journeyRepository as? FakeJourneyRepository ?: return
        val at = journeyRequest.at
        fake.state.value = DataResult.Data(
            JourneySearchResult(
                List(count) { i ->
                    val departure = at + i.minutes
                    Journey(
                        listOf(
                            JourneyLeg(
                                testStation, journeyDestination,
                                departure, departure + 2.hours,
                                TrainNumber((10000 + i).toString()),
                                "Regionale", Operator("Trenitalia"),
                            ),
                        ),
                        setOf(ProviderId("test")),
                    )
                },
                journeyResult.coverage,
                journeyRequest.departureFrom,
                journeyRequest.departureUntil,
            ),
            DataFreshness.Fresh(Clock.System.now(), null),
        )
    }

    fun stageTransferJourney() {
        val fake = journeyRepository as? FakeJourneyRepository ?: return
        val at = journeyRequest.at
        val bologna = Station(StationId("internal-bologna"), "Bologna Centrale")
        val transfer = Journey(
            listOf(
                JourneyLeg(testStation, bologna, at, at + 1.hours, TrainNumber("9516"), "Frecciarossa", Operator("Trenitalia")),
                JourneyLeg(bologna, journeyDestination, at + 75.minutes, at + 3.hours, TrainNumber("4321"), "Regionale", Operator("Trenitalia")),
            ),
            setOf(ProviderId("test")),
        )
        fake.state.value = DataResult.Data(
            JourneySearchResult(
                listOf(transfer),
                journeyResult.coverage,
                journeyRequest.departureFrom,
                journeyRequest.departureUntil,
            ),
            DataFreshness.Fresh(Clock.System.now(), null),
        )
    }

    /**
     * Stages strike coverage overlapping the canonical fake journey legs
     * with Fresh provenance (T8.6), so the next journey observation carries
     * exactly one warning per journey/leg without any provider call.
     */
    fun stageStrikeWarning() {
        val leg = testJourney.legs.single()
        strikeRepository.state.value = DataResult.Data(
            listOf(testStrike.copy(start = leg.departure - 2.hours, end = leg.arrival + 2.hours)),
            DataFreshness.Fresh(Clock.System.now(), null),
        )
    }

    /** Swaps the journey payload for a provider failure with no content (T8.6). */
    fun stageJourneyFailure() {
        val fake = journeyRepository as? FakeJourneyRepository ?: return
        fake.state.value = DataResult.Failure(DomainFailure.TEMPORARY)
    }

    /** Swaps the journey payload for an empty result with Fresh provenance (T8.6). */
    fun stageEmptyJourneyResult() {
        val fake = journeyRepository as? FakeJourneyRepository ?: return
        fake.state.value = DataResult.Data(
            JourneySearchResult(
                emptyList(),
                journeyResult.coverage,
                journeyRequest.departureFrom,
                journeyRequest.departureUntil,
            ),
            DataFreshness.Fresh(Clock.System.now(), null),
        )
    }

    /**
     * Makes the next route-favorite toggle fail (T8.6): the shared
     * controller keeps content visible with the failure flagged instead of
     * inventing optimistic state. No provider call.
     */
    fun stageRouteFavoriteFailure() {
        repositories.favoriteFailure = IllegalStateException("boom")
    }

    /**
     * Swaps the journey payload sources for one genuine known provider
     * (T8.6 micro-corrective): Detail must surface the shared Journey
     * source set, never anything inferred from the train operator.
     */
    fun stageKnownJourneySources() {
        val fake = journeyRepository as? FakeJourneyRepository ?: return
        val current = (fake.state.value as? DataResult.Data)?.value ?: return
        fake.state.value = DataResult.Data(
            current.copy(journeys = current.journeys.map { it.copy(sources = setOf(ProviderId("viaggiatreno"))) }),
            DataFreshness.Fresh(Clock.System.now(), null),
        )
    }

    fun stageJourneyCorrelatedRun(
        status: TrainStatus = TrainStatus.RUNNING,
        delayMinutes: Int? = null,
    ) {
        val leg = testJourney.legs.single()
        repositories.trainState.value = DataResult.Data(
            TrainRun(
                testSummary.copy(operator = leg.operator, status = status, delayMinutes = delayMinutes),
                listOf(
                    TrainStop(testStation, scheduledDeparture = leg.departure),
                    TrainStop(journeyDestination, scheduledArrival = leg.arrival),
                ),
            ),
            DataFreshness.Fresh(Clock.System.now(), null),
        )
    }
    /** T8.7 fixture-only staging. Never compiled into normal export. */
    fun stageStationFailure() { repositories.stationResult = DataResult.Failure(DomainFailure.OFFLINE) }
    fun stageStationResults() { repositories.stationResult = DataResult.Data(listOf(testStation), DataFreshness.Fresh(stationTrainAt, stationTrainAt)) }
    fun stageRecentStation() { runBlocking { repositories.record(testStation) } }
    fun failRecentObservation() {
        repositories.failRecentObservation(IllegalStateException("T8.7 recency observation unavailable"))
    }
    fun stageRecentFailure(failed: Boolean) {
        repositories.recentHistoryFailure = if (failed) IllegalStateException("T8.7 recency unavailable") else null
    }
    fun stageStationBoard(direction: BoardKind, disrupted: Boolean) {
        val normal = testSummary.copy(id = testSummary.id.copy(provider = ProviderId("viaggiatreno"), serviceDate = RailwayTime.serviceDate(stationTrainAt)),
            scheduledTime = stationTrainAt, scheduledPlatform = "4", actualPlatform = "6", category = TrainCategory.REG,
            delayMinutes = if (disrupted) 12 else 0, operator = Operator("Trenitalia"))
        val rows = if (disrupted) listOf(normal, normal.copy(id = normal.id.copy(number = TrainNumber("456")), status = TrainStatus.CANCELLED, delayMinutes = null)) else listOf(normal)
        repositories.boardState.value = DataResult.Data(StationBoard(testStation, direction, rows), DataFreshness.Fresh(stationTrainAt, stationTrainAt - 1.minutes))
    }
    fun stageTrainRuns(multiple: Boolean) {
        val first = testSummary.copy(id = testSummary.id.copy(serviceDate = RailwayTime.serviceDate(stationTrainAt)), operator = Operator("Trenitalia"), scheduledDeparture = stationTrainAt,
            scheduledArrival = stationTrainAt + 4.hours)
        val second = first.copy(id = first.id.copy(provider = ProviderId("other"), origin = ExternalStationRef("napoli")),
            origin = Station(StationId("napoli"), "Napoli Centrale"), operator = Operator("Italo"))
        repositories.runsResult = DataResult.Data(if (multiple) listOf(first, second) else listOf(first), DataFreshness.Fresh(stationTrainAt, stationTrainAt))
    }
    fun stageTrainDetail(status: TrainStatus, delayMinutes: Int?, stale: Boolean, unknown: Boolean) {
        val summary = testSummary.copy(category = TrainCategory.FR, operator = Operator("Trenitalia"), status = status,
            delayMinutes = delayMinutes, scheduledDeparture = stationTrainAt, scheduledArrival = stationTrainAt + 4.hours,
            scheduledPlatform = "4", actualPlatform = "6")
        val stations = listOf(testStation, Station(StationId("firenze"), "Firenze Santa Maria Novella"),
            Station(StationId("bologna"), "Bologna Centrale"), Station(StationId("modena"), "Modena"),
            Station(StationId("milano"), "Milano Centrale"))
        val stops = stations.mapIndexed { index, station -> TrainStop(station,
            scheduledArrival = (stationTrainAt + index.hours).takeUnless { index == 0 },
            scheduledDeparture = (stationTrainAt + index.hours + 2.minutes).takeUnless { index == stations.lastIndex },
            actualArrival = (stationTrainAt + index.hours + 5.minutes).takeIf { index < 2 && index != 0 },
            actualDeparture = (stationTrainAt + index.hours + 7.minutes).takeIf { index < 2 },
            scheduledPlatform = "4", actualPlatform = "6", delayMinutes = delayMinutes,
            status = if (status == TrainStatus.CANCELLED) StopStatus.CANCELLED else if (index < 2) StopStatus.COMPLETED else StopStatus.SCHEDULED) }
        val fetched = stationTrainAt + 70.minutes // After the last observed Firenze passage.
        val freshness = when { unknown -> DataFreshness.Unknown
            stale -> DataFreshness.Stale(fetched, 1.hours, fetched - 1.minutes)
            else -> DataFreshness.Fresh(fetched, fetched - 1.minutes) }
        repositories.trainState.value = DataResult.Data(TrainRun(summary, stops,
            OperationalPosition("Firenze Santa Maria Novella", stationTrainAt + 1.hours)), freshness)
    }
    fun stageTrainFavoriteFailure() { repositories.favoriteFailure = IllegalStateException("T8.7 favorite failure") }
    /** T8.8 fixture-only monitoring staging. Never compiled into normal export. */
    fun stageActiveMonitor(number: String = "123", delayMinutes: Int? = 0, stale: Boolean = false, unknown: Boolean = false,
                           category: TrainCategory? = null, scheduledPlatform: String? = null, actualPlatform: String? = null) { runBlocking {
        val id = TrainRunId(ProviderId("viaggiatreno"), TrainNumber(number), ExternalStationRef("opaque-origin"),
            RailwayTime.serviceDate(stationTrainAt))
        val monitor = monitoring.createMonitor(id, MonitorThresholds(), stationTrainAt + 48.hours)
        val run = TrainRun(testSummary.copy(id = id, status = TrainStatus.RUNNING, delayMinutes = delayMinutes,
            scheduledDeparture = stationTrainAt, scheduledArrival = stationTrainAt + 4.hours,
            operator = Operator("Trenitalia"), category = category,
            scheduledPlatform = scheduledPlatform, actualPlatform = actualPlatform),
            listOf(TrainStop(testStation, scheduledDeparture = stationTrainAt)))
        val freshness = when {
            unknown -> DataFreshness.Unknown
            stale -> DataFreshness.Stale(stationTrainAt + 70.minutes, 1.hours, stationTrainAt + 69.minutes)
            else -> DataFreshness.Fresh(stationTrainAt, stationTrainAt)
        }
        monitoring.persistEvaluation(monitor.id, MonitoredTrainSnapshot(run, freshness, stationTrainAt),
            emptyList(), monitor.snapshotVersion, 0L)
    } }
    fun stageEndedMonitor(number: String = "123", status: TrainStatus = TrainStatus.ARRIVED) { runBlocking {
        val id = TrainRunId(ProviderId("test"), TrainNumber(number), ExternalStationRef("opaque-origin"),
            RailwayTime.serviceDate(stationTrainAt))
        val monitor = monitoring.createMonitor(id, MonitorThresholds(), stationTrainAt + 48.hours)
        val run = TrainRun(testSummary.copy(id = id, status = status),
            listOf(TrainStop(testStation, scheduledDeparture = stationTrainAt)))
        val event = if (status == TrainStatus.CANCELLED) TrainMonitorEvent.Cancelled(id) else TrainMonitorEvent.Arrived(id)
        monitoring.completeTerminally(monitor.id, MonitoredTrainSnapshot(run,
            DataFreshness.Fresh(stationTrainAt, stationTrainAt), stationTrainAt), listOf(event), stationTrainAt, 2L)
    } }
    fun completeActiveMonitorTerminally(number: String = "123", status: TrainStatus = TrainStatus.ARRIVED) { runBlocking {
        val id = TrainRunId(ProviderId("viaggiatreno"), TrainNumber(number), ExternalStationRef("opaque-origin"),
            RailwayTime.serviceDate(stationTrainAt))
        val monitor = monitoring.observeMonitor(id).first() ?: return@runBlocking
        val run = TrainRun(testSummary.copy(id = id, status = status),
            listOf(TrainStop(testStation, scheduledDeparture = stationTrainAt)))
        val event = if (status == TrainStatus.CANCELLED) TrainMonitorEvent.Cancelled(id) else TrainMonitorEvent.Arrived(id)
        monitoring.completeTerminally(monitor.id, MonitoredTrainSnapshot(run,
            DataFreshness.Fresh(stationTrainAt, stationTrainAt), stationTrainAt), listOf(event), stationTrainAt, 2L)
    } }
    fun stageDegradedMonitor(number: String = "123") { runBlocking {
        stageActiveMonitor(number)
        val id = TrainRunId(ProviderId("viaggiatreno"), TrainNumber(number), ExternalStationRef("opaque-origin"),
            RailwayTime.serviceDate(stationTrainAt))
        val monitor = monitoring.observeMonitor(id).first() ?: return@runBlocking
        monitoring.recordRefreshFailure(monitor.id, DomainFailure.OFFLINE, stationTrainAt)
    } }
    fun stageEndedTrainMonitor() { scope.launch {
        val id = (session.root.stack.value.active.instance as? RootComponent.Child.Detail)?.component?.id ?: return@launch
        val monitor = monitoring.createMonitor(id, MonitorThresholds(), stationTrainAt + 1.hours)
        val run = (trainRepository.observeTrain(id).first() as? DataResult.Data)?.value ?: return@launch
        monitoring.completeTerminally(monitor.id, MonitoredTrainSnapshot(run, DataFreshness.Unknown, stationTrainAt),
            listOf(TrainMonitorEvent.Arrived(id)), stationTrainAt, 2L)
    } }
    val trainRefreshes: Int get() = repositories.trainRefreshes
    val boardRefreshes: Int get() = repositories.boardRefreshes
    fun selectHome() { session.state.value.main!!.select(NativePrimaryArea.Search) }

    /** T8.9 fixture-only Saved staging. Never compiled into normal export. */
    fun stageFavoriteRoute() { runBlocking {
        repositories.setFavorite(FavoriteRoute.create(testStation, journeyDestination), true)
    } }
    fun stageFavoriteTrain(
        number: String = "123",
        originId: String? = testStation.id.value,
        originName: String? = testStation.name,
        operatorName: String? = "Trenitalia",
        destinationName: String? = "Milano Centrale",
    ) { runBlocking {
        repositories.setFavorite(FavoriteTrain.create(TrainNumber(number),
            originId?.takeIf { it.isNotBlank() }?.let(::StationId),
            operatorName?.takeIf { it.isNotBlank() }?.let(::Operator),
            originName?.takeIf { it.isNotBlank() },
            destinationName?.takeIf { it.isNotBlank() }), true)
    } }
    fun recordJourneyHistory(): JourneySearchHistoryEntry = runBlocking {
        repositories.recordSearch(JourneySearchRequest(testStation, journeyDestination, journeyRequest.at))
    }
    fun recordTrainHistory(
        number: String = "8640",
        serviceDate: LocalDate? = RailwayTime.serviceDate(stationTrainAt),
        originId: String? = testStation.id.value,
        originName: String? = testStation.name,
        operatorName: String? = "Trenitalia",
    ): TrainSearchHistoryEntry = runBlocking {
        repositories.recordTrainSearch(TrainNumber(number), serviceDate,
            TrainLookupIntent(TrainNumber(number),
                originId?.takeIf { it.isNotBlank() }?.let(::StationId),
                originName?.takeIf { it.isNotBlank() },
                operatorName?.takeIf { it.isNotBlank() }?.let(::Operator)))
    }
    fun stageSavedFavoriteFailure() { repositories.favoriteFailure = IllegalStateException("T8.9 favorite failure") }
    fun clearSavedFavoriteFailure() { repositories.favoriteFailure = null }
    fun stageFavoritesObservationFailure() {
        repositories.failFavoritesObservation(IllegalStateException("T8.9 favorites observation failure"))
    }
    fun stageHistoryObservationFailure() {
        repositories.failSearchHistoryObservation(IllegalStateException("T8.9 history observation failure"))
    }
    fun stageHistoryRemoveFailure() { repositories.removeSearchFailure = IllegalStateException("T8.9 history remove failure") }
    fun clearHistoryRemoveFailure() { repositories.removeSearchFailure = null }

    /**
     * Ordered teardown: stop this fixture's own drivers first (they only
     * touch in-memory fakes), then close the session/graph, whose driver
     * close serializes against in-flight background transactions above.
     */
    fun close() {
        scope.cancel()
        session.close()
    }
}

private class GatedSettingsPermission : NotificationPermission {
    val release = CompletableDeferred<EffectiveNotificationPermission>()

    override suspend fun isGranted(): Boolean = release.await() == EffectiveNotificationPermission.GRANTED

    override suspend fun request(): Boolean = isGranted()

    override suspend fun effective(): EffectiveNotificationPermission = release.await()
}
