package it.danielebufarini.trenify.core.model

/**
 * Provider-neutral data-source attribution (non-rendering semantics).
 *
 * Three concepts stay distinct:
 * - source/provider identity ([ProviderId], mapped below);
 * - the source observation/update timestamp (`sourceTimestamp` on freshness);
 * - the local fetch timestamp (`fetchedAt` on freshness).
 *
 * Only genuinely known provider identities are named. The mapping covers
 * the production provider ids; anything else yields null and renders as
 * Unknown/Unavailable at the presentation layer — the provider is never
 * inferred from an operator name and wire DTOs never reach presentation.
 * Proper source names are data/presentation attribution, not translated
 * business semantics, so the names themselves stay identical across locales.
 *
 * Lives in core:model (not core:ui) so the narrow native presentation
 * boundary can reuse these exact semantics without depending on shared
 * Compose UI; core:ui attribution composables consume the same functions.
 */
fun trainSourceName(provider: ProviderId): String? = when (provider.value) {
    "viaggiatreno" -> "ViaggiaTreno"
    "trenitalia-journeys" -> "Trenitalia"
    "italo-journeys" -> "Italo"
    "mit-strikes" -> "MIT"
    else -> null
}

/** Human-readable names for every genuinely contributing source, or null when none is known. */
fun journeySourceNames(sources: Set<ProviderId>): String? =
    sources.mapNotNull(::trainSourceName).distinct().sorted()
        .joinToString().takeIf { it.isNotEmpty() }
