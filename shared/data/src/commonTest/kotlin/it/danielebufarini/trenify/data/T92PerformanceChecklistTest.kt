package it.danielebufarini.trenify.data

import app.cash.sqldelight.db.SqlDriver
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.model.BoardKind
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Station
import it.danielebufarini.trenify.core.model.StationBoard
import it.danielebufarini.trenify.core.model.TrainRun
import it.danielebufarini.trenify.core.testing.FakeRealtimeProvider
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testRunId
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Continuous-performance checklist (`:shared:data`, commonTest, so it
 * runs on both the JVM/Android-host and iOS-simulator aggregates).
 *
 * lifecycle/background energy and cancellation; no new polling service.
 * Done when: reproducible measurements plus continuous checklist evidence;
 * bounded calls and memory/energy behavior demonstrated.
 *
 * Each test below pins one compositional bound that no single
 * repository-focused suite asserts on its own (restart serving cache with
 * zero provider calls, one call per distinct forced key with no per-row
 * enrichment, solo cancellation staying cancellation). Clause coverage that
 * already has a dedicated owner is referenced, not duplicated:
 * - long-list projection/scroll pathology: `T814PerformanceBaselineTest`;
 * - SQL index/query-plan audit: `IndexQueryPlanAuditTest`;
 * - same-key in-flight dedup per repository: `RealtimeRepositoriesTest`
 *   (`identicalInFlightRequestsShareOneFetchEvenIfOneWaiterCancels`),
 *   `SqlDelightJourneyRepositoryTest`
 *   (`concurrentForcedSearchesShareOneRequestAndCancellationDoesNotCancelOtherWaiters`),
 *   `SqlDelightStrikeRepositoryTest` (`overlappingIdenticalRefreshesShareOneFetch`,
 *   `homeAlertsAndCoordinatorShareOneFetchThroughLoadStrikes`, …);
 * - background/foreground bounded refresh and terminal lifecycle:
 *   `MonitoringCoordinatorTerminalTest`, `BackgroundRefreshOutcomeTest`,
 *   `BackgroundDeliveryLifetimeTest`, `MonitorRefreshHealthTest`;
 * - no polling/busy loops: `HomeComponentsTest.manualRefreshDerivesCurrentWindowWithoutPolling`,
 *   `HomePastDeadlineLivenessTest.oldPersistedSnapshotAgesOnceWithoutBusyLoop`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class T92PerformanceChecklistTest {
    private data class Seeded(
        val driver: SqlDriver,
        val database: TrenifyDatabase,
        val provider: FakeRealtimeProvider,
        val clock: MutableClock,
        val station: Station,
        val board: StationBoard,
        val train: TrainRun,
        val calls: Triple<Int, Int, Int>,
    )

    private fun callsOf(provider: FakeRealtimeProvider) =
        Triple(provider.stationCalls, provider.boardCalls, provider.detailCalls)

    private suspend fun kotlinx.coroutines.test.TestScope.seeded(): Seeded {
        val driver = createRepositoryTestDriver()
        val database = TrenifyDatabase(driver)
        val clock = MutableClock()
        val provider = FakeRealtimeProvider(clock)
        val repository = RealtimeRepositories(
            database, listOf(provider), backgroundScope, clock = clock,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val station = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("roma")).value.single()
        val board = assertIs<DataResult.Data<StationBoard>>(
            repository.refreshBoard(station, BoardKind.DEPARTURES)).value
        val train = assertIs<DataResult.Data<TrainRun>>(repository.refreshTrain(testRunId)).value
        return Seeded(driver, database, provider, clock, station, board, train, callsOf(provider))
    }

    private fun kotlinx.coroutines.test.TestScope.reopened(
        seeded: Seeded,
        online: () -> Boolean = { true },
    ) = RealtimeRepositories(
        seeded.database, listOf(seeded.provider), backgroundScope,
        online = online, clock = seeded.clock,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test fun restartServesCachedTruthWithZeroProviderCalls() = runTest {
        val seeded = seeded()
        try {
            // Simulate process restart: brand-new repository instances over the same database.
            val restarted = reopened(seeded)
            runCurrent()
            assertEquals(seeded.station, restarted.observeStation(seeded.station.id).first())
            val board = assertIs<DataResult.Data<StationBoard>>(
                restarted.observeBoard(seeded.station, BoardKind.DEPARTURES).first())
            assertEquals(seeded.board, board.value)
            val train = assertIs<DataResult.Data<TrainRun>>(
                restarted.observeTrain(testRunId).first())
            assertEquals(seeded.train, train.value)
            // Non-forced startup refreshes must be served from the fresh cache.
            assertIs<DataResult.Data<List<Station>>>(restarted.searchStations("roma"))
            assertIs<DataResult.Data<StationBoard>>(
                restarted.refreshBoard(seeded.station, BoardKind.DEPARTURES))
            assertIs<DataResult.Data<TrainRun>>(restarted.refreshTrain(testRunId))
            assertEquals(seeded.calls, callsOf(seeded.provider),
                "restarted startup must issue zero provider calls")
        } finally { seeded.driver.close() }
    }

    @Test fun offlineRestartKeepsContentWithTypedWarning() = runTest {
        val seeded = seeded()
        try {
            // Age the cache past the 30s board/train TTL so the offline
            // refresh exercises the stale-with-warning fallback, not the
            // fresh short-circuit.
            seeded.clock.instant += 2.minutes
            val offline = reopened(seeded, online = { false })
            runCurrent()
            val train = assertIs<DataResult.Data<TrainRun>>(
                offline.refreshTrain(testRunId))
            assertEquals(seeded.train, train.value)
            assertIs<DataFreshness.Stale>(train.freshness)
            assertEquals(DomainFailure.OFFLINE, train.warning)
            val board = assertIs<DataResult.Data<StationBoard>>(
                offline.refreshBoard(seeded.station, BoardKind.DEPARTURES))
            assertEquals(seeded.board, board.value)
            assertEquals(DomainFailure.OFFLINE, board.warning)
            assertEquals(seeded.calls, callsOf(seeded.provider),
                "offline restart must issue zero provider calls")
        } finally { seeded.driver.close() }
    }

    @Test fun concurrentForcedRefreshesIssueOneCallPerDistinctKeyWithoutRowEnrichment() = runTest {
        val seeded = seeded()
        try {
            val repository = reopened(seeded)
            val departures = async { repository.refreshBoard(seeded.station, BoardKind.DEPARTURES, force = true) }
            val arrivals = async { repository.refreshBoard(seeded.station, BoardKind.ARRIVALS, force = true) }
            val train = async { repository.refreshTrain(testRunId, force = true) }
            val stations = async { repository.searchStations("roma", force = true) }
            runCurrent()
            assertIs<DataResult.Data<StationBoard>>(departures.await())
            assertIs<DataResult.Data<StationBoard>>(arrivals.await())
            assertIs<DataResult.Data<TrainRun>>(train.await())
            assertIs<DataResult.Data<List<Station>>>(stations.await())
            // Exactly one provider call per distinct forced key: no fan-out, no
            // retry storm, and board refresh performs no per-row detail fetch.
            assertEquals(seeded.calls.first + 1, seeded.provider.stationCalls)
            assertEquals(seeded.calls.second + 2, seeded.provider.boardCalls)
            assertEquals(seeded.calls.third + 1, seeded.provider.detailCalls)
        } finally { seeded.driver.close() }
    }

    @Test fun soloCancellationStaysCancellationAndLeavesSubsequentRefreshUsable() = runTest {
        val seeded = seeded()
        try {
            val repository = reopened(seeded)
            val gate = CompletableDeferred<Unit>()
            seeded.provider.beforeDetail = { gate.await() }
            val cancelled = async { repository.refreshTrain(testRunId, force = true) }
            runCurrent()
            assertEquals(seeded.calls.third + 1, seeded.provider.detailCalls)
            cancelled.cancel()
            // Cancellation must remain cancellation: it is never converted into a
            // domain failure result.
            assertFailsWith<CancellationException> { cancelled.await() }
            assertTrue(cancelled.isCancelled)
            // Release the orphaned shared flight: exactly one provider call was
            // issued for it (bounded — cancellation triggers no retry), and a
            // subsequent refresh stays usable.
            gate.complete(Unit)
            runCurrent()
            seeded.provider.beforeDetail = {}
            val recovered = assertIs<DataResult.Data<TrainRun>>(
                repository.refreshTrain(testRunId, force = true))
            assertEquals(seeded.train.summary.id, recovered.value.summary.id)
            assertEquals(seeded.calls.third + 2, seeded.provider.detailCalls)
        } finally { seeded.driver.close() }
    }
}
