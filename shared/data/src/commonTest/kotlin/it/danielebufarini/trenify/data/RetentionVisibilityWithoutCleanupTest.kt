package it.danielebufarini.trenify.data

import app.cash.sqldelight.db.SqlDriver
import it.danielebufarini.trenify.core.domain.MonitorThresholds
import it.danielebufarini.trenify.core.domain.MonitoredTrainSnapshot
import it.danielebufarini.trenify.core.domain.TrainMonitor
import it.danielebufarini.trenify.core.domain.TrainMonitorEvent
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testRun
import it.danielebufarini.trenify.core.testing.testRunId
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * T7.11 corrective pass 2: post-terminal visibility is a logical time
 * policy. The same continuous [SqlDelightMonitoringRepository.observeEndedMonitors]
 * subscription hides an item once `now >= endedAt + 24h` even when physical
 * cleanup permanently fails and no unrelated DB write ever arrives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetentionVisibilityWithoutCleanupTest {
    private class PermanentlyFailingCleanupDriver(delegate: SqlDriver) : HookableTestDriver(delegate) {
        @Volatile
        var failCleanup = true

        override fun beforeExecute(sql: String) {
            // The three retention-cleanup statements all target ended rows
            // past the deadline; every other statement stays healthy.
            if (failCleanup && sql.contains("ended_at_epoch_ms <=")) {
                throw IllegalStateException("injected permanent retention-cleanup failure: $sql")
            }
        }
    }

    private fun arrived() = testRun.copy(summary = testRun.summary.copy(status = TrainStatus.ARRIVED))

    private suspend fun end(
        repository: SqlDelightMonitoringRepository,
        clock: MutableClock,
        number: String,
    ): TrainMonitor {
        val id = testRunId.copy(number = TrainNumber(number))
        val monitor = repository.createMonitor(id, MonitorThresholds(), expiresAt = null)
        val endedAt = clock.now()
        repository.completeTerminally(
            monitor.id,
            MonitoredTrainSnapshot(arrived().copy(summary = arrived().summary.copy(id = id)), DataFreshness.Unknown, endedAt),
            listOf(TrainMonitorEvent.Arrived(id)),
            endedAt, 2L)
        return assertNotNull(repository.observeMonitor(id).first())
    }

    @Test fun expiredItemDisappearsFromLiveSubscriptionWhileCleanupKeepsFailing() = runTest {
        val raw = createRepositoryTestDriver()
        val failing = PermanentlyFailingCleanupDriver(raw)
        val database = TrenifyDatabase(failing)
        val clock = MutableClock()
        val repository = SqlDelightMonitoringRepository(database, clock, StandardTestDispatcher(testScheduler))
        try {
            val first = end(repository, clock, "123")
            clock.instant += 1.hours
            val second = end(repository, clock, "456")
            val firstDeadline = assertNotNull(first.endedAt) + 24.hours
            val secondDeadline = assertNotNull(second.endedAt) + 24.hours

            // One continuous subscription for the whole test.
            val seen = mutableListOf<List<TrainMonitor>>()
            val observation = launch { repository.observeEndedMonitors().collect { seen += it } }
            try {
                runCurrent()
                assertEquals(2, seen.last().size)

                // Just before the first deadline both remain visible.
                clock.instant = firstDeadline - 1.minutes
                advanceTimeBy(1.minutes)
                runCurrent()
                assertEquals(2, seen.last().size)

                // The first deadline passes with cleanup permanently
                // failing and no unrelated write: the same observer hides
                // exactly the expired item.
                clock.instant = firstDeadline
                val cleanupAttempt = runCatching { repository.cleanupExpiredEnded(clock.now()) }
                assertTrue(cleanupAttempt.isFailure, "cleanup must really be attempted and fail here")
                advanceTimeBy(24.hours)
                runCurrent()
                assertTrue(seen.last().map { it.id } == listOf(second.id))
                // The row still physically exists: visibility changed
                // without deletion.
                assertNotNull(
                    repository.observeMonitor(first.trainRunId).first(),
                    "expired row must still exist while cleanup fails",
                )

                // The second deadline hides the remaining item the same way.
                clock.instant = secondDeadline
                advanceTimeBy(2.hours)
                runCurrent()
                assertTrue(seen.last().isEmpty())
                assertNotNull(repository.observeMonitor(second.trainRunId).first())

                // Cleanup succeeds later: physical removal is idempotent
                // and the observer keeps showing the items absent.
                failing.failCleanup = false
                repository.cleanupExpiredEnded(clock.now())
                repository.cleanupExpiredEnded(clock.now())
                runCurrent()
                assertNull(repository.observeMonitor(first.trainRunId).first())
                assertNull(repository.observeMonitor(second.trainRunId).first())
                assertTrue(seen.last().isEmpty())
            } finally {
                observation.cancel()
            }
        } finally {
            raw.close()
        }
    }
}
