package it.danielebufarini.trenify.data

import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.model.ExternalStationRef
import it.danielebufarini.trenify.core.model.ProviderId
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.model.TrainRun
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.provider.api.ProviderCapabilities
import it.danielebufarini.trenify.core.provider.api.ProviderFailure
import it.danielebufarini.trenify.core.provider.api.ProviderMetadata
import it.danielebufarini.trenify.core.provider.api.ProviderResult
import it.danielebufarini.trenify.core.provider.api.ProviderStation
import it.danielebufarini.trenify.core.provider.api.ProviderTrainCandidate
import it.danielebufarini.trenify.core.provider.api.ProviderTrainRunRef
import it.danielebufarini.trenify.core.provider.api.ProviderTrainSnapshot
import it.danielebufarini.trenify.core.provider.api.TrainRealtimeProvider
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testRunId
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T7.11 corrective pass 2: persistence-shared refresh ordering for the
 * shared train_run cache. Request invocation order — not response completion
 * order — decides which concurrent refresh may supersede another, across two
 * independent repository instances sharing one real database.
 *
 * Each staged provider call retains its own response value at script time;
 * the gate only delays its return, so a parked call can never observe a
 * response staged for a later call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainRefreshOrderingTest {
    data class StagedCall(
        val snapshot: ProviderTrainSnapshot,
        val gate: CompletableDeferred<Unit> = CompletableDeferred<Unit>().apply { complete(Unit) },
    )

    class StagedTrainProvider(private val clock: MutableClock) : TrainRealtimeProvider {
        override val id = ProviderId("test")
        override val capabilities = ProviderCapabilities()
        val origin = ProviderStation(ExternalStationRef("opaque-origin"), "Roma Termini")
        private var nextIndex = 0
        var calls = 0
            private set
        val script = mutableListOf<StagedCall>()

        fun snapshot(status: TrainStatus, delayMinutes: Int? = null): ProviderTrainSnapshot {
            val ref = ProviderTrainRunRef(testRunId.number, origin.ref, testRunId.serviceDate)
            val candidate = ProviderTrainCandidate(
                ref, origin, "Milano Centrale", status = status, delayMinutes = delayMinutes,
            )
            return ProviderTrainSnapshot(candidate, emptyList())
        }

        override suspend fun getTrainSnapshot(ref: ProviderTrainRunRef): ProviderResult<ProviderTrainSnapshot> {
            // No lock: staged calls are consumed sequentially on the test
            // dispatcher (each parked call already retained its own entry
            // before the next entry is even scripted).
            val call = script[nextIndex++].also { calls++ }
            call.gate.await()
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

    private suspend fun kotlinx.coroutines.test.TestScope.withTwoRepositories(
        block: suspend kotlinx.coroutines.test.TestScope.(
            RealtimeRepositories, RealtimeRepositories, StagedTrainProvider, MutableClock, TrenifyDatabase,
        ) -> Unit,
    ) {
        val driver = createRepositoryTestDriver()
        val database = TrenifyDatabase(driver)
        val clock = MutableClock()
        val provider = StagedTrainProvider(clock)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val foreground = RealtimeRepositories(database, listOf(provider), backgroundScope, clock = clock, dispatcher = dispatcher)
        val background = RealtimeRepositories(database, listOf(provider), backgroundScope, clock = clock, dispatcher = dispatcher)
        try {
            block(foreground, background, provider, clock, database)
        } finally {
            driver.close()
        }
    }

    private fun data(result: DataResult<TrainRun>): DataResult.Data<TrainRun> =
        assertIs<DataResult.Data<TrainRun>>(result)

    @Test fun orderIdentityIsCapturedBeforeSchedulingCanInvertInvocations() = runTest {
        val driver = createRepositoryTestDriver()
        val database = TrenifyDatabase(driver)
        val clock = MutableClock()
        val provider = StagedTrainProvider(clock)
        val testDispatcher = StandardTestDispatcher(testScheduler)
        // A is admitted through a manual gate that holds its post-admission
        // scheduling; B runs on the plain test dispatcher. The token is
        // captured on the calling thread before the dispatcher hop, so A —
        // invoked first — owns the earlier identity even while its execution
        // is held.
        val gate = ManualTestDispatcher()
        val foreground = RealtimeRepositories(database, listOf(provider), backgroundScope, clock = clock, dispatcher = gate)
        val background = RealtimeRepositories(database, listOf(provider), backgroundScope, clock = clock, dispatcher = testDispatcher)
        try {
            // Seed through the ungated instance: refreshTrain hops to the
            // repository dispatcher after admission, and a direct call on the
            // gated instance cannot complete while the gate is holding.
            provider.script += StagedCall(provider.snapshot(TrainStatus.RUNNING))
            val seed = data(background.refreshTrain(testRunId, force = true))
            assertEquals(TrainStatus.RUNNING, seed.value.summary.status)
            val seedGeneration = assertNotNull(seed.refreshGeneration)

            // Staged in EXECUTION order, not invocation order: B runs fully
            // while A is held at its post-admission dispatcher hop, so B
            // consumes its entry first and A consumes its own retained entry
            // after release. A is still INVOKED first (below) and — with
            // admission capture — owns the earlier token throughout.
            provider.script += StagedCall(provider.snapshot(TrainStatus.RESCHEDULED, 30))
            val gateA = CompletableDeferred<Unit>()
            provider.script += StagedCall(provider.snapshot(TrainStatus.RUNNING), gateA)
            val jobA = async { foreground.refreshTrain(testRunId, force = true) }
            runCurrent()
            assertEquals(1, provider.calls)
            // Exactly one post-admission hop is held; nothing of A's
            // execution has run past admission.
            assertEquals(1, gate.pending())

            // …while B is invoked later and runs fully: B must not acquire
            // an earlier semantic refresh order than A.
            val resultB = data(background.refreshTrain(testRunId, force = true))
            assertEquals(TrainStatus.RESCHEDULED, resultB.value.summary.status)
            val generationB = assertNotNull(resultB.refreshGeneration)
            assertTrue(generationB > seedGeneration)
            assertEquals(2, provider.calls)

            // Release A's held execution: A parks inside its own retained
            // provider call while B's accepted RESCHEDULED stays observable.
            gate.runAll()
            runCurrent()
            assertEquals(
                TrainStatus.RESCHEDULED,
                data(background.observeTrain(testRunId).first()).value.summary.status,
            )

            // Releasing A proves invocation order: the older RUNNING
            // observation cannot overwrite the newer accepted RESCHEDULED.
            // Against token-allocation-after-scheduling this fails because B
            // is admitted first through A's scheduling gap and A commits its
            // stale RUNNING as newer.
            gateA.complete(Unit)
            // Pump the test scheduler first: A's provider resumption runs
            // there, and only its completion queues the final join back on
            // the manual gate. Draining the gate before that pump would
            // drain nothing and hang the join below.
            runCurrent()
            gate.runAll()
            val resultA = data(jobA.await())
            assertEquals(TrainStatus.RESCHEDULED, resultA.value.summary.status)
            assertEquals(generationB, resultA.refreshGeneration)
            assertEquals(
                TrainStatus.RESCHEDULED,
                data(background.observeTrain(testRunId).first()).value.summary.status,
            )
        } finally {
            driver.close()
        }
    }

    @Test fun sameInstanceJoinerReceivesOwnerObservationWithoutRelabeling() = runTest {
        val driver = createRepositoryTestDriver()
        val database = TrenifyDatabase(driver)
        val clock = MutableClock()
        val provider = StagedTrainProvider(clock)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = RealtimeRepositories(database, listOf(provider), backgroundScope, clock = clock, dispatcher = dispatcher)
        try {
            val gateA = CompletableDeferred<Unit>()
            provider.script += StagedCall(provider.snapshot(TrainStatus.RUNNING, 5), gateA)
            val jobA = async { repository.refreshTrain(testRunId, force = true) }
            runCurrent()
            assertEquals(1, provider.calls)

            // B overlaps on the SAME instance and joins A's flight: there is
            // only one provider observation, owned by A. B's admission token
            // remains an unused gap and must not relabel the shared result.
            val jobB = async { repository.refreshTrain(testRunId, force = true) }
            runCurrent()
            assertEquals(1, provider.calls)

            gateA.complete(Unit)
            runCurrent()
            val resultA = data(jobA.await())
            val resultB = data(jobB.await())
            assertEquals(1, provider.calls)
            assertEquals(5, resultA.value.summary.delayMinutes)
            assertEquals(5, resultB.value.summary.delayMinutes)
            assertNotNull(resultA.refreshGeneration)
            assertEquals(resultA.refreshGeneration, resultB.refreshGeneration)
        } finally {
            driver.close()
        }
    }

    @Test fun olderNonTerminalCannotRegressNewerNonTerminal() = runTest {
        withTwoRepositories { foreground, background, provider, _, _ ->
            provider.script += StagedCall(
                provider.snapshot(TrainStatus.RUNNING),
            )
            assertEquals(TrainStatus.RUNNING, data(foreground.refreshTrain(testRunId, force = true)).value.summary.status)

            // A starts first and parks inside the provider fetch.
            val gateA = CompletableDeferred<Unit>()
            provider.script += StagedCall(provider.snapshot(TrainStatus.RUNNING), gateA)
            val jobA = async { foreground.refreshTrain(testRunId, force = true) }
            runCurrent()
            assertEquals(2, provider.calls)

            // B starts later, returns newer RESCHEDULED and commits.
            provider.script += StagedCall(provider.snapshot(TrainStatus.RESCHEDULED, 30))
            val resultB = data(background.refreshTrain(testRunId, force = true))
            assertEquals(TrainStatus.RESCHEDULED, resultB.value.summary.status)
            assertNotNull(resultB.refreshGeneration)

            // A resumes with its own retained older RUNNING response: the
            // accepted RESCHEDULED cache survives, and A itself observes the
            // accepted state back with its generation — never its stale
            // provider response rebranded as current.
            gateA.complete(Unit)
            runCurrent()
            val resultA = data(jobA.await())
            assertEquals(TrainStatus.RESCHEDULED, resultA.value.summary.status)
            assertEquals(resultB.refreshGeneration, resultA.refreshGeneration)
            assertEquals(
                TrainStatus.RESCHEDULED,
                data(foreground.observeTrain(testRunId).first()).value.summary.status,
            )
        }
    }

    @Test fun olderTerminalCannotRegressNewerTerminal() = runTest {
        withTwoRepositories { foreground, background, provider, _, _ ->
            provider.script += StagedCall(provider.snapshot(TrainStatus.RUNNING))
            foreground.refreshTrain(testRunId, force = true)

            // A starts first (older invocation) and parks with CANCELLED.
            val gateA = CompletableDeferred<Unit>()
            provider.script += StagedCall(provider.snapshot(TrainStatus.CANCELLED), gateA)
            val jobA = async { foreground.refreshTrain(testRunId, force = true) }
            runCurrent()
            assertEquals(2, provider.calls)

            // B starts later and commits ARRIVED.
            provider.script += StagedCall(provider.snapshot(TrainStatus.ARRIVED))
            assertEquals(TrainStatus.ARRIVED, data(background.refreshTrain(testRunId, force = true)).value.summary.status)

            // A resumes: the older terminal invocation must not overwrite
            // the newer accepted terminal cache.
            gateA.complete(Unit)
            runCurrent()
            assertEquals(TrainStatus.ARRIVED, data(jobA.await()).value.summary.status)
            assertEquals(
                TrainStatus.ARRIVED,
                data(foreground.observeTrain(testRunId).first()).value.summary.status,
            )
        }
    }

    @Test fun laterTerminalInvocationLegitimatelyWins() = runTest {
        withTwoRepositories { foreground, background, provider, _, _ ->
            provider.script += StagedCall(provider.snapshot(TrainStatus.RUNNING))
            foreground.refreshTrain(testRunId, force = true)

            // A starts first and parks with ARRIVED.
            val gateA = CompletableDeferred<Unit>()
            provider.script += StagedCall(provider.snapshot(TrainStatus.ARRIVED), gateA)
            val jobA = async { foreground.refreshTrain(testRunId, force = true) }
            runCurrent()
            assertEquals(2, provider.calls)

            // B starts later and commits CANCELLED: the later invocation
            // legitimately corrects the earlier terminal state.
            provider.script += StagedCall(provider.snapshot(TrainStatus.CANCELLED))
            assertEquals(TrainStatus.CANCELLED, data(background.refreshTrain(testRunId, force = true)).value.summary.status)

            gateA.complete(Unit)
            runCurrent()
            assertEquals(TrainStatus.CANCELLED, data(jobA.await()).value.summary.status)
            assertEquals(
                TrainStatus.CANCELLED,
                data(foreground.observeTrain(testRunId).first()).value.summary.status,
            )
        }
    }

    @Test fun earlierSuccessCommitsWhileLaterPendingThenLaterOverwrites() = runTest {
        withTwoRepositories { foreground, background, provider, _, _ ->
            provider.script += StagedCall(provider.snapshot(TrainStatus.RUNNING, 5))
            val first = data(foreground.refreshTrain(testRunId, force = true))
            assertEquals(5, first.value.summary.delayMinutes)

            // A (earlier invocation) and B (later invocation) genuinely
            // overlap: both park inside the provider fetch.
            val gateA = CompletableDeferred<Unit>()
            val gateB = CompletableDeferred<Unit>()
            provider.script += StagedCall(provider.snapshot(TrainStatus.RUNNING, 9), gateA)
            provider.script += StagedCall(provider.snapshot(TrainStatus.RESCHEDULED, 30), gateB)
            val jobA = async { foreground.refreshTrain(testRunId, force = true) }
            runCurrent()
            val jobB = async { background.refreshTrain(testRunId, force = true) }
            runCurrent()
            assertEquals(3, provider.calls)

            // A succeeds while B remains pending: issuing B does not
            // invalidate A, so A commits and is observable meanwhile.
            gateA.complete(Unit)
            runCurrent()
            val resultA = data(jobA.await())
            assertEquals(9, resultA.value.summary.delayMinutes)
            assertFalse(jobB.isCompleted)
            assertEquals(
                9,
                data(foreground.observeTrain(testRunId).first()).value.summary.delayMinutes,
            )

            // The later invocation overwrites the earlier success when it
            // arrives, with a strictly newer generation.
            gateB.complete(Unit)
            runCurrent()
            val resultB = data(jobB.await())
            assertEquals(TrainStatus.RESCHEDULED, resultB.value.summary.status)
            assertTrue((resultB.refreshGeneration ?: 0L) > (resultA.refreshGeneration ?: 0L))
            assertEquals(
                TrainStatus.RESCHEDULED,
                data(foreground.observeTrain(testRunId).first()).value.summary.status,
            )
        }
    }

    @Test fun olderNonTerminalCannotRegressNewerTerminal() = runTest {
        withTwoRepositories { foreground, background, provider, _, _ ->
            provider.script += StagedCall(provider.snapshot(TrainStatus.RUNNING))
            foreground.refreshTrain(testRunId, force = true)

            // A starts first (older invocation) and parks with RUNNING.
            val gateA = CompletableDeferred<Unit>()
            provider.script += StagedCall(provider.snapshot(TrainStatus.RUNNING), gateA)
            val jobA = async { foreground.refreshTrain(testRunId, force = true) }
            runCurrent()
            assertEquals(2, provider.calls)

            // B starts later and commits ARRIVED.
            provider.script += StagedCall(provider.snapshot(TrainStatus.ARRIVED))
            assertEquals(TrainStatus.ARRIVED, data(background.refreshTrain(testRunId, force = true)).value.summary.status)

            // A resumes: the older non-terminal invocation must not
            // overwrite the newer accepted terminal cache.
            gateA.complete(Unit)
            runCurrent()
            assertEquals(TrainStatus.ARRIVED, data(jobA.await()).value.summary.status)
            assertEquals(
                TrainStatus.ARRIVED,
                data(foreground.observeTrain(testRunId).first()).value.summary.status,
            )
        }
    }

    @Test fun failedLaterRequestDoesNotBreakTheDatabase() = runTest {
        withTwoRepositories { foreground, background, provider, _, database ->
            provider.script += StagedCall(provider.snapshot(TrainStatus.RUNNING, 5))
            assertEquals(5, data(foreground.refreshTrain(testRunId, force = true)).value.summary.delayMinutes)

            // A later invocation fails at the provider: it commits nothing,
            // and the database keeps serving the accepted state.
            val failing = object : TrainRealtimeProvider by provider {
                override suspend fun getTrainSnapshot(ref: ProviderTrainRunRef): ProviderResult<ProviderTrainSnapshot> =
                    ProviderResult.Unavailable(true, ProviderFailure.TIMEOUT)
            }
            val failingRepositories = RealtimeRepositories(
                database, listOf(failing), backgroundScope,
                clock = MutableClock(), dispatcher = StandardTestDispatcher(testScheduler),
            )
            // With an accepted cache present, a failed later invocation
            // falls back to the stale cache with a warning (pre-existing
            // fallback semantics): it commits nothing and stays servable.
            val failed = data(failingRepositories.refreshTrain(testRunId, force = true))
            assertEquals(DomainFailure.TEMPORARY, failed.warning)
            assertEquals(5, failed.value.summary.delayMinutes)
            assertEquals(
                5,
                data(foreground.observeTrain(testRunId).first()).value.summary.delayMinutes,
            )

            // A subsequent successful refresh still commits normally.
            provider.script += StagedCall(provider.snapshot(TrainStatus.RUNNING, 12))
            assertEquals(12, data(background.refreshTrain(testRunId, force = true)).value.summary.delayMinutes)
        }
    }
}
