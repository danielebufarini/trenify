package it.danielebufarini.trenify.data

import app.cash.sqldelight.db.SqlDriver
import it.danielebufarini.trenify.core.domain.EvaluationCommit
import it.danielebufarini.trenify.core.domain.MonitorId
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
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * T7.11 terminal lifecycle on the real SQLDelight driver: ACTIVE → ENDED,
 * stable endedAt, retention visibility/expiry, stale-refresh protection,
 * manual-stop versus terminal ordering, idempotent cleanup and migration.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitorTerminalLifecycleTest {
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

    private fun arrived() = testRun.copy(summary = testRun.summary.copy(status = TrainStatus.ARRIVED))
    private fun cancelled() = testRun.copy(summary = testRun.summary.copy(status = TrainStatus.CANCELLED))

    private suspend fun seedActive(repository: SqlDelightMonitoringRepository, clock: MutableClock): MonitorId {
        val monitor = repository.createMonitor(testRunId, expiresAt = clock.now() + 1.hours)
        val snapshot = MonitoredTrainSnapshot(testRun, DataFreshness.Unknown, clock.now())
        val committed = committed(repository.persistEvaluation(monitor.id, snapshot, emptyList(), monitor.snapshotVersion, 1L))
        assertTrue(committed.isEmpty())
        return monitor.id
    }

    private fun committed(commit: EvaluationCommit) =
        assertIs<EvaluationCommit.Committed>(commit).inserted

    @Test fun arrivedTransitionsActiveToEndedWithStableEndedAt() = runTest {
        withRepository { _, _, clock, repository ->
            val id = seedActive(repository, clock)
            val arrivedAt = clock.now()
            val arrivedSnapshot = MonitoredTrainSnapshot(arrived(), DataFreshness.Unknown, arrivedAt)
            assertEquals(
                listOf(TrainMonitorEvent.Arrived(testRunId)),
                repository.completeTerminally(id, arrivedSnapshot, listOf(TrainMonitorEvent.Arrived(testRunId)), arrivedAt, 2L),
            )

            assertTrue(repository.observeActiveMonitors().first().isEmpty())
            val ended = repository.observeEndedMonitors().first()
            assertEquals(1, ended.size)
            assertEquals(arrivedAt, ended.single().endedAt)
            assertEquals(arrived(), ended.single().lastSnapshot?.train)
            assertEquals(TrainMonitorEvent.Arrived(testRunId), ended.single().lastEvent)

            // Repeated terminal observations change nothing: stable endedAt,
            // stable final snapshot, no duplicate event, no retention slide.
            clock.instant += 30.minutes
            val repeated = MonitoredTrainSnapshot(arrived(), DataFreshness.Unknown, clock.now())
            assertTrue(
                repository.completeTerminally(id, repeated, listOf(TrainMonitorEvent.Arrived(testRunId)), clock.now(), 2L).isEmpty(),
            )
            val still = repository.observeEndedMonitors().first().single()
            assertEquals(arrivedAt, still.endedAt)
            assertEquals(arrivedSnapshot, still.lastSnapshot)
        }
    }

    @Test fun finalCancelledTransitionsActiveToEnded() = runTest {
        withRepository { _, _, clock, repository ->
            val id = seedActive(repository, clock)
            val endedAt = clock.now()
            val snapshot = MonitoredTrainSnapshot(cancelled(), DataFreshness.Unknown, endedAt)
            assertEquals(
                listOf(TrainMonitorEvent.Cancelled(testRunId)),
                repository.completeTerminally(id, snapshot, listOf(TrainMonitorEvent.Cancelled(testRunId)), endedAt, 2L),
            )
            assertTrue(repository.observeActiveMonitors().first().isEmpty())
            val ended = repository.observeEndedMonitors().first().single()
            assertEquals(endedAt, ended.endedAt)
            assertEquals(cancelled(), ended.lastSnapshot?.train)
        }
    }

    @Test fun nonTerminalStatusesRemainActive() = runTest {
        withRepository { _, _, clock, repository ->
            val id = seedActive(repository, clock)
            listOf(
                TrainStatus.PARTIALLY_CANCELLED,
                TrainStatus.DIVERTED,
                TrainStatus.RESCHEDULED,
                TrainStatus.UNKNOWN,
            ).forEach { status ->
                clock.instant += 1.minutes
                val snapshot = MonitoredTrainSnapshot(
                    testRun.copy(summary = testRun.summary.copy(status = status)),
                    DataFreshness.Unknown,
                    clock.now(),
                )
                val base = assertNotNull(repository.observeMonitor(testRunId).first())
                committed(repository.persistEvaluation(id, snapshot, emptyList(), base.snapshotVersion, 1L))
                assertEquals(1, repository.observeActiveMonitors().first().size)
                assertTrue(repository.observeEndedMonitors().first().isEmpty())
                assertNull(assertNotNull(repository.observeMonitor(testRunId).first()).endedAt)
            }
        }
    }

    @Test fun stalePersistAfterTerminalCannotOverwriteFinalSnapshot() = runTest {
        withRepository { _, _, clock, repository ->
            val id = seedActive(repository, clock)
            val endedAt = clock.now()
            repository.completeTerminally(
                id,
                MonitoredTrainSnapshot(arrived(), DataFreshness.Unknown, endedAt),
                listOf(TrainMonitorEvent.Arrived(testRunId)),
                endedAt, 2L)
            // An older in-flight refresh resumes with non-terminal data.
            clock.instant += 1.minutes
            val stale = MonitoredTrainSnapshot(testRun, DataFreshness.Unknown, clock.now())
            val delay = TrainMonitorEvent.DelayThresholdCrossed(testRunId, 5, 20, 15)
            assertEquals(
                EvaluationCommit.NotActive,
                repository.persistEvaluation(id, stale, listOf(delay), 0L, 0L),
            )

            val ended = repository.observeEndedMonitors().first().single()
            assertEquals(endedAt, ended.endedAt)
            assertEquals(arrived(), ended.lastSnapshot?.train)
            assertEquals(TrainMonitorEvent.Arrived(testRunId), ended.lastEvent)
            assertTrue(repository.observeActiveMonitors().first().isEmpty())
        }
    }

    @Test fun terminalAfterManualStopIsANoOpAndNeverResurrects() = runTest {
        withRepository { _, database, clock, repository ->
            val id = seedActive(repository, clock)
            repository.removeMonitor(testRunId)
            runCurrent()
            assertNull(repository.observeMonitor(testRunId).first())

            val endedAt = clock.now()
            assertTrue(
                repository.completeTerminally(
                    id,
                    MonitoredTrainSnapshot(arrived(), DataFreshness.Unknown, endedAt),
                    listOf(TrainMonitorEvent.Arrived(testRunId)),
                    endedAt, 2L).isEmpty(),
            )
            assertTrue(repository.observeActiveMonitors().first().isEmpty())
            assertTrue(repository.observeEndedMonitors().first().isEmpty())
            // No orphan state rows were recreated for the removed monitor.
            assertNull(database.realtimeQueries.monitorStateById(id.value).executeAsOneOrNull())
        }
    }

    @Test fun manualStopAfterTerminalRemovesTheRetainedMonitor() = runTest {
        withRepository { _, _, clock, repository ->
            val id = seedActive(repository, clock)
            repository.completeTerminally(
                id,
                MonitoredTrainSnapshot(arrived(), DataFreshness.Unknown, clock.now()),
                listOf(TrainMonitorEvent.Arrived(testRunId)),
                clock.now(), 2L)
            assertEquals(1, repository.observeEndedMonitors().first().size)

            repository.removeMonitor(testRunId)
            runCurrent()
            assertTrue(repository.observeActiveMonitors().first().isEmpty())
            assertTrue(repository.observeEndedMonitors().first().isEmpty())
        }
    }

    @Test fun removeEndedMonitorOnlyRemovesEndedAndIsIdempotent() = runTest {
        withRepository { _, _, clock, repository ->
            val id = seedActive(repository, clock)
            // Never touches an active monitor.
            repository.removeEndedMonitor(testRunId)
            runCurrent()
            assertEquals(1, repository.observeActiveMonitors().first().size)

            repository.completeTerminally(
                id,
                MonitoredTrainSnapshot(arrived(), DataFreshness.Unknown, clock.now()),
                listOf(TrainMonitorEvent.Arrived(testRunId)),
                clock.now(), 2L)
            repository.removeEndedMonitor(testRunId)
            runCurrent()
            assertTrue(repository.observeEndedMonitors().first().isEmpty())
            assertNull(repository.observeMonitor(testRunId).first())
            // Duplicate removal is safe.
            repository.removeEndedMonitor(testRunId)
            runCurrent()
            assertTrue(repository.observeEndedMonitors().first().isEmpty())
        }
    }

    @Test fun retentionBoundaryAndCleanup() = runTest {
        withRepository { _, _, clock, repository ->
            val active = repository.createMonitor(
                it.danielebufarini.trenify.core.testing.testRunId.copy(
                    number = it.danielebufarini.trenify.core.model.TrainNumber("999"),
                ),
                expiresAt = clock.now() + 48.hours,
            )
            val activeSnapshot = MonitoredTrainSnapshot(testRun, DataFreshness.Unknown, clock.now())
            val activeEvent = TrainMonitorEvent.DelayThresholdCrossed(active.trainRunId, 5, 20, 15)
            committed(repository.persistEvaluation(active.id, activeSnapshot, listOf(activeEvent), active.snapshotVersion, 1L))

            val id = seedActive(repository, clock)
            val endedAt = clock.now()
            repository.completeTerminally(
                id,
                MonitoredTrainSnapshot(arrived(), DataFreshness.Unknown, endedAt),
                listOf(TrainMonitorEvent.Arrived(testRunId)),
                endedAt, 2L)

            // Still visible just before the deadline; cleanup keeps it.
            clock.instant = endedAt + 24.hours - 1.minutes
            repository.cleanupExpiredEnded(clock.now())
            assertEquals(1, repository.observeEndedMonitors().first().size)

            // At exactly endedAt + 24h the monitor is excluded from visible
            // state even before physical cleanup runs.
            clock.instant = endedAt + 24.hours
            assertTrue(repository.observeEndedMonitors().first().isEmpty())
            repository.cleanupExpiredEnded(clock.now())
            runCurrent()
            assertTrue(repository.observeEndedMonitors().first().isEmpty())
            assertNull(repository.observeMonitor(testRunId).first())
            // Repeated cleanup is harmless and preserves the active monitor
            // with its snapshot and event claims intact.
            repository.cleanupExpiredEnded(clock.now())
            assertEquals(1, repository.observeActiveMonitors().first().size)
            assertEquals(activeSnapshot, assertNotNull(repository.observeMonitor(active.trainRunId).first()).lastSnapshot)
            // The active monitor's event rows survived cleanup of the expired
            // ended monitor: first claim succeeds, immediate reclaim is denied.
            assertTrue(repository.claimNotification(activeEvent, activeSnapshot.evaluatedAt, 5.minutes))
            assertTrue(!repository.claimNotification(activeEvent, activeSnapshot.evaluatedAt, 5.minutes))
        }
    }

    @Test fun safetyExpiryDoesNotDisableEndedMonitors() = runTest {
        withRepository { _, _, clock, repository ->
            val id = seedActive(repository, clock)
            val endedAt = clock.now()
            repository.completeTerminally(
                id,
                MonitoredTrainSnapshot(arrived(), DataFreshness.Unknown, endedAt),
                listOf(TrainMonitorEvent.Arrived(testRunId)),
                endedAt, 2L)
            // The two-day active safety lifetime passes during retention: the
            // ended monitor keeps its 24-hour Recently ended visibility.
            clock.instant += 2.hours
            repository.disableExpired(clock.now())
            assertEquals(1, repository.observeEndedMonitors().first().size)
            assertEquals(endedAt, repository.observeEndedMonitors().first().single().endedAt)
        }
    }

    @Test fun endedStateSurvivesRestartWithoutRepollOrDuplicateEvents() = runTest {
        withRepository { _, database, clock, repository ->
            val id = seedActive(repository, clock)
            val endedAt = clock.now()
            repository.completeTerminally(
                id,
                MonitoredTrainSnapshot(arrived(), DataFreshness.Unknown, endedAt),
                listOf(TrainMonitorEvent.Arrived(testRunId)),
                endedAt, 2L)

            val restarted = SqlDelightMonitoringRepository(database, clock, StandardTestDispatcher(testScheduler))
            assertTrue(restarted.observeActiveMonitors().first().isEmpty())
            val ended = restarted.observeEndedMonitors().first().single()
            assertEquals(endedAt, ended.endedAt)
            assertEquals(arrived(), ended.lastSnapshot?.train)
            assertEquals(TrainMonitorEvent.Arrived(testRunId), ended.lastEvent)
            // Restart never re-emits the terminal event.
            clock.instant += 1.minutes
            assertTrue(
                restarted.completeTerminally(
                    id,
                    MonitoredTrainSnapshot(arrived(), DataFreshness.Unknown, clock.now()),
                    listOf(TrainMonitorEvent.Arrived(testRunId)),
                    clock.now(), 2L).isEmpty(),
            )
            assertEquals(endedAt, restarted.observeEndedMonitors().first().single().endedAt)
        }
    }

    @Test fun restartAfterRetentionHidesAndCleansTheEndedMonitor() = runTest {
        withRepository { _, database, clock, repository ->
            val id = seedActive(repository, clock)
            val endedAt = clock.now()
            repository.completeTerminally(
                id,
                MonitoredTrainSnapshot(arrived(), DataFreshness.Unknown, endedAt),
                listOf(TrainMonitorEvent.Arrived(testRunId)),
                endedAt, 2L)
            clock.instant = endedAt + 25.hours
            val restarted = SqlDelightMonitoringRepository(database, clock, StandardTestDispatcher(testScheduler))
            assertTrue(restarted.observeEndedMonitors().first().isEmpty())
            restarted.cleanupExpiredEnded(clock.now())
            assertNull(restarted.observeMonitor(testRunId).first())
        }
    }

    @Test fun createAfterTerminalNeverReactivatesTheRetainedMonitor() = runTest {
        withRepository { _, _, clock, repository ->
            val id = seedActive(repository, clock)
            val endedAt = clock.now()
            val finalSnapshot = MonitoredTrainSnapshot(arrived(), DataFreshness.Unknown, endedAt)
            repository.completeTerminally(
                id,
                finalSnapshot,
                listOf(TrainMonitorEvent.Arrived(testRunId)),
                endedAt, 2L)

            // A Start/Create request for the retained ended monitor returns it
            // unchanged: endedAt stable, still out of active observation (no
            // polling eligibility), final snapshot and event state intact.
            clock.instant += 1.hours
            val returned = repository.createMonitor(
                testRunId,
                MonitorThresholds(delayMinutes = 45),
                expiresAt = clock.now() + 1.hours,
            )
            runCurrent()
            assertEquals(endedAt, returned.endedAt)
            assertEquals(finalSnapshot, returned.lastSnapshot)
            assertEquals(TrainMonitorEvent.Arrived(testRunId), returned.lastEvent)
            assertTrue(repository.observeActiveMonitors().first().isEmpty())
            assertEquals(1, repository.observeEndedMonitors().first().size)
            assertEquals(endedAt, repository.observeEndedMonitors().first().single().endedAt)
        }
    }

    @Test fun createAfterDurableRemovalStartsAGenuinelyNewActiveMonitor() = runTest {
        withRepository { _, _, clock, repository ->
            val id = seedActive(repository, clock)
            repository.completeTerminally(
                id,
                MonitoredTrainSnapshot(arrived(), DataFreshness.Unknown, clock.now()),
                listOf(TrainMonitorEvent.Arrived(testRunId)),
                clock.now(), 2L)
            repository.removeEndedMonitor(testRunId)
            runCurrent()
            assertNull(repository.observeMonitor(testRunId).first())

            val fresh = repository.createMonitor(testRunId, expiresAt = clock.now() + 1.hours)
            runCurrent()
            assertNull(fresh.endedAt)
            assertNull(fresh.lastSnapshot)
            assertEquals(1, repository.observeActiveMonitors().first().size)
            assertTrue(repository.observeEndedMonitors().first().isEmpty())
        }
    }

    @Test fun routeChangedEventRoundTripsAcrossRestart() = runTest {
        withRepository { _, database, clock, repository ->
            val id = seedActive(repository, clock)
            val event = TrainMonitorEvent.RouteChanged(testRunId, listOf("milano"), emptyList())
            val snapshot = MonitoredTrainSnapshot(testRun, DataFreshness.Unknown, clock.now())
            val base = assertNotNull(repository.observeMonitor(testRunId).first())
            assertEquals(
                listOf(event),
                committed(repository.persistEvaluation(id, snapshot, listOf(event), base.snapshotVersion, 1L)),
            )

            val restarted = SqlDelightMonitoringRepository(database, clock, StandardTestDispatcher(testScheduler))
            assertEquals(event, assertNotNull(restarted.observeMonitor(testRunId).first()).lastEvent)
        }
    }

    @Test fun migrationFromVersion10PreservesRowsWithNullEndedAt() = runTest {
        val driver = createUnmanagedRepositoryTestDriver()
        try {
            version10MonitorTables().forEach { sql -> driver.execute(null, sql, 0) }
            val snapshotJson = cacheJson().encodeToString(testRun.record())
            val eventJson = """{"kind":"DELAY","previousDelay":5,"currentDelay":20,"threshold":15}"""
            val key = testRunId.key
            driver.execute(
                null,
                "INSERT INTO active_monitor VALUES ('m1', '$key', 'test', '123', 'opaque-origin', " +
                    "'2026-09-05', 1, 15, 1, 1, 1, 1, 1, 1000, NULL, 1)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO monitor_state VALUES ('m1', '${snapshotJson.sql}', 'UNKNOWN', NULL, NULL, NULL, 12345, " +
                    "'DELAY', '${eventJson.sql}')",
                0,
            )
            driver.execute(null, "INSERT INTO monitor_event VALUES ('k1', 'm1', 'DELAY', '{}', 12345, 12346)", 0)

            TrenifyDatabase.Schema.migrate(driver, 10, TrenifyDatabase.Schema.version)

            val database = TrenifyDatabase(driver)
            val clock = MutableClock()
            val repository = SqlDelightMonitoringRepository(database, clock, StandardTestDispatcher(testScheduler))
            // Old rows keep backward-compatible active semantics: no endedAt
            // is fabricated and the monitor stays eligible for polling.
            val restored = assertNotNull(repository.observeMonitor(testRunId).first())
            assertNull(restored.endedAt)
            assertEquals(1, repository.observeActiveMonitors().first().size)
            assertTrue(repository.observeEndedMonitors().first().isEmpty())
            assertEquals(testRun, restored.lastSnapshot?.train)
            assertEquals(
                TrainMonitorEvent.DelayThresholdCrossed(testRunId, 5, 20, 15),
                restored.lastEvent,
            )
            assertTrue(restored.notificationsEnabled)
            // Pass-2 ordering migrates safely: the order table exists, the
            // migrated snapshot starts at the oldest generation, and ordered
            // commits work (with older generations rejected) on the old row.
            assertNull(database.realtimeQueries.refreshOrderAccepted(testRunId.key).executeAsOneOrNull())
            database.transaction {
                database.realtimeQueries.ensureRefreshOrder(testRunId.key)
                database.realtimeQueries.bumpRefreshOrder(testRunId.key)
            }
            assertEquals(1L, database.realtimeQueries.refreshOrderIssued(testRunId.key).executeAsOne())
            val migratedSnapshot = MonitoredTrainSnapshot(testRun, DataFreshness.Unknown, clock.now())
            assertIs<EvaluationCommit.Committed>(
                repository.persistEvaluation(restored.id, migratedSnapshot, emptyList(), restored.snapshotVersion, 5L),
            )
            val migrated = assertNotNull(repository.observeMonitor(testRunId).first())
            assertEquals(
                EvaluationCommit.StaleBase,
                repository.persistEvaluation(migrated.id, migratedSnapshot, emptyList(), migrated.snapshotVersion, 3L),
            )
        } finally {
            driver.close()
        }
    }

    private val String.sql: String get() = replace("'", "''")

    // Version 10 monitor tables, verbatim except for the T7.11 column the
    // 10.sqm migration adds.
    private fun version10MonitorTables(): List<String> = listOf(
        "CREATE TABLE active_monitor (id TEXT NOT NULL PRIMARY KEY, train_run_id TEXT NOT NULL UNIQUE, " +
            "provider_id TEXT NOT NULL, train_number TEXT NOT NULL, origin_ref TEXT NOT NULL, " +
            "service_date TEXT NOT NULL, enabled INTEGER NOT NULL, delay_threshold_minutes INTEGER, " +
            "notify_delay INTEGER NOT NULL, notify_platform INTEGER NOT NULL, notify_cancellation INTEGER NOT NULL, " +
            "notify_departure INTEGER NOT NULL, notify_arrival INTEGER NOT NULL, " +
            "created_at_epoch_ms INTEGER NOT NULL, expires_at_epoch_ms INTEGER, notify_enabled INTEGER NOT NULL DEFAULT 1)",
        "CREATE TABLE monitor_state (monitor_id TEXT NOT NULL PRIMARY KEY REFERENCES active_monitor(id) " +
            "ON DELETE CASCADE, snapshot TEXT NOT NULL, freshness_kind TEXT NOT NULL, " +
            "fetched_at_epoch_ms INTEGER, source_timestamp_epoch_ms INTEGER, freshness_age_ms INTEGER, " +
            "evaluated_at_epoch_ms INTEGER NOT NULL, last_event_type TEXT, last_event_payload TEXT)",
        "CREATE TABLE monitor_event (event_key TEXT NOT NULL PRIMARY KEY, " +
            "monitor_id TEXT NOT NULL REFERENCES active_monitor(id) ON DELETE CASCADE, " +
            "event_type TEXT NOT NULL, payload TEXT NOT NULL, emitted_at_epoch_ms INTEGER NOT NULL, " +
            "notified_at_epoch_ms INTEGER)",
    )
}
