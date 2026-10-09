package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.provider.api.*
import it.danielebufarini.trenify.core.testing.*
import it.danielebufarini.trenify.data.SqlDelightJourneyRepository
import it.danielebufarini.trenify.database.TrenifyDatabase
import it.danielebufarini.trenify.feature.journey.JourneySearchComponent
import it.danielebufarini.trenify.feature.journey.JourneySearchState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours

class StationAutocompleteIntegrationTest {
    @Test fun coldAndPopulatedRepositoryCachesResolveMultiwordWithStableIdentity() = runBlocking {
        for (coldFirst in listOf(false, true)) {
            val driver = createOrderingTestDriver()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val clock = MutableClock()
            val fixture = StationAutocompleteFixture(clock)
            try {
                val repository = SqlDelightJourneyRepository(TrenifyDatabase(driver), fixture.provider, scope, clock = clock)
                suspend fun lookup(query: String) = assertIs<DataResult.Data<List<Station>>>(repository.searchStations(query)).value
                if (coldFirst) assertEquals(listOf(StationId("location:trenitalia-journeys:830007230")), lookup("narni amelia").map { it.id })
                val full = lookup("narni").single { it.name == "Narni-Amelia" }
                val spaced = lookup("narni amelia")
                assertEquals(listOf(full), spaced)
                if (coldFirst) {
                    assertEquals(listOf("narni amelia", "narni"), fixture.locationQueries)
                } else {
                    assertEquals(listOf("narni", "narni amelia"), fixture.locationQueries)
                }
                val punctuated = lookup("Narni-Amelia").single()
                assertEquals(full.id, punctuated.id)
                assertEquals(StationId("location:trenitalia-journeys:830007230"), punctuated.id)
                assertIs<DataResult.Data<JourneySearchResult>>(repository.search(JourneySearchRequest(
                    Station(StationId("station:milano centrale"), "Milano Centrale"), punctuated, clock.now())))
                assertEquals(830007230L, fixture.arrivalLocationIds.single())
                clock.instant += 25.hours
                assertEquals(listOf(full), lookup("narni amelia"), "Expired token data must recover through a fresh bounded lookup")
                assertEquals(punctuated.id, lookup("Narni-Amelia").single().id)
            } finally { scope.cancel(); fixture.close(); driver.close() }
        }
    }

    @Test fun rapidMultiwordQueriesWithoutCompletedFirstTokenNeverAdoptStaleSuggestions() = runBlocking {
        val driver = createOrderingTestDriver()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = MutableClock()
        val fixture = StationAutocompleteFixture(clock)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        fixture.beforeLocationResponse = { query ->
            if (query == "narni") {
                firstStarted.complete(Unit)
                withContext(NonCancellable) { releaseFirst.await() }
            }
        }
        val repository = SqlDelightJourneyRepository(TrenifyDatabase(driver), fixture.provider, scope, clock = clock)
        val lifecycle = LifecycleRegistry()
        val owner = NativeProjectionOwner()
        val component = JourneySearchComponent(DefaultComponentContext(lifecycle), repository, {}, Dispatchers.Unconfined, clock)
        val input = NativeJourneySearchPresentation(component, owner)
        val snapshots = MutableStateFlow<List<JourneySearchState>>(emptyList())
        val observation = component.state.subscribe { state -> snapshots.update { it + state } }
        lifecycle.resume()
        suspend fun until(label: String, condition: () -> Boolean) {
            try { withTimeout(5_000) { while (!condition()) delay(5) } }
            catch (failure: TimeoutCancellationException) {
                throw AssertionError("$label: queries=${fixture.locationQueries}; state=${component.state.value}", failure)
            }
        }
        try {
            input.stationText(false, "narni")
            withTimeout(5_000) { firstStarted.await() }
            input.stationText(false, "narni amelia")
            until("cold multiword fallback started") { fixture.locationQueries.count { it == "narni" } == 2 && component.state.value.loading }
            // The cancelled first-token owner and its replacement remain blocked until releaseFirst.
            assertTrue(component.state.value.suggestions.isEmpty(), "Pending fallback cannot publish suggestions")
            assertNull(component.state.value.failure)
            input.selectStation(Station(StationId("location:trenitalia-journeys:830007230"), "Narni-Amelia"))
            assertNull(component.state.value.destination, "A stale/nonexistent suggestion cannot be selected")

            fixture.failTrenitalia = true; fixture.failItalo = true
            input.stationText(false, "roma")
            until("both failed providers completed") { component.state.value.failure == DomainFailure.TEMPORARY && !component.state.value.loading }
            assertTrue(input.state.value.suggestions.isEmpty())
            fixture.failTrenitalia = false; fixture.failItalo = false
            releaseFirst.complete(Unit)
            input.stationText(false, "roma tib")
            until("cold Roma Tiburtina completed") { component.state.value.suggestions.isNotEmpty() && !component.state.value.loading }
            val suggestions = component.state.value.suggestions
            assertEquals(3, suggestions.size)
            assertEquals(StationId("location:italo-journeys:RTB"), suggestions.first().id)
            // The shared HTTP client already retries a failed GET twice; this correction adds none.
            assertEquals(listOf("narni", "narni amelia", "narni", "roma", "roma", "roma", "roma tib"), fixture.locationQueries)
            assertTrue(snapshots.value.filter { it.destinationText in listOf("narni amelia", "roma", "roma tib") }
                .none { state -> state.suggestions.any { it.name == "Narni-Amelia" } }, "Late first-token results must never render for a newer query")
            input.selectStation(suggestions.first())
            assertEquals(suggestions.first().id, input.state.value.destination?.id)
            assertIs<DataResult.Data<JourneySearchResult>>(repository.search(JourneySearchRequest(
                Station(StationId("station:milano centrale"), "Milano Centrale"), assertNotNull(input.state.value.destination), clock.now())))
            assertEquals(830008217L, fixture.arrivalLocationIds.single())
        } finally {
            releaseFirst.complete(Unit); observation.cancel(); owner.close(); lifecycle.destroy()
            scope.cancel(); fixture.close(); driver.close()
        }
    }

    @Test fun distinctSameNameLocationsCannotReuseAnotherStationsJourneyCache() = runBlocking {
        val driver = createOrderingTestDriver()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = MutableClock()
        // One available source produces a complete cache entry, exposing any identity collision.
        val fixture = StationAutocompleteFixture(clock, includeItalo = false)
        try {
            val repository = SqlDelightJourneyRepository(TrenifyDatabase(driver), fixture.provider, scope, clock = clock)
            val stations = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("terni")).value
            val twins = stations.filter { it.name == "Paternion-Feistritz" }
            assertEquals(2, twins.size)
            assertNotEquals(twins[0].id, twins[1].id)
            val origin = Station(StationId("station:milano centrale"), "Milano Centrale")
            for (destination in twins) {
                val result = assertIs<DataResult.Data<JourneySearchResult>>(repository.search(JourneySearchRequest(origin, destination, clock.now())))
                assertFalse(result.value.partial)
            }
            assertEquals(2, fixture.arrivalLocationIds.size, "Both distinct selections must reach their own provider location")
            assertEquals(twins.map { it.id.value.substringAfterLast(':').toLong() }, fixture.arrivalLocationIds)
        } finally { scope.cancel(); fixture.close(); driver.close() }
    }
    @Test fun recordedHttpRepositoryCacheComponentAndNativeFacadePreserveOrderAndSelection() = runBlocking {
        val driver = createOrderingTestDriver()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = MutableClock()
        val fixture = StationAutocompleteFixture(clock)
        val database = TrenifyDatabase(driver)
        var online = true
        val repository = SqlDelightJourneyRepository(database, fixture.provider, scope, online = { online }, clock = clock)
        val lifecycle = LifecycleRegistry()
        val owner = NativeProjectionOwner()
        var submitted: JourneySearchRequest? = null
        val component = JourneySearchComponent(DefaultComponentContext(lifecycle), repository,
            { submitted = it }, Dispatchers.Unconfined, clock)
        val input = NativeJourneySearchPresentation(component, owner)
        lifecycle.resume()
        suspend fun lookup(origin: Boolean, text: String) {
            input.stationText(origin, text)
            withTimeout(5_000) { while (component.state.value.suggestions.isEmpty()) delay(10) }
            assertEquals(component.state.value.suggestions, input.state.value.suggestions)
        }
        try {
            lookup(true, "milano")
            input.selectStation(input.state.value.suggestions.first())
            assertEquals(StationId("location:italo-journeys:MC_"), input.state.value.origin?.id)
            lookup(false, "roma")
            val stations = input.state.value.suggestions
            assertEquals(listOf("Roma Termini", "Roma Tiburtina"), stations.take(2).map { it.name })
            assertEquals(87, stations.size)
            input.selectStation(stations[1])
            input.search()
            assertEquals(stations[1].id, submitted?.destination?.id)
            assertIs<ProviderResult.Success<JourneySearchResult>>(fixture.provider.search(assertNotNull(submitted)))
            assertEquals(830008217L, fixture.selectionBodies.single()["arrivalLocationId"].toString().toLong())

            // Fresh and reopened offline caches must preserve the category evidence and identity.
            val requests = fixture.locationQueries.size
            assertEquals(stations, assertIs<DataResult.Data<List<Station>>>(repository.searchStations("ROMA")).value)
            online = false
            val reopened = SqlDelightJourneyRepository(database, fixture.provider, scope, online = { false }, clock = clock)
            val offline = assertIs<DataResult.Data<List<Station>>>(reopened.searchStations("  roma  "))
            assertEquals(stations, offline.value)
            assertEquals(DomainFailure.OFFLINE, offline.warning)
            assertIs<DataFreshness.Stale>(offline.freshness)
            assertEquals(requests, fixture.locationQueries.size)

            online = true
            clock.instant += 25.hours
            fixture.failItalo = true; fixture.failTrenitalia = true
            val stale = assertIs<DataResult.Data<List<Station>>>(repository.searchStations("roma"))
            assertEquals(stations, stale.value)
            assertEquals(DomainFailure.TEMPORARY, stale.warning)
            assertIs<DataFreshness.Stale>(stale.freshness)
            Unit
        } finally {
            owner.close(); lifecycle.destroy(); scope.cancel(); fixture.close(); driver.close()
        }
    }
}
