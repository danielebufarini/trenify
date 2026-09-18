package it.danielebufarini.trenify.app

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import it.danielebufarini.trenify.database.TrenifyDatabase

actual fun createOrderingTestDriver(): SqlDriver =
    JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also(TrenifyDatabase.Schema::create)
