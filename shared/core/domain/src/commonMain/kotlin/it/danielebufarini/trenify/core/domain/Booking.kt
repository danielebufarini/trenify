package it.danielebufarini.trenify.core.domain

import it.danielebufarini.trenify.core.model.Journey
import it.danielebufarini.trenify.core.model.normalizeStationQuery
import kotlinx.coroutines.CancellationException

/**
 * Pricing-independent booking handoff.
 *
 * Booking opens an authorized official railway-operator channel using journey/operator
 * information only. It requires no pricing data and introduces no third-party booking
 * provider or commercial integration: there is intentionally no `PricingProvider`
 * reference anywhere on this path.
 *
 * A safe official URL alone is not a booking target: the approved mechanism must
 * transfer both stations. Generic homepage fallback was superseded on 2026-10-08.
 */
data class BookingTarget(val url: String, val operatorName: String)

/**
 * A reviewed HTTPS query contract. Register only mechanisms backed by operator
 * documentation or reproducible prefill evidence, cited in [verificationReference].
 * Parameter names and station identifiers must come from that evidence, never guesses.
 * [parameters] maps the selected journey to the operator's identifiers; return null
 * if any required context is unavailable. Include date/time/train only if supported.
 * This is application configuration, not a provider-supplied URL or user preference.
 */
class BookingChannel(
    val url: String,
    val originParameter: String,
    val destinationParameter: String,
    val verificationReference: String,
    val parameters: (Journey) -> Map<String, String>?,
)

class BookingLinkPolicy(
    private val operatorChannels: Map<String, BookingChannel> = defaultOperatorChannels,
    private val allowedHosts: Set<String> = defaultAllowedBookingHosts,
) {
    /**
     * Approved, journey-prefilled target, or null. Channel admission requires evidence;
     * resolution requires both station parameters; URL safety is checked separately.
     * Each registered contract must support the selected journey, including its legs.
     */
    fun target(journey: Journey): BookingTarget? =
        resolve(journey)?.takeIf { isAllowed(it.url) }

    /** Resolve a registered contract. The launcher must additionally check URL safety. */
    fun resolve(journey: Journey): BookingTarget? {
        val operator = journey.legs.first().operator?.name?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val channel = operatorChannels[normalizeStationQuery(operator)] ?: return null
        if (channel.verificationReference.isBlank() || channel.originParameter.isBlank() ||
            channel.destinationParameter.isBlank() || channel.originParameter == channel.destinationParameter ||
            '?' in channel.url || '#' in channel.url) return null
        val parameters = channel.parameters(journey) ?: return null
        if (parameters[channel.originParameter].isNullOrBlank() ||
            parameters[channel.destinationParameter].isNullOrBlank() ||
            parameters.any { (key, value) -> key.isBlank() || value.any(Char::isISOControl) }) return null
        val query = parameters.entries.joinToString("&") { (key, value) ->
            "${encodeParameter(key)}=${encodeParameter(value)}"
        }
        return BookingTarget("${channel.url}?$query", operator)
    }

    /** HTTPS-only official-host allowlist validation. Arbitrary URLs are rejected. */
    fun isAllowed(url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true)) return false
        // Reject malformed paths/queries too, not just malformed authorities.
        if (url.any { it.isWhitespace() || it.isISOControl() || it.code > 127 } ||
            '\\' in url || url.any { it in "<>\"{}|^`" }) return false
        if (Regex("%(?![0-9a-fA-F]{2})").containsMatchIn(url)) return false
        val host = hostOf(url) ?: return false
        return host.lowercase() in allowedHosts
    }

    private fun encodeParameter(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            val char = unsigned.toChar()
            if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "-._~") append(char)
            else {
                append('%')
                append("0123456789ABCDEF"[unsigned ushr 4])
                append("0123456789ABCDEF"[unsigned and 15])
            }
        }
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
         * Operator name (normalized) to verified prefill contract.
         * Trenitalia (including Tper), Italo and Trenord were individually assessed:
         * their previous entries were fixed homepages with no station transfer, and
         * no qualifying contract/evidence was found. Do not restore those fallbacks.
         * Future registrations require evidence and journey-specific parameter mapping.
         */
        val defaultOperatorChannels: Map<String, BookingChannel> = emptyMap()

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

enum class BookingUnavailableReason { UNKNOWN_OPERATOR, REJECTED_TARGET, NO_QUALIFYING_TARGET }

/**
 * Application-layer handoff: validates the policy target before opening it.
 *
 * The URL-opening port is a plain suspending function so this use case — and
 * `core:domain` as a whole — stays independent of `core:platform`. The composition
 * root adapts the `ExternalUrlLauncher` to it.
 */
class OpenBookingLink(
    private val policy: BookingLinkPolicy = BookingLinkPolicy(),
    private val openUrl: suspend (String) -> Boolean,
) {
    /** Availability and execution share this instance's policy configuration. */
    fun canBook(journey: Journey): Boolean = policy.target(journey) != null

    suspend operator fun invoke(journey: Journey): BookingHandoffResult {
        val resolved = policy.resolve(journey)
            ?: return BookingHandoffResult.Unavailable(BookingUnavailableReason.NO_QUALIFYING_TARGET)
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
