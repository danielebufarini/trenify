package it.danielebufarini.trenify.data

import it.danielebufarini.trenify.core.domain.EvaluationCommit
import it.danielebufarini.trenify.core.domain.MonitorId
import it.danielebufarini.trenify.core.domain.MonitoredTrainSnapshot
import it.danielebufarini.trenify.core.domain.TrainMonitorEvent
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.TrainRun
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testRun
import it.danielebufarini.trenify.core.testing.testRunId
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * T7.11 corrective: non-terminal event commits are versioned at the
 * persistence boundary. Two independent repository instances evaluating from
 * the same accepted snapshot cannot create duplicate logical events merely
 * because their evaluatedAt differs; an older refresh cannot regress a newer
 * accepted snapshot; a genuine later return to the same condition still emits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitorEvaluationCasTest {
    private suspend fun kotlinx.coroutines.test.TestScope.withTwoRepositories(
        block: suspend kotlinx.coroutines.test.TestScope.(SqlDelightMonitoringRepository, SqlDelightMonitoringRepository, MutableClock) -> Unit,
    ) {
        val driver = createRepositoryTestDriver()
        try {
            val database = TrenifyDatabase(driver)
            val clock = MutableClock()
            val dispatcher = StandardTestDispatcher(testScheduler)
            // Two independent repository instances sharing one database: the
            // guard must live in persistence, not in instance memory.
            block(
                SqlDelightMonitoringRepository(database, clock, dispatcher),
                SqlDelightMonitoringRepository(database, clock, dispatcher),
                clock,
            )
        } finally {
            driver.close()
        }
    }

    private fun rescheduled(minutes: Int?) =
        testRun.copy(summary = testRun.summary.copy(status = TrainStatus.RESCHEDULED, delayMinutes = minutes))

    private fun snapshotFor(run: TrainRun, clock: MutableClock) =
        MonitoredTrainSnapshot(run, DataFreshness.Unknown, clock.now())

    @Test fun sameBaseSameTransitionCommitsOneLogicalEvent() = runTest {
        withTwoRepositories { first, second, clock ->
            val monitor = first.createMonitor(testRunId, expiresAt = clock.now() + 60.minutes)
            val base = snapshotFor(testRun, clock)
            assertIs<EvaluationCommit.Committed>(
                first.persistEvaluation(monitor.id, base, emptyList(), monitor.snapshotVersion, 1L),
            )
            runCurrent()
            val version = assertNotNull(first.observeMonitor(testRunId).first()).snapshotVersion

            // Both instances evaluate the same transition from the same
            // accepted snapshot; only their evaluatedAt differs.
            clock.instant += 1.minutes
            val observationB = snapshotFor(rescheduled(10), clock)
            val eventB = TrainMonitorEvent.StatusChanged(testRunId, TrainStatus.RUNNING, TrainStatus.RESCHEDULED)
            clock.instant += 1.minutes
            val observationA = snapshotFor(rescheduled(10), clock)
            val eventA = TrainMonitorEvent.StatusChanged(testRunId, TrainStatus.RUNNING, TrainStatus.RESCHEDULED)

            assertEquals(
                listOf(eventB),
                assertIs<EvaluationCommit.Committed>(
                    second.persistEvaluation(monitor.id, observationB, listOf(eventB), version, 2L),
                ).inserted,
            )
            // The second commit from the same base is rejected: no duplicate
            // logical event, no snapshot regression.
            assertEquals(
                EvaluationCommit.StaleBase,
                second.persistEvaluation(monitor.id, observationA, listOf(eventA), version, 2L),
            )
            // And the rejected observation's event was never persisted: its
            // exact key cannot be claimed.
            assertTrue(!second.claimNotification(eventA, observationA.evaluatedAt, 5.minutes))
            assertTrue(second.claimNotification(eventB, observationB.evaluatedAt, 5.minutes))

            // Explicit re-evaluation against the fresh base finds the
            // transition already reflected: nothing new, metadata progresses.
            val fresh = assertNotNull(first.observeMonitor(testRunId).first())
            assertEquals(rescheduled(10), fresh.lastSnapshot?.train)
            assertIs<EvaluationCommit.Committed>(
                first.persistEvaluation(monitor.id, observationA, emptyList(), fresh.snapshotVersion, 2L),
            )
        }
    }

    @Test fun olderRefreshCannotRegressNewerAcceptedSnapshot() = runTest {
        withTwoRepositories { first, second, clock ->
            val monitor = first.createMonitor(testRunId, expiresAt = clock.now() + 60.minutes)
            val base = snapshotFor(testRun, clock)
            first.persistEvaluation(monitor.id, base, emptyList(), monitor.snapshotVersion, 1L)
            runCurrent()

            // A newer refresh commits first.
            clock.instant += 2.minutes
            val newer = snapshotFor(rescheduled(30), clock)
            val version = assertNotNull(first.observeMonitor(testRunId).first()).snapshotVersion
            val newerEvent = TrainMonitorEvent.StatusChanged(testRunId, TrainStatus.RUNNING, TrainStatus.RESCHEDULED)
            assertIs<EvaluationCommit.Committed>(
                second.persistEvaluation(monitor.id, newer, listOf(newerEvent), version, 3L),
            )

            // The older refresh (same original base version, older
            // observation generation) is rejected and the newer accepted
            // snapshot survives untouched.
            clock.instant += 1.minutes
            val older = snapshotFor(testRun.copy(summary = testRun.summary.copy(delayMinutes = 9)), clock)
            val olderEvent = TrainMonitorEvent.DelayThresholdCrossed(testRunId, 5, 9, 15)
            assertEquals(
                EvaluationCommit.StaleBase,
                first.persistEvaluation(monitor.id, older, listOf(olderEvent), version, 2L),
            )
            val current = assertNotNull(first.observeMonitor(testRunId).first())
            assertEquals(rescheduled(30), current.lastSnapshot?.train)
            assertEquals(newerEvent, current.lastEvent)
            assertTrue(!first.claimNotification(olderEvent, older.evaluatedAt, 5.minutes))
        }
    }

    @Test fun returnToSameConditionEmitsGenuinelyNewEvent() = runTest {
        withTwoRepositories { first, _, clock ->
            val monitor = first.createMonitor(testRunId, expiresAt = clock.now() + 60.minutes)
            var version = monitor.snapshotVersion
            var order = 0L
            suspend fun commit(run: TrainRun, events: List<TrainMonitorEvent>): List<TrainMonitorEvent> {
                clock.instant += 1.minutes
                val snapshot = snapshotFor(run, clock)
                val inserted = assertIs<EvaluationCommit.Committed>(
                    first.persistEvaluation(monitor.id, snapshot, events, version, ++order),
                ).inserted
                runCurrent()
                version = assertNotNull(first.observeMonitor(testRunId).first()).snapshotVersion
                return inserted
            }

            // RUNNING -> RESCHEDULED -> RUNNING -> RESCHEDULED: the second
            // entry into RESCHEDULED is a genuine new transition and emits
            // again, with its own evaluatedAt-qualified identity.
            assertTrue(commit(testRun, emptyList()).isEmpty())
            val firstEntry = TrainMonitorEvent.StatusChanged(testRunId, TrainStatus.RUNNING, TrainStatus.RESCHEDULED)
            assertEquals(listOf(firstEntry), commit(rescheduled(10), listOf(firstEntry)))
            assertTrue(commit(testRun, emptyList()).isEmpty())
            val secondEntry = TrainMonitorEvent.StatusChanged(testRunId, TrainStatus.RUNNING, TrainStatus.RESCHEDULED)
            assertEquals(listOf(secondEntry), commit(rescheduled(12), listOf(secondEntry)))
            assertEquals(4, version)
            val current = assertNotNull(first.observeMonitor(testRunId).first())
            assertEquals(secondEntry, current.lastEvent)
        }
    }

    @Test fun concurrentFirstObservationsResolveToOneSnapshot() = runTest {
        withTwoRepositories { first, second, clock ->
            val monitor = first.createMonitor(testRunId, expiresAt = clock.now() + 60.minutes)
            // Both instances commit a first observation concurrently: exactly
            // one wins; the loser retries explicitly against the winner.
            val firstObs = snapshotFor(testRun, clock)
            assertIs<EvaluationCommit.Committed>(
                first.persistEvaluation(monitor.id, firstObs, emptyList(), monitor.snapshotVersion, 1L),
            )
            clock.instant += 1.minutes
            val secondObs = snapshotFor(rescheduled(10), clock)
            assertEquals(
                EvaluationCommit.StaleBase,
                second.persistEvaluation(monitor.id, secondObs, emptyList(), monitor.snapshotVersion, 2L),
            )
            // Explicit retry evaluates the transition against the winner and
            // commits it deterministically.
            val winner = assertNotNull(first.observeMonitor(testRunId).first())
            val event = TrainMonitorEvent.StatusChanged(testRunId, TrainStatus.RUNNING, TrainStatus.RESCHEDULED)
            assertEquals(
                listOf(event),
                assertIs<EvaluationCommit.Committed>(
                    second.persistEvaluation(monitor.id, secondObs, listOf(event), winner.snapshotVersion, 2L),
                ).inserted,
            )
            assertEquals(rescheduled(10), assertNotNull(first.observeMonitor(testRunId).first()).lastSnapshot?.train)
        }
    }

    @Test fun commitAfterRemovalOrDisableIsNotActive() = runTest {
        withTwoRepositories { first, _, clock ->
            val monitor = first.createMonitor(testRunId, expiresAt = clock.now() + 60.minutes)
            first.removeMonitor(testRunId)
            runCurrent()
            assertEquals(
                EvaluationCommit.NotActive,
                first.persistEvaluation(
                    monitor.id,
                    snapshotFor(testRun, clock),
                    emptyList(),
                    monitor.snapshotVersion,
                    0L,
                ),
            )
            assertNull(first.observeMonitor(testRunId).first())
        }
    }
}
