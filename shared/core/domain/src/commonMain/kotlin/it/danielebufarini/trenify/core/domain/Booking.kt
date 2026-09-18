package it.danielebufarini.trenify.core.domain

import it.danielebufarini.trenify.core.model.Journey
import it.danielebufarini.trenify.core.model.normalizeStationQuery
import kotlinx.coroutines.CancellationException

/**
 * Pricing-independent booking handoff (T5.5: FR-BOOKING-001, FR-BOOKING-002).
 *
 * Booking opens an authorized official railway-operator channel using journey/operator
 * information only. It requires no pricing data and introduces no third-party booking
 * provider or commercial integration: there is intentionally no `PricingProvider`
 * reference anywhere on this path.
 *
 * URL discipline: no documented operator deep link carrying origin, destination, date,
 * time, or train reference exists in the repository, so targets are safe fallbacks to
 * approved official operator channels (fixed homepage URLs — never string-concatenated
 * and never carrying invented deep-link parameters). A future pricing offer may supply
 * a pricing-derived deep-link target as enrichment; that is future scope and never a
 * prerequisite for this path.
 */
data class BookingTarget(val url: String, val operatorName: String)

class BookingLinkPolicy(
    private val operatorChannels: Map<String, String> = defaultOperatorChannels,
    private val allowedHosts: Set<String> = defaultAllowedBookingHosts,
) {
    /**
     * Approved official operator target for [journey], or null when no approved channel
     * exists (unknown or missing operator, or a resolved target outside the allowlist).
     * Mixed-operator journeys resolve to the first leg's operator channel: the journey
     * starts there and every operator channel sells its own departures.
     */
    fun target(journey: Journey): BookingTarget? =
        resolve(journey)?.takeIf { isAllowed(it.url) }

    /** Operator mapping without allowlist approval. Null means no known channel. */
    fun resolve(journey: Journey): BookingTarget? {
        val operator = journey.legs.first().operator?.name?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val url = operatorChannels[normalizeStationQuery(operator)] ?: return null
        return BookingTarget(url, operator)
    }

    /** HTTPS-only official-host allowlist validation. Arbitrary URLs are rejected. */
    fun isAllowed(url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true)) return false
        val host = hostOf(url) ?: return false
        return host.lowercase() in allowedHosts
    }

    private fun hostOf(url: String): String? {
        val authority = url.substringAfter("://", missingDelimiterValue = "").substringBefore('/')
            .substringBefore('?').substringBefore('#')
        if (authority.isEmpty() || authority.any(Char::isWhitespace) || '\\' in authority) return null
        // Credential-bearing (user@host), port-bearing, and IPv6-literal authorities are never allowlisted.
        if ('@' in authority || ':' in authority || '[' in authority || ']' in authority) return null
        return authority
    }

    companion object {
        /**
         * Operator name (normalized) to approved official channel.
         *
         * Evidence: "Trenitalia" and "Italo" are the operators produced by the production
         * journey adapters; "Trenord" and "Trenitalia Tper" values are produced by the
         * Italo adapter mapping. The targets are the operators' official homepages used
         * as the safe fallback channel.
         */
        val defaultOperatorChannels: Map<String, String> = mapOf(
            "trenitalia" to "https://www.trenitalia.com",
            "trenitalia tper" to "https://www.trenitalia.com",
            "italo" to "https://www.italotreno.com",
            "trenord" to "https://www.trenord.it",
        )

        /**
         * Official hosts approved for booking. Least privilege: exactly the hosts the
         * policy can emit. Unrelated or private provider endpoints (for example the
         * LeFrecce BFF host or the Italo guest-login host used inside journey adapters)
         * are not trusted booking destinations. Subdomains and lookalikes are not covered.
         */
        val defaultAllowedBookingHosts: Set<String> = setOf(
            "www.trenitalia.com",
            "www.italotreno.com",
            "www.trenord.it",
        )
    }
}

sealed interface BookingHandoffResult {
    data class Opened(val target: BookingTarget) : BookingHandoffResult
    data class Unavailable(val reason: BookingUnavailableReason) : BookingHandoffResult
    data class Failed(val target: BookingTarget) : BookingHandoffResult
}

enum class BookingUnavailableReason { UNKNOWN_OPERATOR, REJECTED_TARGET }

/**
 * Application-layer handoff: validates the policy target before opening it.
 *
 * The URL-opening port is a plain suspending function so this use case — and
 * `core:domain` as a whole — stays independent of `core:platform`. The composition
 * root adapts the T0.9 `ExternalUrlLauncher` to it.
 */
class OpenBookingLink(
    private val policy: BookingLinkPolicy = BookingLinkPolicy(),
    private val openUrl: suspend (String) -> Boolean,
) {
    /** Availability and execution share this instance's policy configuration. */
    fun canBook(journey: Journey): Boolean = policy.target(journey) != null

    suspend operator fun invoke(journey: Journey): BookingHandoffResult {
        val resolved = policy.resolve(journey)
            ?: return BookingHandoffResult.Unavailable(BookingUnavailableReason.UNKNOWN_OPERATOR)
        if (!policy.isAllowed(resolved.url)) {
            return BookingHandoffResult.Unavailable(BookingUnavailableReason.REJECTED_TARGET)
        }
        // target is validated above: HTTPS allowlisted official host only.
        val opened = try {
            openUrl(resolved.url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        return if (opened) BookingHandoffResult.Opened(resolved) else BookingHandoffResult.Failed(resolved)
    }
}
