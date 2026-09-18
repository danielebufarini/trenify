package it.danielebufarini.trenify.app

import it.danielebufarini.trenify.core.domain.StrikeChangeKind
import it.danielebufarini.trenify.core.model.StrikeRelevance
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.testing.testStrike
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Real shared notification wording in the default (English) locale
 * (T7.14-C, simulator-only: the JVM host cannot resolve real resources).
 * Italian catalog completeness is enforced by the resource-parity check;
 * identity stability across locales is covered by
 * [NotificationLocalizationTest] on both legs.
 */
class ResourceNotificationLocalizerTest {
    private val localizer = ResourceNotificationLocalizer()

    @Test fun trainDelayKnownPrevious() = runTest {
        assertEquals("Delay changed from 5 to 20 minutes", localizer.delayChanged(5, 20))
    }

    @Test fun trainDelayUnknownPreviousNeverBecomesZero() = runTest {
        // FR-DATA-002: a previously unknown delay must read as unknown,
        // never as a fabricated change from 0.
        val body = localizer.delayChanged(null, 20)
        assertEquals("Delay is now 20 minutes (previously unknown)", body)
        assertFalse("from 0" in body)
    }

    @Test fun trainTitleAndTerminalEvents() = runTest {
        assertEquals("Train 9624", localizer.trainTitle("9624"))
        assertEquals("The train has been cancelled", localizer.trainCancelled())
        assertEquals("The train is partially cancelled", localizer.trainPartiallyCancelled(emptyList()))
        assertEquals(
            "Cancelled stops: Roma Termini, Milano Centrale",
            localizer.trainPartiallyCancelled(listOf("Roma Termini", "Milano Centrale")),
        )
        assertEquals("The train has departed", localizer.trainDeparted())
        assertEquals("The train has arrived", localizer.trainArrived())
        assertEquals("The train route has changed", localizer.trainRouteChanged())
    }

    @Test fun platformScheduleAndStatusEvents() = runTest {
        assertEquals("platform changed to 9", localizer.platformChanged(null, "9"))
        assertEquals("Roma Termini: platform changed to 9", localizer.platformChanged("Roma Termini", "9"))
        assertEquals("Schedule changed at Roma Termini", localizer.scheduleChanged("Roma Termini"))
        assertEquals("Status changed to Cancelled", localizer.statusChanged(TrainStatus.CANCELLED))
        assertEquals("Status changed to Unknown", localizer.statusChanged(TrainStatus.UNKNOWN))
    }

    @Test fun strikeTitlesAndBody() = runTest {
        assertEquals("Railway strike scheduled", localizer.strikeTitle(StrikeChangeKind.SCHEDULED))
        assertEquals("Railway strike updated", localizer.strikeTitle(StrikeChangeKind.MODIFIED))
        assertEquals("Railway strike revoked", localizer.strikeTitle(StrikeChangeKind.REVOKED))
        val area = localizer.strikeArea(testStrike.geography.regions, testStrike.geography.relevance)
        assertEquals("Piemonte", area)
        val fallback = localizer.strikeArea(emptyList(), StrikeRelevance.NATIONAL)
        assertEquals("National", fallback)
        val unknown = localizer.strikeArea(emptyList(), StrikeRelevance.UNKNOWN)
        assertEquals("Unknown", unknown)
        val body = localizer.strikeBody(
            testStrike.sector, area,
            localizer.strikeMoment(testStrike.start), localizer.strikeMoment(testStrike.end),
        )
        assertTrue(body.startsWith("Ferroviario · Piemonte · "))
        assertFalse("null" in body)
    }
}
