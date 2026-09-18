package it.danielebufarini.trenify.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import it.danielebufarini.trenify.core.domain.MonitorThresholds
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
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * T7.11 corrective: true persisted-driver reopen evidence. A file-backed
 * database is closed and reopened with a fresh driver/repository, proving
 * ended-monitor state and event claims survive a real restart — not merely
 * repository recreation over a still-open in-memory database.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitorTerminalReopenJvmTest {
    @Test fun endedLifecycleSurvivesFileCloseAndReopen() = runTest {
        val dir = Files.createTempDirectory("trenify-t711-reopen").toFile()
        try {
            val url = "jdbc:sqlite:${dir.resolve("trenify.db")}"
            val clock = MutableClock()
            val endedAt: Long
            runCurrent()

            // First lifetime: create an active monitor, then commit terminal
            // state and its arrival event.
            JdbcSqliteDriver(url).use { firstDriver ->
                TrenifyDatabase.Schema.create(firstDriver)
                val database = TrenifyDatabase(firstDriver)
                val repository = SqlDelightMonitoringRepository(
                    database, clock, StandardTestDispatcher(testScheduler),
                )
                val monitor = repository.createMonitor(testRunId, MonitorThresholds(delayMinutes = 15), null)
                val arrived = testRun.copy(summary = testRun.summary.copy(status = TrainStatus.ARRIVED))
                val snapshot = MonitoredTrainSnapshot(arrived, DataFreshness.Unknown, clock.now())
                endedAt = clock.now().toEpochMilliseconds()
                val inserted = repository.completeTerminally(
                    monitor.id,
                    snapshot,
                    listOf(TrainMonitorEvent.Arrived(testRunId)),
                    clock.now(), 2L)
                assertEquals(listOf(TrainMonitorEvent.Arrived(testRunId)), inserted)
                assertEquals(1, repository.observeEndedMonitors().first().size)
                // Simulate the delivered terminal notification: the claim is
                // marked before close, so reopen must never deliver it again.
                assertTrue(repository.claimNotification(TrainMonitorEvent.Arrived(testRunId), snapshot.evaluatedAt, 5.minutes))
                runCurrent()
            }

            // Second lifetime: a fresh driver and repository over the same
            // closed file. Nothing is reconstructed from memory.
            JdbcSqliteDriver(url).use { secondDriver ->
                val database = TrenifyDatabase(secondDriver)
                val clockAfterRestart = MutableClock()
                val restarted = SqlDelightMonitoringRepository(
                    database, clockAfterRestart, StandardTestDispatcher(testScheduler),
                )
                // Ended lifecycle, original endedAt, final snapshot, last
                // event and version all preserved; no active polling
                // eligibility for the ended monitor.
                assertTrue(restarted.observeActiveMonitors().first().isEmpty())
                val ended = restarted.observeEndedMonitors().first().single()
                assertEquals(endedAt, ended.endedAt?.toEpochMilliseconds())
                assertEquals(TrainStatus.ARRIVED, ended.lastSnapshot?.train?.summary?.status)
                assertEquals(TrainMonitorEvent.Arrived(testRunId), ended.lastEvent)
                assertEquals(1, ended.snapshotVersion)

                // The terminal event claim survived: it cannot be claimed
                // again, and re-observing the terminal state neither moves
                // endedAt nor duplicates the event.
                val snapshot = assertNotNull(ended.lastSnapshot)
                assertTrue(
                    !restarted.claimNotification(
                        TrainMonitorEvent.Arrived(testRunId),
                        snapshot.evaluatedAt,
                        5.minutes,
                    ),
                )
                clockAfterRestart.instant += 30.minutes
                assertTrue(
                    restarted.completeTerminally(
                        ended.id,
                        snapshot,
                        listOf(TrainMonitorEvent.Arrived(testRunId)),
                        clockAfterRestart.now(), 2L).isEmpty(),
                )
                assertEquals(endedAt, restarted.observeEndedMonitors().first().single().endedAt?.toEpochMilliseconds())
                runCurrent()
            }

            // Third lifetime past the deadline: hidden and cleaned.
            JdbcSqliteDriver(url).use { thirdDriver ->
                val database = TrenifyDatabase(thirdDriver)
                val lateClock = MutableClock()
                lateClock.instant = Instant.fromEpochMilliseconds(endedAt) + 25.hours
                val late = SqlDelightMonitoringRepository(
                    database, lateClock, StandardTestDispatcher(testScheduler),
                )
                assertTrue(late.observeEndedMonitors().first().isEmpty())
                late.cleanupExpiredEnded(lateClock.now())
                assertNull(late.observeMonitor(testRunId).first())
                runCurrent()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
