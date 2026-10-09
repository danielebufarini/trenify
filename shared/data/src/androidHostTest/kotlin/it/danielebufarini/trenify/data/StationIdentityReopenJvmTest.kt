package it.danielebufarini.trenify.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test

class StationIdentityReopenJvmTest {
    @Test fun providerIdentityAndLegacyCachesSurviveRealFileReopen() = runBlocking {
        val directory = Files.createTempDirectory("trenify-station-identity").toFile()
        try {
            verifyStationIdentityAfterDriverReopen { italo, create ->
                JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("identity-$italo.db")}").also {
                    if (create) TrenifyDatabase.Schema.create(it)
                }
            }
        } finally { directory.deleteRecursively() }
    }
}
