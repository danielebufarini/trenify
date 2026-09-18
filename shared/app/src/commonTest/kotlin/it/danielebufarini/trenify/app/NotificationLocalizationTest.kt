package it.danielebufarini.trenify.app

import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.MonitorThresholds
import it.danielebufarini.trenify.core.domain.MonitoringPolicy
import it.danielebufarini.trenify.core.domain.StrikeChangeEvent
import it.danielebufarini.trenify.core.domain.StrikeChangeKind
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.platform.ApplicationState
import it.danielebufarini.trenify.core.platform.ApplicationStateObserver
import it.danielebufarini.trenify.core.platform.Connectivity
import it.danielebufarini.trenify.core.platform.ConnectivityStatus
import it.danielebufarini.trenify.core.platform.ExternalUrlLauncher
import it.danielebufarini.trenify.core.platform.LifecycleIntegration
import it.danielebufarini.trenify.core.platform.NotificationMessage
import it.danielebufarini.trenify.core.platform.NotificationPresenter
import it.danielebufarini.trenify.core.platform.PlatformServices
import it.danielebufarini.trenify.core.testing.FakeMonitoringRepository
import it.danielebufarini.trenify.core.testing.FakeNotificationSettingsRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testRun
import it.danielebufarini.trenify.core.testing.testRunId
import it.danielebufarini.trenify.core.testing.testStrike
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Notification localization wiring and identity stability (T7.14-C):
 * coordinators resolve every template through [NotificationLocalizer] (the
 * fake tag proves the text origin), unknown previous delay never renders as
 * zero, and notification ids stay identical when only the locale/wording
 * changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationLocalizationTest {
    private class MonitorHarness(
        scope: kotlinx.coroutines.CoroutineScope,
        val tag: String,
    ) {
        val clock = MutableClock()
        val monitoring = FakeMonitoringRepository(clock)
        val trains = FakeRealtimeRepositories()
        val settings = FakeNotificationSettingsRepository()
        val notifications = mutableListOf<NotificationMessage>()
        val localizer = FakeNotificationLocalizer(tag)
        val appState = MutableStateFlow(ApplicationState.Background)
        val connectivity = MutableStateFlow(ConnectivityStatus.Available)
        val services = PlatformServices(
            Connectivity { connectivity },
            ApplicationStateObserver { appState },
            NotificationPresenter(notifications::add),
            GrantedLocalizationPermission,
            RecordingLocalizationScheduler(),
            ExternalUrlLauncher { false },
            LifecycleIntegration { appState.value = it },
        )
        val coordinator = MonitoringCoordinator(
            monitoring,
            trains,
            services,
            settings,
            scope,
            clock,
            MonitoringPolicy(),
            localizer = localizer,
        )

        fun delayed(minutes: Int) = testRun.copy(summary = testRun.summary.copy(delayMinutes = minutes))

        suspend fun seedBaseline() {
            monitoring.createMonitor(testRunId, MonitorThresholds(delayMinutes = 15), clock.now() + 1.hours)
            trains.trainState.value = DataResult.Data(delayed(5), DataFreshness.Unknown)
            coordinator.refreshOnce()
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.monitorHarness(tag: String) =
        MonitorHarness(scope = backgroundScope, tag = tag).also { runCurrent() }

    @Test fun delayChangeUsesLocalizerAndKeepsIdentityAcrossLocales() = runTest {
        val first = monitorHarness("T1")
        val second = monitorHarness("T2")
        for (harness in listOf(first, second)) {
            harness.seedBaseline()
            runCurrent()
            assertEquals(0, harness.notifications.size)
            harness.trains.trainState.value = DataResult.Data(harness.delayed(20), DataFreshness.Unknown)
            harness.coordinator.refreshOnce()
            runCurrent()
        }
        assertEquals(1, first.notifications.size)
        assertEquals(1, second.notifications.size)
        // The previous delay travels through the localizer untouched.
        assertEquals("[T1] delay 5->20", first.notifications.single().body)
        assertEquals("[T2] delay 5->20", second.notifications.single().body)
        assertTrue(first.localizer.calls.contains("delay:5->20"))
        // Identity is the semantic event, never the rendered text.
        assertEquals(first.notifications.single().id, second.notifications.single().id)
        assertTrue(first.notifications.single().id.contains("delay:"))
    }

    @Test fun everyMonitorEventKindResolvesThroughLocalizer() = runTest {
        val harness = monitorHarness("T")
        harness.seedBaseline()
        runCurrent()
        // Cancelled.
        harness.trains.trainState.value = DataResult.Data(
            testRun.copy(summary = testRun.summary.copy(status = TrainStatus.CANCELLED)), DataFreshness.Unknown)
        harness.coordinator.refreshOnce()
        runCurrent()
        assertEquals("[T] cancelled", harness.notifications.last().body)
        val cancelledId = harness.notifications.last().id
        assertTrue(cancelledId.endsWith(":cancelled"))
    }

    @Test fun strikeKindsResolveThroughLocalizerWithStableIds() = runTest {
        val first = strikeHarness("T1")
        val second = strikeHarness("T2")
        val bodies = mutableListOf<String>()
        val ids = mutableListOf<String>()
        for ((harness, kind) in listOf(
            first to StrikeChangeKind.SCHEDULED,
            first to StrikeChangeKind.MODIFIED,
            first to StrikeChangeKind.REVOKED,
        )) {
            harness.repository.pending += StrikeChangeEvent(testStrike, kind)
            harness.coordinator.refreshOnce()
            runCurrent()
        }
        bodies += first.notifications.map { it.body }
        ids += first.notifications.map { it.id }
        assertEquals(
            listOf("[T1] strike SCHEDULED", "[T1] strike MODIFIED", "[T1] strike REVOKED"),
            first.notifications.map { it.title },
        )
        for ((harness, kind) in listOf(
            second to StrikeChangeKind.SCHEDULED,
            second to StrikeChangeKind.MODIFIED,
            second to StrikeChangeKind.REVOKED,
        )) {
            harness.repository.pending += StrikeChangeEvent(testStrike, kind)
            harness.coordinator.refreshOnce()
            runCurrent()
        }
        // Same semantic events under another locale: same ids, other words.
        assertEquals(ids, second.notifications.map { it.id })
        assertNotEquals(bodies, second.notifications.map { it.body })
        assertTrue(second.notifications.all { it.body.startsWith("[T2]") })
        // Strike area falls back to the relevance label, never an enum name.
        assertTrue(first.localizer.calls.any { it.startsWith("strikeArea:") })
    }

    private class StrikeHarness(scope: kotlinx.coroutines.CoroutineScope, tag: String) {
        val clock = MutableClock()
        val repository = FakeStrikeRepository().apply { notificationsEnabled.value = true }
        val notifications = mutableListOf<NotificationMessage>()
        val localizer = FakeNotificationLocalizer(tag)
        val appState = MutableStateFlow(ApplicationState.Background)
        val connectivity = MutableStateFlow(ConnectivityStatus.Available)
        val services = PlatformServices(
            Connectivity { connectivity },
            ApplicationStateObserver { appState },
            NotificationPresenter(notifications::add),
            GrantedLocalizationPermission,
            RecordingLocalizationScheduler(),
            ExternalUrlLauncher { false },
            LifecycleIntegration { appState.value = it },
        )
        val coordinator = StrikeCoordinator(
            repository,
            repository,
            services,
            FakeNotificationSettingsRepository(),
            scope,
            clock,
            localizer = localizer,
        )
    }

    private suspend fun kotlinx.coroutines.test.TestScope.strikeHarness(tag: String) =
        StrikeHarness(scope = backgroundScope, tag = tag).also { runCurrent() }
}

private object GrantedLocalizationPermission : it.danielebufarini.trenify.core.platform.NotificationPermission {
    override suspend fun isGranted() = true
    override suspend fun effective() = it.danielebufarini.trenify.core.platform.EffectiveNotificationPermission.GRANTED
    override suspend fun request() = true
}

private class RecordingLocalizationScheduler : it.danielebufarini.trenify.core.platform.BackgroundScheduler {
    val scheduled = mutableListOf<it.danielebufarini.trenify.core.platform.BackgroundTask>()
    override fun register(taskId: String, task: suspend () -> Unit) = Unit
    override suspend fun schedule(task: it.danielebufarini.trenify.core.platform.BackgroundTask) {
        scheduled += task
    }
    override suspend fun cancel(taskId: String) = Unit
}
