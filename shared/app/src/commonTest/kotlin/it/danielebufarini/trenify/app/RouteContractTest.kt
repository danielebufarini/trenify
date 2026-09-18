package it.danielebufarini.trenify.app

import it.danielebufarini.trenify.core.model.ExternalStationRef
import it.danielebufarini.trenify.core.model.ProviderId
import it.danielebufarini.trenify.core.model.Station
import it.danielebufarini.trenify.core.model.StationId
import it.danielebufarini.trenify.core.model.TrainLookupIntent
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.model.TrainRunId
import it.danielebufarini.trenify.core.testing.journeyDestination
import it.danielebufarini.trenify.core.testing.testRunId
import it.danielebufarini.trenify.core.testing.testStation
import it.danielebufarini.trenify.feature.journey.DefaultJourneyTabComponent
import it.danielebufarini.trenify.feature.journey.JourneyTabComponent
import it.danielebufarini.trenify.feature.journey.lookupKey
import it.danielebufarini.trenify.feature.journey.request
import it.danielebufarini.trenify.feature.journey.serialized
import it.danielebufarini.trenify.core.testing.journeyRequest
import it.danielebufarini.trenify.core.testing.testJourney
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Serialized route/state contract pins (T7.13-A evidence): every durable
 * configuration round-trips through JSON carrying minimal IDs/criteria
 * only, and malformed persisted forms decode to controlled recovery
 * (null) rather than invented identities.
 */
class RouteContractTest {
    private val format = Json { ignoreUnknownKeys = true }

    @Test
    fun rootDetailCarriesNormalizedRunIdentityOnly() {
        val route = DefaultRootComponent.Route.Detail.of(testRunId)
        val json = format.encodeToString(DefaultRootComponent.Route.serializer(), route)
        assertTrue(""""provider":"test"""" in json)
        assertTrue(""""number":"123"""" in json)
        val restored = format.decodeFromString(DefaultRootComponent.Route.serializer(), json)
        assertEquals(route, restored)
        assertEquals(testRunId, (restored as DefaultRootComponent.Route.Detail).trainRunId())
    }

    @Test
    fun rootDetailRejectsMalformedPersistence() {
        assertNull(malformedDetail("", "123", "o", "2026-09-05"))
        assertNull(malformedDetail("test", "12X", "o", "2026-09-05"))
        assertNull(malformedDetail("test", "123", "", "2026-09-05"))
        assertNull(malformedDetail("test", "123", "o", "yesterday"))
    }

    private fun malformedDetail(provider: String, number: String, origin: String, date: String): TrainRunId? =
        DefaultRootComponent.Route.Detail(provider, number, origin, date).trainRunId()

    @Test
    fun rootBoardCarriesStationIdOnly() {
        val route = DefaultRootComponent.Route.Board.of(testStation)
        val json = format.encodeToString(DefaultRootComponent.Route.serializer(), route)
        // No display name travels in the route: only the stable id.
        assertTrue(""""stationId":"${testStation.id.value}""" in json)
        assertTrue("stationName" !in json)
        val restored = format.decodeFromString(DefaultRootComponent.Route.serializer(), json)
        assertEquals(route, restored)
        assertEquals(testStation.id, (restored as DefaultRootComponent.Route.Board).stationId())
        assertNull(DefaultRootComponent.Route.Board("").stationId())
    }

    @Test
    fun rootSearchCarriesCriteriaAndDiscrimination() {
        val intent = TrainLookupIntent(
            number = TrainNumber("123"),
            originId = testStation.id,
            operator = null,
        )
        val route = DefaultRootComponent.Route.Search(
            number = "123",
            serviceDate = "2026-09-05",
            autoSearch = true,
            expected = intent.serialized(),
            generation = 7L,
        )
        val json = format.encodeToString(DefaultRootComponent.Route.serializer(), route)
        val restored = format.decodeFromString(DefaultRootComponent.Route.serializer(), json)
        assertEquals(route, restored)
        val decoded = (restored as DefaultRootComponent.Route.Search)
        assertEquals("2026-09-05", decoded.serviceDate()?.toString())
        assertNotNull(decoded.expected())
    }

    @Test
    fun journeyDetailKeyRoundTripsWithoutJourneySnapshot() {
        val key = testJourney.lookupKey(journeyRequest)
        val route = DefaultJourneyTabComponent.Route.Detail(key)
        val json = format.encodeToString(DefaultJourneyTabComponent.Route.serializer(), route)
        // No realtime snapshot, coverage or sources travel in the route.
        assertTrue("sources" !in json)
        val restored = format.decodeFromString(DefaultJourneyTabComponent.Route.serializer(), json)
        assertEquals(route, restored)
    }

    @Test
    fun journeyResultsRouteRoundTrips() {
        val route = DefaultJourneyTabComponent.Route.Results(journeyRequest.serialized())
        val json = format.encodeToString(DefaultJourneyTabComponent.Route.serializer(), route)
        val restored = format.decodeFromString(DefaultJourneyTabComponent.Route.serializer(), json)
        assertEquals(route, restored)
        assertEquals(journeyRequest, (restored as DefaultJourneyTabComponent.Route.Results).request.request())
    }

    @Test
    fun trainRunIdConversionsRejectNumberOnlyGuesses() {
        // The routing helper requires the full normalized identity.
        val id = TrainRunId(ProviderId("test"), TrainNumber("123"), ExternalStationRef("o"), testRunId.serviceDate)
        assertEquals(id, it.danielebufarini.trenify.core.platform.NotificationDestination.Train(
            "test", "123", "o", testRunId.serviceDate.toString()).trainRunId())
    }

    @Test
    fun stationIdentityRoundTrips() {
        val station = Station(StationId("s1"), journeyDestination.name)
        assertEquals(station.id, DefaultRootComponent.Route.Board.of(station).stationId())
    }
}
