package it.danielebufarini.trenify.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import it.danielebufarini.trenify.core.domain.CurrentStrikeWindow
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.EvaluateStrikeNotification
import it.danielebufarini.trenify.core.domain.StrikeChangeEvent
import it.danielebufarini.trenify.core.domain.StrikeChangeKind
import it.danielebufarini.trenify.core.domain.StrikeIntervalCoverage
import it.danielebufarini.trenify.core.domain.StrikeNotificationRepository
import it.danielebufarini.trenify.core.domain.StrikePolicy
import it.danielebufarini.trenify.core.domain.StrikeRefresh
import it.danielebufarini.trenify.core.domain.StrikeRepository
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.ProviderId
import it.danielebufarini.trenify.core.model.Strike
import it.danielebufarini.trenify.core.model.StrikeGeography
import it.danielebufarini.trenify.core.model.StrikeId
import it.danielebufarini.trenify.core.model.StrikeRelevance
import it.danielebufarini.trenify.core.model.StrikeSource
import it.danielebufarini.trenify.core.model.StrikeStatus
import it.danielebufarini.trenify.core.model.strikeIntervalsOverlap
import it.danielebufarini.trenify.core.provider.api.ProviderFailure
import it.danielebufarini.trenify.core.provider.api.ProviderMetadata
import it.danielebufarini.trenify.core.provider.api.ProviderResult
import it.danielebufarini.trenify.core.provider.api.ProviderStrike
import it.danielebufarini.trenify.core.provider.api.StrikeProvider
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

class SqlDelightStrikeRepository(
    private val database: TrenifyDatabase,
    private val provider: StrikeProvider,
    private val scope: CoroutineScope,
    private val online: () -> Boolean = { true },
    private val clock: Clock = Clock.System,
    private val policy: StrikePolicy = StrikePolicy(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val notificationPolicy: EvaluateStrikeNotification = EvaluateStrikeNotification(),
) : StrikeRepository, StrikeNotificationRepository {
    private val queries = database.realtimeQueries
    private val json = Json
    private val flightMutex = Mutex()
    private val fetchMutex = Mutex()
    /**
     * Single-flight sharing for overlapping refreshes, mirroring the
     * scope-owned flight in [SqlDelightJourneyRepository]: the shared fetch
     * runs as a child of the repository [scope], never of a caller, so
     * cancelling one waiter only cancels its own await while the fetch
     * continues for the remaining waiters. Entries live only for the fetch
     * duration; sequential forced calls still fetch each time.
     *
     * The key is the semantic refresh request, in two namespaces that never
     * collide. A current-window refresh is keyed by its policy span plus the
     * documented [StrikePolicy.refreshBucket] quantum of its single captured
     * instant, so concurrent logical current refreshes share one flight even
     * when independent clock readings differ by milliseconds, while
     * temporally different windows land in different quanta. An explicit
     * custom range is keyed by its exact bounds, so equal-duration or even
     * bound-identical custom ranges never join a current-window flight.
     *
     * Truthful sharing under interval-specific coverage (T7.12 final pass):
     * the shared operation never fetches only the first caller's bounds.
     * Every flight in the current-window namespace fetches the deterministic
     * canonical superset for its bucket ([currentFlightScope]), which
     * contains every caller window that can share that bucket, and each
     * waiter projects the shared outcome back onto its own requested
     * interval ([projectOntoCaller]) — strikes filtered to its bounds,
     * coverage re-expressed for its bounds, freshness re-read for its
     * bounds. No caller is ever marked covered by another caller's
     * interval, while Home, Alerts and the coordinator still share one
     * provider fetch per logical current refresh.
     */
    private data class SharedStrikeFlight(
        val key: String,
        /** Actual fetch bounds: the canonical superset for current-window flights, exact bounds otherwise. */
        val from: Instant,
        val to: Instant,
        /** Exact requested bounds of every joined current-window caller; empty for explicit ranges. */
        val joiners: MutableList<StrikeRequestKey>,
        val deferred: Deferred<DataResult<StrikeRefresh>>,
    )
    private val inFlightRefreshes = mutableMapOf<String, SharedStrikeFlight>()
    /**
     * Interval/request-scoped failure state (T7.12 corrective pass,
     * Blocker 2): a failure is recorded against the exact requested bounds
     * that failed, observers of those exact bounds surface it, and only a
     * successful refresh of those exact bounds clears it. A failure on
     * disjoint interval B never contaminates interval A, and a later
     * success on A never clears B's still-relevant failure. Overlapping or
     * containing (but non-identical) requests neither inherit nor clear
     * each other's failures; each uncovered/stale interval triggers its own
     * targeted fetch. The one exception is a shared current-window flight
     * (T7.12 final pass): its canonical superset fetch actually covers
     * every joined caller, so shared success clears exactly the joined
     * request keys and shared failure records against exactly those keys
     * (see [finishSharedFetch]). Transient failures stay in memory and are
     * never persisted.
     */
    private data class StrikeRequestKey(val from: Instant, val to: Instant)
    private val failures = MutableStateFlow<Map<StrikeRequestKey, DomainFailure>>(emptyMap())

    /**
     * Canonical scope of one logical current refresh: the flight key plus
     * the deterministic superset fetch bounds for its bucket. A caller
     * captured anywhere inside `[bucket, bucket + cacheTtl)` requests
     * `[now - historyWindow, now + futureWindow]`, so `[bucket -
     * historyWindow, bucket + cacheTtl + futureWindow]` contains every
     * window that can share this bucket. The provider operation actually
     * covers these bounds, hence the persisted coverage row does too, and
     * every joined caller finds its own interval contained and fresh.
     */
    private fun currentFlightScope(window: CurrentStrikeWindow): Triple<String, Instant, Instant> {
        val bucket = policy.refreshBucket(window.now)
        val spanMs = (window.to - window.from).inWholeMilliseconds
        val key = "strike-current:$spanMs:${bucket.toEpochMilliseconds()}"
        val from = bucket - policy.historyWindow
        val to = bucket + policy.cacheTtl + policy.futureWindow
        return Triple(key, from, to)
    }

    private fun explicitFlightKey(from: Instant, to: Instant): String =
        "strike-explicit:${from.toEpochMilliseconds()}:${to.toEpochMilliseconds()}"

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeStrikes(
        from: Instant,
        to: Instant,
        includeRevoked: Boolean,
    ): Flow<DataResult<List<Strike>>> {
        require(to > from)
        val key = StrikeRequestKey(from, to)
        val upstream = combine(
            queries.allStrikes(mapper = ::strike).asFlow(),
            queries.strikeSyncState().asFlow(),
            queries.allStrikeCoverages().asFlow(),
            failures,
        ) { _, _, _, _ -> }
        // The local expiry ticker (Blocker 4) restarts on every upstream
        // change, so newly persisted strikes always reschedule the next
        // transition and no stale schedule can outlive its state.
        return upstream.flatMapLatest { expiryTicks(from, to) }.map {
            val currentWarning = failures.value[key]
            cached(from, to, includeRevoked, currentWarning)
                ?: DataResult.Failure(currentWarning ?: DomainFailure.NOT_FOUND)
        }.distinctUntilChanged().flowOn(dispatcher)
    }

    /**
     * Local reactive-expiry signal (T7.12 corrective pass, Blocker 4):
     * emits immediately, then once at each upcoming strike-end transition
     * touching the observed window, then completes until upstream state
     * changes. Local clock only: zero provider calls, no polling loop, no
     * per-card refresh, no writes. One instance runs per active observer
     * and dies with its collection; identical re-reads are suppressed
     * downstream by distinctUntilChanged.
     */
    private fun expiryTicks(from: Instant, to: Instant): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            val now = clock.now()
            val next = queries.strikeEndsOverlapping(
                to_epoch_ms = to.toEpochMilliseconds(),
                from_epoch_ms = from.toEpochMilliseconds(),
            ).executeAsList().map(Instant::fromEpochMilliseconds).filter { it > now }.minOrNull()
            if (next == null) return@flow
            delay((next - now).coerceAtLeast(Duration.ZERO))
            // Progress guard: continue only if the clock actually reached
            // the scheduled transition. A frozen test clock under unbounded
            // advancement (or a backward clock jump) would otherwise
            // reschedule the same wait forever; completing here is safe
            // because any future state change restarts this ticker via
            // upstream, identical re-reads are suppressed downstream, and
            // nothing time-based could have changed without clock movement.
            // Real monotonic clocks always progress here, so multi-expiry
            // behavior in production is unchanged.
            if (clock.now() < next) return@flow
        }
    }

    override suspend fun refresh(from: Instant, to: Instant, force: Boolean): DataResult<StrikeRefresh> {
        require(to > from)
        return fly(explicitFlightKey(from, to), from, to, force)
    }

    override fun observeStrikes(
        window: CurrentStrikeWindow,
        includeRevoked: Boolean,
    ): Flow<DataResult<List<Strike>>> = observeStrikes(window.from, window.to, includeRevoked)

    override suspend fun refresh(window: CurrentStrikeWindow, force: Boolean): DataResult<StrikeRefresh> {
        val (key, fetchFrom, fetchTo) = currentFlightScope(window)
        return fly(key, fetchFrom, fetchTo, force, StrikeRequestKey(window.from, window.to))
    }

    private suspend fun fly(
        key: String,
        from: Instant,
        to: Instant,
        force: Boolean,
        joiner: StrikeRequestKey? = null,
    ): DataResult<StrikeRefresh> {
        val flight = flightMutex.withLock {
            // The freshness fast path is always per caller: only the
            // caller's own requested interval, covered and young, skips the
            // fetch — never another caller's freshness.
            val cached = cached(joiner?.from ?: from, joiner?.to ?: to, includeRevoked = true)
            if (!force && cached?.freshness is DataFreshness.Fresh) return cached.refresh()
            val existing = inFlightRefreshes[key]
            if (existing != null) {
                if (joiner != null && existing.joiners.none { it == joiner }) existing.joiners += joiner
                existing
            } else {
                val deferred = scope.async(dispatcher, start = CoroutineStart.LAZY) {
                    runSharedFetch(key, from, to)
                }
                SharedStrikeFlight(
                    key,
                    from,
                    to,
                    mutableListOf<StrikeRequestKey>().apply { if (joiner != null) add(joiner) },
                    deferred,
                ).also { inFlightRefreshes[key] = it; deferred.start() }
            }
        }
        // A waiter only awaits the repository-owned fetch: its own
        // cancellation never disturbs the shared fetch or the other waiters,
        // while provider failures reach every waiter consistently. A joined
        // current-window caller projects the shared outcome onto its own
        // requested interval; explicit ranges already fetch exactly.
        val outcome = flight.deferred.await()
        return if (joiner != null) projectOntoCaller(outcome, joiner) else outcome
    }

    /**
     * Repository-owned shared fetch: one provider call per logical flight,
     * then failure-state settlement for exactly the joined callers (see
     * [finishSharedFetch]) before any waiter resumes.
     */
    private suspend fun runSharedFetch(key: String, from: Instant, to: Instant): DataResult<StrikeRefresh> {
        val outcome = try {
            // Forced fetches stay serialized as before; only the map lookup
            // above is brief, so joiners never block on a fetch.
            fetchMutex.withLock { fetchAndApply(from, to) }
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                flightMutex.withLock { inFlightRefreshes.remove(key) }
            }
            throw t
        }
        withContext(NonCancellable) {
            flightMutex.withLock { finishSharedFetch(key, from, to, outcome) }
        }
        return outcome
    }

    /**
     * Settles interval failure state for one completed shared flight.
     * Success clears exactly the fetch bounds plus every joined caller's
     * exact requested bounds — each joined interval was actually covered,
     * so its failure evidence is spent. Failure records the shared error
     * against every joined caller's exact bounds, so each joined observer
     * surfaces it, while the transient fetch bounds are dropped (no
     * observer can hold them) and unrelated intervals — including disjoint
     * custom ranges — are never touched. Registration and settlement both
     * hold [flightMutex], so a caller either joins before settlement and
     * is included, or arrives after removal and starts its own flight.
     */
    private fun finishSharedFetch(
        key: String,
        from: Instant,
        to: Instant,
        outcome: DataResult<StrikeRefresh>,
    ) {
        val flight = inFlightRefreshes.remove(key) ?: return
        // Explicit ranges fetch exactly and settle their own key inside
        // fetchAndApply/applySnapshot: with no joined callers there is
        // nothing to project onto and the record must stand untouched.
        if (flight.joiners.isEmpty()) return
        val fetchKey = StrikeRequestKey(from, to)
        val error = when (outcome) {
            is DataResult.Data -> outcome.warning
            is DataResult.Failure -> outcome.error
        }
        failures.update { current ->
            if (error == null) {
                current - fetchKey - flight.joiners.toSet()
            } else {
                (current - fetchKey) + flight.joiners.associateWith { error }
            }
        }
    }

    /**
     * Projects one shared current-window outcome onto a single caller's
     * requested interval: strikes re-read for exactly its bounds (the
     * shared fetch covered a superset, so this is a restriction, never an
     * extension), coverage re-expressed for its bounds only when the
     * shared operation was authoritative, freshness re-read for its bounds
     * including its own recorded failure warning. The operation-scoped
     * change list is shared read-only; it has no per-caller consumers.
     */
    private fun projectOntoCaller(
        outcome: DataResult<StrikeRefresh>,
        caller: StrikeRequestKey,
    ): DataResult<StrikeRefresh> {
        if (outcome !is DataResult.Data) return outcome
        // The shared outcome's warning travels with the projection: a
        // joined caller must see the failure its flight suffered, re-read
        // as stale truth about its own interval — never laundered into a
        // clean Fresh by re-reading without the warning.
        val view = cached(caller.from, caller.to, includeRevoked = true, currentWarning = outcome.warning)
            ?: return DataResult.Failure(outcome.warning ?: DomainFailure.NOT_FOUND)
        val coverage = outcome.value.coverage?.let { StrikeIntervalCoverage(caller.from, caller.to, it.fetchedAt) }
        return DataResult.Data(StrikeRefresh(view.value, outcome.value.changes, coverage), view.freshness, view.warning)
    }

    private suspend fun fetchAndApply(from: Instant, to: Instant): DataResult<StrikeRefresh> {
        val cached = cached(from, to, includeRevoked = true)
        if (!online()) return failed(from, to, cached?.refresh(), DomainFailure.OFFLINE)
        return when (val result = provider.getStrikes(from, to, includeRevoked = true)) {
            // Authoritative absence (Blocker 3): a coverage row that
            // certifies an interval as fresh/empty is established only when
            // the response is parse-complete AND the provider contract says
            // absence for the requested interval is authoritative. The same
            // gate applies to absence-based revocation inside applySnapshot.
            // Decoded records are still upserted either way: presence is
            // evidence even when absence is not.
            is ProviderResult.Success -> applySnapshot(
                from,
                to,
                result.value,
                result.metadata,
                result.complete && provider.suppliesCompleteSnapshots,
            )
            ProviderResult.NotFound -> applySnapshot(
                from,
                to,
                emptyList(),
                ProviderMetadata(provider.id, clock.now()),
                provider.suppliesCompleteSnapshots,
            )
            is ProviderResult.Unavailable -> failed(from, to, cached?.refresh(), result.cause.domain())
        }
    }

    override fun observeNotificationsEnabled(): Flow<Boolean> =
        queries.strikeNotificationsEnabled().asFlow().mapToOneOrNull(dispatcher)
            .map { it != null && it != 0L }
            .distinctUntilChanged()

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        database.transaction {
            queries.setStrikeNotificationsEnabled(enabled.long)
            if (!enabled) queries.dismissPendingStrikeNotifications(clock.now().toEpochMilliseconds())
        }
    }

    override suspend fun pendingNotifications(): List<StrikeChangeEvent> = database.transactionWithResult {
        queries.pendingStrikeNotificationEvents().executeAsList()
            .mapNotNull { event ->
                queries.strikeById(event.strike_id, mapper = ::strike).executeAsOneOrNull()?.let { strike ->
                    StrikeChangeKind.entries.firstOrNull { it.name == event.event_type }
                        ?.let { StrikeChangeEvent(strike, it) }
                }
            }
            .associateBy { it.strike.id }
            .values
            .sortedBy { it.strike.start }
    }

    override suspend fun claimNotification(event: StrikeChangeEvent, emittedAt: Instant): Boolean =
        database.transactionWithResult {
            val stored = queries.strikeNotificationEventByKey(event.fingerprint).executeAsOneOrNull()
                ?: return@transactionWithResult false
            if (stored.notified_at_epoch_ms != null) return@transactionWithResult false
            queries.markStrikeNotificationsDelivered(emittedAt.toEpochMilliseconds(), event.strike.id.value)
            true
        }

    private fun applySnapshot(
        from: Instant,
        to: Instant,
        values: List<ProviderStrike>,
        metadata: ProviderMetadata,
        authoritative: Boolean,
    ): DataResult<StrikeRefresh> {
        if (metadata.providerId != provider.id) {
            return failed(from, to, cached(from, to, true)?.refresh(), DomainFailure.INVALID_RESPONSE)
        }
        val previous = queries.allStrikes(mapper = ::strike).executeAsList().associateBy { it.id }
        val normalized = values.map { normalize(it, metadata.providerId) }.distinctBy { it.id }.map { candidate ->
            val existing = previous[candidate.id]
            candidate.copy(status = candidate.mergedStatus(existing, metadata.fetchedAt))
        }
        val ids = normalized.mapTo(mutableSetOf()) { it.id }
        // Absence-based revocation is allowed only after a successfully
        // authoritative applicable snapshot (or explicit revocation
        // evidence, which arrives as a REVOKED record inside [values] and
        // is stored above regardless). A partial or non-authoritative
        // snapshot must not revoke cached strikes merely because some
        // source records were skipped or absence is not meaningful.
        // Expiry is wall-clock truth, not absence evidence, so ended
        // strikes still complete on any snapshot. Scope uses the shared
        // inclusive strikeIntervalsOverlap definition.
        val reconciled = previous.values.filter { strike ->
            strike.source.provider == provider.id && strike.id !in ids &&
                strikeIntervalsOverlap(strike.start, strike.end, from, to) &&
                strike.status !in setOf(StrikeStatus.REVOKED, StrikeStatus.COMPLETED)
        }.mapNotNull { strike ->
            when {
                strike.end <= metadata.fetchedAt -> strike.copy(status = StrikeStatus.COMPLETED)
                authoritative -> strike.copy(status = StrikeStatus.REVOKED)
                else -> null
            }
        }
        val replacements = (normalized + reconciled).distinctBy { it.id }
        val changes = replacements.mapNotNull { notificationPolicy(previous[it.id], it, metadata.fetchedAt) }
        // Only an authoritative snapshot establishes coverage for the
        // requested interval: anything else still upserts what it decoded
        // (including explicit revocations) but certifies nothing about the
        // rest.
        val coverage = if (authoritative) {
            StrikeIntervalCoverage(from, to, metadata.fetchedAt)
        } else {
            null
        }
        database.transaction {
            replacements.forEach { value -> put(value, metadata.fetchedAt) }
            queries.putStrikeSyncState(
                metadata.fetchedAt.toEpochMilliseconds(),
                metadata.sourceTimestamp?.toEpochMilliseconds(),
            )
            coverage?.let {
                queries.putStrikeCoverage(
                    it.from.toEpochMilliseconds(),
                    it.to.toEpochMilliseconds(),
                    it.fetchedAt.toEpochMilliseconds(),
                    metadata.sourceTimestamp?.toEpochMilliseconds(),
                )
                queries.pruneStrikeCoverages((metadata.fetchedAt - policy.historyWindow).toEpochMilliseconds())
            }
            if (notificationsEnabled()) {
                changes.forEach { event ->
                    queries.putStrikeNotificationEvent(
                        event.fingerprint,
                        event.strike.id.value,
                        event.kind.name,
                        metadata.fetchedAt.toEpochMilliseconds(),
                    )
                }
            }
        }
        failures.update { it - StrikeRequestKey(from, to) }
        val result = requireNotNull(cached(from, to, includeRevoked = true))
        return DataResult.Data(StrikeRefresh(result.value, changes, coverage), result.freshness)
    }

    private fun put(strike: Strike, fetchedAt: Instant) {
        queries.putStrike(
            strike.id.value,
            strike.source.provider.value,
            strike.externalId,
            strike.start.toEpochMilliseconds(),
            strike.end.toEpochMilliseconds(),
            strike.status.name,
            strike.sector,
            strike.geography.relevance.name,
            json.encodeToString(strike.geography.regions),
            json.encodeToString(strike.geography.provinces),
            json.encodeToString(strike.unions),
            json.encodeToString(strike.operators.map { it.name }),
            strike.workforce,
            strike.mode,
            strike.notes,
            strike.source.label,
            strike.source.url,
            strike.sourceUpdatedAt?.toEpochMilliseconds(),
            fetchedAt.toEpochMilliseconds(),
            strike.contentFingerprint,
        )
        strike.externalId?.let { queries.putStrikeExternalId(strike.source.provider.value, it, strike.id.value) }
    }

    private fun cached(
        from: Instant,
        to: Instant,
        includeRevoked: Boolean,
        currentWarning: DomainFailure? = null,
    ): DataResult.Data<List<Strike>>? {
        val now = clock.now()
        // Local effective applicability (Blocker 4): a persisted strike
        // whose end has passed is read as COMPLETED without any provider
        // contact and without writing anything — no notification rows, no
        // monitor events, no status mutation on trains. The expiry ticker
        // in observeStrikes re-reads exactly at each transition so one open
        // observer reacts while the screen stays open.
        val values = queries.strikesOverlapping(
            to_epoch_ms = to.toEpochMilliseconds(),
            from_epoch_ms = from.toEpochMilliseconds(),
            mapper = ::strike,
        ).executeAsList().filter { includeRevoked || it.status != StrikeStatus.REVOKED }
            .map { strike ->
                if (strike.status != StrikeStatus.REVOKED && strike.status != StrikeStatus.COMPLETED && strike.end <= now) {
                    strike.copy(status = StrikeStatus.COMPLETED)
                } else {
                    strike
                }
            }
        // Freshness is per requested interval (T7.12-A): only a containing
        // complete coverage row younger than the cache TTL certifies this
        // interval as fresh. A disjoint never-fetched interval has no
        // containing row and stays unknown — however recently another
        // interval refreshed — so callers trigger a targeted refresh instead
        // of reusing unrelated freshness as evidence of no strikes. A
        // containing but expired row is stale truth about this interval.
        val covering = queries.coveringStrikeCoverage(
            from_epoch_ms = from.toEpochMilliseconds(),
            to_epoch_ms = to.toEpochMilliseconds(),
        ).executeAsOneOrNull()
        if (covering == null && values.isEmpty() && queries.strikeSyncState().executeAsOneOrNull() == null) return null
        val freshness = when {
            covering == null -> DataFreshness.Unknown
            else -> {
                val fetchedAt = Instant.fromEpochMilliseconds(covering.fetched_at_epoch_ms)
                val sourceAt = covering.source_updated_at_epoch_ms?.let(Instant::fromEpochMilliseconds)
                val age = (clock.now() - (sourceAt ?: fetchedAt)).coerceAtLeast(Duration.ZERO)
                if (currentWarning == null && age < policy.cacheTtl && clock.now() - fetchedAt < policy.cacheTtl) {
                    DataFreshness.Fresh(fetchedAt, sourceAt)
                } else {
                    DataFreshness.Stale(fetchedAt, age, sourceAt)
                }
            }
        }
        return DataResult.Data(values, freshness, currentWarning)
    }

    @Suppress("LongParameterList")
    private fun strike(
        id: String,
        providerId: String,
        externalId: String?,
        start: Long,
        end: Long,
        status: String,
        sector: String,
        relevance: String,
        regions: String,
        provinces: String,
        unions: String,
        operators: String,
        workforce: String?,
        mode: String,
        notes: String?,
        sourceLabel: String,
        sourceUrl: String,
        sourceUpdatedAt: Long?,
        fetchedAt: Long,
        contentFingerprint: String,
    ): Strike {
        @Suppress("UNUSED_VARIABLE") val persistedFetchedAt = fetchedAt
        return Strike(
            StrikeId(id),
            externalId,
            Instant.fromEpochMilliseconds(start),
            Instant.fromEpochMilliseconds(end),
            sector,
            unions.stringList(),
            workforce,
            operators.stringList().map(::Operator),
            StrikeGeography(
                StrikeRelevance.entries.firstOrNull { it.name == relevance } ?: StrikeRelevance.UNKNOWN,
                regions.stringList(),
                provinces.stringList(),
            ),
            mode,
            StrikeStatus.entries.firstOrNull { it.name == status } ?: StrikeStatus.SCHEDULED,
            notes,
            StrikeSource(ProviderId(providerId), sourceLabel, sourceUrl),
            sourceUpdatedAt?.let(Instant::fromEpochMilliseconds),
            contentFingerprint,
        )
    }

    private fun String.stringList(): List<String> = runCatching { json.decodeFromString<List<String>>(this) }
        .getOrDefault(emptyList())

    private fun notificationsEnabled(): Boolean = queries.strikeNotificationsEnabled().executeAsOneOrNull()?.let { it != 0L } == true

    private fun <T> failed(
        from: Instant,
        to: Instant,
        cached: DataResult.Data<T>?,
        error: DomainFailure,
    ): DataResult<T> {
        failures.update { it + (StrikeRequestKey(from, to) to error) }
        return cached?.let { value ->
            val stale = when (val freshness = value.freshness) {
                is DataFreshness.Fresh -> DataFreshness.Stale(
                    freshness.fetchedAt,
                    (clock.now() - freshness.fetchedAt).coerceAtLeast(Duration.ZERO),
                    freshness.sourceTimestamp,
                )
                else -> freshness
            }
            value.copy(freshness = stale, warning = error)
        } ?: DataResult.Failure(error)
    }
}

private fun DataResult.Data<List<Strike>>.refresh() = DataResult.Data(
    StrikeRefresh(value),
    freshness,
    warning,
)

private fun Strike.mergedStatus(previous: Strike?, now: Instant): StrikeStatus = when {
    status == StrikeStatus.REVOKED -> StrikeStatus.REVOKED
    end <= now -> StrikeStatus.COMPLETED
    previous == null -> status
    previous.status == StrikeStatus.REVOKED -> StrikeStatus.MODIFIED
    status == StrikeStatus.MODIFIED -> StrikeStatus.MODIFIED
    previous.contentFingerprint != contentFingerprint -> StrikeStatus.MODIFIED
    previous.status == StrikeStatus.MODIFIED -> StrikeStatus.MODIFIED
    else -> StrikeStatus.SCHEDULED
}

internal fun normalize(value: ProviderStrike, providerId: ProviderId): Strike {
    val cleaned = value.copy(
        externalId = value.externalId?.clean()?.takeIf(String::isNotBlank),
        sector = value.sector.clean(),
        unions = value.unions.cleaned(),
        workforce = value.workforce?.clean()?.takeIf(String::isNotBlank),
        operators = value.operators.map { Operator(it.name.clean()) }.distinctBy { it.name.lowercase() }.sortedBy { it.name },
        regions = value.regions.cleaned(),
        provinces = value.provinces.cleaned(),
        mode = value.mode.clean(),
        notes = value.notes?.clean()?.takeIf(String::isNotBlank),
        sourceUrl = value.sourceUrl.trim(),
    )
    require(cleaned.end > cleaned.start && cleaned.sector.isNotBlank() && cleaned.mode.isNotBlank())
    val identity = cleaned.externalId ?: fingerprint(
        listOf(providerId.value, cleaned.start.toString(), cleaned.sector, cleaned.workforce.orEmpty(), cleaned.sourceUrl),
    )
    return Strike(
        id = StrikeId("${providerId.value}:$identity"),
        externalId = cleaned.externalId,
        start = cleaned.start,
        end = cleaned.end,
        sector = cleaned.sector,
        unions = cleaned.unions,
        workforce = cleaned.workforce,
        operators = cleaned.operators,
        geography = StrikeGeography(cleaned.relevance, cleaned.regions, cleaned.provinces),
        mode = cleaned.mode,
        status = cleaned.status,
        notes = cleaned.notes,
        source = StrikeSource(providerId, if (providerId.value == "mit-strikes") "MIT" else providerId.value, cleaned.sourceUrl),
        sourceUpdatedAt = cleaned.sourceUpdatedAt,
        contentFingerprint = strikeContentFingerprint(cleaned),
    )
}

internal fun strikeContentFingerprint(value: ProviderStrike): String = fingerprint(
    listOf(
        value.start.toString(),
        value.end.toString(),
        value.sector,
        value.unions.joinToString("|"),
        value.workforce.orEmpty(),
        value.operators.joinToString("|") { it.name },
        value.relevance.name,
        value.regions.joinToString("|"),
        value.provinces.joinToString("|"),
        value.mode,
        value.notes.orEmpty(),
        value.sourceUrl,
    ),
)

private fun fingerprint(values: List<String>): String {
    var hash = -0x340d631b7bdddcdbL
    values.joinToString("\u001f") { it.clean().lowercase() }.forEach { char ->
        hash = hash xor char.code.toLong()
        hash *= 0x100000001b3L
    }
    return hash.toULong().toString(16).padStart(16, '0')
}

private fun String.clean(): String = trim().replace(Regex("\\s+"), " ")
private fun List<String>.cleaned(): List<String> = map(String::clean).filter(String::isNotBlank)
    .distinctBy(String::lowercase).sortedBy(String::lowercase)
private val Boolean.long: Long get() = if (this) 1L else 0L

private fun ProviderFailure.domain(): DomainFailure = when (this) {
    ProviderFailure.PROTOCOL, ProviderFailure.PARSING -> DomainFailure.INVALID_RESPONSE
    ProviderFailure.UNSUPPORTED -> DomainFailure.UNSUPPORTED
    else -> DomainFailure.TEMPORARY
}
