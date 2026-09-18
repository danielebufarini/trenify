package it.danielebufarini.trenify.app

import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.ProviderId
import it.danielebufarini.trenify.core.model.aged
import it.danielebufarini.trenify.core.model.journeySourceNames
import it.danielebufarini.trenify.core.model.trainSourceName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * FR-DATA-001 source-identity mapping (T7.14 corrective): known provider
 * identities resolve to stable human-readable names, unknown identities
 * stay unknown (never guessed from operator names), multi-source journeys
 * expose every genuinely contributing source, and fetch vs source-update
 * timestamps remain distinct values.
 */
class SourceIdentityTest {
    @Test fun knownProvidersResolveToStableNames() {
        assertEquals("ViaggiaTreno", trainSourceName(ProviderId("viaggiatreno")))
        assertEquals("Trenitalia", trainSourceName(ProviderId("trenitalia-journeys")))
        assertEquals("Italo", trainSourceName(ProviderId("italo-journeys")))
        assertEquals("MIT", trainSourceName(ProviderId("mit-strikes")))
    }

    @Test fun unknownProvidersStayUnknownInsteadOfGuessed() {
        assertNull(trainSourceName(ProviderId("test")))
        assertNull(trainSourceName(ProviderId("multi-source-journey")))
        assertNull(trainSourceName(ProviderId("")))
        assertNull(trainSourceName(ProviderId("trenitalia")))
    }

    @Test fun multiSourceJourneyExposesAllGenuineSources() {
        val names = journeySourceNames(setOf(ProviderId("trenitalia-journeys"), ProviderId("italo-journeys")))
        assertEquals("Italo, Trenitalia", names)
    }

    @Test fun journeySourcesNeverInventOrGuess() {
        assertNull(journeySourceNames(emptySet()))
        assertNull(journeySourceNames(setOf(ProviderId("test"))))
        // A partially known set exposes only the genuinely known sources.
        assertEquals("ViaggiaTreno", journeySourceNames(setOf(ProviderId("viaggiatreno"), ProviderId("test"))))
    }

    @Test fun fetchAndSourceUpdateTimestampsRemainDistinct() {
        val fetchedAt = kotlin.time.Instant.parse("2026-09-05T08:00:00Z")
        val sourceTimestamp = kotlin.time.Instant.parse("2026-09-05T07:58:30Z")
        val fresh = DataFreshness.Fresh(fetchedAt, sourceTimestamp)
        assertEquals(fetchedAt, fresh.fetchedAt)
        assertEquals(sourceTimestamp, fresh.sourceTimestamp)
        assertTrue(fresh.fetchedAt != fresh.sourceTimestamp)
        // Elapsed-time aging preserves both instants while marking staleness.
        val stale = fresh.aged(fetchedAt + 60.minutes, 30.seconds)
        assertTrue(stale is DataFreshness.Stale)
        assertEquals(fetchedAt, (stale as DataFreshness.Stale).fetchedAt)
        assertEquals(sourceTimestamp, stale.sourceTimestamp)
    }
}
