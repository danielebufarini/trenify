package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.MonitorThresholds
import it.danielebufarini.trenify.core.domain.MonitoredTrainSnapshot
import it.danielebufarini.trenify.core.domain.MonitoringRepository
import it.danielebufarini.trenify.core.domain.ObserveActiveMonitors
import it.danielebufarini.trenify.core.domain.ObserveEndedMonitors
import it.danielebufarini.trenify.core.domain.RemoveEndedMonitor
import it.danielebufarini.trenify.core.domain.SetMonitorNotifications
import it.danielebufarini.trenify.core.domain.StopTrainMonitoring
import it.danielebufarini.trenify.core.domain.TrainMonitorEvent
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.ProviderId
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.model.TrainRunId
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.testing.FakeMonitoringRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testRun
import it.danielebufarini.trenify.core.testing.testRunId
import it.danielebufarini.trenify.core.testing.testSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * T8.8 native Monitoring projection: semantic snapshots and capability gating
 * over the existing [MonitoringTabComponent]. All lifecycle, terminal,
 * retention and concurrency behavior stays shared; this suite proves the
 * projection never invents state and ended items can never appear as active.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NativeMonitoringPresentationTest {
    private val at = kotlin.time.Instant.parse("2026-09-14T09:00:00Z")

    private class FlakyNotifications(
        private val delegate: FakeMonitoringRepository,
        var failWrites: Boolean = true,
    ) : MonitoringRepository by delegate {
        override suspend fun setMonitorNotificationsEnabled(trainRunId: TrainRunId, enabled: Boolean) {
            if (failWrites) throw IllegalStateException("disk")
            delegate.setMonitorNotificationsEnabled(trainRunId, enabled)
        }
        override suspend fun cleanupExpiredEnded(now: kotlin.time.Instant): Unit = throw IllegalStateException("disk")
    }

    private fun kotlinx.coroutines.test.TestScope.facade(
        repository: FakeMonitoringRepository,
        clock: MutableClock = MutableClock(at),
        source: MonitoringRepository = repository,
    ): Pair<NativeMonitoringPresentation, LifecycleRegistry> {
        val life = LifecycleRegistry()
        val component = it.danielebufarini.trenify.feature.monitoring.DefaultMonitoringTabComponent(
            DefaultComponentContext(life),
            ObserveActiveMonitors(source),
            StopTrainMonitoring(source),
            {},
            StandardTestDispatcher(testScheduler),
            setMonitorNotifications = SetMonitorNotifications(source),
            observeEndedMonitors = ObserveEndedMonitors(source),
            removeEndedMonitor = RemoveEndedMonitor(source),
            clock = clock,
        )
        return NativeMonitoringPresentation(component, NativeProjectionOwner()) to life
    }

    private suspend fun stageActive(
        repository: FakeMonitoringRepository,
        clock: MutableClock,
        id: TrainRunId = testRunId,
        status: TrainStatus = TrainStatus.RUNNING,
        delay: Int? = 0,
        category: it.danielebufarini.trenify.core.model.TrainCategory? = null,
        scheduledPlatform: String? = null,
        actualPlatform: String? = null,
    ) {
        val created = repository.createMonitor(id, MonitorThresholds(), clock.now() + 48.hours)
        val run = testRun.copy(summary = testSummary.copy(id = id, status = status, delayMinutes = delay,
            scheduledDeparture = clock.now(), scheduledArrival = clock.now() + 3.hours,
            category = category, scheduledPlatform = scheduledPlatform, actualPlatform = actualPlatform))
        repository.persistEvaluation(
            created.id,
            MonitoredTrainSnapshot(run, DataFreshness.Fresh(clock.now(), clock.now()), clock.now()),
            emptyList(),
            created.snapshotVersion,
            0L,
        )
    }

    private suspend fun stageEnded(
        repository: FakeMonitoringRepository,
        clock: MutableClock,
        id: TrainRunId = testRunId,
        status: TrainStatus = TrainStatus.ARRIVED,
    ) {
        val created = repository.createMonitor(id, MonitorThresholds(), clock.now() + 48.hours)
        val run = testRun.copy(summary = testSummary.copy(id = id, status = status))
        val event = if (status == TrainStatus.CANCELLED) TrainMonitorEvent.Cancelled(id) else TrainMonitorEvent.Arrived(id)
        repository.completeTerminally(
            created.id,
            MonitoredTrainSnapshot(run, DataFreshness.Fresh(clock.now(), clock.now()), clock.now()),
            listOf(event),
            clock.now(),
            2L,
        )
    }

    @Test fun emptyProjectsLoadingThenEmptySections() = runTest {
        val repository = FakeMonitoringRepository(MutableClock(at))
        val (facade, life) = facade(repository)
        try {
            runCurrent()
            assertFalse(facade.state.value.loading)
            assertTrue(facade.state.value.active.isEmpty())
            assertTrue(facade.state.value.ended.isEmpty())
        } finally {
            life.destroy()
        }
    }

    @Test fun activeCardProjectsStableIdentityRouteStatusAndGenuineProvenance() = runTest {
        val clock = MutableClock(at)
        val repository = FakeMonitoringRepository(clock)
        val id = testRunId.copy(provider = ProviderId("viaggiatreno"))
        stageActive(repository, clock, id = id, delay = 12)
        val (facade, life) = facade(repository, clock)
        try {
            runCurrent()
            val card = facade.state.value.active.single()
            assertEquals(id.key, card.trainRunKey)
            assertEquals(id.key, card.identity.key)
            assertEquals("123", card.trainNumber)
            assertEquals(TrainStatus.RUNNING, card.status)
            assertEquals(12, card.delayMinutes)
            assertFalse(card.ended)
            assertNull(card.endedAtEpochSeconds)
            assertNotNull(card.evaluatedAtEpochSeconds)
            // Genuine provider identity, never inferred from the operator.
            assertEquals("ViaggiaTreno", card.observation.provenance.providerName)
            assertEquals(NativeRealtimeFreshness.Fresh, card.observation.freshness)
            assertNotNull(card.observation.provenance.fetchedAtEpochSeconds)
            assertNotNull(card.observation.provenance.sourceTimestampEpochSeconds)
            assertTrue(card.hasSnapshot)
            assertTrue(card.canStop)
            assertTrue(card.canToggleNotifications)
            assertFalse(card.canRemove)
            assertFalse(card.canRetryNotifications)
            assertTrue(card.canOpen)
        } finally {
            life.destroy()
        }
    }

    @Test fun activeCardExposesFullOperationalSnapshotSemantics() = runTest {
        val clock = MutableClock(at)
        val repository = FakeMonitoringRepository(clock)
        val id = testRunId.copy(provider = ProviderId("viaggiatreno"))
        stageActive(repository, clock, id = id,
            category = it.danielebufarini.trenify.core.model.TrainCategory.FR,
            scheduledPlatform = "1", actualPlatform = "2")
        val (facade, life) = facade(repository, clock)
        try {
            runCurrent()
            val card = facade.state.value.active.single()
            assertEquals(it.danielebufarini.trenify.core.model.TrainCategory.FR, card.category)
            assertEquals(id.serviceDate, card.identity.serviceDate)
            assertEquals(at, kotlin.time.Instant.fromEpochSeconds(card.scheduledDepartureEpochSeconds!!))
            assertEquals(at + 3.hours, kotlin.time.Instant.fromEpochSeconds(card.scheduledArrivalEpochSeconds!!))
            assertEquals("1", card.scheduledPlatform)
            assertEquals("2", card.actualPlatform)
        } finally {
            life.destroy()
        }
    }

    @Test fun endedArrivedIsTerminalReadOnlyWithRemoveOnly() = runTest {
        val clock = MutableClock(at)
        val repository = FakeMonitoringRepository(clock)
        stageEnded(repository, clock, status = TrainStatus.ARRIVED)
        val (facade, life) = facade(repository, clock)
        try {
            runCurrent()
            assertTrue(facade.state.value.active.isEmpty())
            val card = facade.state.value.ended.single()
            assertEquals(testRunId.key, card.trainRunKey)
            assertTrue(card.ended)
            assertEquals(at, kotlin.time.Instant.fromEpochSeconds(card.endedAtEpochSeconds!!))
            assertEquals(TrainStatus.ARRIVED, card.status)
            assertFalse(card.canStop)
            assertFalse(card.canToggleNotifications)
            assertFalse(card.canRetryNotifications)
            assertTrue(card.canRemove)
            assertTrue(card.canOpen)
        } finally {
            life.destroy()
        }
    }

    @Test fun finalCancelledRetainsFinalSnapshotAsEnded() = runTest {
        val clock = MutableClock(at)
        val repository = FakeMonitoringRepository(clock)
        stageEnded(repository, clock, status = TrainStatus.CANCELLED)
        val (facade, life) = facade(repository, clock)
        try {
            runCurrent()
            assertTrue(facade.state.value.active.isEmpty())
            val card = facade.state.value.ended.single()
            assertEquals(TrainStatus.CANCELLED, card.status)
            assertTrue(card.ended)
            assertTrue(card.canRemove)
            assertFalse(card.canStop)
        } finally {
            life.destroy()
        }
    }

    @Test fun endedItemsCanNeverBeProjectedAsActive() = runTest {
        val clock = MutableClock(at)
        val repository = FakeMonitoringRepository(clock)
        val other = testRunId.copy(number = TrainNumber("456"))
        stageActive(repository, clock, id = other)
        stageEnded(repository, clock, id = testRunId)
        val (facade, life) = facade(repository, clock)
        try {
            runCurrent()
            assertEquals(listOf(other.key), facade.state.value.active.map { it.trainRunKey })
            assertEquals(listOf(testRunId.key), facade.state.value.ended.map { it.trainRunKey })
            assertTrue(facade.state.value.active.none { it.ended })
            assertTrue(facade.state.value.ended.all { it.ended })
        } finally {
            life.destroy()
        }
    }

    @Test fun terminalTransitionMovesActiveToEndedWithRetainedFinalSnapshot() = runTest {
        val clock = MutableClock(at)
        val repository = FakeMonitoringRepository(clock)
        stageActive(repository, clock)
        val (facade, life) = facade(repository, clock)
        try {
            runCurrent()
            assertEquals(1, facade.state.value.active.size)
            val created = assertNotNull(repository.observeMonitor(testRunId).first())
            val arrived = testRun.copy(summary = testSummary.copy(status = TrainStatus.ARRIVED))
            repository.completeTerminally(
                created.id,
                MonitoredTrainSnapshot(arrived, DataFreshness.Fresh(clock.now(), clock.now()), clock.now()),
                listOf(TrainMonitorEvent.Arrived(testRunId)),
                clock.now(),
                2L,
            )
            runCurrent()
            assertTrue(facade.state.value.active.isEmpty())
            val ended = facade.state.value.ended.single()
            assertEquals(TrainStatus.ARRIVED, ended.status)
            assertTrue(ended.hasSnapshot)
        } finally {
            life.destroy()
        }
    }

    @Test fun stopAndRemoveEndedAreGatedBySharedEndedFlag() = runTest {
        val clock = MutableClock(at)
        val repository = FakeMonitoringRepository(clock)
        val other = testRunId.copy(number = TrainNumber("456"))
        stageActive(repository, clock, id = other)
        stageEnded(repository, clock, id = testRunId)
        val (facade, life) = facade(repository, clock)
        try {
            runCurrent()
            // Ended stop/removeEnded/toggle are no-ops: no reactivation, no deletion via the wrong path.
            facade.stop(testRunId.key)
            facade.setMonitorNotifications(testRunId.key, false)
            facade.removeEnded(other.key)
            runCurrent()
            assertNotNull(repository.observeMonitor(testRunId).first())
            assertNotNull(repository.observeMonitor(other).first())
            // Active stop removes immediately with no retention; ended removal deletes permanently.
            facade.stop(other.key)
            runCurrent()
            assertNull(repository.observeMonitor(other).first())
            assertTrue(facade.state.value.active.isEmpty())
            facade.removeEnded(testRunId.key)
            runCurrent()
            assertNull(repository.observeMonitor(testRunId).first())
            assertTrue(facade.state.value.ended.isEmpty())
        } finally {
            life.destroy()
        }
    }

    @Test fun notificationTogglePendingFailureAndRetry() = runTest {
        val clock = MutableClock(at)
        val repository = FakeMonitoringRepository(clock)
        stageActive(repository, clock)
        val (facade, life) = facade(repository, clock)
        try {
            runCurrent()
            facade.setMonitorNotifications(testRunId.key, false)
            runCurrent()
            assertFalse(facade.state.value.active.single().notificationsEnabled)
            assertFalse(facade.state.value.active.single().notificationsPending)
        } finally {
            life.destroy()
        }
    }

    @Test fun notificationFailureSurfacesRetryableState() = runTest {
        val clock = MutableClock(at)
        val repository = FakeMonitoringRepository(clock)
        stageActive(repository, clock)
        val (facade, life) = facade(repository, clock, source = FlakyNotifications(repository))
        try {
            runCurrent()
            facade.setMonitorNotifications(testRunId.key, false)
            runCurrent()
            val card = facade.state.value.active.single()
            assertTrue(card.notificationsFailed)
            assertTrue(card.canRetryNotifications)
            // The persisted value is untouched by the failed write.
            assertTrue(card.notificationsEnabled)
        } finally {
            life.destroy()
        }
    }

    @Test fun refreshFailureRetainsContentAsStaleDegraded() = runTest {
        val clock = MutableClock(at)
        val repository = FakeMonitoringRepository(clock)
        stageActive(repository, clock)
        val created = assertNotNull(repository.observeMonitor(testRunId).first())
        repository.recordRefreshFailure(created.id, DomainFailure.OFFLINE, clock.now())
        val (facade, life) = facade(repository, clock)
        try {
            runCurrent()
            val card = facade.state.value.active.single()
            assertTrue(card.hasSnapshot)
            assertEquals(DomainFailure.OFFLINE, card.refreshFailure)
            assertEquals(NativeRealtimeFreshness.Stale, card.observation.freshness)
            assertTrue(card.observation.provenance.degraded)
            assertTrue(card.hasSnapshot)
        } finally {
            life.destroy()
        }
    }

    @Test fun unknownProvenanceCarriesNoTimestamps() = runTest {
        val clock = MutableClock(at)
        val repository = FakeMonitoringRepository(clock)
        val created = repository.createMonitor(testRunId, MonitorThresholds(), clock.now() + 48.hours)
        repository.persistEvaluation(
            created.id,
            MonitoredTrainSnapshot(testRun, DataFreshness.Unknown, clock.now()),
            emptyList(),
            created.snapshotVersion,
            0L,
        )
        val (facade, life) = facade(repository, clock)
        try {
            runCurrent()
            val card = facade.state.value.active.single()
            assertEquals(NativeRealtimeFreshness.Unknown, card.observation.freshness)
            assertNull(card.observation.provenance.fetchedAtEpochSeconds)
            assertNull(card.observation.provenance.sourceTimestampEpochSeconds)
            // Unknown provider ids never invent a source name.
            assertNull(card.observation.provenance.providerName)
        } finally {
            life.destroy()
        }
    }

    @Test fun expiredEndedDisappearsAfterRetentionBoundary() = runTest {
        val clock = MutableClock(at)
        val repository = FakeMonitoringRepository(clock)
        stageEnded(repository, clock)
        val (facade, life) = facade(repository, clock)
        try {
            runCurrent()
            assertEquals(1, facade.state.value.ended.size)
            clock.instant += 24.hours
            repository.cleanupExpiredEnded(clock.now())
            runCurrent()
            assertTrue(facade.state.value.ended.isEmpty())
            assertTrue(facade.state.value.active.isEmpty())
        } finally {
            life.destroy()
        }
    }

    @Test fun expiredEndedExcludedEvenWhenPhysicalCleanupFails() = runTest {
        // Authoritative T7.11 contract: retention visibility is a logical time
        // policy on the observation, not a function of successful physical
        // cleanup. A failed best-effort deletion leaves the stale row
        // persisted but must NOT keep it visible in Recently ended.
        val clock = MutableClock(at)
        val repository = FakeMonitoringRepository(clock)
        val other = testRunId.copy(number = TrainNumber("456"))
        stageEnded(repository, clock)
        stageActive(repository, clock, id = other)
        val flaky = FlakyNotifications(repository)
        val (facade, life) = facade(repository, clock, source = flaky)
        try {
            runCurrent()
            assertEquals(1, facade.state.value.ended.size)
            clock.instant += 24.hours + 1.minutes
            val cleanupFailure = try {
                flaky.cleanupExpiredEnded(clock.now())
                null
            } catch (expected: IllegalStateException) {
                expected
            }
            assertNotNull(cleanupFailure)
            // A harmless write on the surviving monitor forces re-emission;
            // the expired item is logically excluded despite cleanup failure.
            flaky.failWrites = false
            facade.setMonitorNotifications(other.key, false)
            runCurrent()
            assertTrue(facade.state.value.ended.isEmpty())
            assertEquals(listOf(other.key), facade.state.value.active.map { it.trainRunKey })
            // The stale row still exists physically; visibility never depended on deletion.
            assertNotNull(repository.observeMonitor(testRunId).first())
        } finally {
            life.destroy()
        }
    }

    @Test fun staleRetryAfterTerminalIsProvenNoOp() = runTest {
        val clock = MutableClock(at)
        val repository = FakeMonitoringRepository(clock)
        val other = testRunId.copy(number = TrainNumber("456"))
        stageActive(repository, clock)
        stageActive(repository, clock, id = other)
        val flaky = FlakyNotifications(repository)
        val (facade, life) = facade(repository, clock, source = flaky)
        try {
            runCurrent()
            facade.setMonitorNotifications(testRunId.key, false)
            runCurrent()
            assertTrue(facade.state.value.active.single { it.trainRunKey == testRunId.key }.notificationsFailed)
            // The monitor reaches terminal state; the failure now refers to an ended row.
            val created = assertNotNull(repository.observeMonitor(testRunId).first())
            val arrived = testRun.copy(summary = testSummary.copy(status = TrainStatus.ARRIVED))
            repository.completeTerminally(
                created.id,
                MonitoredTrainSnapshot(arrived, DataFreshness.Fresh(clock.now(), clock.now()), clock.now()),
                listOf(TrainMonitorEvent.Arrived(testRunId)),
                clock.now(),
                2L,
            )
            runCurrent()
            assertEquals(listOf(other.key), facade.state.value.active.map { it.trainRunKey })
            val endedBefore = assertNotNull(repository.observeMonitor(testRunId).first())
            // A stale retry must neither mutate the ended record nor reactivate it.
            facade.retryMonitorNotifications()
            runCurrent()
            val endedAfter = assertNotNull(repository.observeMonitor(testRunId).first())
            assertEquals(endedBefore, endedAfter)
            assertTrue(endedAfter.notificationsEnabled)
            assertNotNull(endedAfter.endedAt)
            assertTrue(facade.state.value.active.none { it.trainRunKey == testRunId.key })
            assertEquals(listOf(testRunId.key), facade.state.value.ended.map { it.trainRunKey })
            // The surviving monitor is untouched by the stale retry.
            assertTrue(facade.state.value.active.single().notificationsEnabled)
        } finally {
            life.destroy()
        }
    }

    @Test fun retryWhileStillActivePreservesExistingBehavior() = runTest {
        val clock = MutableClock(at)
        val repository = FakeMonitoringRepository(clock)
        stageActive(repository, clock)
        val flaky = FlakyNotifications(repository)
        val (facade, life) = facade(repository, clock, source = flaky)
        try {
            runCurrent()
            facade.setMonitorNotifications(testRunId.key, false)
            runCurrent()
            assertTrue(facade.state.value.active.single().notificationsFailed)
            flaky.failWrites = false
            facade.retryMonitorNotifications()
            runCurrent()
            val card = facade.state.value.active.single()
            assertFalse(card.notificationsEnabled)
            assertFalse(card.notificationsFailed)
            assertFalse(card.canRetryNotifications)
        } finally {
            life.destroy()
        }
    }

    @Test fun openForwardsWithoutAlteringEndedState() = runTest {
        val clock = MutableClock(at)
        val repository = FakeMonitoringRepository(clock)
        stageEnded(repository, clock)
        var opened = 0
        val life = LifecycleRegistry()
        val component = it.danielebufarini.trenify.feature.monitoring.DefaultMonitoringTabComponent(
            DefaultComponentContext(life),
            ObserveActiveMonitors(repository),
            StopTrainMonitoring(repository),
            { _ -> opened++ },
            StandardTestDispatcher(testScheduler),
            observeEndedMonitors = ObserveEndedMonitors(repository),
            removeEndedMonitor = RemoveEndedMonitor(repository),
            clock = clock,
        )
        val facade = NativeMonitoringPresentation(component, NativeProjectionOwner())
        try {
            runCurrent()
            facade.open(testRunId.key)
            assertEquals(1, opened)
            val ended = assertNotNull(repository.observeMonitor(testRunId).first())
            assertNotNull(ended.endedAt)
            assertTrue(repository.observeActiveMonitors().first().isEmpty())
        } finally {
            life.destroy()
        }
    }

    @Test fun shellMonitoringExposesNativeFacade() = runTest {
        val clock = MutableClock(at)
        val repository = FakeMonitoringRepository(clock)
        stageActive(repository, clock)
        val realtime = FakeRealtimeRepositories()
        val life = LifecycleRegistry()
        val factory = AppComponentFactory(realtime, realtime, realtime, realtime,
            MutableStateFlow(false), StandardTestDispatcher(testScheduler), monitoringRepository = repository)
        val root = DefaultRootComponent(DefaultComponentContext(life), factory)
        val shell = createShellPresentation(root)
        try {
            runCurrent()
            shell.select(NativePrimaryArea.Monitoring)
            runCurrent()
            assertEquals(NativeDestination.Monitoring, shell.state.value.active.destination)
            val facade = assertNotNull(shell.state.value.active.monitoring)
            runCurrent()
            assertEquals(1, facade.state.value.active.size)
            assertEquals(testRunId.key, facade.state.value.active.single().trainRunKey)
            shell.close()
        } finally {
            life.destroy()
        }
    }

    @Test fun unresolvedLoadingExposesNoCards() = runTest {
        val repository = FakeMonitoringRepository(MutableClock(at))
        val life = LifecycleRegistry()
        // A source that completes without ever emitting keeps the component in
        // its unresolved loading state: no cards, no capabilities.
        val component = it.danielebufarini.trenify.feature.monitoring.DefaultMonitoringTabComponent(
            DefaultComponentContext(life),
            ObserveActiveMonitors(object : MonitoringRepository by repository {
                override fun observeActiveMonitors() = kotlinx.coroutines.flow.emptyFlow<List<it.danielebufarini.trenify.core.domain.TrainMonitor>>()
            }),
            StopTrainMonitoring(repository),
            {},
            StandardTestDispatcher(testScheduler),
            observeEndedMonitors = ObserveEndedMonitors(repository),
            removeEndedMonitor = RemoveEndedMonitor(repository),
            clock = MutableClock(at),
        )
        val facade = NativeMonitoringPresentation(component, NativeProjectionOwner())
        try {
            runCurrent()
            // Before the first observation delivers, loading stays true with no cards.
            assertTrue(facade.state.value.loading)
            assertTrue(facade.state.value.active.isEmpty())
            assertTrue(facade.state.value.ended.isEmpty())
        } finally {
            life.destroy()
        }
    }
}
