package it.danielebufarini.trenify.provider.journey

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.network.createHttpClient
import it.danielebufarini.trenify.core.provider.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

internal val at = Instant.parse("2026-09-06T04:00:00Z")
internal val clock = object : Clock { override fun now() = at }
internal val query = JourneySearchRequest(station("Roma Termini"), station("Milano Centrale"), at)
private fun journey(number: String, source: String = "a") = Journey(listOf(JourneyLeg(query.origin, query.destination,
    at + 1.hours, at + 4.hours, TrainNumber(number), "Frecciarossa", Operator("Trenitalia"))), setOf(ProviderId(source)))

private class Source(override val id: ProviderId, val action: suspend Source.(JourneySearchRequest) -> ProviderResult<JourneySearchResult>) : JourneySource {
    override val operators = listOf(Operator("Trenitalia"))
    override val services = listOf("Rail")
    override suspend fun search(request: JourneySearchRequest) = action(request)
    override suspend fun searchStations(query: String) = ProviderResult.Success(listOf(station(query)), ProviderMetadata(id, at))
}

@OptIn(ExperimentalCoroutinesApi::class)
class JourneyProviderTest {
    @Test fun sourcesExecuteIndependentlyAndEquivalentJourneysRetainBothSources() = runTest {
        val started = CompletableDeferred<Unit>()
        val first = Source(ProviderId("a")) { request -> started.await(); result(request, listOf(journey("1")), true, at) }
        val second = Source(ProviderId("b")) { request -> started.complete(Unit); result(request, listOf(journey("1", "b"), journey("2", "b")), true, at) }
        val result = assertIs<ProviderResult.Success<JourneySearchResult>>(MultiSourceJourneyProvider(listOf(first, second), clock).search(query)).value
        assertEquals(2, result.journeys.size)
        assertEquals(setOf(ProviderId("a"), ProviderId("b")), result.journeys.first().sources)
        assertFalse(result.partial)
    }

    @Test fun firstSourceProgressIsPublishedBeforeSlowerSourceCompletes() = runTest {
        val slowStarted = CompletableDeferred<Unit>()
        val releaseSlow = CompletableDeferred<Unit>()
        val fast = object : JourneySource {
            override val id = ProviderId("fast")
            override val operators = listOf(Operator("Trenitalia"))
            override val services = listOf("Rail")
            override suspend fun searchStations(query: String) = ProviderResult.Success(listOf(station(query)), ProviderMetadata(id, at))
            override suspend fun search(request: JourneySearchRequest) = result(request, listOf(journey("1", "fast")), true, at)
            override suspend fun searchProgressively(
                request: JourneySearchRequest,
                publish: suspend (ProviderResult.Success<JourneySearchResult>) -> Unit,
            ): ProviderResult<JourneySearchResult> {
                val value = result(request, listOf(journey("1", "fast")), true, at)
                publish(value)
                return value
            }
        }
        val slow = object : JourneySource {
            override val id = ProviderId("slow")
            override val operators = listOf(Operator("Italo"))
            override val services = listOf("Rail")
            override suspend fun searchStations(query: String) = ProviderResult.Success(listOf(station(query)), ProviderMetadata(id, at))
            override suspend fun search(request: JourneySearchRequest) = result(request, listOf(journey("2", "slow")), true, at)
            override suspend fun searchProgressively(
                request: JourneySearchRequest,
                publish: suspend (ProviderResult.Success<JourneySearchResult>) -> Unit,
            ): ProviderResult<JourneySearchResult> {
                slowStarted.complete(Unit)
                releaseSlow.await()
                val value = result(request, listOf(journey("2", "slow")), true, at)
                publish(value)
                return value
            }
        }
        val updates = mutableListOf<JourneySearchResult>()
        val search = async {
            MultiSourceJourneyProvider(listOf(fast, slow), clock).searchProgressively(query) { updates += it.value }
        }
        slowStarted.await()
        assertTrue(updates.any { it.journeys.any { journey -> journey.legs.single().number == TrainNumber("1") } })
        assertFalse(search.isCompleted)
        releaseSlow.complete(Unit)
        val final = assertIs<ProviderResult.Success<JourneySearchResult>>(search.await())
        assertEquals(setOf(TrainNumber("1"), TrainNumber("2")), final.value.journeys.map { it.legs.single().number }.toSet())
        assertFalse(final.value.partial)
    }

    @Test fun exceptionAndTimeoutInOneSourcePreserveSuccessEvenWhenEmpty() = runTest {
        for (broken in listOf<Source>(Source(ProviderId("bad")) { throw IllegalStateException("HTTP detail") },
            Source(ProviderId("bad")) { awaitCancellation() })) {
            val good = Source(ProviderId("good")) { request -> result(request, emptyList(), true, at) }
            val result = assertIs<ProviderResult.Success<JourneySearchResult>>(MultiSourceJourneyProvider(listOf(broken, good), clock).search(query)).value
            assertTrue(result.journeys.isEmpty())
            assertTrue(result.partial)
            assertEquals(JourneySourceStatus.UNAVAILABLE, result.coverage.first().status)
        }
    }

    @Test fun allFailuresReturnExistingTypedFailureAndCancellationPropagates() = runTest {
        val broken = Source(ProviderId("bad")) { ProviderResult.Unavailable(false, ProviderFailure.PARSING) }
        assertEquals(ProviderResult.Unavailable(true, ProviderFailure.UNAVAILABLE), MultiSourceJourneyProvider(listOf(broken)).search(query))
        val cancelled = Source(ProviderId("cancelled")) { throw CancellationException("cancel") }
        assertFailsWith<CancellationException> { MultiSourceJourneyProvider(listOf(cancelled)).search(query) }
    }

    @Test fun sameNumberDifferentSchedulesOrOperatorsAreNotDuplicates() {
        val original = journey("1")
        val leg = original.legs.single()
        val later = original.copy(legs = listOf(leg.copy(departure = leg.departure + 1.hours, arrival = leg.arrival + 1.hours)))
        val differentOperator = original.copy(legs = listOf(leg.copy(operator = Operator("Other"))))
        assertEquals(3, mergeJourneys(listOf(original, later, differentOperator)).size)
    }

    @Test fun trenitaliaMapsObservedTimetableAndKeepsWireIdsAndPricesOutOfResults() = runTest {
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("locations/search")) respond(
                if (request.url.parameters["name"]!!.startsWith("Roma")) """[{"id":1,"name":"Roma Termini"}]"""
                else """[{"id":2,"name":"Milano Centrale"}]""", headers = jsonHeaders)
            else {
                assertEquals(HttpMethod.Post, request.method)
                val body = request.body.toByteArray().decodeToString()
                assertTrue("departureLocationId" in body)
                assertTrue("DEPARTURE_DATE" in body)
                respond(trenitaliaFixtureV1, headers = jsonHeaders)
            }
        }
        createHttpClient(engine).use { client ->
            val result = assertIs<ProviderResult.Success<JourneySearchResult>>(TrenitaliaJourneyAdapter(client, clock).search(query)).value
            assertEquals(TrainNumber("9516"), result.journeys.single().legs.single().number)
            assertEquals(Instant.parse("2026-09-06T06:05:00Z"), result.journeys.single().departure)
            assertFalse("discard-me" in result.toString())
            assertFalse("DO-NOT-EXPORT" in result.toString())
        }
    }

    @Test fun trenitaliaDepartureTimeAlwaysIncludesSecondsInRomeTime() = runTest {
        val cases = listOf(
            "2026-09-14T05:50:00Z" to "2026-09-14T07:50:00",
            "2026-09-14T05:00:00Z" to "2026-09-14T07:00:00",
            "2026-09-14T05:50:17Z" to "2026-09-14T07:50:17",
            "2026-12-14T06:50:00Z" to "2026-12-14T07:50:00",
        )
        for ((instant, expected) in cases) {
            val request = JourneySearchRequest(station("Monza"), station("Milano Centrale"), Instant.parse(instant))
            var searches = 0
            val engine = MockEngine { httpRequest ->
                if (httpRequest.url.encodedPath.endsWith("locations/search")) respond(
                    if (httpRequest.url.parameters["name"] == "Monza") """[{"id":830001322,"name":"Monza"}]"""
                    else """[{"id":830001700,"name":"Milano Centrale"}]""", headers = jsonHeaders)
                else {
                    searches++
                    assertEquals(HttpMethod.Post, httpRequest.method)
                    val body = Json.parseToJsonElement(httpRequest.body.toByteArray().decodeToString()).jsonObject
                    assertEquals(expected, body.getValue("departureTime").jsonPrimitive.content)
                    respond("""{"solutions":[]}""", headers = jsonHeaders)
                }
            }
            createHttpClient(engine).use { client ->
                assertIs<ProviderResult.Success<JourneySearchResult>>(TrenitaliaJourneyAdapter(client, clock).search(request))
            }
            assertEquals(1, searches)
        }
    }

    @Test fun trenitaliaRegionalJourneyPreservesLegitimatelyUnknownOperator() = runTest {
        val request = JourneySearchRequest(
            station("Monza"),
            station("Milano Centrale"),
            Instant.parse("2026-09-18T06:00:00Z"),
        )
        val engine = MockEngine { httpRequest ->
            if (httpRequest.url.encodedPath.endsWith("locations/search")) respond(
                if (httpRequest.url.parameters["name"] == "Monza")
                    """[{"id":830001322,"name":"Monza"}]"""
                else
                    """[{"id":830001700,"name":"Milano Centrale"}]""",
                headers = jsonHeaders,
            ) else {
                respond(trenitaliaRegional2815Fixture, headers = jsonHeaders)
            }
        }

        createHttpClient(engine).use { client ->
            val result = assertIs<ProviderResult.Success<JourneySearchResult>>(
                TrenitaliaJourneyAdapter(client, clock).search(request),
            ).value
            val leg = result.journeys.single().legs.single()
            assertEquals(TrainNumber("2815"), leg.number)
            assertEquals("Regionale", leg.category)
            assertNull(leg.operator)
            assertEquals(StationId("station:monza"), leg.origin.id)
            assertEquals(StationId("station:milano centrale"), leg.destination.id)
        }
    }

    @Test fun italoGuestProtocolPollsAndMapsLocalRomeTimesWithoutPublishingSessionData() = runTest {
        // 23:00 Rome: the 8-hour window crosses midnight, so both daily
        // booking queries run (two polls) and the fixture departure stays
        // inside the window.
        val request = JourneySearchRequest(
            station("Roma Termini"), station("Milano Centrale"), Instant.parse("2026-09-05T21:00:00Z"),
        )
        var polls = 0
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/api/login") -> respond("{}", headers = headersOf(HttpHeaders.SetCookie, "BIGSessionToken=synthetic-token; Path=/; Secure"))
                request.url.encodedPath.endsWith("stations/list") -> respond("""{"RMT":{"name":"Roma Termini"},"MC_":{"name":"Milano Centrale"}}""", headers = jsonHeaders)
                request.url.encodedPath.endsWith("stations") -> respond(if (request.url.parameters["sn"]!!.startsWith("Roma"))
                    """{"stations":[{"stationCode":"RMT","name":"Roma Termini"}]}"""
                    else """{"stations":[{"stationCode":"MC_","name":"Milano Centrale"}]}""", headers = jsonHeaders)
                request.url.encodedPath.endsWith("booking") -> {
                    assertEquals("Bearer synthetic-token", request.headers[HttpHeaders.Authorization])
                    respond("""{"operationId":"test-operation","pollAfter":1}""", HttpStatusCode.Accepted, jsonHeaders)
                }
                else -> { polls++; respond(italoFixtureV1, headers = jsonHeaders) }
            }
        }
        createHttpClient(engine).use { client ->
            val result = assertIs<ProviderResult.Success<JourneySearchResult>>(ItaloJourneyAdapter(client, clock).search(request)).value
            assertEquals(TrainNumber("9908"), result.journeys.single().legs.single().number)
            assertEquals(Instant.parse("2026-09-06T04:35:00Z"), result.journeys.single().departure)
            assertEquals(2, polls)
            assertFalse("synthetic-token" in result.toString())
            assertFalse("MC_" in result.toString())
        }
    }

    @Test fun arriveByUsesArrivalDeadlineAndIncludesPreviousDayDepartures() = runTest {
        val arrival = query.copy(at = at + 6.hours, mode = JourneySearchMode.ARRIVE_BY)
        val source = Source(ProviderId("a")) { request ->
            assertEquals(arrival.at - 8.hours, request.departureFrom)
            result(request, listOf(journey("1"), journey("2").let { it.copy(legs = it.legs.map { leg -> leg.copy(arrival = at + 7.hours) }) }), true, at)
        }
        val result = assertIs<ProviderResult.Success<JourneySearchResult>>(MultiSourceJourneyProvider(listOf(source)).search(arrival)).value
        assertEquals(listOf(TrainNumber("1")), result.journeys.map { it.legs.single().number })
    }

    @Test fun malformedAndHttpErrorsAreTyped() = runTest {
        for ((body, status) in listOf("<html>changed</html>" to HttpStatusCode.OK, "{}" to HttpStatusCode.TooManyRequests)) {
            createHttpClient(MockEngine { respond(body, status, jsonHeaders) }).use { client ->
                assertIs<ProviderResult.Unavailable>(TrenitaliaJourneyAdapter(client).searchStations("Roma"))
            }
        }
    }
}

private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
