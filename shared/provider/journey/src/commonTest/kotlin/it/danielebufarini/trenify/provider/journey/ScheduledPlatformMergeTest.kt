package it.danielebufarini.trenify.provider.journey

import it.danielebufarini.trenify.core.model.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours

class ScheduledPlatformMergeTest {
    @Test fun equivalentJourneyWithoutPlatformCannotDiscardScheduledPlatformFromAnotherSource() {
        val leg = JourneyLeg(query.origin, query.destination, at + 1.hours, at + 2.hours,
            TrainNumber("123"), "Regionale", Operator("Trenitalia"))
        val missing = Journey(listOf(leg), setOf(ProviderId("a")))
        val planned = Journey(listOf(leg.copy(scheduledPlatform = "7")), setOf(ProviderId("b")))
        for (sources in listOf(listOf(missing, planned), listOf(planned, missing))) {
            val merged = mergeJourneys(sources).single()
            assertEquals("7", merged.legs.single().scheduledPlatform)
            assertEquals(setOf(ProviderId("a"), ProviderId("b")), merged.sources)
        }
    }
}
