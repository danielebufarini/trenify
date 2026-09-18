package it.danielebufarini.trenify.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class FavoriteRouteTest {
    @Test
    fun orderedStationIdentityIsStableUnambiguousAndNameIndependent() {
        val origin = Station(StationId("ab:c"), "Same label")
        val destination = Station(StationId("a:bc"), "Same label")
        val route = FavoriteRoute.create(origin, destination)

        assertEquals(route.id, FavoriteRoute.create(origin.copy(name = "Renamed"), destination).id)
        assertNotEquals(route.id, FavoriteRoute.create(destination, origin).id)
        assertEquals("4:ab:c4:a:bc", route.id.value)
        assertFailsWith<IllegalArgumentException> { FavoriteRoute.create(origin, origin.copy(name = "Other")) }
    }
}
