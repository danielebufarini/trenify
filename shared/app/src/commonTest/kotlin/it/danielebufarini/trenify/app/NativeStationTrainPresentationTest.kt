package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.testing.*
import it.danielebufarini.trenify.core.ui.RealtimeState
import it.danielebufarini.trenify.feature.station.*
import it.danielebufarini.trenify.feature.train.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.LocalDate
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class NativeStationTrainPresentationTest {
    private val at = Instant.parse("2026-09-14T09:00:00Z")
    private val originB = Station(StationId("origin-b"), "Same display name")
    private fun TestScope.search(repo: FakeRealtimeRepositories, opened: MutableList<TrainRunId>, expected: TrainLookupIntent? = null,
        date: LocalDate = testRunId.serviceDate): Pair<NativeTrainSearchPresentation, LifecycleRegistry> {
        val life = LifecycleRegistry()
        return NativeTrainSearchPresentation(TrainSearchComponent(DefaultComponentContext(life), FindTrainRuns(repo), opened::add,
            StandardTestDispatcher(testScheduler), initialNumber = "123", serviceDate = date, expected = expected, historyRepository = repo), NativeProjectionOwner()) to life
    }
    private fun TestScope.detail(repo: FakeRealtimeRepositories, monitors: FakeMonitoringRepository? = null, id: TrainRunId = testRunId): Pair<NativeTrainDetailPresentation, LifecycleRegistry> {
        val life = LifecycleRegistry()
        val component = TrainDetailComponent(DefaultComponentContext(life), id, ObserveTrainRun(repo), MutableStateFlow(false),
            StandardTestDispatcher(testScheduler), clock = MutableClock(at), observeMonitor = monitors?.let(::ObserveTrainMonitor),
            startMonitoring = monitors?.let(::StartTrainMonitoring), stopMonitoring = monitors?.let(::StopTrainMonitoring),
            removeEndedMonitor = monitors?.let(::RemoveEndedMonitor), favoritesRepository = repo,
            setMonitorNotifications = monitors?.let(::SetMonitorNotifications), updateMonitorThresholds = monitors?.let(::UpdateMonitorThresholds))
        return NativeTrainDetailPresentation(component, NativeProjectionOwner()) to life
    }
    private fun TestScope.factory(repo: FakeRealtimeRepositories) = AppComponentFactory(repo, repo, repo, repo,
        MutableStateFlow(false), StandardTestDispatcher(testScheduler), journeyRepository = FakeJourneyRepository())

    @Test fun stationQueryProjectsLoadingResultsStableIdentityAndUserOnlyEdit() = runTest {
        val repo = FakeRealtimeRepositories()
        val life = LifecycleRegistry()
        val opened = mutableListOf<Station>()
        val component = StationSearchComponent(DefaultComponentContext(life), SearchStations(repo), repo, opened::add, StandardTestDispatcher(testScheduler))
        val facade = NativeStationSearchPresentation(component, NativeProjectionOwner()) {}
        facade.editQuery("Roma"); assertTrue(facade.state.value.observation.loading)
        facade.editQuery("Roma") // published-value synchronization cannot restart debounce
        advanceTimeBy(1000); runCurrent()
        val row = facade.state.value.results.single()
        assertEquals(testStation.id.value, row.stationId)
        assertEquals(listOf("Roma"), repo.searches)
        facade.selectStation("wrong display name"); assertTrue(opened.isEmpty())
        facade.selectStation(row.stationId); assertEquals(listOf(testStation), opened)
        life.destroy()
    }
    @Test fun stationFailureRetryRecentRemovalAndFavoriteFailureRetainContent() = runTest {
        val repo = FakeRealtimeRepositories(); repo.record(testStation)
        val life = LifecycleRegistry()
        val facade = NativeStationSearchPresentation(StationSearchComponent(DefaultComponentContext(life), SearchStations(repo), repo, {}, StandardTestDispatcher(testScheduler), historyRepository = repo), NativeProjectionOwner()) {}
        runCurrent(); assertEquals(testStation.id.value, facade.state.value.recent.single().stationId)
        facade.removeRecent(testStation.id.value); runCurrent(); assertTrue(facade.state.value.recent.isEmpty())
        repo.stationResult = DataResult.Failure(DomainFailure.OFFLINE)
        facade.editQuery("Roma"); advanceTimeBy(1000); runCurrent()
        assertEquals(DomainFailure.OFFLINE, facade.state.value.observation.failure)
        repo.stationResult = DataResult.Data(listOf(testStation), DataFreshness.Fresh(at, at))
        facade.retry(); runCurrent(); assertEquals(1, facade.state.value.results.size)
        repo.favoriteFailure = IllegalStateException("write")
        facade.toggleFavorite(testStation.id.value); assertTrue(facade.state.value.results.single().pending)
        runCurrent(); assertTrue(facade.state.value.results.single().failed); assertEquals(1, facade.state.value.results.size)
        life.destroy()
    }
    @Test fun boardDirectionsProvenanceStatusExactNavigationAndNoNPlusOne() = runTest {
        val repo = FakeRealtimeRepositories()
        val row = testSummary.copy(id = testRunId.copy(provider = ProviderId("viaggiatreno")), scheduledTime = at,
            status = TrainStatus.CANCELLED, actualPlatform = "12", operator = Operator("Unrelated operator"))
        repo.boardState.value = DataResult.Data(StationBoard(testStation, BoardKind.DEPARTURES, listOf(row)), DataFreshness.Fresh(at, at))
        val opened = mutableListOf<TrainRunId>(); val life = LifecycleRegistry()
        val facade = NativeStationBoardPresentation(StationBoardComponent(DefaultComponentContext(life), testStation.id, ObserveStation(repo), LoadStationBoard(repo), repo,
            MutableStateFlow(false), opened::add, StandardTestDispatcher(testScheduler), clock = MutableClock(at)), NativeProjectionOwner())
        runCurrent(); assertEquals(BoardKind.DEPARTURES, facade.state.value.direction)
        assertEquals(testStation.id.value, facade.state.value.stationId)
        assertEquals(at.epochSeconds, facade.state.value.observation.provenance.fetchedAtEpochSeconds)
        assertEquals(at.epochSeconds, facade.state.value.observation.provenance.sourceTimestampEpochSeconds)
        assertEquals("ViaggiaTreno", facade.state.value.trains.single().providerName)
        assertEquals(TrainStatus.CANCELLED, facade.state.value.trains.single().status)
        assertNull(facade.state.value.trains.single().scheduledDepartureEpochSeconds)
        facade.openTrain(row.id.key); assertEquals(listOf(row.id), opened)
        facade.setDirection(BoardKind.ARRIVALS); runCurrent()
        assertEquals(BoardKind.ARRIVALS, facade.state.value.direction)
        assertEquals(0, repo.trainRefreshes)
        life.destroy()
    }
    @Test fun boardFailureRetainsRowsWithTruthfulDegradationAndUnknownTimestamps() = runTest {
        val state = RealtimeState(StationBoard(testStation, BoardKind.ARRIVALS, listOf(testSummary)), failure = DomainFailure.OFFLINE, stale = true, freshness = DataFreshness.Unknown)
        val observation = state.observation()
        assertTrue(observation.hasContent); assertTrue(observation.provenance.degraded)
        assertEquals(NativeRealtimeFreshness.Stale, observation.freshness)
        assertNull(observation.provenance.fetchedAtEpochSeconds); assertNull(observation.provenance.sourceTimestampEpochSeconds)
    }
    @Test fun numberOnlyUnambiguousRunStillOpensOnceAndRecordsOnce() = runTest {
        val repo = FakeRealtimeRepositories(); val opened = mutableListOf<TrainRunId>(); val (facade, life) = search(repo, opened)
        facade.submit(); runCurrent(); assertEquals(listOf(testRunId), opened)
        assertEquals(1, repo.observeSearchHistory().first().size)
        life.destroy()
    }
    @Test fun sameNumberDifferentOriginsAndOperatorsRequireExplicitExactSelection() = runTest {
        for (operator in listOf(false, true)) {
            val repo = FakeRealtimeRepositories()
            val second = testSummary.copy(id = testRunId.copy(origin = ExternalStationRef("other"), provider = ProviderId("other")),
                origin = if (operator) testStation else originB, operator = if (operator) Operator("Italo") else null)
            repo.runsResult = DataResult.Data(listOf(testSummary, second), DataFreshness.Unknown)
            val opened = mutableListOf<TrainRunId>(); val (facade, life) = search(repo, opened)
            facade.submit(); runCurrent(); assertTrue(opened.isEmpty())
            assertEquals(2, facade.state.value.runs.map { it.identity.key }.toSet().size)
            facade.selectRun(second.id.key); assertEquals(listOf(second.id), opened)
            assertEquals(1, repo.observeSearchHistory().first().size)
            life.destroy()
        }
    }
    @Test fun carriedOriginOperatorAndServiceDateRemainInSharedLookupAndHistory() = runTest {
        val repo = FakeRealtimeRepositories(); val expected = TrainLookupIntent(TrainNumber("123"), testStation.id, testStation.name, Operator("Trenitalia"))
        val date = LocalDate.parse("2026-09-14"); val opened = mutableListOf<TrainRunId>()
        val (facade, life) = search(repo, opened, expected, date)
        assertEquals(testStation.id.value, facade.state.value.expectedOriginId)
        assertEquals("Trenitalia", facade.state.value.expectedOperatorName)
        assertEquals(date.toString(), facade.state.value.serviceDate)
        facade.submit(); runCurrent()
        // Unknown operator cannot prove compatibility: explicit single-run picker.
        assertTrue(opened.isEmpty()); assertEquals(1, facade.state.value.runs.size)
        val entry = assertIs<TrainSearchHistoryEntry>(repo.observeSearchHistory().first().single())
        assertEquals(date, entry.serviceDate); assertEquals(expected, entry.lookupIntent())
        facade.editNumber("456"); assertNull(facade.state.value.expectedOriginId)
        facade.editNumber("123"); assertEquals(testStation.id.value, facade.state.value.expectedOriginId)
        life.destroy()
    }
    @Test fun incompatibleOriginOrOperatorSingleRunNeverSilentlyOpens() = runTest {
        for (expected in listOf(TrainLookupIntent(TrainNumber("123"), originB.id), TrainLookupIntent(TrainNumber("123"), operator = Operator("Italo")))) {
            val repo = FakeRealtimeRepositories(); repo.runsResult = DataResult.Data(listOf(testSummary.copy(operator = Operator("Trenitalia"))), DataFreshness.Unknown)
            val opened = mutableListOf<TrainRunId>(); val (facade, life) = search(repo, opened, expected)
            facade.submit(); runCurrent(); assertTrue(opened.isEmpty()); assertTrue(facade.state.value.noCompatibleService); assertTrue(facade.state.value.runs.isEmpty())
            life.destroy()
        }
    }
    @Test fun allTrainStatusesAndNullDelayStaySemantic() = runTest {
        for (status in TrainStatus.entries) {
            val repo = FakeRealtimeRepositories(); repo.trainState.value = DataResult.Data(testRun.copy(summary = testSummary.copy(status = status, delayMinutes = null)), DataFreshness.Unknown)
            val (facade, life) = detail(repo); runCurrent()
            assertEquals(status, facade.state.value.summary?.status); assertNull(facade.state.value.summary?.delayMinutes)
            life.destroy()
        }
    }
    @Test fun stopsUseSharedProgressWithUnknownBarrierAndRepeatedStationOccurrenceIdentity() = runTest {
        val repo = FakeRealtimeRepositories()
        val stops = listOf(StopStatus.COMPLETED, StopStatus.CANCELLED, StopStatus.UNKNOWN, StopStatus.SCHEDULED).map { TrainStop(testStation, status = it, scheduledArrival = at, actualPlatform = "3") }
        repo.trainState.value = DataResult.Data(testRun.copy(stops = stops), DataFreshness.Unknown)
        val (facade, life) = detail(repo); runCurrent()
        assertEquals(routeProgress(stops), facade.state.value.stops.map { it.progress })
        assertEquals(listOf(StopProgress.COMPLETED, StopProgress.CANCELLED, StopProgress.UNKNOWN, StopProgress.FUTURE), facade.state.value.stops.map { it.progress })
        assertEquals(4, facade.state.value.stops.map { it.key }.toSet().size)
        assertTrue(facade.state.value.stops.all { it.stationId == testStation.id.value && it.actualArrivalEpochSeconds == null })
        life.destroy()
    }
    @Test fun trainDetailPreservesStopScopedPlatformsAndOptionalActualValues() = runTest {
        val repo = FakeRealtimeRepositories()
        val tirano = Station(StationId("stop-tirano"), "Tirano")
        val monza = Station(StationId("stop-monza"), "Monza")
        val milano = Station(StationId("stop-milano"), "Milano Centrale")
        val stops = listOf(
            TrainStop(tirano, scheduledPlatform = "1"),
            TrainStop(monza, scheduledPlatform = "5", actualPlatform = "7"),
            TrainStop(milano),
        )
        repo.trainState.value = DataResult.Data(
            testRun.copy(
                summary = testSummary.copy(scheduledPlatform = "99", actualPlatform = "98"),
                stops = stops,
            ),
            DataFreshness.Fresh(at, at),
        )
        val (facade, life) = detail(repo); runCurrent()
        val projected = facade.state.value.stops
        assertEquals(listOf("1", "5", null), projected.map { it.scheduledPlatform })
        assertEquals(listOf(null, "7", null), projected.map { it.actualPlatform })
        assertEquals("5", projected.single { it.name == "Monza" }.scheduledPlatform)
        assertNull(projected.single { it.name == "Milano Centrale" }.actualPlatform)
        // The run-global fields remain internal domain data; the route-stop
        // projection above is the only source Train Details uses for platforms.
        assertEquals("99", facade.state.value.summary?.scheduledPlatform)
        assertEquals("98", facade.state.value.summary?.actualPlatform)
        life.destroy()
    }
    @Test fun detailGenuineProvenanceAndQualifiedPositionSurviveFreshStaleFailure() = runTest {
        val repo = FakeRealtimeRepositories()
        val id = testRunId.copy(provider = ProviderId("viaggiatreno"))
        repo.trainState.value = DataResult.Data(testRun.copy(summary = testSummary.copy(id = id, operator = Operator("Different operator")), position = OperationalPosition("Bologna", at)), DataFreshness.Fresh(at, at))
        val (facade, life) = detail(repo, id = id); runCurrent()
        assertEquals("ViaggiaTreno", facade.state.value.observation.provenance.providerName)
        assertEquals("ViaggiaTreno", facade.state.value.summary?.providerName)
        assertEquals(at.epochSeconds, facade.state.value.observation.provenance.fetchedAtEpochSeconds)
        assertEquals(at.epochSeconds, facade.state.value.observation.provenance.sourceTimestampEpochSeconds)
        assertEquals(at.epochSeconds, facade.state.value.positionObservedAtEpochSeconds)
        repo.trainState.value = DataResult.Failure(DomainFailure.OFFLINE); runCurrent()
        assertNotNull(facade.state.value.summary); assertEquals(NativeRealtimeFreshness.Stale, facade.state.value.observation.freshness)
        assertTrue(facade.state.value.observation.provenance.degraded)
        repo.trainState.value = DataResult.Data(testRun.copy(position = OperationalPosition("Bologna", null)), DataFreshness.Unknown); runCurrent()
        assertNull(facade.state.value.positionStationName); assertNull(facade.state.value.observation.provenance.fetchedAtEpochSeconds)
        assertEquals(NativeRealtimeFreshness.Unknown, facade.state.value.observation.freshness)
        life.destroy()
    }
    @Test fun favoriteIdentitySuccessAndFailureNeverHideDetailOrCreateMonitoring() = runTest {
        val repo = FakeRealtimeRepositories(); val monitors = FakeMonitoringRepository()
        val (facade, life) = detail(repo, monitors); runCurrent()
        facade.toggleFavorite(); assertTrue(facade.state.value.favorite.pending); runCurrent()
        assertEquals(testSummary.toFavoriteTrain().id.value, facade.state.value.favoriteIdentity)
        assertEquals(testSummary.toFavoriteTrain(), repo.favoriteTrains.value.single()); assertTrue(monitors.monitors.value.isEmpty())
        repo.favoriteFailure = IllegalStateException("save"); facade.toggleFavorite(); runCurrent()
        assertTrue(facade.state.value.favorite.failed); assertNotNull(facade.state.value.summary)
        life.destroy()
    }
    @Test fun monitoringActionsPreferencesAndRefreshRemainShared() = runTest {
        val repo = FakeRealtimeRepositories(); val monitors = FakeMonitoringRepository()
        val (facade, life) = detail(repo, monitors)
        assertFalse(facade.state.value.lifecycleResolved); facade.toggleMonitoring(); assertTrue(monitors.monitors.value.isEmpty())
        runCurrent(); facade.toggleMonitoring(); runCurrent(); assertTrue(facade.state.value.monitored)
        facade.editMonitorThreshold("18"); facade.saveMonitorThreshold(); runCurrent()
        assertEquals(18, monitors.monitors.value.single().thresholds.delayMinutes)
        facade.setMonitorNotifications(false); runCurrent(); assertEquals(false, facade.state.value.monitorNotificationsEnabled)
        facade.setMonitorEvent(MonitorEventKind.DELAY, false); runCurrent(); assertFalse(facade.state.value.notifyDelay)
        facade.refresh(); runCurrent(); assertEquals(1, repo.trainRefreshes)
        facade.toggleMonitoring(); runCurrent(); assertFalse(facade.state.value.monitored)
        life.destroy()
    }
    @Test fun terminalMonitorRemainsReadOnlyAndRetainedUntilExplicitRemoval() = runTest {
        val repo = FakeRealtimeRepositories(); val clock = MutableClock(at); val monitors = FakeMonitoringRepository(clock)
        val monitor = monitors.createMonitor(testRunId, MonitorThresholds(), at + 1.hours)
        monitors.completeTerminally(monitor.id, MonitoredTrainSnapshot(testRun, DataFreshness.Unknown, at), listOf(TrainMonitorEvent.Arrived(testRunId)), at, 2L)
        val (facade, life) = detail(repo, monitors); runCurrent()
        assertTrue(facade.state.value.ended); assertFalse(facade.state.value.canRefresh); assertFalse(facade.state.value.canToggleMonitoring)
        facade.refresh(); facade.toggleMonitoring(); facade.setMonitorNotifications(true); runCurrent()
        assertEquals(0, repo.trainRefreshes); assertEquals(at, monitors.monitors.value.single().endedAt)
        assertEquals(at + 24.hours, monitors.monitors.value.single().visibleUntil())
        facade.removeEndedMonitor(); runCurrent(); assertTrue(monitors.monitors.value.isEmpty())
        life.destroy()
    }
    @Test fun shellStationTrainBackChainsUseSameDecomposeChildrenAndCloseObsoleteFacade() = runTest {
        val repo = FakeRealtimeRepositories(); val life = LifecycleRegistry()
        val root = DefaultRootComponent(DefaultComponentContext(life), factory(repo)); val shell = createShellPresentation(root)
        shell.homeComponent()!!.openStations(); runCurrent()
        assertEquals(NativeDestination.StationSearch, shell.state.value.active.destination)
        val search = shell.state.value.active.stationSearch!!
        search.editQuery("Roma"); advanceTimeBy(1000); runCurrent(); search.selectStation(testStation.id.value); runCurrent()
        val board = shell.state.value.active.stationBoard!!; val boardIdentity = shell.state.value.active.identity
        board.openTrain(testRunId.key); runCurrent(); assertNotNull(shell.state.value.active.trainDetail)
        val departed = shell.state.value.active.trainDetail!!
        shell.back(); runCurrent(); assertEquals(boardIdentity, shell.state.value.active.identity); assertSame(board, shell.state.value.active.stationBoard)
        departed.toggleFavorite(); runCurrent(); assertTrue(repo.favoriteTrains.value.isEmpty())
        shell.back(); assertSame(search, shell.state.value.active.stationSearch)
        shell.back(); assertEquals(NativeDestination.Home, shell.state.value.active.destination)
        shell.homeComponent()!!.openTrainSearch(); runCurrent()
        val trainSearch = shell.state.value.active.trainSearch!!
        repo.runsResult = DataResult.Data(listOf(testSummary, testSummary.copy(id = testRunId.copy(origin = ExternalStationRef("b")))), DataFreshness.Unknown)
        trainSearch.editNumber("123"); trainSearch.submit(); runCurrent(); trainSearch.selectRun(testRunId.key); runCurrent()
        assertNotNull(shell.state.value.active.trainDetail); shell.back(); assertSame(trainSearch, shell.state.value.active.trainSearch)
        assertEquals(2, trainSearch.state.value.runs.size); shell.back(); assertEquals(NativeDestination.Home, shell.state.value.active.destination)
        shell.close(); life.destroy()
    }
    @Test fun restoredBoardResolvesCurrentCanonicalNameDirectionAndNativeDestination() = runTest {
        val repo = FakeRealtimeRepositories(); val life = LifecycleRegistry(); val keeper = StateKeeperDispatcher(null)
        val root = DefaultRootComponent(DefaultComponentContext(life, keeper), factory(repo)); val shell = createShellPresentation(root)
        shell.homeComponent()!!.openStations(); runCurrent()
        shell.state.value.active.stationSearch!!.editQuery("Roma"); advanceTimeBy(1000); runCurrent()
        shell.state.value.active.stationSearch!!.selectStation(testStation.id.value); runCurrent()
        shell.state.value.active.stationBoard!!.setDirection(BoardKind.ARRIVALS); runCurrent()
        val saved = keeper.save(); shell.close(); life.destroy()
        repo.stationDirectory.value = mapOf(testStation.id to testStation.copy(name = "Renamed"))
        val revivedLife = LifecycleRegistry(); val revived = DefaultRootComponent(DefaultComponentContext(revivedLife, StateKeeperDispatcher(saved)), factory(repo))
        val restored = createShellPresentation(revived); runCurrent()
        assertEquals(NativeDestination.StationBoard, restored.state.value.active.destination)
        assertEquals(BoardKind.ARRIVALS, restored.state.value.active.stationBoard!!.state.value.direction)
        assertEquals("Renamed", restored.state.value.active.stationBoard!!.state.value.station!!.name)
        restored.close(); revivedLife.destroy()
    }
}
