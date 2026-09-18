package it.danielebufarini.trenify.app

import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.MonitorThresholds
import it.danielebufarini.trenify.core.domain.MonitoredTrainSnapshot
import it.danielebufarini.trenify.core.domain.MonitoringPolicy
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.platform.ApplicationState
import it.danielebufarini.trenify.core.platform.ApplicationStateObserver
import it.danielebufarini.trenify.core.platform.BackgroundScheduler
import it.danielebufarini.trenify.core.platform.BackgroundTask
import it.danielebufarini.trenify.core.platform.Connectivity
import it.danielebufarini.trenify.core.platform.ConnectivityStatus
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Monitor refresh-failure presentation truth (T7.14 corrective): a failed
 * refresh is never evaluated as a new observation, yet the retained
 * snapshot becomes visibly stale-with-warning, stays distinguishable
 * offline vs temporary, generates no events/notifications, and recovers
 * cleanly on the next accepted refresh.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitorRefreshFailureTest {
    private fun kotlinx.coroutines.test.TestScope.coordinator(
        monitoring: FakeMonitoringRepository,
        trains: FakeRealtimeRepositories,
        clock: MutableClock,
        notifications: MutableList<NotificationMessage>,
    ): MonitoringCoordinator {
        val appState = MutableStateFlow(ApplicationState.Background)
        val connectivity = MutableStateFlow(ConnectivityStatus.Available)
        val services = PlatformServices(
            Connectivity { connectivity },
            ApplicationStateObserver { appState },
            NotificationPresenter(notifications::add),
            GrantedRefreshPermission,
            RecordingRefreshScheduler(),
            ExternalUrlLauncher { false },
            LifecycleIntegration { appState.value = it },
        )
        return MonitoringCoordinator(
            monitoring,
            trains,
            services,
            FakeNotificationSettingsRepository(),
            backgroundScope,
            clock,
            MonitoringPolicy(foregroundRefreshInterval = 1.hours),
            localizer = FakeNotificationLocalizer(),
        )
    }

    private suspend fun stageAcceptedSnapshot(
        monitoring: FakeMonitoringRepository,
        trains: FakeRealtimeRepositories,
        clock: MutableClock,
        coordinator: MonitoringCoordinator,
    ) {
        monitoring.createMonitor(testRunId, MonitorThresholds(delayMinutes = 15), clock.now() + 1.hours)
        trains.trainState.value = DataResult.Data(
            testRun.copy(summary = testRun.summary.copy(delayMinutes = 5)),
            DataFreshness.Fresh(clock.now(), null),
        )
        coordinator.refreshOnce()
    }

    @Test fun offlineRefreshPreservesSnapshotAndRecordsTypedWarning() = runTest {
        val clock = MutableClock()
        val monitoring = FakeMonitoringRepository(clock)
        val trains = FakeRealtimeRepositories()
        val notifications = mutableListOf<NotificationMessage>()
        val coordinator = coordinator(monitoring, trains, clock, notifications)
        runCurrent()
        stageAcceptedSnapshot(monitoring, trains, clock, coordinator)
        val accepted = monitoring.observeMonitor(testRunId).first()!!
        val version = accepted.snapshotVersion

        // The provider refresh now fails offline with a cached fallback.
        trains.trainState.value = DataResult.Failure(DomainFailure.OFFLINE)
        coordinator.refreshOnce()

        val degraded = monitoring.observeMonitor(testRunId).first()!!
        // The accepted train observation/event state is unchanged...
        assertEquals(accepted.lastSnapshot?.train, degraded.lastSnapshot?.train)
        assertEquals(version, degraded.snapshotVersion)
        assertTrue(monitoring.persistedEvents.isEmpty())
        assertTrue(notifications.isEmpty())
        // ...while the failed refresh survives as typed presentation truth.
        assertEquals(DomainFailure.OFFLINE, degraded.refreshFailure)
        // Recreation preserves the degraded truth: the same persisted row
        // still carries the warning without any new fetch.
        val reopened = monitoring.observeMonitor(testRunId).first()!!
        assertEquals(DomainFailure.OFFLINE, reopened.refreshFailure)
    }

    @Test fun temporaryRefreshRecordsDistinguishableWarning() = runTest {
        val clock = MutableClock()
        val monitoring = FakeMonitoringRepository(clock)
        val trains = FakeRealtimeRepositories()
        val notifications = mutableListOf<NotificationMessage>()
        val coordinator = coordinator(monitoring, trains, clock, notifications)
        runCurrent()
        stageAcceptedSnapshot(monitoring, trains, clock, coordinator)
        val accepted = monitoring.observeMonitor(testRunId).first()!!

        trains.trainState.value = DataResult.Failure(DomainFailure.TEMPORARY)
        coordinator.refreshOnce()

        val degraded = monitoring.observeMonitor(testRunId).first()!!
        assertEquals(accepted.lastSnapshot?.train, degraded.lastSnapshot?.train)
        assertEquals(DomainFailure.TEMPORARY, degraded.refreshFailure)
        assertTrue(monitoring.persistedEvents.isEmpty())
        assertTrue(notifications.isEmpty())
    }

    @Test fun warningBackedFallbackRecordsWarningWithoutNewObservation() = runTest {
        val clock = MutableClock()
        val monitoring = FakeMonitoringRepository(clock)
        val trains = FakeRealtimeRepositories()
        val notifications = mutableListOf<NotificationMessage>()
        val coordinator = coordinator(monitoring, trains, clock, notifications)
        runCurrent()
        stageAcceptedSnapshot(monitoring, trains, clock, coordinator)
        val version = monitoring.observeMonitor(testRunId).first()!!.snapshotVersion

        // A warning-backed replay of old cache: not a new observation.
        trains.trainState.value = DataResult.Data(
            testRun.copy(summary = testRun.summary.copy(delayMinutes = 40)),
            DataFreshness.Fresh(clock.now(), null),
            warning = DomainFailure.TEMPORARY,
        )
        coordinator.refreshOnce()

        val degraded = monitoring.observeMonitor(testRunId).first()!!
        // Delay/platform/status must not move on a failed refresh.
        assertEquals(5, degraded.lastSnapshot?.train?.summary?.delayMinutes)
        assertEquals(version, degraded.snapshotVersion)
        assertEquals(DomainFailure.TEMPORARY, degraded.refreshFailure)
        assertTrue(monitoring.persistedEvents.isEmpty())
        assertTrue(notifications.isEmpty())
    }

    @Test fun notFoundRefreshPreservesSnapshotAndRecordsHealthWithoutEvents() = runTest {
        val clock = MutableClock()
        val monitoring = FakeMonitoringRepository(clock)
        val trains = FakeRealtimeRepositories()
        val notifications = mutableListOf<NotificationMessage>()
        val coordinator = coordinator(monitoring, trains, clock, notifications)
        runCurrent()
        stageAcceptedSnapshot(monitoring, trains, clock, coordinator)
        val accepted = monitoring.observeMonitor(testRunId).first()!!
        val version = accepted.snapshotVersion

        // The provider no longer knows this run: not a new observation,
        // not a monitoring event — only stale-cache presentation truth.
        trains.trainState.value = DataResult.Failure(DomainFailure.NOT_FOUND)
        coordinator.refreshOnce()

        val degraded = monitoring.observeMonitor(testRunId).first()!!
        assertEquals(accepted.lastSnapshot?.train, degraded.lastSnapshot?.train)
        assertEquals(version, degraded.snapshotVersion)
        assertEquals(DomainFailure.NOT_FOUND, degraded.refreshFailure)
        assertTrue(monitoring.persistedEvents.isEmpty())
        assertTrue(notifications.isEmpty())

        // A later clean accepted refresh clears the warning and restores Fresh.
        clock.instant += 1.minutes
        trains.trainState.value = DataResult.Data(
            testRun.copy(summary = testRun.summary.copy(delayMinutes = 9)),
            DataFreshness.Fresh(clock.now(), null),
        )
        coordinator.refreshOnce()
        val recovered = monitoring.observeMonitor(testRunId).first()!!
        assertEquals(9, recovered.lastSnapshot?.train?.summary?.delayMinutes)
        assertNull(recovered.refreshFailure)
        assertTrue(recovered.lastSnapshot?.freshness is DataFreshness.Fresh)
    }

    @Test fun cleanAcceptedRefreshClearsWarningAndRestoresFresh() = runTest {
        val clock = MutableClock()
        val monitoring = FakeMonitoringRepository(clock)
        val trains = FakeRealtimeRepositories()
        val notifications = mutableListOf<NotificationMessage>()
        val coordinator = coordinator(monitoring, trains, clock, notifications)
        runCurrent()
        stageAcceptedSnapshot(monitoring, trains, clock, coordinator)
        trains.trainState.value = DataResult.Failure(DomainFailure.OFFLINE)
        coordinator.refreshOnce()
        assertEquals(DomainFailure.OFFLINE, monitoring.observeMonitor(testRunId).first()!!.refreshFailure)

        // A later clean accepted provider snapshot updates normally...
        clock.instant += 1.minutes
        trains.trainState.value = DataResult.Data(
            testRun.copy(summary = testRun.summary.copy(delayMinutes = 20)),
            DataFreshness.Fresh(clock.now(), null),
        )
        coordinator.refreshOnce()

        val recovered = monitoring.observeMonitor(testRunId).first()!!
        assertEquals(20, recovered.lastSnapshot?.train?.summary?.delayMinutes)
        assertNull(recovered.refreshFailure)
        assertNull(recovered.refreshFailedAt)
        assertTrue(recovered.lastSnapshot?.freshness is DataFreshness.Fresh)
    }

    @Test fun endedMonitorsStayReadOnlyOnFailedRefresh() = runTest {
        val clock = MutableClock()
        val monitoring = FakeMonitoringRepository(clock)
        val trains = FakeRealtimeRepositories()
        val notifications = mutableListOf<NotificationMessage>()
        val coordinator = coordinator(monitoring, trains, clock, notifications)
        runCurrent()
        monitoring.createMonitor(testRunId, MonitorThresholds(delayMinutes = 15), clock.now() + 1.hours)
        val arrived = testRun.copy(summary = testRun.summary.copy(status = TrainStatus.ARRIVED))
        trains.trainState.value = DataResult.Data(arrived, DataFreshness.Fresh(clock.now(), null))
        coordinator.refreshOnce()
        val ended = monitoring.observeMonitor(testRunId).first()!!
        assertTrue(ended.endedAt != null)

        // A later failed refresh must not touch the ended monitor: it is
        // not refreshed, and recording health for it is a no-op.
        trains.trainState.value = DataResult.Failure(DomainFailure.OFFLINE)
        coordinator.refreshOnce()
        monitoring.recordRefreshFailure(ended.id, DomainFailure.OFFLINE, clock.now())
        val after = monitoring.observeMonitor(testRunId).first()!!
        assertNull(after.refreshFailure)
        assertEquals(ended.lastSnapshot?.train, after.lastSnapshot?.train)
        assertEquals(ended.endedAt, after.endedAt)
        clock.instant += 1.seconds
        advanceTimeBy(1_000)
        runCurrent()
    }
}

private object GrantedRefreshPermission : NotificationPermission {
    override suspend fun isGranted() = true
    override suspend fun request() = true
}

private class RecordingRefreshScheduler : BackgroundScheduler {
    override fun register(taskId: String, handler: suspend () -> Unit) = Unit
    override suspend fun schedule(task: BackgroundTask) = Unit
    override suspend fun cancel(taskId: String) = Unit
}
