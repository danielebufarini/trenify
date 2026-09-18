package it.danielebufarini.trenify.provider.journey

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.provider.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Instant

internal val journeyJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
internal class JourneyWireFailure(val reason: ProviderFailure, val retryable: Boolean) : Exception()

internal suspend fun HttpResponse.journeyBody(): String {
    if (status.value !in 200..299) throw JourneyWireFailure(
        if (status.value == 429 || status.value >= 500 || status.value in setOf(401, 403)) ProviderFailure.UNAVAILABLE
        else ProviderFailure.PROTOCOL,
        status.value == 429 || status.value >= 500,
    )
    return bodyAsText()
}

internal suspend fun <T> journeyRequest(block: suspend () -> ProviderResult<T>): ProviderResult<T> = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: JourneyWireFailure) {
    ProviderResult.Unavailable(failure.retryable, failure.reason)
} catch (_: SerializationException) {
    ProviderResult.Unavailable(false, ProviderFailure.PARSING)
} catch (_: IllegalArgumentException) {
    ProviderResult.Unavailable(false, ProviderFailure.PROTOCOL)
} catch (_: HttpRequestTimeoutException) {
    ProviderResult.Unavailable(true, ProviderFailure.TIMEOUT)
} catch (_: Exception) {
    ProviderResult.Unavailable(true, ProviderFailure.TRANSPORT)
}

internal fun station(name: String): Station {
    val clean = name.trim().replace(Regex("\\s+"), " ")
    require(clean.isNotBlank())
    return Station(StationId("station:${normalizeStationQuery(clean)}"), clean)
}

internal fun wireTime(value: String): Instant = Instant.parseOrNull(value)
    ?: LocalDateTime.parse(value).toInstant(RailwayTime.zone)

internal fun JourneySource.result(
    request: JourneySearchRequest,
    journeys: List<Journey>,
    complete: Boolean,
    at: Instant,
): ProviderResult.Success<JourneySearchResult> = ProviderResult.Success(
    JourneySearchResult(journeys.filter { it.matches(request) }, listOf(
        JourneyCoverage(id, (operators + journeys.flatMap { it.operators }).distinct(), services, JourneySourceStatus.AVAILABLE, complete),
    ), request.departureFrom, request.departureUntil),
    ProviderMetadata(id, at),
)
