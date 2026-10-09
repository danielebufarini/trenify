package it.danielebufarini.trenify.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test

class StationAutocompleteReopenJvmTest {
    @Test fun persistedNegativeRecoversAfterRealDatabaseReopenWithoutPrefixHistory() = runBlocking {
        val directory = Files.createTempDirectory("trenify-autocomplete-negative").toFile()
        try {
            verifyStationAutocompleteNegativeAfterDriverReopen { create ->
                JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("autocomplete.db")}").also {
                    if (create) TrenifyDatabase.Schema.create(it)
                }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun negativeAndDegradedCachesRecoverAcrossRealDatabaseReopen() = runBlocking {
        val directory = Files.createTempDirectory("trenify-autocomplete-recovery").toFile()
        try {
            verifyStationAutocompleteRecoveryAfterDriverReopen { create ->
                JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("autocomplete.db")}").also {
                    if (create) TrenifyDatabase.Schema.create(it)
                }
            }
        } finally { directory.deleteRecursively() }
    }
}
