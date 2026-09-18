package it.danielebufarini.trenify.core.domain

import kotlinx.coroutines.CancellationException

/**
 * Dedicated strike/operator-information reference policy (T7.12-D).
 *
 * This is deliberately separate from the booking-link allowlist
 * ([BookingLinkPolicy]): proving that a host may sell tickets says nothing
 * about it being an authoritative strike/operator-information source, and
 * vice versa. The only official strike-information host evidenced in the
 * repository today is the MIT strike feed host; operator homepages are
 * booking channels, not strike-information authority, and are rejected here
 * until an ADR evidences them as such. A URL's existence or usability never
 * promotes strike impact: confirmation requires verified evidence through
 * [OperatorStrikeConfirmation], never this policy alone.
 */
class StrikeInformationPolicy(
    private val allowedHosts: Set<String> = defaultAllowedStrikeInformationHosts,
) {
    /**
     * True for an actually available official strike/source reference target:
     * HTTPS with a strict allowlisted official-host authority. Malformed,
     * non-HTTPS, credential/port/IPv6-literal, untrusted or unsupported
     * references are not launchable.
     */
    fun officialReferenceUsable(url: String): Boolean = isAllowed(url)

    /**
     * Same dedicated validation for official guaranteed-service information
     * references. No guaranteed-service URL exists in current provider data,
     * so production exposes that absence honestly instead of fabricating a
     * target; this entry point validates one if provider data ever carries it.
     */
    fun guaranteedServiceReferenceUsable(url: String): Boolean = isAllowed(url)

    private fun isAllowed(url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true)) return false
        val host = hostOf(url) ?: return false
        return host.lowercase() in allowedHosts
    }

    private fun hostOf(url: String): String? {
        val authority = url.substringAfter("://", missingDelimiterValue = "").substringBefore('/')
            .substringBefore('?').substringBefore('#')
        if (authority.isEmpty() || authority.any(Char::isWhitespace) || '\\' in authority) return null
        // Credential-bearing (user@host), port-bearing, and IPv6-literal
        // authorities are never allowlisted.
        if ('@' in authority || ':' in authority || '[' in authority || ']' in authority) return null
        return authority
    }

    companion object {
        /**
         * Official hosts approved for strike/operator information. Least
         * privilege: exactly the MIT strike feed host evidenced by the
         * production strike adapter. Booking-channel hosts (Trenitalia,
         * Italo, Trenord) are intentionally absent: ticket-sale authority is
         * not strike-information authority.
         */
        val defaultAllowedStrikeInformationHosts: Set<String> = setOf(
            "scioperi.mit.gov.it",
        )
    }
}

sealed interface StrikeReferenceResult {
    data class Opened(val url: String) : StrikeReferenceResult
    data class Unavailable(val reason: StrikeReferenceUnavailableReason) : StrikeReferenceResult
    data class Failed(val url: String) : StrikeReferenceResult
}

enum class StrikeReferenceUnavailableReason { NO_REFERENCE, REJECTED_TARGET }

/**
 * Application-layer handoff for official strike/operator-information links:
 * validates the dedicated policy target before opening it through the
 * injected URL-opening port (adapted from the T0.9 `ExternalUrlLauncher` at
 * the composition root), so `core:domain` stays independent of
 * `core:platform`.
 */
class OpenStrikeReference(
    private val policy: StrikeInformationPolicy = StrikeInformationPolicy(),
    private val openUrl: suspend (String) -> Boolean,
) {
    fun canOpen(url: String?): Boolean = url != null && policy.officialReferenceUsable(url)

    fun canOpenGuaranteedService(url: String?): Boolean = url != null && policy.guaranteedServiceReferenceUsable(url)

    suspend operator fun invoke(url: String?): StrikeReferenceResult {
        if (url.isNullOrBlank()) return StrikeReferenceResult.Unavailable(StrikeReferenceUnavailableReason.NO_REFERENCE)
        if (!policy.officialReferenceUsable(url)) {
            return StrikeReferenceResult.Unavailable(StrikeReferenceUnavailableReason.REJECTED_TARGET)
        }
        val opened = try {
            openUrl(url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        return if (opened) StrikeReferenceResult.Opened(url) else StrikeReferenceResult.Failed(url)
    }
}
