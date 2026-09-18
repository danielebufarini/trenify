package it.danielebufarini.trenify.data

import it.danielebufarini.trenify.core.model.*
import kotlinx.datetime.LocalDate
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.time.Instant

// Persistence DTOs belong to data; the railway model has no serialization dependency.
@Serializable internal data class StationRecord(val id: String, val name: String) {
    fun model() = Station(StationId(id), name)
}
private fun Station.record() = StationRecord(id.value, name)

@Serializable internal data class SummaryRecord(
    val provider: String, val number: String, val originRef: String, val date: String,
    val origin: StationRecord, val destination: String?, val scheduled: Long?,
    val status: String, val delay: Int?, val scheduledPlatform: String?, val actualPlatform: String?, val operator: String?,
    // T7.10 additions, all optional: rows persisted before T7.10 decode with
    // nulls (unknown/unavailable), never invented values. Position rides the
    // summary payload because the train table stores the summary column plus
    // stop rows rather than the whole snapshot record.
    val category: String? = null, val scheduledDeparture: Long? = null, val scheduledArrival: Long? = null,
    val position: PositionRecord? = null,
) {
    fun model() = TrainRunSummary(
        TrainRunId(ProviderId(provider), TrainNumber(number), ExternalStationRef(originRef), LocalDate.parse(date)),
        origin.model(), destination, scheduled?.let(Instant::fromEpochMilliseconds),
        scheduledDeparture?.let(Instant::fromEpochMilliseconds),
        scheduledArrival?.let(Instant::fromEpochMilliseconds),
        TrainStatus.entries.firstOrNull { it.name == status } ?: TrainStatus.UNKNOWN,
        delay, scheduledPlatform, actualPlatform, operator?.let(::Operator),
        category?.let { code -> TrainCategory.entries.firstOrNull { it.code == code } },
    )
}
private fun TrainRunSummary.record() = SummaryRecord(
    id.provider.value, id.number.value, id.origin.value, id.serviceDate.toString(),
    origin.record(), destinationName, scheduledTime?.toEpochMilliseconds(), status.name,
    delayMinutes, scheduledPlatform, actualPlatform, operator?.name,
    category?.code, scheduledDeparture?.toEpochMilliseconds(), scheduledArrival?.toEpochMilliseconds(),
    position = null,
)

@Serializable internal data class StopRecord(
    val station: StationRecord, val scheduledArrival: Long?, val scheduledDeparture: Long?,
    val actualArrival: Long?, val actualDeparture: Long?, val scheduledPlatform: String?,
    val actualPlatform: String?, val delay: Int?, val status: String,
) {
    fun model() = TrainStop(station.model(), scheduledArrival?.let(Instant::fromEpochMilliseconds),
        scheduledDeparture?.let(Instant::fromEpochMilliseconds), actualArrival?.let(Instant::fromEpochMilliseconds),
        actualDeparture?.let(Instant::fromEpochMilliseconds), scheduledPlatform, actualPlatform, delay,
        StopStatus.entries.firstOrNull { it.name == status } ?: StopStatus.UNKNOWN)
}
private fun TrainStop.record() = StopRecord(station.record(), scheduledArrival?.toEpochMilliseconds(),
    scheduledDeparture?.toEpochMilliseconds(), actualArrival?.toEpochMilliseconds(), actualDeparture?.toEpochMilliseconds(),
    scheduledPlatform, actualPlatform, delayMinutes, status.name)

@Serializable internal data class PositionRecord(val stationName: String?, val observedAt: Long?) {
    fun model() = OperationalPosition(stationName, observedAt?.let(Instant::fromEpochMilliseconds))
}
private fun OperationalPosition.record() = PositionRecord(stationName, observedAt?.toEpochMilliseconds())

/** Summary payload plus the train-level position; in-memory summaries never carry a position. */
internal fun TrainRunSummary.recordWith(position: OperationalPosition?): SummaryRecord =
    record().copy(position = position?.record())

@Serializable internal data class TrainSnapshotRecord(
    val summary: SummaryRecord,
    val stops: List<StopRecord>,
) {
    fun model() = TrainRun(summary.model(), stops.map(StopRecord::model), summary.position?.model())
}

internal fun TrainRun.record() = TrainSnapshotRecord(summary.recordWith(position), stops.map(TrainStop::record))

@Serializable internal data class BoardRecord(val station: StationRecord, val kind: String, val trains: List<SummaryRecord>) {
    fun model() = StationBoard(station.model(), BoardKind.valueOf(kind), trains.map { it.model() })
}

private class RecordSerializer<T, R>(
    private val delegate: KSerializer<R>,
    private val toRecord: (T) -> R,
    private val fromRecord: (R) -> T,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: T) = delegate.serialize(encoder, toRecord(value))
    override fun deserialize(decoder: Decoder): T = fromRecord(delegate.deserialize(decoder))
}

internal fun cacheJson() = Json {
    ignoreUnknownKeys = true
    serializersModule = SerializersModule {
        contextual(Station::class, RecordSerializer(StationRecord.serializer(), Station::record, StationRecord::model))
        contextual(TrainRunSummary::class, RecordSerializer(SummaryRecord.serializer(), TrainRunSummary::record, SummaryRecord::model))
        contextual(TrainStop::class, RecordSerializer(StopRecord.serializer(), TrainStop::record, StopRecord::model))
        contextual(StationBoard::class, RecordSerializer(BoardRecord.serializer(),
            { BoardRecord(it.station.record(), it.kind.name, it.trains.map(TrainRunSummary::record)) }, BoardRecord::model))
    }
}
