package it.danielebufarini.trenify.core.domain

import it.danielebufarini.trenify.core.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

enum class DomainFailure { OFFLINE, NOT_FOUND, TEMPORARY, INVALID_RESPONSE, UNSUPPORTED, INVALID_REQUEST }
sealed interface DataResult<out T> {
    data class Data<T>(
        val value: T,
        val freshness: DataFreshness,
        val warning: DomainFailure? = null,
        /**
         * Persistence-shared refresh invocation generation backing this
         * observation (T7.11 corrective pass 2). Request invocation order,
         * not response completion order, determines which concurrent refresh
         * may supersede another; a superseded refresh returns the currently
         * accepted observation with its generation instead of its own stale
         * provider response. Null when the observation carries no ordering
         * evidence (cached reads without order tracking, legacy paths).
         */
        val refreshGeneration: Long? = null,
    ) : DataResult<T>
    data class Failure(val error: DomainFailure) : DataResult<Nothing>
}
data class RealtimePolicy(
    val stationTtl: Duration = 1.days,
    val boardTtl: Duration = 30.seconds,
    val trainTtl: Duration = 30.seconds,
    val completedTrainTtl: Duration = 1.days,
    val monitorTtl: Duration = 60.seconds,
    val searchDebounce: Duration = 275.seconds / 1000,
    val pollingInterval: Duration = 30.seconds,
    val maximumBackoff: Duration = 300.seconds,
)
interface StationRepository {
    suspend fun searchStations(query: String, force: Boolean = false): DataResult<List<Station>>

    /**
     * Observes the canonical local station for a stable [StationId].
     * Emits null when the station is unknown or was deleted. This is a
     * strictly local identity resolution: it never touches the provider
     * network and never starts polling — board routes carry only the id
     * and re-resolve the live station through this flow.
     */
    fun observeStation(id: StationId): Flow<Station?>
}
interface TrainRepository {
    fun observeBoard(station: Station, kind: BoardKind): Flow<DataResult<StationBoard>>
    suspend fun refreshBoard(station: Station, kind: BoardKind, force: Boolean = false): DataResult<StationBoard>
    suspend fun findTrainRuns(number: TrainNumber, date: LocalDate? = null): DataResult<List<TrainRunSummary>>
    fun observeTrain(id: TrainRunId): Flow<DataResult<TrainRun>>
    suspend fun refreshTrain(id: TrainRunId, force: Boolean = false): DataResult<TrainRun>
}
/**
 * Station recency (recent-station suggestions) and durable search history are
 * separate personal-data concepts that must never be merged:
 * - recency tracks stations the user opened, newest first, capped locally;
 *   it has its own observe/record/remove-one/clear operations below and is
 *   never manufactured into search-history entries;
 * - search history records explicit validated journey/train submissions with
 *   their own identities and keeps them independently of recency.
 * Clearing search history never clears recency and vice versa; user-facing
 * delete-all actions keep both operations distinct (T7.8).
 */
/**
 * Thrown by a personal-data write (history, recency or favorite save) that
 * was ordered before a completed delete-all action and therefore must not
 * resurrect the deleted data. Callers already treat persistence failures as
 * retryable best-effort outcomes; a retry issued after the deletion completed
 * is a new post-deletion user action and proceeds normally.
 */
class PersonalDataWriteSupersededException :
    IllegalStateException("Personal-data write superseded by a completed deletion")

interface HistoryRepository {
    /** Station recency for suggestions, newest first. Distinct from search history. */
    fun observeRecentStations(): Flow<List<Station>>
    /** Records one station opening in the recency list. Never creates search history. */
    suspend fun record(station: Station)
    /** Removes one station from the recency suggestions. Keeps the canonical station and provider mappings. */
    suspend fun removeRecentStation(station: Station)
    /** Clears station recency suggestions only. Keeps stations, mappings, favorites, monitors and search history. */
    suspend fun clearHistory()
    /**
     * Durable search history (journey and train entries), newest first.
     * Independent of journey-cache TTL/eviction and of station recency:
     * recording happens once per validated user submission (including
     * no-result and failure outcomes), never per refresh, observation or
     * keystroke. Each entry has a submission identity distinct from any cache
     * key, so separate submissions with identical criteria remain separate
     * entries.
     */
    fun observeSearchHistory(): Flow<List<SearchHistoryEntry>>
    suspend fun recordSearch(request: JourneySearchRequest): JourneySearchHistoryEntry
    /**
     * Records one validated train-number search submission with the explicit
     * requested [serviceDate] and the [expected] origin/operator
     * discrimination when the submission actually knew it. A plain
     * number-only search stores no invented route. Like journey submissions,
     * the entry is recorded once per validated submission whatever the later
     * lookup outcome is; refreshes, observations and keystrokes never record.
     */
    suspend fun recordTrainSearch(
        number: TrainNumber,
        serviceDate: LocalDate?,
        expected: TrainLookupIntent?,
    ): TrainSearchHistoryEntry
    suspend fun lookupSearch(id: SearchHistoryEntryId): SearchHistoryEntry?
    suspend fun removeSearch(id: SearchHistoryEntryId)
    /**
     * Clears journey and train search entries. Never clears station recency:
     * user-visible recent-station suggestions are cleared through
     * [clearHistory] instead.
     */
    suspend fun clearSearchHistory()

    /**
     * Atomically deletes all user-visible search data in one persistence
     * operation (T7.8 Settings action):
     * - journey search-history entries;
     * - train search-history entries;
     * - station recency / recent-station suggestions.
     *
     * The deletion is all-or-nothing: a failure leaves the previous state
     * consistent instead of partially deleted.
     *
     * Never deletes canonical stations, provider-to-internal station
     * mappings, favorites, monitors, monitoring snapshots, dedup/event-claim
     * state, settings/preferences, strike preferences or unrelated caches.
     * No remote or backend interaction is involved.
     *
     * Ordering (T7.8): a personal-data write whose repository invocation
     * began before this deletion completed is ordered before it and must not
     * commit afterwards (implementations fail it with
     * [PersonalDataWriteSupersededException]); only a write invoked after the
     * deletion completed counts as a new post-deletion user action.
     */
    suspend fun clearSearchHistoryAndRecency()
}
interface FavoritesRepository {
    fun observeFavoriteStations(): Flow<List<Station>>
    suspend fun setFavorite(station: Station, favorite: Boolean)
    fun observeFavoriteRoutes(): Flow<List<FavoriteRoute>>
    suspend fun setFavorite(route: FavoriteRoute, favorite: Boolean)
    fun observeFavoriteTrains(): Flow<List<FavoriteTrain>>
    suspend fun setFavorite(train: FavoriteTrain, favorite: Boolean)

    /**
     * Atomically deletes every favorite in one persistence operation (T7.8
     * Settings action):
     * - all station favorites;
     * - all favorite routes;
     * - all recurring-train favorites.
     *
     * The deletion is all-or-nothing: a failure leaves the previous state
     * consistent instead of partially deleted.
     *
     * Never deletes search history, station recency, canonical stations,
     * station mappings, monitors, monitoring snapshots, dedup/event-claim
     * state, settings/preferences, strike preferences or unrelated caches.
     * No remote or backend interaction is involved.
     *
     * Ordering (T7.8): a favorite save whose repository invocation began
     * before this deletion completed is ordered before it and must not commit
     * afterwards (implementations fail it with
     * [PersonalDataWriteSupersededException]); only a save invoked after the
     * deletion completed counts as a new post-deletion user action.
     */
    suspend fun clearAllFavorites()
}

class SearchStations(private val repository: StationRepository) {
    suspend operator fun invoke(query: String, force: Boolean = false) = repository.searchStations(query, force)
}
class ObserveStation(private val repository: StationRepository) {
    operator fun invoke(id: StationId): Flow<Station?> = repository.observeStation(id)
}
class LoadStationBoard(private val repository: TrainRepository) {
    fun observe(station: Station, kind: BoardKind) = repository.observeBoard(station, kind)
    suspend operator fun invoke(station: Station, kind: BoardKind, force: Boolean = false) =
        repository.refreshBoard(station, kind, force)
}
class FindTrainRuns(private val repository: TrainRepository) {
    suspend operator fun invoke(number: TrainNumber, date: LocalDate? = null) = repository.findTrainRuns(number, date)
}
class ObserveTrainRun(private val repository: TrainRepository) {
    operator fun invoke(id: TrainRunId) = repository.observeTrain(id)
    suspend fun refresh(id: TrainRunId, force: Boolean = false) = repository.refreshTrain(id, force)
}

/**
 * Settings delete-all-search-history action (T7.8): one atomic local
 * operation over journey entries, train entries and station recency.
 */
class ClearSearchHistoryAndRecency(private val repository: HistoryRepository) {
    suspend operator fun invoke() = repository.clearSearchHistoryAndRecency()
}

/**
 * Settings delete-all-favorites action (T7.8): one atomic local operation
 * over station, route and recurring-train favorites.
 */
class ClearAllFavorites(private val repository: FavoritesRepository) {
    suspend operator fun invoke() = repository.clearAllFavorites()
}
