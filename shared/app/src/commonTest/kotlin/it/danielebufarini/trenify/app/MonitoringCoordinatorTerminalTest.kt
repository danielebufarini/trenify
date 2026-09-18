package it.danielebufarini.trenify.app

import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.MonitorThresholds
import it.danielebufarini.trenify.core.domain.EvaluationCommit
import it.danielebufarini.trenify.core.domain.MonitorId
import it.danielebufarini.trenify.core.domain.MonitoredTrainSnapshot
import it.danielebufarini.trenify.core.domain.MonitoringPolicy
import it.danielebufarini.trenify.core.domain.MonitoringRepository
import it.danielebufarini.trenify.core.domain.TrainMonitor
import it.danielebufarini.trenify.core.domain.TrainMonitorEvent
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Station
import it.danielebufarini.trenify.core.model.StationId
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.model.TrainRun
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.model.TrainStop
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * T7.11 terminal lifecycle at the coordinator level: polling shutdown,
 * final-snapshot protection, stale-refresh linearization, manual-stop
 * ordering, restart semantics and retention cleanup — all deterministic, no
 * sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitoringCoordinatorTerminalTest {
    private class Harness(scope: kotlinx.coroutines.CoroutineScope) {
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
            GrantedTerminalPermission,
            RecordingTerminalScheduler(),
            ExternalUrlLauncher { false },
            LifecycleIntegration { appState.value = it },
        )
        // The init loop stays inert in Background; tests drive refreshOnce.
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

        suspend fun seedActive() {
            monitoring.createMonitor(testRunId, MonitorThresholds(delayMinutes = 15), clock.now() + 1.hours)
            coordinator.refreshOnce()
        }

        fun delayed(minutes: Int): TrainRun =
            testRun.copy(summary = testRun.summary.copy(delayMinutes = minutes))

        fun arrived(): TrainRun = testRun.copy(summary = testRun.summary.copy(status = TrainStatus.ARRIVED))
        fun cancelled(): TrainRun = testRun.copy(summary = testRun.summary.copy(status = TrainStatus.CANCELLED))
        fun withStatus(status: TrainStatus): TrainRun =
            testRun.copy(summary = testRun.summary.copy(status = status))
    }

    private suspend fun kotlinx.coroutines.test.TestScope.harness() =
        Harness(scope = backgroundScope).also { runCurrent() }

    @Test fun arrivedStopsPollingRetainsFinalSnapshotAndFixesEndedAt() = runTest {
        val harness = harness()
        harness.seedActive()
        runCurrent()
        assertEquals(0, harness.notifications.size)
        val pollsBeforeTerminal = harness.trains.trainRefreshes

        harness.trains.trainState.value = DataResult.Data(harness.arrived(), DataFreshness.Unknown)
        harness.coordinator.refreshOnce()
        runCurrent()

        assertEquals(1, harness.notifications.size)
        assertEquals("[L] arrived", harness.notifications.single().body)
        assertTrue(harness.monitoring.observeActiveMonitors().first().isEmpty())
        val ended = harness.monitoring.observeEndedMonitors().first().single()
        assertEquals(harness.clock.now(), ended.endedAt)
        assertEquals(TrainStatus.ARRIVED, ended.lastSnapshot?.train?.summary?.status)
        val endedAt = assertNotNull(ended.endedAt)

        // No automatic provider polling for the ended monitor afterwards.
        val pollsAfterTerminal = harness.trains.trainRefreshes
        assertEquals(pollsBeforeTerminal + 1, pollsAfterTerminal)
        harness.clock.instant += 5.minutes
        repeat(3) {
            harness.coordinator.refreshOnce()
            runCurrent()
        }
        assertEquals(pollsAfterTerminal, harness.trains.trainRefreshes)
        assertEquals(1, harness.notifications.size)
        assertEquals(endedAt, harness.monitoring.observeEndedMonitors().first().single().endedAt)
    }

    @Test fun finalCancelledStopsPollingAndIsRetained() = runTest {
        val harness = harness()
        harness.seedActive()
        runCurrent()

        harness.trains.trainState.value = DataResult.Data(harness.cancelled(), DataFreshness.Unknown)
        harness.coordinator.refreshOnce()
        runCurrent()

        assertEquals(1, harness.notifications.size)
        assertEquals("[L] cancelled", harness.notifications.single().body)
        assertTrue(harness.monitoring.observeActiveMonitors().first().isEmpty())
        assertEquals(1, harness.monitoring.observeEndedMonitors().first().size)

        val polls = harness.trains.trainRefreshes
        harness.coordinator.refreshOnce()
        runCurrent()
        assertEquals(polls, harness.trains.trainRefreshes)
    }

    @Test fun firstRefreshAlreadyTerminalClaimsArrivalAndEnds() = runTest {
        val harness = harness()
        harness.monitoring.createMonitor(testRunId, MonitorThresholds(delayMinutes = 15), harness.clock.now() + 1.hours)
        harness.trains.trainState.value = DataResult.Data(harness.arrived(), DataFreshness.Unknown)
        harness.coordinator.refreshOnce()
        runCurrent()

        // No previous snapshot exists, yet the terminal event is still claimed
        // from the source-backed terminal status and the monitor ends.
        assertEquals(1, harness.notifications.size)
        assertEquals("[L] arrived", harness.notifications.single().body)
        assertTrue(harness.monitoring.observeActiveMonitors().first().isEmpty())
        assertEquals(1, harness.monitoring.observeEndedMonitors().first().size)
    }

    @Test fun nonTerminalStatusesKeepPollingWithoutEnding() = runTest {
        val harness = harness()
        harness.seedActive()
        runCurrent()
        val polls = harness.trains.trainRefreshes

        harness.trains.trainState.value = DataResult.Data(harness.withStatus(TrainStatus.PARTIALLY_CANCELLED), DataFreshness.Unknown)
        harness.coordinator.refreshOnce()
        runCurrent()
        assertEquals(polls + 1, harness.trains.trainRefreshes)
        assertEquals(1, harness.monitoring.observeActiveMonitors().first().size)
        assertTrue(harness.monitoring.observeEndedMonitors().first().isEmpty())

        harness.clock.instant += 6.minutes
        harness.trains.trainState.value = DataResult.Data(harness.withStatus(TrainStatus.DIVERTED), DataFreshness.Unknown)
        harness.coordinator.refreshOnce()
        runCurrent()
        assertEquals(polls + 2, harness.trains.trainRefreshes)
        assertEquals("[L] status DIVERTED", harness.notifications.last().body)
        assertNull(assertNotNull(harness.monitoring.observeMonitor(testRunId).first()).endedAt)

        harness.clock.instant += 6.minutes
        harness.trains.trainState.value = DataResult.Data(harness.withStatus(TrainStatus.RESCHEDULED), DataFreshness.Unknown)
        harness.coordinator.refreshOnce()
        runCurrent()
        assertEquals(polls + 3, harness.trains.trainRefreshes)
        assertEquals("[L] status RESCHEDULED", harness.notifications.last().body)
        assertEquals(1, harness.monitoring.observeActiveMonitors().first().size)
        assertTrue(harness.monitoring.observeEndedMonitors().first().isEmpty())
    }

    @Test fun unknownStatusAndProviderFailureKeepMonitoringWithoutEvents() = runTest {
        val harness = harness()
        harness.seedActive()
        runCurrent()

        harness.trains.trainState.value = DataResult.Data(harness.withStatus(TrainStatus.UNKNOWN), DataFreshness.Unknown)
        harness.coordinator.refreshOnce()
        runCurrent()
        assertEquals(0, harness.notifications.size)
        assertEquals(1, harness.monitoring.observeActiveMonitors().first().size)

        harness.trains.trainState.value = DataResult.Failure(DomainFailure.TEMPORARY)
        harness.coordinator.refreshOnce()
        runCurrent()
        assertEquals(0, harness.notifications.size)
        assertEquals(1, harness.monitoring.observeActiveMonitors().first().size)
        assertNull(assertNotNull(harness.monitoring.observeMonitor(testRunId).first()).endedAt)
    }

    @Test fun routeChangedKeepsPollingAndNotifiesOnce() = runTest {
        val harness = harness()
        harness.seedActive()
        runCurrent()

        val extended = testRun.copy(
            stops = testRun.stops + TrainStop(Station(StationId("milano"), "Milano Centrale")),
        )
        harness.trains.trainState.value = DataResult.Data(extended, DataFreshness.Unknown)
        harness.coordinator.refreshOnce()
        runCurrent()
        assertEquals(1, harness.notifications.size)
        assertEquals("[L] route", harness.notifications.single().body)
        assertEquals(1, harness.monitoring.observeActiveMonitors().first().size)

        // Repeating the identical route notifies nothing.
        harness.coordinator.refreshOnce()
        runCurrent()
        assertEquals(1, harness.notifications.size)
        assertEquals(1, harness.monitoring.observeActiveMonitors().first().size)
    }

    @Test fun routeChangePlusRescheduleEmitsBothAndStaysActive() = runTest {
        val harness = harness()
        harness.seedActive()
        runCurrent()

        val changed = testRun.copy(
            summary = testRun.summary.copy(status = TrainStatus.RESCHEDULED),
            stops = testRun.stops + TrainStop(Station(StationId("milano"), "Milano Centrale")),
        )
        harness.trains.trainState.value = DataResult.Data(changed, DataFreshness.Unknown)
        harness.coordinator.refreshOnce()
        runCurrent()
        assertEquals(2, harness.notifications.size)
        assertEquals(1, harness.monitoring.observeActiveMonitors().first().size)
        assertTrue(harness.monitoring.observeEndedMonitors().first().isEmpty())
    }

    @Test fun staleInFlightRefreshCannotReviveTerminalMonitor() = runTest {
        val harness = harness()
        harness.seedActive()
        runCurrent()
        val baseRun = (harness.trains.trainState.value as DataResult.Data).value

        // Refresh A begins and parks inside the provider fetch.
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        harness.trains.onRefresh = { if (calls++ == 0) gate.await() }
        val jobA = launch { harness.coordinator.refreshOnce() }
        runCurrent()
        assertEquals(2, harness.trains.trainRefreshes)

        // Refresh B observes the terminal state and commits while A is parked.
        harness.trains.onRefresh = {}
        harness.trains.trainState.value = DataResult.Data(harness.arrived(), DataFreshness.Unknown)
        harness.coordinator.refreshOnce()
        runCurrent()
        assertEquals(1, harness.notifications.size)
        val endedAt = assertNotNull(harness.monitoring.observeEndedMonitors().first().single().endedAt)

        // A resumes with older non-terminal data: the monitor must remain
        // ended, the final snapshot terminal, with no reactivation, no extra
        // poll and no duplicate terminal event.
        harness.trains.trainState.value = DataResult.Data(baseRun, DataFreshness.Unknown)
        gate.complete(Unit)
        runCurrent()
        jobA.join()

        assertTrue(harness.monitoring.observeActiveMonitors().first().isEmpty())
        val ended = harness.monitoring.observeEndedMonitors().first().single()
        assertEquals(endedAt, ended.endedAt)
        assertEquals(TrainStatus.ARRIVED, ended.lastSnapshot?.train?.summary?.status)
        assertEquals(TrainMonitorEvent.Arrived(testRunId), ended.lastEvent)
        assertEquals(1, harness.notifications.size)
        assertEquals(3, harness.trains.trainRefreshes)
        harness.coordinator.refreshOnce()
        runCurrent()
        assertEquals(3, harness.trains.trainRefreshes)
    }

    @Test fun stopBeforeTerminalLeavesNoRetention() = runTest {
        val harness = harness()
        harness.seedActive()
        runCurrent()

        harness.monitoring.removeMonitor(testRunId)
        harness.trains.trainState.value = DataResult.Data(harness.arrived(), DataFreshness.Unknown)
        val polls = harness.trains.trainRefreshes
        harness.coordinator.refreshOnce()
        runCurrent()

        // The removed monitor is never polled and never retained.
        assertEquals(polls, harness.trains.trainRefreshes)
        assertEquals(0, harness.notifications.size)
        assertTrue(harness.monitoring.observeActiveMonitors().first().isEmpty())
        assertTrue(harness.monitoring.observeEndedMonitors().first().isEmpty())
    }

    @Test fun stopAfterTerminalRemovesTheRetainedMonitorDeterministically() = runTest {
        val harness = harness()
        harness.seedActive()
        runCurrent()
        harness.trains.trainState.value = DataResult.Data(harness.arrived(), DataFreshness.Unknown)
        harness.coordinator.refreshOnce()
        runCurrent()
        assertEquals(1, harness.monitoring.observeEndedMonitors().first().size)

        // Terminal first, manual stop second: the final state is removed, not
        // both retained and removed.
        harness.monitoring.removeMonitor(testRunId)
        runCurrent()
        assertTrue(harness.monitoring.observeActiveMonitors().first().isEmpty())
        assertTrue(harness.monitoring.observeEndedMonitors().first().isEmpty())

        // Restart keeps it removed: never in Recently ended, never polled.
        val polls = harness.trains.trainRefreshes
        harness.coordinator.refreshOnce()
        runCurrent()
        assertEquals(polls, harness.trains.trainRefreshes)
        assertEquals(1, harness.notifications.size)
    }

    @Test fun restartAfterTerminalStaysEndedWithoutRepollOrRenotify() = runTest {
        val harness = harness()
        harness.seedActive()
        runCurrent()
        harness.trains.trainState.value = DataResult.Data(harness.arrived(), DataFreshness.Unknown)
        harness.coordinator.refreshOnce()
        runCurrent()
        assertEquals(1, harness.monitoring.observeEndedMonitors().first().size)
        val polls = harness.trains.trainRefreshes

        // Restart: a new coordinator over the same repositories restores the
        // ended state instead of resuming active monitoring.
        val restarted = MonitoringCoordinator(
            harness.monitoring,
            harness.trains,
            harness.services,
            harness.settings,
            backgroundScope,
            harness.clock,
            MonitoringPolicy(),
            localizer = FakeNotificationLocalizer(),
        )
        runCurrent()
        assertTrue(harness.monitoring.observeActiveMonitors().first().isEmpty())
        assertEquals(1, harness.monitoring.observeEndedMonitors().first().size)
        restarted.refreshOnce()
        runCurrent()
        assertEquals(polls, harness.trains.trainRefreshes)
        assertEquals(1, harness.notifications.size)

        // Restart past the deadline hides the item via startup cleanup.
        harness.clock.instant += 25.hours
        val postRetention = MonitoringCoordinator(
            harness.monitoring,
            harness.trains,
            harness.services,
            harness.settings,
            backgroundScope,
            harness.clock,
            MonitoringPolicy(),
            localizer = FakeNotificationLocalizer(),
        )
        runCurrent()
        assertNotNull(postRetention)
        assertTrue(harness.monitoring.observeEndedMonitors().first().isEmpty())
    }

    @Test fun expiredEndedIsCleanedOnRefreshAndNeverRepolled() = runTest {
        val harness = harness()
        harness.seedActive()
        runCurrent()
        harness.trains.trainState.value = DataResult.Data(harness.arrived(), DataFreshness.Unknown)
        harness.coordinator.refreshOnce()
        runCurrent()
        assertEquals(1, harness.monitoring.observeEndedMonitors().first().size)
        val polls = harness.trains.trainRefreshes

        harness.clock.instant += 25.hours
        harness.coordinator.refreshOnce()
        runCurrent()
        assertTrue(harness.monitoring.observeEndedMonitors().first().isEmpty())
        assertNull(harness.monitoring.observeMonitor(testRunId).first())
        assertEquals(polls, harness.trains.trainRefreshes)
        assertEquals(1, harness.notifications.size)
    }

    @Test fun terminalSnapshotPreservesSourceTimestampsAndDoesNotInventData() = runTest {
        val harness = harness()
        harness.seedActive()
        runCurrent()
        val fetchedAt = harness.clock.now()
        val sourceAt = harness.clock.now()
        harness.trains.trainState.value = DataResult.Data(
            harness.arrived(),
            DataFreshness.Fresh(fetchedAt, sourceAt),
        )
        harness.coordinator.refreshOnce()
        runCurrent()
        val snapshot = assertNotNull(
            harness.monitoring.observeEndedMonitors().first().single().lastSnapshot,
        )
        // T7.10 semantics hold for the final snapshot: source observation
        // timestamp and repository fetchedAt stay distinct.
        assertEquals(DataFreshness.Fresh(fetchedAt, sourceAt), snapshot.freshness)
        assertEquals(TrainStatus.ARRIVED, snapshot.train.summary.status)
    }

    @Test fun mutedTerminalStillEndsMonitoringWithoutDelivery() = runTest {
        val harness = harness()
        harness.seedActive()
        runCurrent()
        harness.monitoring.setMonitorNotificationsEnabled(testRunId, false)
        harness.trains.trainState.value = DataResult.Data(harness.arrived(), DataFreshness.Unknown)
        harness.coordinator.refreshOnce()
        runCurrent()

        assertEquals(0, harness.notifications.size)
        assertEquals(1, harness.monitoring.persistedEvents.size)
        assertTrue(harness.monitoring.observeActiveMonitors().first().isEmpty())
        assertEquals(1, harness.monitoring.observeEndedMonitors().first().size)
    }

    @Test fun createAfterTerminalDoesNotReactivate() = runTest {
        val harness = harness()
        harness.seedActive()
        runCurrent()
        harness.trains.trainState.value = DataResult.Data(harness.arrived(), DataFreshness.Unknown)
        harness.coordinator.refreshOnce()
        runCurrent()
        val endedAt = assertNotNull(harness.monitoring.observeEndedMonitors().first().single().endedAt)

        // A Start/Create request for the retained ended monitor returns it
        // unchanged: no reactivation, no polling eligibility.
        val returned = harness.monitoring.createMonitor(
            testRunId,
            MonitorThresholds(delayMinutes = 45),
            harness.clock.now() + 1.hours,
        )
        runCurrent()
        assertEquals(endedAt, returned.endedAt)
        assertTrue(harness.monitoring.observeActiveMonitors().first().isEmpty())
        assertEquals(1, harness.monitoring.observeEndedMonitors().first().size)
        val polls = harness.trains.trainRefreshes
        harness.coordinator.refreshOnce()
        runCurrent()
        assertEquals(polls, harness.trains.trainRefreshes)
    }

    // ---- T7.11 corrective: retention watcher (D3) ----

    private suspend fun kotlinx.coroutines.test.TestScope.advanceBoth(
        harness: Harness,
        duration: Duration,
    ) {
        // Production advances both together (real time); tests must advance
        // the fake clock and the virtual scheduler in lockstep, or the
        // deadline watcher and the retention cutoff diverge.
        harness.clock.instant += duration
        advanceTimeBy(duration)
        runCurrent()
    }

    @Test fun retentionExpiryReachesLiveObserverWithoutUnrelatedWrites() = runTest {
        val harness = harness()
        harness.monitoring.createMonitor(testRunId, MonitorThresholds(delayMinutes = 15), harness.clock.now() + 48.hours)
        val created = assertNotNull(harness.monitoring.observeMonitor(testRunId).first())
        val endedAt = harness.clock.now()
        harness.monitoring.completeTerminally(
            created.id,
            MonitoredTrainSnapshot(harness.arrived(), DataFreshness.Unknown, endedAt),
            listOf(TrainMonitorEvent.Arrived(testRunId)),
            endedAt, 2L)
        // One continuous subscription for the whole test: the repository and
        // its query are never reconstructed.
        val emissions = mutableListOf<List<TrainMonitor>>()
        val collectJob = backgroundScope.launch {
            harness.monitoring.observeEndedMonitors().collect { emissions += it }
        }
        runCurrent()
        assertEquals(1, emissions.last().size)

        // Just before the deadline the item remains.
        advanceBoth(harness, 23.hours)
        assertEquals(1, emissions.last().size)

        // Past the deadline the same subscription emits it absent, driven
        // solely by the deadline watcher (no unrelated DB write occurred).
        advanceBoth(harness, 2.hours)
        assertTrue(emissions.last().isEmpty())
        assertNull(harness.monitoring.observeMonitor(testRunId).first())
        collectJob.cancel()
    }

    @Test fun watcherTracksNextExpiryAcrossMultipleEndedMonitors() = runTest {
        val harness = harness()
        val other = testRunId.copy(number = TrainNumber("456"))
        harness.monitoring.createMonitor(testRunId, MonitorThresholds(delayMinutes = 15), harness.clock.now() + 72.hours)
        val first = assertNotNull(harness.monitoring.observeMonitor(testRunId).first())
        val endedFirst = harness.clock.now()
        harness.monitoring.completeTerminally(
            first.id,
            MonitoredTrainSnapshot(harness.arrived(), DataFreshness.Unknown, endedFirst),
            listOf(TrainMonitorEvent.Arrived(testRunId)),
            endedFirst, 2L)
        harness.clock.instant += 12.hours
        harness.monitoring.createMonitor(other, MonitorThresholds(delayMinutes = 15), harness.clock.now() + 72.hours)
        val second = assertNotNull(harness.monitoring.observeMonitor(other).first())
        val endedSecond = harness.clock.now()
        harness.monitoring.completeTerminally(
            second.id,
            MonitoredTrainSnapshot(harness.arrived(), DataFreshness.Unknown, endedSecond),
            listOf(TrainMonitorEvent.Arrived(other)),
            endedSecond, 2L)

        val emissions = mutableListOf<List<TrainMonitor>>()
        val collectJob = backgroundScope.launch {
            harness.monitoring.observeEndedMonitors().collect { emissions += it }
        }
        runCurrent()
        assertEquals(2, emissions.last().size)

        // fake T+23h from the first expiry basis: both remain.
        advanceBoth(harness, 11.hours)
        assertEquals(2, emissions.last().size)

        // Past the first deadline but before the second: only the later
        // monitor remains, and the watcher re-armed for its expiry.
        advanceBoth(harness, 2.hours)
        assertEquals(listOf(other), emissions.last().map { it.trainRunId })

        // Past the second deadline: none remain.
        advanceBoth(harness, 12.hours)
        assertTrue(emissions.last().isEmpty())
        collectJob.cancel()
    }

    // ---- T7.11 corrective: terminal dispatch decoupling (D8) ----

    @Test fun terminalDispatchSurvivesCollectorSelfCancellation() = runTest {
        val harness = harness()
        harness.seedActive()
        runCurrent()
        harness.trains.trainState.value = DataResult.Data(harness.arrived(), DataFreshness.Unknown)

        // Park the delivery inside claimNotification: with the old inline
        // dispatch, cancelling the refresh caller here would drop the already
        // committed terminal event forever.
        val claimGate = CompletableDeferred<Unit>()
        lateinit var refreshJob: Job
        val decorator = object : MonitoringRepository by harness.monitoring {
            override suspend fun completeTerminally(
                monitorId: MonitorId,
                snapshot: MonitoredTrainSnapshot,
                events: List<TrainMonitorEvent>,
                endedAt: Instant,
                observationOrder: Long?,
            ): List<TrainMonitorEvent> {
                val committed = harness.monitoring.completeTerminally(monitorId, snapshot, events, endedAt, observationOrder)
                // The commit removed the monitor from active observation,
                // which cancels/replaces the polling collector running this
                // refresh — cancel the caller exactly here.
                refreshJob.cancel()
                return committed
            }

            override suspend fun claimNotification(
                event: TrainMonitorEvent,
                emittedAt: Instant,
                cooldown: Duration,
            ): Boolean {
                claimGate.await()
                return harness.monitoring.claimNotification(event, emittedAt, cooldown)
            }
        }
        val racing = MonitoringCoordinator(
            decorator,
            harness.trains,
            harness.services,
            harness.settings,
            backgroundScope,
            harness.clock,
            MonitoringPolicy(),
            localizer = FakeNotificationLocalizer(),
        )
        runCurrent()

        refreshJob = launch { racing.refreshOnce() }
        runCurrent()
        // The caller is dead but the decoupled delivery attempt is parked in
        // the coordinator scope: nothing delivered yet, nothing lost.
        assertEquals(0, harness.notifications.size)
        assertEquals(1, harness.monitoring.observeEndedMonitors().first().size)

        claimGate.complete(Unit)
        runCurrent()
        // Exactly one permitted delivery attempt for the committed event.
        assertEquals(1, harness.notifications.size)
        assertEquals("[L] arrived", harness.notifications.single().body)

        // Duplicate terminal observations and restart never re-deliver it.
        racing.refreshOnce()
        runCurrent()
        assertEquals(1, harness.notifications.size)
    }

    @Test fun presenterFailureIsContainedAndNeverDuplicates() = runTest {
        val harness = harness()
        harness.seedActive()
        runCurrent()
        harness.trains.trainState.value = DataResult.Data(harness.arrived(), DataFreshness.Unknown)
        var shows = 0
        val failingServices = harness.services.copy(
            notifications = NotificationPresenter {
                shows++
                throw IllegalStateException("presenter down")
            },
        )
        val racing = MonitoringCoordinator(
            harness.monitoring,
            harness.trains,
            failingServices,
            harness.settings,
            backgroundScope,
            harness.clock,
            MonitoringPolicy(),
            localizer = FakeNotificationLocalizer(),
        )
        runCurrent()
        // The at-most-once claim is marked before show: the failure is
        // contained (no coordinator crash) and never retried as a duplicate.
        racing.refreshOnce()
        runCurrent()
        assertEquals(1, shows)
        assertEquals(0, harness.notifications.size)
        racing.refreshOnce()
        runCurrent()
        assertEquals(1, shows)
        assertEquals(0, harness.notifications.size)
        assertEquals(1, harness.monitoring.observeEndedMonitors().first().size)
    }

    // ---- T7.11 corrective: failure isolation (D9) ----

    @Test fun cleanupFailureDoesNotKillMonitoring() = runTest {
        val harness = harness()
        harness.seedActive()
        runCurrent()
        val failing = object : MonitoringRepository by harness.monitoring {
            override suspend fun cleanupExpiredEnded(now: Instant): Unit =
                throw IllegalStateException("cleanup down")
        }
        val racing = MonitoringCoordinator(
            failing,
            harness.trains,
            harness.services,
            harness.settings,
            backgroundScope,
            harness.clock,
            MonitoringPolicy(),
            localizer = FakeNotificationLocalizer(),
        )
        runCurrent()
        // Startup cleanup threw inside init: monitoring still works.
        harness.trains.trainState.value = DataResult.Data(harness.delayed(5), DataFreshness.Unknown)
        racing.refreshOnce()
        runCurrent()
        harness.trains.trainState.value = DataResult.Data(harness.delayed(20), DataFreshness.Unknown)
        racing.refreshOnce()
        runCurrent()
        assertEquals(1, harness.notifications.size)
        assertEquals(1, harness.monitoring.observeActiveMonitors().first().size)
    }

    @Test fun oneMonitorFailureDoesNotStopOtherMonitors() = runTest {
        val harness = harness()
        val other = testRunId.copy(number = TrainNumber("456"))
        harness.monitoring.createMonitor(testRunId, MonitorThresholds(delayMinutes = 15), harness.clock.now() + 1.hours)
        harness.monitoring.createMonitor(other, MonitorThresholds(delayMinutes = 15), harness.clock.now() + 1.hours)
        harness.coordinator.refreshOnce()
        runCurrent()
        harness.trains.trainState.value = DataResult.Data(harness.delayed(5), DataFreshness.Unknown)
        harness.coordinator.refreshOnce()
        runCurrent()
        val failing = object : MonitoringRepository by harness.monitoring {
            override suspend fun persistEvaluation(
                monitorId: MonitorId,
                snapshot: MonitoredTrainSnapshot,
                events: List<TrainMonitorEvent>,
                expectedVersion: Long,
                observationOrder: Long?,
            ): EvaluationCommit {
                if (monitorId.value == testRunId.key) throw IllegalStateException("store down")
                return harness.monitoring.persistEvaluation(monitorId, snapshot, events, expectedVersion, observationOrder)
            }
        }
        val racing = MonitoringCoordinator(
            failing,
            harness.trains,
            harness.services,
            harness.settings,
            backgroundScope,
            harness.clock,
            MonitoringPolicy(),
            localizer = FakeNotificationLocalizer(),
        )
        runCurrent()
        harness.trains.trainState.value = DataResult.Data(harness.delayed(20), DataFreshness.Unknown)
        racing.refreshOnce()
        runCurrent()
        // The failing monitor was skipped; the healthy one still committed
        // and notified (same shared observation evaluated per monitor).
        assertEquals(1, harness.notifications.size)
    }

    @Test fun cancellationAlwaysPropagatesOutOfRefresh() = runTest {
        val harness = harness()
        val doomed = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val racing = MonitoringCoordinator(
            harness.monitoring,
            harness.trains,
            harness.services,
            harness.settings,
            doomed,
            harness.clock,
            MonitoringPolicy(),
            localizer = FakeNotificationLocalizer(),
        )
        runCurrent()
        doomed.cancel(CancellationException("shutdown"))
        assertFailsWith<CancellationException> {
            racing.refreshOnce()
        }
    }
}

private object GrantedTerminalPermission : NotificationPermission {
    override suspend fun isGranted() = true
    override suspend fun request() = true
    override suspend fun effective() = EffectiveNotificationPermission.GRANTED
}

private class RecordingTerminalScheduler : BackgroundScheduler {
    val scheduled = mutableListOf<BackgroundTask>()
    override fun register(taskId: String, handler: suspend () -> Unit) = Unit
    override suspend fun schedule(task: BackgroundTask) {
        scheduled += task
    }
    override suspend fun cancel(taskId: String) = Unit
}
