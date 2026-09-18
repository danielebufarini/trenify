package it.danielebufarini.trenify.stationtrain

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.arkivanov.essenty.statekeeper.StateKeeperDispatcher
import com.arkivanov.decompose.ComponentContext
import kotlinx.datetime.LocalDate
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import it.danielebufarini.trenify.app.*
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.testing.*
import it.danielebufarini.trenify.design.theme.TrenifyTheme
import it.danielebufarini.trenify.navigation.TrenifyAndroidShell
import it.danielebufarini.trenify.navigation.TrenifyShellLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class TrenifyStationTrainTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val at = Instant.parse("2026-09-14T09:00:00Z")
    private val life = LifecycleRegistry()
    private val repo = FakeRealtimeRepositories()
    private val monitoring = FakeMonitoringRepository()
    private lateinit var root: RootComponent
    private lateinit var shell: NativeShellPresentation

    private fun mount(keeper: StateKeeperDispatcher = StateKeeperDispatcher()) {
        compose.runOnUiThread {
            root = DefaultRootComponent(DefaultComponentContext(life, keeper), AppComponentFactory(repo, repo, repo, repo,
                MutableStateFlow(false), monitoringRepository = monitoring))
            shell = createShellPresentation(root)
            life.resume()
        }
        compose.setContent { TrenifyAndroidShell(root) }
    }
    private fun close() = compose.runOnUiThread { shell.close(); life.destroy() }
    private fun stationSearch() { compose.runOnUiThread { shell.homeComponent()!!.openStations() }; compose.onNodeWithTag("native-station-search").assertExists() }
    private fun selectStation() {
        compose.onNodeWithTag("station-query").performTextInput("Roma")
        waitTag("station-${testStation.id.value}")
        compose.onNodeWithTag("station-${testStation.id.value}").performClick()
        waitTag("native-station-board")
    }
    private fun trainSearch() { compose.runOnUiThread { shell.homeComponent()!!.openTrainSearch() }; waitTag("native-train-search") }
    private fun submit() { compose.onNodeWithTag("train-number").performTextInput("123"); compose.onNodeWithTag("train-submit").performClick() }
    private fun waitTag(tag: String) = compose.waitUntil(5000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun stageBoard(direction: BoardKind = BoardKind.DEPARTURES, disrupted: Boolean = false, runDate: LocalDate = testRunId.serviceDate) {
        val summary = testSummary.copy(scheduledTime = at, actualPlatform = "6", scheduledPlatform = "4", category = TrainCategory.REG,
            id = testRunId.copy(provider = ProviderId("viaggiatreno"), serviceDate = runDate), delayMinutes = if (disrupted) 12 else 0)
        repo.boardState.value = DataResult.Data(StationBoard(testStation, direction, if (disrupted) listOf(summary,
            summary.copy(id = summary.id.copy(number = TrainNumber("456")), status = TrainStatus.CANCELLED, delayMinutes = null)) else listOf(summary)), DataFreshness.Fresh(at, at - 1.minutes))
    }
    private fun stageDetail(status: TrainStatus = TrainStatus.RUNNING, delay: Int? = 0, unknown: Boolean = false, runId: TrainRunId = testRunId) {
        repo.trainState.value = DataResult.Data(testRun.copy(summary = testSummary.copy(id = runId, status = status, delayMinutes = delay,
            scheduledDeparture = at, scheduledArrival = at + 3.hours, category = TrainCategory.FR, operator = Operator("Trenitalia"), scheduledPlatform = "4", actualPlatform = "6"),
            stops = listOf("Roma Termini", "Firenze Santa Maria Novella", "Bologna Centrale", "Milano Centrale").mapIndexed { i, name ->
                TrainStop(Station(StationId("stop-$i"), name), scheduledArrival = at + i.hours,
                    actualArrival = (at + i.hours + 5.minutes).takeIf { i < 2 }, scheduledDeparture = at + i.hours + 2.minutes,
                    actualDeparture = (at + i.hours + 7.minutes).takeIf { i < 2 }, scheduledPlatform = "4", actualPlatform = "6",
                    status = if (status == TrainStatus.CANCELLED) StopStatus.CANCELLED else if (i < 2) StopStatus.COMPLETED else StopStatus.SCHEDULED)
            }, position = OperationalPosition("Firenze Santa Maria Novella", at + 1.hours)),
            if (unknown) DataFreshness.Unknown else if (delay != null && delay > 0) DataFreshness.Stale(at + 70.minutes, 1.hours, at + 69.minutes) else DataFreshness.Fresh(at + 70.minutes, at + 69.minutes))
    }
    private fun twoRuns(date: LocalDate = testRunId.serviceDate) { repo.runsResult = DataResult.Data(listOf(testSummary.copy(id = testRunId.copy(serviceDate = date), operator = Operator("Trenitalia")),
        testSummary.copy(id = testRunId.copy(origin = ExternalStationRef("napoli"), provider = ProviderId("other"), serviceDate = date),
            origin = Station(StationId("napoli"), "Napoli Centrale"), operator = Operator("Italo"))), DataFreshness.Unknown) }
    private fun scroll(tag: String) = compose.onNodeWithTag("native-train-detail").performScrollToNode(hasTestTag(tag))

    @Test fun stationLoadingResultsIdentitySelectionAndBack() {
        val gate = CompletableDeferred<Unit>(); repo.onSearch = { gate.await() }
        mount()
        try {
            stationSearch(); compose.onNodeWithTag("station-query").performTextInput("Roma")
            waitTag("station-search-loading"); compose.onNodeWithTag("station-search-loading").assertIsDisplayed()
            compose.runOnIdle { gate.complete(Unit) }; waitTag("station-${testStation.id.value}")
            compose.onNodeWithTag("station-${testStation.id.value}").performClick(); waitTag("native-station-board")
            compose.onNodeWithTag("shell-back").performClick(); compose.onNodeWithTag("station-query").assertTextContains("Roma")
            compose.onNodeWithTag("shell-back").performClick(); compose.onNodeWithTag("home-list").assertExists()
        } finally { close() }
    }
    @Test fun stationFailureRetryAndEmptyAreNative() {
        repo.stationResult = DataResult.Failure(DomainFailure.OFFLINE); mount()
        try {
            stationSearch(); compose.onNodeWithTag("station-query").performTextInput("Roma")
            waitTag("station-search-error"); compose.onNodeWithTag("station-search-retry").assertIsDisplayed().performClick()
            compose.runOnUiThread { repo.stationResult = DataResult.Data(emptyList(), DataFreshness.Unknown); shell.state.value.active.stationSearch!!.retry() }
            waitTag("station-search-empty"); compose.onNodeWithTag("station-search-empty").assertIsDisplayed()
        } finally { close() }
    }

    @Test fun cachedRecentStationsRemainVisibleAndActionableAfterObservationFailure() {
        kotlinx.coroutines.runBlocking { repo.record(testStation) }
        mount()
        try {
            stationSearch(); waitTag("station-${testStation.id.value}")
            repo.failRecentObservation(IllegalStateException("recency observation unavailable")); waitTag("recent-error")
            compose.onNodeWithTag("recent-error").assertIsDisplayed()
            compose.onNodeWithTag("station-${testStation.id.value}").assertIsDisplayed().performClick()
            waitTag("native-station-board")
        } finally { close() }
    }

    @Test fun recentFailureClearsAfterSuccessfulRecovery() {
        kotlinx.coroutines.runBlocking { repo.record(testStation) }
        mount()
        try {
            stationSearch(); waitTag("station-${testStation.id.value}")
            compose.runOnUiThread { repo.recentHistoryFailure = IllegalStateException("recency unavailable") }
            compose.onNodeWithTag("recent-clear").performClick(); waitTag("recent-error")
            compose.onNodeWithTag("recent-error").assertIsDisplayed()
            compose.onNodeWithTag("station-${testStation.id.value}").assertIsDisplayed().performClick()
            waitTag("native-station-board")
            compose.onNodeWithTag("shell-back").performClick(); waitTag("recent-error")
            compose.runOnUiThread { repo.recentHistoryFailure = null }
            compose.onNodeWithTag("recent-clear").performClick(); waitTag("recent-empty")
            compose.onNodeWithTag("recent-error").assertDoesNotExist()
            compose.onNodeWithTag("station-${testStation.id.value}").assertDoesNotExist()
        } finally { close() }
    }

    @Test fun emptyRecentFailureShowsErrorWithoutInventingRows() {
        mount()
        try {
            stationSearch()
            repo.failRecentObservation(IllegalStateException("recency observation unavailable")); waitTag("recent-error")
            compose.onNodeWithTag("recent-error").assertIsDisplayed()
            compose.onNodeWithTag("recent-empty").assertIsDisplayed()
            compose.onNodeWithTag("station-${testStation.id.value}").assertDoesNotExist()
        } finally { close() }
    }
    @Test fun boardDeparturesArrivalsDisruptionPlatformsProvenanceAndTrainBack() {
        stageBoard(disrupted = true); mount()
        try {
            stationSearch(); selectStation()
            compose.onNodeWithTag("board-chip-departures").assertIsSelected()
            compose.onNodeWithTag("board-fetched").assertIsDisplayed(); compose.onNodeWithTag("board-source-update").assertIsDisplayed()
            val key = testRunId.copy(provider = ProviderId("viaggiatreno")).key
            compose.onNodeWithTag("native-station-board", useUnmergedTree = true).performScrollToNode(hasTestTag("train-platform-$key"))
            compose.onNodeWithTag("train-platform-$key", useUnmergedTree = true).assertTextContains("6", substring = true)
            compose.onNodeWithTag("train-source-$key", useUnmergedTree = true).assertTextContains("ViaggiaTreno", substring = true)
            compose.onNodeWithTag("native-station-board", useUnmergedTree = true).performScrollToNode(hasTestTag("board-chip-arrivals"))
            compose.onNodeWithTag("board-chip-arrivals").performClick()
            compose.runOnUiThread { stageBoard(BoardKind.ARRIVALS, true) }
            compose.onNodeWithTag("board-chip-arrivals").assertIsSelected()
            compose.runOnUiThread { stageDetail(runId = testRunId.copy(provider = ProviderId("viaggiatreno"))) }
            compose.onNodeWithTag("native-station-board", useUnmergedTree = true).performScrollToNode(hasTestTag("run-$key"))
            compose.onNodeWithTag("run-$key").performClick(); waitTag("native-train-detail")
            compose.runOnIdle { assertEquals(key, shell.state.value.active.trainDetail!!.state.value.identity.key); assertEquals(0, repo.trainRefreshes) }
            // Provenance/freshness remains in the shared observation, but the
            // Train Details traveler surface no longer renders diagnostics.
            compose.onNodeWithTag("train-detail-source").assertDoesNotExist()
            compose.onNodeWithTag("train-detail-fetched").assertDoesNotExist()
            compose.onNodeWithTag("train-detail-source-update").assertDoesNotExist()
            compose.onNodeWithTag("train-detail-freshness").assertDoesNotExist()
            compose.onNodeWithTag("train-platform-$key").assertDoesNotExist()
            val intermediateStop = shell.state.value.active.trainDetail!!.state.value.stops[1]
            scroll("stop-platform-${intermediateStop.key}")
            compose.onNodeWithTag("stop-platform-${intermediateStop.key}-expected", useUnmergedTree = true)
                .assertTextContains("Expected platform: 4", substring = true)
            compose.onNodeWithTag("stop-platform-${intermediateStop.key}-actual", useUnmergedTree = true)
                .assertTextContains("Actual platform: 6", substring = true)
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            waitTag("native-station-board"); compose.onNodeWithTag("board-chip-arrivals").assertIsSelected()
        } finally { close() }
    }
    @Test fun sameNumberOriginsOperatorsPickerExactSelectionAndBack() {
        twoRuns(); mount()
        try {
            trainSearch(); submit(); waitTag("native-train-run-picker")
            val second = (repo.runsResult as DataResult.Data).value.last()
            compose.onNodeWithTag("native-train-search").performScrollToNode(hasTestTag("run-${second.id.key}"))
            compose.onNodeWithTag("run-${second.id.key}").performClick(); waitTag("native-train-detail")
            compose.runOnIdle { assertEquals(second.id, (root.stack.value.active.instance as RootComponent.Child.Detail).component.id) }
            compose.onNodeWithTag("shell-back").performClick(); waitTag("native-train-run-picker")
            compose.onNodeWithTag("shell-back").performClick(); compose.onNodeWithTag("home-list").assertExists()
        } finally { close() }
    }
    @Test fun detailUnknownDelayCancellationTimelineFavoriteFailureAndMonitoringForwarding() {
        stageDetail(TrainStatus.UNKNOWN, null, true); mount()
        try {
            trainSearch(); submit(); waitTag("native-train-detail")
            compose.runOnIdle {
                val observation = shell.state.value.active.trainDetail!!.state.value.observation
                assertEquals(NativeRealtimeFreshness.Unknown, observation.freshness)
                assertTrue(observation.provenance.stale)
            }
            compose.onNodeWithTag("train-detail-stale").assertDoesNotExist()
            compose.onNodeWithTag("train-detail-freshness").assertDoesNotExist()
            compose.onNodeWithTag("train-detail-source").assertDoesNotExist()
            compose.onNodeWithTag("train-detail-fetched").assertDoesNotExist()
            compose.onNodeWithTag("train-detail-source-update").assertDoesNotExist()
            scroll("train-status-${testRunId.key}")
            compose.onNodeWithTag("train-status-${testRunId.key}").assertContentDescriptionContains(compose.activity.getString(it.danielebufarini.trenify.R.string.st_unknown))
            scroll("train-favorite"); compose.onNodeWithTag("train-favorite").performClick()
            compose.runOnIdle { assertEquals(testSummary.copy(operator = Operator("Trenitalia")).toFavoriteTrain().id, repo.favoriteTrains.value.single().id); assertTrue(monitoring.monitors.value.isEmpty()) }
            compose.runOnUiThread { repo.favoriteFailure = IllegalStateException("write") }
            compose.onNodeWithTag("train-favorite").performClick(); waitTag("train-favorite-error")
            compose.onNodeWithTag("train-favorite-error").assertIsDisplayed()
            scroll("monitor-toggle"); compose.onNodeWithTag("monitor-toggle").performClick()
            compose.waitUntil(5000) { monitoring.monitors.value.isNotEmpty() }
            compose.runOnUiThread { stageDetail(delay = 12) }
            compose.onNodeWithTag("train-detail-fetched").assertDoesNotExist()
            compose.runOnIdle { assertEquals(listOf(StopProgress.COMPLETED, StopProgress.COMPLETED, StopProgress.NEXT, StopProgress.FUTURE), shell.state.value.active.trainDetail!!.state.value.stops.map { it.progress }) }
            val stop = shell.state.value.active.trainDetail!!.state.value.stops[2]
            scroll("stop-progress-${stop.key}"); compose.onNodeWithTag("stop-progress-${stop.key}").assertIsDisplayed()
            compose.runOnUiThread { stageDetail(TrainStatus.CANCELLED, null) }
            scroll("train-status-${testRunId.key}"); compose.onNodeWithTag("train-status-${testRunId.key}").assertContentDescriptionContains(compose.activity.getString(it.danielebufarini.trenify.R.string.st_cancelled))
        } finally { close() }
    }

    @Test fun endedMonitoringRemainsReadOnlyWithTwentyFourHourRetention() {
        stageDetail(TrainStatus.ARRIVED, 0)
        val ended = kotlinx.coroutines.runBlocking {
            val monitor = monitoring.createMonitor(testRunId, MonitorThresholds(), at + 1.hours)
            monitoring.completeTerminally(monitor.id, MonitoredTrainSnapshot(testRun, DataFreshness.Unknown, at),
                listOf(TrainMonitorEvent.Arrived(testRunId)), at, 2L)
            monitoring.monitors.value.single()
        }
        mount()
        try {
            trainSearch(); submit(); waitTag("native-train-detail")
            scroll("monitor-ended-label"); compose.onNodeWithTag("monitor-ended-label").assertIsDisplayed()
            compose.onNodeWithTag("train-refresh").assertDoesNotExist()
            compose.onNodeWithTag("monitor-toggle").assertDoesNotExist()
            compose.onNodeWithTag("monitor-preferences").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(at + 24.hours, ended.visibleUntil())
                shell.state.value.active.trainDetail!!.toggleMonitoring()
                shell.state.value.active.trainDetail!!.refresh()
                assertEquals(0, repo.trainRefreshes)
                assertEquals(at, monitoring.monitors.value.single().endedAt)
            }
        } finally { close() }
    }

    @Test fun restoredStationBoardUsesCanonicalIdArrivalsAndSharedBack() {
        val keeper = StateKeeperDispatcher(); val originalLife = LifecycleRegistry()
        val original = DefaultRootComponent(DefaultComponentContext(originalLife, keeper),
            AppComponentFactory(repo, repo, repo, repo, MutableStateFlow(false)))
        val main = (original.stack.value.active.instance as RootComponent.Child.Main).component
        main.select(MainTab.Stations)
        val stations = (main.pages.value.items.first { it.instance is MainComponent.Child.Stations }.instance as MainComponent.Child.Stations).component
        (stations.stack.value.active.instance as it.danielebufarini.trenify.feature.station.StationsTabComponent.Child.Search).component.select(testStation)
        (stations.stack.value.active.instance as it.danielebufarini.trenify.feature.station.StationsTabComponent.Child.Board).component.select(BoardKind.ARRIVALS)
        val saved = keeper.save(); originalLife.destroy()
        stageBoard(BoardKind.ARRIVALS)
        mount(StateKeeperDispatcher(saved))
        try {
            waitTag("native-station-board"); compose.onNodeWithTag("board-chip-arrivals").assertIsSelected()
            compose.runOnIdle { assertEquals(testStation.id.value, shell.state.value.active.stationBoard!!.state.value.stationId) }
            compose.onNodeWithTag("shell-back").performClick(); waitTag("native-station-search")
        } finally { close() }
    }

    @Test fun restoredTrainDetailBackReconstructsDatedDiscriminatedSearch() {
        val keeper = StateKeeperDispatcher(); val originalLife = LifecycleRegistry()
        val original = DefaultRootComponent(DefaultComponentContext(originalLife, keeper),
            AppComponentFactory(repo, repo, repo, repo, MutableStateFlow(false)))
        val main = (original.stack.value.active.instance as RootComponent.Child.Main).component
        main.openTrainSearch(TrainNumber("123"), testRunId.serviceDate,
            TrainLookupIntent(TrainNumber("123"), testStation.id, testStation.name, Operator("Trenitalia")))
        original.onNotificationDestination(it.danielebufarini.trenify.core.platform.NotificationDestination.Train(
            testRunId.provider.value, testRunId.number.value, testRunId.origin.value, testRunId.serviceDate.toString()))
        val saved = keeper.save(); originalLife.destroy()
        mount(StateKeeperDispatcher(saved))
        try {
            waitTag("native-train-detail")
            compose.runOnIdle { assertEquals(testRunId.key, shell.state.value.active.trainDetail!!.state.value.identity.key) }
            compose.onNodeWithTag("shell-back").performClick(); waitTag("native-train-search")
            compose.onNodeWithTag("train-number").assertTextContains("123")
            compose.onNodeWithTag("train-expected-origin").assertTextContains(testStation.name, substring = true)
            compose.onNodeWithTag("train-expected-operator").assertTextContains("Trenitalia", substring = true)
            compose.runOnIdle { assertEquals(testRunId.serviceDate.toString(), shell.state.value.active.trainSearch!!.state.value.serviceDate) }
        } finally { close() }
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val resolver = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TrenifyT87")
        })!!
        resolver.openOutputStream(uri)!!.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun deterministicNativeStationTrainReviewCaptures() {
        val captureRun = testRunId.copy(serviceDate = LocalDate.parse("2026-09-14"))
        stageBoard(runDate = captureRun.serviceDate); stageDetail(runId = captureRun)
        compose.runOnUiThread {
            root = DefaultRootComponent(DefaultComponentContext(life), object : AppComponentFactory(repo, repo, repo, repo, MutableStateFlow(false), monitoringRepository = monitoring) {
                override fun trainSearch(context: ComponentContext, onTrain: (TrainRunId) -> Unit,
                    initialNumber: String, serviceDate: LocalDate?, autoSearch: Boolean, expected: TrainLookupIntent?) =
                    super.trainSearch(context, onTrain, initialNumber, serviceDate ?: captureRun.serviceDate, autoSearch, expected)
            })
            shell = createShellPresentation(root); shell.homeComponent()!!.openStations()
        }
        var dark by mutableStateOf(false); var large by mutableStateOf(false)
        compose.setContent {
            val state by shell.state.collectAsState()
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                androidx.compose.ui.platform.LocalDensity.current.density, if (large) 2f else 1f)) {
                TrenifyTheme(darkTheme = dark, reduceMotion = true) {
                    TrenifyShellLayout(state, shell::select, shell::back, shell::openSettings) { NativeStationTrainEntry(state.active) }
                }
            }
        }
        try {
            compose.onNodeWithTag("station-query").performTextInput("Roma"); waitTag("station-${testStation.id.value}")
            capture("android-station-search")
            compose.onNodeWithTag("station-${testStation.id.value}").performClick(); waitTag("native-station-board")
            capture("android-board-departures")
            compose.onNodeWithTag("board-chip-arrivals").performClick(); compose.runOnUiThread { stageBoard(BoardKind.ARRIVALS, runDate = captureRun.serviceDate) }
            compose.onNodeWithTag("board-chip-arrivals").assertIsSelected(); capture("android-board-arrivals")
            compose.runOnUiThread { stageBoard(BoardKind.ARRIVALS, true, runDate = captureRun.serviceDate) }
            compose.onNodeWithTag("native-station-board", useUnmergedTree = true).performScrollToNode(hasTestTag("train-delay")); capture("android-board-disruption")
            val cancelled = shell.state.value.active.stationBoard!!.state.value.trains.last()
            compose.onNodeWithTag("native-station-board", useUnmergedTree = true).performScrollToNode(hasTestTag("train-status-${cancelled.identity.key}"))
            capture("android-board-cancelled")
            compose.runOnUiThread { shell.back(); shell.back(); shell.homeComponent()!!.openTrainSearch() }
            waitTag("native-train-search"); capture("android-train-search")
            twoRuns(captureRun.serviceDate); submit(); waitTag("native-train-run-picker")
            compose.onNodeWithTag("native-train-search").performScrollToNode(hasTestTag("run-${(repo.runsResult as DataResult.Data).value.last().id.key}")); capture("android-train-picker")
            compose.runOnUiThread { shell.state.value.active.trainSearch!!.selectRun(captureRun.key) }
            waitTag("native-train-detail"); capture("android-train-normal")
            compose.runOnUiThread { stageDetail(delay = 12, runId = captureRun) }; compose.onNodeWithTag("train-delay", useUnmergedTree = true).assertTextContains("12", substring = true)
            capture("android-train-delayed")
            compose.onNodeWithTag("native-train-detail").performScrollToIndex(4); capture("android-train-route")
            compose.runOnIdle { dark = true }; scroll("train-status-${captureRun.key}"); capture("android-train-dark")
            compose.runOnIdle { large = true }; compose.onNodeWithTag("native-train-detail").assertIsDisplayed(); capture("android-train-large-text")
            scroll("train-favorite"); compose.onNodeWithTag("train-favorite").assertHeightIsAtLeast(48.dp)
            scroll("train-detail-stops")
            compose.onNodeWithTag("train-detail-stops").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Heading, Unit))
            compose.onNodeWithTag("native-train-detail").performScrollToIndex(5)
            capture("android-train-large-text-route")
        } finally { close() }
    }
}
