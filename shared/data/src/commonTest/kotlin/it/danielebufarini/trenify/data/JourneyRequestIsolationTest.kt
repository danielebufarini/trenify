package it.danielebufarini.trenify.data

import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.provider.api.*
import it.danielebufarini.trenify.core.testing.*
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours

private class EchoJourneyProvider(private val clock: MutableClock) : JourneyProvider {
    override val id = ProviderId("echo")
    var calls = 0
    override suspend fun searchStations(query: String) =
        ProviderResult.Success(emptyList<Station>(), ProviderMetadata(id, clock.now()))

    override suspend fun search(request: JourneySearchRequest): ProviderResult<JourneySearchResult> {
        calls++
        val leg = JourneyLeg(request.origin, request.destination, request.at + 1.hours,
            request.at + 2.hours, TrainNumber("7"), "Regionale", Operator("Echo"))
        val journey = Journey(listOf(leg), setOf(id))
        val coverage = JourneyCoverage(id, listOf(Operator("Echo")), listOf("Rail"),
            JourneySourceStatus.AVAILABLE, true)
        return ProviderResult.Success(
            JourneySearchResult(listOf(journey), listOf(coverage), request.departureFrom, request.departureUntil),
            ProviderMetadata(id, clock.now()),
        )
    }
}

/** A stale previous journey request must never overwrite a newer search. */
@OptIn(ExperimentalCoroutinesApi::class)
class JourneyRequestIsolationTest {
    @Test fun concurrentSearchesForDifferentRequestsKeepTheirOwnWindows() = runTest {
        val driver = createRepositoryTestDriver()
        try {
            val clock = MutableClock()
            val provider = EchoJourneyProvider(clock)
            val repository = SqlDelightJourneyRepository(TrenifyDatabase(driver), provider, backgroundScope,
                clock = clock, dispatcher = StandardTestDispatcher(testScheduler))
            val first = journeyRequest
            val second = first.copy(at = first.at + 1.hours)
            val deferredA = async { repository.search(first, force = true) }
            val deferredB = async { repository.search(second, force = true) }
            val resultA = assertIs<DataResult.Data<JourneySearchResult>>(deferredA.await())
            val resultB = assertIs<DataResult.Data<JourneySearchResult>>(deferredB.await())
            assertEquals(2, provider.calls)
            assertEquals(first.departureFrom, resultA.value.departureFrom)
            assertEquals(first.departureUntil, resultA.value.departureUntil)
            assertEquals(second.departureFrom, resultB.value.departureFrom)
            assertEquals(second.departureUntil, resultB.value.departureUntil)
            assertEquals(listOf(first.at + 1.hours), resultA.value.journeys.map { it.departure })
            assertEquals(listOf(second.at + 1.hours), resultB.value.journeys.map { it.departure })
            // Observations stay keyed: neither result leaks into the other request.
            assertEquals(resultA, repository.observe(first).first())
            assertEquals(resultB, repository.observe(second).first())
        } finally { driver.close() }
    }
}
