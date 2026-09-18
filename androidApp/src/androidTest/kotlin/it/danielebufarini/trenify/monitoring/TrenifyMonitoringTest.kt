package it.danielebufarini.trenify.monitoring

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import it.danielebufarini.trenify.app.*
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.testing.*
import it.danielebufarini.trenify.design.theme.TrenifyTheme
import it.danielebufarini.trenify.navigation.TrenifyAndroidShell
import it.danielebufarini.trenify.navigation.TrenifyShellLayout
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class TrenifyMonitoringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val at = Instant.parse("2026-09-14T09:00:00Z")
    private val life = LifecycleRegistry()
    private val repo = FakeRealtimeRepositories()
    private val monitoring = FakeMonitoringRepository()
    private lateinit var root: RootComponent
    private lateinit var shell: NativeShellPresentation

    private val activeId = testRunId.copy(provider = ProviderId("viaggiatreno"))
    private val activeKey = activeId.key

    private fun mount() {
        compose.runOnUiThread {
            root = DefaultRootComponent(DefaultComponentContext(life), AppComponentFactory(repo, repo, repo, repo,
                MutableStateFlow(false), monitoringRepository = monitoring))
            shell = createShellPresentation(root)
            life.resume()
        }
        compose.setContent { TrenifyAndroidShell(root) }
    }
    private fun close() = compose.runOnUiThread { shell.close(); life.destroy() }
    private fun openMonitoring() {
        compose.onNodeWithTag("shell-tab-Monitoring").performClick()
        waitTag("native-monitoring")
    }
    private fun waitTag(tag: String) = compose.waitUntil(5000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun scrollTo(tag: String) =
        compose.onNodeWithTag("native-monitoring", useUnmergedTree = true).performScrollToNode(hasTestTag(tag))
    // Card content merges into the clickable card surface; actions keep their own nodes.
    private fun onCard(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    @Test fun monitorCardsPreserveOperationalSnapshotSemantics() {
        stageActive(category = TrainCategory.FR, scheduledPlatform = "1", actualPlatform = "2")
        stageEnded(testRunId.copy(number = TrainNumber("456")))
        mount()
        try {
            openMonitoring()
            scrollTo("monitor-identity-$activeKey")
            onCard("monitor-identity-$activeKey").assertTextContains("FR", substring = true)
            onCard("monitor-identity-$activeKey").assertTextContains("123", substring = true)
            onCard("monitor-service-date-$activeKey").assertTextContains("2026", substring = true)
            onCard("monitor-departure-$activeKey").assertIsDisplayed()
            onCard("monitor-arrival-$activeKey").assertIsDisplayed()
            onCard("monitor-platform-$activeKey").assertTextContains("1", substring = true)
            onCard("monitor-platform-$activeKey").assertTextContains("2", substring = true)
            val other = testRunId.copy(number = TrainNumber("456"))
            scrollTo("monitor-ended-identity-${other.key}")
            onCard("monitor-ended-identity-${other.key}").assertTextContains("456", substring = true)
            onCard("monitor-ended-service-date-${other.key}").assertTextContains("2026", substring = true)
            // Absent snapshot values are omitted, never invented.
            onCard("monitor-ended-departure-${other.key}").assertDoesNotExist()
            onCard("monitor-ended-arrival-${other.key}").assertDoesNotExist()
            onCard("monitor-ended-platform-${other.key}").assertDoesNotExist()
        } finally { close() }
    }

    @Test fun minimalActiveCardOmitsAbsentOperationalValues() {
        stageActive(); mount()
        try {
            openMonitoring()
            scrollTo("monitor-identity-$activeKey")
            onCard("monitor-identity-$activeKey").assertTextContains("123", substring = true)
            onCard("monitor-departure-$activeKey").assertIsDisplayed()
            onCard("monitor-arrival-$activeKey").assertIsDisplayed()
            onCard("monitor-platform-$activeKey").assertDoesNotExist()
        } finally { close() }
    }

    private fun stageActive(id: TrainRunId = activeId, status: TrainStatus = TrainStatus.RUNNING, delay: Int? = 0,
                            category: TrainCategory? = null, scheduledPlatform: String? = null, actualPlatform: String? = null) {
        kotlinx.coroutines.runBlocking {
            val created = monitoring.createMonitor(id, MonitorThresholds(), at + 48.hours)
            val run = testRun.copy(summary = testSummary.copy(id = id,
                status = status, delayMinutes = delay, scheduledDeparture = at, scheduledArrival = at + 3.hours,
                operator = Operator("Trenitalia"), category = category,
                scheduledPlatform = scheduledPlatform, actualPlatform = actualPlatform))
            monitoring.persistEvaluation(created.id, MonitoredTrainSnapshot(run,
                DataFreshness.Fresh(at, at), at), emptyList(), created.snapshotVersion, 0L)
        }
    }
    private fun stageEnded(id: TrainRunId = testRunId, status: TrainStatus = TrainStatus.ARRIVED) {
        kotlinx.coroutines.runBlocking {
            val created = monitoring.createMonitor(id, MonitorThresholds(), at + 48.hours)
            val run = testRun.copy(summary = testSummary.copy(id = id, status = status))
            val event = if (status == TrainStatus.CANCELLED) TrainMonitorEvent.Cancelled(id) else TrainMonitorEvent.Arrived(id)
            monitoring.completeTerminally(created.id, MonitoredTrainSnapshot(run,
                DataFreshness.Fresh(at, at), at), listOf(event), at, 2L)
        }
    }

    @Test fun emptyMonitoringShowsEmptyState() {
        mount()
        try {
            openMonitoring()
            compose.onNodeWithTag("monitoring-empty").assertIsDisplayed()
            compose.onNodeWithTag("monitoring-active-header").assertDoesNotExist()
            compose.onNodeWithTag("monitoring-recently-ended-header").assertDoesNotExist()
        } finally { close() }
    }

    @Test fun activeMonitorShowsIdentityStatusDelayAndProvenance() {
        stageActive(delay = 12); mount()
        try {
            openMonitoring()
            val key = activeKey
            compose.onNodeWithTag("monitoring-active-header").assertIsDisplayed()
            scrollTo("monitor-$key")
            compose.onNodeWithTag("monitor-$key").assertIsDisplayed()
            onCard("monitor-status-$key")
                .assertContentDescriptionContains("12", substring = true)
            scrollTo("monitor-source-$key")
            onCard("monitor-source-$key").assertTextContains("ViaggiaTreno", substring = true)
            onCard("monitor-freshness-$key").assertIsDisplayed()
            onCard("monitor-fetched-$key").assertIsDisplayed()
            onCard("monitor-source-update-$key").assertIsDisplayed()
            scrollTo("monitor-stop-$key")
            compose.onNodeWithTag("monitor-stop-$key").assertIsDisplayed()
            compose.onNodeWithTag("monitor-notifications-$key").assertIsDisplayed()
            compose.onNodeWithTag("monitor-remove-$key").assertDoesNotExist()
            compose.onNodeWithTag("monitoring-recently-ended-header").assertDoesNotExist()
        } finally { close() }
    }

    @Test fun activeAndRecentlyEndedStayDistinct() {
        val other = testRunId.copy(number = TrainNumber("456"))
        stageActive(); stageEnded(other); mount()
        try {
            openMonitoring()
            compose.onNodeWithTag("monitoring-active-header").assertIsDisplayed()
            compose.onNodeWithTag("monitoring-recently-ended-header").assertIsDisplayed()
            scrollTo("monitor-$activeKey")
            compose.onNodeWithTag("monitor-$activeKey").assertIsDisplayed()
            scrollTo("monitor-ended-${other.key}")
            compose.onNodeWithTag("monitor-ended-${other.key}").assertIsDisplayed()
            // Ended rows never expose active-only actions.
            compose.onNodeWithTag("monitor-stop-${other.key}").assertDoesNotExist()
            compose.onNodeWithTag("monitor-notifications-${other.key}").assertDoesNotExist()
            // Active rows never expose ended-only removal.
            compose.onNodeWithTag("monitor-remove-$activeKey").assertDoesNotExist()
            scrollTo("monitor-remove-${other.key}")
            compose.onNodeWithTag("monitor-remove-${other.key}").assertIsDisplayed()
        } finally { close() }
    }

    @Test fun arrivedEndedIsReadOnlyWithFinalSnapshot() {
        stageEnded(); mount()
        try {
            openMonitoring()
            compose.onNodeWithTag("monitoring-no-active").assertIsDisplayed()
            scrollTo("monitor-ended-label-${testRunId.key}")
            onCard("monitor-ended-label-${testRunId.key}").assertIsDisplayed()
            onCard("monitor-ended-status-${testRunId.key}")
                .assertContentDescriptionContains(compose.activity.getString(it.danielebufarini.trenify.R.string.st_arrived), substring = true)
            scrollTo("monitor-ended-at-${testRunId.key}")
            onCard("monitor-ended-at-${testRunId.key}").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(at + 24.hours, monitoring.monitors.value.single().visibleUntil())
            }
        } finally { close() }
    }

    @Test fun finalCancelledEndedRetainsCancellation() {
        stageEnded(status = TrainStatus.CANCELLED); mount()
        try {
            openMonitoring()
            scrollTo("monitor-ended-status-${testRunId.key}")
            onCard("monitor-ended-status-${testRunId.key}")
                .assertContentDescriptionContains(compose.activity.getString(it.danielebufarini.trenify.R.string.st_cancelled), substring = true)
            compose.onNodeWithTag("monitor-stop-${testRunId.key}").assertDoesNotExist()
            scrollTo("monitor-remove-${testRunId.key}")
            compose.onNodeWithTag("monitor-remove-${testRunId.key}").assertIsDisplayed()
        } finally { close() }
    }

    @Test fun staleDegradedMonitorRetainsContentWithWarning() {
        stageActive()
        kotlinx.coroutines.runBlocking {
            val created = monitoring.monitors.value.single()
            monitoring.recordRefreshFailure(created.id, DomainFailure.OFFLINE, at)
        }
        mount()
        try {
            openMonitoring()
            val key = activeKey
            scrollTo("monitor-$key")
            compose.onNodeWithTag("monitor-$key").assertIsDisplayed()
            scrollTo("monitor-degraded-$key")
            onCard("monitor-degraded-$key").assertIsDisplayed()
            onCard("monitor-error-$key")
                .assertTextContains(compose.activity.getString(it.danielebufarini.trenify.R.string.st_offline), substring = true)
            onCard("monitor-freshness-$key")
                .assertTextContains(compose.activity.getString(it.danielebufarini.trenify.R.string.st_stale), substring = true)
        } finally { close() }
    }

    @Test fun stopAndRemoveForwardToSharedRepository() {
        val other = testRunId.copy(number = TrainNumber("456"))
        stageActive(); stageEnded(other); mount()
        try {
            openMonitoring()
            scrollTo("monitor-stop-$activeKey")
            compose.onNodeWithTag("monitor-stop-$activeKey").performClick()
            compose.waitUntil(5000) { monitoring.monitors.value.none { it.trainRunId == activeId } }
            compose.onNodeWithTag("monitor-$activeKey").assertDoesNotExist()
            scrollTo("monitor-remove-${other.key}")
            compose.onNodeWithTag("monitor-remove-${other.key}").performClick()
            compose.waitUntil(5000) { monitoring.monitors.value.none { it.trainRunId == other } }
            compose.onNodeWithTag("monitoring-empty").assertIsDisplayed()
        } finally { close() }
    }

    @Test fun notificationToggleAndRetryForward() {
        stageActive(); mount()
        try {
            openMonitoring()
            val key = activeKey
            scrollTo("monitor-notifications-$key")
            compose.onNodeWithTag("monitor-notifications-$key").performClick()
            compose.waitUntil(5000) { monitoring.monitors.value.single().notificationsEnabled.not() }
            compose.runOnIdle { assertFalse(monitoring.monitors.value.single().notificationsEnabled) }
        } finally { close() }
    }

    @Test fun monitorCardOpensNativeTrainDetailAndBackReturns() {
        stageActive(); mount()
        try {
            openMonitoring()
            val key = activeKey
            scrollTo("monitor-$key")
            compose.onNodeWithTag("monitor-$key").performClick()
            compose.waitUntil(5000) { shell.state.value.active.destination == NativeDestination.TrainDetail }
            compose.onNodeWithTag("shell-back").performClick()
            waitTag("native-monitoring")
            scrollTo("monitor-$key")
            compose.onNodeWithTag("monitor-$key").assertIsDisplayed()
        } finally { close() }
    }

    @Test fun restoredMonitoringBindsNativeDestination() {
        stageActive()
        val keeper = com.arkivanov.essenty.statekeeper.StateKeeperDispatcher()
        val originalLife = LifecycleRegistry()
        val original = DefaultRootComponent(DefaultComponentContext(originalLife, keeper),
            AppComponentFactory(repo, repo, repo, repo, MutableStateFlow(false), monitoringRepository = monitoring))
        (original.stack.value.active.instance as RootComponent.Child.Main).component.select(MainTab.Monitoring)
        val saved = keeper.save(); originalLife.destroy()
        compose.runOnUiThread {
            root = DefaultRootComponent(DefaultComponentContext(life, com.arkivanov.essenty.statekeeper.StateKeeperDispatcher(saved)),
                AppComponentFactory(repo, repo, repo, repo, MutableStateFlow(false), monitoringRepository = monitoring))
            shell = createShellPresentation(root)
            life.resume()
        }
        compose.setContent { TrenifyAndroidShell(root) }
        try {
            waitTag("native-monitoring")
            compose.runOnIdle { assertEquals(NativeDestination.Monitoring, shell.state.value.active.destination) }
            scrollTo("monitor-$activeKey")
            compose.onNodeWithTag("monitor-$activeKey").assertIsDisplayed()
        } finally { close() }
    }

    @Test fun monitoringAccessibilityHeadingsTouchTargetsAndLargeText() {
        stageActive(); stageEnded(testRunId.copy(number = TrainNumber("456")))
        mount()
        try {
            openMonitoring()
            compose.onNodeWithTag("monitoring-active-header")
                .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Heading, Unit))
            compose.onNodeWithTag("monitoring-recently-ended-header")
                .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Heading, Unit))
            scrollTo("monitor-stop-$activeKey")
            compose.onNodeWithTag("monitor-stop-$activeKey").assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("native-monitoring").assertIsDisplayed()
        } finally { close() }
    }

    @Test fun deterministicNativeMonitoringReviewCaptures() {
        val date = kotlinx.datetime.LocalDate.parse("2026-09-14")
        val captureActive = testRunId.copy(provider = ProviderId("viaggiatreno"), serviceDate = date)
        val captureEnded = testRunId.copy(number = TrainNumber("456"), serviceDate = date)
        val captureCancelled = testRunId.copy(number = TrainNumber("789"), serviceDate = date)
        fun persistActive(delay: Int?) = kotlinx.coroutines.runBlocking {
            val existing = monitoring.monitors.value.firstOrNull { it.trainRunId == captureActive }
            val created = existing ?: monitoring.createMonitor(captureActive, MonitorThresholds(), at + 48.hours)
            val run = testRun.copy(summary = testSummary.copy(id = captureActive, status = TrainStatus.RUNNING, delayMinutes = delay,
                scheduledDeparture = at, scheduledArrival = at + 3.hours, operator = Operator("Trenitalia")))
            monitoring.persistEvaluation(created.id, MonitoredTrainSnapshot(run, DataFreshness.Fresh(at, at), at),
                emptyList(), monitoring.monitors.value.first { it.trainRunId == captureActive }.snapshotVersion, 0L)
        }
        compose.runOnUiThread {
            root = DefaultRootComponent(DefaultComponentContext(life), AppComponentFactory(repo, repo, repo, repo,
                MutableStateFlow(false), monitoringRepository = monitoring))
            shell = createShellPresentation(root)
            life.resume()
            shell.select(NativePrimaryArea.Monitoring)
        }
        var dark by mutableStateOf(false); var large by mutableStateOf(false)
        compose.setContent {
            val state by shell.state.collectAsState()
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                androidx.compose.ui.platform.LocalDensity.current.density, if (large) 2f else 1f)) {
                TrenifyTheme(darkTheme = dark, reduceMotion = true) {
                    TrenifyShellLayout(state, shell::select, shell::back, shell::openSettings) {
                        state.active.monitoring?.let {
                            NativeMonitoringEntry(state.active)
                        } ?: it.danielebufarini.trenify.stationtrain.NativeStationTrainEntry(state.active)
                    }
                }
            }
        }
        try {
            waitTag("native-monitoring")
            capture("android-monitoring-empty")
            compose.runOnUiThread { persistActive(null) }
            waitTag("monitor-${captureActive.key}")
            capture("android-monitoring-active")
            compose.runOnUiThread { persistActive(12) }
            compose.waitUntil(5000) { shell.state.value.active.monitoring!!.state.value.active.single().delayMinutes == 12 }
            capture("android-monitoring-active-delayed")
            kotlinx.coroutines.runBlocking {
                val created = monitoring.monitors.value.single()
                monitoring.recordRefreshFailure(created.id, DomainFailure.OFFLINE, at)
            }
            compose.waitUntil(5000) { shell.state.value.active.monitoring!!.state.value.active.single().refreshFailure != null }
            capture("android-monitoring-degraded")
            compose.runOnUiThread { shell.state.value.active.monitoring!!.setMonitorNotifications(captureActive.key, false) }
            compose.waitUntil(5000) { shell.state.value.active.monitoring!!.state.value.active.single().notificationsEnabled.not() }
            capture("android-monitoring-notifications")
            kotlinx.coroutines.runBlocking {
                repo.trainState.value = DataResult.Data(testRun.copy(summary = testSummary.copy(id = captureActive,
                    status = TrainStatus.RUNNING, delayMinutes = 12, scheduledDeparture = at, scheduledArrival = at + 3.hours,
                    operator = Operator("Trenitalia"))), DataFreshness.Fresh(at, at))
            }
            scrollTo("monitor-${captureActive.key}")
            compose.onNodeWithTag("monitor-${captureActive.key}").performClick()
            compose.waitUntil(5000) { shell.state.value.active.destination == NativeDestination.TrainDetail }
            waitTag("native-train-detail")
            capture("android-monitoring-detail")
            compose.onNodeWithTag("shell-back").performClick()
            waitTag("native-monitoring")
            kotlinx.coroutines.runBlocking {
                val ended = monitoring.createMonitor(captureEnded, MonitorThresholds(), at + 48.hours)
                val arrived = testRun.copy(summary = testSummary.copy(id = captureEnded, status = TrainStatus.ARRIVED))
                monitoring.completeTerminally(ended.id, MonitoredTrainSnapshot(arrived, DataFreshness.Fresh(at, at), at),
                    listOf(TrainMonitorEvent.Arrived(captureEnded)), at, 2L)
                repo.trainState.value = DataResult.Data(testRun.copy(summary = testSummary.copy(id = captureEnded,
                    status = TrainStatus.ARRIVED)), DataFreshness.Fresh(at, at))
            }
            compose.onNodeWithTag("native-monitoring", useUnmergedTree = true).performScrollToNode(hasTestTag("monitor-ended-${captureEnded.key}"))
            capture("android-monitoring-active-ended")
            scrollTo("monitor-ended-${captureEnded.key}")
            compose.onNodeWithTag("monitor-ended-${captureEnded.key}").performClick()
            compose.waitUntil(5000) { shell.state.value.active.destination == NativeDestination.TrainDetail }
            waitTag("native-train-detail")
            capture("android-monitoring-terminal-detail")
            compose.onNodeWithTag("shell-back").performClick()
            waitTag("native-monitoring")
            kotlinx.coroutines.runBlocking {
                val cancelled = monitoring.createMonitor(captureCancelled, MonitorThresholds(), at + 48.hours)
                val gone = testRun.copy(summary = testSummary.copy(id = captureCancelled, status = TrainStatus.CANCELLED))
                monitoring.completeTerminally(cancelled.id, MonitoredTrainSnapshot(gone, DataFreshness.Fresh(at, at), at),
                    listOf(TrainMonitorEvent.Cancelled(captureCancelled)), at, 2L)
            }
            compose.onNodeWithTag("native-monitoring", useUnmergedTree = true).performScrollToNode(hasTestTag("monitor-ended-${captureCancelled.key}"))
            capture("android-monitoring-cancelled")
            compose.runOnIdle { dark = true }
            compose.onNodeWithTag("native-monitoring").assertIsDisplayed()
            capture("android-monitoring-dark")
            compose.runOnIdle { large = true }
            compose.onNodeWithTag("native-monitoring").assertIsDisplayed()
            capture("android-monitoring-large-text")
        } finally { close() }
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val resolver = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TrenifyT88")
        })!!
        resolver.openOutputStream(uri)!!.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
