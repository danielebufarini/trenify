package it.danielebufarini.trenify.data

import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.EvaluationCommit
import it.danielebufarini.trenify.core.domain.MonitoredTrainSnapshot
import it.danielebufarini.trenify.core.domain.TrainMonitorEvent
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testRun
import it.danielebufarini.trenify.core.testing.testRunId
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

/**
 * Persisted monitor refresh health (T7.14 corrective, both drivers):
 * a failed refresh records typed presentation-only metadata on the
 * retained snapshot, survives repository recreation, clears on the next
 * clean accepted commit, and never touches ended monitors.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitorRefreshHealthTest {
    private fun snapshot(clock: MutableClock) =
        MonitoredTrainSnapshot(testRun, DataFreshness.Fresh(clock.now(), null), clock.now())

    @Test fun refreshFailurePersistsAcrossRecreationAndClearsOnCleanCommit() = runTest {
        val driver = createRepositoryTestDriver()
        try {
            val database = TrenifyDatabase(driver)
            val clock = MutableClock()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val first = SqlDelightMonitoringRepository(database, clock, dispatcher)
            val monitor = first.createMonitor(testRunId, expiresAt = clock.now() + 60.minutes)
            assertIs<EvaluationCommit.Committed>(
                first.persistEvaluation(monitor.id, snapshot(clock), emptyList(), monitor.snapshotVersion, 1L),
            )
            val version = first.observeMonitor(testRunId).first()!!.snapshotVersion

            first.recordRefreshFailure(monitor.id, DomainFailure.OFFLINE, clock.now())
            val degraded = first.observeMonitor(testRunId).first()!!
            assertEquals(DomainFailure.OFFLINE, degraded.refreshFailure)
            // The accepted observation is untouched: same snapshot and version.
            assertEquals(testRun, degraded.lastSnapshot?.train)
            assertEquals(version, degraded.snapshotVersion)

            // Recreation/restart keeps the degraded truth: no fresh fetch happened.
            val second = SqlDelightMonitoringRepository(database, clock, dispatcher)
            val reopened = second.observeMonitor(testRunId).first()!!
            assertEquals(DomainFailure.OFFLINE, reopened.refreshFailure)
            assertEquals(testRun, reopened.lastSnapshot?.train)

            // A later clean accepted refresh clears the warning and updates normally.
            clock.instant += 1.minutes
            val recovered = testRun.copy(summary = testRun.summary.copy(delayMinutes = 12))
            assertIs<EvaluationCommit.Committed>(
                second.persistEvaluation(
                    monitor.id,
                    MonitoredTrainSnapshot(recovered, DataFreshness.Fresh(clock.now(), null), clock.now()),
                    emptyList(),
                    reopened.snapshotVersion,
                    2L,
                ),
            )
            val fresh = second.observeMonitor(testRunId).first()!!
            assertNull(fresh.refreshFailure)
            assertNull(fresh.refreshFailedAt)
            assertEquals(12, fresh.lastSnapshot?.train?.summary?.delayMinutes)
        } finally {
            driver.close()
        }
    }

    @Test fun refreshFailureIsNoOpForEndedMonitors() = runTest {
        val driver = createRepositoryTestDriver()
        try {
            val database = TrenifyDatabase(driver)
            val clock = MutableClock()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repository = SqlDelightMonitoringRepository(database, clock, dispatcher)
            val monitor = repository.createMonitor(testRunId, expiresAt = clock.now() + 60.minutes)
            val arrived = testRun.copy(summary = testRun.summary.copy(status = TrainStatus.ARRIVED))
            repository.completeTerminally(
                monitor.id,
                MonitoredTrainSnapshot(arrived, DataFreshness.Fresh(clock.now(), null), clock.now()),
                listOf(TrainMonitorEvent.Arrived(testRunId)),
                clock.now(),
                1L,
            )
            // Ended monitors are read-only: no refresh-health write is possible.
            repository.recordRefreshFailure(monitor.id, DomainFailure.OFFLINE, clock.now())
            val ended = repository.observeMonitor(testRunId).first()!!
            assertNull(ended.refreshFailure)
            assertNull(ended.refreshFailedAt)
        } finally {
            driver.close()
        }
    }
}
