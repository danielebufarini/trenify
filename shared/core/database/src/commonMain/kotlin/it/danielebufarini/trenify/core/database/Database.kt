package it.danielebufarini.trenify.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import it.danielebufarini.trenify.database.App_metadata
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

fun createDatabase(driver: SqlDriver): TrenifyDatabase = TrenifyDatabase(driver)

fun TrenifyDatabase.observeMetadata(): Flow<List<App_metadata>> =
    appMetadataQueries.selectAll().asFlow().mapToList(Dispatchers.Default)
