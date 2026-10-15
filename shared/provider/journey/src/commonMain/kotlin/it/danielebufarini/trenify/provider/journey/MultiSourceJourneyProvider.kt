package it.danielebufarini.trenify.provider.journey

import it.danielebufarini.trenify.core.network.RequestInstrumentation
import it.danielebufarini.trenify.core.network.span
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.provider.api.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlin.time.Clock

class MultiSourceJourneyProvider(
    private val sources: List<JourneySource>,
    private val clock: Clock = Clock.System,
    private val instrumentation: RequestInstrumentation = RequestInstrumentation.None,
) : JourneyProvider {
    init { require(sources.isNotEmpty() && sources.map { it.id }.distinct().size == sources.size) }
    override val id: ProviderId = ProviderId("multi-source-journey")

    override suspend fun searchStations(query: String): ProviderResult<List<Station>> = coroutineScope {
        val primary = sources.map { source -> async { stationSearch(source, query) } }.awaitAll()
        val tokens = stationSearchText(query).split(' ')
        // Only an empty aggregate expands once. Successful primary responses (including
        // aliases) stay authoritative; failed sources are not retried in the same lookup.
        val fallback = primary.any { it is ProviderResult.Success } &&
            primary.filterIsInstance<ProviderResult.Success<List<Station>>>().all { it.value.isEmpty() } &&
            tokens.size > 1 && tokens.first().length >= 2
        val results = if (fallback) primary.mapIndexed { index, result -> async {
            if (result is ProviderResult.Success) {
                val expanded = stationSearch(sources[index], tokens.first())
                if (expanded is ProviderResult.Success) expanded else result.copy(complete = false)
            } else result
        } }.awaitAll() else primary
        val successes = results.filterIsInstance<ProviderResult.Success<List<Station>>>()
        if (successes.isEmpty()) failure(results) else ProviderResult.Success(
            rankStationSuggestions(query, successes.flatMap { it.value }.let { candidates ->
                if (!fallback) candidates else candidates.filter { station ->
                    val words = stationSearchText(station.name).split(' ')
                    words.windowed(tokens.size).any { window ->
                        tokens.indices.all { window[it].startsWith(tokens[it]) }
                    }
                }
            }),
            ProviderMetadata(id, clock.now()),
            complete = primary.all { it is ProviderResult.Success && it.complete } &&
                results.all { it is ProviderResult.Success && it.complete },
        )
    }

    private suspend fun stationSearch(source: JourneySource, query: String): ProviderResult<List<Station>> =
        instrumentation.span("provider.total", mapOf("provider" to source.id.value, "operation" to "station_search")) {
            sourceBoundary { source.searchStations(query) }
        }

    override suspend fun search(request: JourneySearchRequest): ProviderResult<JourneySearchResult> =
        searchProgressively(request) { }

    override suspend fun searchProgressively(
        request: JourneySearchRequest,
        publish: suspend (ProviderResult.Success<JourneySearchResult>) -> Unit,
    ): ProviderResult<JourneySearchResult> = coroutineScope {
        val channel = Channel<Update>(Channel.UNLIMITED)
        val latest = arrayOfNulls<ProviderResult.Success<JourneySearchResult>>(sources.size)
        val terminal = arrayOfNulls<ProviderResult<JourneySearchResult>>(sources.size)
        val jobs = sources.mapIndexed { index, source -> launch {
            try {
                val result = instrumentation.span("provider.total", mapOf(
                    "provider" to source.id.value,
                    "operation" to "journey_search",
                )) {
                    sourceBoundary {
                        source.searchProgressively(request) { update ->
                            channel.send(Update.Progress(index, update))
                        }
                    }
                }
                instrumentation.event("provider.result", buildMap {
                    put("provider", source.id.value)
                    when (result) {
                        is ProviderResult.Success -> {
                            put("status", if (result.value.journeys.isEmpty()) "success_empty" else "success_with_results")
                            put("count", result.value.journeys.size.toString())
                            put("complete", (!result.value.partial).toString())
                        }
                        is ProviderResult.Unavailable -> {
                            put("status", "unavailable")
                            put("cause", result.cause.name)
                            put("retryable", result.retryable.toString())
                        }
                        ProviderResult.NotFound -> put("status", "unsupported")
                    }
                })
                channel.send(Update.Finished(index, result))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                channel.close(cancelled)
                throw cancelled
            }
        } }
        var finished = 0
        while (finished < sources.size) {
            when (val update = channel.receive()) {
                is Update.Progress -> {
                    latest[update.index] = update.result
                    publish(combined(request, sources, latest, terminal))
                }
                is Update.Finished -> {
                    terminal[update.index] = update.result
                    if (update.result is ProviderResult.Success) {
                        latest[update.index] = update.result
                    }
                    finished++
                    if (latest.any { it != null }) publish(combined(request, sources, latest, terminal))
                }
            }
        }
        jobs.joinAll()
        channel.close()
        val results = terminal.filterNotNull()
        val successes = results.filterIsInstance<ProviderResult.Success<JourneySearchResult>>()
        if (successes.isEmpty()) failure(results) else combined(request, sources, latest, terminal)
    }

    private fun combined(
        request: JourneySearchRequest,
        sources: List<JourneySource>,
        latest: Array<ProviderResult.Success<JourneySearchResult>?>,
        terminal: Array<ProviderResult<JourneySearchResult>?>,
    ): ProviderResult.Success<JourneySearchResult> {
        val successes = latest.filterNotNull()
        val coverage = sources.mapIndexed { index, source ->
            val current = latest[index]
            when {
                terminal[index] is ProviderResult.Success -> current!!.value.coverage
                terminal[index] != null -> listOf(JourneyCoverage(
                    source.id,
                    source.operators,
                    source.services,
                    if (terminal[index] == ProviderResult.NotFound ||
                        (terminal[index] is ProviderResult.Unavailable &&
                            (terminal[index] as ProviderResult.Unavailable).cause == ProviderFailure.UNSUPPORTED)
                    ) JourneySourceStatus.UNSUPPORTED else JourneySourceStatus.UNAVAILABLE,
                    complete = false,
                ))
                current != null -> current.value.coverage
                else -> listOf(JourneyCoverage(source.id, source.operators, source.services,
                    JourneySourceStatus.UNAVAILABLE, complete = false))
            }
        }.flatten()
        return ProviderResult.Success(
            JourneySearchResult(
                mergeJourneys(successes.flatMap { it.value.journeys }.filter { it.matches(request) }),
                coverage,
                request.departureFrom,
                request.departureUntil,
            ),
            ProviderMetadata(id, successes.minOf { it.metadata.fetchedAt }),
            complete = coverage.all { it.status == JourneySourceStatus.AVAILABLE && it.complete },
        )
    }

    private sealed interface Update {
        data class Progress(val index: Int, val result: ProviderResult.Success<JourneySearchResult>) : Update
        data class Finished(val index: Int, val result: ProviderResult<JourneySearchResult>) : Update
    }
}

// Compare complete scheduled leg sequences; never combine fragments of conflicting timetables.
internal fun mergeJourneys(journeys: List<Journey>): List<Journey> = journeys.groupBy { journey ->
    journey.legs.map { leg -> listOf(
        normalizeJourneyStationName(leg.origin.name), normalizeJourneyStationName(leg.destination.name),
        leg.departure.toString(), leg.arrival.toString(), leg.number?.value.orEmpty(),
        leg.operator?.name?.let(::normalizeStationQuery).orEmpty(),
        leg.category?.let(::normalizeStationQuery).orEmpty(),
    ) }
}.values.map { equivalents -> equivalents.first().copy(
    legs = equivalents.first().legs.mapIndexed { index, leg ->
        leg.copy(scheduledPlatform = equivalents.firstNotNullOfOrNull { it.legs[index].scheduledPlatform })
    },
    sources = equivalents.flatMap { it.sources }.toSet(),
) }
    .sortedBy(JourneySort.DEPARTURE)

private fun failure(results: List<ProviderResult<*>>): ProviderResult<Nothing> =
    if (results.all { it == ProviderResult.NotFound || (it is ProviderResult.Unavailable && it.cause == ProviderFailure.UNSUPPORTED) })
        ProviderResult.Unavailable(false, ProviderFailure.UNSUPPORTED)
    else ProviderResult.Unavailable(true, ProviderFailure.UNAVAILABLE)
