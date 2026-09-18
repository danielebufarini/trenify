package it.danielebufarini.trenify.core.model

import kotlin.time.Instant
import kotlin.jvm.JvmInline

@JvmInline
value class StrikeId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

enum class StrikeStatus {
    SCHEDULED,
    MODIFIED,
    REVOKED,
    COMPLETED,
}

enum class StrikeRelevance {
    LOCAL,
    PROVINCIAL,
    REGIONAL,
    INTERREGIONAL,
    NATIONAL,
    UNKNOWN,
}

data class StrikeGeography(
    val relevance: StrikeRelevance = StrikeRelevance.UNKNOWN,
    val regions: List<String> = emptyList(),
    val provinces: List<String> = emptyList(),
)

data class StrikeSource(
    val provider: ProviderId,
    val label: String,
    val url: String,
)

data class Strike(
    val id: StrikeId,
    val externalId: String?,
    val start: Instant,
    val end: Instant,
    val sector: String,
    val unions: List<String>,
    val workforce: String?,
    val operators: List<Operator>,
    val geography: StrikeGeography,
    val mode: String,
    val status: StrikeStatus,
    val notes: String?,
    val source: StrikeSource,
    val sourceUpdatedAt: Instant?,
    val contentFingerprint: String,
) {
    init {
        require(end > start)
        require(sector.isNotBlank())
        require(mode.isNotBlank())
        require(contentFingerprint.isNotBlank())
    }
}

/**
 * Single shared T7.12 strike-interval overlap definition: touching
 * boundaries count as overlap (`aStart <= bEnd && bStart <= aEnd`).
 *
 * Every production layer that decides whether a strike belongs to a
 * requested interval must use this definition (or its exact SQL mirror in
 * `strikesOverlapping`/`strikeEndsOverlapping`, referenced here): provider
 * range filtering, persisted-cache lookup, absence-revocation scope, and the
 * domain temporal-evidence policy. Independent strict `<`/`>` copies drifted
 * apart and dropped exact-boundary strikes before the policy could see
 * them; do not reintroduce local variants.
 */
fun strikeIntervalsOverlap(aStart: Instant, aEnd: Instant, bStart: Instant, bEnd: Instant): Boolean =
    aStart <= bEnd && bStart <= aEnd
