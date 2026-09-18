package it.danielebufarini.trenify.core.provider.api

import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.ProviderId
import it.danielebufarini.trenify.core.model.StrikeRelevance
import it.danielebufarini.trenify.core.model.StrikeStatus
import kotlin.time.Instant

data class ProviderStrike(
    val externalId: String?,
    val start: Instant,
    val end: Instant,
    val sector: String,
    val unions: List<String> = emptyList(),
    val workforce: String? = null,
    val operators: List<Operator> = emptyList(),
    val relevance: StrikeRelevance = StrikeRelevance.UNKNOWN,
    val regions: List<String> = emptyList(),
    val provinces: List<String> = emptyList(),
    val mode: String,
    val status: StrikeStatus = StrikeStatus.SCHEDULED,
    val notes: String? = null,
    val sourceUrl: String,
    val sourceUpdatedAt: Instant? = null,
)

interface StrikeProvider {
    val id: ProviderId

    /** True only when absence from a successful response is meaningful for the requested interval. */
    val suppliesCompleteSnapshots: Boolean get() = false

    suspend fun getStrikes(
        from: Instant,
        to: Instant,
        includeRevoked: Boolean = true,
    ): ProviderResult<List<ProviderStrike>>
}
