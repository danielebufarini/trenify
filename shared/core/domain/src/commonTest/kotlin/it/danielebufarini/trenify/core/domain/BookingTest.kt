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

    @Test fun previousHomepageChannelsAreIndividuallyIneligible() {
        val policy = BookingLinkPolicy()
        listOf("Trenitalia", "TRENITALIA", "Trenitalia Tper", "Italo", "Trenord").forEach {
            assertNull(policy.target(journeyWith(it)), it)
        }
        assertNull(policy.target(journeyWith("Italo", "Trenitalia")))
    }

    @Test fun unknownOrMissingOperatorHasNoApprovedTarget() {
        val policy = bookingContractTestPolicy()
        assertNull(policy.target(journeyWith("Unknown Operator")))
        assertNull(policy.target(journeyWith(null)))
        assertNull(policy.target(journeyWith("Italo", "Trenitalia")))
    }

    @Test fun registeredContractReceivesSelectedJourneyAndEncodesBothStations() {
        // Synthetic contract tests the policy, not live Trenitalia support.
        val journey = testJourney.copy(legs = testJourney.legs.map {
            it.copy(origin = it.origin.copy(name = "Reggio nell'Emilia & città"),
                destination = it.destination.copy(name = "Milano/Centrale?x=1"))
        })
        val target = assertNotNull(bookingContractTestPolicy().target(journey))
        assertEquals("https://www.trenitalia.com/contract-test?from=Reggio%20nell%27Emilia%20%26%20citt%C3%A0&to=Milano%2FCentrale%3Fx%3D1", target.url)
        assertEquals("Trenitalia", target.operatorName)
        val other = assertNotNull(bookingContractTestPolicy().target(testJourney))
        assertNotEquals(other.url, target.url)
    }

    @Test fun genericHomepageAndUnverifiedParametersAreNotBookingDestinations() {
        listOf("https://www.trenitalia.com", "https://www.italotreno.com", "https://www.trenord.it").forEach { url ->
            val homepage = BookingChannel(url, "from", "to", "", parameters = { emptyMap() })
            assertNull(BookingLinkPolicy(mapOf("trenitalia" to homepage)).target(testJourney))
            // Parameter-looking URLs alone do not constitute verification evidence.
            val guessed = BookingChannel(url, "from", "to", "", parameters = {
                mapOf("from" to it.origin.name, "to" to it.destination.name)
            })
            assertNull(BookingLinkPolicy(mapOf("trenitalia" to guessed)).target(testJourney))
        }
    }

    @Test fun requiredStationParametersMustBothBePresentAndNonBlank() = runTest {
        val cases = listOf<Map<String, String>?>(null, emptyMap(), mapOf("from" to "Roma"),
            mapOf("to" to "Milano"), mapOf("from" to "", "to" to "Milano"),
            mapOf("from" to "Roma", "to" to "  "))
        for (parameters in cases) {
            val channel = BookingChannel("https://www.trenitalia.com/contract-test", "from", "to",
                "Synthetic contract double", parameters = { parameters })
            var calls = 0
            val handoff = OpenBookingLink(BookingLinkPolicy(mapOf("trenitalia" to channel))) { calls++; true }
            assertFalse(handoff.canBook(testJourney))
            assertIs<BookingHandoffResult.Unavailable>(handoff(testJourney))
            assertEquals(0, calls)
        }
    }

    @Test fun contractCanTransferSupportedAdditionalContext() {
        val channel = BookingChannel("https://www.trenitalia.com/contract-test", "from", "to",
            "Synthetic contract double", parameters = {
                mapOf("from" to it.origin.name, "to" to it.destination.name, "date" to "2026-10-08",
                    "time" to "10:30", "train" to it.legs.single().number!!.value)
            })
        val target = assertNotNull(BookingLinkPolicy(mapOf("trenitalia" to channel)).target(testJourney))
        assertTrue("&date=2026-10-08&time=10%3A30&train=123" in target.url)
        // The simpler contract needs neither date nor train: they are optional.
        assertNotNull(bookingContractTestPolicy().target(testJourney))
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
        assertFalse(policy.isAllowed("https://www.trenitalia.com/path with spaces"))
        assertFalse(policy.isAllowed("https://www.trenitalia.com/%GG"))
        assertFalse(policy.isAllowed("https://www.trenitalia.com/path\\evil"))
        assertFalse(policy.isAllowed("https://www.trenitalia.com/\n"))
    }

    @Test fun handoffOpensValidatedTargetWithoutPricing() = runTest {
        val opened = mutableListOf<String>()
        val handoff = OpenBookingLink(bookingContractTestPolicy()) { opened.add(it); true }
        val result = handoff(testJourney)
        assertEquals(listOf("https://www.trenitalia.com/contract-test?from=Roma%20Termini&to=Milano%20Centrale"), opened)
        assertEquals(BookingHandoffResult.Opened(BookingTarget("https://www.trenitalia.com/contract-test?from=Roma%20Termini&to=Milano%20Centrale", "Trenitalia")), result)
    }

    @Test fun availabilityAndExecutionShareOnePolicyConfiguration() = runTest {
        var calls = 0
        val restrictive = OpenBookingLink(BookingLinkPolicy(operatorChannels = emptyMap())) { calls++; true }
        assertFalse(restrictive.canBook(testJourney))
        assertEquals(
            BookingHandoffResult.Unavailable(BookingUnavailableReason.NO_QUALIFYING_TARGET),
            restrictive(testJourney),
        )
        assertEquals(0, calls)
        val permissive = OpenBookingLink(bookingContractTestPolicy()) { true }
        assertTrue(permissive.canBook(testJourney))
        assertIs<BookingHandoffResult.Opened>(permissive(testJourney))
    }

    @Test fun handoffReportsUnavailableWithoutTouchingLauncher() = runTest {
        var calls = 0
        val handoff = OpenBookingLink { calls++; true }
        assertEquals(
            BookingHandoffResult.Unavailable(BookingUnavailableReason.NO_QUALIFYING_TARGET),
            handoff(journeyWith("Unknown Operator")),
        )
        assertEquals(0, calls)
    }

    @Test fun handoffRejectsNonAllowlistedPolicyTargetWithoutOpening() = runTest {
        var calls = 0
        val policy = BookingLinkPolicy(operatorChannels = mapOf("trenitalia" to bookingContractTestChannel("https://evil.com/contract-test")))
        val handoff = OpenBookingLink(policy) { calls++; true }
        assertFalse(handoff.canBook(testJourney))
        assertEquals(
            BookingHandoffResult.Unavailable(BookingUnavailableReason.REJECTED_TARGET),
            handoff(testJourney),
        )
        assertEquals(0, calls)
    }

    @Test fun everyInvalidDestinationIsRejectedBeforeTheLauncher() = runTest {
        val urls = listOf("http://www.trenitalia.com/search", "https://example.com/search",
            "https://www.trenitalia.com.evil.com/search", "https://user@www.trenitalia.com/search",
            "https://www.trenitalia.com:443/search", "https://www.trenitalia.com/path with spaces",
            "https://www.trenitalia.com/%GG", "not a url")
        for (url in urls) {
            var calls = 0
            val policy = BookingLinkPolicy(mapOf("trenitalia" to bookingContractTestChannel(url)))
            val handoff = OpenBookingLink(policy) { calls++; true }
            assertFalse(handoff.canBook(testJourney), url)
            assertIs<BookingHandoffResult.Unavailable>(handoff(testJourney), url)
            assertEquals(0, calls, url)
        }
    }

    @Test fun launcherRefusalAndThrowBecomeFailed() = runTest {
        val target = BookingTarget("https://www.trenitalia.com/contract-test?from=Roma%20Termini&to=Milano%20Centrale", "Trenitalia")
        assertEquals(BookingHandoffResult.Failed(target), OpenBookingLink(bookingContractTestPolicy()) { false }(testJourney))
        assertEquals(
            BookingHandoffResult.Failed(target),
            OpenBookingLink(bookingContractTestPolicy()) { throw IllegalStateException("no handler") }(testJourney),
        )
    }

    @Test fun handoffDoesNotSwallowCancellation() = runTest {
        val handoff = OpenBookingLink(bookingContractTestPolicy()) { throw CancellationException("gone") }
        assertFailsWith<CancellationException> { handoff(testJourney) }
    }
}
