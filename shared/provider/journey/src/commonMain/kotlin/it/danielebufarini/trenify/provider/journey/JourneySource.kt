package it.danielebufarini.trenify.provider.journey

import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.provider.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Adapter-layer extension point. Only normalized data may leave a source. */
interface JourneySource {
    val id: ProviderId
    val operators: List<Operator>
    val services: List<String>
    suspend fun searchStations(query: String): ProviderResult<List<Station>>
    suspend fun search(request: JourneySearchRequest): ProviderResult<JourneySearchResult>

    suspend fun searchProgressively(
        request: JourneySearchRequest,
        publish: suspend (ProviderResult.Success<JourneySearchResult>) -> Unit,
    ): ProviderResult<JourneySearchResult> {
        val result = search(request)
        if (result is ProviderResult.Success) publish(result)
        return result
    }
}

internal suspend fun <T> sourceBoundary(timeout: Duration = 45.seconds, block: suspend () -> ProviderResult<T>): ProviderResult<T> =
    try {
        withTimeoutOrNull(timeout) { block() } ?: ProviderResult.Unavailable(true, ProviderFailure.TIMEOUT)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ProviderResult.Unavailable(true, ProviderFailure.UNAVAILABLE)
    }
