package it.danielebufarini.trenify.feature.journey

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.SearchJourneys
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Journey
import it.danielebufarini.trenify.core.model.JourneyLeg
import it.danielebufarini.trenify.core.model.JourneySearchRequest
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.testing.FakeJourneyRepository
import it.danielebufarini.trenify.core.testing.journeyResult
import it.danielebufarini.trenify.core.testing.journeyDestination
import it.danielebufarini.trenify.core.testing.journeyRequest
import it.danielebufarini.trenify.core.testing.testJourney
import it.danielebufarini.trenify.core.testing.testStation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class JourneyLookupKeyTest {
    @Test
    fun keyRoundTripsThroughJsonWithoutDomainSnapshots() {
        val key = testJourney.lookupKey(journeyRequest)
        val json = Json.encodeToString(key)
        // The persisted form carries criteria only: no provider wire payload,
        // no coverage, no realtime snapshot.
        assertFalse("sources" in json)
        val restored = Json.decodeFromString<JourneyLookupKey>(json)
        assertEquals(key, restored)
        assertNotNull(restored.searchRequest())
    }

    @Test
    fun exactJourneyMatches() {
        val key = testJourney.lookupKey(journeyRequest)
        assertEquals(testJourney, key.resolveIn(listOf(testJourney)))
    }

    @Test
    fun sameLookingServicesDoNotMatch() {
        val key = testJourney.lookupKey(journeyRequest)
        val leg = testJourney.legs.single()
        // Same endpoints and number, different scheduled time: another service.
        val shifted = testJourney.copy(legs = listOf(
            leg.copy(departure = leg.departure + 1.hours, arrival = leg.arrival + 1.hours),
        ))
        assertNull(key.resolveIn(listOf(shifted)))
        // Same times, different number: another service.
        val renumbered = testJourney.copy(legs = listOf(leg.copy(number = TrainNumber("999"))))
        assertNull(key.resolveIn(listOf(renumbered)))
        // Unknown journey entirely.
        assertNull(key.resolveIn(emptyList()))
    }

    @Test
    fun ambiguousMatchesResolveToNothing() {
        val key = testJourney.lookupKey(journeyRequest)
        assertNull(key.resolveIn(listOf(testJourney, testJourney)))
    }

    @Test
    fun malformedKeysHaveNoRequest() {
        val key = testJourney.lookupKey(journeyRequest)
        val blankOrigin = key.request.origin.copy(id = "")
        val blankRequest = key.request.copy(origin = blankOrigin)
        val blanked = key.copy(request = blankRequest)
        // A malformed originating request decodes to nothing, so the
        // restore path cannot even start a repository lookup.
        assertNull(blanked.searchRequest())
        // ...while leg identity is unaffected: matching still works for the
        // intact legs, and blanking a leg breaks the match instead.
        assertEquals(testJourney, blanked.resolveIn(listOf(testJourney)))
        val blankLeg = key.legs.single().copy(number = "999")
        assertNull(key.copy(legs = listOf(blankLeg)).resolveIn(listOf(testJourney)))
    }
}

class JourneyDetailRestoreTest {
    private fun kotlinx.coroutines.test.TestScope.restored(
        repository: FakeJourneyRepository,
        key: JourneyLookupKey = testJourney.lookupKey(journeyRequest),
    ) = JourneyDetailComponent.restored(
        DefaultComponentContext(LifecycleRegistry()),
        key,
        search = SearchJourneys(repository),
        correlate = null,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun freshCacheResolvesWithoutPresentingSerializedSnapshot() = runTest {
        val repository = FakeJourneyRepository()
        val component = restored(repository)
        testScheduler.advanceUntilIdle()
        // Nothing is presented before repository observation resolves it.
        assertNotNull(component.journey)
        assertEquals(testJourney, component.journey)
        assertFalse(component.state.value.resolving)
        assertFalse(component.state.value.notFound)
    }

    @Test
    fun recoverableLookupResolvesAfterForcedRefresh() = runTest {
        // Staged repository: the cached observation misses, the forced
        // lookup recovers. FakeJourneyRepository runs its beforeSearch hook
        // before returning the current state, so toggling state there stages
        // the two sequential lookups.
        val staged = FakeJourneyRepository()
        val healthy = staged.state.value
        staged.state.value = DataResult.Failure(DomainFailure.NOT_FOUND)
        staged.beforeSearch = {
            // calls is already incremented when this hook runs: the first
            // (cached) lookup misses, the second (forced) lookup recovers.
            if (staged.calls > 1) staged.state.value = healthy
        }
        val component = restored(staged)
        testScheduler.advanceUntilIdle()
        assertEquals(2, staged.calls)
        assertEquals(testJourney, component.journey)
        assertFalse(component.state.value.notFound)
    }

    @Test
    fun expiredTargetReachesControlledNotFound() = runTest {
        val repository = FakeJourneyRepository()
        repository.state.value = DataResult.Failure(DomainFailure.NOT_FOUND)
        val component = restored(repository)
        testScheduler.advanceUntilIdle()
        assertNull(component.journey)
        assertFalse(component.state.value.resolving)
        assertTrue(component.state.value.notFound)
    }

    @Test
    fun ambiguousRestorationNeverOpensWrongService() = runTest {
        val repository = FakeJourneyRepository()
        val leg = testJourney.legs.single()
        val twin = testJourney.copy(legs = listOf(
            leg.copy(departure = leg.departure + 1.hours, arrival = leg.arrival + 1.hours),
        ))
        repository.state.value = DataResult.Data(
            journeyResult.copy(journeys = listOf(testJourney, testJourney, twin)),
            DataFreshness.Unknown,
        )
        // A key matching several identical snapshots is ambiguous.
        val component = restored(repository)
        testScheduler.advanceUntilIdle()
        assertNull(component.journey)
        assertTrue(component.state.value.notFound)
    }

    @Test
    fun malformedKeyReachesControlledNotFound() = runTest {
        val repository = FakeJourneyRepository()
        val key = testJourney.lookupKey(journeyRequest)
        val malformed = key.copy(request = key.request.copy(origin = key.request.origin.copy(id = "")))
        val component = restored(repository, malformed)
        testScheduler.advanceUntilIdle()
        assertNull(component.journey)
        assertTrue(component.state.value.notFound)
    }

    @Test
    fun livePathStillResolvesImmediately() = runTest {
        val lifecycle = LifecycleRegistry()
        val component = JourneyDetailComponent(
            DefaultComponentContext(lifecycle),
            testJourney,
            correlate = null,
            onTrain = {},
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        lifecycle.resume()
        runCurrent()
        assertEquals(testJourney, component.journey)
        assertFalse(component.state.value.resolving)
        assertFalse(component.state.value.notFound)
        lifecycle.destroy()
    }
}
