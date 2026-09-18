package it.danielebufarini.trenify.app

import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.MonitorThresholds
import it.danielebufarini.trenify.core.domain.MonitoredTrainSnapshot
import it.danielebufarini.trenify.core.domain.MonitoringPolicy
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Station
import it.danielebufarini.trenify.core.model.StationId
import it.danielebufarini.trenify.core.model.StopStatus
import it.danielebufarini.trenify.core.model.TrainStop
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.platform.ApplicationState
import it.danielebufarini.trenify.core.platform.ApplicationStateObserver
import it.danielebufarini.trenify.core.platform.BackgroundScheduler
import it.danielebufarini.trenify.core.platform.BackgroundTask
import it.danielebufarini.trenify.core.platform.Connectivity
import it.danielebufarini.trenify.core.platform.ConnectivityStatus
import it.danielebufarini.trenify.core.platform.EffectiveNotificationPermission
import it.danielebufarini.trenify.core.platform.ExternalUrlLauncher
import it.danielebufarini.trenify.core.platform.LifecycleIntegration
import it.danielebufarini.trenify.core.platform.NotificationMessage
import it.danielebufarini.trenify.core.platform.NotificationPermission
import it.danielebufarini.trenify.core.platform.NotificationPresenter
import it.danielebufarini.trenify.core.platform.PlatformServices
import it.danielebufarini.trenify.core.testing.FakeMonitoringRepository
import it.danielebufarini.trenify.core.testing.FakeNotificationSettingsRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testRun
import it.danielebufarini.trenify.core.testing.testRunId
import it.danielebufarini.trenify.core.testing.testStation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * T7.7 coordinator delivery precedence and race safety: global switch x
 * per-train switch x event flag x effective OS permission, with snapshot
 * progression preserved whenever delivery is gated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitoringCoordinatorNotificationPolicyTest {
    private class Harness(
        val permission: FakePolicyPermission = FakePolicyPermission(),
        scope: kotlinx.coroutines.CoroutineScope,
    ) {
        val clock = MutableClock()
        val monitoring = FakeMonitoringRepository(clock)
        val trains = FakeRealtimeRepositories()
        val settings = FakeNotificationSettingsRepository()
        val notifications = mutableListOf<NotificationMessage>()
        val appState = MutableStateFlow(ApplicationState.Background)
        val connectivity = MutableStateFlow(ConnectivityStatus.Available)
        val services = PlatformServices(
            Connectivity { connectivity },
            ApplicationStateObserver { appState },
            NotificationPresenter(notifications::add),
            permission,
            RecordingPolicyScheduler(),
            ExternalUrlLauncher { false },
            LifecycleIntegration { appState.value = it },
        )
        // The init loop is inert while the app stays in Background; tests
        // drive refreshOnce directly.
        val coordinator = MonitoringCoordinator(
            monitoring,
            trains,
            services,
            settings,
            scope,
            clock,
            MonitoringPolicy(),
            localizer = FakeNotificationLocalizer(),
        )

        suspend fun seed() {
            monitoring.createMonitor(testRunId, MonitorThresholds(delayMinutes = 15), clock.now() + 1.hours)
            coordinator.refreshOnce()
            // Known delay baseline: unknown -> N is no truthful transition
            // (T7.11 corrective), so later threshold tests start from 5.
            trains.trainState.value = DataResult.Data(delayed(5), DataFreshness.Unknown)
            coordinator.refreshOnce()
            assertEquals(0, notifications.size)
        }

        fun delayed(minutes: Int) = testRun.copy(summary = testRun.summary.copy(delayMinutes = minutes))
    }

    private suspend fun kotlinx.coroutines.test.TestScope.harness() =
        Harness(scope = backgroundScope).also { runCurrent() }

    @Test fun globalOffStillProgressesSnapshotsWithoutDelivery() = runTest {
        val harness = harness()
        harness.seed()
        runCurrent()
        harness.settings.setNotificationsEnabled(false)
        harness.trains.trainState.value = DataResult.Data(harness.delayed(20), DataFreshness.Unknown)
        harness.coordinator.refreshOnce()
        runCurrent()

        assertEquals(0, harness.notifications.size)
        assertEquals(0, harness.monitoring.claimedNotifications.size)
        // The evaluation still ran and the snapshot progressed.
        assertEquals(1, harness.monitoring.persistedEvents.size)
        val monitor = assertNotNull(harness.monitoring.observeMonitor(testRunId).first())
        assertEquals(20, monitor.lastSnapshot?.train?.summary?.delayMinutes)
        assertTrue(monitor.enabled)
    }

    @Test fun mutedTrainStillProgressesSnapshotsWithoutDelivery() = runTest {
        val harness = harness()
        harness.seed()
        runCurrent()
        harness.monitoring.setMonitorNotificationsEnabled(testRunId, false)
        harness.trains.trainState.value = DataResult.Data(harness.delayed(20), DataFreshness.Unknown)
        harness.coordinator.refreshOnce()
        runCurrent()

        assertEquals(0, harness.notifications.size)
        assertEquals(1, harness.monitoring.persistedEvents.size)
        val monitor = assertNotNull(harness.monitoring.observeMonitor(testRunId).first())
        assertEquals(20, monitor.lastSnapshot?.train?.summary?.delayMinutes)
        assertTrue(monitor.enabled)
    }

    @Test fun deniedPermissionStillProgressesSnapshotsWithoutDelivery() = runTest {
        val harness = harness()
        harness.seed()
        runCurrent()
        harness.permission.effective = EffectiveNotificationPermission.DENIED
        harness.trains.trainState.value = DataResult.Data(harness.delayed(20), DataFreshness.Unknown)
        harness.coordinator.refreshOnce()
        runCurrent()

        assertEquals(0, harness.notifications.size)
        assertEquals(1, harness.monitoring.persistedEvents.size)
        val monitor = assertNotNull(harness.monitoring.observeMonitor(testRunId).first())
        assertEquals(20, monitor.lastSnapshot?.train?.summary?.delayMinutes)
        assertTrue(monitor.enabled)
    }

    @Test fun disabledDelayFlagPreventsDelayDeliveryButKeepsProgressing() = runTest {
        val harness = harness()
        harness.seed()
        runCurrent()
        harness.monitoring.updateMonitorPreferences(
            testRunId,
            MonitorThresholds(delayMinutes = 15, notifyDelay = false),
        )
        harness.trains.trainState.value = DataResult.Data(harness.delayed(20), DataFreshness.Unknown)
        harness.coordinator.refreshOnce()
        runCurrent()

        assertEquals(0, harness.notifications.size)
        assertEquals(0, harness.monitoring.persistedEvents.size)
        val monitor = assertNotNull(harness.monitoring.observeMonitor(testRunId).first())
        assertEquals(20, monitor.lastSnapshot?.train?.summary?.delayMinutes)
    }

    @Test fun cancellationAndPlatformEventsRespectGatesEndToEnd() = runTest {
        val harness = harness()
        // Cancellation notifies with all gates open.
        harness.monitoring.createMonitor(testRunId, MonitorThresholds(delayMinutes = 15), harness.clock.now() + 1.hours)
        harness.coordinator.refreshOnce()
        runCurrent()
        harness.trains.trainState.value = DataResult.Data(
            testRun.copy(summary = testRun.summary.copy(status = TrainStatus.CANCELLED)),
            DataFreshness.Unknown,
        )
        harness.coordinator.refreshOnce()
        runCurrent()
        assertEquals(1, harness.notifications.size)

        // A disabled cancellation flag suppresses the same transition.
        val second = Harness(harness.permission, backgroundScope)
        runCurrent()
        second.monitoring.createMonitor(
            testRunId,
            MonitorThresholds(delayMinutes = 15, notifyCancellation = false),
            second.clock.now() + 1.hours,
        )
        // Share the same train feed shape: fresh base first, then cancelled.
        second.trains.trainState.value = DataResult.Data(testRun, DataFreshness.Unknown)
        second.coordinator.refreshOnce()
        runCurrent()
        second.trains.trainState.value = DataResult.Data(
            testRun.copy(summary = testRun.summary.copy(status = TrainStatus.CANCELLED)),
            DataFreshness.Unknown,
        )
        second.coordinator.refreshOnce()
        runCurrent()
        assertEquals(0, second.notifications.size)
        // The monitor is terminally disabled after cancellation, so it leaves
        // active observation; its stored snapshot still progressed.
        val monitor = second.monitoring.monitors.value.single()
        assertEquals(TrainStatus.CANCELLED, monitor.lastSnapshot?.train?.summary?.status)
    }

    @Test fun departureAndArrivalEventsNotifyWhenAllGatesAllow() = runTest {
        val harness = harness()
        // Seed a not-departed snapshot directly, then observe the transition.
        val notDeparted = testRun.copy(
            summary = testRun.summary.copy(status = TrainStatus.NOT_DEPARTED),
            stops = listOf(
                testRun.stops.single().copy(actualPlatform = "2", actualDeparture = null, status = StopStatus.SCHEDULED),
            ),
        )
        harness.monitoring.createMonitor(testRunId, MonitorThresholds(delayMinutes = 15), harness.clock.now() + 1.hours)
        val created = assertNotNull(harness.monitoring.observeMonitor(testRunId).first())
        harness.monitoring.persistEvaluation(
            created.id,
            MonitoredTrainSnapshot(notDeparted, DataFreshness.Unknown, harness.clock.now()),
            emptyList(),
            created.snapshotVersion,
            0L,
        )
        harness.trains.trainState.value = DataResult.Data(testRun, DataFreshness.Unknown)
        harness.coordinator.refreshOnce()
        runCurrent()
        assertEquals(1, harness.notifications.size)
        assertEquals("[L] departed", harness.notifications.single().body)

        // Arrival follows from the running snapshot.
        harness.monitoring.claimedNotifications.clear()
        harness.trains.trainState.value = DataResult.Data(
            testRun.copy(summary = testRun.summary.copy(status = TrainStatus.ARRIVED)),
            DataFreshness.Unknown,
        )
        harness.coordinator.refreshOnce()
        runCurrent()
        assertEquals(2, harness.notifications.size)
        assertEquals("[L] arrived", harness.notifications.last().body)
    }

    @Test fun everySupportedEventKindNotifiesWhenAllGatesAllow() = runTest {
        // Each transition runs in an isolated harness so persisted snapshots
        // can never leak between entries.
        val transitions = listOf(
            // Unknown -> 20 is no truthful transition: the delay entry first
            // establishes a known 5-minute baseline, then crosses to 20.
            Triple("[L] delay 5->20", { h: Harness -> h.delayed(5) }, { h: Harness -> h.delayed(20) }),
            Triple("[L] cancelled", { _: Harness -> testRun },
                { _: Harness -> testRun.copy(summary = testRun.summary.copy(status = TrainStatus.CANCELLED)) }),
            Triple("[L] partial[]", { _: Harness -> testRun },
                { _: Harness -> testRun.copy(summary = testRun.summary.copy(status = TrainStatus.PARTIALLY_CANCELLED)) }),
            Triple("[L] platform 9", { _: Harness -> testRun },
                { _: Harness -> testRun.copy(stops = listOf(testRun.stops.single().copy(actualPlatform = "9"))) }),
            Triple("[L] schedule Roma Termini", { _: Harness -> testRun },
                { h: Harness ->
                    testRun.copy(
                        stops = listOf(
                            testRun.stops.single().copy(scheduledDeparture = h.clock.now()),
                        ),
                    )
                }),
            Triple("[L] status DIVERTED", { _: Harness -> testRun },
                { _: Harness -> testRun.copy(summary = testRun.summary.copy(status = TrainStatus.DIVERTED)) }),
            Triple("[L] route", { _: Harness -> testRun },
                { _: Harness ->
                    testRun.copy(
                        stops = testRun.stops + TrainStop(
                            Station(StationId("milano"), "Milano Centrale"),
                        ),
                    )
                }),
        )
        transitions.forEach { (body, base, mutate) ->
            val entry = Harness(scope = backgroundScope)
            runCurrent()
            entry.monitoring.createMonitor(
                testRunId,
                MonitorThresholds(delayMinutes = 15),
                entry.clock.now() + 1.hours,
            )
            entry.trains.trainState.value = DataResult.Data(base(entry), DataFreshness.Unknown)
            entry.coordinator.refreshOnce()
            runCurrent()
            assertEquals(0, entry.notifications.size, "baseline must stay silent for: $body")
            entry.trains.trainState.value = DataResult.Data(mutate(entry), DataFreshness.Unknown)
            entry.coordinator.refreshOnce()
            runCurrent()
            assertEquals(1, entry.notifications.size, "expected one notification for: $body")
            assertEquals(body, entry.notifications.last().body)
        }
    }

    @Test fun muteDuringSuspendedFetchPreventsDelivery() = runTest {
        val harness = harness()
        harness.seed()
        runCurrent()
        // The provider fetch suspends; the user mutes before it resumes with
        // a meaningful change. The stale pre-fetch monitor copy must not
        // override the newly persisted mute.
        harness.trains.onRefresh = {
            harness.monitoring.setMonitorNotificationsEnabled(testRunId, false)
        }
        harness.trains.trainState.value = DataResult.Data(harness.delayed(20), DataFreshness.Unknown)
        harness.coordinator.refreshOnce()
        runCurrent()
        harness.trains.onRefresh = {}

        assertEquals(0, harness.notifications.size)
        assertEquals(0, harness.monitoring.claimedNotifications.size)
        // Snapshot and evaluation state still progressed normally.
        assertEquals(1, harness.monitoring.persistedEvents.size)
        val monitor = assertNotNull(harness.monitoring.observeMonitor(testRunId).first())
        assertEquals(20, monitor.lastSnapshot?.train?.summary?.delayMinutes)
        assertNotNull(monitor.lastEvent)
    }

    @Test fun unmuteDoesNotReplayMutedPeriodChanges() = runTest {
        val harness = harness()
        harness.seed()
        runCurrent()
        harness.monitoring.setMonitorNotificationsEnabled(testRunId, false)
        harness.trains.trainState.value = DataResult.Data(harness.delayed(20), DataFreshness.Unknown)
        harness.coordinator.refreshOnce()
        runCurrent()
        assertEquals(0, harness.notifications.size)

        // Unmuting with no further change notifies nothing: the muted-period
        // change is already reflected in the stored snapshot.
        harness.monitoring.setMonitorNotificationsEnabled(testRunId, true)
        harness.coordinator.refreshOnce()
        runCurrent()
        assertEquals(0, harness.notifications.size)
        assertEquals(1, harness.monitoring.persistedEvents.size)

        // A genuinely new change after unmute notifies normally.
        harness.trains.trainState.value = DataResult.Data(harness.delayed(35), DataFreshness.Unknown)
        harness.coordinator.refreshOnce()
        runCurrent()
        assertEquals(1, harness.notifications.size)
    }

    @Test fun savedPreferenceChangesApplyWithoutRecreatingTheMonitor() = runTest {
        val harness = harness()
        harness.seed()
        runCurrent()
        val before = assertNotNull(harness.monitoring.observeMonitor(testRunId).first())

        harness.monitoring.updateMonitorPreferences(testRunId, MonitorThresholds(delayMinutes = 30))
        harness.trains.trainState.value = DataResult.Data(harness.delayed(20), DataFreshness.Unknown)
        harness.coordinator.refreshOnce()
        runCurrent()

        // Below the new threshold: no event, but the snapshot progresses and
        // the monitor identity is untouched.
        assertEquals(0, harness.notifications.size)
        val after = assertNotNull(harness.monitoring.observeMonitor(testRunId).first())
        assertEquals(before.id, after.id)
        assertEquals(before.createdAt, after.createdAt)
        assertEquals(30, after.thresholds.delayMinutes)
        assertEquals(20, after.lastSnapshot?.train?.summary?.delayMinutes)

        harness.trains.trainState.value = DataResult.Data(harness.delayed(65), DataFreshness.Unknown)
        harness.coordinator.refreshOnce()
        runCurrent()
        assertEquals(1, harness.notifications.size)
        val notified = assertNotNull(harness.monitoring.observeMonitor(testRunId).first())
        assertEquals(before.id, notified.id)
    }
}

private class FakePolicyPermission(
    var effective: EffectiveNotificationPermission = EffectiveNotificationPermission.GRANTED,
) : NotificationPermission {
    override suspend fun isGranted(): Boolean = effective == EffectiveNotificationPermission.GRANTED
    override suspend fun request(): Boolean = isGranted()
    override suspend fun effective(): EffectiveNotificationPermission = effective
}

private class RecordingPolicyScheduler : BackgroundScheduler {
    val scheduled = mutableListOf<BackgroundTask>()
    override fun register(taskId: String, handler: suspend () -> Unit) = Unit
    override suspend fun schedule(task: BackgroundTask) {
        scheduled += task
    }
    override suspend fun cancel(taskId: String) = Unit
}
