package it.danielebufarini.trenify.core.provider.api

import it.danielebufarini.trenify.core.model.*
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

data class ProviderCapabilities(
    val stationSearch: Boolean = true,
    val stationBoards: Boolean = true,
    val trainSearch: Boolean = true,
    val trainDetail: Boolean = true,
)
data class ProviderMetadata(val providerId: ProviderId, val fetchedAt: Instant, val sourceTimestamp: Instant? = null)
enum class ProviderFailure { TRANSPORT, TIMEOUT, PROTOCOL, UNAVAILABLE, PARSING, UNSUPPORTED }

sealed interface ProviderResult<out T> {
    /**
     * Successful provider response.
     *
     * [complete] is provider-neutral snapshot completeness (T7.12): true when
     * no source record was skipped while producing [value], so absence from
     * [value] is meaningful evidence for the requested scope. Parsers that
     * skip undecodable records must report false here while keeping their
     * diagnostics adapter-local; repositories must not treat an incomplete
     * snapshot as proof of emptiness. Defaults to true so providers without
     * partial-parse observability keep their established behavior.
     */
    data class Success<T>(val value: T, val metadata: ProviderMetadata, val complete: Boolean = true) : ProviderResult<T>
    data object NotFound : ProviderResult<Nothing>
    data class Unavailable(val retryable: Boolean, val cause: ProviderFailure) : ProviderResult<Nothing>
}

data class ProviderStation(val ref: ExternalStationRef, val name: String)
data class ProviderTrainRunRef(
    val number: TrainNumber,
    val origin: ExternalStationRef,
    val serviceDate: LocalDate,
    val external: ExternalRunRef? = null,
)
data class ProviderTrainCandidate(
    val ref: ProviderTrainRunRef,
    val origin: ProviderStation,
    val destinationName: String? = null,
    /**
     * Station-board event time for board rows (scheduled departure/arrival at
     * the board station). Never an origin/destination terminal time there.
     */
    val scheduledTime: Instant? = null,
    /**
     * Scheduled origin departure / destination arrival. Detail snapshots only;
     * always null for board rows (T7.10).
     */
    val scheduledDeparture: Instant? = null,
    val scheduledArrival: Instant? = null,
    val status: TrainStatus = TrainStatus.UNKNOWN,
    val delayMinutes: Int? = null,
    val scheduledPlatform: String? = null,
    val actualPlatform: String? = null,
    val operator: Operator? = null,
    /** Source-backed category; null when the provider supplies none (T7.10). */
    val category: TrainCategory? = null,
)
data class ProviderTrainStop(
    val station: ProviderStation,
    val scheduledArrival: Instant? = null,
    val scheduledDeparture: Instant? = null,
    val actualArrival: Instant? = null,
    val actualDeparture: Instant? = null,
    val scheduledPlatform: String? = null,
    val actualPlatform: String? = null,
    val delayMinutes: Int? = null,
    val status: StopStatus = StopStatus.UNKNOWN,
)
data class ProviderTrainSnapshot(
    val train: ProviderTrainCandidate,
    val stops: List<ProviderTrainStop>,
    /** Source-backed operational position; null when the provider supplies none (T7.10). */
    val position: ProviderOperationalPosition? = null,
)
/**
 * Adapter-local operational observation: the reported last-detection station
 * name ([stationName], null for the source unknown marker/absence) with its
 * own source observation instant ([observedAt], null when absent — never fetch
 * or cache time). Provider-neutral; wire codes never appear here.
 */
data class ProviderOperationalPosition(val stationName: String?, val observedAt: Instant?)

interface TrainRealtimeProvider {
    val id: ProviderId
    val capabilities: ProviderCapabilities
    suspend fun searchStations(query: String, limit: Int): ProviderResult<List<ProviderStation>>
    suspend fun departures(station: ExternalStationRef, at: Instant): ProviderResult<List<ProviderTrainCandidate>>
    suspend fun arrivals(station: ExternalStationRef, at: Instant): ProviderResult<List<ProviderTrainCandidate>>
    suspend fun findTrainCandidates(number: TrainNumber, serviceDate: LocalDate?): ProviderResult<List<ProviderTrainCandidate>>
    suspend fun getTrainSnapshot(ref: ProviderTrainRunRef): ProviderResult<ProviderTrainSnapshot>
}

