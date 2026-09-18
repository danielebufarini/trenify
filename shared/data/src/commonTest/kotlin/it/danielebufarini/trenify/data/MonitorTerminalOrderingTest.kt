package it.danielebufarini.trenify.data

import it.danielebufarini.trenify.core.domain.MonitoredTrainSnapshot
import it.danielebufarini.trenify.core.domain.TrainMonitorEvent
import it.danielebufarini.trenify.core.model.DataFreshness
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * T7.11 final pass: the latest accepted provider observation order governs
 * the retained terminal snapshot too — not first-terminal-completion-wins.
 *
 * First accepted terminal observation establishes `endedAt` (ACTIVE → ENDED);
 * strictly newer accepted terminal observations correct the retained final
 * snapshot while `endedAt` and the ENDED lifecycle stay monotonic. Older
 * observations are rejected independently of completion order, and durable
 * removal still wins over any late correction.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitorTerminalOrderingTest {
    private suspend fun kotlinx.coroutines.test.TestScope.withRepository(
        block: suspend kotlinx.coroutines.test.TestScope.(TrenifyDatabase, MutableClock, SqlDelightMonitoringRepository) -> Unit,
    ) {
        val driver = createRepositoryTestDriver()
        val database = TrenifyDatabase(driver)
        val clock = MutableClock()
        val repository = SqlDelightMonitoringRepository(database, clock, StandardTestDispatcher(testScheduler))
        try {
            block(database, clock, repository)
        } finally {
            driver.close()
        }
    }

    private fun arrived() = testRun.copy(summary = testRun.summary.copy(status = TrainStatus.ARRIVED))
    private fun cancelled() = testRun.copy(summary = testRun.summary.copy(status = TrainStatus.CANCELLED))

    private suspend fun seedRunning(
        repository: SqlDelightMonitoringRepository,
        clock: MutableClock,
    ) = repository.createMonitor(testRunId, expiresAt = clock.now() + 48.hours).also {
        val base = MonitoredTrainSnapshot(testRun, DataFreshness.Unknown, clock.now())
        val commit = repository.persistEvaluation(it.id, base, emptyList(), it.snapshotVersion, 1L)
        assertTrue(commit is it.danielebufarini.trenify.core.domain.EvaluationCommit.Committed)
    }

    private suspend fun acceptedGeneration(database: TrenifyDatabase, monitorId: String): Long? =
        database.realtimeQueries.monitorStateById(monitorId).executeAsOneOrNull()?.accepted_refresh_generation

    @Test fun newerTerminalCorrectsRetainedSnapshotPreservingEndedAt() = runTest {
        withRepository { database, clock, repository ->
            val monitor = seedRunning(repository, clock)
            runCurrent()

            // Generation 2 commits ARRIVED terminally while generation 3 is
            // still pending: first accepted terminal observation ends the
            // monitor.
            val endedAt = clock.now()
            assertEquals(
                listOf(TrainMonitorEvent.Arrived(testRunId)),
                repository.completeTerminally(
                    monitor.id,
                    MonitoredTrainSnapshot(arrived(), DataFreshness.Unknown, endedAt),
                    listOf(TrainMonitorEvent.Arrived(testRunId)),
                    endedAt,
                    2L,
                ),
            )
            assertEquals(endedAt, repository.observeEndedMonitors().first().single().endedAt)
            assertEquals(2L, acceptedGeneration(database, monitor.id.value))

            // Generation 3 arrives later with CANCELLED: the strictly newer
            // accepted observation corrects the retained final snapshot.
            clock.instant += 1.minutes
            val correctionAt = clock.now()
            assertEquals(
                listOf(TrainMonitorEvent.Cancelled(testRunId)),
                repository.completeTerminally(
                    monitor.id,
                    MonitoredTrainSnapshot(cancelled(), DataFreshness.Unknown, correctionAt),
                    listOf(TrainMonitorEvent.Cancelled(testRunId)),
                    correctionAt,
                    3L,
                ),
            )

            val final = repository.observeEndedMonitors().first().single()
            assertEquals(endedAt, final.endedAt, "endedAt must remain the first terminal transition time")
            assertEquals(cancelled(), final.lastSnapshot?.train)
            assertEquals(TrainMonitorEvent.Cancelled(testRunId), final.lastEvent)
            assertEquals(3L, acceptedGeneration(database, monitor.id.value))
            assertTrue(repository.observeActiveMonitors().first().isEmpty(), "no polling eligibility after correction")
            // The corrected CANCELLED event is claimable exactly once; the
            // already displayed ARRIVED truth is not retracted here, only the
            // persisted final truth moves.
            assertTrue(repository.claimNotification(TrainMonitorEvent.Cancelled(testRunId), correctionAt, 5.minutes))
            assertTrue(!repository.claimNotification(TrainMonitorEvent.Cancelled(testRunId), correctionAt, 5.minutes))
        }
    }

    @Test fun inverseTerminalOrderingArrivedWinsWhenNewer() = runTest {
        withRepository { database, clock, repository ->
            val monitor = seedRunning(repository, clock)
            runCurrent()

            val endedAt = clock.now()
            repository.completeTerminally(
                monitor.id,
                MonitoredTrainSnapshot(cancelled(), DataFreshness.Unknown, endedAt),
                listOf(TrainMonitorEvent.Cancelled(testRunId)),
                endedAt,
                2L,
            )
            clock.instant += 1.minutes
            val correctionAt = clock.now()
            repository.completeTerminally(
                monitor.id,
                MonitoredTrainSnapshot(arrived(), DataFreshness.Unknown, correctionAt),
                listOf(TrainMonitorEvent.Arrived(testRunId)),
                correctionAt,
                3L,
            )

            // Request/observation order decides, not status precedence.
            val final = repository.observeEndedMonitors().first().single()
            assertEquals(endedAt, final.endedAt)
            assertEquals(arrived(), final.lastSnapshot?.train)
            assertEquals(TrainMonitorEvent.Arrived(testRunId), final.lastEvent)
            assertEquals(3L, acceptedGeneration(database, monitor.id.value))
            assertTrue(repository.observeActiveMonitors().first().isEmpty())
        }
    }

    @Test fun olderTerminalCompletingLaterCannotRegressNewerFinalState() = runTest {
        withRepository { database, clock, repository ->
            val monitor = seedRunning(repository, clock)
            runCurrent()

            // Newer generation 3 commits first (completion order inverted
            // relative to invocation order).
            val endedAt = clock.now()
            repository.completeTerminally(
                monitor.id,
                MonitoredTrainSnapshot(cancelled(), DataFreshness.Unknown, endedAt),
                listOf(TrainMonitorEvent.Cancelled(testRunId)),
                endedAt,
                3L,
            )
            // Older generation 2 returns later and must not overwrite.
            clock.instant += 1.minutes
            assertTrue(
                repository.completeTerminally(
                    monitor.id,
                    MonitoredTrainSnapshot(arrived(), DataFreshness.Unknown, clock.now()),
                    listOf(TrainMonitorEvent.Arrived(testRunId)),
                    clock.now(),
                    2L,
                ).isEmpty(),
            )

            val final = repository.observeEndedMonitors().first().single()
            assertEquals(endedAt, final.endedAt)
            assertEquals(cancelled(), final.lastSnapshot?.train)
            assertEquals(3L, acceptedGeneration(database, monitor.id.value))
            // The rejected older ARRIVED was never persisted: its event key
            // cannot be claimed.
            assertTrue(
                !repository.claimNotification(
                    TrainMonitorEvent.Arrived(testRunId),
                    clock.now(),
                    5.minutes,
                ),
            )
        }
    }

    @Test fun sameOrderTerminalIsIdempotentWithoutDuplicate() = runTest {
        withRepository { database, clock, repository ->
            val monitor = seedRunning(repository, clock)
            runCurrent()

            val endedAt = clock.now()
            assertEquals(
                listOf(TrainMonitorEvent.Arrived(testRunId)),
                repository.completeTerminally(
                    monitor.id,
                    MonitoredTrainSnapshot(arrived(), DataFreshness.Unknown, endedAt),
                    listOf(TrainMonitorEvent.Arrived(testRunId)),
                    endedAt,
                    2L,
                ),
            )
            clock.instant += 30.minutes
            assertTrue(
                repository.completeTerminally(
                    monitor.id,
                    MonitoredTrainSnapshot(arrived(), DataFreshness.Unknown, clock.now()),
                    listOf(TrainMonitorEvent.Arrived(testRunId)),
                    clock.now(),
                    2L,
                ).isEmpty(),
            )

            val final = repository.observeEndedMonitors().first().single()
            assertEquals(endedAt, final.endedAt)
            assertEquals(2L, acceptedGeneration(database, monitor.id.value))
            assertTrue(repository.observeActiveMonitors().first().isEmpty())
        }
    }

    @Test fun sameTerminalStateNewerOrderRefreshesSnapshotWithoutMovingEndedAt() = runTest {
        withRepository { database, clock, repository ->
            val monitor = seedRunning(repository, clock)
            runCurrent()

            val endedAt = clock.now()
            repository.completeTerminally(
                monitor.id,
                MonitoredTrainSnapshot(arrived(), DataFreshness.Unknown, endedAt),
                listOf(TrainMonitorEvent.Arrived(testRunId)),
                endedAt,
                2L,
            )
            val versionAfterFirst =
                assertNotNull(repository.observeMonitor(testRunId).first()).snapshotVersion

            // Same ARRIVED state with a strictly newer observation: the final
            // snapshot refreshes to the fresher terminal detail (cache
            // parity), but endedAt never moves, no duplicate ARRIVED event is
            // claimed, retention does not restart, and polling stays off.
            clock.instant += 10.minutes
            val fresherAt = clock.now()
            assertTrue(
                repository.completeTerminally(
                    monitor.id,
                    MonitoredTrainSnapshot(arrived(), DataFreshness.Unknown, fresherAt),
                    listOf(TrainMonitorEvent.Arrived(testRunId)),
                    clock.now(),
                    3L,
                ).isEmpty(),
                "same-state correction claims no new logical terminal event",
            )

            val final = repository.observeEndedMonitors().first().single()
            assertEquals(endedAt, final.endedAt)
            assertEquals(fresherAt, final.lastSnapshot?.evaluatedAt)
            assertEquals(TrainMonitorEvent.Arrived(testRunId), final.lastEvent)
            assertEquals(3L, acceptedGeneration(database, monitor.id.value))
            assertEquals(versionAfterFirst + 1, final.snapshotVersion)
            assertTrue(repository.observeActiveMonitors().first().isEmpty())
            // The correction claimed nothing new, but the single persisted
            // ARRIVED event is still exactly-once claimable: first claim
            // succeeds, immediate reclaim is denied.
            assertTrue(repository.claimNotification(TrainMonitorEvent.Arrived(testRunId), fresherAt, 5.minutes))
            assertTrue(!repository.claimNotification(TrainMonitorEvent.Arrived(testRunId), fresherAt, 5.minutes))
        }
    }

    @Test fun removalWinsOverLateTerminalCorrection() = runTest {
        withRepository { database, clock, repository ->
            val monitor = seedRunning(repository, clock)
            runCurrent()

            repository.completeTerminally(
                monitor.id,
                MonitoredTrainSnapshot(arrived(), DataFreshness.Unknown, clock.now()),
                listOf(TrainMonitorEvent.Arrived(testRunId)),
                clock.now(),
                2L,
            )
            assertEquals(1, repository.observeEndedMonitors().first().size)

            repository.removeMonitor(testRunId)
            runCurrent()
            assertNull(repository.observeMonitor(testRunId).first())

            // A strictly newer terminal continuation arriving after durable
            // removal must not recreate the deleted monitor.
            clock.instant += 1.minutes
            assertTrue(
                repository.completeTerminally(
                    monitor.id,
                    MonitoredTrainSnapshot(cancelled(), DataFreshness.Unknown, clock.now()),
                    listOf(TrainMonitorEvent.Cancelled(testRunId)),
                    clock.now(),
                    3L,
                ).isEmpty(),
            )
            assertTrue(repository.observeActiveMonitors().first().isEmpty())
            assertTrue(repository.observeEndedMonitors().first().isEmpty())
            assertNull(repository.observeMonitor(testRunId).first())
            assertNull(database.realtimeQueries.monitorStateById(monitor.id.value).executeAsOneOrNull())
        }
    }

    @Test fun disabledMonitorIsNeverResurrectedByLateTerminalCorrection() = runTest {
        withRepository { _, clock, repository ->
            val monitor = seedRunning(repository, clock)
            runCurrent()

            repository.disableMonitor(monitor.id)
            clock.instant += 1.minutes
            assertTrue(
                repository.completeTerminally(
                    monitor.id,
                    MonitoredTrainSnapshot(arrived(), DataFreshness.Unknown, clock.now()),
                    listOf(TrainMonitorEvent.Arrived(testRunId)),
                    clock.now(),
                    2L,
                ).isEmpty(),
            )
            assertTrue(repository.observeActiveMonitors().first().isEmpty())
            assertTrue(repository.observeEndedMonitors().first().isEmpty())
        }
    }
}
