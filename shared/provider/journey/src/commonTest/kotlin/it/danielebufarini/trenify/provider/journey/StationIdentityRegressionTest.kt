package it.danielebufarini.trenify.provider.journey

import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.provider.api.*
import it.danielebufarini.trenify.core.testing.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import kotlin.time.Duration.Companion.hours

class StationIdentityRegressionTest {
    @Test fun trenitaliaLocationIdentityDoesNotDependOnOtherCandidates() = identityAcrossQueries(false)
    @Test fun italoLocationIdentityDoesNotDependOnOtherCandidates() = identityAcrossQueries(true)

    private fun identityAcrossQueries(italo: Boolean) = runBlocking {
        val clock = MutableClock()
        val fixture = HomonymousStationFixture(italo, clock)
        try {
            suspend fun lookup(query: String) = assertIs<ProviderResult.Success<List<Station>>>(fixture.source.searchStations(query)).value
            val both = lookup("paternion")
            assertEquals(2, both.size)
            assertNotEquals(both[0].id, both[1].id)
            val first = both.single { it.id.value == "location:${fixture.providerId}:${fixture.firstRef}" }
            val only = lookup("feistritz").single()
            assertEquals(first.id, only.id, "A physical location must retain its identity when its homonym is absent")
            assertEquals(first, only)
            assertEquals(both.reversed(), lookup("paternion f"))
            assertEquals(only, lookup("feistritz").single())
            assertEquals(3, fixture.queries.size, "Fresh cache must not add HTTP requests")
            clock.instant += 25.hours
            fixture.reverseExactResponse = true
            assertEquals(both.reversed(), lookup("paternion"))
            assertEquals(only, lookup("feistritz").single())
        } finally { fixture.close() }
        val restarted = HomonymousStationFixture(italo, clock)
        try {
            val only = assertIs<ProviderResult.Success<List<Station>>>(restarted.source.searchStations("feistritz")).value.single()
            assertEquals(StationId("location:${restarted.providerId}:${restarted.firstRef}"), only.id)
        } finally { restarted.close() }
    }

    @Test fun bothProtocolsSelectTheExactLocationAndRejectAmbiguousLegacyIds() = runBlocking {
        for (italo in listOf(false, true)) {
            val clock = MutableClock()
            val fixture = HomonymousStationFixture(italo, clock)
            try {
                val twins = assertIs<ProviderResult.Success<List<Station>>>(fixture.provider.searchStations("paternion")).value
                val origin = station("Milano Centrale")
                for (destination in twins) {
                    assertIs<ProviderResult.Success<JourneySearchResult>>(fixture.source.search(JourneySearchRequest(origin, destination, clock.now())))
                    assertEquals(destination.id.value.substringAfterLast(':'), fixture.selectedRefs.last())
                }
                assertEquals(setOf(fixture.firstRef, fixture.secondRef), fixture.selectedRefs.toSet())
                val count = fixture.selectedRefs.size
                val legacy = station("Paternion-Feistritz")
                assertEquals(ProviderResult.Unavailable(false, ProviderFailure.UNSUPPORTED),
                    fixture.source.search(JourneySearchRequest(origin, legacy, clock.now())))
                assertEquals(count, fixture.selectedRefs.size, "Ambiguous legacy identity must not submit a journey")
                assertIs<ProviderResult.Success<JourneySearchResult>>(fixture.source.search(
                    JourneySearchRequest(origin, station("Roma Termini"), clock.now())))
                assertEquals(if (italo) "RMT" else "830008409", fixture.selectedRefs.last())
            } finally { fixture.close() }
        }
    }

    @Test fun crossCatalogNamesResolveOnlyWhenUniqueAndOwnReferencesNeverSelectAnotherLocation() = runBlocking {
        for (italo in listOf(false, true)) {
            val clock = MutableClock()
            val fixture = HomonymousStationFixture(italo, clock)
            try {
                val origin = station("Milano Centrale")
                val foreignUnique = Station(StationId("location:another-catalog:foreign-unique"), "Roma Termini")
                assertIs<ProviderResult.Success<JourneySearchResult>>(fixture.source.search(JourneySearchRequest(origin, foreignUnique, clock.now())))
                assertEquals(if (italo) "RMT" else "830008409", fixture.selectedRefs.single())
                val foreignAmbiguous = Station(StationId("location:another-catalog:foreign-twin"), "Paternion-Feistritz")
                val wrongOwnRef = Station(StationId("location:${fixture.providerId}:missing-ref"), "Paternion-Feistritz")
                for (destination in listOf(foreignAmbiguous, wrongOwnRef)) {
                    assertEquals(ProviderResult.Unavailable(false, ProviderFailure.UNSUPPORTED),
                        fixture.source.search(JourneySearchRequest(origin, destination, clock.now())))
                }
                assertEquals(1, fixture.selectedRefs.size, "Neither ambiguous bridges nor mismatched references may submit")
            } finally { fixture.close() }
        }
    }
}
