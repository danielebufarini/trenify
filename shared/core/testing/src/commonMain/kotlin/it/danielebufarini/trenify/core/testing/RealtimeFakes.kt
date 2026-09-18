package it.danielebufarini.trenify.core.testing

import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.provider.api.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class MutableClock(var instant: Instant = Instant.parse("2026-09-05T08:00:00Z")) : Clock {
    override fun now() = instant
}

/**
 * A clock that advances on every reading, so independent `now()` calls never
 * coincide accidentally in single-flight and window-derivation tests.
 */
class TickingClock(
    var instant: Instant = Instant.parse("2026-09-05T08:00:00Z"),
    val step: Duration = 7.milliseconds,
) : Clock {
    override fun now(): Instant = instant.also { instant += step }
}

val testStation = Station(StationId("internal-station"), "Roma Termini")
val testRunId = TrainRunId(ProviderId("test"), TrainNumber("123"), ExternalStationRef("opaque-origin"), LocalDate.parse("2026-09-05"))
val testSummary = TrainRunSummary(testRunId, testStation, "Milano Centrale", status = TrainStatus.RUNNING)
val testRun = TrainRun(testSummary, listOf(TrainStop(testStation, scheduledPlatform = "1", actualPlatform = "2")))

class FakeRealtimeProvider(private val clock: Clock = MutableClock()) : TrainRealtimeProvider {
    override val id = ProviderId("test")
    override val capabilities = ProviderCapabilities()
    val origin = ProviderStation(ExternalStationRef("opaque-origin"), "Roma Termini")
    var candidate = ProviderTrainCandidate(ProviderTrainRunRef(testRunId.number, origin.ref, testRunId.serviceDate),
        origin, "Milano Centrale", status = TrainStatus.RUNNING)
    var stationCalls = 0
    var boardCalls = 0
    var detailCalls = 0
    var failure: ProviderFailure? = null
    var beforeDetail: suspend () -> Unit = {}
    var detail = ProviderTrainSnapshot(candidate, listOf(
        ProviderTrainStop(origin, scheduledDeparture = clock.now(), actualDeparture = clock.now(),
            scheduledPlatform = "1", actualPlatform = "2", status = StopStatus.COMPLETED),
    ))
    private fun <T> result(value: T): ProviderResult<T> = failure?.let { ProviderResult.Unavailable(false, it) }
        ?: ProviderResult.Success(value, ProviderMetadata(id, clock.now(), clock.now()))
    override suspend fun searchStations(query: String, limit: Int): ProviderResult<List<ProviderStation>> {
        stationCalls++
        return result(listOf(origin))
    }
    override suspend fun departures(station: ExternalStationRef, at: Instant): ProviderResult<List<ProviderTrainCandidate>> {
        boardCalls++
        return result(listOf(candidate))
    }
    override suspend fun arrivals(station: ExternalStationRef, at: Instant) = departures(station, at)
    override suspend fun findTrainCandidates(number: TrainNumber, serviceDate: LocalDate?) = result(listOf(candidate))
    override suspend fun getTrainSnapshot(ref: ProviderTrainRunRef): ProviderResult<ProviderTrainSnapshot> {
        detailCalls++
        beforeDetail()
        return result(detail)
    }
}

class FakeRealtimeRepositories : StationRepository, TrainRepository, HistoryRepository, FavoritesRepository {
    var stationResult: DataResult<List<Station>> = DataResult.Data(listOf(testStation), DataFreshness.Unknown)
    var runsResult: DataResult<List<TrainRunSummary>> = DataResult.Data(listOf(testSummary), DataFreshness.Unknown)
    val trainState = MutableStateFlow<DataResult<TrainRun>>(DataResult.Data(testRun, DataFreshness.Unknown))
    val boardState = MutableStateFlow<DataResult<StationBoard>>(
        DataResult.Data(StationBoard(testStation, BoardKind.DEPARTURES, listOf(testSummary)), DataFreshness.Unknown))
    var searches = mutableListOf<String>()
    var trainRefreshes = 0
    var boardRefreshes = 0
    var onSearch: suspend (String) -> Unit = {}
    var onRefresh: suspend () -> Unit = {}
    override suspend fun searchStations(query: String, force: Boolean): DataResult<List<Station>> {
        searches += query
        onSearch(query)
        return stationResult
    }
    /**
     * Canonical local stations for [observeStation] (T7.13 corrective
     * pass): boards resolve their route id through this directory, so tests
     * stage renames by replacing the entry and deletions by removing it.
     */
    val stationDirectory = MutableStateFlow(mapOf(testStation.id to testStation))
    override fun observeStation(id: StationId): Flow<Station?> = stationDirectory.map { it[id] }
    override suspend fun findTrainRuns(number: TrainNumber, date: LocalDate?) = runsResult
    override fun observeTrain(id: TrainRunId): Flow<DataResult<TrainRun>> = trainState
    private var generation = 0L

    override suspend fun refreshTrain(id: TrainRunId, force: Boolean): DataResult<TrainRun> {
        trainRefreshes++
        onRefresh()
        val current = trainState.value
        // Test-only monotonic observation evidence, mirroring the production
        // refresh-generation contract so fake-driven coordinator tests
        // exercise the ordered path. Warning-backed fallbacks and failures
        // pass through untouched so tests can stage unordered results.
        return if (current is DataResult.Data && current.warning == null && current.refreshGeneration == null) {
            current.copy(refreshGeneration = ++generation)
        } else {
            current
        }
    }
    override fun observeBoard(station: Station, kind: BoardKind): Flow<DataResult<StationBoard>> = boardState
    override suspend fun refreshBoard(station: Station, kind: BoardKind, force: Boolean): DataResult<StationBoard> {
        boardRefreshes++
        val result = DataResult.Data(StationBoard(station, kind, listOf(testSummary)), DataFreshness.Unknown)
        boardState.value = result
        return result
    }
    private val history = MutableStateFlow<List<Station>>(emptyList())
    private val recentObservationFailures = MutableSharedFlow<Throwable>(extraBufferCapacity = 1)
    override fun observeRecentStations(): Flow<List<Station>> = merge(
        history,
        recentObservationFailures.map<Throwable, List<Station>> { throw it },
    )
    fun failRecentObservation(cause: Throwable) {
        check(recentObservationFailures.tryEmit(cause))
    }
    override suspend fun record(station: Station) { history.value = listOf(station) }
    override suspend fun removeRecentStation(station: Station) {
        history.value = history.value.filterNot { it.id == station.id }
    }
    var recentHistoryFailure: Throwable? = null
    override suspend fun clearHistory() {
        recentHistoryFailure?.let { throw it }
        history.value = emptyList()
    }
    var clearHistoryFailure: Throwable? = null
    var clearHistoryCalls = 0
    override suspend fun clearSearchHistoryAndRecency() {
        clearHistoryCalls++
        clearHistoryFailure?.let { throw it }
        history.value = emptyList()
        searchHistory.value = emptyList()
    }
    private val searchHistory = MutableStateFlow<List<SearchHistoryEntry>>(emptyList())
    private var historySubmissions = 0
    var searchHistoryFailure: Throwable? = null
    private val searchHistoryObservationFailures = MutableSharedFlow<Throwable>(extraBufferCapacity = 1)
    override fun observeSearchHistory(): Flow<List<SearchHistoryEntry>> = merge(
        searchHistory,
        searchHistoryObservationFailures.map<Throwable, List<SearchHistoryEntry>> { throw it },
    )
    fun failSearchHistoryObservation(cause: Throwable) {
        check(searchHistoryObservationFailures.tryEmit(cause))
    }
    override suspend fun recordSearch(request: JourneySearchRequest): JourneySearchHistoryEntry {
        searchHistoryFailure?.let { throw it }
        val entry = JourneySearchHistoryEntry(
            SearchHistoryEntryId("history-${historySubmissions++}"),
            request.origin,
            request.destination,
            request.at,
            request.mode,
            Clock.System.now(),
        )
        searchHistory.value = listOf(entry) + searchHistory.value
        return entry
    }
    override suspend fun recordTrainSearch(
        number: TrainNumber,
        serviceDate: LocalDate?,
        expected: TrainLookupIntent?,
    ): TrainSearchHistoryEntry {
        searchHistoryFailure?.let { throw it }
        val entry = TrainSearchHistoryEntry(
            SearchHistoryEntryId("history-${historySubmissions++}"),
            number,
            serviceDate,
            expected?.originId?.takeIf { it.value.isNotBlank() },
            expected?.originName?.takeIf { it.isNotBlank() },
            expected?.operator?.takeIf { it.name.isNotBlank() },
            destinationName = null,
            Clock.System.now(),
        )
        searchHistory.value = listOf(entry) + searchHistory.value
        return entry
    }
    override suspend fun lookupSearch(id: SearchHistoryEntryId): SearchHistoryEntry? =
        searchHistory.value.firstOrNull { it.id == id }
    var removeSearchFailure: Throwable? = null
    override suspend fun removeSearch(id: SearchHistoryEntryId) {
        removeSearchFailure?.let { throw it }
        searchHistory.value = searchHistory.value.filterNot { it.id == id }
    }
    override suspend fun clearSearchHistory() { searchHistory.value = emptyList() }
    val favorites = MutableStateFlow<List<Station>>(emptyList())
    var favoriteFailure: Throwable? = null
    var beforeFavoriteMutation: suspend () -> Unit = {}
    private val favoritesObservationFailures = MutableSharedFlow<Throwable>(extraBufferCapacity = 1)
    fun failFavoritesObservation(cause: Throwable) {
        check(favoritesObservationFailures.tryEmit(cause))
    }
    override fun observeFavoriteStations(): Flow<List<Station>> = merge(
        favorites,
        favoritesObservationFailures.map<Throwable, List<Station>> { throw it },
    )
    override suspend fun setFavorite(station: Station, favorite: Boolean) {
        beforeFavoriteMutation()
        favoriteFailure?.let { throw it }
        favorites.value = if (favorite) {
            (favorites.value + station).distinctBy(Station::id)
        } else {
            favorites.value.filterNot { it.id == station.id }
        }
    }
    val favoriteRoutes = MutableStateFlow<List<FavoriteRoute>>(emptyList())
    override fun observeFavoriteRoutes(): Flow<List<FavoriteRoute>> = merge(
        favoriteRoutes,
        favoritesObservationFailures.map<Throwable, List<FavoriteRoute>> { throw it },
    )
    override suspend fun setFavorite(route: FavoriteRoute, favorite: Boolean) {
        beforeFavoriteMutation()
        favoriteFailure?.let { throw it }
        favoriteRoutes.value = if (favorite) {
            (favoriteRoutes.value + route).distinctBy(FavoriteRoute::id)
        } else {
            favoriteRoutes.value.filterNot { it.id == route.id }
        }
    }
    val favoriteTrains = MutableStateFlow<List<FavoriteTrain>>(emptyList())
    override fun observeFavoriteTrains(): Flow<List<FavoriteTrain>> = merge(
        favoriteTrains,
        favoritesObservationFailures.map<Throwable, List<FavoriteTrain>> { throw it },
    )
    override suspend fun setFavorite(train: FavoriteTrain, favorite: Boolean) {
        beforeFavoriteMutation()
        favoriteFailure?.let { throw it }
        favoriteTrains.value = if (favorite) {
            (favoriteTrains.value + train).distinctBy(FavoriteTrain::id)
        } else {
            favoriteTrains.value.filterNot { it.id == train.id }
        }
    }
    var clearFavoritesFailure: Throwable? = null
    var clearFavoritesCalls = 0
    override suspend fun clearAllFavorites() {
        clearFavoritesCalls++
        clearFavoritesFailure?.let { throw it }
        favorites.value = emptyList()
        favoriteRoutes.value = emptyList()
        favoriteTrains.value = emptyList()
    }
}

class FakeMonitoringRepository(
    private val clock: Clock = MutableClock(),
) : MonitoringRepository {
    val monitors = MutableStateFlow<List<TrainMonitor>>(emptyList())
    val persistedEvents = mutableListOf<TrainMonitorEvent>()
    val claimedNotifications = mutableListOf<TrainMonitorEvent>()
    private val eventKeys = mutableSetOf<String>()
    private val notificationTimes = mutableMapOf<MonitorEventKind, Instant>()

    override suspend fun createMonitor(
        trainRunId: TrainRunId,
        thresholds: MonitorThresholds,
        expiresAt: Instant?,
    ): TrainMonitor {
        val existing = monitors.value.firstOrNull { it.trainRunId == trainRunId }
        // Monotonic lifecycle (T7.11 corrective, mirrors production): a
        // retained ended monitor is returned unchanged, never reactivated. A
        // new ACTIVE row is possible only after durable removal/expiry.
        if (existing?.endedAt != null) return existing
        val monitor = existing?.copy(enabled = true, thresholds = thresholds, expiresAt = expiresAt)
            ?: TrainMonitor(MonitorId(trainRunId.key), trainRunId, true, thresholds, clock.now(), expiresAt)
        monitors.value = monitors.value.filterNot { it.trainRunId == trainRunId } + monitor
        return monitor
    }

    override suspend fun removeMonitor(trainRunId: TrainRunId) {
        monitors.value = monitors.value.filterNot { it.trainRunId == trainRunId }
        // Mirror production row deletion: event identity for a removed monitor
        // is forgotten, so re-monitoring the same train starts clean.
        eventKeys.removeAll { key -> key.contains(trainRunId.key) }
        acceptedOrders.keys.removeAll { key -> key.value.contains(trainRunId.key) }
    }

    override suspend fun updateMonitorPreferences(trainRunId: TrainRunId, thresholds: MonitorThresholds) {
        monitors.value = monitors.value.map { monitor ->
            if (monitor.trainRunId == trainRunId) monitor.copy(thresholds = thresholds) else monitor
        }
    }

    override suspend fun setMonitorNotificationsEnabled(trainRunId: TrainRunId, enabled: Boolean) {
        monitors.value = monitors.value.map { monitor ->
            if (monitor.trainRunId == trainRunId) monitor.copy(notificationsEnabled = enabled) else monitor
        }
    }

    override suspend fun recordRefreshFailure(monitorId: MonitorId, failure: DomainFailure, failedAt: Instant) {
        // Presentation-only metadata (mirrors production): a no-op for
        // missing, disabled or ended monitors; never touches the snapshot,
        // events, version or ordering generation.
        monitors.value = monitors.value.map { monitor ->
            if (monitor.id == monitorId && monitor.enabled && monitor.endedAt == null) {
                monitor.copy(refreshFailure = failure, refreshFailedAt = failedAt)
            } else monitor
        }
    }

    override fun observeActiveMonitors(): Flow<List<TrainMonitor>> = monitors.map { values ->
        values.filter { monitor ->
            val expiresAt = monitor.expiresAt
            monitor.enabled && monitor.endedAt == null && (expiresAt == null || expiresAt > clock.now())
        }
    }

    override fun observeEndedMonitors(): Flow<List<TrainMonitor>> = monitors.map { values ->
        values.filter { monitor ->
            val endedAt = monitor.endedAt
            monitor.enabled && endedAt != null && clock.now() < endedAt + MONITOR_POST_TERMINAL_RETENTION
        }
    }

    override fun observeMonitor(trainRunId: TrainRunId): Flow<TrainMonitor?> = monitors.map { values ->
        values.firstOrNull { it.trainRunId == trainRunId && it.enabled }
    }

    private fun eventKey(monitorId: MonitorId, event: TrainMonitorEvent, emittedAt: Instant): String =
        // Terminal events use stable identity so ARRIVED/CANCELLED can never
        // duplicate across repeated observations or restart (mirrors
        // production). Non-terminal events keep the evaluated-at qualifier so
        // genuine recurring transitions can re-notify.
        when (event) {
            is TrainMonitorEvent.Arrived, is TrainMonitorEvent.Cancelled ->
                "$monitorId:${event.fingerprint}"
            else -> "${emittedAt}:$monitorId:${event.fingerprint}"
        }

    private val acceptedOrders = mutableMapOf<MonitorId, Long>()

    override suspend fun persistEvaluation(
        monitorId: MonitorId,
        snapshot: MonitoredTrainSnapshot,
        events: List<TrainMonitorEvent>,
        expectedVersion: Long,
        observationOrder: Long?,
    ): EvaluationCommit {
        // Stale-safe: a refresh that resumes after the terminal commit (or
        // after removal/disable) must not overwrite the final snapshot or
        // resurrect the monitor. Versioned (mirrors production CAS): a base
        // that moved is rejected so concurrent evaluations from one accepted
        // snapshot cannot duplicate a logical event or regress newer state.
        // Ordered (mirrors production pass 2): a superseded observation is
        // dropped even when the version still matches.
        val current = monitors.value.firstOrNull { it.id == monitorId }
            ?: return EvaluationCommit.NotActive
        if (!current.enabled || current.endedAt != null) return EvaluationCommit.NotActive
        if (current.snapshotVersion != expectedVersion) return EvaluationCommit.StaleBase
        val accepted = acceptedOrders[monitorId] ?: 0L
        if (observationOrder != null && observationOrder < accepted) return EvaluationCommit.StaleBase
        val inserted = events.filter { eventKeys.add(eventKey(monitorId, it, snapshot.evaluatedAt)) }
        persistedEvents += inserted
        if (observationOrder != null) acceptedOrders[monitorId] = observationOrder
        monitors.value = monitors.value.map { monitor ->
            if (monitor.id == monitorId) {
                monitor.copy(
                    lastSnapshot = snapshot,
                    lastEvent = events.lastOrNull() ?: monitor.lastEvent,
                    snapshotVersion = expectedVersion + 1,
                    refreshFailure = null,
                    refreshFailedAt = null,
                )
            } else monitor
        }
        return EvaluationCommit.Committed(inserted)
    }

    override suspend fun completeTerminally(
        monitorId: MonitorId,
        snapshot: MonitoredTrainSnapshot,
        events: List<TrainMonitorEvent>,
        endedAt: Instant,
        observationOrder: Long?,
    ): List<TrainMonitorEvent> {
        // Observation-ordered terminal commit (mirrors production final
        // pass): first accepted terminal observation establishes endedAt
        // (ACTIVE → ENDED); strictly newer accepted terminal observations
        // correct the retained snapshot while endedAt and ENDED stay
        // monotonic. Removed/disabled monitors never resurrect.
        val current = monitors.value.firstOrNull { it.id == monitorId } ?: return emptyList()
        if (!current.enabled) return emptyList()
        val accepted = acceptedOrders[monitorId] ?: 0L
        if (observationOrder != null) {
            if (observationOrder < accepted) return emptyList()
            if (observationOrder == accepted && current.endedAt != null) return emptyList()
        } else if (current.endedAt != null) {
            return emptyList()
        }
        if (current.endedAt == null) {
            val inserted = events.filter { eventKeys.add(eventKey(monitorId, it, snapshot.evaluatedAt)) }
            persistedEvents += inserted
            if (observationOrder != null) acceptedOrders[monitorId] = observationOrder
            monitors.value = monitors.value.map { monitor ->
                if (monitor.id == monitorId) {
                    monitor.copy(
                        lastSnapshot = snapshot,
                        lastEvent = events.lastOrNull() ?: monitor.lastEvent,
                        endedAt = endedAt,
                        snapshotVersion = current.snapshotVersion + 1,
                        refreshFailure = null,
                        refreshFailedAt = null,
                    )
                } else monitor
            }
            return inserted
        }
        // Terminal snapshot correction only: strictly newer order refreshes
        // the retained snapshot and advances the accepted generation while
        // the original endedAt, ENDED lifecycle and polling shutdown stay
        // fixed. Same-state corrections claim nothing new (stable identity
        // dedupes); different-state corrections claim the new terminal event.
        val order = observationOrder ?: return emptyList()
        val inserted = events.filter { eventKeys.add(eventKey(monitorId, it, snapshot.evaluatedAt)) }
        persistedEvents += inserted
        acceptedOrders[monitorId] = order
        monitors.value = monitors.value.map { monitor ->
            if (monitor.id == monitorId) {
                monitor.copy(
                    lastSnapshot = snapshot,
                    lastEvent = events.lastOrNull() ?: monitor.lastEvent,
                    snapshotVersion = current.snapshotVersion + 1,
                    refreshFailure = null,
                    refreshFailedAt = null,
                )
            } else monitor
        }
        return inserted
    }

    override suspend fun removeEndedMonitor(trainRunId: TrainRunId) {
        val removed = monitors.value.filter { it.trainRunId == trainRunId && it.endedAt != null }
        monitors.value = monitors.value - removed.toSet()
        removed.forEach { monitor ->
            eventKeys.removeAll { key -> key.contains(monitor.id.value) }
            acceptedOrders.remove(monitor.id)
        }
    }

    override suspend fun cleanupExpiredEnded(now: Instant) {
        val removed = monitors.value.filter { it.isRetentionExpired(now) && it.endedAt != null }
        monitors.value = monitors.value - removed.toSet()
        removed.forEach { monitor ->
            eventKeys.removeAll { key -> key.contains(monitor.id.value) }
            acceptedOrders.remove(monitor.id)
        }
    }

    override suspend fun disableExpired(now: Instant) {
        monitors.value = monitors.value.map { monitor ->
            val expiresAt = monitor.expiresAt
            // The two-day safety expiry limits active monitors only; ended
            // monitors keep their 24-hour retention even past expiresAt.
            if (monitor.endedAt == null && expiresAt != null && expiresAt <= now) monitor.copy(enabled = false)
            else monitor
        }
    }

    override suspend fun disableMonitor(monitorId: MonitorId) {
        monitors.value = monitors.value.map { if (it.id == monitorId) it.copy(enabled = false) else it }
    }

    override suspend fun claimNotification(
        event: TrainMonitorEvent,
        emittedAt: Instant,
        cooldown: Duration,
    ): Boolean {
        val previous = notificationTimes[event.kind]
        if (previous != null && emittedAt - previous < cooldown) return false
        notificationTimes[event.kind] = emittedAt
        claimedNotifications += event
        return true
    }
}
