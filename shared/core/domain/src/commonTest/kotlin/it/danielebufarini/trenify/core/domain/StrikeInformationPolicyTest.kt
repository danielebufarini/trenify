package it.danielebufarini.trenify.core.domain

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T7.12-D strike/operator-information reference validation: dedicated
 * allowlist, never the booking allowlist, never fabricated targets.
 */
class StrikeInformationPolicyTest {
    private val policy = StrikeInformationPolicy()

    @Test
    fun officialMitReferenceIsUsable() {
        assertTrue(policy.officialReferenceUsable("https://scioperi.mit.gov.it/8479"))
        assertTrue(policy.guaranteedServiceReferenceUsable("https://scioperi.mit.gov.it/guaranteed"))
    }

    @Test
    fun bookingChannelHostsAreNotStrikeInformationAuthority() {
        // Ticket-sale authority is not strike-information authority: the
        // booking allowlist is never reused here.
        assertFalse(policy.officialReferenceUsable("https://www.trenitalia.com"))
        assertFalse(policy.officialReferenceUsable("https://www.italotreno.com"))
        assertFalse(policy.officialReferenceUsable("https://www.trenord.it"))
    }

    @Test
    fun malformedUntrustedAndUnsupportedReferencesAreNotLaunchable() {
        assertFalse(policy.officialReferenceUsable("http://scioperi.mit.gov.it/8479"))
        assertFalse(policy.officialReferenceUsable("not a url"))
        assertFalse(policy.officialReferenceUsable(""))
        assertFalse(policy.officialReferenceUsable("https://evil-scioperi.mit.gov.it/8479"))
        assertFalse(policy.officialReferenceUsable("https://scioperi.mit.gov.it.evil.example/8479"))
        assertFalse(policy.officialReferenceUsable("https://scioperi.mit.gov.it:443/8479"))
        assertFalse(policy.officialReferenceUsable("https://user@scioperi.mit.gov.it/8479"))
        assertFalse(policy.officialReferenceUsable("https://[::1]/8479"))
        assertFalse(policy.officialReferenceUsable("ftp://scioperi.mit.gov.it/8479"))
        assertFalse(policy.officialReferenceUsable("https://scioperi.mit.gov.it\\@evil.example/"))
    }

    @Test
    fun openStrikeReferenceValidatesBeforeOpening() = runTest {
        val opened = mutableListOf<String>()
        val open = OpenStrikeReference(policy) { url -> opened += url; true }
        assertTrue(open.canOpen("https://scioperi.mit.gov.it/8479"))
        assertFalse(open.canOpen("https://www.trenitalia.com"))
        assertFalse(open.canOpen(null))
        assertEquals(
            StrikeReferenceResult.Opened("https://scioperi.mit.gov.it/8479"),
            open("https://scioperi.mit.gov.it/8479"),
        )
        assertEquals(
            StrikeReferenceResult.Unavailable(StrikeReferenceUnavailableReason.NO_REFERENCE),
            open(null),
        )
        assertEquals(
            StrikeReferenceResult.Unavailable(StrikeReferenceUnavailableReason.REJECTED_TARGET),
            open("https://www.trenitalia.com"),
        )
        assertEquals(listOf("https://scioperi.mit.gov.it/8479"), opened)
    }

    @Test
    fun openFailureIsReportedWithoutFabrication() = runTest {
        val failing = OpenStrikeReference(policy) { false }
        assertEquals(
            StrikeReferenceResult.Failed("https://scioperi.mit.gov.it/8479"),
            failing("https://scioperi.mit.gov.it/8479"),
        )
        var calls = 0
        val throwing = OpenStrikeReference(policy) { calls++; throw IllegalStateException("launcher broken") }
        assertEquals(
            StrikeReferenceResult.Failed("https://scioperi.mit.gov.it/8479"),
            throwing("https://scioperi.mit.gov.it/8479"),
        )
        assertEquals(1, calls)
    }
}
