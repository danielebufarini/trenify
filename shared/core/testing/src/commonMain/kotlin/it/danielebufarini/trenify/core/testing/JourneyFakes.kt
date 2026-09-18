package it.danielebufarini.trenify.core.testing

import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.provider.api.*
import kotlinx.coroutines.flow.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

val journeyDestination = Station(StationId("internal-milan"), "Milano Centrale")
val journeyRequest = JourneySearchRequest(testStation, journeyDestination, MutableClock().now())
val testJourney = Journey(listOf(JourneyLeg(testStation, journeyDestination, journeyRequest.at,
    journeyRequest.at + 3.hours, TrainNumber("123"), "Frecciarossa", Operator("Trenitalia"))), setOf(ProviderId("test")))
val journeyResult = JourneySearchResult(listOf(testJourney), listOf(JourneyCoverage(ProviderId("test"),
    listOf(Operator("Trenitalia")), listOf("Frecce"), JourneySourceStatus.AVAILABLE, true)),
    journeyRequest.departureFrom, journeyRequest.departureUntil)

/** This module is a test dependency only; production never depends on core:testing. */
class FakeJourneyProvider(private val clock: Clock = MutableClock()) : JourneyProvider {
    override val id = ProviderId("test-journey")
    var calls = 0
    var beforeSearch: suspend () -> Unit = {}
    var result: ProviderResult<JourneySearchResult> = ProviderResult.Success(journeyResult, ProviderMetadata(id, clock.now()))
    override suspend fun search(request: JourneySearchRequest): ProviderResult<JourneySearchResult> {
        calls++
        beforeSearch()
        return result
    }
    override suspend fun searchStations(query: String) = ProviderResult.Success(
        listOf(testStation, journeyDestination).filter { normalizeStationQuery(query) in it.normalizedName }, ProviderMetadata(id, clock.now()))
}

class FakeJourneyRepository : JourneyRepository {
    var calls = 0
    var beforeSearch: suspend () -> Unit = {}
    val state = MutableStateFlow<DataResult<JourneySearchResult>>(DataResult.Data(journeyResult, DataFreshness.Fresh(MutableClock().now(), null)))
    override suspend fun searchStations(query: String) = DataResult.Data(
        listOf(testStation, journeyDestination).filter { normalizeStationQuery(query) in it.normalizedName }, DataFreshness.Unknown)
    override fun observe(request: JourneySearchRequest): Flow<DataResult<JourneySearchResult>> = state
    override suspend fun search(request: JourneySearchRequest, force: Boolean): DataResult<JourneySearchResult> {
        calls++
        beforeSearch()
        return state.value
    }
}
