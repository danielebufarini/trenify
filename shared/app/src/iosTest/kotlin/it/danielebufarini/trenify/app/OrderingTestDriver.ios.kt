package it.danielebufarini.trenify.app

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import it.danielebufarini.trenify.database.TrenifyDatabase

actual fun createOrderingTestDriver(): SqlDriver = NativeSqliteDriver(
    TrenifyDatabase.Schema,
    "ordering-test",
    onConfiguration = { it.copy(inMemory = true) },
)
