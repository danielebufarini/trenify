package it.danielebufarini.trenify.data

import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.provider.api.*
import it.danielebufarini.trenify.core.testing.*
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlin.test.*
import kotlin.time.Instant

// T7.10 persistence: new optional realtime fields ride the existing JSON
// payload columns, so no SQLDelight schema change or migration is required.
// These tests prove round-trip, NULL preservation, pre-T7.10 row
// compatibility and observation/fetch timestamp separation on real drivers.
@OptIn(ExperimentalCoroutinesApi::class)
class TrainInfoPersistenceTest {
    private suspend fun TestScope.withRepository(block: suspend TestScope.(RealtimeRepositories, FakeRealtimeProvider, MutableClock, TrenifyDatabase) -> Unit) {
        val driver = createRepositoryTestDriver()
        val database = TrenifyDatabase(driver)
        val clock = MutableClock()
        val provider = FakeRealtimeProvider(clock)
        val repository = RealtimeRepositories(database, listOf(provider), backgroundScope, clock = clock,
            dispatcher = StandardTestDispatcher(testScheduler))
        try { block(repository, provider, clock, database) } finally { driver.close() }
    }

    private fun restarted(database: TrenifyDatabase, provider: FakeRealtimeProvider, clock: MutableClock, scope: TestScope) =
        RealtimeRepositories(database, listOf(provider), scope.backgroundScope, clock = clock,
            dispatcher = StandardTestDispatcher(scope.testScheduler))

    @Test fun trainDetailRoundTripsCategoryTerminalsAndPosition() = runTest {
        withRepository { repository, provider, clock, database ->
            val departure = Instant.parse("2026-09-05T04:30:00Z")
            val arrival = Instant.parse("2026-09-05T07:40:00Z")
            val observed = Instant.parse("2026-09-05T05:38:00Z")
            provider.candidate = provider.candidate.copy(
                category = TrainCategory.REG,
                scheduledDeparture = departure,
                scheduledArrival = arrival,
            )
            provider.detail = ProviderTrainSnapshot(
                provider.candidate,
                listOf(
                    ProviderTrainStop(provider.origin, scheduledDeparture = departure, actualDeparture = observed,
                        status = StopStatus.COMPLETED),
                    ProviderTrainStop(ProviderStation(ExternalStationRef("second"), "Firenze"),
                        scheduledArrival = arrival, status = StopStatus.SCHEDULED),
                ),
                ProviderOperationalPosition("BOLOGNA CENTRALE", observed),
            )
            val stored = assertIs<DataResult.Data<TrainRun>>(repository.refreshTrain(testRunId)).value
            assertEquals(TrainCategory.REG, stored.summary.category)
            assertEquals(departure, stored.summary.scheduledDeparture)
            assertEquals(arrival, stored.summary.scheduledArrival)
            assertEquals(OperationalPosition("BOLOGNA CENTRALE", observed), stored.position)
            assertEquals(listOf(StopStatus.COMPLETED, StopStatus.SCHEDULED), stored.stops.map { it.status })
            val afterRestart = assertIs<DataResult.Data<TrainRun>>(
                restarted(database, provider, clock, this).observeTrain(testRunId).first()).value
            assertEquals(stored, afterRestart)
        }
    }

    @Test fun absentFieldsStayAbsentAcrossRestart() = runTest {
        withRepository { repository, provider, clock, database ->
            val stored = assertIs<DataResult.Data<TrainRun>>(repository.refreshTrain(testRunId)).value
            assertNull(stored.summary.category)
            assertNull(stored.summary.scheduledDeparture)
            assertNull(stored.summary.scheduledArrival)
            assertNull(stored.position)
            val afterRestart = assertIs<DataResult.Data<TrainRun>>(
                restarted(database, provider, clock, this).observeTrain(testRunId).first()).value
            assertNull(afterRestart.summary.category)
            assertNull(afterRestart.summary.scheduledDeparture)
            assertNull(afterRestart.summary.scheduledArrival)
            assertNull(afterRestart.position)
        }
    }

    @Test fun boardRowsCarryCategoryAndEventTimeWithoutTerminals() = runTest {
        withRepository { repository, provider, _, _ ->
            val event = Instant.parse("2026-09-05T05:35:00Z")
            provider.candidate = provider.candidate.copy(category = TrainCategory.FR, scheduledTime = event)
            val station = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("roma")).value.single()
            val board = assertIs<DataResult.Data<StationBoard>>(
                repository.refreshBoard(station, BoardKind.DEPARTURES)).value
            val row = board.trains.single()
            assertEquals(TrainCategory.FR, row.category)
            assertEquals(event, row.scheduledTime)
            assertNull(row.scheduledDeparture)
            assertNull(row.scheduledArrival)
            assertEquals(0, provider.detailCalls)
        }
    }

    @Test fun legacyRowsWithoutNewFieldsDecodeAsUnknown() = runTest {
        withRepository { repository, _, clock, database ->
            // A pre-T7.10 summary payload: no category/terminal keys at all.
            val legacySummary = """{"provider":"test","number":"123","originRef":"opaque-origin","date":"2026-09-05","origin":{"id":"internal-station","name":"Roma Termini"},"destination":"Milano Centrale","scheduled":null,"status":"RUNNING","delay":null,"scheduledPlatform":"1","actualPlatform":"2","operator":null}"""
            val legacyStop = """{"station":{"id":"internal-station","name":"Roma Termini"},"scheduledArrival":null,"scheduledDeparture":null,"actualArrival":null,"actualDeparture":null,"scheduledPlatform":"1","actualPlatform":"2","delay":null,"status":"COMPLETED"}"""
            database.transaction {
                database.realtimeQueries.putTrain(testRunId.key, "test", "123", "2026-09-05",
                    legacySummary, clock.now().toEpochMilliseconds(), null)
                database.realtimeQueries.putStop(testRunId.key, 0, legacyStop)
            }
            val migrated = assertIs<DataResult.Data<TrainRun>>(repository.observeTrain(testRunId).first()).value
            assertNull(migrated.summary.category)
            assertNull(migrated.summary.scheduledDeparture)
            assertNull(migrated.summary.scheduledArrival)
            assertNull(migrated.position)
            assertEquals(StopStatus.COMPLETED, migrated.stops.single().status)
            assertEquals("2", migrated.stops.single().actualPlatform)
        }
    }

    @Test fun observationTimestampStaysDistinctFromFetchTimestamp() = runTest {
        withRepository { repository, provider, clock, database ->
            val observed = Instant.parse("2026-09-05T05:38:00Z")
            provider.detail = provider.detail.copy(
                position = ProviderOperationalPosition("BOLOGNA CENTRALE", observed))
            val stored = assertIs<DataResult.Data<TrainRun>>(repository.refreshTrain(testRunId)).value
            assertEquals(observed, stored.position?.observedAt)
            val row = database.realtimeQueries.trainById(testRunId.key).executeAsOne()
            assertEquals(clock.now().toEpochMilliseconds(), row.fetched_at)
            assertNotEquals(row.fetched_at, stored.position?.observedAt?.toEpochMilliseconds())
            val freshness = assertIs<DataFreshness.Fresh>(
                assertIs<DataResult.Data<TrainRun>>(repository.observeTrain(testRunId).first()).freshness)
            assertEquals(Instant.fromEpochMilliseconds(row.fetched_at), freshness.fetchedAt)
        }
    }

    @Test fun cancellationAndStopStatusesSurviveRoundTrip() = runTest {
        withRepository { repository, provider, clock, database ->
            provider.candidate = provider.candidate.copy(status = TrainStatus.CANCELLED)
            provider.detail = ProviderTrainSnapshot(
                provider.candidate,
                listOf(
                    ProviderTrainStop(provider.origin, status = StopStatus.CANCELLED),
                    ProviderTrainStop(ProviderStation(ExternalStationRef("second"), "Firenze"),
                        status = StopStatus.UNKNOWN),
                ),
            )
            val stored = assertIs<DataResult.Data<TrainRun>>(repository.refreshTrain(testRunId)).value
            assertEquals(TrainStatus.CANCELLED, stored.summary.status)
            val afterRestart = assertIs<DataResult.Data<TrainRun>>(
                restarted(database, provider, clock, this).observeTrain(testRunId).first()).value
            assertEquals(stored, afterRestart)
            assertEquals(listOf(StopStatus.CANCELLED, StopStatus.UNKNOWN), afterRestart.stops.map { it.status })
        }
    }

    @Test fun snapshotRecordJsonRoundTripsUnknownCategorySafely() {
        val json = cacheJson()
        val record = TrainSnapshotRecord(
            SummaryRecord("test", "123", "opaque-origin", "2026-09-05",
                StationRecord("internal-station", "Roma Termini"), "Milano Centrale", null,
                "RUNNING", null, null, null, null, "FUTURE", null, null),
            emptyList(),
        )
        // A category code unknown to this build degrades to unknown, never a crash.
        assertNull(json.decodeFromString<TrainSnapshotRecord>(json.encodeToString(record)).model().summary.category)
    }
}
