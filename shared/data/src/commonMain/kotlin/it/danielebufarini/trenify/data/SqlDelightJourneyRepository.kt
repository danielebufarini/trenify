package it.danielebufarini.trenify.data

import app.cash.sqldelight.coroutines.asFlow
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

class SqlDelightJourneyRepository(
    database: TrenifyDatabase,
    private val provider: JourneyProvider,
    private val scope: CoroutineScope,
    private val online: () -> Boolean = { true },
    private val clock: Clock = Clock.System,
    private val policy: JourneyPolicy = JourneyPolicy(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val instrumentation: RequestInstrumentation = RequestInstrumentation.None,
) : JourneyRepository {
    // Reuse the existing keyed SQLDelight cache; versioned keys isolate journey payloads.
    private val queries = database.realtimeQueries
    private val json = Json { ignoreUnknownKeys = true }
    private val warnings = MutableStateFlow<Map<String, DomainFailure>>(emptyMap())
    private val mutex = Mutex()
    private val flights = mutableMapOf<String, Deferred<DataResult<JourneySearchResult>>>()

    override fun observe(request: JourneySearchRequest): Flow<DataResult<JourneySearchResult>> = combine(
        queries.cacheByKey(request.cacheKey).asFlow(), warnings,
    ) { _, errors -> cached(request, errors[request.cacheKey]) ?: DataResult.Failure(errors[request.cacheKey] ?: DomainFailure.NOT_FOUND) }
        .distinctUntilChanged().flowOn(dispatcher)

    override suspend fun search(request: JourneySearchRequest, force: Boolean): DataResult<JourneySearchResult> {
        if (!SearchJourneys(this).valid(request)) return DataResult.Failure(DomainFailure.INVALID_REQUEST)
        val cachedPartial = if (!force) withContext(dispatcher) {
            cached(request, warnings.value[request.cacheKey])?.takeIf { it.value.partial }
        } else null
        val flight = mutex.withLock {
            flights[request.cacheKey] ?: scope.async(dispatcher, start = CoroutineStart.LAZY) {
                try { refresh(request, force) }
                finally { withContext(NonCancellable) { mutex.withLock { flights.remove(request.cacheKey) } } }
            }.also { flights[request.cacheKey] = it; it.start() }
        }
        // Progressive content is useful immediately, but stale by contract.
        // Start or join the refresh above without making a reopen wait for it.
        if (cachedPartial != null) return cachedPartial
        return flight.await()
    }

    private suspend fun refresh(request: JourneySearchRequest, force: Boolean): DataResult<JourneySearchResult> {
        val key = request.cacheKey
        val cached = instrumentation.span("journey.cache_lookup", mapOf("cache" to "journey")) {
            cached(request, warnings.value[key])
        }
        instrumentation.event("journey.cache", mapOf(
            "hit" to (cached != null).toString(),
            "fresh" to (cached?.freshness is DataFreshness.Fresh).toString(),
        ))
        if (!online()) return failed(request, DomainFailure.OFFLINE)
        if (!force && cached?.freshness is DataFreshness.Fresh) return cached
        instrumentation.event("journey.submit_to_provider_start")
        var firstPublished = false
        var latest: DataResult.Data<JourneySearchResult>? = null
        val result = instrumentation.span("journey.provider_total") {
            provider.searchProgressively(request) { update ->
                val value = update.value
                if (value.departureFrom != request.departureFrom || value.departureUntil != request.departureUntil ||
                    value.journeys.any { !it.matches(request) }) return@searchProgressively
                if (!firstPublished) {
                    instrumentation.event("journey.submit_to_first_repository_result", mapOf(
                        "partial" to value.partial.toString(),
                    ))
                    firstPublished = true
                }
                warnings.update { it - key }
                instrumentation.span("journey.persistence_write") {
                    queries.putCache(key, json.encodeToString(value.record()), update.metadata.fetchedAt.toEpochMilliseconds(), null)
                }
                latest = instrumentation.span("journey.persistence_to_observation") { cached(request, null)!! }
            }
        }
        return when (result) {
            is ProviderResult.Success -> {
                val value = result.value
                if (value.departureFrom != request.departureFrom || value.departureUntil != request.departureUntil ||
                    value.journeys.any { !it.matches(request) }) return failed(request, DomainFailure.INVALID_RESPONSE)
                if (latest == null || latest!!.value != value) {
                    warnings.update { it - key }
                    instrumentation.span("journey.persistence_write") {
                        queries.putCache(key, json.encodeToString(value.record()), result.metadata.fetchedAt.toEpochMilliseconds(), null)
                    }
                    latest = instrumentation.span("journey.persistence_to_observation") { cached(request, null)!! }
                }
                latest ?: failed(request, DomainFailure.INVALID_RESPONSE)
            }
            ProviderResult.NotFound -> failed(request, DomainFailure.NOT_FOUND)
            is ProviderResult.Unavailable -> failed(request, result.cause.domainFailure())
        }
    }

    override suspend fun searchStations(query: String): DataResult<List<Station>> = withContext(dispatcher) {
        val normalized = normalizeStationQuery(query)
        if (normalized.length < 2) return@withContext DataResult.Data(emptyList(), DataFreshness.Unknown)
        val key = "journey-stations-v1:$normalized"
        val row = queries.cacheByKey(key).executeAsOneOrNull()
        val stored = row?.let { runCatching { json.decodeFromString<List<StationRecord>>(it.payload).map(StationRecord::model) }.getOrNull() }
        val fetched = row?.let { Instant.fromEpochMilliseconds(it.fetched_at) }
        if (stored != null && fetched != null && online() && clock.now() - fetched < policy.stationTtl)
            return@withContext DataResult.Data(stored, DataFreshness.Fresh(fetched, null))
        val result = if (online()) provider.searchStations(normalized) else null
        if (result is ProviderResult.Success) {
            queries.putCache(key, json.encodeToString(result.value.map { StationRecord(it.id.value, it.name) }),
                result.metadata.fetchedAt.toEpochMilliseconds(), null)
            DataResult.Data(result.value, DataFreshness.Fresh(result.metadata.fetchedAt, null))
        } else {
            val error = if (!online()) DomainFailure.OFFLINE else DomainFailure.TEMPORARY
            if (stored != null && fetched != null) DataResult.Data(stored,
                DataFreshness.Stale(fetched, (clock.now() - fetched).coerceAtLeast(Duration.ZERO), null), error)
            else DataResult.Failure(error)
        }
    }

    private fun failed(request: JourneySearchRequest, failure: DomainFailure): DataResult<JourneySearchResult> {
        warnings.update { it + (request.cacheKey to failure) }
        return cached(request, failure) ?: DataResult.Failure(failure)
    }

    private fun cached(request: JourneySearchRequest, failure: DomainFailure?): DataResult.Data<JourneySearchResult>? {
        val row = queries.cacheByKey(request.cacheKey).executeAsOneOrNull() ?: return null
        val result = runCatching { json.decodeFromString<JourneyResultRecord>(row.payload).model() }.getOrNull() ?: return null
        // Contract guard (T8 corrective): the persisted window must equal the
        // current request window. Rows written under a different horizon (for
        // example the pre-8h v1 contract) are unreachable by key already, and
        // this check additionally rejects any bounds mismatch that ever
        // reaches this read path instead of serving foreign coverage.
        if (result.departureFrom != request.departureFrom || result.departureUntil != request.departureUntil) return null
        val fetched = Instant.fromEpochMilliseconds(row.fetched_at)
        val age = (clock.now() - fetched).coerceAtLeast(Duration.ZERO)
        val freshness = if (failure == null && !result.partial && age < policy.ttl) DataFreshness.Fresh(fetched, null)
            else DataFreshness.Stale(fetched, age, null)
        // Partial success remains observable but is never a fresh complete cache entry.
        return DataResult.Data(result, freshness, failure)
    }
}

/**
 * Persisted journey cache contract (T8 corrective): v2 isolates the 8-hour
 * horizon from pre-8h v1 rows. Old v1 rows stay physically stored but are
 * unreachable — no migration is required to delete them. [cached] further
 * validates the persisted window against the live request, so a horizon
 * change without a key bump can never serve foreign coverage either.
 */
private val JourneySearchRequest.cacheKey: String get() = "journey-v2:$key"
private fun ProviderFailure.domainFailure(): DomainFailure = when (this) {
    ProviderFailure.PROTOCOL, ProviderFailure.PARSING -> DomainFailure.INVALID_RESPONSE
    ProviderFailure.UNSUPPORTED -> DomainFailure.UNSUPPORTED
    else -> DomainFailure.TEMPORARY
}
