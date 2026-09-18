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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private class TestClock(var now: Instant) : Clock {
    override fun now() = now
}

private val probeAt = Instant.parse("2026-09-18T06:00:00Z")
private fun probeClock() = TestClock(probeAt)
private val cacheHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderStationCacheTest {
    // Cache unit semantics: TTL, negative TTL, failure handling, bounds, single flight.

    @Test fun hitWithinTtlReusesValueWithoutRefetch() = runTest {
        val clock = probeClock()
        val cache = ProviderLocalCache<List<String>>(clock, ttl = 24.hours)
        var fetches = 0
        assertEquals(listOf("a"), cache.getOrFetch("roma") { fetches++; listOf("a") })
        clock.now += 23.hours
        assertEquals(listOf("a"), cache.getOrFetch("roma") { fetches++; listOf("b") })
        assertEquals(1, fetches)
    }

    @Test fun expiryTriggersRefetch() = runTest {
        val clock = probeClock()
        val cache = ProviderLocalCache<List<String>>(clock, ttl = 24.hours)
        var fetches = 0
        cache.getOrFetch("roma") { fetches++; listOf("a") }
        clock.now += 25.hours
        assertEquals(listOf("b"), cache.getOrFetch("roma") { fetches++; listOf("b") })
        assertEquals(2, fetches)
    }

    @Test fun negativeEntriesExpireEarlierThanPositiveOnes() = runTest {
        val clock = probeClock()
        val cache = ProviderLocalCache<List<String>>(clock, ttl = 24.hours, negativeTtl = 1.hours, isNegative = { it.isEmpty() })
        val scripted = mutableListOf(listOf<String>(), emptyList(), listOf("a"))
        var fetches = 0
        assertEquals(emptyList(), cache.getOrFetch("monza") { scripted[fetches++] })
        clock.now += 61.minutes
        assertEquals(emptyList(), cache.getOrFetch("monza") { scripted[fetches++] })
        assertEquals(2, fetches)
        clock.now += 61.minutes
        assertEquals(listOf("a"), cache.getOrFetch("monza") { scripted[fetches++] })
        assertEquals(3, fetches)
        clock.now += 2.hours
        assertEquals(listOf("a"), cache.getOrFetch("monza") { scripted[fetches++] })
        assertEquals(3, fetches)
    }

    @Test fun failuresAreNeverCached() = runTest {
        val clock = probeClock()
        val cache = ProviderLocalCache<String>(clock, ttl = 24.hours)
        var fetches = 0
        assertFailsWith<IllegalStateException> {
            cache.getOrFetch("roma") { fetches++; throw IllegalStateException("boom") }
        }
        assertEquals("ok", cache.getOrFetch("roma") { fetches++; "ok" })
        assertEquals(2, fetches)
        assertEquals("ok", cache.getOrFetch("roma") { fetches++; "changed" })
        assertEquals(2, fetches)
    }

    @Test fun eldestEntryIsEvictedWhenBounded() = runTest {
        val clock = probeClock()
        val cache = ProviderLocalCache<String>(clock, ttl = 24.hours, maxEntries = 2)
        val fetches = mutableMapOf<String, Int>()
        suspend fun get(key: String): String = cache.getOrFetch(key) {
            fetches[key] = (fetches[key] ?: 0) + 1
            "$key-${fetches[key]}"
        }
        assertEquals("a-1", get("a"))
        assertEquals("b-1", get("b"))
        assertEquals("c-1", get("c"))
        assertEquals("a-2", get("a"))
        assertEquals("b-2", get("b"))
        assertEquals("c-2", get("c"))
    }

    @Test fun concurrentMissFetchesOnlyOnce() = runTest {
        val cache = ProviderLocalCache<String>(probeClock(), ttl = 24.hours)
        var fetches = 0
        val gate = CompletableDeferred<Unit>()
        val results = (1..5).map {
            async {
                cache.getOrFetch("roma") {
                    fetches++
                    gate.await()
                    "shared"
                }
            }
        }
        // No mid-flight scheduling assertion: any interleaving must end with
        // exactly one upstream fetch, which is the property under test.
        gate.complete(Unit)
        assertEquals(List(5) { "shared" }, results.awaitAll())
        assertEquals(1, fetches)
    }

    // Adapter behavior over a fake transport: no real Internet.

    @Test fun trenitaliaRepeatStationLookupCallsUpstreamOnce() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("""[{"id":1,"name":"Roma Termini"}]""", headers = cacheHeaders)
        }
        createHttpClient(engine).use { client ->
            val adapter = TrenitaliaJourneyAdapter(client, probeClock())
            val first = assertIs<ProviderResult.Success<List<Station>>>(adapter.searchStations("Roma Termini"))
            val second = assertIs<ProviderResult.Success<List<Station>>>(adapter.searchStations("Roma Termini"))
            assertEquals(first.value, second.value)
            assertEquals(StationId("station:roma termini"), first.value.single().id)
            assertEquals(1, calls)
        }
    }

    @Test fun trenitaliaPositiveResolutionUsesPositiveTtl() = runTest {
        val clock = TestClock(probeAt)
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("""[{"id":1,"name":"Roma Termini"}]""", headers = cacheHeaders)
        }
        createHttpClient(engine).use { client ->
            val adapter = TrenitaliaJourneyAdapter(client, clock)
            adapter.searchStations("Roma Termini")
            clock.now += 23.hours
            adapter.searchStations("Roma Termini")
            assertEquals(1, calls)
            clock.now += 2.hours
            adapter.searchStations("Roma Termini")
            assertEquals(2, calls)
        }
    }

    @Test fun trenitaliaEmptyResolutionUsesShortNegativeTtlAndRecovers() = runTest {
        val clock = TestClock(probeAt)
        var calls = 0
        val engine = MockEngine {
            calls++
            // The first two upstream lookups see a transient empty response;
            // afterwards the station exists.
            if (calls <= 2) respond("""[]""", headers = cacheHeaders)
            else respond("""[{"id":9,"name":"Nuova Stazione"}]""", headers = cacheHeaders)
        }
        createHttpClient(engine).use { client ->
            val adapter = TrenitaliaJourneyAdapter(client, clock)
            suspend fun stations(): List<Station> =
                assertIs<ProviderResult.Success<List<Station>>>(adapter.searchStations("Nuova Stazione")).value
            assertTrue(stations().isEmpty())
            assertTrue(stations().isEmpty())
            assertEquals(1, calls)
            clock.now += 61.minutes
            assertTrue(stations().isEmpty())
            assertEquals(2, calls)
            clock.now += 61.minutes
            assertEquals(1, stations().size)
            assertEquals(3, calls)
            // The replacing positive entry honors the full positive TTL.
            clock.now += 23.hours
            assertEquals(1, stations().size)
            assertEquals(3, calls)
        }
    }

    @Test fun trenitaliaFailedLookupIsNotCachedAsNegative() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) respond("<html>changed</html>", HttpStatusCode.OK, cacheHeaders)
            else respond("""[{"id":1,"name":"Roma Termini"}]""", headers = cacheHeaders)
        }
        createHttpClient(engine).use { client ->
            val adapter = TrenitaliaJourneyAdapter(client, probeClock())
            assertIs<ProviderResult.Unavailable>(adapter.searchStations("Roma Termini"))
            val recovered = assertIs<ProviderResult.Success<List<Station>>>(
                adapter.searchStations("Roma Termini")).value
            assertEquals(1, recovered.size)
            assertEquals(2, calls)
            adapter.searchStations("Roma Termini")
            assertEquals(2, calls)
        }
    }

    @Test fun trenitaliaDifferentStationQueriesDoNotCollide() = runTest {
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            val name = request.url.parameters["name"]!!
            respond("""[{"id":${name.length},"name":"$name"}]""", headers = cacheHeaders)
        }
        createHttpClient(engine).use { client ->
            val adapter = TrenitaliaJourneyAdapter(client, probeClock())
            adapter.searchStations("Roma Termini")
            adapter.searchStations("Milano Centrale")
            adapter.searchStations("Roma Termini")
            assertEquals(2, calls)
        }
    }

    @Test fun trenitaliaConcurrentLookupsShareOneUpstreamCall() = runTest {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val engine = MockEngine {
            calls++
            gate.await()
            respond("""[{"id":1,"name":"Roma Termini"}]""", headers = cacheHeaders)
        }
        createHttpClient(engine).use { client ->
            val adapter = TrenitaliaJourneyAdapter(client, probeClock())
            val results = (1..5).map { async { adapter.searchStations("Roma Termini") } }
            // No mid-flight scheduling assertion: every interleaving (shared
            // in-flight fetch or sequential cache hits) must end with exactly
            // one upstream call, which is the property under test.
            gate.complete(Unit)
            val values = results.awaitAll().map { assertIs<ProviderResult.Success<List<Station>>>(it).value }
            assertTrue(values.all { it == values.first() })
            assertEquals(1, calls)
        }
    }

    @Test fun providersKeepSeparateStationCaches() = runTest {
        var trenitaliaCalls = 0
        var italoCalls = 0
        val trenitaliaEngine = MockEngine {
            trenitaliaCalls++
            respond("""[{"id":1,"name":"Roma Termini"}]""", headers = cacheHeaders)
        }
        val italoEngine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/api/login") ->
                    respond("{}", headers = headersOf(HttpHeaders.SetCookie, "BIGSessionToken=t; Path=/"))
                else -> {
                    italoCalls++
                    respond("""{"stations":[{"stationCode":"RMT","name":"Roma Termini"}]}""", headers = cacheHeaders)
                }
            }
        }
        createHttpClient(trenitaliaEngine).use { trenitaliaClient ->
            createHttpClient(italoEngine).use { italoClient ->
                val trenitalia = TrenitaliaJourneyAdapter(trenitaliaClient, probeClock())
                val italo = ItaloJourneyAdapter(italoClient, probeClock())
                trenitalia.searchStations("Roma Termini")
                italo.searchStations("Roma Termini")
                trenitalia.searchStations("Roma Termini")
                italo.searchStations("Roma Termini")
                assertEquals(1, trenitaliaCalls)
                assertEquals(1, italoCalls)
            }
        }
    }

    @Test fun italoUnsupportedStationAvoidsRepeatCallsWithinNegativeTtl() = runTest {
        var logins = 0
        var lookups = 0
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/api/login") -> {
                    logins++
                    respond("{}", headers = headersOf(HttpHeaders.SetCookie, "BIGSessionToken=t; Path=/"))
                }
                else -> {
                    lookups++
                    val query = request.url.parameters["sn"]!!
                    respond(
                        if (query == "Monza") """{"stations":[]}"""
                        else """{"stations":[{"stationCode":"MC_","name":"Milano Centrale"}]}""",
                        headers = cacheHeaders,
                    )
                }
            }
        }
        createHttpClient(engine).use { client ->
            val adapter = ItaloJourneyAdapter(client, probeClock())
            val request = JourneySearchRequest(station("Monza"), station("Milano Centrale"), probeAt)
            val first = assertIs<ProviderResult.Unavailable>(adapter.search(request))
            assertEquals(ProviderFailure.UNSUPPORTED, first.cause)
            val second = assertIs<ProviderResult.Unavailable>(adapter.search(request))
            assertEquals(ProviderFailure.UNSUPPORTED, second.cause)
            assertEquals(1, logins)
            assertEquals(2, lookups)
        }
    }

    private fun italoSuccessEngine(
        bookings: MutableList<String>,
        catalogueCalls: IntArray,
        loginCalls: IntArray,
    ) = MockEngine { request ->
        when {
            request.url.encodedPath.endsWith("/api/login") -> {
                loginCalls[0]++
                respond("{}", headers = headersOf(HttpHeaders.SetCookie, "BIGSessionToken=t; Path=/"))
            }
            request.url.encodedPath.endsWith("stations/list") -> {
                catalogueCalls[0]++
                respond("""{"RMT":{"name":"Roma Termini"},"MC_":{"name":"Milano Centrale"}}""", headers = cacheHeaders)
            }
            request.url.encodedPath.endsWith("stations") -> respond(
                if (request.url.parameters["sn"] == "Roma Termini")
                    """{"stations":[{"stationCode":"RMT","name":"Roma Termini"}]}"""
                else """{"stations":[{"stationCode":"MC_","name":"Milano Centrale"}]}""",
                headers = cacheHeaders,
            )
            request.url.encodedPath.endsWith("booking") -> {
                val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                bookings += body.getValue("departureDate").jsonPrimitive.content
                respond(
                    """{"bookingId":"x","trips":[{"direction":"forward","travelSolutions":[{"journeys":[
                    |{"serviceProvider":"ITALO","sequence":1,"segments":[{"departureStation":"RMT","arrivalStation":"MC_",
                    |"std":"2026-09-18T08:35:00","sta":"2026-09-18T12:20:00","trainNumber":"9908","carrierCode":"VF"}]}]
                    |}]}]}""".trimMargin(),
                    headers = cacheHeaders,
                )
            }
            else -> error("unexpected ${request.url.encodedPath}")
        }
    }

    @Test fun italoCatalogueIsReusedAcrossSearchesWhileBookingRepeats() = runTest {
        val bookings = mutableListOf<String>()
        val catalogueCalls = intArrayOf(0)
        val loginCalls = intArrayOf(0)
        createHttpClient(italoSuccessEngine(bookings, catalogueCalls, loginCalls)).use { client ->
            val adapter = ItaloJourneyAdapter(client, probeClock())
            val request = JourneySearchRequest(station("Roma Termini"), station("Milano Centrale"), probeAt)
            val first = assertIs<ProviderResult.Success<JourneySearchResult>>(adapter.search(request)).value
            val second = assertIs<ProviderResult.Success<JourneySearchResult>>(adapter.search(request)).value
            assertEquals(listOf(TrainNumber("9908")), first.journeys.map { it.legs.single().number })
            assertEquals(first, second)
            assertEquals(listOf("2026-09-18", "2026-09-18"), bookings)
            assertEquals(1, catalogueCalls[0])
            // One isolated guest session per search is intentional: planning
            // state must never leak between concurrent searches.
            assertEquals(2, loginCalls[0])
        }
    }

    @Test fun italoBookingIsScopedToWindowCalendarDays() = runTest {
        val bookings = mutableListOf<String>()
        val catalogueCalls = intArrayOf(0)
        val loginCalls = intArrayOf(0)
        createHttpClient(italoSuccessEngine(bookings, catalogueCalls, loginCalls)).use { client ->
            val adapter = ItaloJourneyAdapter(client, probeClock())
            val sameDay = JourneySearchRequest(station("Roma Termini"), station("Milano Centrale"), probeAt)
            assertIs<ProviderResult.Success<JourneySearchResult>>(adapter.search(sameDay))
            assertEquals(listOf("2026-09-18"), bookings)
            bookings.clear()
            // 20:00 Rome: the 8-hour window crosses midnight, so two daily
            // booking queries are required; a same-calendar-day rule would
            // wrongly keep one.
            val crossing = JourneySearchRequest(
                station("Roma Termini"), station("Milano Centrale"), Instant.parse("2026-09-18T18:00:00Z"),
            )
            assertIs<ProviderResult.Success<JourneySearchResult>>(adapter.search(crossing))
            assertEquals(listOf("2026-09-18", "2026-09-19"), bookings)
        }
    }

    private fun solutionPage(departures: List<Pair<String, String>>, firstNumber: Int): String {
        val solutions = departures.mapIndexed { index, (departure, arrival) ->
            """{"solution":{"nodes":[{"origin":"Monza","destination":"Milano Centrale","departureTime":"$departure","arrivalTime":"$arrival","train":{"name":"${firstNumber + index}","trainCategory":"Regionale","acronym":"REG"}}]}}"""
        }.joinToString(",")
        return """{"solutions":[$solutions]}"""
    }

    @Test fun trenitaliaPaginationStopsWhenDeparturesPassTheWindow() = runTest {
        val request = JourneySearchRequest(station("Monza"), station("Milano Centrale"), probeAt)
        var solutionPosts = 0
        val engine = MockEngine { httpRequest ->
            if (httpRequest.url.encodedPath.endsWith("locations/search")) {
                val name = httpRequest.url.parameters["name"]!!
                respond(
                    if (name == "Monza") """[{"id":830001322,"name":"Monza"}]"""
                    else """[{"id":830001700,"name":"Milano Centrale"}]""",
                    headers = cacheHeaders,
                )
            } else {
                solutionPosts++
                val body = Json.parseToJsonElement(httpRequest.body.toByteArray().decodeToString()).jsonObject
                val offset = body.getValue("criteria").jsonObject.getValue("offset").jsonPrimitive.int
                if (offset == 0) {
                    // Full page inside the 08:00-16:00 Rome window.
                    val departures = (0 until 10).map { i ->
                        val depMinute = 5 + i * 5
                        val arrMinute = depMinute + 30
                        "2026-09-18T08:${depMinute.toString().padStart(2, '0')}:00" to
                            "2026-09-18T${if (arrMinute >= 60) "09" else "08"}:${(arrMinute % 60).toString().padStart(2, '0')}:00"
                    }
                    respond(solutionPage(departures, 10000), headers = cacheHeaders)
                } else {
                    // Past the 16:00 Rome window end: pagination must stop here
                    // instead of fetching the remaining pages for 24 hours.
                    respond(
                        solutionPage(listOf("2026-09-18T17:00:00" to "2026-09-18T17:30:00"), 10010),
                        headers = cacheHeaders,
                    )
                }
            }
        }
        createHttpClient(engine).use { client ->
            val result = assertIs<ProviderResult.Success<JourneySearchResult>>(
                TrenitaliaJourneyAdapter(client, probeClock()).search(request),
            ).value
            assertEquals(2, solutionPosts)
            assertEquals(10, result.journeys.size)
            assertTrue(result.journeys.all { it.departure <= request.departureUntil })
            assertFalse(result.partial)
        }
    }

    @Test fun trenitaliaPublishesEachPageBeforeCompletion() = runTest {
        val request = JourneySearchRequest(station("Monza"), station("Milano Centrale"), probeAt)
        val engine = MockEngine { httpRequest ->
            if (httpRequest.url.encodedPath.endsWith("locations/search")) {
                val name = httpRequest.url.parameters["name"]!!
                respond(
                    if (name == "Monza") """[{"id":830001322,"name":"Monza"}]"""
                    else """[{"id":830001700,"name":"Milano Centrale"}]""",
                    headers = cacheHeaders,
                )
            } else {
                val body = Json.parseToJsonElement(httpRequest.body.toByteArray().decodeToString()).jsonObject
                val offset = body.getValue("criteria").jsonObject.getValue("offset").jsonPrimitive.int
                if (offset == 0) {
                    val departures = (0 until 10).map { i ->
                        "2026-09-18T08:${(5 + i).toString().padStart(2, '0')}:00" to
                            "2026-09-18T08:${(35 + i).toString().padStart(2, '0')}:00"
                    }
                    respond(solutionPage(departures, 10000), headers = cacheHeaders)
                } else {
                    respond(
                        solutionPage(listOf("2026-09-18T09:00:00" to "2026-09-18T09:30:00"), 10010),
                        headers = cacheHeaders,
                    )
                }
            }
        }
        createHttpClient(engine).use { client ->
            val published = mutableListOf<JourneySearchResult>()
            val final = assertIs<ProviderResult.Success<JourneySearchResult>>(
                TrenitaliaJourneyAdapter(client, probeClock()).searchProgressively(request) { published += it.value },
            ).value
            assertEquals(2, published.size)
            assertEquals(10, published.first().journeys.size)
            assertTrue(published.first().partial)
            assertEquals(11, published.last().journeys.size)
            assertEquals(11, final.journeys.size)
            assertFalse(final.partial)
        }
    }

    @Test fun concurrentSearchesForDifferentRequestsKeepTheirOwnWindows() = runTest {
        val echoId = ProviderId("echo")
        val echo = object : JourneySource {
            override val id = echoId
            override val operators = listOf(Operator("Echo"))
            override val services = listOf("Rail")
            override suspend fun searchStations(query: String) =
                ProviderResult.Success(listOf(station(query)), ProviderMetadata(id, probeAt))
            override suspend fun search(request: JourneySearchRequest): ProviderResult<JourneySearchResult> {
                val leg = JourneyLeg(request.origin, request.destination, request.at + 1.hours,
                    request.at + 2.hours, TrainNumber("7"), "Regionale", Operator("Echo"))
                return result(request, listOf(Journey(legs = listOf(leg), sources = setOf(id))), true, probeAt)
            }
        }
        val provider = MultiSourceJourneyProvider(listOf(echo), probeClock())
        val first = JourneySearchRequest(station("Roma Termini"), station("Milano Centrale"), probeAt)
        val second = JourneySearchRequest(station("Roma Termini"), station("Milano Centrale"), probeAt + 1.hours)
        val deferredA = async { provider.searchProgressively(first) { } }
        val deferredB = async { provider.searchProgressively(second) { } }
        val resultA = assertIs<ProviderResult.Success<JourneySearchResult>>(deferredA.await()).value
        val resultB = assertIs<ProviderResult.Success<JourneySearchResult>>(deferredB.await()).value
        assertEquals(first.departureFrom, resultA.departureFrom)
        assertEquals(first.departureUntil, resultA.departureUntil)
        assertEquals(second.departureFrom, resultB.departureFrom)
        assertEquals(second.departureUntil, resultB.departureUntil)
        assertEquals(listOf(probeAt + 1.hours), resultA.journeys.map { it.departure })
        assertEquals(listOf(probeAt + 2.hours), resultB.journeys.map { it.departure })
    }
}
