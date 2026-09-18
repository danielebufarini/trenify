package it.danielebufarini.trenify.data

import app.cash.sqldelight.db.SqlDriver
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.MonitorThresholds
import it.danielebufarini.trenify.core.domain.NotificationSettings
import it.danielebufarini.trenify.core.domain.PersonalDataWriteSupersededException
import it.danielebufarini.trenify.core.model.FavoriteRoute
import it.danielebufarini.trenify.core.model.FavoriteTrain
import it.danielebufarini.trenify.core.model.JourneySearchHistoryEntry
import it.danielebufarini.trenify.core.model.JourneySearchMode
import it.danielebufarini.trenify.core.model.JourneySearchRequest
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.SearchHistoryEntry
import it.danielebufarini.trenify.core.model.Station
import it.danielebufarini.trenify.core.model.StationId
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.model.TrainSearchHistoryEntry
import it.danielebufarini.trenify.core.testing.FakeRealtimeProvider
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.journeyDestination
import it.danielebufarini.trenify.core.testing.testRunId
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T7.8 Settings deletion actions on the real persistence path.
 *
 * Proves, against a real SQLDelight driver on both test targets, that each
 * confirmed deletion is one atomic transaction with rollback, preserves every
 * unrelated table, propagates reactively, survives restart, keeps working for
 * new post-deletion actions, and orders deterministically against in-flight
 * writes (a write that began before a completed deletion can never resurrect
 * deleted data).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PersonalDataDeletionTest {
    private suspend fun TestScope.withRepository(
        dispatcher: CoroutineDispatcher? = null,
        driver: SqlDriver = createRepositoryTestDriver(),
        block: suspend TestScope.(RealtimeRepositories, FakeRealtimeProvider, MutableClock, TrenifyDatabase) -> Unit,
    ) {
        val database = TrenifyDatabase(driver)
        val clock = MutableClock()
        val provider = FakeRealtimeProvider(clock)
        val repository = RealtimeRepositories(
            database,
            listOf(provider),
            backgroundScope,
            clock = clock,
            dispatcher = dispatcher ?: StandardTestDispatcher(testScheduler),
        )
        try {
            block(repository, provider, clock, database)
        } finally {
            driver.close()
        }
    }

    private data class PersonalData(
        val station: Station,
        val journey: JourneySearchHistoryEntry,
        val train: TrainSearchHistoryEntry,
        val route: FavoriteRoute,
        val favoriteTrain: FavoriteTrain,
    )

    private suspend fun populate(repository: RealtimeRepositories, clock: MutableClock, database: TrenifyDatabase): PersonalData {
        val station = assertIs<DataResult.Data<List<Station>>>(
            repository.searchStations("roma"),
        ).value.single()
        repository.record(station)
        val journey = repository.recordSearch(JourneySearchRequest(station, journeyDestination, clock.now()))
        val train = repository.recordTrainSearch(TrainNumber("123"), LocalDate.parse("2026-09-06"), expected = null)
        repository.setFavorite(station, true)
        val route = FavoriteRoute.create(station, journeyDestination)
        repository.setFavorite(route, true)
        val favoriteTrain = FavoriteTrain.create(
            TrainNumber("123"),
            originId = station.id,
            operator = Operator("Trenitalia"),
            originName = station.name,
            destinationName = "Milano Centrale",
        )
        repository.setFavorite(favoriteTrain, true)
        val monitors = SqlDelightMonitoringRepository(database, clock, Dispatchers.Default)
        val monitor = monitors.createMonitor(testRunId, MonitorThresholds(), expiresAt = null)
        database.realtimeQueries.putMonitorState(
            monitor.id.value, "{}", "FRESH", clock.now().toEpochMilliseconds(), null, null,
            clock.now().toEpochMilliseconds(), null, null, 0L, 0L,
        )
        database.realtimeQueries.putMonitorEvent("tev-1", monitor.id.value, "DELAY", "{}", clock.now().toEpochMilliseconds())
        val settings = SqlDelightNotificationSettingsRepository(database, Dispatchers.Default)
        settings.setNotificationsEnabled(false)
        settings.setDefaultThresholds(MonitorThresholds(delayMinutes = 30, notifyArrival = false))
        database.realtimeQueries.setStrikeNotificationsEnabled(1)
        database.realtimeQueries.putStrikeNotificationEvent(
            "sev-1", "strike-1", "STRIKE_NEW", clock.now().toEpochMilliseconds(),
        )
        database.realtimeQueries.putStrike(
            "strike-1", "test", "ext-1", 100L, 200L, "ACTIVE", "NATIONAL", "RELEVANT",
            "Lombardia", "", "", "Trenitalia", null, "SCIOPERO", null,
            "RFI", "https://example.invalid", null, clock.now().toEpochMilliseconds(), "fp-1",
        )
        database.realtimeQueries.putCache("board-test", "{}", clock.now().toEpochMilliseconds(), null)
        repository.refreshTrain(testRunId)
        return PersonalData(station, journey, train, route, favoriteTrain)
    }

        private suspend fun assertUnrelatedSurvived(
        repository: RealtimeRepositories,
        provider: FakeRealtimeProvider,
        clock: MutableClock,
        database: TrenifyDatabase,
        data: PersonalData,
    ) {
        // Canonical stations, identities and provider mappings survive.
        assertEquals(
            data.station.id.value,
            database.realtimeQueries.stationById(data.station.id.value).executeAsOne().id,
        )
        assertEquals(
            data.station.id.value,
            database.realtimeQueries.stationByExternal("test", "opaque-origin").executeAsOne().id,
        )
        assertEquals(
            journeyDestination.id.value,
            database.realtimeQueries.stationById(journeyDestination.id.value).executeAsOne().id,
        )
        // Monitors, snapshots and dedup/event-claim state survive.
        assertEquals(
            1,
            database.realtimeQueries.activeMonitors(clock.now().toEpochMilliseconds()).executeAsList().size,
        )
        val monitorId = database.realtimeQueries.activeMonitors(clock.now().toEpochMilliseconds()).executeAsList().single().id
        assertNotNull(database.realtimeQueries.monitorStateById(monitorId).executeAsOneOrNull())
        assertNotNull(database.realtimeQueries.eventByKey("tev-1").executeAsOneOrNull())
        // Settings/preferences and strike preferences survive.
        val settings = SqlDelightNotificationSettingsRepository(database, Dispatchers.Default)
        assertEquals(
            NotificationSettings(false, MonitorThresholds(delayMinutes = 30, notifyArrival = false)),
            settings.observe().first(),
        )
        assertEquals(1L, database.realtimeQueries.strikeNotificationsEnabled().executeAsOne())
        assertNotNull(database.realtimeQueries.strikeById("strike-1").executeAsOneOrNull())
        assertNotNull(database.realtimeQueries.strikeNotificationEventByKey("sev-1").executeAsOneOrNull())
        // Unrelated caches survive.
        assertNotNull(database.realtimeQueries.cacheByKey("board-test").executeAsOneOrNull())
        assertNotNull(database.realtimeQueries.trainById(testRunId.key).executeAsOneOrNull())
        // No remote interaction happened around the deletion.
        assertEquals(1, provider.stationCalls)
        assertEquals(1, provider.detailCalls)
        assertEquals(0, provider.boardCalls)
    }

    @Test fun historyDeletionClearsJourneysTrainsAndRecencyAsOneAtomicAction() = runTest {
        withRepository { repository, provider, clock, database ->
            val data = populate(repository, clock, database)
            val searchStates = mutableListOf<List<SearchHistoryEntry>>()
            val recencyStates = mutableListOf<List<Station>>()
            val searchObservation = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.observeSearchHistory().collect { searchStates += it }
            }
            val recencyObservation = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.observeRecentStations().collect { recencyStates += it }
            }
            runCurrent()

            repository.clearSearchHistoryAndRecency()
            runCurrent()

            // All three user-visible search kinds are gone from flows and tables.
            assertTrue(repository.observeSearchHistory().first().isEmpty())
            assertTrue(repository.observeRecentStations().first().isEmpty())
            assertTrue(database.realtimeQueries.journeySearchHistory().executeAsList().isEmpty())
            assertTrue(database.realtimeQueries.trainSearchHistory().executeAsList().isEmpty())
            assertTrue(database.realtimeQueries.recentStations().executeAsList().isEmpty())
            assertTrue(searchStates.isNotEmpty() && searchStates.last().isEmpty())
            assertTrue(recencyStates.isNotEmpty() && recencyStates.last().isEmpty())
            // Every favorite kind survives.
            assertEquals(listOf(data.station), repository.observeFavoriteStations().first())
            assertEquals(listOf(data.route), repository.observeFavoriteRoutes().first())
            assertEquals(listOf(data.favoriteTrain), repository.observeFavoriteTrains().first())
            assertUnrelatedSurvived(repository, provider, clock, database, data)
            searchObservation.cancel()
            recencyObservation.cancel()
        }
    }

    @Test fun historyDeletionRollsBackWhenTrainClearFails() = runTest {
        val failing = FailingTestDriver(createRepositoryTestDriver()) { sql ->
            "DELETE FROM train_search_history" in sql
        }
        withRepository(driver = failing) { repository, _, clock, database ->
            val data = populate(repository, clock, database)

            // The second statement of the one transaction fails: nothing may
            // be partially committed.
            assertFailsWith<IllegalStateException> { repository.clearSearchHistoryAndRecency() }
            assertEquals(
                setOf(data.journey.id, data.train.id),
                repository.observeSearchHistory().first().map { it.id }.toSet(),
            )
            assertEquals(listOf(data.station), repository.observeRecentStations().first())
            assertEquals(1, database.realtimeQueries.journeySearchHistory().executeAsList().size)
            assertEquals(1, database.realtimeQueries.trainSearchHistory().executeAsList().size)
            assertEquals(1, database.realtimeQueries.recentStations().executeAsList().size)

            // The fault is single-shot: retrying completes the same deletion
            // exactly once with no duplicate or partial effects.
            repository.clearSearchHistoryAndRecency()
            assertTrue(repository.observeSearchHistory().first().isEmpty())
            assertTrue(repository.observeRecentStations().first().isEmpty())
            assertEquals(listOf(data.station), repository.observeFavoriteStations().first())
        }
    }

    @Test fun historyDeletionSurvivesRestartAndNewActionsWork() = runTest {
        withRepository { repository, provider, clock, database ->
            val data = populate(repository, clock, database)
            repository.clearSearchHistoryAndRecency()

            // A restarted repository re-queries the database: deleted rows
            // stay absent without any new write recreating them.
            val restarted = RealtimeRepositories(
                database,
                listOf(provider),
                backgroundScope,
                clock = clock,
                dispatcher = StandardTestDispatcher(testScheduler),
            )
            assertTrue(restarted.observeSearchHistory().first().isEmpty())
            assertTrue(restarted.observeRecentStations().first().isEmpty())

            // New explicit user actions after the deletion work normally.
            val journey = restarted.recordSearch(JourneySearchRequest(data.station, journeyDestination, clock.now()))
            val train = restarted.recordTrainSearch(TrainNumber("456"), LocalDate.parse("2026-09-07"), expected = null)
            restarted.record(data.station)
            assertEquals(setOf(journey.id, train.id), restarted.observeSearchHistory().first().map { it.id }.toSet())
            assertEquals(listOf(data.station), restarted.observeRecentStations().first())
        }
    }

    @Test fun favoritesDeletionClearsAllThreeKindsAsOneAtomicAction() = runTest {
        withRepository { repository, provider, clock, database ->
            val data = populate(repository, clock, database)
            val stationStates = mutableListOf<List<Station>>()
            val observation = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.observeFavoriteStations().collect { stationStates += it }
            }
            runCurrent()

            repository.clearAllFavorites()
            runCurrent()

            // All three favorite kinds are gone from flows and tables.
            assertTrue(repository.observeFavoriteStations().first().isEmpty())
            assertTrue(repository.observeFavoriteRoutes().first().isEmpty())
            assertTrue(repository.observeFavoriteTrains().first().isEmpty())
            assertTrue(database.realtimeQueries.favoriteStations().executeAsList().isEmpty())
            assertTrue(database.realtimeQueries.favoriteRoutes().executeAsList().isEmpty())
            assertTrue(database.realtimeQueries.favoriteTrains().executeAsList().isEmpty())
            assertTrue(stationStates.isNotEmpty() && stationStates.last().isEmpty())
            // Both history kinds and recency survive.
            assertEquals(
                setOf(data.journey.id, data.train.id),
                repository.observeSearchHistory().first().map { it.id }.toSet(),
            )
            assertEquals(listOf(data.station), repository.observeRecentStations().first())
            assertUnrelatedSurvived(repository, provider, clock, database, data)
            observation.cancel()
        }
    }

    @Test fun favoritesDeletionRollsBackWhenRouteClearFails() = runTest {
        val failing = FailingTestDriver(createRepositoryTestDriver()) { sql ->
            "DELETE FROM favorite_route" in sql
        }
        withRepository(driver = failing) { repository, _, clock, database ->
            val data = populate(repository, clock, database)

            // The second statement of the one transaction fails: every
            // favorite kind must still be intact.
            assertFailsWith<IllegalStateException> { repository.clearAllFavorites() }
            assertEquals(listOf(data.station), repository.observeFavoriteStations().first())
            assertEquals(listOf(data.route), repository.observeFavoriteRoutes().first())
            assertEquals(listOf(data.favoriteTrain), repository.observeFavoriteTrains().first())
            assertEquals(1, database.realtimeQueries.favoriteStations().executeAsList().size)
            assertEquals(1, database.realtimeQueries.favoriteRoutes().executeAsList().size)
            assertEquals(1, database.realtimeQueries.favoriteTrains().executeAsList().size)

            // The fault is single-shot: retrying completes the same deletion
            // exactly once with no duplicate or partial effects.
            repository.clearAllFavorites()
            assertTrue(repository.observeFavoriteStations().first().isEmpty())
            assertTrue(repository.observeFavoriteRoutes().first().isEmpty())
            assertTrue(repository.observeFavoriteTrains().first().isEmpty())
            assertEquals(
                setOf(data.journey.id, data.train.id),
                repository.observeSearchHistory().first().map { it.id }.toSet(),
            )
        }
    }

    @Test fun favoritesDeletionSurvivesRestartAndNewActionsWork() = runTest {
        withRepository { repository, provider, clock, database ->
            val data = populate(repository, clock, database)
            repository.clearAllFavorites()

            val restarted = RealtimeRepositories(
                database,
                listOf(provider),
                backgroundScope,
                clock = clock,
                dispatcher = StandardTestDispatcher(testScheduler),
            )
            assertTrue(restarted.observeFavoriteStations().first().isEmpty())
            assertTrue(restarted.observeFavoriteRoutes().first().isEmpty())
            assertTrue(restarted.observeFavoriteTrains().first().isEmpty())

            // New explicit favorite saves after the deletion work normally.
            restarted.setFavorite(data.station, true)
            restarted.setFavorite(data.route, true)
            restarted.setFavorite(data.favoriteTrain, true)
            assertEquals(listOf(data.station), restarted.observeFavoriteStations().first())
            assertEquals(listOf(data.route), restarted.observeFavoriteRoutes().first())
            assertEquals(listOf(data.favoriteTrain), restarted.observeFavoriteTrains().first())
        }
    }

    private suspend fun GatingTestDriver.awaitEntered() {
        withTimeout(30_000) { while (!entered) delay(5) }
    }

    private suspend fun TestScope.withRealThreads(
        gate: GatingTestDriver,
        block: suspend TestScope.(RealtimeRepositories, FakeRealtimeProvider, MutableClock, TrenifyDatabase) -> Unit,
    ) {
        // Real threads need real time: inside runTest, withTimeout and delay
        // would otherwise follow the virtual clock and expire before the
        // worker threads are even scheduled.
        withContext(Dispatchers.Default) {
            withRepository(dispatcher = Dispatchers.Default, driver = gate, block = block)
        }
    }

    @Test fun inFlightJourneyWriteIsRemovedByCompletedDeletion() = runTest {
        val gate = GatingTestDriver(createRepositoryTestDriver())
        withRealThreads(gate) { repository, _, clock, database ->
            val station = assertIs<DataResult.Data<List<Station>>>(
                repository.searchStations("roma"),
            ).value.single()
            val request = JourneySearchRequest(station, journeyDestination, clock.now())
            gate.blockWhen = { sql -> "INSERT INTO journey_search_history" in sql }

            // The write is parked inside its own transaction, holding the
            // history mutex; only then does the deletion start.
            val write = async(Dispatchers.Default) { repository.recordSearch(request) }
            gate.awaitEntered()
            val delete = async(Dispatchers.Default) { repository.clearSearchHistoryAndRecency() }
            // Deterministic, not timing-based: the parked write cannot have
            // finished while the gate is closed, and the deletion cannot pass
            // a mutex held by the parked write.
            assertFalse(write.isCompleted)
            assertFalse(delete.isCompleted)

            gate.open()
            withTimeout(30_000) { write.await() }
            withTimeout(30_000) { delete.await() }

            // The write committed first, then the deletion removed its row:
            // reopening or observing afterwards never restores it.
            assertTrue(database.realtimeQueries.journeySearchHistory().executeAsList().isEmpty())
            assertTrue(repository.observeSearchHistory().first().isEmpty())
        }
    }

    @Test fun inFlightRecencyWriteIsRemovedByCompletedDeletion() = runTest {
        val gate = GatingTestDriver(createRepositoryTestDriver())
        withRealThreads(gate) { repository, _, _, database ->
            val station = assertIs<DataResult.Data<List<Station>>>(
                repository.searchStations("roma"),
            ).value.single()
            gate.blockWhen = { sql -> "INTO recent_station" in sql }

            val write = async(Dispatchers.Default) { repository.record(station) }
            gate.awaitEntered()
            val delete = async(Dispatchers.Default) { repository.clearSearchHistoryAndRecency() }
            assertFalse(write.isCompleted)
            assertFalse(delete.isCompleted)

            gate.open()
            withTimeout(30_000) { write.await() }
            withTimeout(30_000) { delete.await() }

            assertTrue(database.realtimeQueries.recentStations().executeAsList().isEmpty())
            assertTrue(repository.observeRecentStations().first().isEmpty())
        }
    }

    @Test fun inFlightFavoriteSaveIsRemovedByCompletedDeletion() = runTest {
        val gate = GatingTestDriver(createRepositoryTestDriver())
        withRealThreads(gate) { repository, _, _, database ->
            val station = assertIs<DataResult.Data<List<Station>>>(
                repository.searchStations("roma"),
            ).value.single()
            gate.blockWhen = { sql -> "INTO favorite_station" in sql }

            val write = async(Dispatchers.Default) { repository.setFavorite(station, true) }
            gate.awaitEntered()
            val delete = async(Dispatchers.Default) { repository.clearAllFavorites() }
            assertFalse(write.isCompleted)
            assertFalse(delete.isCompleted)

            gate.open()
            withTimeout(30_000) { write.await() }
            withTimeout(30_000) { delete.await() }

            assertTrue(database.realtimeQueries.favoriteStations().executeAsList().isEmpty())
            assertTrue(repository.observeFavoriteStations().first().isEmpty())
        }
    }

    @Test fun writeStartedBeforeCompletedHistoryDeleteCannotCommitAfterwards() = runTest {
        // Two repository instances share one database and one history gate:
        // the deletion runs parked on a real worker thread (its driver-level
        // park would deadlock the test scheduler) while the write runs on
        // the test dispatcher, where draining it proves it started.
        val gate = GatingTestDriver(createRepositoryTestDriver())
        val database = TrenifyDatabase(gate)
        try {
            val clock = MutableClock()
            val provider = FakeRealtimeProvider(clock)
            val sharedHistory = DeleteWinsGate()
            val writer = RealtimeRepositories(
                database, listOf(provider), backgroundScope, clock = clock,
                dispatcher = StandardTestDispatcher(testScheduler), historyGate = sharedHistory,
            )
            val deleter = RealtimeRepositories(
                database, listOf(provider), backgroundScope, clock = clock,
                dispatcher = Dispatchers.Default, historyGate = sharedHistory,
            )
            val station = assertIs<DataResult.Data<List<Station>>>(
                writer.searchStations("roma"),
            ).value.single()
            val request = JourneySearchRequest(station, journeyDestination, clock.now())
            gate.blockWhen = { sql -> "DELETE FROM journey_search_history" in sql }

            // Park the deletion inside its own transaction, holding the
            // shared mutex. Real-time wait: the virtual clock stays put while
            // the worker thread parks.
            val delete = async(Dispatchers.Default) { deleter.clearSearchHistoryAndRecency() }
            withContext(Dispatchers.Default) { gate.awaitEntered() }

            // Start the write and drain the test scheduler: afterwards the
            // write is provably suspended on the gate mutex — its only
            // pre-commit suspension — so it provably started before the
            // deletion completes, and must be dropped on acquisition.
            // runCatching keeps the expected failure inside the Deferred
            // result instead of letting the test scheduler report it.
            val write = async { runCatching { writer.recordSearch(request) } }
            runCurrent()
            assertFalse(write.isCompleted)

            gate.open()
            withContext(Dispatchers.Default) { withTimeout(30_000) { delete.await() } }
            runCurrent()
            assertIs<PersonalDataWriteSupersededException>(write.await().exceptionOrNull())

            assertTrue(database.realtimeQueries.journeySearchHistory().executeAsList().isEmpty())
            assertTrue(writer.observeSearchHistory().first().isEmpty())

            // A retry issued after the deletion completed is a new explicit
            // user action and proceeds normally.
            val after = writer.recordSearch(request)
            assertEquals(listOf(after), writer.observeSearchHistory().first())
        } finally {
            gate.close()
        }
    }

    @Test fun saveStartedBeforeCompletedFavoritesDeleteCannotCommitAfterwards() = runTest {
        val gate = GatingTestDriver(createRepositoryTestDriver())
        val database = TrenifyDatabase(gate)
        try {
            val clock = MutableClock()
            val provider = FakeRealtimeProvider(clock)
            val sharedFavorites = DeleteWinsGate()
            val writer = RealtimeRepositories(
                database, listOf(provider), backgroundScope, clock = clock,
                dispatcher = StandardTestDispatcher(testScheduler), favoritesGate = sharedFavorites,
            )
            val deleter = RealtimeRepositories(
                database, listOf(provider), backgroundScope, clock = clock,
                dispatcher = Dispatchers.Default, favoritesGate = sharedFavorites,
            )
            val station = assertIs<DataResult.Data<List<Station>>>(
                writer.searchStations("roma"),
            ).value.single()
            gate.blockWhen = { sql -> "DELETE FROM favorite_station" in sql }

            val delete = async(Dispatchers.Default) { deleter.clearAllFavorites() }
            withContext(Dispatchers.Default) { gate.awaitEntered() }

            // Draining proves the save started — suspended on the shared
            // mutex — before the deletion completes. runCatching keeps the
            // expected failure inside the Deferred result.
            val write = async { runCatching { writer.setFavorite(station, true) } }
            runCurrent()
            assertFalse(write.isCompleted)

            gate.open()
            withContext(Dispatchers.Default) { withTimeout(30_000) { delete.await() } }
            runCurrent()
            assertIs<PersonalDataWriteSupersededException>(write.await().exceptionOrNull())

            assertTrue(database.realtimeQueries.favoriteStations().executeAsList().isEmpty())
            assertTrue(writer.observeFavoriteStations().first().isEmpty())

            writer.setFavorite(station, true)
            assertEquals(listOf(station), writer.observeFavoriteStations().first())
        } finally {
            gate.close()
        }
    }
}
