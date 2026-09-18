package it.danielebufarini.trenify.app

import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.EvaluationCommit
import it.danielebufarini.trenify.core.domain.MonitorId
import it.danielebufarini.trenify.core.domain.MonitorThresholds
import it.danielebufarini.trenify.core.domain.MonitoredTrainSnapshot
import it.danielebufarini.trenify.core.domain.MonitoringPolicy
import it.danielebufarini.trenify.core.domain.MonitoringRepository
import it.danielebufarini.trenify.core.domain.TrainMonitorEvent
import it.danielebufarini.trenify.core.model.ExternalStationRef
import it.danielebufarini.trenify.core.model.ProviderId
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.model.TrainRun
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
import it.danielebufarini.trenify.core.provider.api.ProviderCapabilities
import it.danielebufarini.trenify.core.provider.api.ProviderFailure
import it.danielebufarini.trenify.core.provider.api.ProviderMetadata
import it.danielebufarini.trenify.core.provider.api.ProviderResult
import it.danielebufarini.trenify.core.provider.api.ProviderStation
import it.danielebufarini.trenify.core.provider.api.ProviderTrainCandidate
import it.danielebufarini.trenify.core.provider.api.ProviderTrainRunRef
import it.danielebufarini.trenify.core.provider.api.ProviderTrainSnapshot
import it.danielebufarini.trenify.core.provider.api.ProviderTrainStop
import it.danielebufarini.trenify.core.provider.api.TrainRealtimeProvider
import it.danielebufarini.trenify.core.testing.FakeNotificationSettingsRepository
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testRunId
import it.danielebufarini.trenify.data.RealtimeRepositories
import it.danielebufarini.trenify.data.SqlDelightMonitoringRepository
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * T7.11 corrective pass 2: persistence-shared refresh ordering end to end
 * through the production TrainRepository.refreshTrain →
 * MonitoringCoordinator → MonitoringRepository path. Two independent
 * graph-equivalent instance pairs share one real test database; their
 * instance-local flights cannot order each other, so ordering must come
 * from persistence.
 *
 * Each staged provider call retains its own response value at script time;
 * the gate only delays its return.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitoringRefreshOrderingTest {
    data class StagedCall(
        val snapshot: ProviderTrainSnapshot,
        val gate: CompletableDeferred<Unit> = CompletableDeferred<Unit>().apply { complete(Unit) },
        val fail: Boolean = false,
    )

    class StagedTrainProvider(private val clock: MutableClock) : TrainRealtimeProvider {
        override val id = ProviderId("test")
        override val capabilities = ProviderCapabilities()
        val origin = ProviderStation(ExternalStationRef("opaque-origin"), "Roma Termini")
        private var nextIndex = 0
        val script = mutableListOf<StagedCall>()

        fun snapshot(status: TrainStatus, delayMinutes: Int? = null): ProviderTrainSnapshot {
            val ref = ProviderTrainRunRef(testRunId.number, origin.ref, testRunId.serviceDate)
            val candidate = ProviderTrainCandidate(
                ref, origin, "Milano Centrale", status = status, delayMinutes = delayMinutes,
            )
            // Every staged observation serves the same stop through the same
            // provider mapping, so evaluations differ only in the staged
            // status/delay — never in spurious route-change noise.
            return ProviderTrainSnapshot(candidate, listOf(ProviderTrainStop(origin)))
        }

        override suspend fun getTrainSnapshot(ref: ProviderTrainRunRef): ProviderResult<ProviderTrainSnapshot> {
            val call = script[nextIndex++]
            call.gate.await()
            if (call.fail) return ProviderResult.Unavailable(true, ProviderFailure.TIMEOUT)
            return ProviderResult.Success(call.snapshot, ProviderMetadata(id, clock.now(), clock.now()))
        }

        override suspend fun searchStations(query: String, limit: Int) = ProviderResult.NotFound
        override suspend fun departures(station: ExternalStationRef, at: kotlin.time.Instant) =
            ProviderResult.NotFound
        override suspend fun arrivals(station: ExternalStationRef, at: kotlin.time.Instant) =
            ProviderResult.NotFound
        override suspend fun findTrainCandidates(number: TrainNumber, serviceDate: LocalDate?) =
            ProviderResult.NotFound
    }

    class Graph(
        database: TrenifyDatabase,
        provider: StagedTrainProvider,
        val clock: MutableClock,
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
        val notifications: MutableList<NotificationMessage>,
    ) {
        val trains = RealtimeRepositories(database, listOf(provider), scope, clock = clock, dispatcher = dispatcher)
        val monitoring = SqlDelightMonitoringRepository(database, clock, dispatcher)
        private val appState = MutableStateFlow(ApplicationState.Background)
        private val connectivity = MutableStateFlow(ConnectivityStatus.Available)
        val services = PlatformServices(
            Connectivity { connectivity },
            ApplicationStateObserver { appState },
            NotificationPresenter(notifications::add),
            GrantedOrderingPermission,
            RecordingOrderingScheduler(),
            ExternalUrlLauncher { false },
            LifecycleIntegration { appState.value = it },
        )
        val settings = FakeNotificationSettingsRepository()
        val coordinator = MonitoringCoordinator(
            monitoring, trains, services, settings, scope, clock, MonitoringPolicy(),
            localizer = FakeNotificationLocalizer(),
        )
    }

    private suspend fun kotlinx.coroutines.test.TestScope.twoGraphs(
        block: suspend kotlinx.coroutines.test.TestScope.(Graph, Graph, StagedTrainProvider, MutableClock, TrenifyDatabase) -> Unit,
    ) {
        val driver = createOrderingTestDriver()
        val database = TrenifyDatabase(driver)
        val clock = MutableClock()
        val provider = StagedTrainProvider(clock)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val foreground = Graph(database, provider, clock, backgroundScope, dispatcher, mutableListOf())
        val background = Graph(database, provider, clock, backgroundScope, dispatcher, mutableListOf())
        try {
            block(foreground, background, provider, clock, database)
        } finally {
            driver.close()
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.seedRunningBaseline(
        graph: Graph,
        provider: StagedTrainProvider,
    ) {
        graph.monitoring.createMonitor(
            testRunId, MonitorThresholds(delayMinutes = 15), graph.clock.now() + 48.hours,
        )
        // The baseline commits through the production refresh path, so the
        // provider station mapping is shared with every later staged
        // observation. The first observation has no previous snapshot, hence
        // no events and no delivery.
        provider.script += StagedCall(provider.snapshot(TrainStatus.RUNNING))
        graph.coordinator.refreshOnce()
        runCurrent()
        val monitor = assertNotNull(graph.monitoring.observeMonitor(testRunId).first())
        assertEquals(TrainStatus.RUNNING, monitor.lastSnapshot?.train?.summary?.status)
        assertTrue(monitor.lastEvent == null)
        assertTrue(graph.notifications.isEmpty())
    }

    private fun data(result: DataResult<TrainRun>): DataResult.Data<TrainRun> =
        assertIs<DataResult.Data<TrainRun>>(result)

    private suspend fun acceptedMonitorGeneration(database: TrenifyDatabase): Long? =
        database.realtimeQueries.monitorStateById(
            it.danielebufarini.trenify.core.domain.MonitorId(testRunId.key).value,
        ).executeAsOneOrNull()?.accepted_refresh_generation

    /**
     * T7.11 final pass blocker regression: the earlier terminal invocation
     * commits while the later terminal request is still pending, and the
     * later accepted observation must then correct the retained final
     * snapshot — cache and monitor governed by the same request/observation
     * order, with endedAt and ENDED monotonic.
     */
    @Test fun earlierTerminalCommitsWhileLaterTerminalPendingThenLaterCorrectsEndToEnd() = runTest {
        twoGraphs { foreground, background, provider, clock, database ->
            seedRunningBaseline(foreground, provider)
            runCurrent()

            // A invoked first (token 2, ARRIVED); B invoked later (token 3,
            // CANCELLED). Both park inside the provider fetch so invocation
            // order — and therefore token order — is deterministic.
            val gateA = CompletableDeferred<Unit>()
            val gateB = CompletableDeferred<Unit>()
            provider.script += StagedCall(provider.snapshot(TrainStatus.ARRIVED), gateA)
            provider.script += StagedCall(provider.snapshot(TrainStatus.CANCELLED), gateB)
            val jobA = async { foreground.coordinator.refreshOnce() }
            runCurrent()
            val jobB = async { background.coordinator.refreshOnce() }
            runCurrent()

            // A completes first while B remains pending: the shared cache
            // accepts ARRIVED and monitoring commits the ARRIVED terminal
            // state, establishing endedAt with the generation-2 observation.
            gateA.complete(Unit)
            runCurrent()
            jobA.await()
            runCurrent()
            val endedAt = assertNotNull(foreground.monitoring.observeMonitor(testRunId).first()).endedAt
            assertNotNull(endedAt)
            assertEquals(
                TrainStatus.ARRIVED,
                data(foreground.trains.observeTrain(testRunId).first()).value.summary.status,
            )
            assertEquals(
                TrainStatus.ARRIVED,
                assertNotNull(foreground.monitoring.observeMonitor(testRunId).first())
                    .lastSnapshot?.train?.summary?.status,
            )
            assertEquals(2L, acceptedMonitorGeneration(database))
            assertTrue(jobB.isActive, "B must still be pending while A commits")

            // B completes later: the cache accepts CANCELLED with generation
            // 3 and terminal persistence applies the generation-3 correction.
            clock.instant += 1.minutes
            gateB.complete(Unit)
            runCurrent()
            jobB.await()
            runCurrent()

            assertEquals(
                TrainStatus.CANCELLED,
                data(foreground.trains.observeTrain(testRunId).first()).value.summary.status,
            )
            val monitor = assertNotNull(foreground.monitoring.observeMonitor(testRunId).first())
            assertEquals(endedAt, monitor.endedAt, "endedAt never moves across terminal corrections")
            assertEquals(TrainStatus.CANCELLED, monitor.lastSnapshot?.train?.summary?.status)
            assertEquals(
                TrainMonitorEvent.Cancelled(testRunId),
                monitor.lastEvent,
            )
            assertEquals(3L, acceptedMonitorGeneration(database))
            assertTrue(foreground.monitoring.observeActiveMonitors().first().isEmpty())
            assertTrue(background.monitoring.observeActiveMonitors().first().isEmpty())

            // No lifecycle reactivation: a further refresh neither polls the
            // ended monitor nor moves its terminal state.
            clock.instant += 1.minutes
            foreground.coordinator.refreshOnce()
            runCurrent()
            val quiescent = assertNotNull(foreground.monitoring.observeMonitor(testRunId).first())
            assertEquals(endedAt, quiescent.endedAt)
            assertEquals(TrainStatus.CANCELLED, quiescent.lastSnapshot?.train?.summary?.status)
        }
    }

    @Test fun inverseTerminalOrderArrivedCorrectsCancelledEndToEnd() = runTest {
        twoGraphs { foreground, background, provider, clock, database ->
            seedRunningBaseline(foreground, provider)
            runCurrent()

            val gateA = CompletableDeferred<Unit>()
            val gateB = CompletableDeferred<Unit>()
            provider.script += StagedCall(provider.snapshot(TrainStatus.CANCELLED), gateA)
            provider.script += StagedCall(provider.snapshot(TrainStatus.ARRIVED), gateB)
            val jobA = async { foreground.coordinator.refreshOnce() }
            runCurrent()
            val jobB = async { background.coordinator.refreshOnce() }
            runCurrent()

            gateA.complete(Unit)
            runCurrent()
            jobA.await()
            runCurrent()
            val endedAt = assertNotNull(foreground.monitoring.observeMonitor(testRunId).first()).endedAt
            assertNotNull(endedAt)
            assertEquals(
                TrainStatus.CANCELLED,
                assertNotNull(foreground.monitoring.observeMonitor(testRunId).first())
                    .lastSnapshot?.train?.summary?.status,
            )

            // Rule is request/observation order, not status precedence: the
            // later ARRIVED observation becomes the authoritative final
            // snapshot.
            clock.instant += 1.minutes
            gateB.complete(Unit)
            runCurrent()
            jobB.await()
            runCurrent()

            assertEquals(
                TrainStatus.ARRIVED,
                data(foreground.trains.observeTrain(testRunId).first()).value.summary.status,
            )
            val monitor = assertNotNull(foreground.monitoring.observeMonitor(testRunId).first())
            assertEquals(endedAt, monitor.endedAt)
            assertEquals(TrainStatus.ARRIVED, monitor.lastSnapshot?.train?.summary?.status)
            assertEquals(3L, acceptedMonitorGeneration(database))
            assertTrue(foreground.monitoring.observeActiveMonitors().first().isEmpty())
        }
    }

    /**
     * T7.11 final-pass verification correction: A invoked first with a
     * successful RESCHEDULED response, B invoked later with FAILURE remaining
     * pending; A completes and commits, B fails afterwards. The cache and the
     * monitor remain A's accepted result with no fallback transition and no
     * event regression. Fixed staged outcomes; no mutable response state.
     */
    @Test fun earlierSuccessCommitsBeforeLaterFailureEndToEnd() = runTest {
        twoGraphs { foreground, background, provider, clock, _ ->
            seedRunningBaseline(foreground, provider)
            runCurrent()

            val gateA = CompletableDeferred<Unit>()
            val gateB = CompletableDeferred<Unit>()
            provider.script += StagedCall(provider.snapshot(TrainStatus.RESCHEDULED, 30), gateA)
            provider.script += StagedCall(provider.snapshot(TrainStatus.RUNNING), gateB, fail = true)
            val jobA = async { foreground.coordinator.refreshOnce() }
            runCurrent()
            val jobB = async { background.coordinator.refreshOnce() }
            runCurrent()

            // A completes and commits while B remains pending.
            gateA.complete(Unit)
            runCurrent()
            jobA.await()
            runCurrent()
            assertEquals(1, foreground.notifications.size)

            // B fails afterwards: it commits nothing — the RESCHEDULED cache
            // and monitor survive with no RUNNING regression, no false
            // transition and no extra delivery.
            clock.instant += 1.minutes
            gateB.complete(Unit)
            runCurrent()
            jobB.await()
            runCurrent()

            assertEquals(
                TrainStatus.RESCHEDULED,
                data(foreground.trains.observeTrain(testRunId).first()).value.summary.status,
            )
            val monitor = assertNotNull(foreground.monitoring.observeMonitor(testRunId).first())
            assertEquals(TrainStatus.RESCHEDULED, monitor.lastSnapshot?.train?.summary?.status)
            assertEquals(
                TrainMonitorEvent.StatusChanged(testRunId, TrainStatus.RUNNING, TrainStatus.RESCHEDULED),
                monitor.lastEvent,
            )
            assertEquals(1, foreground.notifications.size + background.notifications.size)
        }
    }

    @Test fun olderNonTerminalCannotRegressNewerNonTerminalEndToEnd() = runTest {
        twoGraphs { foreground, background, provider, clock, _ ->
            seedRunningBaseline(foreground, provider)
            runCurrent()

            // A starts first and parks with old RUNNING.
            val gateA = CompletableDeferred<Unit>()
            provider.script += StagedCall(provider.snapshot(TrainStatus.RUNNING), gateA)
            val jobA = async { foreground.coordinator.refreshOnce() }
            runCurrent()

            // B starts later, returns RESCHEDULED and commits the single
            // genuine transition.
            clock.instant += 1.minutes
            provider.script += StagedCall(provider.snapshot(TrainStatus.RESCHEDULED, 30))
            background.coordinator.refreshOnce()
            runCurrent()
            assertEquals(1, background.notifications.size)

            // A resumes: shared cache and monitor snapshot stay RESCHEDULED
            // with exactly one transition — no later RUNNING regression.
            clock.instant += 1.minutes
            gateA.complete(Unit)
            runCurrent()
            jobA.await()
            runCurrent()

            assertEquals(
                TrainStatus.RESCHEDULED,
                data(foreground.trains.observeTrain(testRunId).first()).value.summary.status,
            )
            val monitor = assertNotNull(foreground.monitoring.observeMonitor(testRunId).first())
            assertEquals(TrainStatus.RESCHEDULED, monitor.lastSnapshot?.train?.summary?.status)
            assertEquals(
                TrainMonitorEvent.StatusChanged(testRunId, TrainStatus.RUNNING, TrainStatus.RESCHEDULED),
                monitor.lastEvent,
            )
            // Seed v1, B's transition v2: A's superseded observation is the
            // accepted RESCHEDULED cache, so its commit is a no-op metadata
            // refresh (v3) that preserves the snapshot, the single
            // transition event and the single delivery — never a RUNNING
            // regression. A strictly older observation would be dropped by
            // the order guard instead (pinned by the StaleBase retry test).
            assertEquals(3L, monitor.snapshotVersion)
            assertEquals(1, foreground.notifications.size + background.notifications.size)
        }
    }

    @Test fun olderTerminalCannotRegressNewerTerminalEndToEnd() = runTest {
        twoGraphs { foreground, background, provider, clock, _ ->
            seedRunningBaseline(foreground, provider)
            runCurrent()

            // A starts first and parks with CANCELLED.
            val gateA = CompletableDeferred<Unit>()
            provider.script += StagedCall(provider.snapshot(TrainStatus.CANCELLED), gateA)
            val jobA = async { foreground.coordinator.refreshOnce() }
            runCurrent()

            // B starts later and commits ARRIVED terminally.
            clock.instant += 1.minutes
            provider.script += StagedCall(provider.snapshot(TrainStatus.ARRIVED))
            background.coordinator.refreshOnce()
            runCurrent()
            val endedAt = assertNotNull(background.monitoring.observeMonitor(testRunId).first()).endedAt
            assertNotNull(endedAt)

            // A resumes: the accepted ARRIVED cache and the stable ended
            // monitor survive the older terminal invocation.
            clock.instant += 1.minutes
            gateA.complete(Unit)
            runCurrent()
            jobA.await()
            runCurrent()

            assertEquals(
                TrainStatus.ARRIVED,
                data(foreground.trains.observeTrain(testRunId).first()).value.summary.status,
            )
            val monitor = assertNotNull(foreground.monitoring.observeMonitor(testRunId).first())
            assertEquals(endedAt, monitor.endedAt)
            assertEquals(TrainStatus.ARRIVED, monitor.lastSnapshot?.train?.summary?.status)
            assertTrue(foreground.monitoring.observeActiveMonitors().first().isEmpty())
        }
    }

    @Test fun laterTerminalInvocationLegitimatelyWinsEndToEnd() = runTest {
        twoGraphs { foreground, background, provider, clock, _ ->
            seedRunningBaseline(foreground, provider)
            runCurrent()

            // A starts first and parks with ARRIVED.
            val gateA = CompletableDeferred<Unit>()
            provider.script += StagedCall(provider.snapshot(TrainStatus.ARRIVED), gateA)
            val jobA = async { foreground.coordinator.refreshOnce() }
            runCurrent()

            // B starts later and commits CANCELLED: the later invocation
            // wins the cache, and its terminal commit ends the monitor.
            clock.instant += 1.minutes
            provider.script += StagedCall(provider.snapshot(TrainStatus.CANCELLED))
            background.coordinator.refreshOnce()
            runCurrent()

            gateA.complete(Unit)
            runCurrent()
            jobA.await()
            runCurrent()

            assertEquals(
                TrainStatus.CANCELLED,
                data(foreground.trains.observeTrain(testRunId).first()).value.summary.status,
            )
            val monitor = assertNotNull(foreground.monitoring.observeMonitor(testRunId).first())
            assertNotNull(monitor.endedAt)
            assertEquals(TrainStatus.CANCELLED, monitor.lastSnapshot?.train?.summary?.status)
        }
    }

    @Test fun staleBaseRetryDropsOlderObservationAfterNewerCommitEndToEnd() = runTest {
        twoGraphs { foreground, background, provider, clock, _ ->
            seedRunningBaseline(foreground, provider)
            runCurrent()

            // Park A's first evaluation commit at the real repository
            // boundary (same decorator technique as the self-cancellation
            // tests): while A is parked, a newer observation commits.
            val holdFirstCommit = CompletableDeferred<Unit>()
            var parkFirst = true
            val parked = object : MonitoringRepository by foreground.monitoring {
                override suspend fun persistEvaluation(
                    monitorId: MonitorId,
                    snapshot: MonitoredTrainSnapshot,
                    events: List<TrainMonitorEvent>,
                    expectedVersion: Long,
                    observationOrder: Long?,
                ): EvaluationCommit {
                    if (parkFirst) {
                        parkFirst = false
                        holdFirstCommit.await()
                    }
                    return foreground.monitoring.persistEvaluation(
                        monitorId, snapshot, events, expectedVersion, observationOrder,
                    )
                }
            }
            val coordinatorA = MonitoringCoordinator(
                parked, foreground.trains, foreground.services, foreground.settings,
                backgroundScope, clock, MonitoringPolicy(),

                localizer = FakeNotificationLocalizer(),
            )
            runCurrent()

            // A refreshes with RUNNING and reaches its parked commit.
            provider.script += StagedCall(provider.snapshot(TrainStatus.RUNNING))
            val jobA = async { coordinatorA.refreshOnce() }
            runCurrent()

            // B (newer invocation, independent graph) commits RESCHEDULED
            // end to end while A is parked.
            clock.instant += 1.minutes
            provider.script += StagedCall(provider.snapshot(TrainStatus.RESCHEDULED, 30))
            background.coordinator.refreshOnce()
            runCurrent()

            // A resumes: its initial commit loses the version race, and its
            // bounded retry meets a fresh base whose accepted observation
            // generation is newer — the older observation is dropped even
            // though the retried version matches. An order-blind retry would
            // regress the snapshot to RUNNING and duplicate the transition.
            clock.instant += 1.minutes
            holdFirstCommit.complete(Unit)
            runCurrent()
            jobA.await()
            runCurrent()

            val monitor = assertNotNull(foreground.monitoring.observeMonitor(testRunId).first())
            assertEquals(TrainStatus.RESCHEDULED, monitor.lastSnapshot?.train?.summary?.status)
            assertEquals(
                TrainMonitorEvent.StatusChanged(testRunId, TrainStatus.RUNNING, TrainStatus.RESCHEDULED),
                monitor.lastEvent,
            )
            assertEquals(2L, monitor.snapshotVersion)
            assertEquals(1, background.notifications.size)
            assertTrue(foreground.notifications.isEmpty())
        }
    }

    @Test fun olderNonTerminalCannotRegressNewerTerminalEndToEnd() = runTest {
        twoGraphs { foreground, background, provider, clock, _ ->
            seedRunningBaseline(foreground, provider)
            runCurrent()

            // A starts first and parks with RUNNING.
            val gateA = CompletableDeferred<Unit>()
            provider.script += StagedCall(provider.snapshot(TrainStatus.RUNNING), gateA)
            val jobA = async { foreground.coordinator.refreshOnce() }
            runCurrent()

            // B starts later and commits ARRIVED terminally.
            clock.instant += 1.minutes
            provider.script += StagedCall(provider.snapshot(TrainStatus.ARRIVED))
            background.coordinator.refreshOnce()
            runCurrent()
            val endedAt = assertNotNull(background.monitoring.observeMonitor(testRunId).first()).endedAt
            assertNotNull(endedAt)

            // A resumes: the older non-terminal invocation must not regress
            // the newer accepted terminal cache or the stable ended monitor.
            clock.instant += 1.minutes
            gateA.complete(Unit)
            runCurrent()
            jobA.await()
            runCurrent()

            assertEquals(
                TrainStatus.ARRIVED,
                data(foreground.trains.observeTrain(testRunId).first()).value.summary.status,
            )
            val monitor = assertNotNull(foreground.monitoring.observeMonitor(testRunId).first())
            assertEquals(endedAt, monitor.endedAt)
            assertEquals(TrainStatus.ARRIVED, monitor.lastSnapshot?.train?.summary?.status)
            assertTrue(foreground.monitoring.observeActiveMonitors().first().isEmpty())
        }
    }

    /**
     * Complementary variant: the failing request is invoked first (older
     * token) while the success is invoked later. The failure still commits
     * nothing. See [earlierSuccessCommitsBeforeLaterFailureEndToEnd] for the
     * exact A-first/B-later deterministic proof.
     */
    @Test fun laterFailureCannotRestoreOlderObservationEndToEnd() = runTest {
        twoGraphs { foreground, background, provider, clock, _ ->
            seedRunningBaseline(foreground, provider)
            runCurrent()

            // B starts a later provider request first and parks while
            // holding only old RUNNING context.
            val gateB = CompletableDeferred<Unit>()
            provider.script += StagedCall(provider.snapshot(TrainStatus.RUNNING), gateB, fail = true)
            val jobB = async { background.coordinator.refreshOnce() }
            runCurrent()

            // A succeeds with RESCHEDULED: cache and monitor_state commit.
            clock.instant += 1.minutes
            provider.script += StagedCall(provider.snapshot(TrainStatus.RESCHEDULED, 30))
            foreground.coordinator.refreshOnce()
            runCurrent()
            assertEquals(1, foreground.notifications.size)

            // B's provider request fails. The failure fallback may affect
            // display freshness only: the RESCHEDULED cache and monitor
            // survive with no RUNNING regression, no false transition, and
            // no extra version bump from a skipped persistence.
            clock.instant += 1.minutes
            gateB.complete(Unit)
            runCurrent()
            jobB.await()
            runCurrent()

            assertEquals(
                TrainStatus.RESCHEDULED,
                data(foreground.trains.observeTrain(testRunId).first()).value.summary.status,
            )
            val monitor = assertNotNull(foreground.monitoring.observeMonitor(testRunId).first())
            assertEquals(TrainStatus.RESCHEDULED, monitor.lastSnapshot?.train?.summary?.status)
            assertEquals(
                TrainMonitorEvent.StatusChanged(testRunId, TrainStatus.RUNNING, TrainStatus.RESCHEDULED),
                monitor.lastEvent,
            )
            assertEquals(2L, monitor.snapshotVersion)
            assertEquals(1, foreground.notifications.size + background.notifications.size)
        }
    }

    @Test fun productionRefreshesAlwaysCarryOrderingEvidenceEndToEnd() = runTest {
        twoGraphs { foreground, _, provider, clock, _ ->
            val recorded = mutableListOf<Long?>()
            val recording = object : MonitoringRepository by foreground.monitoring {
                override suspend fun persistEvaluation(
                    monitorId: MonitorId,
                    snapshot: MonitoredTrainSnapshot,
                    events: List<TrainMonitorEvent>,
                    expectedVersion: Long,
                    observationOrder: Long?,
                ): EvaluationCommit {
                    recorded += observationOrder
                    return foreground.monitoring.persistEvaluation(
                        monitorId, snapshot, events, expectedVersion, observationOrder,
                    )
                }
            }
            val coordinator = MonitoringCoordinator(
                recording, foreground.trains, foreground.services, foreground.settings,
                backgroundScope, clock, MonitoringPolicy(),

                localizer = FakeNotificationLocalizer(),
            )
            runCurrent()
            foreground.monitoring.createMonitor(
                testRunId, MonitorThresholds(delayMinutes = 15), clock.now() + 48.hours,
            )

            // Baseline, steady refresh, and genuine transition: every
            // production evaluation from an actual refresh must carry
            // explicit accepted ordering evidence — the nullable legacy
            // branch is never reached here.
            provider.script += StagedCall(provider.snapshot(TrainStatus.RUNNING))
            coordinator.refreshOnce()
            runCurrent()
            clock.instant += 1.minutes
            provider.script += StagedCall(provider.snapshot(TrainStatus.RUNNING, 5))
            coordinator.refreshOnce()
            runCurrent()
            clock.instant += 1.minutes
            provider.script += StagedCall(provider.snapshot(TrainStatus.RESCHEDULED, 30))
            coordinator.refreshOnce()
            runCurrent()

            assertTrue(recorded.isNotEmpty())
            assertTrue(recorded.all { it != null }, "unordered production evaluation: $recorded")
            // The genuine transition emits exactly two distinct notifiable
            // events — the status change and the threshold-band crossing from
            // the last known delay (5 -> 30 across band 15). The claim policy
            // dedupes identical fingerprints, so a count of two proves two
            // distinct events were delivered once each — not a duplicate.
            assertEquals(
                setOf("[L] status RESCHEDULED", "[L] delay 5->30"),
                foreground.notifications.map { it.body }.toSet(),
            )
        }
    }
}

private object GrantedOrderingPermission : NotificationPermission {
    override suspend fun isGranted() = true
    override suspend fun request() = true
    override suspend fun effective() = EffectiveNotificationPermission.GRANTED
}

private class RecordingOrderingScheduler : BackgroundScheduler {
    val scheduled = mutableListOf<BackgroundTask>()
    override fun register(taskId: String, handler: suspend () -> Unit) = Unit
    override suspend fun schedule(task: BackgroundTask) {
        scheduled += task
    }
    override suspend fun cancel(taskId: String) = Unit
}
