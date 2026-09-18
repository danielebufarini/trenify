package it.danielebufarini.trenify.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import it.danielebufarini.trenify.database.TrenifyDatabase

actual fun createRepositoryTestDriver(): SqlDriver =
    JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also(TrenifyDatabase.Schema::create)

actual fun createUnmanagedRepositoryTestDriver(): SqlDriver =
    JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

