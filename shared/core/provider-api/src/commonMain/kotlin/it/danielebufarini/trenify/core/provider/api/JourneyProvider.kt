package it.danielebufarini.trenify.core.provider.api

import it.danielebufarini.trenify.core.model.*

interface JourneyProvider {
    val id: ProviderId
    suspend fun searchStations(query: String): ProviderResult<List<Station>>
    suspend fun search(request: JourneySearchRequest): ProviderResult<JourneySearchResult>

    /**
     * Publishes authoritative partial results while a provider continues its
     * work. Providers that cannot make progress publish their final result once.
     */
    suspend fun searchProgressively(
        request: JourneySearchRequest,
        publish: suspend (ProviderResult.Success<JourneySearchResult>) -> Unit,
    ): ProviderResult<JourneySearchResult> {
        val result = search(request)
        if (result is ProviderResult.Success) publish(result)
        return result
    }
}
