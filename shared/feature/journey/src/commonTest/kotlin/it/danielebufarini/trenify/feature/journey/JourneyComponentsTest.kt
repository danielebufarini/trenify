package it.danielebufarini.trenify.feature.journey

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.testing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.datetime.LocalDate
import kotlin.test.*
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class JourneyComponentsTest {
    @Test fun validSearchOpensResultsThenDetailAndBackKeepsForm() = runTest {
        val lifecycle = LifecycleRegistry()
        val repository = FakeJourneyRepository()
        val tab = DefaultJourneyTabComponent(DefaultComponentContext(lifecycle), repository,
            dispatcher = StandardTestDispatcher(testScheduler))
        lifecycle.resume()
        try {
            val form = assertIs<JourneyTabComponent.Child.Search>(tab.stack.value.active.instance).component
            form.search()
            assertTrue(form.state.value.invalid)
            form.stationText(true, "Roma"); advanceUntilIdle(); form.select(testStation)
            form.stationText(false, "Milano"); advanceUntilIdle(); form.select(journeyDestination)
            form.date("2026-09-05"); form.time("10:00"); form.search()
            val results = assertIs<JourneyTabComponent.Child.Results>(tab.stack.value.active.instance).component
            runCurrent()
            assertEquals(journeyRequest, results.request)
            assertEquals(listOf(testJourney), results.state.value.journeys)
            results.sort(JourneySort.DURATION)
            results.select(testJourney)
            assertIs<JourneyTabComponent.Child.Detail>(tab.stack.value.active.instance)
            tab.back(); tab.back()
            assertEquals(testStation, assertIs<JourneyTabComponent.Child.Search>(tab.stack.value.active.instance).component.state.value.origin)
        } finally { lifecycle.destroy() }
    }

    @Test fun searchPrefillsCurrentRomeTimeAndSavesOrderedRoutesReactively() = runTest {
        val lifecycle = LifecycleRegistry()
        val favorites = FakeRealtimeRepositories()
        val clock = MutableClock()
        val component = JourneySearchComponent(
            DefaultComponentContext(lifecycle),
            FakeJourneyRepository(),
            {},
            StandardTestDispatcher(testScheduler),
            clock,
            favorites,
            JourneySearchIntent(testStation, journeyDestination),
        )
        lifecycle.resume()
        runCurrent()

        assertEquals(testStation, component.state.value.origin)
        assertEquals(journeyDestination, component.state.value.destination)
        assertEquals(LocalDate.parse("2026-09-05"), component.state.value.date)
        assertEquals(10, component.state.value.timeHour)
        assertEquals(0, component.state.value.timeMinute)
        assertEquals(JourneySearchMode.DEPART_AFTER, component.state.value.mode)
        assertFalse(requireNotNull(component.favoriteRouteState).value.favorite)

        component.toggleFavoriteRoute()
        runCurrent()
        val route = FavoriteRoute.create(testStation, journeyDestination)
        assertEquals(listOf(route), favorites.favoriteRoutes.value)
        assertTrue(requireNotNull(component.favoriteRouteState).value.favorite)

        component.stationText(true, "Milano")
        advanceUntilIdle()
        component.select(journeyDestination)
        component.stationText(false, "Roma")
        advanceUntilIdle()
        component.select(testStation)
        component.toggleFavoriteRoute()
        runCurrent()
        assertEquals(setOf(route, FavoriteRoute.create(journeyDestination, testStation)),
            favorites.favoriteRoutes.value.toSet())
        lifecycle.destroy()
    }

    @Test fun detailCanSaveAndRemoveTheSameRouteWithoutRetainingJourneyData() = runTest {
        val lifecycle = LifecycleRegistry()
        val favorites = FakeRealtimeRepositories()
        val component = JourneyDetailComponent(
            DefaultComponentContext(lifecycle),
            testJourney,
            null,
            {},
            StandardTestDispatcher(testScheduler),
            favoritesRepository = favorites,
        )
        lifecycle.resume()
        runCurrent()

        component.toggleFavoriteRoute()
        runCurrent()
        assertEquals(listOf(FavoriteRoute.create(testStation, journeyDestination)), favorites.favoriteRoutes.value)
        component.toggleFavoriteRoute()
        runCurrent()
        assertTrue(favorites.favoriteRoutes.value.isEmpty())
        lifecycle.destroy()
    }

    @Test fun invalidDateAndNonexistentRomeTimeDoNotNavigate() = runTest {
        val lifecycle = LifecycleRegistry()
        var searches = 0
        val component = JourneySearchComponent(DefaultComponentContext(lifecycle), FakeJourneyRepository(), { searches++ },
            StandardTestDispatcher(testScheduler), MutableClock())
        component.stationText(true, "Roma"); advanceUntilIdle(); component.select(testStation)
        component.stationText(false, "Milano"); advanceUntilIdle(); component.select(journeyDestination)
        component.date("bad-date"); component.search()
        assertTrue(component.state.value.invalid)
        component.date("2026-03-29"); component.time("02:30"); component.search()
        assertTrue(component.state.value.invalid)
        assertEquals(0, searches)
        lifecycle.destroy()
    }

    @Test fun resultsExposeLoadingFailurePartialCacheAndCancelOnDestroy() = runTest {
        val lifecycle = LifecycleRegistry()
        val repository = FakeJourneyRepository()
        val gate = CompletableDeferred<Unit>()
        repository.beforeSearch = { gate.await() }
        val component = JourneyResultsComponent(DefaultComponentContext(lifecycle), journeyRequest, SearchJourneys(repository), {},
            StandardTestDispatcher(testScheduler))
        runCurrent()
        assertTrue(component.state.value.results.loading)
        repository.state.value = DataResult.Data(journeyResult, DataFreshness.Unknown, DomainFailure.TEMPORARY)
        runCurrent()
        assertEquals(listOf(testJourney), component.state.value.journeys)
        assertTrue(component.state.value.results.stale)
        gate.complete(Unit); runCurrent()
        assertFalse(component.state.value.results.loading)
        assertTrue(component.state.value.results.failed)
        repository.beforeSearch = { awaitCancellation() }
        component.refresh(); runCurrent()
        lifecycle.destroy(); runCurrent()
        assertEquals(2, repository.calls)
    }

    @Test fun validatedSubmissionIsRecordedOnceAndRefreshNeverRecords() = runTest {
        val lifecycle = LifecycleRegistry()
        val journeys = FakeJourneyRepository()
        val history = FakeRealtimeRepositories()
        var searched: JourneySearchRequest? = null
        val component = JourneySearchComponent(DefaultComponentContext(lifecycle), journeys, { searched = it },
            StandardTestDispatcher(testScheduler), MutableClock(), historyRepository = history)
        lifecycle.resume()
        try {
            // Rejected input records nothing.
            component.search()
            runCurrent()
            assertTrue(component.state.value.invalid)
            assertTrue(history.observeSearchHistory().first().isEmpty())
            // One validated submission records exactly one entry with the
            // requested criteria, including a non-default mode.
            component.stationText(true, "Roma"); advanceUntilIdle(); component.select(testStation)
            component.stationText(false, "Milano"); advanceUntilIdle(); component.select(journeyDestination)
            component.date("2026-01-15"); component.time("10:00"); component.mode(JourneySearchMode.ARRIVE_BY)
            component.search(); runCurrent()
            val expected = JourneySearchRequest(testStation, journeyDestination,
                Instant.parse("2026-01-15T09:00:00Z"), JourneySearchMode.ARRIVE_BY)
            assertEquals(expected, searched)
            val recorded = assertIs<JourneySearchHistoryEntry>(history.observeSearchHistory().first().single())
            assertEquals(expected, recorded.request())
            // Refreshing or observing the same request, as Results does,
            // records nothing more.
            journeys.search(requireNotNull(searched)); runCurrent()
            assertEquals(1, history.observeSearchHistory().first().size)
            // A separate identical submission is a distinct entry, not a dedup.
            component.search(); runCurrent()
            assertEquals(2, history.observeSearchHistory().first().size)
        } finally { lifecycle.destroy() }
    }

    @Test fun providerFailureOutcomeStillRecordsExactlyOneEntryThroughTheTabFlow() = runTest {
        val lifecycle = LifecycleRegistry()
        val journeys = FakeJourneyRepository()
        journeys.state.value = DataResult.Failure(DomainFailure.TEMPORARY)
        val history = FakeRealtimeRepositories()
        val tab = DefaultJourneyTabComponent(DefaultComponentContext(lifecycle), journeys,
            historyRepository = history, dispatcher = StandardTestDispatcher(testScheduler))
        lifecycle.resume()
        try {
            val form = assertIs<JourneyTabComponent.Child.Search>(tab.stack.value.active.instance).component
            form.stationText(true, "Roma"); advanceUntilIdle(); form.select(testStation)
            form.stationText(false, "Milano"); advanceUntilIdle(); form.select(journeyDestination)
            form.date("2026-09-05"); form.time("10:00")
            val expected = JourneySearchRequest(testStation, journeyDestination,
                Instant.parse("2026-09-05T08:00:00Z"), JourneySearchMode.DEPART_AFTER)
            form.search(); advanceUntilIdle()
            // The real repository search executed, returned the failure, and
            // the results reached the failure state.
            val results = assertIs<JourneyTabComponent.Child.Results>(tab.stack.value.active.instance).component
            assertEquals(expected, results.request)
            assertEquals(1, journeys.calls)
            assertTrue(results.state.value.results.failed)
            // Exactly one history entry exists for the validated submission.
            assertEquals(expected, assertIs<JourneySearchHistoryEntry>(
                history.observeSearchHistory().first().single()).request())
            // A manual refresh re-executes the search but records nothing more.
            results.refresh(); advanceUntilIdle()
            assertEquals(2, journeys.calls)
            assertEquals(1, history.observeSearchHistory().first().size)
        } finally { lifecycle.destroy() }
    }

    @Test fun emptyNoResultOutcomeStillRecordsExactlyOneEntryThroughTheTabFlow() = runTest {
        val lifecycle = LifecycleRegistry()
        val journeys = FakeJourneyRepository()
        journeys.state.value = DataResult.Data(journeyResult.copy(journeys = emptyList()),
            DataFreshness.Fresh(MutableClock().now(), null))
        val history = FakeRealtimeRepositories()
        val tab = DefaultJourneyTabComponent(DefaultComponentContext(lifecycle), journeys,
            historyRepository = history, dispatcher = StandardTestDispatcher(testScheduler))
        lifecycle.resume()
        try {
            val form = assertIs<JourneyTabComponent.Child.Search>(tab.stack.value.active.instance).component
            form.stationText(true, "Roma"); advanceUntilIdle(); form.select(testStation)
            form.stationText(false, "Milano"); advanceUntilIdle(); form.select(journeyDestination)
            form.date("2026-09-05"); form.time("10:00"); form.search(); advanceUntilIdle()
            // The real repository search executed and returned no journeys
            // without failing, and exactly one history entry was recorded.
            val results = assertIs<JourneyTabComponent.Child.Results>(tab.stack.value.active.instance).component
            assertEquals(1, journeys.calls)
            assertFalse(results.state.value.results.failed)
            assertTrue(results.state.value.journeys.isEmpty())
            assertEquals(1, history.observeSearchHistory().first().size)
        } finally { lifecycle.destroy() }
    }

    @Test fun historyPersistenceFailureNeverBlocksValidatedSearch() = runTest {
        val lifecycle = LifecycleRegistry()
        val journeys = FakeJourneyRepository()
        val history = FakeRealtimeRepositories()
        var searched: JourneySearchRequest? = null
        val component = JourneySearchComponent(DefaultComponentContext(lifecycle), journeys, { searched = it },
            StandardTestDispatcher(testScheduler), MutableClock(), historyRepository = history)
        lifecycle.resume()
        try {
            component.stationText(true, "Roma"); advanceUntilIdle(); component.select(testStation)
            component.stationText(false, "Milano"); advanceUntilIdle(); component.select(journeyDestination)
            component.date("2026-09-05"); component.time("10:00")
            // A cancelled history write still lets the search proceed.
            history.searchHistoryFailure = CancellationException("history cancelled")
            component.search(); runCurrent()
            assertNotNull(searched)
            // An ordinary persistence failure is non-fatal the same way.
            history.searchHistoryFailure = IllegalStateException("history unavailable")
            searched = null
            component.search(); runCurrent()
            assertNotNull(searched)
            assertTrue(history.observeSearchHistory().first().isEmpty())
        } finally { lifecycle.destroy() }
    }

    @Test fun historyRepeatPreservesOriginalDateAndSearchesCurrentRepository() = runTest {
        val lifecycle = LifecycleRegistry()
        val journeys = FakeJourneyRepository()
        val history = FakeRealtimeRepositories()
        val old = JourneySearchRequest(testStation, journeyDestination,
            Instant.parse("2026-01-15T09:00:00Z"), JourneySearchMode.ARRIVE_BY)
        history.recordSearch(old)
        val tab = DefaultJourneyTabComponent(DefaultComponentContext(lifecycle), journeys,
            historyRepository = history, dispatcher = StandardTestDispatcher(testScheduler))
        lifecycle.resume()
        try {
            runCurrent()
            val search = assertIs<JourneyTabComponent.Child.Search>(tab.stack.value.active.instance).component
            search.history(); runCurrent()
            val historyChild = assertIs<JourneyTabComponent.Child.History>(tab.stack.value.active.instance).component
            runCurrent()
            assertEquals(1, historyChild.state.value.entries.size)
            // Repeat populates a fresh search with the original criteria for
            // explicit execution; the old date stays visible and editable.
            historyChild.repeat(historyChild.state.value.entries.single()); runCurrent()
            val prefilled = assertIs<JourneyTabComponent.Child.Search>(tab.stack.value.active.instance).component
            assertEquals(testStation, prefilled.state.value.origin)
            assertEquals(journeyDestination, prefilled.state.value.destination)
            assertEquals(LocalDate.parse("2026-01-15"), prefilled.state.value.date)
            assertEquals(10, prefilled.state.value.timeHour)
            assertEquals(0, prefilled.state.value.timeMinute)
            assertEquals(JourneySearchMode.ARRIVE_BY, prefilled.state.value.mode)
            prefilled.search(); runCurrent()
            assertEquals(old, assertIs<JourneyTabComponent.Child.Results>(tab.stack.value.active.instance).component.request)
        } finally { lifecycle.destroy() }
    }

    @Test fun trainRepeatDispatchesToTrainCallbackWhileJourneyRepeatStaysLocal() = runTest {
        val lifecycle = LifecycleRegistry()
        val history = FakeRealtimeRepositories()
        val journey = history.recordSearch(journeyRequest)
        val train = history.recordTrainSearch(TrainNumber("456"), kotlinx.datetime.LocalDate.parse("2026-09-06"),
            TrainLookupIntent(TrainNumber("456"), testStation.id, testStation.name, Operator("Trenitalia")))
        val repeatedJourneys = mutableListOf<SearchHistoryEntry>()
        val repeatedTrains = mutableListOf<TrainSearchHistoryEntry>()
        val component = SearchHistoryComponent(DefaultComponentContext(lifecycle), history,
            onRepeat = { repeatedJourneys += it },
            onTrainRepeat = { repeatedTrains += it },
            dispatcher = StandardTestDispatcher(testScheduler))
        lifecycle.resume()
        try {
            runCurrent()
            assertEquals(2, component.state.value.entries.size)
            component.repeat(train)
            component.repeat(journey)
            assertEquals(listOf(train), repeatedTrains)
            assertEquals(listOf<SearchHistoryEntry>(journey), repeatedJourneys)
            // Per-item deletion covers train entries through the same operation.
            component.remove(train); runCurrent()
            assertEquals(listOf(journey), component.state.value.entries)
            assertEquals(listOf(journey), history.observeSearchHistory().first())
        } finally { lifecycle.destroy() }
    }

    @Test fun trainRepeatLeavesTheJourneyTabThroughTheApplicationCallback() = runTest {
        val lifecycle = LifecycleRegistry()
        val journeys = FakeJourneyRepository()
        val history = FakeRealtimeRepositories()
        val train = history.recordTrainSearch(TrainNumber("456"), kotlinx.datetime.LocalDate.parse("2026-09-06"),
            expected = null)
        val trainRepeats = mutableListOf<TrainSearchHistoryEntry>()
        val tab = DefaultJourneyTabComponent(DefaultComponentContext(lifecycle), journeys,
            historyRepository = history, onTrainRepeat = { trainRepeats += it },
            dispatcher = StandardTestDispatcher(testScheduler))
        lifecycle.resume()
        try {
            runCurrent()
            val search = assertIs<JourneyTabComponent.Child.Search>(tab.stack.value.active.instance).component
            search.history(); runCurrent()
            val historyChild = assertIs<JourneyTabComponent.Child.History>(tab.stack.value.active.instance).component
            runCurrent()
            // The train repeat leaves the tab through the application
            // callback instead of pushing a journey search.
            historyChild.repeat(train); runCurrent()
            assertEquals(listOf(train), trainRepeats)
            assertIs<JourneyTabComponent.Child.History>(tab.stack.value.active.instance)
        } finally { lifecycle.destroy() }
    }

    @Test fun historyRemoveAndClearUpdateImmediatelyWithoutNetwork() = runTest {
        val lifecycle = LifecycleRegistry()
        val history = FakeRealtimeRepositories()
        history.recordSearch(journeyRequest)
        history.recordSearch(journeyRequest.copy(mode = JourneySearchMode.ARRIVE_BY))
        val component = SearchHistoryComponent(DefaultComponentContext(lifecycle), history,
            dispatcher = StandardTestDispatcher(testScheduler))
        lifecycle.resume()
        try {
            runCurrent()
            assertEquals(2, component.state.value.entries.size)
            component.remove(component.state.value.entries.last()); runCurrent()
            assertEquals(1, component.state.value.entries.size)
            component.clear(); runCurrent()
            assertTrue(component.state.value.entries.isEmpty())
            assertTrue(history.observeSearchHistory().first().isEmpty())
        } finally { lifecycle.destroy() }
    }

    @Test fun detailOnlyOpensVerifiedTrainRunAndRejectsUnknownLegIndex() = runTest {
        val lifecycle = LifecycleRegistry()
        val trains = FakeRealtimeRepositories()
        val leg = testJourney.legs.single()
        trains.trainState.value = DataResult.Data(TrainRun(testSummary.copy(operator = leg.operator), listOf(
            TrainStop(testStation, scheduledDeparture = leg.departure), TrainStop(journeyDestination, scheduledArrival = leg.arrival))),
            DataFreshness.Fresh(MutableClock().now(), null))
        var selected: TrainRunId? = null
        val component = JourneyDetailComponent(DefaultComponentContext(lifecycle), testJourney, CorrelateJourneyLeg(trains),
            { selected = it }, StandardTestDispatcher(testScheduler))
        component.realtime(0); assertNull(selected)
        advanceUntilIdle()
        component.realtime(10); assertNull(selected)
        component.realtime(0); assertEquals(testRunId, selected)
        lifecycle.destroy()
    }
}
