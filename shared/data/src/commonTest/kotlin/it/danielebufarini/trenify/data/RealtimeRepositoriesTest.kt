package it.danielebufarini.trenify.data

import app.cash.sqldelight.db.SqlDriver
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.provider.api.*
import it.danielebufarini.trenify.core.testing.*
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

expect fun createRepositoryTestDriver(): SqlDriver
expect fun createUnmanagedRepositoryTestDriver(): SqlDriver

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeRepositoriesTest {
    private suspend fun TestScope.withRepository(block: suspend TestScope.(RealtimeRepositories, FakeRealtimeProvider, MutableClock, TrenifyDatabase) -> Unit) {
        val driver = createRepositoryTestDriver()
        val database = TrenifyDatabase(driver)
        val clock = MutableClock()
        val provider = FakeRealtimeProvider(clock)
        val repository = RealtimeRepositories(database, listOf(provider), backgroundScope, clock = clock,
            dispatcher = StandardTestDispatcher(testScheduler))
        try { block(repository, provider, clock, database) } finally { driver.close() }
    }

    @Test fun stationCachePreservesInternalIdentityAcrossFetchesAndRestarts() = runTest {
        withRepository { repository, provider, clock, database ->
            val first = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("roma")).value.single()
            assertNotEquals(provider.origin.ref.value, first.id.value)
            assertIs<DataFreshness.Fresh>(assertIs<DataResult.Data<List<Station>>>(repository.searchStations(" ROMA ")).freshness)
            assertEquals(1, provider.stationCalls)
            clock.instant += 1500.minutes
            assertEquals(first.id, assertIs<DataResult.Data<List<Station>>>(repository.searchStations("roma")).value.single().id)
            val restarted = RealtimeRepositories(database, listOf(provider), backgroundScope, clock = clock,
                dispatcher = StandardTestDispatcher(testScheduler))
            assertEquals(first, assertIs<DataResult.Data<List<Station>>>(restarted.searchStations("roma")).value.single())
            assertEquals(first.id.value, database.realtimeQueries.stationByExternal("test", "opaque-origin").executeAsOne().id)
        }
    }

    @Test fun trainAndStopsRoundTripAndRefreshWritesAreReactive() = runTest {
        withRepository { repository, provider, clock, database ->
            val states = mutableListOf<DataResult<TrainRun>>()
            val observation = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.observeTrain(testRunId).collect { states += it }
            }
            val first = assertIs<DataResult.Data<TrainRun>>(repository.refreshTrain(testRunId)).value
            runCurrent()
            assertEquals("1", first.stops.single().scheduledPlatform)
            assertEquals("2", first.stops.single().actualPlatform)
            assertEquals(first, assertIs<DataResult.Data<TrainRun>>(states.last()).value)
            assertEquals(clock.now().toEpochMilliseconds(), database.realtimeQueries.trainById(testRunId.key).executeAsOne().source_timestamp)
            clock.instant += 1.minutes
            provider.detail = provider.detail.copy(stops = listOf(provider.detail.stops.single().copy(actualPlatform = "5"),
                provider.detail.stops.single().copy(station = ProviderStation(ExternalStationRef("second"), "Firenze"))))
            repository.refreshTrain(testRunId)
            runCurrent()
            val updated = assertIs<DataResult.Data<TrainRun>>(states.last()).value
            assertEquals(listOf("5", "2"), updated.stops.map { it.actualPlatform })
            assertEquals(listOf(0L, 1L), database.realtimeQueries.stopsForTrain(testRunId.key).executeAsList().map { it.stop_index })
            val restarted = RealtimeRepositories(database, listOf(provider), backgroundScope, clock = clock,
                dispatcher = StandardTestDispatcher(testScheduler))
            assertEquals(updated, assertIs<DataResult.Data<TrainRun>>(restarted.observeTrain(testRunId).first()).value)
            observation.cancel()
        }
    }

    @Test fun intermediateStopPlatformsRoundTripThroughDomainAndSqlDelight() = runTest {
        withRepository { repository, provider, _, _ ->
            val tirano = ProviderStation(ExternalStationRef("tirano"), "Tirano")
            val monza = ProviderStation(ExternalStationRef("monza"), "Monza")
            val milano = ProviderStation(ExternalStationRef("milano"), "Milano Centrale")
            provider.detail = provider.detail.copy(
                stops = listOf(
                    ProviderTrainStop(tirano, scheduledPlatform = "1", actualPlatform = null),
                    ProviderTrainStop(monza, scheduledPlatform = "5", actualPlatform = "7"),
                    ProviderTrainStop(milano, scheduledPlatform = null, actualPlatform = null),
                ),
            )

            val loaded = assertIs<DataResult.Data<TrainRun>>(repository.refreshTrain(testRunId)).value
            assertEquals(listOf("1", "5", null), loaded.stops.map { it.scheduledPlatform })
            assertEquals(listOf(null, "7", null), loaded.stops.map { it.actualPlatform })
            assertEquals("5", loaded.stops.single { it.station.name == "Monza" }.scheduledPlatform)
            assertNull(loaded.stops.single { it.station.name == "Milano Centrale" }.actualPlatform)

            val reopened = assertIs<DataResult.Data<TrainRun>>(
                repository.observeTrain(testRunId).first(),
            ).value
            assertEquals(loaded.stops, reopened.stops)
        }
    }

    @Test fun freshTrainCacheAvoidsNetworkAndStaleSuccessUpdatesIt() = runTest {
        withRepository { repository, provider, clock, _ ->
            repository.refreshTrain(testRunId)
            repository.refreshTrain(testRunId)
            assertEquals(1, provider.detailCalls)
            clock.instant += 1.minutes
            provider.detail = provider.detail.copy(train = provider.candidate.copy(delayMinutes = 7))
            val refreshed = assertIs<DataResult.Data<TrainRun>>(repository.refreshTrain(testRunId))
            assertEquals(7, refreshed.value.summary.delayMinutes)
            assertIs<DataFreshness.Fresh>(refreshed.freshness)
            assertEquals(2, provider.detailCalls)
        }
    }

    @Test fun staleFailureRetainsContentAndEmitsWarning() = runTest {
        withRepository { repository, provider, clock, _ ->
            val original = assertIs<DataResult.Data<TrainRun>>(repository.refreshTrain(testRunId)).value
            clock.instant += 2.minutes
            provider.failure = ProviderFailure.TRANSPORT
            val failure = assertIs<DataResult.Data<TrainRun>>(repository.refreshTrain(testRunId))
            assertEquals(original, failure.value)
            assertIs<DataFreshness.Stale>(failure.freshness)
            assertEquals(DomainFailure.TEMPORARY, failure.warning)
            val observed = assertIs<DataResult.Data<TrainRun>>(repository.observeTrain(testRunId).first())
            assertEquals(original, observed.value)
            assertEquals(DomainFailure.TEMPORARY, observed.warning)
        }
    }

    @Test fun offlineWithoutCacheIsControlledAndOfflineWithCacheRemainsUseful() = runTest {
        withRepository { repository, provider, clock, database ->
            val offline = RealtimeRepositories(database, listOf(provider), backgroundScope, online = { false }, clock = clock,
                dispatcher = StandardTestDispatcher(testScheduler))
            assertEquals(DataResult.Failure(DomainFailure.OFFLINE), offline.refreshTrain(testRunId))
            assertEquals(DataResult.Failure(DomainFailure.OFFLINE), offline.searchStations("roma"))
            repository.refreshTrain(testRunId)
            clock.instant += 2.minutes
            val result = assertIs<DataResult.Data<TrainRun>>(offline.refreshTrain(testRunId))
            assertEquals(DomainFailure.OFFLINE, result.warning)
            assertEquals(1, provider.detailCalls)
        }
    }

    @Test fun identicalInFlightRequestsShareOneFetchEvenIfOneWaiterCancels() = runTest {
        withRepository { repository, provider, _, _ ->
            val gate = CompletableDeferred<Unit>()
            provider.beforeDetail = { gate.await() }
            val first = async { repository.refreshTrain(testRunId, force = true) }
            val second = async { repository.refreshTrain(testRunId, force = true) }
            runCurrent()
            assertEquals(1, provider.detailCalls)
            first.cancel()
            gate.complete(Unit)
            assertIs<DataResult.Data<TrainRun>>(second.await())
            provider.beforeDetail = {}
            repository.refreshTrain(testRunId, force = true)
            assertEquals(2, provider.detailCalls)
        }
    }

    @Test fun protocolOrWrongIdentityCannotCorruptExistingTrain() = runTest {
        withRepository { repository, provider, _, _ ->
            val original = assertIs<DataResult.Data<TrainRun>>(repository.refreshTrain(testRunId)).value
            provider.failure = ProviderFailure.PROTOCOL
            assertEquals(original, assertIs<DataResult.Data<TrainRun>>(repository.refreshTrain(testRunId, true)).value)
            provider.failure = null
            provider.detail = provider.detail.copy(train = provider.candidate.copy(
                ref = provider.candidate.ref.copy(number = TrainNumber("999"))))
            val result = assertIs<DataResult.Data<TrainRun>>(repository.refreshTrain(testRunId, true))
            assertEquals(original, result.value)
            assertEquals(DomainFailure.INVALID_RESPONSE, result.warning)
        }
    }

    @Test fun departuresAndArrivalsCacheIndependentlyWithoutDetailEnrichment() = runTest {
        withRepository { repository, provider, clock, _ ->
            val station = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("roma")).value.single()
            repository.refreshBoard(station, BoardKind.DEPARTURES)
            repository.refreshBoard(station, BoardKind.ARRIVALS)
            repository.refreshBoard(station, BoardKind.DEPARTURES)
            assertEquals(2, provider.boardCalls)
            assertEquals(0, provider.detailCalls)
            clock.instant += 2.minutes
            provider.failure = ProviderFailure.PROTOCOL
            val result = assertIs<DataResult.Data<StationBoard>>(repository.refreshBoard(station, BoardKind.DEPARTURES))
            assertEquals(1, result.value.trains.size)
            assertIs<DataFreshness.Stale>(result.freshness)
            assertEquals(BoardKind.ARRIVALS, assertIs<DataResult.Data<StationBoard>>(
                repository.observeBoard(station, BoardKind.ARRIVALS).first()).value.kind)
        }
    }

    @Test fun localHistoryAndFavoritesPersistReactivelyAndCanBeRemovedOfflineWithoutDeletingSharedData() = runTest {
        withRepository { repository, provider, clock, database ->
            val station = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("roma")).value.single()
            val favoriteStates = mutableListOf<List<Station>>()
            val observation = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.observeFavoriteStations().collect { favoriteStates += it }
            }
            repository.record(station)
            repository.record(station)
            repository.setFavorite(station, true)
            repository.setFavorite(station, true)
            runCurrent()
            assertEquals(listOf(station), repository.observeRecentStations().first())
            assertEquals(listOf(station), repository.observeFavoriteStations().first())
            assertEquals(listOf(emptyList(), listOf(station)), favoriteStates)
            val cachedBoard = assertIs<DataResult.Data<StationBoard>>(
                repository.refreshBoard(station, BoardKind.DEPARTURES),
            ).value
            repository.clearHistory()
            assertTrue(repository.observeRecentStations().first().isEmpty())
            assertEquals(listOf(station), repository.observeFavoriteStations().first())

            database.realtimeQueries.insertActiveMonitor(
                "monitor", testRunId.key, testRunId.provider.value, testRunId.number.value,
                testRunId.origin.value, testRunId.serviceDate.toString(), 1L, null,
                1L, 1L, 1L, 1L, 1L, clock.now().toEpochMilliseconds(), null,
            )
            val restartedOffline = RealtimeRepositories(
                database,
                listOf(provider),
                backgroundScope,
                online = { false },
                clock = clock,
                dispatcher = StandardTestDispatcher(testScheduler),
            )
            assertEquals(listOf(station), restartedOffline.observeFavoriteStations().first())
            restartedOffline.setFavorite(station, false)
            restartedOffline.setFavorite(station, false)
            runCurrent()
            assertTrue(restartedOffline.observeFavoriteStations().first().isEmpty())
            assertEquals(
                cachedBoard,
                assertIs<DataResult.Data<StationBoard>>(
                    restartedOffline.observeBoard(station, BoardKind.DEPARTURES).first(),
                ).value,
            )
            assertEquals(station.id.value, database.realtimeQueries.stationByExternal("test", "opaque-origin").executeAsOne().id)
            assertEquals(1, database.realtimeQueries.activeMonitors(clock.now().toEpochMilliseconds()).executeAsList().size)
            observation.cancel()
        }
    }

    @Test fun favoriteTrainsPersistReactivelyCoexistAndSurviveRestartWithoutVolatileState() = runTest {
        withRepository { repository, _, clock, database ->
            val milano = Station(StationId("journey-only-milano"), "Milano Centrale")
            val roma = Station(StationId("station-roma"), "Roma Termini")
            val napoli = Station(StationId("station-napoli"), "Napoli Centrale")
            val train = FavoriteTrain.create(
                TrainNumber("123"),
                originId = roma.id,
                operator = Operator("Trenitalia"),
                originName = roma.name,
                destinationName = "Milano Centrale",
            )
            val sameNumberOtherOrigin = FavoriteTrain.create(
                TrainNumber("123"),
                originId = napoli.id,
                operator = Operator("Trenitalia"),
                originName = napoli.name,
            )
            val states = mutableListOf<List<FavoriteTrain>>()
            val observation = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.observeFavoriteTrains().collect { states += it }
            }

            repository.setFavorite(train, true)
            repository.setFavorite(train, true)
            repository.setFavorite(sameNumberOtherOrigin, true)
            runCurrent()

            assertEquals(setOf(train, sameNumberOtherOrigin), repository.observeFavoriteTrains().first().toSet())
            assertTrue(states.any { it.toSet() == setOf(train, sameNumberOtherOrigin) })
            val row = database.realtimeQueries.favoriteTrains().executeAsList()
                .single { it.id == train.id.value }
            assertEquals("123", row.train_number)
            assertEquals("station-roma", row.origin_station_id)
            assertEquals("Roma Termini", row.origin_name)
            assertEquals("Trenitalia", row.operator_name)
            assertEquals("Milano Centrale", row.destination_name)

            repository.setFavorite(milano.copy(id = StationId("station-milano")), true)
            repository.setFavorite(FavoriteRoute.create(testStation, milano.copy(id = StationId("station-milano"))), true)
            assertEquals(1, repository.observeFavoriteStations().first().size)
            assertEquals(1, repository.observeFavoriteRoutes().first().size)
            assertEquals(2, repository.observeFavoriteTrains().first().size)

            val restartedOffline = RealtimeRepositories(
                database,
                listOf(FakeRealtimeProvider(clock)),
                backgroundScope,
                online = { false },
                clock = clock,
                dispatcher = StandardTestDispatcher(testScheduler),
            )
            assertEquals(setOf(train, sameNumberOtherOrigin), restartedOffline.observeFavoriteTrains().first().toSet())
            restartedOffline.setFavorite(train, false)
            restartedOffline.setFavorite(train, false)
            assertEquals(listOf(sameNumberOtherOrigin), restartedOffline.observeFavoriteTrains().first())
            assertEquals(1, restartedOffline.observeFavoriteStations().first().size)
            assertEquals(1, restartedOffline.observeFavoriteRoutes().first().size)
            observation.cancel()
        }
    }

    @Test fun migrationFromVersion5PreservesExistingFavoritesAndData() = runTest {
        val driver = createUnmanagedRepositoryTestDriver()
        try {
            version5Schema().forEach { sql -> driver.execute(null, sql, 0) }
            driver.execute(null, "INSERT INTO station VALUES ('s-roma', 'Roma Termini', 'roma termini', 1)", 0)
            driver.execute(null, "INSERT INTO station VALUES ('s-milano', 'Milano Centrale', 'milano centrale', 1)", 0)
            driver.execute(null, "INSERT INTO favorite_station VALUES ('s-roma')", 0)
            driver.execute(null, "INSERT INTO favorite_route VALUES ('6:s-roma8:s-milano', 's-roma', 's-milano')", 0)

            // Migrates through every intermediate version up to the current
            // schema, so pre-train-favorite data keeps working with new code.
            TrenifyDatabase.Schema.migrate(driver, 5, 7)

            val database = TrenifyDatabase(driver)
            assertEquals(listOf("s-roma"), database.realtimeQueries.favoriteStations().executeAsList().map { it.id })
            assertEquals(1, database.realtimeQueries.favoriteRoutes().executeAsList().size)
            assertEquals("Roma Termini", database.realtimeQueries.stationById("s-roma").executeAsOne().name)

            val clock = MutableClock()
            val repository = RealtimeRepositories(database, listOf(FakeRealtimeProvider(clock)), backgroundScope,
                clock = clock, dispatcher = StandardTestDispatcher(testScheduler))
            val train = FavoriteTrain.create(
                TrainNumber("9784"),
                originId = StationId("station-roma"),
                operator = Operator("Trenitalia"),
                originName = "Roma Termini",
            )
            repository.setFavorite(train, true)
            assertEquals(listOf(train), repository.observeFavoriteTrains().first())
            assertEquals(listOf("s-roma"), repository.observeFavoriteStations().first().map { it.id.value })
            assertEquals(1, repository.observeFavoriteRoutes().first().size)
        } finally {
            driver.close()
        }
    }

    @Test fun migrationFromVersion6PreservesAllFavoritesWithNullableTrainDiscrimination() = runTest {
        val driver = createUnmanagedRepositoryTestDriver()
        try {
            version6Schema().forEach { sql -> driver.execute(null, sql, 0) }
            driver.execute(null, "INSERT INTO station VALUES ('s-roma', 'Roma Termini', 'roma termini', 1)", 0)
            driver.execute(null, "INSERT INTO station VALUES ('s-milano', 'Milano Centrale', 'milano centrale', 1)", 0)
            driver.execute(null, "INSERT INTO favorite_station VALUES ('s-roma')", 0)
            driver.execute(null, "INSERT INTO favorite_route VALUES ('6:s-roma8:s-milano', 's-roma', 's-milano')", 0)
            // Pre-fix recurring favorites: name-derived identities with no
            // stable station identity. No StationId is fabricated from names.
            val romaLegacy = FavoriteTrain.create(
                TrainNumber("123"),
                originId = null,
                operator = Operator("Trenitalia"),
                originName = "Roma Termini",
                destinationName = "Milano Centrale",
            )
            val napoliLegacy = FavoriteTrain.create(
                TrainNumber("123"),
                originId = null,
                operator = Operator("Trenitalia"),
                originName = "Napoli Centrale",
            )
            driver.execute(null,
                "INSERT INTO favorite_train VALUES ('${romaLegacy.id.value}', '123', 'Roma Termini', 'Trenitalia', 'Milano Centrale')",
                0)
            driver.execute(null,
                "INSERT INTO favorite_train VALUES ('${napoliLegacy.id.value}', '123', 'Napoli Centrale', 'Trenitalia', NULL)",
                0)

            TrenifyDatabase.Schema.migrate(driver, 6, 7)

            val database = TrenifyDatabase(driver)
            assertEquals(listOf("s-roma"), database.realtimeQueries.favoriteStations().executeAsList().map { it.id })
            assertEquals(1, database.realtimeQueries.favoriteRoutes().executeAsList().size)
            assertEquals("Roma Termini", database.realtimeQueries.stationById("s-roma").executeAsOne().name)

            val rows = database.realtimeQueries.favoriteTrains().executeAsList()
            assertEquals(2, rows.size)
            assertTrue(rows.all { it.origin_station_id == null })
            assertEquals(
                setOf("Roma Termini", "Napoli Centrale"),
                rows.map { it.origin_name }.toSet(),
            )

            val clock = MutableClock()
            val repository = RealtimeRepositories(database, listOf(FakeRealtimeProvider(clock)), backgroundScope,
                clock = clock, dispatcher = StandardTestDispatcher(testScheduler))
            val trains = repository.observeFavoriteTrains().first()
            assertEquals(setOf(romaLegacy, napoliLegacy), trains.toSet())
            assertTrue(trains.all { it.originId == null })

            // Legacy rows stay removable through their preserved identities.
            repository.setFavorite(romaLegacy, false)
            assertEquals(listOf(napoliLegacy), repository.observeFavoriteTrains().first())

            // New saves persist the stable origin identity alongside display data.
            val stable = FavoriteTrain.create(
                TrainNumber("123"),
                originId = StationId("station-roma"),
                operator = Operator("Trenitalia"),
                originName = "Roma Termini",
            )
            repository.setFavorite(stable, true)
            assertEquals(
                "station-roma",
                database.realtimeQueries.favoriteTrains().executeAsList()
                    .single { it.id == stable.id.value }.origin_station_id,
            )
            assertEquals(setOf(napoliLegacy, stable), repository.observeFavoriteTrains().first().toSet())

            // Station and route favorites survive alongside train favorites.
            assertEquals(listOf("s-roma"), repository.observeFavoriteStations().first().map { it.id.value })
            assertEquals(1, repository.observeFavoriteRoutes().first().size)
        } finally {
            driver.close()
        }
    }

    // Tables touched by the 5 -> 6 migration test. Unrelated v5 tables are omitted:
    // 5.sqm only adds favorite_train, so they cannot affect this migration.
    private fun version5Schema(): List<String> = listOf(
        "CREATE TABLE station (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, normalized_name TEXT NOT NULL, fetched_at INTEGER NOT NULL)",
        "CREATE TABLE station_external_id (provider_id TEXT NOT NULL, external_id TEXT NOT NULL, station_id TEXT NOT NULL REFERENCES station(id), PRIMARY KEY (provider_id, external_id))",
        "CREATE TABLE favorite_station (station_id TEXT NOT NULL PRIMARY KEY REFERENCES station(id))",
        "CREATE TABLE favorite_route (id TEXT NOT NULL PRIMARY KEY, origin_station_id TEXT NOT NULL REFERENCES station(id), destination_station_id TEXT NOT NULL REFERENCES station(id), UNIQUE (origin_station_id, destination_station_id), CHECK (origin_station_id != destination_station_id))",
    )

    // Pre-fix (version 6) favorite_train without the stable origin identity.
    // Unrelated v6 tables are omitted: 6.sqm only alters favorite_train.
    private fun version6Schema(): List<String> = version5Schema() + listOf(
        "CREATE TABLE favorite_train (id TEXT NOT NULL PRIMARY KEY, train_number TEXT NOT NULL, origin_name TEXT, operator_name TEXT, destination_name TEXT)",
    )

    @Test fun journeySearchHistoryRecordsResolvesAndSeparatesFromRecencyAndFavorites() = runTest {
        withRepository { repository, _, clock, database ->
            val realtimeStation = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("roma")).value.single()
            val states = mutableListOf<List<SearchHistoryEntry>>()
            val observation = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.observeSearchHistory().collect { states += it }
            }
            // Journey-only stations are resolved into the shared station table.
            val entry = repository.recordSearch(journeyRequest)
            runCurrent()
            assertEquals(journeyRequest.origin, entry.origin)
            assertEquals(journeyRequest.destination, entry.destination)
            assertEquals(journeyRequest.at, entry.at)
            assertEquals(journeyRequest.mode, entry.mode)
            assertEquals(clock.now(), entry.submittedAt)
            assertEquals(listOf(entry), repository.observeSearchHistory().first())
            assertEquals(entry, repository.lookupSearch(entry.id))
            assertNull(repository.lookupSearch(SearchHistoryEntryId("missing")))
            assertEquals(journeyRequest.destination.name,
                database.realtimeQueries.stationById(journeyRequest.destination.id.value).executeAsOne().name)
            assertTrue(states.any { it == listOf(entry) })

            repository.record(realtimeStation)
            repository.setFavorite(realtimeStation, true)
            val second = repository.recordSearch(journeyRequest.copy(mode = JourneySearchMode.ARRIVE_BY))
            assertEquals(listOf(second, entry), repository.observeSearchHistory().first())

            // Remove-one keeps the remaining entry; clearing recency or
            // favorites never touches search history and vice versa.
            repository.removeSearch(entry.id)
            assertEquals(listOf(second), repository.observeSearchHistory().first())
            repository.clearHistory()
            assertTrue(repository.observeRecentStations().first().isEmpty())
            assertEquals(listOf(second), repository.observeSearchHistory().first())
            repository.clearSearchHistory()
            assertTrue(repository.observeSearchHistory().first().isEmpty())
            assertEquals(listOf(realtimeStation), repository.observeFavoriteStations().first())
            assertEquals(realtimeStation.id.value,
                database.realtimeQueries.stationById(realtimeStation.id.value).executeAsOne().id)
            assertEquals(journeyRequest.destination.id.value,
                database.realtimeQueries.stationById(journeyRequest.destination.id.value).executeAsOne().id)
            observation.cancel()
        }
    }

    @Test fun journeySearchHistorySurvivesRestartAndJourneyCacheEviction() = runTest {
        withRepository { repository, provider, clock, database ->
            val entry = repository.recordSearch(journeyRequest)
            database.realtimeQueries.putCache("journey-v2:${journeyRequest.key}", "{}", clock.now().toEpochMilliseconds(), null)
            database.realtimeQueries.deleteCache("journey-v2:${journeyRequest.key}")
            val restartedOffline = RealtimeRepositories(
                database,
                listOf(provider),
                backgroundScope,
                online = { false },
                clock = clock,
                dispatcher = StandardTestDispatcher(testScheduler),
            )
            assertEquals(listOf(entry), restartedOffline.observeSearchHistory().first())
            assertEquals(entry, restartedOffline.lookupSearch(entry.id))
            restartedOffline.removeSearch(entry.id)
            assertTrue(restartedOffline.observeSearchHistory().first().isEmpty())
        }
    }

    @Test fun bothSearchModesAndRomeDstInstantsRoundTripExactly() = runTest {
        withRepository { repository, _, _, _ ->
            // Spring-forward Sunday: 02:00-02:59 never exists in Europe/Rome;
            // autumn Sunday repeats 02:00-02:59. Stored instants must be exact.
            val spring = JourneySearchRequest(testStation, journeyDestination,
                Instant.parse("2026-03-29T01:30:00Z"), JourneySearchMode.DEPART_AFTER)
            val autumn = JourneySearchRequest(testStation, journeyDestination,
                Instant.parse("2026-10-25T00:30:00Z"), JourneySearchMode.ARRIVE_BY)
            repository.recordSearch(spring)
            repository.recordSearch(autumn)
            val observed = repository.observeSearchHistory().first()
                .filterIsInstance<JourneySearchHistoryEntry>()
            assertEquals(2, observed.size)
            assertEquals(autumn.at, observed.first { it.mode == JourneySearchMode.ARRIVE_BY }.at)
            assertEquals(spring.at, observed.first { it.mode == JourneySearchMode.DEPART_AFTER }.at)
            assertEquals("2026-03-29", spring.at.toLocalDateTime(RailwayTime.zone).date.toString())
            assertEquals("2026-10-25", autumn.at.toLocalDateTime(RailwayTime.zone).date.toString())
            assertEquals(
                setOf(JourneySearchMode.DEPART_AFTER, JourneySearchMode.ARRIVE_BY),
                observed.map { it.mode }.toSet(),
            )
        }
    }

    @Test fun migrationFromVersion7PreservesAllFavoritesAndStartsEmptyHistory() = runTest {
        val driver = createUnmanagedRepositoryTestDriver()
        try {
            version7Schema().forEach { sql -> driver.execute(null, sql, 0) }
            driver.execute(null, "INSERT INTO station VALUES ('s-roma', 'Roma Termini', 'roma termini', 1)", 0)
            driver.execute(null, "INSERT INTO station VALUES ('s-milano', 'Milano Centrale', 'milano centrale', 1)", 0)
            driver.execute(null, "INSERT INTO favorite_station VALUES ('s-roma')", 0)
            driver.execute(null, "INSERT INTO favorite_route VALUES ('6:s-roma8:s-milano', 's-roma', 's-milano')", 0)
            // A post-fix version 7 row carries the stable origin identity; the
            // id is derived from it, never hand-written.
            val legacy = FavoriteTrain.create(
                TrainNumber("123"),
                originId = StationId("s-roma"),
                operator = Operator("Trenitalia"),
                originName = "Roma Termini",
            )
            driver.execute(null,
                "INSERT INTO favorite_train VALUES ('${legacy.id.value}', '123', 'Roma Termini', 'Trenitalia', NULL, 's-roma')",
                0)

            // Migrates to the current schema, like the older migration tests
            // do: intermediate versions are covered by migration verification.
            TrenifyDatabase.Schema.migrate(driver, 7, 9)

            val database = TrenifyDatabase(driver)
            assertEquals(listOf("s-roma"), database.realtimeQueries.favoriteStations().executeAsList().map { it.id })
            assertEquals(1, database.realtimeQueries.favoriteRoutes().executeAsList().size)
            assertEquals(1, database.realtimeQueries.favoriteTrains().executeAsList().size)
            assertTrue(database.realtimeQueries.journeySearchHistory().executeAsList().isEmpty())
            assertTrue(database.realtimeQueries.trainSearchHistory().executeAsList().isEmpty())

            val clock = MutableClock()
            val repository = RealtimeRepositories(database, listOf(FakeRealtimeProvider(clock)), backgroundScope,
                clock = clock, dispatcher = StandardTestDispatcher(testScheduler))
            val entry = repository.recordSearch(JourneySearchRequest(
                Station(StationId("s-roma"), "Roma Termini"),
                Station(StationId("s-milano"), "Milano Centrale"),
                clock.now(), JourneySearchMode.DEPART_AFTER))
            assertEquals(listOf(entry), repository.observeSearchHistory().first())
            assertEquals(1, repository.observeFavoriteRoutes().first().size)
            assertEquals(listOf(legacy), repository.observeFavoriteTrains().first())
        } finally {
            driver.close()
        }
    }

    // Version 7 is version 6 with the stable train-favorite origin identity.
    private fun version7Schema(): List<String> = version5Schema() + listOf(
        "CREATE TABLE favorite_train (id TEXT NOT NULL PRIMARY KEY, train_number TEXT NOT NULL, origin_name TEXT, operator_name TEXT, destination_name TEXT, origin_station_id TEXT)",
    )

    @Test fun trainSearchHistoryMergesWithJourneysAndStaysSeparateFromRecency() = runTest {
        withRepository { repository, _, clock, database ->
            val realtimeStation = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("roma")).value.single()
            val states = mutableListOf<List<SearchHistoryEntry>>()
            val observation = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.observeSearchHistory().collect { states += it }
            }
            val date = LocalDate.parse("2026-09-06")
            // A plain number-only submission stores no invented route.
            val numberOnly = repository.recordTrainSearch(TrainNumber("123"), date, expected = null)
            assertEquals(TrainNumber("123"), numberOnly.number)
            assertEquals(date, numberOnly.serviceDate)
            assertNull(numberOnly.originId)
            assertNull(numberOnly.originName)
            assertNull(numberOnly.operator)
            assertNull(numberOnly.destinationName)
            assertEquals(clock.now(), numberOnly.submittedAt)
            val trainRow = database.realtimeQueries.trainSearchHistory().executeAsList().single()
            assertEquals("123", trainRow.train_number)
            assertEquals("2026-09-06", trainRow.service_date)
            assertNull(trainRow.origin_station_id)
            assertNull(trainRow.origin_name)
            assertNull(trainRow.operator_name)
            assertNull(trainRow.destination_name)

            // A discriminated submission keeps its origin/operator and resolves
            // the origin into the shared station table.
            clock.instant += 1.minutes
            val expected = TrainLookupIntent(TrainNumber("123"), testStation.id, testStation.name, Operator("Trenitalia"))
            val discriminated = repository.recordTrainSearch(TrainNumber("123"), date, expected)
            assertEquals(testStation.id, discriminated.originId)
            assertEquals(testStation.name, discriminated.originName)
            assertEquals(Operator("Trenitalia"), discriminated.operator)
            assertEquals(expected, discriminated.lookupIntent())
            assertEquals(testStation.name,
                database.realtimeQueries.stationById(testStation.id.value).executeAsOne().name)

            // Journey and train entries merge newest-first; recency is independent.
            clock.instant += 1.minutes
            val journey = repository.recordSearch(journeyRequest)
            assertEquals(listOf(journey, discriminated, numberOnly), repository.observeSearchHistory().first())
            assertEquals(discriminated, repository.lookupSearch(discriminated.id))
            assertEquals(journey, repository.lookupSearch(journey.id))
            repository.record(realtimeStation)
            assertEquals(listOf(journey, discriminated, numberOnly), repository.observeSearchHistory().first())

            // Remove-one is per entry; clearing search history keeps recency and stations.
            repository.removeSearch(numberOnly.id)
            assertEquals(listOf(journey, discriminated), repository.observeSearchHistory().first())
            repository.clearSearchHistory()
            assertTrue(repository.observeSearchHistory().first().isEmpty())
            assertEquals(listOf(realtimeStation), repository.observeRecentStations().first())
            assertEquals(realtimeStation.id.value,
                database.realtimeQueries.stationById(realtimeStation.id.value).executeAsOne().id)
            assertTrue(states.any { it == listOf(journey, discriminated, numberOnly) })
            observation.cancel()
        }
    }

    @Test fun trainSearchHistorySurvivesRestartAndSkipsCorruptRows() = runTest {
        withRepository { repository, provider, clock, database ->
            val date = LocalDate.parse("2026-09-06")
            val entry = repository.recordTrainSearch(TrainNumber("123"), date, expected = null)
            // Corrupt rows never hide the remaining history: an invalid number
            // is skipped while an unreadable date degrades to unknown.
            database.realtimeQueries.insertTrainSearchHistory(
                "corrupt", "abc", "2026-09-06", null, null, null, null, clock.now().toEpochMilliseconds())
            database.realtimeQueries.insertTrainSearchHistory(
                "bad-date", "456", "not-a-date", null, null, null, null, clock.now().toEpochMilliseconds())
            val observed = repository.observeSearchHistory().first().filterIsInstance<TrainSearchHistoryEntry>()
            assertEquals(setOf("123", "456"), observed.map { it.number.value }.toSet())
            assertNull(observed.single { it.number.value == "456" }.serviceDate)
            val restartedOffline = RealtimeRepositories(
                database,
                listOf(provider),
                backgroundScope,
                online = { false },
                clock = clock,
                dispatcher = StandardTestDispatcher(testScheduler),
            )
            assertEquals(entry, restartedOffline.lookupSearch(entry.id))
            assertEquals(2, restartedOffline.observeSearchHistory().first().size)
            restartedOffline.removeSearch(entry.id)
            assertEquals(1, restartedOffline.observeSearchHistory().first().size)
        }
    }

    @Test fun nameOnlyLegacyDiscriminationRoundTripsThroughPersistence() = runTest {
        withRepository { repository, _, _, database ->
            val date = LocalDate.parse("2026-09-06")
            val legacy = TrainLookupIntent(
                TrainNumber("123"),
                originId = null,
                originName = "Roma Termini",
                operator = Operator("Trenitalia"),
            )
            val entry = repository.recordTrainSearch(TrainNumber("123"), date, legacy)
            assertNull(entry.originId)
            assertEquals("Roma Termini", entry.originName)
            assertEquals(Operator("Trenitalia"), entry.operator)
            // The row keeps the bare name without a fabricated station identity.
            val row = database.realtimeQueries.trainSearchHistory().executeAsList().single()
            assertNull(row.origin_station_id)
            assertEquals("Roma Termini", row.origin_name)
            assertEquals("Trenitalia", row.operator_name)
            // Observation and lookup preserve the entry and its legacy intent exactly.
            val observed = assertIs<TrainSearchHistoryEntry>(repository.observeSearchHistory().first().single())
            assertEquals(entry, observed)
            assertEquals(legacy, observed.lookupIntent())
            assertEquals(entry, repository.lookupSearch(entry.id))
        }
    }

    @Test fun idOnlyDiscriminatorPersistsWithoutStationRow() = runTest {
        withRepository { repository, _, _, database ->
            val date = LocalDate.parse("2026-09-06")
            val idOnly = TrainLookupIntent(TrainNumber("123"), originId = StationId("some-stable-id"),
                originName = null, operator = Operator("Trenitalia"))
            val entry = repository.recordTrainSearch(TrainNumber("123"), date, idOnly)
            assertEquals(StationId("some-stable-id"), entry.originId)
            assertNull(entry.originName)
            // The row keeps the bare ID; no canonical station row is fabricated.
            val row = database.realtimeQueries.trainSearchHistory().executeAsList().single()
            assertEquals("some-stable-id", row.origin_station_id)
            assertNull(row.origin_name)
            assertNull(database.realtimeQueries.stationById("some-stable-id").executeAsOneOrNull())
            // Observation and lookup preserve the ID-only intent exactly.
            val observed = assertIs<TrainSearchHistoryEntry>(repository.observeSearchHistory().first().single())
            assertEquals(entry, observed)
            assertEquals(idOnly, observed.lookupIntent())
            assertEquals(entry, repository.lookupSearch(entry.id))
        }
    }

    @Test fun idOnlyDiscriminatorPersistsWithForeignKeysEnforced() = runTest {
        val driver = createRepositoryTestDriver()
        try {
            driver.execute(null, "PRAGMA foreign_keys=ON", 0)
            val database = TrenifyDatabase(driver)
            val clock = MutableClock()
            val repository = RealtimeRepositories(database, listOf(FakeRealtimeProvider(clock)), backgroundScope,
                clock = clock, dispatcher = StandardTestDispatcher(testScheduler))
            // Control: enforcement is genuinely active — a journey row still
            // requires canonical station rows.
            assertTrue(
                runCatching {
                    database.realtimeQueries.insertJourneySearchHistory(
                        "control", "no-such-a", "no-such-b", "A", "B", 1L, "DEPART_AFTER", 2L)
                }.isFailure,
                "expected foreign-key enforcement to reject the control row",
            )
            // The ID-only train discriminator is opaque: it needs no
            // canonical station row and persists without a fake one.
            val idOnly = TrainLookupIntent(TrainNumber("123"), originId = StationId("some-stable-id"),
                originName = null, operator = Operator("Trenitalia"))
            val entry = repository.recordTrainSearch(
                TrainNumber("123"), LocalDate.parse("2026-09-06"), idOnly)
            val observed = assertIs<TrainSearchHistoryEntry>(repository.observeSearchHistory().first().single())
            assertEquals(entry, observed)
            assertEquals(idOnly, observed.lookupIntent())
            assertNull(database.realtimeQueries.stationById("some-stable-id").executeAsOneOrNull())
        } finally {
            driver.close()
        }
    }

    @Test fun recencyRemoveOneAndClearKeepStationsMappingsFavoritesMonitorsAndHistory() = runTest {
        withRepository { repository, _, clock, database ->
            val station = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("roma")).value.single()
            repository.record(station)
            repository.setFavorite(station, true)
            val journey = repository.recordSearch(journeyRequest)
            val train = repository.recordTrainSearch(TrainNumber("123"), LocalDate.parse("2026-09-06"), expected = null)
            database.realtimeQueries.insertActiveMonitor(
                "monitor", testRunId.key, testRunId.provider.value, testRunId.number.value,
                testRunId.origin.value, testRunId.serviceDate.toString(), 1L, null,
                1L, 1L, 1L, 1L, 1L, clock.now().toEpochMilliseconds(), null,
            )
            // Remove-one drops the suggestion but keeps the station, its
            // provider mapping, favorites, monitors and both histories.
            repository.removeRecentStation(station)
            assertTrue(repository.observeRecentStations().first().isEmpty())
            assertEquals(station.id.value, database.realtimeQueries.stationById(station.id.value).executeAsOne().id)
            assertEquals(station.id.value,
                database.realtimeQueries.stationByExternal("test", "opaque-origin").executeAsOne().id)
            assertEquals(listOf(station), repository.observeFavoriteStations().first())
            assertEquals(2, repository.observeSearchHistory().first().size)
            // Re-record, then clear-all: suggestions go while every shared
            // row and both history kinds survive.
            repository.record(station)
            assertEquals(listOf(station), repository.observeRecentStations().first())
            repository.clearHistory()
            assertTrue(repository.observeRecentStations().first().isEmpty())
            assertEquals(station.id.value, database.realtimeQueries.stationById(station.id.value).executeAsOne().id)
            assertEquals(listOf(station), repository.observeFavoriteStations().first())
            assertEquals(setOf(journey.id, train.id), repository.observeSearchHistory().first().map { it.id }.toSet())
            assertEquals(1, database.realtimeQueries.activeMonitors(clock.now().toEpochMilliseconds()).executeAsList().size)
        }
    }

    @Test fun migrationFromVersion8PreservesHistoryRecencyAndFavorites() = runTest {
        val driver = createUnmanagedRepositoryTestDriver()
        try {
            version8Schema().forEach { sql -> driver.execute(null, sql, 0) }
            driver.execute(null, "INSERT INTO station VALUES ('s-roma', 'Roma Termini', 'roma termini', 1)", 0)
            driver.execute(null, "INSERT INTO station VALUES ('s-milano', 'Milano Centrale', 'milano centrale', 1)", 0)
            driver.execute(null, "INSERT INTO recent_station VALUES ('s-roma', 5)", 0)
            driver.execute(null, "INSERT INTO favorite_station VALUES ('s-roma')", 0)
            driver.execute(null, "INSERT INTO favorite_route VALUES ('6:s-roma8:s-milano', 's-roma', 's-milano')", 0)
            // A post-fix version 7 row carries the stable origin identity; the
            // id is derived from it, never hand-written.
            val legacy = FavoriteTrain.create(
                TrainNumber("123"),
                originId = StationId("s-roma"),
                operator = Operator("Trenitalia"),
                originName = "Roma Termini",
            )
            driver.execute(null,
                "INSERT INTO favorite_train VALUES ('${legacy.id.value}', '123', 'Roma Termini', 'Trenitalia', NULL, 's-roma')",
                0)
            driver.execute(null,
                "INSERT INTO journey_search_history VALUES ('j-1', 's-roma', 's-milano', 'Roma Termini', 'Milano Centrale', 10, 'DEPART_AFTER', 20)",
                0)

            TrenifyDatabase.Schema.migrate(driver, 8, 9)

            val database = TrenifyDatabase(driver)
            assertEquals(listOf("s-roma"), database.realtimeQueries.favoriteStations().executeAsList().map { it.id })
            assertEquals(1, database.realtimeQueries.favoriteRoutes().executeAsList().size)
            assertEquals(1, database.realtimeQueries.favoriteTrains().executeAsList().size)
            assertEquals(1, database.realtimeQueries.journeySearchHistory().executeAsList().size)
            assertEquals(listOf("s-roma"), database.realtimeQueries.recentStations().executeAsList().map { it.id })
            assertTrue(database.realtimeQueries.trainSearchHistory().executeAsList().isEmpty())

            val clock = MutableClock()
            val repository = RealtimeRepositories(database, listOf(FakeRealtimeProvider(clock)), backgroundScope,
                clock = clock, dispatcher = StandardTestDispatcher(testScheduler))
            // Existing recency, journey history and favorites survive alongside
            // new train history; recency removal keeps the station and history.
            assertEquals(listOf(Station(StationId("s-roma"), "Roma Termini")), repository.observeRecentStations().first())
            assertEquals(1, repository.observeSearchHistory().first().filterIsInstance<JourneySearchHistoryEntry>().size)
            val train = repository.recordTrainSearch(TrainNumber("123"), LocalDate.parse("2026-09-06"), expected = null)
            assertEquals(listOf(train), repository.observeSearchHistory().first().filterIsInstance<TrainSearchHistoryEntry>())
            assertEquals(2, repository.observeSearchHistory().first().size)
            repository.removeRecentStation(Station(StationId("s-roma"), "Roma Termini"))
            assertTrue(repository.observeRecentStations().first().isEmpty())
            assertEquals(2, repository.observeSearchHistory().first().size)
            assertEquals("Roma Termini", database.realtimeQueries.stationById("s-roma").executeAsOne().name)
        } finally {
            driver.close()
        }
    }

    // Version 8 is version 7 with the journey search history table, plus the
    // recency table this test needs (omitted from the older helpers).
    private fun version8Schema(): List<String> = version7Schema() + listOf(
        "CREATE TABLE recent_station (station_id TEXT NOT NULL PRIMARY KEY REFERENCES station(id), visited_at INTEGER NOT NULL)",
        "CREATE INDEX recent_station_time ON recent_station(visited_at)",
        "CREATE TABLE journey_search_history (id TEXT NOT NULL PRIMARY KEY, origin_station_id TEXT NOT NULL REFERENCES station(id), destination_station_id TEXT NOT NULL REFERENCES station(id), origin_name TEXT NOT NULL, destination_name TEXT NOT NULL, requested_at INTEGER NOT NULL, search_mode TEXT NOT NULL, submitted_at INTEGER NOT NULL, CHECK (origin_station_id != destination_station_id))",
        "CREATE INDEX journey_search_history_submitted ON journey_search_history(submitted_at DESC)",
    )

    @Test fun favoriteRoutesResolveMissingStationsPersistReactivelyAndKeepOrderedIdentity() = runTest {
        withRepository { repository, provider, clock, database ->
            val realtimeOrigin = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("roma")).value.single()
            val journeyOnlyDestination = Station(StationId("journey-only-milano"), "Milano Centrale")
            val route = FavoriteRoute.create(realtimeOrigin, journeyOnlyDestination)
            val reverse = FavoriteRoute.create(journeyOnlyDestination, realtimeOrigin)
            val states = mutableListOf<List<FavoriteRoute>>()
            val observation = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.observeFavoriteRoutes().collect { states += it }
            }

            repository.setFavorite(realtimeOrigin, true)
            repository.setFavorite(route, true)
            repository.setFavorite(route, true)
            repository.setFavorite(reverse, true)
            runCurrent()

            assertEquals(setOf(route, reverse), repository.observeFavoriteRoutes().first().toSet())
            assertEquals(listOf(realtimeOrigin), repository.observeFavoriteStations().first())
            assertEquals(journeyOnlyDestination.name,
                database.realtimeQueries.stationById(journeyOnlyDestination.id.value).executeAsOne().name)
            assertEquals(2, database.realtimeQueries.favoriteRoutes().executeAsList().size)
            assertTrue(states.any { it.toSet() == setOf(route, reverse) })

            val sameNameDifferentIdentity = FavoriteRoute.create(
                realtimeOrigin.copy(id = StationId("journey-only-roma")),
                journeyOnlyDestination,
            )
            repository.setFavorite(sameNameDifferentIdentity, true)
            assertEquals(3, repository.observeFavoriteRoutes().first().size)

            val restartedOffline = RealtimeRepositories(
                database,
                listOf(provider),
                backgroundScope,
                online = { false },
                clock = clock,
                dispatcher = StandardTestDispatcher(testScheduler),
            )
            assertEquals(setOf(route, reverse, sameNameDifferentIdentity),
                restartedOffline.observeFavoriteRoutes().first().toSet())
            restartedOffline.setFavorite(route, false)
            restartedOffline.setFavorite(route, false)
            assertEquals(setOf(reverse, sameNameDifferentIdentity),
                restartedOffline.observeFavoriteRoutes().first().toSet())
            assertEquals(listOf(realtimeOrigin), restartedOffline.observeFavoriteStations().first())
            observation.cancel()
        }
    }
}
