package it.danielebufarini.trenify.data

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import it.danielebufarini.trenify.database.TrenifyDatabase

actual fun createRepositoryTestDriver(): SqlDriver = NativeSqliteDriver(
    TrenifyDatabase.Schema,
    "realtime-test",
    onConfiguration = { it.copy(inMemory = true) },
)

private object UnmanagedTestSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 5L
    override fun create(driver: SqlDriver): QueryResult.Value<Unit> = QueryResult.Value(Unit)
    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> = QueryResult.Value(Unit)
}

actual fun createUnmanagedRepositoryTestDriver(): SqlDriver = NativeSqliteDriver(
    UnmanagedTestSchema,
    "realtime-migration-test",
    onConfiguration = { it.copy(inMemory = true) },
)
