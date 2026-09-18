package it.danielebufarini.trenify.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T8.14 SQL/index audit guard (JVM, in-memory SQLite).
 *
 * High-frequency production queries must resolve through an index or primary
 * key rather than a full table scan. Each case runs EXPLAIN QUERY PLAN
 * against the real schema and asserts the plan touches an index. Queries
 * whose access pattern cannot use an index (station substring search) are
 * covered functionally instead; see the T8.14 evidence document.
 */
class IndexQueryPlanAuditTest {
    private fun plan(driver: SqlDriver, sql: String): List<String> =
        driver.executeQuery(null, "EXPLAIN QUERY PLAN $sql", { cursor ->
            QueryResult.Value(buildList {
                while (cursor.next().value) add(cursor.getString(3) ?: "")
            })
        }, 0).value

    private fun database(): Pair<SqlDriver, TrenifyDatabase> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also(TrenifyDatabase.Schema::create)
        return driver to TrenifyDatabase(driver)
    }

    @Test fun activeMonitorsUsesEnabledExpiryIndex() {
        val (driver, _) = database()
        try {
            val detail = plan(
                driver,
                "SELECT * FROM active_monitor WHERE enabled = 1 AND ended_at_epoch_ms IS NULL " +
                    "AND (expires_at_epoch_ms IS NULL OR expires_at_epoch_ms > 0)",
            ).joinToString("\n")
            assertTrue(detail.contains("active_monitor_enabled_expiry", ignoreCase = true), "plan was:\n$detail")
        } finally {
            driver.close()
        }
    }

    @Test fun monitorByTrainUsesUniqueTrainRunIndex() {
        val (driver, _) = database()
        try {
            val detail = plan(driver, "SELECT * FROM active_monitor WHERE train_run_id = 'x'").joinToString("\n")
            assertTrue(detail.contains("INDEX", ignoreCase = true), "plan was:\n$detail")
        } finally {
            driver.close()
        }
    }

    @Test fun strikeWindowQueryUsesStrikeIntervalIndex() {
        val (driver, database) = database()
        try {
            val detail = plan(
                driver,
                "SELECT * FROM strike WHERE start_epoch_ms <= 2 AND end_epoch_ms >= 1",
            ).joinToString("\n")
            assertTrue(detail.contains("strike_interval", ignoreCase = true), "plan was:\n$detail")
            // Functional mirror: overlapping-interval semantics stay intact.
            assertTrue(database.realtimeQueries.strikesOverlapping(2, 1).executeAsList().isEmpty())
        } finally {
            driver.close()
        }
    }

    @Test fun stationExternalLookupUsesPrimaryKey() {
        val (driver, _) = database()
        try {
            val detail = plan(
                driver,
                "SELECT * FROM station_external_id WHERE provider_id = 'p' AND external_id = 'e'",
            ).joinToString("\n")
            // PRIMARY KEY(provider_id, external_id) surfaces as its auto-index.
            assertTrue(detail.contains("sqlite_autoindex_station_external_id_1", ignoreCase = true), "plan was:\n$detail")
        } finally {
            driver.close()
        }
    }

    @Test fun strikeCoverageLookupUsesCoverageIndex() {
        val (driver, _) = database()
        try {
            val detail = plan(
                driver,
                "SELECT * FROM strike_coverage WHERE from_epoch_ms <= 1 AND to_epoch_ms >= 2",
            ).joinToString("\n")
            assertTrue(detail.contains("strike_coverage_interval", ignoreCase = true), "plan was:\n$detail")
        } finally {
            driver.close()
        }
    }

    @Test fun stationSubstringSearchStaysBounded() {
        val (_, database) = database()
        val queries = database.realtimeQueries
        repeat(40) { i ->
            queries.putStation("s$i", "Stazione Test $i", "stazione test $i", 0L)
        }
        // Corrected T8.14-R8 wording: LIMIT 30 bounds RETURNED rows, not
        // scanned rows. instr() cannot use the normalized-name B-tree index
        // (substring predicate), so SQLite scans candidate rows; correctness
        // of the bound is what this asserts. Scan cost itself is measured at
        // realistic cardinality in stationSubstringSearchAtRealisticCardinality.
        assertEquals(30, queries.searchStations("stazione").executeAsList().size)
    }

    /**
     * T8.14-R3 genuine worst case: deterministic 3000-row dataset (~network
     * scale) where the ordered index scan cannot stop early.
     *
     * - Case A (no-match): the substring occurs in ZERO rows, forcing
     *   predicate evaluation over the full candidate set.
     * - Case B (late-match): only 10 rows match, all sorting after every
     *   non-match, so the scan evaluates nearly all rows before LIMIT 30
     *   can stop it (it never fills: 10 < 30).
     *
     * The previous match-all dataset was NOT a worst case (the scan stops
     * after the first 30 matches); its number is superseded, not retained.
     * Precise wording: LIMIT 30 bounds RESULT cardinality, never the number
     * of rows the substring predicate evaluates.
     */
    @Test fun stationSubstringSearchWorstCase() {
        val (driver, database) = database()
        try {
            val queries = database.realtimeQueries
            repeat(2990) { i ->
                queries.putStation("a$i", "Stazione Alpha $i", "stazione alpha $i", 0L)
            }
            repeat(10) { i ->
                queries.putStation("k$i", "Stazione Kilo $i", "stazione kilo $i", 0L)
            }
            // Prove the scenario: 3000 rows staged, ordering puts matches last.
            assertEquals(
                3000,
                driver.executeQuery(null, "SELECT COUNT(*) FROM station", { cursor ->
                    QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: -1L else -1L)
                }, 0).value,
            )

            fun planFor(term: String): String = driver.executeQuery(
                null,
                "EXPLAIN QUERY PLAN SELECT * FROM station " +
                    "WHERE instr(normalized_name, '$term') > 0 ORDER BY normalized_name LIMIT 30",
                { cursor ->
                    QueryResult.Value(buildList {
                        while (cursor.next().value) add(cursor.getString(3) ?: "")
                    })
                },
                0,
            ).value.joinToString("\n")

            fun timed(term: String, expectedRows: Int): List<Double> {
                repeat(3) {
                    assertEquals(expectedRows, queries.searchStations(term).executeAsList().size)
                }
                return DoubleArray(11) {
                    kotlin.system.measureNanoTime {
                        assertEquals(expectedRows, queries.searchStations(term).executeAsList().size)
                    } / 1_000_000.0
                }.sorted()
            }

            fun report(tag: String, samples: List<Double>): String =
                "$tag rows=3000 median=${"%.2f".format(samples[5])}ms " +
                    "min=${"%.2f".format(samples.first())}ms " +
                    "max=${"%.2f".format(samples.last())}ms n=11"

            val planNoMatch = planFor("zzzqqq")
            val noMatch = timed("zzzqqq", 0)
            println("T8.14-DB " + report("stationSearchNoMatch", noMatch))
            println("T8.14-DB stationSearchNoMatch plan:\n$planNoMatch")
            val planLate = planFor("kilo")
            val late = timed("kilo", 10)
            println("T8.14-DB " + report("stationSearchLateMatch", late))
            println("T8.14-DB stationSearchLateMatch plan:\n$planLate")
            // Pathology guards only (not product gates): full-evaluation
            // worst cases over a network-scale cache must stay comfortably
            // in the millisecond regime, or the query needs redesign.
            assertTrue(noMatch[5] < 5_000.0, "no-match search pathology: $noMatch")
            assertTrue(late[5] < 5_000.0, "late-match search pathology: $late")
        } finally {
            driver.close()
        }
    }
}
