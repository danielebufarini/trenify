package it.danielebufarini.trenify.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import it.danielebufarini.trenify.database.TrenifyDatabase

actual fun createTestDriver(): SqlDriver = NativeSqliteDriver(
    schema = TrenifyDatabase.Schema,
    name = ":memory:",
    onConfiguration = { it.copy(inMemory = true) },
)
