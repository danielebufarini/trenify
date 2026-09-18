package it.danielebufarini.trenify.data

import app.cash.sqldelight.db.SqlDriver
import it.danielebufarini.trenify.core.domain.MonitorThresholds
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T7.15 persistence closure: every historical migration runs in order against
 * real seeded data on both database drivers. Version 1 exercises the full
 * empty version 1 through the full chain, the version 2 base tables, and the
 * version 4 monitor/strike tables. All prove no
 * destructive reset and byte-identical preservation, migrated defaults for
 * later columns, an empty (never fabricated) strike-coverage table, a working
 * settings table (15.sqm) and writable new tables.
 *
 * Frozen DDL below is verbatim from the historical `.sqm` files; only
 * unrelated newer tables are omitted per fixture.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoricalMigrationPreservationTest {
    @Test fun migrationFromEmptyVersion1CreatesCurrentSchema() = runTest {
        val driver = createUnmanagedRepositoryTestDriver()
        try {
            TrenifyDatabase.Schema.migrate(driver, 1, TrenifyDatabase.Schema.version)

            // The whole historical chain runs on an empty version 1 database,
            // including the settings table (15.sqm).
            val database = TrenifyDatabase(driver)
            val settings = SqlDelightNotificationSettingsRepository(
                database,
                StandardTestDispatcher(testScheduler),
            )
            settings.setNotificationsEnabled(true)
            assertTrue(settings.observe().first().notificationsEnabled)
        } finally {
            driver.close()
        }
    }

    @Test fun migrationFromVersion2PreservesCacheAndPersonalData() = runTest {
        val driver = createUnmanagedRepositoryTestDriver()
        try {
            version1Tables().forEach { sql -> driver.execute(null, sql, 0) }
            seedVersion1Rows(driver)

            TrenifyDatabase.Schema.migrate(driver, 2, TrenifyDatabase.Schema.version)

            assertVersion1RowsPreserved(TrenifyDatabase(driver))
        } finally {
            driver.close()
        }
    }

    @Test fun migrationFromVersion4PreservesMonitorsStrikesAndSettings() = runTest {
        val driver = createUnmanagedRepositoryTestDriver()
        try {
            (version1Tables() + version2Tables() + version3Tables()).forEach { sql ->
                driver.execute(null, sql, 0)
            }
            seedVersion1Rows(driver)
            seedVersion3Rows(driver)

            TrenifyDatabase.Schema.migrate(driver, 4, TrenifyDatabase.Schema.version)

            val database = TrenifyDatabase(driver)
            val queries = database.realtimeQueries
            assertVersion1RowsPreserved(database)

            // Monitors and events survive with migrated lifecycle defaults:
            // still active, no fabricated terminal instant or refresh failure.
            val lifecycle = queries.monitorLifecycleByTrain("run-9624").executeAsOne()
            assertEquals("m1", lifecycle.id)
            assertEquals(1L, lifecycle.enabled)
            assertNull(lifecycle.ended_at_epoch_ms)
            val state = queries.monitorStateById("m1").executeAsOne()
            assertEquals("{}", state.snapshot)
            assertEquals("UNKNOWN", state.freshness_kind)
            assertEquals(12345L, state.evaluated_at_epoch_ms)
            assertEquals("DELAY", state.last_event_type)
            assertEquals(0L, state.snapshot_version)
            assertEquals(0L, state.accepted_refresh_generation)
            val event = queries.eventByKey("k1").executeAsOne()
            assertEquals("DELAY", event.event_type)
            assertEquals(12346L, event.notified_at_epoch_ms)

            // Strike snapshot, sync state, opt-in and notification claims survive.
            val strike = queries.strikeById("st1").executeAsOne()
            assertEquals(1000L, strike.start_epoch_ms)
            assertEquals(2000L, strike.end_epoch_ms)
            assertEquals("SCHEDULED", strike.status)
            assertEquals("fp1", strike.content_fingerprint)
            val sync = queries.strikeSyncState().executeAsOne()
            assertEquals(1600L, sync.fetched_at_epoch_ms)
            assertEquals(1500L, sync.source_updated_at_epoch_ms)
            assertEquals(0L, queries.strikeNotificationsEnabled().executeAsOne())
            assertEquals("sk1", queries.strikeNotificationEventByKey("sk1").executeAsOne().event_key)
            // No interval was refreshed through the new coverage table, so no
            // row may be fabricated by the migration.
            assertTrue(queries.allStrikeCoverages().executeAsList().isEmpty())

            // Settings storage exists on the migrated database and round-trips
            // through the real repository (15.sqm; previously "no such table").
            val settings = SqlDelightNotificationSettingsRepository(
                database,
                StandardTestDispatcher(testScheduler),
            )
            settings.setNotificationsEnabled(false)
            settings.setDefaultThresholds(MonitorThresholds(delayMinutes = 30))
            val observed = settings.observe().first()
            assertEquals(false, observed.notificationsEnabled)
            assertEquals(30, observed.defaultThresholds.delayMinutes)

            // Tables introduced after version 3 are present and writable.
            queries.addFavoriteRoute("fr1", "s-mil", "s-rom")
            assertEquals(1, queries.favoriteRoutes().executeAsList().size)
            queries.addFavoriteTrain("ft1", "9624", "s-mil", "Milano Centrale", null, "Roma Termini")
            assertEquals(1, queries.favoriteTrains().executeAsList().size)
            queries.insertJourneySearchHistory(
                "j1", "s-mil", "s-rom", "Milano Centrale", "Roma Termini", 4000, "departAfter", 4001,
            )
            assertEquals("j1", queries.journeySearchHistoryById("j1").executeAsOne().id)
            queries.insertTrainSearchHistory(
                "t1", "9624", "2026-09-05", "s-mil", "Milano Centrale", null, "Roma Termini", 4002,
            )
            assertEquals("t1", queries.trainSearchHistoryById("t1").executeAsOne().id)
            queries.putStrikeCoverage(1000, 2000, 5000, null)
            assertEquals(1000L, queries.coveringStrikeCoverage(1200, 1800).executeAsOne().from_epoch_ms)
            database.transaction {
                queries.ensureRefreshOrder("run-9624")
                queries.bumpRefreshOrder("run-9624")
            }
            assertEquals(1L, queries.refreshOrderIssued("run-9624").executeAsOne())
        } finally {
            driver.close()
        }
    }

    private fun assertVersion1RowsPreserved(database: TrenifyDatabase) {
        val queries = database.realtimeQueries
        val milano = queries.stationById("s-mil").executeAsOne()
        assertEquals("Milano Centrale", milano.name)
        assertEquals("milano centrale", milano.normalized_name)
        assertEquals(1000L, milano.fetched_at)
        assertEquals("s-mil", queries.stationByExternal("vt", "MIL").executeAsOne().id)
        val run = queries.trainById("run-9624").executeAsOne()
        assertEquals("9624", run.train_number)
        assertEquals("2026-09-05", run.service_date)
        assertEquals("{}", run.summary)
        assertEquals(2500L, run.source_timestamp)
        assertEquals(listOf("{}", "{}"), queries.stopsForTrain("run-9624").executeAsList().map { it.payload })
        assertEquals("[]", queries.cacheByKey("board:s-mil:dep").executeAsOne().payload)
        assertEquals("{}", queries.cacheByKey("journey:s-mil:s-rom").executeAsOne().payload)
        assertEquals(listOf("s-rom"), queries.recentStations().executeAsList().map { it.id })
        assertEquals(listOf("s-mil"), queries.favoriteStations().executeAsList().map { it.id })
    }

    private fun seedVersion1Rows(driver: SqlDriver) {
        listOf(
            "INSERT INTO station VALUES ('s-mil', 'Milano Centrale', 'milano centrale', 1000)",
            "INSERT INTO station VALUES ('s-rom', 'Roma Termini', 'roma termini', 1000)",
            "INSERT INTO station_external_id VALUES ('vt', 'MIL', 's-mil')",
            "INSERT INTO station_external_id VALUES ('vt', 'ROM', 's-rom')",
            "INSERT INTO train_run VALUES ('run-9624', 'vt', '9624', '2026-09-05', '{}', 2000, 2500)",
            "INSERT INTO train_stop VALUES ('run-9624', 0, '{}')",
            "INSERT INTO train_stop VALUES ('run-9624', 1, '{}')",
            "INSERT INTO realtime_cache VALUES ('board:s-mil:dep', '[]', 2000, 2500)",
            "INSERT INTO realtime_cache VALUES ('journey:s-mil:s-rom', '{}', 2000, NULL)",
            "INSERT INTO recent_station VALUES ('s-rom', 3000)",
            "INSERT INTO favorite_station VALUES ('s-mil')",
        ).forEach { sql -> driver.execute(null, sql, 0) }
    }

    private fun seedVersion3Rows(driver: SqlDriver) {
        listOf(
            "INSERT INTO active_monitor VALUES ('m1', 'run-9624', 'vt', '9624', 's-mil', " +
                "'2026-09-05', 1, 15, 1, 1, 1, 1, 1, 1000, 2000)",
            "INSERT INTO monitor_state VALUES ('m1', '{}', 'UNKNOWN', NULL, NULL, NULL, 12345, 'DELAY', '{}')",
            "INSERT INTO monitor_event VALUES ('k1', 'm1', 'DELAY', '{}', 12345, 12346)",
            "INSERT INTO strike VALUES ('st1', 'mit', NULL, 1000, 2000, 'SCHEDULED', 'national', 'all', " +
                "'Lombardia', 'Milano', 'CGIL', 'Trenitalia', NULL, 'full', '{}', 'MIT', 'https://x', " +
                "1500, 1600, 'fp1')",
            "INSERT INTO strike_external_id VALUES ('mit', 'ext1', 'st1')",
            "INSERT INTO strike_sync_state VALUES (1, 1600, 1500)",
            "INSERT INTO strike_notification_setting VALUES (1, 0)",
            "INSERT INTO strike_notification_event VALUES ('sk1', 'st1', 'SCHEDULED', 1500, NULL)",
        ).forEach { sql -> driver.execute(null, sql, 0) }
    }

    // Version 1 tables, verbatim from 1.sqm.
    private fun version1Tables(): List<String> = listOf(
        "CREATE TABLE station (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, " +
            "normalized_name TEXT NOT NULL, fetched_at INTEGER NOT NULL)",
        "CREATE INDEX station_search ON station(normalized_name)",
        "CREATE TABLE station_external_id (provider_id TEXT NOT NULL, external_id TEXT NOT NULL, " +
            "station_id TEXT NOT NULL REFERENCES station(id), PRIMARY KEY (provider_id, external_id))",
        "CREATE INDEX station_external_internal ON station_external_id(station_id, provider_id)",
        "CREATE TABLE train_run (id TEXT NOT NULL PRIMARY KEY, provider_id TEXT NOT NULL, " +
            "train_number TEXT NOT NULL, service_date TEXT NOT NULL, summary TEXT NOT NULL, " +
            "fetched_at INTEGER NOT NULL, source_timestamp INTEGER)",
        "CREATE INDEX train_number_date ON train_run(train_number, service_date)",
        "CREATE TABLE train_stop (train_run_id TEXT NOT NULL REFERENCES train_run(id) ON DELETE CASCADE, " +
            "stop_index INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY (train_run_id, stop_index))",
        "CREATE TABLE realtime_cache (cache_key TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL, " +
            "fetched_at INTEGER NOT NULL, source_timestamp INTEGER)",
        "CREATE TABLE recent_station (station_id TEXT NOT NULL PRIMARY KEY REFERENCES station(id), " +
            "visited_at INTEGER NOT NULL)",
        "CREATE INDEX recent_station_time ON recent_station(visited_at)",
        "CREATE TABLE favorite_station (station_id TEXT NOT NULL PRIMARY KEY REFERENCES station(id))",
    )

    // Version 2 tables, verbatim from 2.sqm.
    private fun version2Tables(): List<String> = listOf(
        "CREATE TABLE active_monitor (id TEXT NOT NULL PRIMARY KEY, train_run_id TEXT NOT NULL UNIQUE, " +
            "provider_id TEXT NOT NULL, train_number TEXT NOT NULL, origin_ref TEXT NOT NULL, " +
            "service_date TEXT NOT NULL, enabled INTEGER NOT NULL, delay_threshold_minutes INTEGER, " +
            "notify_delay INTEGER NOT NULL, notify_platform INTEGER NOT NULL, " +
            "notify_cancellation INTEGER NOT NULL, notify_departure INTEGER NOT NULL, " +
            "notify_arrival INTEGER NOT NULL, created_at_epoch_ms INTEGER NOT NULL, expires_at_epoch_ms INTEGER)",
        "CREATE INDEX active_monitor_enabled_expiry ON active_monitor(enabled, expires_at_epoch_ms)",
        "CREATE TABLE monitor_state (monitor_id TEXT NOT NULL PRIMARY KEY REFERENCES active_monitor(id) " +
            "ON DELETE CASCADE, snapshot TEXT NOT NULL, freshness_kind TEXT NOT NULL, " +
            "fetched_at_epoch_ms INTEGER, source_timestamp_epoch_ms INTEGER, freshness_age_ms INTEGER, " +
            "evaluated_at_epoch_ms INTEGER NOT NULL, last_event_type TEXT, last_event_payload TEXT)",
        "CREATE TABLE monitor_event (event_key TEXT NOT NULL PRIMARY KEY, " +
            "monitor_id TEXT NOT NULL REFERENCES active_monitor(id) ON DELETE CASCADE, " +
            "event_type TEXT NOT NULL, payload TEXT NOT NULL, emitted_at_epoch_ms INTEGER NOT NULL, " +
            "notified_at_epoch_ms INTEGER)",
        "CREATE INDEX monitor_event_notification ON monitor_event(monitor_id, event_type, notified_at_epoch_ms)",
    )

    // Version 3 tables, verbatim from 3.sqm.
    private fun version3Tables(): List<String> = listOf(
        "CREATE TABLE strike (id TEXT NOT NULL PRIMARY KEY, provider_id TEXT NOT NULL, external_id TEXT, " +
            "start_epoch_ms INTEGER NOT NULL, end_epoch_ms INTEGER NOT NULL, status TEXT NOT NULL, " +
            "sector TEXT NOT NULL, relevance TEXT NOT NULL, regions TEXT NOT NULL, provinces TEXT NOT NULL, " +
            "unions TEXT NOT NULL, operators TEXT NOT NULL, workforce TEXT, mode TEXT NOT NULL, notes TEXT, " +
            "source_label TEXT NOT NULL, source_url TEXT NOT NULL, source_updated_at_epoch_ms INTEGER, " +
            "fetched_at_epoch_ms INTEGER NOT NULL, content_fingerprint TEXT NOT NULL)",
        "CREATE INDEX strike_interval ON strike(start_epoch_ms, end_epoch_ms)",
        "CREATE TABLE strike_external_id (provider_id TEXT NOT NULL, external_id TEXT NOT NULL, " +
            "strike_id TEXT NOT NULL, PRIMARY KEY (provider_id, external_id))",
        "CREATE INDEX strike_external_internal ON strike_external_id(strike_id)",
        "CREATE TABLE strike_sync_state (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1), " +
            "fetched_at_epoch_ms INTEGER NOT NULL, source_updated_at_epoch_ms INTEGER)",
        "CREATE TABLE strike_notification_setting (id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1), " +
            "enabled INTEGER NOT NULL)",
        "CREATE TABLE strike_notification_event (event_key TEXT NOT NULL PRIMARY KEY, " +
            "strike_id TEXT NOT NULL, event_type TEXT NOT NULL, emitted_at_epoch_ms INTEGER NOT NULL, " +
            "notified_at_epoch_ms INTEGER)",
        "CREATE INDEX strike_notification_pending ON " +
            "strike_notification_event(notified_at_epoch_ms, emitted_at_epoch_ms)",
    )
}
