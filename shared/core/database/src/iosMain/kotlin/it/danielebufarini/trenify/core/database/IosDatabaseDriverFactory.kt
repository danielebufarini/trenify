package it.danielebufarini.trenify.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import it.danielebufarini.trenify.database.TrenifyDatabase

class IosDatabaseDriverFactory {
    fun create(): SqlDriver = NativeSqliteDriver(
        schema = TrenifyDatabase.Schema,
        name = "trenify.db",
    )
}
