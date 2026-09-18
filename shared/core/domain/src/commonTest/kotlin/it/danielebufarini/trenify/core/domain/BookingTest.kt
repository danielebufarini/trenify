package it.danielebufarini.trenify.core.domain

import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.testing.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.hours

class BookingTest {
    private fun journeyWith(vararg operators: String?) = Journey(
        operators.mapIndexed { index, operator ->
            val base = testJourney.legs.single()
            base.copy(
                departure = base.departure + (index * 4).hours,
                arrival = base.arrival + (index * 4).hours,
                operator = operator?.let(::Operator),
            )
        }.let { legs ->
            // Multi-leg journeys must chain stations; single-leg journeys pass through unchanged.
            if (legs.size < 2) legs else legs.mapIndexed { index, leg ->
                leg.copy(origin = if (index == 0) leg.origin else legs[index - 1].destination)
            }
        },
        setOf(testJourney.sources.single()),
    )

    @Test fun knownOperatorsResolveToTheirApprovedOfficialChannel() {
        val policy = BookingLinkPolicy()
        assertEquals(BookingTarget("https://www.trenitalia.com", "Trenitalia"), policy.target(journeyWith("Trenitalia")))
        assertEquals(BookingTarget("https://www.trenitalia.com", "TRENITALIA"), policy.target(journeyWith("TRENITALIA")))
        assertEquals(BookingTarget("https://www.trenitalia.com", "Trenitalia Tper"), policy.target(journeyWith("Trenitalia Tper")))
        assertEquals(BookingTarget("https://www.italotreno.com", "Italo"), policy.target(journeyWith("Italo")))
        assertEquals(BookingTarget("https://www.trenord.it", "Trenord"), policy.target(journeyWith("Trenord")))
    }

    @Test fun unknownOrMissingOperatorHasNoApprovedTarget() {
        val policy = BookingLinkPolicy()
        assertNull(policy.target(journeyWith("Unknown Operator")))
        assertNull(policy.target(journeyWith(null)))
    }

    @Test fun mixedOperatorJourneyUsesFirstLegOperatorChannel() {
        val policy = BookingLinkPolicy()
        assertEquals(
            BookingTarget("https://www.italotreno.com", "Italo"),
            policy.target(journeyWith("Italo", "Trenitalia")),
        )
    }

    @Test fun allowlistRequiresHttpsOfficialHosts() {
        val policy = BookingLinkPolicy()
        assertTrue(policy.isAllowed("https://www.trenitalia.com"))
        assertTrue(policy.isAllowed("https://WWW.ITALOTRENO.COM"))
        assertTrue(policy.isAllowed("https://www.trenord.it"))
        assertFalse(policy.isAllowed("http://www.trenitalia.com"))
        // Least privilege: apex hosts and unrelated/private provider endpoints are not trusted.
        assertFalse(policy.isAllowed("https://trenitalia.com"))
        assertFalse(policy.isAllowed("https://www.lefrecce.it"))
        assertFalse(policy.isAllowed("https://biglietti.italotreno.com"))
        assertFalse(policy.isAllowed("https://www.trenitalia.com.evil.com"))
        assertFalse(policy.isAllowed("https://evil-trenitalia.com"))
        assertFalse(policy.isAllowed("https://trenitalia.com@evil.com"))
        assertFalse(policy.isAllowed("https://www.trenitalia.com:443"))
        assertFalse(policy.isAllowed("https://user:pass@www.trenitalia.com"))
        assertFalse(policy.isAllowed("https://example.com"))
        assertFalse(policy.isAllowed(""))
        assertFalse(policy.isAllowed("not a url"))
        assertFalse(policy.isAllowed("https://"))
    }

    @Test fun handoffOpensValidatedTargetWithoutPricing() = runTest {
        val opened = mutableListOf<String>()
        val handoff = OpenBookingLink { opened.add(it); true }
        val result = handoff(testJourney)
        assertEquals(listOf("https://www.trenitalia.com"), opened)
        assertEquals(BookingHandoffResult.Opened(BookingTarget("https://www.trenitalia.com", "Trenitalia")), result)
    }

    @Test fun availabilityAndExecutionShareOnePolicyConfiguration() = runTest {
        var calls = 0
        val restrictive = OpenBookingLink(BookingLinkPolicy(operatorChannels = emptyMap())) { calls++; true }
        assertFalse(restrictive.canBook(testJourney))
        assertEquals(
            BookingHandoffResult.Unavailable(BookingUnavailableReason.UNKNOWN_OPERATOR),
            restrictive(testJourney),
        )
        assertEquals(0, calls)
        val permissive = OpenBookingLink { true }
        assertTrue(permissive.canBook(testJourney))
        assertIs<BookingHandoffResult.Opened>(permissive(testJourney))
    }

    @Test fun handoffReportsUnavailableWithoutTouchingLauncher() = runTest {
        var calls = 0
        val handoff = OpenBookingLink { calls++; true }
        assertEquals(
            BookingHandoffResult.Unavailable(BookingUnavailableReason.UNKNOWN_OPERATOR),
            handoff(journeyWith("Unknown Operator")),
        )
        assertEquals(0, calls)
    }

    @Test fun handoffRejectsNonAllowlistedPolicyTargetWithoutOpening() = runTest {
        var calls = 0
        val policy = BookingLinkPolicy(operatorChannels = mapOf("trenitalia" to "https://evil.com"))
        val handoff = OpenBookingLink(policy) { calls++; true }
        assertFalse(handoff.canBook(testJourney))
        assertEquals(
            BookingHandoffResult.Unavailable(BookingUnavailableReason.REJECTED_TARGET),
            handoff(testJourney),
        )
        assertEquals(0, calls)
    }

    @Test fun launcherRefusalAndThrowBecomeFailed() = runTest {
        val target = BookingTarget("https://www.trenitalia.com", "Trenitalia")
        assertEquals(BookingHandoffResult.Failed(target), OpenBookingLink { false }(testJourney))
        assertEquals(
            BookingHandoffResult.Failed(target),
            OpenBookingLink { throw IllegalStateException("no handler") }(testJourney),
        )
    }

    @Test fun handoffDoesNotSwallowCancellation() = runTest {
        val handoff = OpenBookingLink { throw CancellationException("gone") }
        assertFailsWith<CancellationException> { handoff(testJourney) }
    }
}
