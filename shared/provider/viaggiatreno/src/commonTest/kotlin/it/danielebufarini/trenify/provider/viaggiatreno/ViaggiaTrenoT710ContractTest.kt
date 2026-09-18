package it.danielebufarini.trenify.provider.viaggiatreno

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.provider.api.*
import it.danielebufarini.trenify.core.network.createHttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import kotlin.test.*
import kotlin.time.Instant

// T7.10 deterministic source-evidence coverage: category, distinct terminal
// scheduled times, board-event separation, cancellation, stop progress,
// operational position, rescheduled signals and service-date/DST handling.
// Expected values assert provider-neutral semantics, never raw wire strings.
class ViaggiaTrenoT710ContractTest {
    private val fullRef = ProviderTrainRunRef(TrainNumber("8412"), ExternalStationRef("S01700"), LocalDate.parse("2026-09-05"))
    private val bologna = ExternalStationRef("S10001")

    private suspend fun withAdapter(body: String, block: suspend (ViaggiaTrenoAdapter, MockEngine) -> Unit) {
        val engine = MockEngine { respond(body) }
        val client = createHttpClient(engine, it.danielebufarini.trenify.core.network.NetworkConfig(maximumGetRetries = 0))
        try { block(ViaggiaTrenoAdapter(client), engine) } finally { client.close() }
    }

    private val earlyRef = ProviderTrainRunRef(TrainNumber("1201"), ExternalStationRef("S08409"), LocalDate.parse("2026-09-05"))
    private val cancelledRef = ProviderTrainRunRef(TrainNumber("3307"), ExternalStationRef("S08409"), LocalDate.parse("2026-09-05"))
    private val legacyRef = ProviderTrainRunRef(TrainNumber("2493"), ExternalStationRef("S01700"), LocalDate.parse("2026-09-05"))

    private suspend fun detail(ref: ProviderTrainRunRef, body: String): ProviderTrainSnapshot {
        var snapshot: ProviderTrainSnapshot? = null
        withAdapter(body) { adapter, _ ->
            snapshot = assertIs<ProviderResult.Success<ProviderTrainSnapshot>>(adapter.getTrainSnapshot(ref)).value
        }
        return snapshot!!
    }

    @Test fun categoryKnownAbsentAndUnknown() = runTest {
        withAdapter(FixturesV1.boardBolognaDepartures) { adapter, _ ->
            val row = assertIs<ProviderResult.Success<List<ProviderTrainCandidate>>>(
                adapter.departures(bologna, Instant.parse("2026-09-05T05:00:00Z"))).value.single()
            assertEquals(TrainCategory.REG, row.category)
        }
        withAdapter(FixturesV1.boardBolognaArrivals) { adapter, _ ->
            // Frecce carry an empty categoria; only categoriaDescrizione (" FR",
            // with its realized leading space) proves the category.
            val row = assertIs<ProviderResult.Success<List<ProviderTrainCandidate>>>(
                adapter.arrivals(bologna, Instant.parse("2026-09-05T05:00:00Z"))).value.single()
            assertEquals(TrainCategory.FR, row.category)
        }
        assertEquals(TrainCategory.REG, detail(fullRef, FixturesV1.detailFull).train.category)
        // tipoProdotto identifies Frecce in detail snapshots when categoria is empty.
        for ((product, expected) in listOf("100" to TrainCategory.FR, "101" to TrainCategory.FA, "102" to TrainCategory.FB)) {
            val value = detail(fullRef, FixturesV1.detailFull
                .replace("\"categoria\":\"REG\"", "\"categoria\":\"\",\"tipoProdotto\":\"$product\"")).train.category
            assertEquals(expected, value)
        }
        // Historic (99), default (0) and absent tipoProdotto prove no category.
        for (product in listOf("99", "0", "")) {
            val value = detail(fullRef, FixturesV1.detailFull
                .replace("\"categoria\":\"REG\"", "\"categoria\":\"\",\"tipoProdotto\":\"$product\"")).train.category
            assertNull(value)
        }
        // Absent category fields stay unknown: never operator, number prefix or default.
        assertNull(detail(legacyRef, FixturesV1.detail).train.category)
        // Unlisted codes and composite designations stay unknown.
        assertNull(detail(fullRef, FixturesV1.detailFull.replace("\"categoria\":\"REG\"", "\"categoria\":\"XX\"")).train.category)
        assertNull(detail(fullRef, FixturesV1.detailFull.replace(
            "\"categoria\":\"REG\"", "\"categoria\":\"\",\"categoriaDescrizione\":\"EC FR\"")).train.category)
    }

    @Test fun compositeAndContradictoryCategoryTuplesStayUnknownOrDetailed() = runTest {
        val compositeRef = ProviderTrainRunRef(TrainNumber("2251"), ExternalStationRef("S01700"), LocalDate.parse("2026-09-05"))
        // The actual conflicting tuple: coarse "EC" with display "EC FR".
        // The coarse field must not silently override the stronger evidence.
        assertNull(detail(compositeRef, FixturesV1.detailComposite).train.category)
        // Same composite with an empty coarse field: still unknown.
        assertNull(detail(compositeRef, FixturesV1.detailComposite
            .replace("\"categoria\":\"EC\"", "\"categoria\":\"\"")).train.category)
        // The composite board row still parses; only its category is unknown.
        withAdapter(FixturesV1.boardComposite) { adapter, _ ->
            val row = assertIs<ProviderResult.Success<List<ProviderTrainCandidate>>>(
                adapter.departures(compositeRef.origin, Instant.parse("2026-09-05T05:00:00Z"))).value.single()
            assertNull(row.category)
            assertEquals(Instant.fromEpochMilliseconds(1788582600000), row.scheduledTime)
        }
        // A consistent non-composite EC pair still maps.
        assertEquals(TrainCategory.EC, detail(compositeRef, FixturesV1.detailComposite
            .replace("\"categoriaDescrizione\":\"EC FR\",\"compNumeroTreno\":\"EC FR 2251\",", "")).train.category)
        // The detailed field wins a genuine conflict; the coarse field never overrides it.
        assertEquals(TrainCategory.IC, detail(compositeRef, FixturesV1.detailComposite
            .replace("\"categoriaDescrizione\":\"EC FR\",\"compNumeroTreno\":\"EC FR 2251\",", "\"categoriaDescrizione\":\"IC\",")).train.category)
        // Other unlisted designations (RV, TS) stay unknown without widening scope.
        for (code in listOf("RV", "TS")) {
            assertNull(detail(compositeRef, FixturesV1.detailComposite
                .replace("\"categoria\":\"EC\",\"categoriaDescrizione\":\"EC FR\",\"compNumeroTreno\":\"EC FR 2251\",",
                    "\"categoria\":\"$code\",")).train.category)
        }
    }

    @Test fun categoryPrecedenceVetoesWeakerFallbacks() = runTest {
        // Detailed value wins a genuine conflict between known values.
        assertEquals(TrainCategory.IC, detail(fullRef, FixturesV1.detailFull
            .replace("\"tipoTreno\":\"PG\"", "\"tipoTreno\":\"PG\",\"categoriaDescrizione\":\"IC\"")).train.category)
        // Present-but-unsupported detailed value vetoes the known coarse one.
        assertNull(detail(fullRef, FixturesV1.detailFull
            .replace("\"tipoTreno\":\"PG\"", "\"tipoTreno\":\"PG\",\"categoriaDescrizione\":\"ZZ\"")).train.category)
        // Composite detailed value vetoes the known coarse one.
        assertNull(detail(fullRef, FixturesV1.detailFull
            .replace("\"tipoTreno\":\"PG\"", "\"tipoTreno\":\"PG\",\"categoriaDescrizione\":\"EC FR\"")).train.category)
        // Absent detailed value defers to the known coarse category.
        assertEquals(TrainCategory.REG, detail(fullRef, FixturesV1.detailFull).train.category)
        // Present-but-unsupported coarse value vetoes the recognized product code.
        assertNull(detail(fullRef, FixturesV1.detailFull
            .replace("\"categoria\":\"REG\"", "\"categoria\":\"XX\",\"tipoProdotto\":\"101\"")).train.category)
        // Absent coarse value defers to the recognized product code.
        assertEquals(TrainCategory.FA, detail(fullRef, FixturesV1.detailFull
            .replace("\"categoria\":\"REG\"", "\"categoria\":\"\",\"tipoProdotto\":\"101\"")).train.category)
    }

    @Test fun boardEventTimeStaysDistinctFromTerminalTimes() = runTest {
        withAdapter(FixturesV1.boardBolognaDepartures) { adapter, engine ->
            val row = assertIs<ProviderResult.Success<List<ProviderTrainCandidate>>>(
                adapter.departures(bologna, Instant.parse("2026-09-05T05:00:00Z"))).value.single()
            // Station event at Bologna (07:35 Rome), not the origin terminal.
            assertEquals(Instant.fromEpochMilliseconds(1788586500000), row.scheduledTime)
            assertNull(row.scheduledDeparture)
            assertNull(row.scheduledArrival)
            assertEquals(1, engine.requestHistory.size)
            assertTrue(engine.requestHistory.none { it.url.encodedPath.contains("andamentoTreno") })
        }
        withAdapter(FixturesV1.boardBolognaArrivals) { adapter, _ ->
            val row = assertIs<ProviderResult.Success<List<ProviderTrainCandidate>>>(
                adapter.arrivals(bologna, Instant.parse("2026-09-05T05:00:00Z"))).value.single()
            assertEquals(Instant.fromEpochMilliseconds(1788586200000), row.scheduledTime)
            assertNull(row.scheduledDeparture)
            assertNull(row.scheduledArrival)
        }
        val full = detail(fullRef, FixturesV1.detailFull)
        assertEquals(Instant.fromEpochMilliseconds(1788582600000), full.train.scheduledDeparture)
        assertEquals(Instant.fromEpochMilliseconds(1788594000000), full.train.scheduledArrival)
        assertNotEquals(
            Instant.fromEpochMilliseconds(1788586500000),
            full.train.scheduledDeparture,
            "Board event time must never populate the origin terminal time",
        )
        // Missing terminals stay missing: one is never copied from the other.
        val legacy = detail(legacyRef, FixturesV1.detail)
        assertNull(legacy.train.scheduledDeparture)
        assertNull(legacy.train.scheduledArrival)
    }

    @Test fun stopCompletionCancellationAndUnknown() = runTest {
        val full = detail(fullRef, FixturesV1.detailFull)
        assertEquals(
            listOf(StopStatus.COMPLETED, StopStatus.COMPLETED, StopStatus.SCHEDULED, StopStatus.SCHEDULED),
            full.stops.map { it.status },
        )
        // The type marker governs: a type-0 stop stays scheduled even with
        // actual timestamps present (mirrors the uninterpretable-code veto).
        val early = detail(earlyRef, FixturesV1.detailScheduledTypeWithActuals)
        assertEquals(listOf(StopStatus.SCHEDULED, StopStatus.SCHEDULED), early.stops.map { it.status })
        assertEquals(Instant.fromEpochMilliseconds(1788582780000), early.stops[0].actualDeparture)
        // Suppressed stops stay cancelled, distinct from delay/unknown.
        val cancelled = detail(cancelledRef, FixturesV1.detailCancelled)
        assertEquals(TrainStatus.CANCELLED, cancelled.train.status)
        assertEquals(listOf(StopStatus.CANCELLED), cancelled.stops.map { it.status })
        // Extraordinary stops (type 2) have no provider-neutral state: unknown.
        val extra = detail(fullRef, FixturesV1.detailFull.replace(
            "{\"id\":\"S10002\",\"stazione\":\"FIRENZE S.M.N.\",\"arrivo_teorico\":1788590100000,\"partenza_teorica\":1788590400000,\"actualFermataType\":0}",
            "{\"id\":\"S10002\",\"stazione\":\"FIRENZE S.M.N.\",\"arrivo_teorico\":1788590100000,\"partenza_teorica\":1788590400000,\"actualFermataType\":2}",
        ))
        assertEquals(StopStatus.UNKNOWN, extra.stops[2].status)
    }

    @Test fun positionAndObservationTimestamp() = runTest {
        val full = detail(fullRef, FixturesV1.detailFull)
        assertEquals("BOLOGNA CENTRALE", full.position?.stationName)
        assertEquals(Instant.fromEpochMilliseconds(1788586680000), full.position?.observedAt)
        withAdapter(FixturesV1.detailFull) { adapter, _ ->
            val result = assertIs<ProviderResult.Success<ProviderTrainSnapshot>>(adapter.getTrainSnapshot(fullRef))
            assertEquals(Instant.fromEpochMilliseconds(1788586680000), result.metadata.sourceTimestamp)
        }
        // Unknown marker plus missing time: no position at all, nothing fabricated.
        assertNull(detail(cancelledRef, FixturesV1.detailCancelled).position)
        assertNull(detail(earlyRef, FixturesV1.detailScheduledTypeWithActuals).position)
        // Weaker observations are preserved as-is, never upgraded or filled in.
        val noTime = detail(fullRef, FixturesV1.detailFull.replace(",\"oraUltimoRilevamento\":1788586680000", ""))
        assertEquals("BOLOGNA CENTRALE", noTime.position?.stationName)
        assertNull(noTime.position?.observedAt)
        val noStation = detail(cancelledRef, FixturesV1.detailCancelled
            .replace("\"stazioneUltimoRilevamento\":\"--\"", "\"stazioneUltimoRilevamento\":\"--\",\"oraUltimoRilevamento\":1788582780000"))
        assertNull(noStation.position?.stationName)
        assertEquals(Instant.fromEpochMilliseconds(1788582780000), noStation.position?.observedAt)
        // Pre-T7.10 fixture shape: observation time without a station name.
        val legacy = detail(legacyRef, FixturesV1.detail)
        assertNull(legacy.position?.stationName)
        assertEquals(Instant.fromEpochMilliseconds(1788582780000), legacy.position?.observedAt)
    }

    @Test fun rescheduledSignalRequiresExplicitSourceEvidence() = runTest {
        // Delay alone never implies rescheduling.
        assertEquals(TrainStatus.RUNNING, detail(fullRef, FixturesV1.detailFull).train.status)
        withAdapter(FixturesV1.boardBolognaDepartures
            .replace("\"provvedimento\":0", "\"provvedimento\":0,\"riprogrammazione\":\"Y\"")) { adapter, _ ->
            assertEquals(TrainStatus.RESCHEDULED, assertIs<ProviderResult.Success<List<ProviderTrainCandidate>>>(
                adapter.departures(bologna, Instant.parse("2026-09-05T05:00:00Z"))).value.single().status)
        }
        withAdapter(FixturesV1.boardBolognaDepartures
            .replace("\"provvedimento\":0", "\"provvedimento\":1,\"riprogrammazione\":\"Y\"")) { adapter, _ ->
            // Cancellation outranks rescheduling.
            assertEquals(TrainStatus.CANCELLED, assertIs<ProviderResult.Success<List<ProviderTrainCandidate>>>(
                adapter.departures(bologna, Instant.parse("2026-09-05T05:00:00Z"))).value.single().status)
        }
        withAdapter(FixturesV1.boardBolognaDepartures
            .replace("\"provvedimento\":0", "\"provvedimento\":0,\"riprogrammazione\":\"N\"")) { adapter, _ ->
            assertEquals(TrainStatus.RUNNING, assertIs<ProviderResult.Success<List<ProviderTrainCandidate>>>(
                adapter.departures(bologna, Instant.parse("2026-09-05T05:00:00Z"))).value.single().status)
        }
        withAdapter(FixturesV1.boardBolognaDepartures
            .replace("\"provvedimento\":0", "\"provvedimento\":0,\"riprogrammazione\":\"Z\"")) { adapter, _ ->
            // Unknown flag values never produce RESCHEDULED; the RUNNING here
            // comes solely from the independent circolante evidence.
            assertEquals(TrainStatus.RUNNING, assertIs<ProviderResult.Success<List<ProviderTrainCandidate>>>(
                adapter.departures(bologna, Instant.parse("2026-09-05T05:00:00Z"))).value.single().status)
        }
        withAdapter(FixturesV1.boardBolognaDepartures
            .replace("\"provvedimento\":0,\"circolante\":true", "\"provvedimento\":0,\"riprogrammazione\":\"Z\"")) { adapter, _ ->
            // An unknown flag value with no other operational evidence
            // contributes nothing: the row stays UNKNOWN, never a guess.
            assertEquals(TrainStatus.UNKNOWN, assertIs<ProviderResult.Success<List<ProviderTrainCandidate>>>(
                adapter.departures(bologna, Instant.parse("2026-09-05T05:00:00Z"))).value.single().status)
        }
        withAdapter(FixturesV1.boardBolognaDepartures
            .replace("\"provvedimento\":0", "\"provvedimento\":1,\"riprogrammazione\":\"Z\"")) { adapter, _ ->
            // Cancellation evidence still applies alongside an unknown flag.
            assertEquals(TrainStatus.CANCELLED, assertIs<ProviderResult.Success<List<ProviderTrainCandidate>>>(
                adapter.departures(bologna, Instant.parse("2026-09-05T05:00:00Z"))).value.single().status)
        }
        val rescheduled = detail(fullRef, FixturesV1.detailFull
            .replace("\"provvedimento\":0", "\"provvedimento\":0,\"riprogrammazione\":\"Y\""))
        assertEquals(TrainStatus.RESCHEDULED, rescheduled.train.status)
    }

    @Test fun boardArrivalFlagIsStationScopedWhileDetailArrivalIsTrainScoped() = runTest {
        withAdapter(FixturesV1.boardBolognaDepartures
            .replace("\"provvedimento\":0", "\"provvedimento\":0,\"arrivato\":true")) { adapter, _ ->
            // Arrived at the board station proves circulation, not journey completion.
            assertEquals(TrainStatus.RUNNING, assertIs<ProviderResult.Success<List<ProviderTrainCandidate>>>(
                adapter.departures(bologna, Instant.parse("2026-09-05T05:00:00Z"))).value.single().status)
        }
        val arrived = detail(fullRef, FixturesV1.detailFull.replace("\"circolante\":true", "\"arrivato\":true"))
        assertEquals(TrainStatus.ARRIVED, arrived.train.status)
    }

    @Test fun serviceDateBoundaryAndRomeDstInstantsAreExact() = runTest {
        val ref = ProviderTrainRunRef(TrainNumber("1963"), ExternalStationRef("S08409"), LocalDate.parse("2026-10-25"))
        withAdapter(FixturesV1.detailOvernightDst) { adapter, engine ->
            val value = assertIs<ProviderResult.Success<ProviderTrainSnapshot>>(adapter.getTrainSnapshot(ref)).value
            assertEquals(LocalDate.parse("2026-10-25"), value.train.ref.serviceDate)
            assertEquals(TrainCategory.ICN, value.train.category)
            // 23:50 Rome Oct 25 (+01:00) and 01:20 Rome Oct 26 (+01:00): the
            // arrival lands on the next local date, the service date does not move.
            assertEquals(Instant.fromEpochMilliseconds(1792968600000), value.train.scheduledDeparture)
            assertEquals(Instant.fromEpochMilliseconds(1792974000000), value.train.scheduledArrival)
            assertEquals("2026-10-25", Instant.fromEpochMilliseconds(1792968600000)
                .toLocalDateTime(RailwayTime.zone).date.toString())
            assertEquals("2026-10-26", Instant.fromEpochMilliseconds(1792974000000)
                .toLocalDateTime(RailwayTime.zone).date.toString())
            assertEquals("FIRENZE S.M.N.", value.position?.stationName)
            assertTrue(engine.requestHistory.single().url.encodedPath.endsWith("/S08409/1963/1792879200000"))
        }
        // Board date headers use the Rome offset in force at the instant:
        // +02:00 before the transition, +01:00 after it.
        withAdapter(FixturesV1.boardBolognaDepartures) { adapter, engine ->
            adapter.departures(bologna, Instant.parse("2026-10-25T00:30:00Z"))
            assertTrue(engine.requestHistory.single().url.toString().decodeURLPart()
                .contains("Sun Oct 25 2026 02:30:00 GMT+0200"))
        }
        withAdapter(FixturesV1.boardBolognaDepartures) { adapter, engine ->
            adapter.departures(bologna, Instant.parse("2026-10-25T22:30:00Z"))
            assertTrue(engine.requestHistory.single().url.toString().decodeURLPart()
                .contains("Sun Oct 25 2026 23:30:00 GMT+0100"))
        }
    }
}
