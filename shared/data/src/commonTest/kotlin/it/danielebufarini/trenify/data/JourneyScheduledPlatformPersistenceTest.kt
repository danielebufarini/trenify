package it.danielebufarini.trenify.data

import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.provider.api.*
import it.danielebufarini.trenify.core.testing.*
import it.danielebufarini.trenify.database.TrenifyDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

class JourneyScheduledPlatformPersistenceTest {
    @Test fun providerScheduledPlatformSurvivesCacheReopenAndOfflineFailure() = runTest {
        val driver = createRepositoryTestDriver()
        try {
            val database = TrenifyDatabase(driver)
            val clock = MutableClock()
            val journey = testJourney.copy(legs = testJourney.legs.map { it.copy(scheduledPlatform = "7") })
            val result = journeyResult.copy(journeys = listOf(journey))
            val provider = FakeJourneyProvider(clock).apply { this.result = ProviderResult.Success(result, ProviderMetadata(id, clock.now())) }
            val repository = SqlDelightJourneyRepository(database, provider, backgroundScope, clock = clock,
                dispatcher = StandardTestDispatcher(testScheduler))
            assertEquals("7", assertIs<DataResult.Data<JourneySearchResult>>(repository.search(journeyRequest)).value.journeys.single().legs.single().scheduledPlatform)
            val reopened = SqlDelightJourneyRepository(database, provider, backgroundScope, online = { false }, clock = clock,
                dispatcher = StandardTestDispatcher(testScheduler))
            assertEquals("7", assertIs<DataResult.Data<JourneySearchResult>>(reopened.observe(journeyRequest).first()).value.journeys.single().legs.single().scheduledPlatform)
            val offline = assertIs<DataResult.Data<JourneySearchResult>>(reopened.search(journeyRequest, true))
            assertEquals(DomainFailure.OFFLINE, offline.warning)
            assertEquals("7", offline.value.journeys.single().legs.single().scheduledPlatform)
        } finally {
            driver.close()
        }
    }

    @Test fun legacyScheduledJourneyCacheWithoutPlatformRemainsReadable() {
        val json = Json { ignoreUnknownKeys = true }
        val legacy = json.encodeToString(JourneyResultRecord.serializer(), journeyResult.record())
        assertFalse("scheduledPlatform" in legacy)
        assertEquals(journeyResult, json.decodeFromString(JourneyResultRecord.serializer(), legacy).model())
    }
}
