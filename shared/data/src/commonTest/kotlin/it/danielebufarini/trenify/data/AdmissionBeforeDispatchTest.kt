package it.danielebufarini.trenify.data

import app.cash.sqldelight.db.SqlDriver
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.PersonalDataWriteSupersededException
import it.danielebufarini.trenify.core.model.JourneySearchRequest
import it.danielebufarini.trenify.core.model.Station
import it.danielebufarini.trenify.core.testing.FakeRealtimeProvider
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.journeyDestination
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * T7.8 corrective pass: the write's delete-order identity must be captured
 * at public repository-invocation time, before the first dispatcher hop.
 *
 * Each test deliberately separates the two moments the buggy implementation
 * conflated:
 *
 * 1. the repository invocation begins (the async block runs on the test
 *    scheduler and the dispatched persistence work is parked in
 *    [ManualTestDispatcher] — provably started, provably not yet admitted
 *    to the delete-wins critical section);
 * 2. `clearSearchHistoryAndRecency()` / `clearAllFavorites()` then executes
 *    and completes on a real worker thread;
 * 3. the parked write is finally released and must fail as superseded
 *    instead of recreating the deleted data.
 *
 * No sleeps, no thread-timing assumptions: every ordering step is enforced
 * by dispatcher queues.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdmissionBeforeDispatchTest {
    private data class TwoRepos(
        val writer: RealtimeRepositories,
        val deleter: RealtimeRepositories,
        val provider: FakeRealtimeProvider,
        val clock: MutableClock,
        val database: TrenifyDatabase,
        val manual: ManualTestDispatcher,
    )

    private fun kotlinx.coroutines.test.TestScope.twoRepos(
        driver: SqlDriver,
        historyGate: DeleteWinsGate,
        favoritesGate: DeleteWinsGate,
    ): TwoRepos {
        val database = TrenifyDatabase(driver)
        val clock = MutableClock()
        val provider = FakeRealtimeProvider(clock)
        val manual = ManualTestDispatcher()
        // Writer and deleter share one database and one gate per domain:
        // ordering is a property of the persisted data, and the writer's
        // manual dispatcher lets the test park its dispatched work.
        val writer = RealtimeRepositories(
            database, listOf(provider), backgroundScope, clock = clock,
            dispatcher = manual,
            historyGate = historyGate,
            favoritesGate = favoritesGate,
        )
        val deleter = RealtimeRepositories(
            database, listOf(provider), backgroundScope, clock = clock,
            dispatcher = Dispatchers.Default,
            historyGate = historyGate,
            favoritesGate = favoritesGate,
        )
        return TwoRepos(writer, deleter, provider, clock, database, manual)
    }

    @Test fun journeyAdmissionBeforeDispatchIsDroppedByCompletedDelete() = runTest {
        val driver = createRepositoryTestDriver()
        try {
            val repos = twoRepos(driver, DeleteWinsGate(), DeleteWinsGate())
            val station = setupStation(repos)
            val request = JourneySearchRequest(station, journeyDestination, repos.clock.now())

            // Invocation begins on the test scheduler; the dispatched
            // persistence work parks in the manual queue: started, not admitted.
            val write = async { runCatching { repos.writer.recordSearch(request) } }
            runCurrent()
            assertFalse(write.isCompleted)
            assertEquals(1, repos.manual.pending())

            // The deletion executes and completes while the write is parked.
            repos.deleter.clearSearchHistoryAndRecency()

            // Releasing the parked write must fail it as superseded.
            repos.manual.runAll()
            assertIs<PersonalDataWriteSupersededException>(write.await().exceptionOrNull())

            assertTrue(repos.database.realtimeQueries.journeySearchHistory().executeAsList().isEmpty())
            assertTrue(repos.deleter.observeSearchHistory().first().isEmpty())

            // A new explicit search after the deletion works normally.
            val after = repos.deleter.recordSearch(request)
            assertEquals(listOf(after), repos.deleter.observeSearchHistory().first())
        } finally {
            driver.close()
        }
    }

    @Test fun recencyAdmissionBeforeDispatchIsDroppedByCompletedDelete() = runTest {
        val driver = createRepositoryTestDriver()
        try {
            val repos = twoRepos(driver, DeleteWinsGate(), DeleteWinsGate())
            val station = setupStation(repos)

            val write = async { runCatching { repos.writer.record(station) } }
            runCurrent()
            assertFalse(write.isCompleted)
            assertEquals(1, repos.manual.pending())

            repos.deleter.clearSearchHistoryAndRecency()

            repos.manual.runAll()
            assertIs<PersonalDataWriteSupersededException>(write.await().exceptionOrNull())

            assertTrue(repos.database.realtimeQueries.recentStations().executeAsList().isEmpty())
            assertTrue(repos.deleter.observeRecentStations().first().isEmpty())

            repos.deleter.record(station)
            assertEquals(listOf(station), repos.deleter.observeRecentStations().first())
        } finally {
            driver.close()
        }
    }

    @Test fun favoriteSaveAdmissionBeforeDispatchIsDroppedByCompletedDelete() = runTest {
        val driver = createRepositoryTestDriver()
        try {
            val repos = twoRepos(driver, DeleteWinsGate(), DeleteWinsGate())
            val station = setupStation(repos)

            val write = async { runCatching { repos.writer.setFavorite(station, true) } }
            runCurrent()
            assertFalse(write.isCompleted)
            assertEquals(1, repos.manual.pending())

            repos.deleter.clearAllFavorites()

            repos.manual.runAll()
            assertIs<PersonalDataWriteSupersededException>(write.await().exceptionOrNull())

            assertTrue(repos.database.realtimeQueries.favoriteStations().executeAsList().isEmpty())
            assertTrue(repos.deleter.observeFavoriteStations().first().isEmpty())

            repos.deleter.setFavorite(station, true)
            assertEquals(listOf(station), repos.deleter.observeFavoriteStations().first())
        } finally {
            driver.close()
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.setupStation(repos: TwoRepos): Station {
        // Setup runs through the deleter (real worker thread): the writer's
        // manual queue stays empty until the staged invocation begins.
        val result = repos.deleter.searchStations("roma")
        assertEquals(0, repos.manual.pending())
        return (result as DataResult.Data<List<Station>>).value.single()
    }
}
