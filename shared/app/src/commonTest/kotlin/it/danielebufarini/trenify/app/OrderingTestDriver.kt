package it.danielebufarini.trenify.app

import app.cash.sqldelight.db.SqlDriver

/**
 * T7.11 corrective pass 2: end-to-end refresh-ordering tests drive the
 * production TrainRepository.refreshTrain → MonitoringCoordinator →
 * MonitoringRepository path against one real shared database, so the app
 * module needs its own test driver (the data module's test driver is not
 * visible across modules).
 */
expect fun createOrderingTestDriver(): SqlDriver
