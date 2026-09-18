package it.danielebufarini.trenify.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.cash.sqldelight.coroutines.mapToList
import it.danielebufarini.trenify.core.network.RequestInstrumentation
import it.danielebufarini.trenify.core.network.span
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.provider.api.*
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

class RealtimeRepositories(
    private val database: TrenifyDatabase,
    providers: List<TrainRealtimeProvider>,
    private val scope: CoroutineScope,
    private val online: () -> Boolean = { true },
    private val clock: Clock = Clock.System,
    private val policy: RealtimePolicy = RealtimePolicy(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val instrumentation: RequestInstrumentation = RequestInstrumentation.None,
    private val historyGate: DeleteWinsGate = DeleteWinsGate(),
    private val favoritesGate: DeleteWinsGate = DeleteWinsGate(),
) : StationRepository, TrainRepository, HistoryRepository, FavoritesRepository {
    private val providers = providers.associateBy { it.id }
    private val provider = providers.first()
    private val queries = database.realtimeQueries
    private val json = cacheJson()
    private val flights = SingleFlight(scope)
    private val warnings = MutableStateFlow<Map<String, DomainFailure?>>(emptyMap())

    private fun station(value: ProviderStation, metadata: ProviderMetadata): Station {
        val existing = queries.stationByExternal(metadata.providerId.value, value.ref.value).executeAsOneOrNull()
        val id = existing?.id ?: Uuid.random().toString()
        val name = value.name.ifBlank { existing?.name ?: "—" }
        queries.putStation(id, name, normalizeStationQuery(name), metadata.fetchedAt.toEpochMilliseconds())
        queries.putExternal(metadata.providerId.value, value.ref.value, id)
        return Station(StationId(id), name)
    }

    private fun summary(value: ProviderTrainCandidate, metadata: ProviderMetadata) = TrainRunSummary(
        id = TrainRunId(metadata.providerId, value.ref.number, value.ref.origin, value.ref.serviceDate),
        origin = station(value.origin, metadata),
        destinationName = value.destinationName,
        scheduledTime = value.scheduledTime,
        scheduledDeparture = value.scheduledDeparture,
        scheduledArrival = value.scheduledArrival,
        status = value.status,
        delayMinutes = value.delayMinutes,
        scheduledPlatform = value.scheduledPlatform,
        actualPlatform = value.actualPlatform,
        operator = value.operator,
        category = value.category,
    )

    private fun snapshot(value: ProviderTrainSnapshot, metadata: ProviderMetadata): TrainRun = TrainRun(
        summary(value.train, metadata),
        value.stops.map { stop ->
            TrainStop(station(stop.station, metadata), stop.scheduledArrival, stop.scheduledDeparture,
                stop.actualArrival, stop.actualDeparture, stop.scheduledPlatform, stop.actualPlatform,
                stop.delayMinutes, stop.status)
        },
        value.position?.let { OperationalPosition(it.stationName, it.observedAt) },
    )

    private fun freshness(fetched: Long, source: Long?, ttl: Duration, warning: DomainFailure? = null): DataFreshness {
        val fetchedAt = Instant.fromEpochMilliseconds(fetched)
        val sourceAt = source?.let(Instant::fromEpochMilliseconds)
        val age = (clock.now() - (sourceAt ?: fetchedAt)).coerceAtLeast(Duration.ZERO)
        return if (warning == null && age < ttl && clock.now() - fetchedAt < ttl) DataFreshness.Fresh(fetchedAt, sourceAt)
        else DataFreshness.Stale(fetchedAt, age, sourceAt)
    }

    private inline fun <reified T> cached(key: String, ttl: Duration, warning: DomainFailure? = null): DataResult.Data<T>? {
        val row = queries.cacheByKey(key).executeAsOneOrNull() ?: return null
        val value = try { json.decodeFromString<T>(row.payload) } catch (_: IllegalArgumentException) {
            queries.deleteCache(key)
            return null
        }
        return DataResult.Data(value, freshness(row.fetched_at, row.source_timestamp, ttl, warning), warning)
    }

    private inline fun <reified T> save(key: String, value: T, metadata: ProviderMetadata) {
        queries.putCache(key, json.encodeToString(value), metadata.fetchedAt.toEpochMilliseconds(), metadata.sourceTimestamp?.toEpochMilliseconds())
    }

    private fun <T> failed(key: String, cached: DataResult.Data<T>?, error: DomainFailure): DataResult<T> {
        warnings.update { it + (key to error) }
        return cached?.let {
            val stale = when (val freshness = it.freshness) {
                is DataFreshness.Fresh -> DataFreshness.Stale(freshness.fetchedAt, clock.now() - freshness.fetchedAt, freshness.sourceTimestamp)
                else -> freshness
            }
            it.copy(freshness = stale, warning = error)
        } ?: DataResult.Failure(error)
    }

    private fun failedTrain(id: TrainRunId, error: DomainFailure): DataResult<TrainRun> {
        // Display fallback (T7.11 final pass): reread the currently accepted
        // cache rather than the object captured before the provider call, so
        // a failure landing after a concurrent success surfaces the freshest
        // accepted truth. Warning bookkeeping and staleness stay identical
        // to [failed]; the failure commits nothing (accepted_generation
        // untouched), and the attached generation, if any, is display
        // evidence only — a warning-backed fallback is never a new
        // monitoring observation (see MonitoringCoordinator).
        val result = failed(id.key, cachedTrain(id), error)
        if (result !is DataResult.Data) return result
        val generation = database.transactionWithResult { acceptedRefreshOrderLocked(id) }.takeIf { it > 0L }
        return result.copy(refreshGeneration = generation)
    }

    /**
     * Local canonical-station resolution (T7.13 corrective pass): observes
     * the existing `stationById` row only. No provider call, no polling —
     * board routes identify the station by id and display this live value.
     */
    override fun observeStation(id: StationId): Flow<Station?> =
        queries.stationById(id.value).asFlow().mapToOneOrNull(dispatcher)
            .map { row -> row?.let { Station(StationId(it.id), it.name) } }
            .flowOn(dispatcher)

    override suspend fun searchStations(query: String, force: Boolean): DataResult<List<Station>> = withContext(dispatcher) {
        val normalized = normalizeStationQuery(query)
        if (normalized.isBlank()) return@withContext DataResult.Data(emptyList(), DataFreshness.Unknown)
        val key = "stations:$normalized"
        flights.run(key) {
            val cached = cached<List<Station>>(key, policy.stationTtl)
            if (!force && cached?.freshness is DataFreshness.Fresh) return@run cached
            val local = queries.searchStations(normalized).executeAsList().map { Station(StationId(it.id), it.name) }
            val fallback = cached ?: local.takeIf { it.isNotEmpty() }?.let { DataResult.Data(it, DataFreshness.Unknown) }
            if (!online()) return@run failed(key, fallback, DomainFailure.OFFLINE)
            when (val result = provider.searchStations(normalized, 30)) {
                is ProviderResult.Success -> {
                    database.transaction {
                        save(key, result.value.map { station(it, result.metadata) }.distinctBy { it.id }, result.metadata)
                    }
                    warnings.update { it - key }
                    cached<List<Station>>(key, policy.stationTtl)!!
                }
                ProviderResult.NotFound -> {
                    val metadata = ProviderMetadata(provider.id, clock.now())
                    save(key, emptyList<Station>(), metadata)
                    cached<List<Station>>(key, policy.stationTtl)!!
                }
                is ProviderResult.Unavailable -> failed(key, fallback, result.cause.domain())
            }
        }
    }

    private fun boardKey(station: Station, kind: BoardKind) = "board:${station.id.value}:$kind"
    override fun observeBoard(station: Station, kind: BoardKind): Flow<DataResult<StationBoard>> {
        val key = boardKey(station, kind)
        return combine(queries.cacheByKey(key).asFlow().mapToOneOrNull(dispatcher), warnings) { _, errors ->
            cached<StationBoard>(key, policy.boardTtl, errors[key]) ?: DataResult.Failure(errors[key] ?: DomainFailure.NOT_FOUND)
        }.flowOn(dispatcher)
    }

    override suspend fun refreshBoard(station: Station, kind: BoardKind, force: Boolean): DataResult<StationBoard> = withContext(dispatcher) {
        val key = boardKey(station, kind)
        flights.run(key) {
            val cached = cached<StationBoard>(key, policy.boardTtl)
            if (!force && cached?.freshness is DataFreshness.Fresh) return@run cached
            if (!online()) return@run failed(key, cached, DomainFailure.OFFLINE)
            val selected = providers.values.firstOrNull { candidate ->
                candidate.capabilities.stationBoards &&
                    queries.externalForStation(station.id.value, candidate.id.value).executeAsOneOrNull() != null
            } ?: return@run failed(key, cached, DomainFailure.UNSUPPORTED)
            val ref = ExternalStationRef(queries.externalForStation(station.id.value, selected.id.value).executeAsOne())
            val result = if (kind == BoardKind.DEPARTURES) selected.departures(ref, clock.now()) else selected.arrivals(ref, clock.now())
            when (result) {
                is ProviderResult.Success -> {
                    database.transaction {
                        save(key, StationBoard(station, kind, result.value.map { summary(it, result.metadata) }), result.metadata)
                    }
                    warnings.update { it - key }
                    cached<StationBoard>(key, policy.boardTtl)!!
                }
                ProviderResult.NotFound -> failed(key, cached, DomainFailure.NOT_FOUND)
                is ProviderResult.Unavailable -> failed(key, cached, result.cause.domain())
            }
        }
    }

    override suspend fun findTrainRuns(number: TrainNumber, date: LocalDate?): DataResult<List<TrainRunSummary>> = withContext(dispatcher) {
        val key = "runs:${number.value}:${date ?: RailwayTime.serviceDate(clock.now())}"
        flights.run(key) {
            val cached = cached<List<TrainRunSummary>>(key, policy.trainTtl)
            if (cached?.freshness is DataFreshness.Fresh) return@run cached
            if (!online()) return@run failed(key, cached, DomainFailure.OFFLINE)
            when (val result = provider.findTrainCandidates(number, date)) {
                is ProviderResult.Success -> {
                    database.transaction {
                        save(key, result.value.map { summary(it, result.metadata) }.distinctBy { it.id }, result.metadata)
                    }
                    cached<List<TrainRunSummary>>(key, policy.trainTtl)!!
                }
                ProviderResult.NotFound -> DataResult.Data(emptyList(), DataFreshness.Unknown)
                is ProviderResult.Unavailable -> failed(key, cached, result.cause.domain())
            }
        }
    }

    /**
     * Acquires the persistence-shared monotonic order token for one refresh
     * invocation (T7.11 corrective pass 2). Must be called before the first
     * network suspension of the refresh: the token identifies invocation
     * order across independent repository/AppGraph instances sharing this
     * database, whose instance-local flights cannot order each other.
     */
    private fun issueRefreshOrderLocked(id: TrainRunId): Long = database.transactionWithResult {
        // One transaction: concurrent issuers serialize, so every
        // invocation observes a distinct monotonic token.
        queries.ensureRefreshOrder(id.key)
        queries.bumpRefreshOrder(id.key)
        queries.refreshOrderIssued(id.key).executeAsOne()
    }

    private fun acceptedRefreshOrderLocked(id: TrainRunId): Long =
        queries.refreshOrderAccepted(id.key).executeAsOneOrNull() ?: 0L

    /**
     * Commits [block] (the train cache write) only when [token] is newer
     * than the currently accepted generation, and records it as accepted —
     * atomically in one transaction. An earlier invocation completing later
     * loses to a newer accepted generation (terminal-vs-terminal included:
     * ordering, not status heuristics, decides); an earlier success while a
     * later request is still pending commits normally and is overwritten when
     * the later response arrives. A failed request never reaches here, so it
     * commits nothing and never blocks the database.
     */
    private fun commitRefreshOrderLocked(id: TrainRunId, token: Long, block: () -> Unit): Boolean =
        database.transactionWithResult {
            queries.acceptRefreshOrder(token, id.key)
            if (queries.refreshOrderWriteCount().executeAsOne() != 1L) return@transactionWithResult false
            block()
            true
        }

    private fun cachedTrain(id: TrainRunId, warning: DomainFailure? = null): DataResult.Data<TrainRun>? = database.transactionWithResult {
        val row = queries.trainById(id.key).executeAsOneOrNull() ?: return@transactionWithResult null
        try {
            val summary = json.decodeFromString<SummaryRecord>(row.summary)
            val run = TrainRun(summary.model(),
                queries.stopsForTrain(id.key).executeAsList().map { json.decodeFromString<TrainStop>(it.payload) },
                summary.position?.model())
            val ttl = if (run.summary.status in setOf(TrainStatus.ARRIVED, TrainStatus.CANCELLED)) policy.completedTrainTtl else policy.trainTtl
            DataResult.Data(run, freshness(row.fetched_at, row.source_timestamp, ttl, warning), warning)
        } catch (_: IllegalArgumentException) {
            database.transaction { queries.deleteStops(id.key); queries.deleteTrain(id.key) }
            null
        }
    }

    override fun observeTrain(id: TrainRunId): Flow<DataResult<TrainRun>> =
        combine(queries.trainById(id.key).asFlow().mapToOneOrNull(dispatcher), warnings) { _, errors ->
            cachedTrain(id, errors[id.key]) ?: DataResult.Failure(errors[id.key] ?: DomainFailure.NOT_FOUND)
        }.flowOn(dispatcher)

    override suspend fun refreshTrain(id: TrainRunId, force: Boolean): DataResult<TrainRun> {
        // Admission ordering (T7.11 final pass): the persistence-shared
        // order token is captured before the first dispatcher hop, mutex
        // acquisition, child-coroutine suspension, network suspension, or any
        // other scheduling boundary that could let a later public invocation
        // obtain an earlier identity. Everything after this line may suspend;
        // nothing before it does. The issuance is one tiny indexed
        // transaction on the calling thread; joiners of one SingleFlight
        // burn an unused token gap and receive the owner's observation with
        // the owner's generation (only the owner's block runs).
        val order = issueRefreshOrderLocked(id)
        return withContext(dispatcher) {
            flights.run(id.key) {
                val cached = instrumentation.span("train.cache_lookup", mapOf("provider" to id.provider.value)) {
                    cachedTrain(id)
                }
                instrumentation.event("train.cache", mapOf(
                    "hit" to (cached != null).toString(),
                    "fresh" to (cached?.freshness is DataFreshness.Fresh).toString(),
                ))
                if (!force && cached?.freshness is DataFreshness.Fresh) {
                    return@run cached.copy(refreshGeneration = database.transactionWithResult { acceptedRefreshOrderLocked(id) })
                }
                if (!online()) return@run failedTrain(id, DomainFailure.OFFLINE)
                val selected = providers[id.provider]?.takeIf { it.capabilities.trainDetail }
                    ?: return@run failedTrain(id, DomainFailure.UNSUPPORTED)
                when (val result = selected.getTrainSnapshot(ProviderTrainRunRef(id.number, id.origin, id.serviceDate))) {
                    is ProviderResult.Success -> {
                        val actual = result.value.train.ref
                        if (result.metadata.providerId != id.provider || actual.number != id.number ||
                            actual.origin != id.origin || actual.serviceDate != id.serviceDate) {
                            return@run failedTrain(id, DomainFailure.INVALID_RESPONSE)
                        }
                        val run = snapshot(result.value, result.metadata)
                        val committed = instrumentation.span("train.persistence_write") {
                            commitRefreshOrderLocked(id, order) {
                                queries.putTrain(id.key, id.provider.value, id.number.value, id.serviceDate.toString(),
                                    json.encodeToString(run.summary.recordWith(run.position)),
                                    result.metadata.fetchedAt.toEpochMilliseconds(),
                                    result.metadata.sourceTimestamp?.toEpochMilliseconds())
                                queries.deleteStops(id.key)
                                run.stops.forEachIndexed { index, stop -> queries.putStop(id.key, index.toLong(), json.encodeToString(stop)) }
                            }
                        }
                        warnings.update { it - id.key }
                        if (committed) {
                            val observed = instrumentation.span("train.persistence_to_observation") { cachedTrain(id) }
                                ?: return@run failedTrain(id, DomainFailure.TEMPORARY)
                            observed.copy(refreshGeneration = order)
                        } else {
                            // Superseded: this invocation is older than the
                            // accepted generation. Its provider response must
                            // never be rebranded as current, so the currently
                            // accepted cache is returned with its generation
                            // instead — the coordinator evaluates truth, not
                            // staleness.
                            val accepted = cachedTrain(id)
                                ?: return@run failedTrain(id, DomainFailure.TEMPORARY)
                            accepted.copy(
                                refreshGeneration = database.transactionWithResult { acceptedRefreshOrderLocked(id) },
                            )
                        }
                    }
                    ProviderResult.NotFound -> failedTrain(id, DomainFailure.NOT_FOUND)
                    is ProviderResult.Unavailable -> failedTrain(id, result.cause.domain())
                }
            }
        }
    }

    override fun observeRecentStations(): Flow<List<Station>> = queries.recentStations().asFlow().mapToList(dispatcher)
        .map { rows -> rows.map { Station(StationId(it.id), it.name) } }
    override suspend fun record(station: Station) {
        // Ticket before the dispatcher hop: the invocation, not the delayed
        // dispatched work, establishes the delete order.
        val ticket = historyGate.issueWriteTicket()
        return withContext(dispatcher) {
            historyGate.write(ticket) {
                database.transaction {
                    queries.recordStation(station.id.value, clock.now().toEpochMilliseconds())
                    queries.pruneHistory()
                }
            }
        }
    }
    override suspend fun removeRecentStation(station: Station): Unit = withContext(dispatcher) {
        // Recency only: the canonical station row and provider mappings stay intact.
        queries.removeRecentStation(station.id.value)
    }
    override suspend fun clearHistory(): Unit = withContext(dispatcher) { queries.clearHistory() }
    override fun observeSearchHistory(): Flow<List<SearchHistoryEntry>> {
        val journeys = queries.journeySearchHistory().asFlow().mapToList(dispatcher).map { rows ->
            rows.mapNotNull { row ->
                searchHistoryEntry(row.id, row.origin_station_id, row.origin_name, row.destination_station_id,
                    row.destination_name, row.requested_at, row.search_mode, row.submitted_at)
            }
        }
        val trains = queries.trainSearchHistory().asFlow().mapToList(dispatcher).map { rows ->
            rows.mapNotNull { row ->
                trainSearchHistoryEntry(row.id, row.train_number, row.service_date, row.origin_station_id,
                    row.origin_name, row.operator_name, row.destination_name, row.submitted_at)
            }
        }
        // Journey submissions come first on equal timestamps for a stable order.
        return combine(journeys, trains) { journeyEntries, trainEntries ->
            (journeyEntries + trainEntries).sortedByDescending { it.submittedAt }
        }.flowOn(dispatcher)
    }
    override suspend fun recordSearch(request: JourneySearchRequest): JourneySearchHistoryEntry {
        // Ticket before the dispatcher hop: the invocation, not the delayed
        // dispatched work, establishes the delete order.
        val ticket = historyGate.issueWriteTicket()
        return withContext(dispatcher) {
            val entry = JourneySearchHistoryEntry(
                SearchHistoryEntryId(Uuid.random().toString()),
                request.origin,
                request.destination,
                request.at,
                request.mode,
                clock.now(),
            )
            historyGate.write(ticket) {
                database.transaction {
                    // Journey stations may be journey-only cache identities rather than
                    // realtime station rows; resolve them into the shared station table
                    // first so the history row never references a missing station or a
                    // provider-specific format, and so the write stays atomic.
                    ensureStation(request.origin)
                    ensureStation(request.destination)
                    queries.insertJourneySearchHistory(
                        entry.id.value,
                        request.origin.id.value,
                        request.destination.id.value,
                        request.origin.name,
                        request.destination.name,
                        request.at.toEpochMilliseconds(),
                        request.mode.name,
                        entry.submittedAt.toEpochMilliseconds(),
                    )
                }
            }
            entry
        }
    }
    override suspend fun recordTrainSearch(
        number: TrainNumber,
        serviceDate: LocalDate?,
        expected: TrainLookupIntent?,
    ): TrainSearchHistoryEntry {
        // Ticket before the dispatcher hop: the invocation, not the delayed
        // dispatched work, establishes the delete order.
        val ticket = historyGate.issueWriteTicket()
        return withContext(dispatcher) {
            // Origin id and name are independent discriminators mirroring
            // TrainLookupIntent: either may be known without the other, and a
            // number-only search keeps both null rather than inventing a route.
            val originId = expected?.originId?.takeIf { it.value.isNotBlank() }
            val originName = expected?.originName?.takeIf { it.isNotBlank() }
            val operator = expected?.operator?.takeIf { it.name.isNotBlank() }
            val entry = TrainSearchHistoryEntry(
                SearchHistoryEntryId(Uuid.random().toString()),
                number,
                serviceDate,
                originId,
                originName,
                operator,
                destinationName = null,
                clock.now(),
            )
            historyGate.write(ticket) {
                database.transaction {
                    // Only a fully known origin becomes a shared station row; a bare
                    // id or name is still stored as discrimination without fabricating
                    // the missing half. The stored origin id is an opaque
                    // discriminator and needs no matching station row.
                    if (originId != null && originName != null) ensureStation(Station(originId, originName))
                    queries.insertTrainSearchHistory(
                        entry.id.value,
                        number.value,
                        serviceDate?.toString(),
                        originId?.value,
                        originName,
                        operator?.name,
                        null,
                        entry.submittedAt.toEpochMilliseconds(),
                    )
                }
            }
            entry
        }
    }
    override suspend fun lookupSearch(id: SearchHistoryEntryId): SearchHistoryEntry? = withContext(dispatcher) {
        queries.journeySearchHistoryById(id.value).executeAsOneOrNull()?.let { row ->
            searchHistoryEntry(row.id, row.origin_station_id, row.origin_name, row.destination_station_id,
                row.destination_name, row.requested_at, row.search_mode, row.submitted_at)
        } ?: queries.trainSearchHistoryById(id.value).executeAsOneOrNull()?.let { row ->
            trainSearchHistoryEntry(row.id, row.train_number, row.service_date, row.origin_station_id,
                row.origin_name, row.operator_name, row.destination_name, row.submitted_at)
        }
    }
    override suspend fun removeSearch(id: SearchHistoryEntryId): Unit = withContext(dispatcher) {
        database.transaction {
            queries.deleteJourneySearchHistory(id.value)
            queries.deleteTrainSearchHistory(id.value)
        }
    }
    override suspend fun clearSearchHistory(): Unit = withContext(dispatcher) {
        // Search entries only: station recency has its own clearHistory operation.
        database.transaction {
            queries.clearJourneySearchHistory()
            queries.clearTrainSearchHistory()
        }
    }
    override suspend fun clearSearchHistoryAndRecency(): Unit = withContext(dispatcher) {
        // One atomic Settings deletion: journey entries, train entries and
        // user-visible station recency together. Canonical stations, mappings,
        // favorites, monitors and settings are untouched. In-flight personal
        // writes ordered before this deletion are dropped by the gate instead
        // of resurrecting afterwards; only later invocations count as new
        // post-deletion actions.
        historyGate.delete {
            database.transaction {
                queries.clearJourneySearchHistory()
                queries.clearTrainSearchHistory()
                queries.clearHistory()
            }
        }
    }
    override fun observeFavoriteStations(): Flow<List<Station>> = queries.favoriteStations().asFlow().mapToList(dispatcher)
        .map { rows -> rows.map { Station(StationId(it.id), it.name) } }
    override suspend fun setFavorite(station: Station, favorite: Boolean): Unit {
        // Ticket before the dispatcher hop: the invocation, not the delayed
        // dispatched work, establishes the delete order.
        val ticket = favoritesGate.issueWriteTicket()
        return withContext(dispatcher) {
            // Saves are delete-ordered through the gate; pure removals converge
            // with a concurrent clear on their own and keep their exact legacy path.
            if (favorite) {
                favoritesGate.write(ticket) {
                    database.transaction {
                        val stationId = station.id.value
                        if (queries.favoriteStationById(stationId).executeAsOneOrNull() == null) {
                            if (queries.stationById(stationId).executeAsOneOrNull() == null) {
                                queries.putStation(
                                    stationId,
                                    station.name,
                                    station.normalizedName,
                                    clock.now().toEpochMilliseconds(),
                                )
                            }
                            queries.addFavorite(stationId)
                        }
                    }
                }
            } else {
                database.transaction {
                    val stationId = station.id.value
                    if (queries.favoriteStationById(stationId).executeAsOneOrNull() != null) {
                        queries.removeFavorite(stationId)
                    }
                }
            }
        }
    }
    override fun observeFavoriteRoutes(): Flow<List<FavoriteRoute>> =
        queries.favoriteRoutes().asFlow().mapToList(dispatcher).map { rows ->
            rows.map { row ->
                FavoriteRoute(
                    FavoriteRouteId(row.id),
                    Station(StationId(row.origin_id), row.origin_name),
                    Station(StationId(row.destination_id), row.destination_name),
                )
            }
        }
    override suspend fun setFavorite(route: FavoriteRoute, favorite: Boolean): Unit {
        // Ticket before the dispatcher hop: the invocation, not the delayed
        // dispatched work, establishes the delete order.
        val ticket = favoritesGate.issueWriteTicket()
        return withContext(dispatcher) {
            // Saves are delete-ordered through the gate; pure removals converge
            // with a concurrent clear on their own and keep their exact legacy path.
            if (favorite) {
                favoritesGate.write(ticket) {
                    database.transaction {
                        val originId = route.origin.id.value
                        val destinationId = route.destination.id.value
                        if (queries.favoriteRouteByEndpoints(originId, destinationId).executeAsOneOrNull() == null) {
                            ensureStation(route.origin)
                            ensureStation(route.destination)
                            queries.addFavoriteRoute(route.id.value, originId, destinationId)
                        }
                    }
                }
            } else {
                database.transaction {
                    val originId = route.origin.id.value
                    val destinationId = route.destination.id.value
                    if (queries.favoriteRouteByEndpoints(originId, destinationId).executeAsOneOrNull() != null) {
                        queries.removeFavoriteRoute(originId, destinationId)
                    }
                }
            }
        }
    }

    override fun observeFavoriteTrains(): Flow<List<FavoriteTrain>> =
        queries.favoriteTrains().asFlow().mapToList(dispatcher).map { rows ->
            rows.map { row ->
                // Pre-fix rows carry a null stable origin identity with the
                // display name preserved; the model keeps their name-derived
                // identities verifiable without fabricating a StationId.
                FavoriteTrain(
                    FavoriteTrainId(row.id),
                    TrainNumber(row.train_number),
                    row.origin_station_id?.let(::StationId),
                    row.operator_name?.let(::Operator),
                    row.origin_name,
                    row.destination_name,
                )
            }
        }
    override suspend fun setFavorite(train: FavoriteTrain, favorite: Boolean): Unit {
        // Ticket before the dispatcher hop: the invocation, not the delayed
        // dispatched work, establishes the delete order.
        val ticket = favoritesGate.issueWriteTicket()
        return withContext(dispatcher) {
            // Saves are delete-ordered through the gate; pure removals converge
            // with a concurrent clear on their own and keep their exact legacy path.
            if (favorite) {
                favoritesGate.write(ticket) {
                    database.transaction {
                        if (queries.favoriteTrainById(train.id.value).executeAsOneOrNull() == null) {
                            queries.addFavoriteTrain(
                                train.id.value,
                                train.number.value,
                                train.originId?.value,
                                train.originName,
                                train.operator?.name,
                                train.destinationName,
                            )
                        }
                    }
                }
            } else {
                database.transaction {
                    if (queries.favoriteTrainById(train.id.value).executeAsOneOrNull() != null) {
                        queries.removeFavoriteTrain(train.id.value)
                    }
                }
            }
        }
    }
    override suspend fun clearAllFavorites(): Unit = withContext(dispatcher) {
        // One atomic Settings deletion: station, route and recurring-train
        // favorites together. Search history, recency, canonical stations,
        // mappings, monitors and settings are untouched. In-flight saves
        // ordered before this deletion are dropped by the gate instead of
        // resurrecting afterwards; only later invocations count as new
        // post-deletion actions.
        favoritesGate.delete {
            database.transaction {
                queries.clearFavoriteStations()
                queries.clearFavoriteRoutes()
                queries.clearFavoriteTrains()
            }
        }
    }

    private fun trainSearchHistoryEntry(
        id: String,
        number: String,
        serviceDate: String?,
        originId: String?,
        originName: String?,
        operatorName: String?,
        destinationName: String?,
        submittedAt: Long,
    ): TrainSearchHistoryEntry? {
        // A corrupt train number can never become history; an unreadable
        // service date degrades to an unknown date instead of hiding the entry.
        // Origin id and name round-trip independently so name-only legacy
        // discrimination is never lost.
        if (number.isBlank() || !number.all(Char::isDigit)) return null
        val date = serviceDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        return TrainSearchHistoryEntry(
            SearchHistoryEntryId(id),
            TrainNumber(number),
            date,
            originId?.takeIf { it.isNotBlank() }?.let(::StationId),
            originName?.takeIf { it.isNotBlank() },
            operatorName?.takeIf { it.isNotBlank() }?.let(::Operator),
            destinationName?.takeIf { it.isNotBlank() },
            Instant.fromEpochMilliseconds(submittedAt),
        )
    }

    private fun searchHistoryEntry(
        id: String,
        originId: String,
        originName: String,
        destinationId: String,
        destinationName: String,
        requestedAt: Long,
        mode: String,
        submittedAt: Long,
    ): JourneySearchHistoryEntry? {
        // Unknown modes come only from corrupt or future-version rows; they
        // must not break observation of the remaining history.
        val searchMode = JourneySearchMode.entries.firstOrNull { it.name == mode } ?: return null
        return JourneySearchHistoryEntry(
            SearchHistoryEntryId(id),
            Station(StationId(originId), originName),
            Station(StationId(destinationId), destinationName),
            Instant.fromEpochMilliseconds(requestedAt),
            searchMode,
            Instant.fromEpochMilliseconds(submittedAt),
        )
    }

    private fun ensureStation(station: Station) {
        if (queries.stationById(station.id.value).executeAsOneOrNull() == null) {
            queries.putStation(
                station.id.value,
                station.name,
                station.normalizedName,
                clock.now().toEpochMilliseconds(),
            )
        }
    }
}

private fun ProviderFailure.domain() = when (this) {
    ProviderFailure.PROTOCOL, ProviderFailure.PARSING -> DomainFailure.INVALID_RESPONSE
    ProviderFailure.UNSUPPORTED -> DomainFailure.UNSUPPORTED
    else -> DomainFailure.TEMPORARY
}

/**
 * Delete-wins ordering for one personal-data domain (T7.8).
 *
 * Writes capture a ticket on invocation; a deletion bumps the generation
 * when it completes, while still holding the mutex. A write commits only
 * when no deletion completed between its invocation and its commit: a write
 * that began before a completed deletion therefore observes a stale ticket
 * once it acquires the mutex and fails with
 * [PersonalDataWriteSupersededException] instead of committing past the
 * deletion and resurrecting deleted data — including a write whose
 * invocation raced with the deletion's flight, since the bump lands after
 * all of the deletion's work. Only a write invoked after the deletion
 * completed observes the fresh ticket and proceeds as a new post-deletion
 * user action. Pure removals never resurrect anything, so they bypass the
 * gate and keep their legacy path.
 *
 * The mutex also serializes each domain's transactions, so a deletion waits
 * for an in-flight write's transaction and then removes its rows: the user
 * never observes a partially committed deletion either way.
 *
 * Instances sharing one database must share one gate per domain (injected
 * through the repository constructor): ordering is a property of the
 * persisted data, not of the repository object, so two instances writing the
 * same tables through different gates could otherwise interleave a save past
 * a deletion. Production uses a single repository instance; tests share
 * gates to stage deterministic delete-vs-write interleavings across
 * dispatchers.
 */
class DeleteWinsGate {
    private val mutex = Mutex()

    @Volatile
    private var generation = 0L

    /**
     * Captures the caller's delete-order identity. Repository methods must
     * call this synchronously at public invocation time, before the first
     * dispatcher hop or suspension: a ticket captured only after a hop can
     * already postdate a completed deletion and would wrongly admit the
     * write as a post-deletion action.
     */
    fun issueWriteTicket(): Long = generation

    suspend fun <T> write(ticket: Long, block: suspend () -> T): T {
        mutex.lock()
        try {
            if (ticket != generation) throw PersonalDataWriteSupersededException()
            return block()
        } finally {
            mutex.unlock()
        }
    }

    suspend fun <T> write(block: suspend () -> T): T = write(issueWriteTicket(), block)

    suspend fun delete(block: suspend () -> Unit) {
        mutex.lock()
        try {
            block()
            generation++
        } finally {
            mutex.unlock()
        }
    }
}

private class SingleFlight(private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private val running = mutableMapOf<String, Deferred<*>>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> run(key: String, block: suspend () -> T): T {
        val deferred = mutex.withLock {
            (running[key] as? Deferred<T>) ?: scope.async(start = CoroutineStart.LAZY) {
                try { block() } finally { mutex.withLock { running.remove(key) } }
            }.also { running[key] = it; it.start() }
        }
        return deferred.await()
    }
}
