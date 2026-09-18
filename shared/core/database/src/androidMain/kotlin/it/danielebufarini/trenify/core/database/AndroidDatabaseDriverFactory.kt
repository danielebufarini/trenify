package it.danielebufarini.trenify.core.database

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import it.danielebufarini.trenify.database.TrenifyDatabase

class AndroidDatabaseDriverFactory(
    private val context: Context,
) {
    fun create(): SqlDriver = AndroidSqliteDriver(
        schema = TrenifyDatabase.Schema,
        context = context,
        name = "trenify.db",
    )
}
