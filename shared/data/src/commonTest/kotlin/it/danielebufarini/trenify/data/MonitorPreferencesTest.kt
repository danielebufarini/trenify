package it.danielebufarini.trenify.data

import it.danielebufarini.trenify.core.domain.EvaluationCommit
import it.danielebufarini.trenify.core.domain.MonitorThresholds
import it.danielebufarini.trenify.core.domain.MonitoredTrainSnapshot
import it.danielebufarini.trenify.core.domain.TrainMonitorEvent
import it.danielebufarini.trenify.core.model.DataFreshness
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * T7.7 per-monitor preference edits: mute/unmute, threshold and flag edits
 * preserve identity, snapshots, event claims, expiry and enabled state
 * without recreation or synthetic events.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitorPreferencesTest {
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

    private suspend fun seedEvaluatedMonitor(
        repository: SqlDelightMonitoringRepository,
        clock: MutableClock,
    ): MonitorSeed {
        val thresholds = MonitorThresholds(delayMinutes = 15, notifyPlatform = false)
        val monitor = repository.createMonitor(testRunId, thresholds, expiresAt = clock.now() + 1.hours)
        val event = TrainMonitorEvent.DelayThresholdCrossed(testRunId, 5, 20, 15)
        val snapshot = MonitoredTrainSnapshot(testRun, DataFreshness.Fresh(clock.now(), clock.now()), clock.now())
        assertEquals(
            listOf(event),
            committed(repository.persistEvaluation(monitor.id, snapshot, listOf(event), monitor.snapshotVersion, 1L)),
        )
        assertTrue(repository.claimNotification(event, snapshot.evaluatedAt, 5.minutes))
        return MonitorSeed(monitor.id.value, thresholds, snapshot, event)
    }

    @Test fun muteAndUnmutePreserveIdentitySnapshotClaimsExpiryAndEnabledState() = runTest {
        withRepository { _, clock, repository ->
            val seed = seedEvaluatedMonitor(repository, clock)
            val before = assertNotNull(repository.observeMonitor(testRunId).first())
            assertTrue(before.notificationsEnabled)

            repository.setMonitorNotificationsEnabled(testRunId, false)
            runCurrent()
            val muted = assertNotNull(repository.observeMonitor(testRunId).first())
            assertFalse(muted.notificationsEnabled)
            assertEquals(before.id, muted.id)
            assertEquals(before.trainRunId, muted.trainRunId)
            assertEquals(before.createdAt, muted.createdAt)
            assertEquals(before.expiresAt, muted.expiresAt)
            assertTrue(muted.enabled)
            assertEquals(before.thresholds, muted.thresholds)
            assertEquals(before.lastSnapshot, muted.lastSnapshot)
            assertEquals(before.lastEvent, muted.lastEvent)
            // The persisted claim survives: re-claiming stays denied and the
            // muted monitor is still active (muting never disables it).
            assertFalse(repository.claimNotification(seed.event, seed.snapshot.evaluatedAt, 5.minutes))
            assertEquals(1, repository.observeActiveMonitors().first().size)

            repository.setMonitorNotificationsEnabled(testRunId, true)
            runCurrent()
            val unmuted = assertNotNull(repository.observeMonitor(testRunId).first())
            assertTrue(unmuted.notificationsEnabled)
            assertEquals(before.id, unmuted.id)
            assertEquals(before.lastSnapshot, unmuted.lastSnapshot)
            assertEquals(before.lastEvent, unmuted.lastEvent)
        }
    }

    @Test fun thresholdEditPreservesIdentitySnapshotClaimsExpiryEnabledAndMute() = runTest {
        withRepository { _, clock, repository ->
            val seed = seedEvaluatedMonitor(repository, clock)
            val before = assertNotNull(repository.observeMonitor(testRunId).first())
            repository.setMonitorNotificationsEnabled(testRunId, false)
            runCurrent()

            val updated = MonitorThresholds(delayMinutes = 30)
            repository.updateMonitorPreferences(testRunId, updated)
            runCurrent()
            val after = assertNotNull(repository.observeMonitor(testRunId).first())
            assertEquals(updated, after.thresholds)
            assertEquals(before.id, after.id)
            assertEquals(before.createdAt, after.createdAt)
            assertEquals(before.expiresAt, after.expiresAt)
            assertTrue(after.enabled)
            assertFalse(after.notificationsEnabled)
            assertEquals(before.lastSnapshot, after.lastSnapshot)
            assertEquals(before.lastEvent, after.lastEvent)
            assertFalse(repository.claimNotification(seed.event, seed.snapshot.evaluatedAt, 5.minutes))
        }
    }

    @Test fun eventFlagEditPreservesIdentityAndHistory() = runTest {
        withRepository { _, clock, repository ->
            val seed = seedEvaluatedMonitor(repository, clock)
            val before = assertNotNull(repository.observeMonitor(testRunId).first())

            val updated = before.thresholds.copy(notifyCancellation = false, notifyArrival = false)
            repository.updateMonitorPreferences(testRunId, updated)
            runCurrent()
            val after = assertNotNull(repository.observeMonitor(testRunId).first())
            assertEquals(updated, after.thresholds)
            assertEquals(before.id, after.id)
            assertEquals(before.lastSnapshot, after.lastSnapshot)
            assertEquals(before.lastEvent, after.lastEvent)
            assertTrue(after.notificationsEnabled)
            assertFalse(repository.claimNotification(seed.event, seed.snapshot.evaluatedAt, 5.minutes))
        }
    }

    @Test fun preferenceEditsSurviveRestart() = runTest {
        withRepository { database, clock, repository ->
            seedEvaluatedMonitor(repository, clock)
            repository.setMonitorNotificationsEnabled(testRunId, false)
            repository.updateMonitorPreferences(testRunId, MonitorThresholds(delayMinutes = 45))
            runCurrent()

            val restarted = SqlDelightMonitoringRepository(database, clock, StandardTestDispatcher(testScheduler))
            val restored = assertNotNull(restarted.observeMonitor(testRunId).first())
            assertFalse(restored.notificationsEnabled)
            assertEquals(45, restored.thresholds.delayMinutes)
            assertNotNull(restored.lastSnapshot)
            assertNotNull(restored.lastEvent)
            assertTrue(restored.enabled)
        }
    }

    @Test fun preferenceEditsProduceNoSyntheticEvents() = runTest {
        withRepository { database, clock, repository ->
            val seed = seedEvaluatedMonitor(repository, clock)
            repository.setMonitorNotificationsEnabled(testRunId, false)
            repository.updateMonitorPreferences(testRunId, MonitorThresholds(delayMinutes = 30))
            runCurrent()

            // Re-persisting the unchanged snapshot yields nothing new and keeps
            // the recorded last event intact.
            val current = assertNotNull(repository.observeMonitor(testRunId).first())
            val idle = committed(
                repository.persistEvaluation(
                    it.danielebufarini.trenify.core.domain.MonitorId(seed.monitorId),
                    seed.snapshot,
                    emptyList(),
                    current.snapshotVersion,
                    1L,
                ),
            )
            assertTrue(idle.isEmpty())
            val after = assertNotNull(repository.observeMonitor(testRunId).first())
            assertEquals(seed.event, after.lastEvent)
            assertEquals(
                "DELAY",
                database.realtimeQueries.monitorStateById(seed.monitorId).executeAsOne().last_event_type,
            )
        }
    }

    @Test fun migrationFromVersion9PreservesMonitorsHistoryAndStrikeSetting() = runTest {
        val driver = createUnmanagedRepositoryTestDriver()
        try {
            version9MonitorTables().forEach { sql -> driver.execute(null, sql, 0) }
            // Valid production-shaped payloads, so the migrated rows exercise
            // the real read path below.
            val snapshotJson = cacheJson().encodeToString(testRun.record())
            val eventJson = """{"kind":"DELAY","previousDelay":5,"currentDelay":20,"threshold":15}"""
            val key = testRunId.key
            driver.execute(
                null,
                "INSERT INTO active_monitor VALUES ('m1', '$key', 'test', '123', 'opaque-origin', " +
                    "'2026-09-05', 1, 15, 1, 1, 1, 1, 1, 1000, 2000)",
                0,
            )
            // Raw string interpolation would break on JSON quotes; the insert
            // helper escapes them for the legacy-table seed.
            driver.execute(
                null,
                "INSERT INTO monitor_state VALUES ('m1', '${snapshotJson.sql}', 'UNKNOWN', NULL, NULL, NULL, 12345, " +
                    "'DELAY', '${eventJson.sql}')",
                0,
            )
            driver.execute(null, "INSERT INTO monitor_event VALUES ('k1', 'm1', 'DELAY', '{}', 12345, 12346)", 0)
            driver.execute(null, "INSERT INTO strike_notification_setting VALUES (1, 0)", 0)

            // Migrate to the current schema: the 9.sqm step still applies
            // first, so pre-T7.7 rows are covered, and the T7.11 steps must
            // leave them active with no fabricated endedAt.
            TrenifyDatabase.Schema.migrate(driver, 9, TrenifyDatabase.Schema.version)

            val database = TrenifyDatabase(driver)
            val clock = MutableClock()
            val repository = SqlDelightMonitoringRepository(database, clock, StandardTestDispatcher(testScheduler))
            val restored = assertNotNull(repository.observeMonitor(testRunId).first())
            // Pre-migration rows default to notifications on without losing state.
            assertTrue(restored.notificationsEnabled)
            assertNull(restored.endedAt)
            assertEquals(15, restored.thresholds.delayMinutes)
            assertTrue(restored.enabled)
            assertEquals(1000L, restored.createdAt.toEpochMilliseconds())
            assertEquals(2000L, restored.expiresAt?.toEpochMilliseconds())
            assertEquals(testRun, restored.lastSnapshot?.train)
            assertEquals(
                TrainMonitorEvent.DelayThresholdCrossed(testRunId, 5, 20, 15),
                restored.lastEvent,
            )
            val state = database.realtimeQueries.monitorStateById("m1").executeAsOne()
            assertEquals(snapshotJson, state.snapshot)
            assertEquals(eventJson, state.last_event_payload)
            val event = database.realtimeQueries.eventByKey("k1").executeAsOne()
            assertEquals(12346L, event.notified_at_epoch_ms)
            // The existing strike opt-in survives the monitor migration untouched.
            assertEquals(0L, database.realtimeQueries.strikeNotificationsEnabled().executeAsOne())
        } finally {
            driver.close()
        }
    }

    private fun committed(commit: EvaluationCommit) =
        assertIs<EvaluationCommit.Committed>(commit).inserted

    private val String.sql: String get() = replace("'", "''")

    // Version 9 monitor/strike tables, verbatim except for the T7.7 column the
    // 9.sqm migration adds. Unrelated v9 tables are omitted: the migration
    // only alters active_monitor.
    private fun version9MonitorTables(): List<String> = listOf(
        "CREATE TABLE active_monitor (id TEXT NOT NULL PRIMARY KEY, train_run_id TEXT NOT NULL UNIQUE, " +
            "provider_id TEXT NOT NULL, train_number TEXT NOT NULL, origin_ref TEXT NOT NULL, " +
            "service_date TEXT NOT NULL, enabled INTEGER NOT NULL, delay_threshold_minutes INTEGER, " +
            "notify_delay INTEGER NOT NULL, notify_platform INTEGER NOT NULL, notify_cancellation INTEGER NOT NULL, " +
            "notify_departure INTEGER NOT NULL, notify_arrival INTEGER NOT NULL, " +
            "created_at_epoch_ms INTEGER NOT NULL, expires_at_epoch_ms INTEGER)",
        "CREATE TABLE monitor_state (monitor_id TEXT NOT NULL PRIMARY KEY REFERENCES active_monitor(id) " +
            "ON DELETE CASCADE, snapshot TEXT NOT NULL, freshness_kind TEXT NOT NULL, " +
            "fetched_at_epoch_ms INTEGER, source_timestamp_epoch_ms INTEGER, freshness_age_ms INTEGER, " +
            "evaluated_at_epoch_ms INTEGER NOT NULL, last_event_type TEXT, last_event_payload TEXT)",
        "CREATE TABLE monitor_event (event_key TEXT NOT NULL PRIMARY KEY, " +
            "monitor_id TEXT NOT NULL REFERENCES active_monitor(id) ON DELETE CASCADE, " +
            "event_type TEXT NOT NULL, payload TEXT NOT NULL, emitted_at_epoch_ms INTEGER NOT NULL, " +
            "notified_at_epoch_ms INTEGER)",
        "CREATE TABLE strike_notification_setting (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1), " +
            "enabled INTEGER NOT NULL)",
    )

    private data class MonitorSeed(
        val monitorId: String,
        val thresholds: MonitorThresholds,
        val snapshot: MonitoredTrainSnapshot,
        val event: TrainMonitorEvent,
    )
}
