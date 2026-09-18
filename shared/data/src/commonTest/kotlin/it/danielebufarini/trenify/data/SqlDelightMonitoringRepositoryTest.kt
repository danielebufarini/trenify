package it.danielebufarini.trenify.data

import app.cash.sqldelight.db.SqlDriver
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testRun
import it.danielebufarini.trenify.core.testing.testRunId
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private fun committed(commit: EvaluationCommit) =
    assertIs<EvaluationCommit.Committed>(commit).inserted

@OptIn(ExperimentalCoroutinesApi::class)
class SqlDelightMonitoringRepositoryTest {
    private suspend fun kotlinx.coroutines.test.TestScope.withRepository(
        block: suspend kotlinx.coroutines.test.TestScope.(SqlDriver, TrenifyDatabase, MutableClock, SqlDelightMonitoringRepository) -> Unit,
    ) {
        val driver = createRepositoryTestDriver()
        val database = TrenifyDatabase(driver)
        val clock = MutableClock()
        val repository = SqlDelightMonitoringRepository(database, clock, StandardTestDispatcher(testScheduler))
        try {
            block(driver, database, clock, repository)
        } finally {
            driver.close()
        }
    }

    @Test fun createRemoveAndObservationAreReactive() = runTest {
        withRepository { _, _, clock, repository ->
            val emissions = mutableListOf<List<TrainMonitor>>()
            val observation = backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
                repository.observeActiveMonitors().take(3).toList(emissions)
            }
            runCurrent()
            repository.createMonitor(testRunId, expiresAt = clock.now() + 1.hours)
            runCurrent()
            repository.removeMonitor(testRunId)
            runCurrent()
            assertEquals(listOf(0, 1, 0), emissions.map { it.size })
            observation.cancel()
        }
    }

    @Test fun snapshotAndEventsSurviveRepositoryRestart() = runTest {
        withRepository { _, database, clock, repository ->
            val monitor = repository.createMonitor(testRunId, expiresAt = clock.now() + 1.hours)
            val event = TrainMonitorEvent.DelayThresholdCrossed(testRunId, 5, 20, 15)
            val snapshot = MonitoredTrainSnapshot(testRun, DataFreshness.Fresh(clock.now(), clock.now()), clock.now())
            assertEquals(
                listOf(event),
                committed(repository.persistEvaluation(monitor.id, snapshot, listOf(event), monitor.snapshotVersion, 1L)),
            )

            val restarted = SqlDelightMonitoringRepository(database, clock, StandardTestDispatcher(testScheduler))
            val restored = assertNotNull(restarted.observeMonitor(testRunId).first())
            assertEquals(snapshot, restored.lastSnapshot)
            assertEquals(event, restored.lastEvent)
            assertEquals(1, restored.snapshotVersion)
            assertTrue(
                committed(restarted.persistEvaluation(monitor.id, snapshot, listOf(event), restored.snapshotVersion, 1L)).isEmpty(),
            )
        }
    }

    @Test fun expirationAndNotificationCooldownArePersisted() = runTest {
        withRepository { _, _, clock, repository ->
            val monitor = repository.createMonitor(testRunId, expiresAt = clock.now() + 1.hours)
            val first = TrainMonitorEvent.DelayThresholdCrossed(testRunId, 5, 20, 15)
            var snapshot = MonitoredTrainSnapshot(testRun, DataFreshness.Unknown, clock.now())
            committed(repository.persistEvaluation(monitor.id, snapshot, listOf(first), monitor.snapshotVersion, 1L))
            assertTrue(repository.claimNotification(first, snapshot.evaluatedAt, 5.minutes))
            assertFalse(repository.claimNotification(first, snapshot.evaluatedAt, 5.minutes))

            clock.instant += 1.minutes
            val second = TrainMonitorEvent.DelayThresholdCrossed(testRunId, 20, 35, 15)
            snapshot = snapshot.copy(evaluatedAt = clock.now())
            val current = assertNotNull(repository.observeMonitor(testRunId).first())
            committed(repository.persistEvaluation(monitor.id, snapshot, listOf(second), current.snapshotVersion, 1L))
            assertFalse(repository.claimNotification(second, snapshot.evaluatedAt, 5.minutes))

            clock.instant += 1.hours
            repository.disableExpired(clock.now())
            assertTrue(repository.observeActiveMonitors().first().isEmpty())
        }
    }
}
