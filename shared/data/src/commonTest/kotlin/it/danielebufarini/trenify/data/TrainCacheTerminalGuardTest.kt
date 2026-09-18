package it.danielebufarini.trenify.data

import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.model.TrainRun
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.testing.FakeRealtimeProvider
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * T7.11 corrective: the shared train cache is guarded at the persistence
 * boundary, so a stale non-terminal refresh resuming after an accepted
 * terminal commit cannot regress it — even across two independent
 * repository/AppGraph instances whose instance-local flights cannot order
 * each other.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainCacheTerminalGuardTest {
    private suspend fun kotlinx.coroutines.test.TestScope.withTwoRepositories(
        block: suspend kotlinx.coroutines.test.TestScope.(
            RealtimeRepositories, RealtimeRepositories, FakeRealtimeProvider, MutableClock, TrenifyDatabase,
        ) -> Unit,
    ) {
        val driver = createRepositoryTestDriver()
        val database = TrenifyDatabase(driver)
        val clock = MutableClock()
        val provider = FakeRealtimeProvider(clock)
        val dispatcher = StandardTestDispatcher(testScheduler)
        // Two independent instances (foreground Activity vs background
        // service equivalents) sharing one persistent database.
        val foreground = RealtimeRepositories(database, listOf(provider), backgroundScope, clock = clock, dispatcher = dispatcher)
        val background = RealtimeRepositories(database, listOf(provider), backgroundScope, clock = clock, dispatcher = dispatcher)
        try {
            block(foreground, background, provider, clock, database)
        } finally {
            driver.close()
        }
    }

    private fun runningDetail(provider: FakeRealtimeProvider) =
        provider.detail.copy(train = provider.detail.train.copy(status = TrainStatus.RUNNING))

    private fun terminalDetail(provider: FakeRealtimeProvider, status: TrainStatus) =
        provider.detail.copy(train = provider.detail.train.copy(status = status))

    private suspend fun kotlinx.coroutines.test.TestScope.staleRefreshAfterTerminal(
        terminal: TrainStatus,
        foreground: RealtimeRepositories,
        background: RealtimeRepositories,
        provider: FakeRealtimeProvider,
    ) {
        provider.detail = runningDetail(provider)
        val seeded = assertIs<DataResult.Data<TrainRun>>(
            foreground.refreshTrain(testRunId, force = true),
        )
        assertEquals(TrainStatus.RUNNING, seeded.value.summary.status)

        // Refresh A starts and parks inside the provider fetch.
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        provider.beforeDetail = { if (calls++ == 0) gate.await() }
        val jobA = async { foreground.refreshTrain(testRunId, force = true) }
        runCurrent()
        assertEquals(2, provider.detailCalls)

        // Refresh B (independent instance) observes and commits the terminal
        // state while A is parked.
        provider.beforeDetail = {}
        provider.detail = terminalDetail(provider, terminal)
        val resultB = assertIs<DataResult.Data<TrainRun>>(
            background.refreshTrain(testRunId, force = true),
        )
        assertEquals(terminal, resultB.value.summary.status)

        // A resumes with older non-terminal data: the accepted terminal cache
        // must survive, and A itself observes the terminal state back.
        provider.detail = runningDetail(provider)
        gate.complete(Unit)
        runCurrent()
        val resultA = assertIs<DataResult.Data<TrainRun>>(jobA.await())
        assertEquals(terminal, resultA.value.summary.status)
        val cached = assertIs<DataResult.Data<TrainRun>>(
            foreground.observeTrain(testRunId).first(),
        )
        assertEquals(terminal, cached.value.summary.status)
    }

    @Test fun staleRunningCannotRegressAcceptedArrival() = runTest {
        withTwoRepositories { foreground, background, provider, _, _ ->
            staleRefreshAfterTerminal(TrainStatus.ARRIVED, foreground, background, provider)
        }
    }

    @Test fun staleRunningCannotRegressAcceptedCancellation() = runTest {
        withTwoRepositories { foreground, background, provider, _, _ ->
            staleRefreshAfterTerminal(TrainStatus.CANCELLED, foreground, background, provider)
        }
    }

    @Test fun nonTerminalCacheStillOverwritesFreely() = runTest {
        withTwoRepositories { foreground, _, provider, _, _ ->
            provider.detail = runningDetail(provider).copy(
                train = runningDetail(provider).train.copy(delayMinutes = 5),
            )
            foreground.refreshTrain(testRunId, force = true)
            provider.detail = runningDetail(provider).copy(
                train = runningDetail(provider).train.copy(delayMinutes = 40),
            )
            val updated = assertIs<DataResult.Data<TrainRun>>(
                foreground.refreshTrain(testRunId, force = true),
            )
            assertEquals(40, updated.value.summary.delayMinutes)
        }
    }

    @Test fun terminalToTerminalCorrectionsStillCommit() = runTest {
        withTwoRepositories { foreground, _, provider, _, _ ->
            provider.detail = terminalDetail(provider, TrainStatus.ARRIVED)
            foreground.refreshTrain(testRunId, force = true)
            provider.detail = terminalDetail(provider, TrainStatus.CANCELLED)
            val corrected = assertIs<DataResult.Data<TrainRun>>(
                foreground.refreshTrain(testRunId, force = true),
            )
            assertEquals(TrainStatus.CANCELLED, corrected.value.summary.status)
        }
    }
}
