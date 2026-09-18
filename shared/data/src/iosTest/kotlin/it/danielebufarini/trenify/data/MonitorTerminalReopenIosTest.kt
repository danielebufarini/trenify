package it.danielebufarini.trenify.data

import app.cash.sqldelight.driver.native.NativeSqliteDriver
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
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * T7.11 corrective (iOS): true persisted-driver reopen evidence on the real
 * native driver. A uniquely named file-backed database is closed and reopened
 * with a fresh driver/repository. The unique name keeps runs hermetic without
 * guessing sandbox paths for cleanup.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitorTerminalReopenIosTest {
    @Test fun endedLifecycleSurvivesFileCloseAndReopen() = runTest {
        val name = "t711-reopen-${NSUUID().UUIDString()}.db"
        val clock = MutableClock()
        var endedAt = 0L
        lateinit var evaluatedAt: kotlin.time.Instant

        NativeSqliteDriver(TrenifyDatabase.Schema, name).also { firstDriver ->
            try {
                val repository = SqlDelightMonitoringRepository(
                    TrenifyDatabase(firstDriver), clock, StandardTestDispatcher(testScheduler),
                )
                val monitor = repository.createMonitor(testRunId, MonitorThresholds(delayMinutes = 15), null)
                val arrived = testRun.copy(summary = testRun.summary.copy(status = TrainStatus.ARRIVED))
                val snapshot = MonitoredTrainSnapshot(arrived, DataFreshness.Unknown, clock.now())
                evaluatedAt = snapshot.evaluatedAt
                endedAt = clock.now().toEpochMilliseconds()
                assertEquals(
                    listOf(TrainMonitorEvent.Arrived(testRunId)),
                    repository.completeTerminally(
                        monitor.id, snapshot, listOf(TrainMonitorEvent.Arrived(testRunId)), clock.now(), 2L),
                )
                assertTrue(repository.claimNotification(TrainMonitorEvent.Arrived(testRunId), evaluatedAt, 5.minutes))
                runCurrent()
            } finally {
                firstDriver.close()
            }
        }

        NativeSqliteDriver(TrenifyDatabase.Schema, name).also { secondDriver ->
            try {
                val restarted = SqlDelightMonitoringRepository(
                    TrenifyDatabase(secondDriver), MutableClock(), StandardTestDispatcher(testScheduler),
                )
                assertTrue(restarted.observeActiveMonitors().first().isEmpty())
                val ended = restarted.observeEndedMonitors().first().single()
                assertEquals(endedAt, ended.endedAt?.toEpochMilliseconds())
                assertEquals(TrainStatus.ARRIVED, ended.lastSnapshot?.train?.summary?.status)
                assertEquals(TrainMonitorEvent.Arrived(testRunId), ended.lastEvent)
                // The marked claim survived: no second delivery.
                assertTrue(!restarted.claimNotification(TrainMonitorEvent.Arrived(testRunId), evaluatedAt, 5.minutes))
                // Re-observing the terminal state moves nothing.
                val later = MutableClock()
                later.instant += 30.minutes
                assertTrue(
                    restarted.completeTerminally(
                        ended.id,
                        assertNotNull(ended.lastSnapshot),
                        listOf(TrainMonitorEvent.Arrived(testRunId)),
                        later.now(), 2L).isEmpty(),
                )
                assertEquals(endedAt, restarted.observeEndedMonitors().first().single().endedAt?.toEpochMilliseconds())
            } finally {
                secondDriver.close()
            }
        }

        NativeSqliteDriver(TrenifyDatabase.Schema, name).also { thirdDriver ->
            try {
                val lateClock = MutableClock()
                lateClock.instant = kotlin.time.Instant.fromEpochMilliseconds(endedAt) + 25.hours
                val late = SqlDelightMonitoringRepository(
                    TrenifyDatabase(thirdDriver), lateClock, StandardTestDispatcher(testScheduler),
                )
                assertTrue(late.observeEndedMonitors().first().isEmpty())
                late.cleanupExpiredEnded(lateClock.now())
                assertNull(late.observeMonitor(testRunId).first())
            } finally {
                thirdDriver.close()
            }
        }
    }
}
