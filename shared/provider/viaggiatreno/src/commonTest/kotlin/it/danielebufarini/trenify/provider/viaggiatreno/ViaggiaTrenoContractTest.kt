package it.danielebufarini.trenify.provider.viaggiatreno

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.provider.api.*
import it.danielebufarini.trenify.core.network.createHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.*
import kotlin.time.Instant

class ViaggiaTrenoContractTest {
    private val ref = ProviderTrainRunRef(TrainNumber("2493"), ExternalStationRef("S01700"), LocalDate.parse("2026-09-05"))
    private suspend fun withAdapter(body: String, code: HttpStatusCode = HttpStatusCode.OK,
        block: suspend (ViaggiaTrenoAdapter, MockEngine) -> Unit) {
        val engine = MockEngine { respond(body, code) }
        val client = createHttpClient(engine, it.danielebufarini.trenify.core.network.NetworkConfig(maximumGetRetries = 0))
        try { block(ViaggiaTrenoAdapter(client), engine) } finally { client.close() }
    }
    @Test fun stationAutocompleteAndTrainCandidatesRespectWireFormat() = runTest {
        withAdapter(FixturesV1.stations) { adapter, engine ->
            val result = assertIs<ProviderResult.Success<List<ProviderStation>>>(adapter.searchStations("Roma", 10))
            assertEquals(listOf("ROMA TERMINI", "ROMA TIBURTINA"), result.value.map { it.name })
            assertEquals("text/plain", engine.requestHistory.single().headers[HttpHeaders.Accept])
        }
        withAdapter(FixturesV1.candidates) { adapter, _ ->
            val result = assertIs<ProviderResult.Success<List<ProviderTrainCandidate>>>(adapter.findTrainCandidates(ref.number, null))
            assertEquals("MILANO CENTRALE", result.value.single().origin.name)
            assertEquals(ref.serviceDate, result.value.single().ref.serviceDate)
            val otherDate = assertIs<ProviderResult.Success<List<ProviderTrainCandidate>>>(
                adapter.findTrainCandidates(ref.number, LocalDate.parse("2026-09-06")))
            assertTrue(otherDate.value.isEmpty())
        }
    }
    @Test fun boardsUseRomeDateAndNeverRequestDetails() = runTest {
        withAdapter(FixturesV1.board) { adapter, engine ->
            val departures = assertIs<ProviderResult.Success<List<ProviderTrainCandidate>>>(
                adapter.departures(ref.origin, Instant.parse("2026-09-05T08:00:00Z")))
            val arrivals = assertIs<ProviderResult.Success<List<ProviderTrainCandidate>>>(
                adapter.arrivals(ref.origin, Instant.parse("2026-09-05T08:00:00Z")))
            assertEquals("23", departures.value.single().scheduledPlatform)
            assertEquals("19", departures.value.single().actualPlatform)
            assertEquals("2", arrivals.value.single().scheduledPlatform)
            assertEquals("3", arrivals.value.single().actualPlatform)
            assertEquals(2, engine.requestHistory.size)
            assertEquals("application/json", engine.requestHistory.first().headers[HttpHeaders.Accept])
            assertTrue(engine.requestHistory.first().url.toString().decodeURLPart().contains("Sat Sep 05 2026 10:00:00 GMT+0200"))
            assertTrue(engine.requestHistory.none { it.url.encodedPath.contains("andamentoTreno") })
        }
    }
    @Test fun detailPreservesPlatformsStopsAndSourceTimestamp() = runTest {
        withAdapter(FixturesV1.detail) { adapter, engine ->
            val result = assertIs<ProviderResult.Success<ProviderTrainSnapshot>>(adapter.getTrainSnapshot(ref))
            assertEquals(TrainStatus.RUNNING, result.value.train.status)
            assertEquals(3, result.value.train.delayMinutes)
            assertEquals("23", result.value.stops.single().scheduledPlatform)
            assertEquals("19", result.value.stops.single().actualPlatform)
            assertEquals(Instant.fromEpochMilliseconds(1788582780000), result.metadata.sourceTimestamp)
            assertTrue(engine.requestHistory.single().url.encodedPath.endsWith("/S01700/2493/1788559200000"))
        }
    }
    @Test fun regionalDetailProvidesAuthoritativeOperatorAndIntermediatePlannedSegment() = runTest {
        val regional = ProviderTrainRunRef(
            TrainNumber("2815"),
            ExternalStationRef("S01440"),
            LocalDate.parse("2026-09-18"),
        )
        withAdapter(FixturesV1.detailRegional2815) { adapter, _ ->
            val snapshot = assertIs<ProviderResult.Success<ProviderTrainSnapshot>>(
                adapter.getTrainSnapshot(regional),
            ).value
            assertEquals("TIRANO", snapshot.train.origin.name)
            assertEquals(Operator("Trenord"), snapshot.train.operator)
            assertEquals(TrainCategory.REG, snapshot.train.category)
            assertEquals(TrainStatus.RUNNING, snapshot.train.status)
            assertEquals(7, snapshot.train.delayMinutes)
            val monza = snapshot.stops.single { it.station.ref == ExternalStationRef("S01322") }
            val milano = snapshot.stops.single { it.station.ref == ExternalStationRef("S01700") }
            assertEquals(Instant.fromEpochMilliseconds(1789712820000), monza.scheduledDeparture)
            assertEquals(Instant.fromEpochMilliseconds(1789713240000), monza.actualDeparture)
            assertEquals(Instant.fromEpochMilliseconds(1789713780000), milano.scheduledArrival)
            assertEquals(Instant.fromEpochMilliseconds(1789714200000), milano.actualArrival)
        }
    }
    @Test fun regional2823MapsExpectedAndActualPlatformsToTheirStops() = runTest {
        val regional = ProviderTrainRunRef(
            TrainNumber("2823"),
            ExternalStationRef("S01440"),
            LocalDate.parse("2026-09-18"),
        )
        withAdapter(FixturesV1.detailRegional2823) { adapter, _ ->
            val snapshot = assertIs<ProviderResult.Success<ProviderTrainSnapshot>>(
                adapter.getTrainSnapshot(regional),
            ).value
            val tirano = snapshot.stops.single { it.station.ref == ExternalStationRef("S01440") }
            val monza = snapshot.stops.single { it.station.ref == ExternalStationRef("S01322") }
            val milano = snapshot.stops.single { it.station.ref == ExternalStationRef("S01700") }

            assertEquals("1", tirano.scheduledPlatform)
            assertEquals("1", tirano.actualPlatform)
            assertEquals("5", monza.scheduledPlatform)
            assertEquals("7", monza.actualPlatform)
            assertEquals("9", milano.scheduledPlatform)
            assertNull(milano.actualPlatform)
        }
    }
    @Test fun cancellationAndUnknownValuesDegradeSafely() = runTest {
        for ((code, status) in listOf(1 to TrainStatus.CANCELLED, 2 to TrainStatus.PARTIALLY_CANCELLED,
            3 to TrainStatus.DIVERTED, 999 to TrainStatus.UNKNOWN)) {
            withAdapter(FixturesV1.detail.replace("\"provvedimento\":0", "\"provvedimento\":$code")) { adapter, _ ->
                assertEquals(status, assertIs<ProviderResult.Success<ProviderTrainSnapshot>>(adapter.getTrainSnapshot(ref)).value.train.status)
            }
        }
        for ((type, status) in listOf("ST" to TrainStatus.CANCELLED, "SI" to TrainStatus.PARTIALLY_CANCELLED,
            "SF" to TrainStatus.PARTIALLY_CANCELLED, "PP" to TrainStatus.PARTIALLY_CANCELLED, "DV" to TrainStatus.DIVERTED)) {
            withAdapter(FixturesV1.detail.replace("\"PG\"", "\"$type\"")) { adapter, _ ->
                assertEquals(status, assertIs<ProviderResult.Success<ProviderTrainSnapshot>>(adapter.getTrainSnapshot(ref)).value.train.status)
            }
        }
        withAdapter(FixturesV1.detail.replace("\"PG\"", "\"FUTURE\"").replace("\"actualFermataType\":1", "\"actualFermataType\":888")) { adapter, _ ->
            val value = assertIs<ProviderResult.Success<ProviderTrainSnapshot>>(adapter.getTrainSnapshot(ref)).value
            assertEquals(TrainStatus.UNKNOWN, value.train.status)
            assertEquals(StopStatus.UNKNOWN, value.stops.single().status)
        }
    }
    @Test fun missingOptionalFieldsAndExtraFieldsAreAccepted() = runTest {
        withAdapter("""{"numeroTreno":2493,"future":[]}""") { adapter, _ ->
            val value = assertIs<ProviderResult.Success<ProviderTrainSnapshot>>(adapter.getTrainSnapshot(ref)).value
            assertEquals(TrainStatus.UNKNOWN, value.train.status)
            assertTrue(value.stops.isEmpty())
        }
    }
    @Test fun emptyAndMalformedPayloadsHaveControlledResults() = runTest {
        for (body in listOf("", "null")) withAdapter(body) { adapter, _ ->
            assertTrue(assertIs<ProviderResult.Success<List<ProviderStation>>>(adapter.searchStations("x", 10)).value.isEmpty())
        }
        withAdapter("[]") { adapter, _ ->
            assertTrue(assertIs<ProviderResult.Success<List<ProviderTrainCandidate>>>(
                adapter.departures(ref.origin, Instant.parse("2026-09-05T08:00:00Z"))).value.isEmpty())
        }
        for (body in listOf("", "{broken", "<html>unavailable</html>", """{"numeroTreno":5}""")) {
            withAdapter(body) { adapter, _ -> assertIs<ProviderResult.Unavailable>(adapter.getTrainSnapshot(ref)) }
        }
        withAdapter("not a station") { adapter, _ ->
            assertEquals(ProviderFailure.PARSING, assertIs<ProviderResult.Unavailable>(adapter.searchStations("x", 10)).cause)
        }
    }
    @Test fun httpErrorsAndTimeoutsMapToSharedFailures() = runTest {
        withAdapter("", HttpStatusCode.NotFound) { adapter, _ -> assertIs<ProviderResult.NotFound>(adapter.getTrainSnapshot(ref)) }
        withAdapter("", HttpStatusCode.ServiceUnavailable) { adapter, _ ->
            assertEquals(ProviderFailure.UNAVAILABLE, assertIs<ProviderResult.Unavailable>(adapter.getTrainSnapshot(ref)).cause)
        }
        withAdapter("", HttpStatusCode.BadRequest) { adapter, _ ->
            assertEquals(ProviderFailure.PROTOCOL, assertIs<ProviderResult.Unavailable>(adapter.getTrainSnapshot(ref)).cause)
        }
        val client = HttpClient(MockEngine { throw HttpRequestTimeoutException(url = "https://test", timeoutMillis = 1) })
        try {
            assertEquals(ProviderFailure.TIMEOUT, assertIs<ProviderResult.Unavailable>(
                ViaggiaTrenoAdapter(client).getTrainSnapshot(ref)).cause)
        } finally { client.close() }
        val cancelled = HttpClient(MockEngine { throw CancellationException("cancelled") })
        try {
            assertFailsWith<CancellationException> { ViaggiaTrenoAdapter(cancelled).getTrainSnapshot(ref) }
        } finally { cancelled.close() }
    }
}
