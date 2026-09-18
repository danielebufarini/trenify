package it.danielebufarini.trenify.core.database

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

expect fun createTestDriver(): SqlDriver

class DatabaseSmokeTest {
    @Test
    fun createsQueriesAndObservesTheSharedDatabase() = runTest {
        val driver = createTestDriver()
        val database = createDatabase(driver)

        database.appMetadataQueries.insertOrReplace("schema", "ready")

        assertEquals("ready", database.appMetadataQueries.selectByKey("schema").executeAsOne().value_)
        assertEquals(listOf("ready"), database.observeMetadata().first().map { it.value_ })
        driver.close()
    }
}
