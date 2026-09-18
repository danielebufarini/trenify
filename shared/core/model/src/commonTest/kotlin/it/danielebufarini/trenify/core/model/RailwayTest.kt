package it.danielebufarini.trenify.core.model

import kotlinx.datetime.LocalDate
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class RailwayTest {
    @Test fun runIdentityIncludesDateOriginAndProviderWithoutDelimiterCollisions() {
        val id = TrainRunId(ProviderId("one"), TrainNumber("123"), ExternalStationRef("origin"), LocalDate.parse("2026-09-05"))
        assertNotEquals(id.key, id.copy(serviceDate = LocalDate.parse("2026-09-06")).key)
        assertNotEquals(id.key, id.copy(origin = ExternalStationRef("other")).key)
        assertNotEquals(id.key, id.copy(provider = ProviderId("other")).key)
        assertNotEquals(id.copy(provider = ProviderId("one:123")).key, id.copy(origin = ExternalStationRef("123:origin")).key)
    }
    @Test fun romeServiceDateDoesNotUseUtcOrDeviceTimezone() {
        assertEquals(LocalDate.parse("2026-09-06"), RailwayTime.serviceDate(Instant.parse("2026-09-05T22:30:00Z")))
        assertEquals(Instant.parse("2026-01-04T23:00:00Z"), RailwayTime.startOfDay(LocalDate.parse("2026-01-05")))
        assertEquals(Instant.parse("2026-09-04T22:00:00Z"), RailwayTime.startOfDay(LocalDate.parse("2026-09-05")))
    }
    @Test fun springAndAutumnServiceDaysHave23And25Hours() {
        fun start(date: String) = RailwayTime.startOfDay(LocalDate.parse(date))
        assertEquals(23.hours, start("2026-03-30") - start("2026-03-29"))
        assertEquals(25.hours, start("2026-10-26") - start("2026-10-25"))
        assertEquals(RailwayTime.serviceDate(Instant.parse("2026-10-25T00:30:00Z")),
            RailwayTime.serviceDate(Instant.parse("2026-10-25T01:30:00Z")))
    }
    @Test fun stationSearchNormalizesAccentsAndWhitespace() {
        assertEquals("forli porta", normalizeStationQuery("  FORLÌ   Porta "))
        assertFailsWith<IllegalArgumentException> { TrainNumber("") }
    }
}

