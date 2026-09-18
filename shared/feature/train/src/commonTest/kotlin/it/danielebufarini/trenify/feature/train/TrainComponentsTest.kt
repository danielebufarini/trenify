package it.danielebufarini.trenify.feature.train

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.testing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TrainComponentsTest {
    private val trenitalia = Operator("Trenitalia")
    private val napoliStation = Station(StationId("napoli-centrale"), "Napoli Centrale")
    private val romaFavorite = FavoriteTrain.create(
        TrainNumber("123"),
        originId = testStation.id,
        operator = trenitalia,
        originName = testStation.name,
    )

    @Test fun zeroSingleAndMultipleResultsRequireCorrectNavigation() = runTest {
        for (count in 0..2) {
            val lifecycle = LifecycleRegistry()
            val repositories = FakeRealtimeRepositories()
            val runs = (0 until count).map { testSummary.copy(id = testRunId.copy(origin = ExternalStationRef("origin-$it"))) }
            repositories.runsResult = DataResult.Data(runs, DataFreshness.Unknown)
            val opened = mutableListOf<TrainRunId>()
            val component = TrainSearchComponent(DefaultComponentContext(lifecycle), FindTrainRuns(repositories),
                opened::add, StandardTestDispatcher(testScheduler))
            component.number("123")
            component.search()
            assertTrue(component.state.value.results.loading)
            runCurrent()
            assertEquals(count, component.state.value.results.data?.size)
            assertEquals(if (count == 1) listOf(runs.single().id) else emptyList(), opened)
            if (count == 2) {
                component.select(runs[1])
                assertEquals(listOf(runs[1].id), opened)
            }
            lifecycle.destroy()
        }
    }

    @Test fun detailEmitsCacheBeforeRefreshCompletesAndStopsPollingOnPause() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val gate = CompletableDeferred<Unit>()
        repositories.onRefresh = { gate.await() }
        val available = MutableStateFlow(true)
        val component = TrainDetailComponent(DefaultComponentContext(lifecycle), testRunId, ObserveTrainRun(repositories),
            available, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertEquals(testRun, component.state.value.data)
        assertEquals(0, repositories.trainRefreshes)
        lifecycle.resume()
        runCurrent()
        assertEquals(1, repositories.trainRefreshes)
        assertEquals(testRun, component.state.value.data)
        assertTrue(component.state.value.loading)
        gate.complete(Unit)
        runCurrent()
        assertFalse(component.state.value.loading)
        repositories.trainState.value = DataResult.Data(testRun.copy(summary = testSummary.copy(delayMinutes = 8)), DataFreshness.Unknown)
        runCurrent()
        assertEquals(8, component.state.value.data?.summary?.delayMinutes)
        component.refresh()
        runCurrent()
        assertEquals(2, repositories.trainRefreshes)
        lifecycle.stop()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(2, repositories.trainRefreshes)
        lifecycle.resume()
        runCurrent()
        assertEquals(3, repositories.trainRefreshes)
        available.value = false
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(3, repositories.trainRefreshes)
        lifecycle.destroy()
    }

    @Test fun terminalTrainStopsPollingAndFailureRetainsVisibleCache() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        repositories.trainState.value = DataResult.Data(testRun.copy(summary = testSummary.copy(status = TrainStatus.ARRIVED)), DataFreshness.Unknown)
        val component = TrainDetailComponent(DefaultComponentContext(lifecycle), testRunId, ObserveTrainRun(repositories),
            MutableStateFlow(true), StandardTestDispatcher(testScheduler))
        lifecycle.resume()
        runCurrent()
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(1, repositories.trainRefreshes)
        repositories.trainState.value = DataResult.Failure(DomainFailure.TEMPORARY)
        component.refresh()
        runCurrent()
        assertNotNull(component.state.value.data)
        assertTrue(component.state.value.failed)
        lifecycle.destroy()
    }

    @Test fun favoriteLaunchPrefillsNumberUsesExplicitDateAndNeverAutoSelectsAmongMany() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val serviceDate = kotlinx.datetime.LocalDate.parse("2026-09-06")
        var capturedDate: kotlinx.datetime.LocalDate? = null
        var capturedNumber: TrainNumber? = null
        val capturing = object : TrainRepository by repositories {
            override suspend fun findTrainRuns(
                number: TrainNumber,
                date: kotlinx.datetime.LocalDate?,
            ): DataResult<List<TrainRunSummary>> {
                capturedNumber = number
                capturedDate = date
                return repositories.findTrainRuns(number, date)
            }
        }
        val second = testSummary.copy(
            id = testRunId.copy(origin = ExternalStationRef("second-origin")),
            origin = napoliStation,
            operator = trenitalia,
        )
        val first = testSummary.copy(operator = trenitalia)
        repositories.runsResult = DataResult.Data(listOf(first, second), DataFreshness.Unknown)
        val opened = mutableListOf<TrainRunId>()
        val component = TrainSearchComponent(DefaultComponentContext(lifecycle), FindTrainRuns(capturing),
            opened::add, StandardTestDispatcher(testScheduler),
            initialNumber = "123", serviceDate = serviceDate, autoSearch = true,
            expected = romaFavorite.lookupIntent)
        assertEquals("123", component.state.value.number)
        assertEquals(serviceDate, component.state.value.serviceDate)
        runCurrent()

        // A favorite launch performs a fresh lookup for the explicit service date.
        assertEquals(TrainNumber("123"), capturedNumber)
        assertEquals(serviceDate, capturedDate)
        assertEquals(2, component.state.value.results.data?.size)
        // Ambiguous candidates require explicit selection: no silent first-candidate navigation.
        assertTrue(opened.isEmpty())
        assertFalse(component.state.value.noCompatibleService)
        component.select(first)
        assertEquals(listOf(first.id), opened)
        lifecycle.destroy()
    }

    @Test fun favoriteLaunchWithZeroOrOneCompatibleCandidateFollowsControlledBehavior() = runTest {
        for (count in 0..1) {
            val lifecycle = LifecycleRegistry()
            val repositories = FakeRealtimeRepositories()
            val runs = (0 until count).map {
                testSummary.copy(
                    id = testRunId.copy(origin = ExternalStationRef("origin-$it")),
                    operator = trenitalia,
                )
            }
            repositories.runsResult = DataResult.Data(runs, DataFreshness.Unknown)
            val opened = mutableListOf<TrainRunId>()
            val component = TrainSearchComponent(DefaultComponentContext(lifecycle), FindTrainRuns(repositories),
                opened::add, StandardTestDispatcher(testScheduler),
                initialNumber = "123",
                serviceDate = kotlinx.datetime.LocalDate.parse("2026-09-06"), autoSearch = true,
                expected = romaFavorite.lookupIntent)
            runCurrent()
            if (count == 0) {
                // A previously valid train that no longer exists stays in a
                // controlled not-found state without navigation.
                assertTrue(component.state.value.results.data?.isEmpty() == true)
                assertFalse(component.state.value.noCompatibleService)
                assertTrue(opened.isEmpty())
            } else {
                assertFalse(component.state.value.noCompatibleService)
                assertEquals(listOf(runs.single().id), opened)
            }
            lifecycle.destroy()
        }
    }

    @Test fun favoriteLaunchNeverAutoOpensASingleIncompatibleSameNumberCandidate() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        // The blocking counterexample: only Napoli 123 runs today, but the
        // saved recurring train departs from Roma with the same operator.
        val napoliOnly = testSummary.copy(
            id = testRunId.copy(origin = ExternalStationRef("napoli-origin")),
            origin = napoliStation,
            operator = trenitalia,
        )
        repositories.runsResult = DataResult.Data(listOf(napoliOnly), DataFreshness.Unknown)
        val opened = mutableListOf<TrainRunId>()
        val component = TrainSearchComponent(DefaultComponentContext(lifecycle), FindTrainRuns(repositories),
            opened::add, StandardTestDispatcher(testScheduler),
            initialNumber = "123",
            serviceDate = kotlinx.datetime.LocalDate.parse("2026-09-06"), autoSearch = true,
            expected = romaFavorite.lookupIntent)
        runCurrent()

        assertTrue(opened.isEmpty())
        assertTrue(component.state.value.noCompatibleService)
        assertTrue(component.state.value.results.data?.isEmpty() == true)
        lifecycle.destroy()
    }

    @Test fun favoriteLaunchWithConflictingOperatorDoesNotAutoOpen() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val otherOperator = testSummary.copy(operator = Operator("Italo"))
        repositories.runsResult = DataResult.Data(listOf(otherOperator), DataFreshness.Unknown)
        val opened = mutableListOf<TrainRunId>()
        val component = TrainSearchComponent(DefaultComponentContext(lifecycle), FindTrainRuns(repositories),
            opened::add, StandardTestDispatcher(testScheduler),
            initialNumber = "123",
            serviceDate = kotlinx.datetime.LocalDate.parse("2026-09-06"), autoSearch = true,
            expected = romaFavorite.lookupIntent)
        runCurrent()

        assertTrue(opened.isEmpty())
        assertTrue(component.state.value.noCompatibleService)
        lifecycle.destroy()
    }

    @Test fun favoriteLaunchWithMixedOriginsRequiresExplicitSelection() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val romaRun = testSummary.copy(operator = trenitalia)
        val napoliRun = testSummary.copy(
            id = testRunId.copy(origin = ExternalStationRef("napoli-origin")),
            origin = napoliStation,
            operator = trenitalia,
        )
        repositories.runsResult = DataResult.Data(listOf(romaRun, napoliRun), DataFreshness.Unknown)
        val opened = mutableListOf<TrainRunId>()
        val component = TrainSearchComponent(DefaultComponentContext(lifecycle), FindTrainRuns(repositories),
            opened::add, StandardTestDispatcher(testScheduler),
            initialNumber = "123",
            serviceDate = kotlinx.datetime.LocalDate.parse("2026-09-06"), autoSearch = true,
            expected = romaFavorite.lookupIntent)
        runCurrent()

        // Napoli must never be silently opened; both runs stay selectable.
        assertTrue(opened.isEmpty())
        assertFalse(component.state.value.noCompatibleService)
        assertEquals(2, component.state.value.results.data?.size)
        component.select(napoliRun)
        assertEquals(listOf(napoliRun.id), opened)
        lifecycle.destroy()
    }

    @Test fun favoriteLaunchWithInsufficientDiscriminationRequiresPickerForMany() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val numberOnly = FavoriteTrain.create(TrainNumber("123"), originId = null, operator = null)
        val romaRun = testSummary.copy(operator = trenitalia)
        val napoliRun = testSummary.copy(
            id = testRunId.copy(origin = ExternalStationRef("napoli-origin")),
            origin = napoliStation,
            operator = trenitalia,
        )
        repositories.runsResult = DataResult.Data(listOf(romaRun, napoliRun), DataFreshness.Unknown)
        val opened = mutableListOf<TrainRunId>()
        val component = TrainSearchComponent(DefaultComponentContext(lifecycle), FindTrainRuns(repositories),
            opened::add, StandardTestDispatcher(testScheduler),
            initialNumber = "123",
            serviceDate = kotlinx.datetime.LocalDate.parse("2026-09-06"), autoSearch = true,
            expected = numberOnly.lookupIntent)
        runCurrent()

        // Nothing may be inferred from the number alone: explicit selection only.
        assertTrue(opened.isEmpty())
        assertEquals(2, component.state.value.results.data?.size)
        component.select(romaRun)
        assertEquals(listOf(romaRun.id), opened)
        lifecycle.destroy()
    }

    @Test fun favoriteLaunchWithUnverifiableSingleCandidateRequiresExplicitSelection() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        // Origin matches, but the candidate operator is unknown while the
        // favorite expects Trenitalia: no silent navigation from missing data.
        val unverified = testSummary.copy(operator = null)
        repositories.runsResult = DataResult.Data(listOf(unverified), DataFreshness.Unknown)
        val opened = mutableListOf<TrainRunId>()
        val component = TrainSearchComponent(DefaultComponentContext(lifecycle), FindTrainRuns(repositories),
            opened::add, StandardTestDispatcher(testScheduler),
            initialNumber = "123",
            serviceDate = kotlinx.datetime.LocalDate.parse("2026-09-06"), autoSearch = true,
            expected = romaFavorite.lookupIntent)
        runCurrent()

        assertTrue(opened.isEmpty())
        assertFalse(component.state.value.noCompatibleService)
        assertEquals(1, component.state.value.results.data?.size)
        component.select(unverified)
        assertEquals(listOf(testRunId), opened)
        lifecycle.destroy()
    }

    @Test fun favoriteLaunchPerformsFreshLookupAndNeverReusesAnOldRunId() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val serviceDate = kotlinx.datetime.LocalDate.parse("2026-09-06")
        val staleId = testRunId.copy(serviceDate = kotlinx.datetime.LocalDate.parse("2026-09-05"))
        var captured: Pair<TrainNumber, kotlinx.datetime.LocalDate?>? = null
        val capturing = object : TrainRepository by repositories {
            override suspend fun findTrainRuns(
                number: TrainNumber,
                date: kotlinx.datetime.LocalDate?,
            ): DataResult<List<TrainRunSummary>> {
                captured = number to date
                return repositories.findTrainRuns(number, date)
            }
        }
        val fresh = testSummary.copy(
            id = testRunId.copy(origin = ExternalStationRef("opaque-origin"), serviceDate = serviceDate),
            operator = trenitalia,
        )
        repositories.runsResult = DataResult.Data(listOf(fresh), DataFreshness.Unknown)
        val opened = mutableListOf<TrainRunId>()
        val component = TrainSearchComponent(DefaultComponentContext(lifecycle), FindTrainRuns(capturing),
            opened::add, StandardTestDispatcher(testScheduler),
            initialNumber = romaFavorite.lookupIntent.number.value,
            serviceDate = serviceDate, autoSearch = true,
            expected = romaFavorite.lookupIntent)
        runCurrent()

        assertEquals(TrainNumber("123") to serviceDate, captured)
        assertEquals(listOf(fresh.id), opened)
        assertFalse(staleId in opened)
        lifecycle.destroy()
    }

    @Test fun detailSavesAndRemovesRecurringFavoriteWithoutCreatingAMonitor() = runTest {
        val lifecycle = LifecycleRegistry()
        val realtime = FakeRealtimeRepositories()
        val monitoring = FakeMonitoringRepository()
        val component = TrainDetailComponent(
            DefaultComponentContext(lifecycle),
            testRunId,
            ObserveTrainRun(realtime),
            MutableStateFlow(false),
            StandardTestDispatcher(testScheduler),
            observeMonitor = ObserveTrainMonitor(monitoring),
            startMonitoring = StartTrainMonitoring(monitoring),
            stopMonitoring = StopTrainMonitoring(monitoring),
            favoritesRepository = realtime,
        )
        runCurrent()

        val expected = FavoriteTrain.create(
            TrainNumber("123"),
            originId = testStation.id,
            operator = null,
            originName = "Roma Termini",
            destinationName = "Milano Centrale",
        )
        assertEquals(true, component.favoriteTrainState?.value?.available)
        assertEquals(false, component.favoriteTrainState?.value?.favorite)
        component.toggleFavoriteTrain()
        runCurrent()
        assertEquals(listOf(expected), realtime.favoriteTrains.value)
        assertEquals(true, component.favoriteTrainState?.value?.favorite)
        assertTrue(monitoring.monitors.value.isEmpty())

        component.toggleFavoriteTrain()
        runCurrent()
        assertTrue(realtime.favoriteTrains.value.isEmpty())
        assertEquals(false, component.favoriteTrainState?.value?.favorite)
        assertTrue(monitoring.monitors.value.isEmpty())
        lifecycle.destroy()
    }

    @Test fun validatedTrainSubmissionIsRecordedOnceAndSelectNeverRecords() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val history = FakeRealtimeRepositories()
        var opened: TrainRunId? = null
        val component = TrainSearchComponent(DefaultComponentContext(lifecycle), FindTrainRuns(repositories),
            { opened = it }, StandardTestDispatcher(testScheduler), historyRepository = history)
        lifecycle.resume()
        try {
            // Rejected input records nothing.
            component.number("abc")
            component.search()
            runCurrent()
            assertTrue(component.state.value.invalidNumber)
            assertTrue(history.observeSearchHistory().first().isEmpty())
            // One validated submission records exactly one number-only entry
            // with the explicit service date and no invented route.
            val date = component.state.value.serviceDate
            component.number("123")
            component.search()
            runCurrent()
            val recorded = assertIs<TrainSearchHistoryEntry>(history.observeSearchHistory().first().single())
            assertEquals(TrainNumber("123"), recorded.number)
            assertEquals(date, recorded.serviceDate)
            assertNull(recorded.originId)
            assertNull(recorded.originName)
            assertNull(recorded.operator)
            assertNull(recorded.destinationName)
            // Selecting a candidate and observing results record nothing more.
            val second = testSummary.copy(id = testRunId.copy(origin = ExternalStationRef("second-origin")))
            repositories.runsResult = DataResult.Data(listOf(testSummary, second), DataFreshness.Unknown)
            component.search()
            runCurrent()
            assertEquals(2, history.observeSearchHistory().first().size)
            component.select(second)
            runCurrent()
            assertEquals(second.id, opened)
            assertEquals(2, history.observeSearchHistory().first().size)
        } finally { lifecycle.destroy() }
    }

    @Test fun trainFailureOutcomeStillRecordsAndPersistenceFailureNeverBlocksSearch() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val history = FakeRealtimeRepositories()
        var opened: TrainRunId? = null
        val component = TrainSearchComponent(DefaultComponentContext(lifecycle), FindTrainRuns(repositories),
            { opened = it }, StandardTestDispatcher(testScheduler), historyRepository = history)
        lifecycle.resume()
        try {
            // A failure outcome still leaves exactly one entry behind.
            repositories.runsResult = DataResult.Failure(DomainFailure.OFFLINE)
            component.number("123")
            component.search()
            runCurrent()
            assertTrue(component.state.value.results.failed)
            assertEquals(1, history.observeSearchHistory().first().size)
            // A history write failure never blocks the validated search.
            history.searchHistoryFailure = IllegalStateException("history write failed")
            repositories.runsResult = DataResult.Data(listOf(testSummary), DataFreshness.Unknown)
            component.search()
            runCurrent()
            assertEquals(testRunId, opened)
            assertEquals(1, history.observeSearchHistory().first().size)
        } finally { lifecycle.destroy() }
    }

    @Test fun discriminatedSubmissionKeepsExpectedDiscriminationForRepeat() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val history = FakeRealtimeRepositories()
        val component = TrainSearchComponent(DefaultComponentContext(lifecycle), FindTrainRuns(repositories),
            {}, StandardTestDispatcher(testScheduler),
            initialNumber = "123",
            serviceDate = kotlinx.datetime.LocalDate.parse("2026-09-06"),
            expected = romaFavorite.lookupIntent,
            historyRepository = history)
        lifecycle.resume()
        try {
            component.search()
            runCurrent()
            val recorded = assertIs<TrainSearchHistoryEntry>(history.observeSearchHistory().first().single())
            assertEquals(romaFavorite.lookupIntent, recorded.lookupIntent())
            assertEquals(testStation.id, recorded.originId)
            assertEquals(testStation.name, recorded.originName)
        } finally { lifecycle.destroy() }
    }

    @Test fun editedNumberIgnoresStaleDiscriminationForLookupAndHistory() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val history = FakeRealtimeRepositories()
        lifecycle.resume()
        try {
            // A repeat of 123 carrying Roma/Trenitalia discrimination, then
            // the user edits the number before submitting: the stale 123
            // discrimination must apply to neither the lookup nor the entry.
            val candidate456 = testSummary.copy(id = testRunId.copy(number = TrainNumber("456")))
            repositories.runsResult = DataResult.Data(listOf(candidate456), DataFreshness.Unknown)
            val opened = mutableListOf<TrainRunId>()
            val edited = TrainSearchComponent(DefaultComponentContext(lifecycle), FindTrainRuns(repositories),
                opened::add, StandardTestDispatcher(testScheduler),
                initialNumber = "123",
                serviceDate = kotlinx.datetime.LocalDate.parse("2026-09-06"),
                expected = romaFavorite.lookupIntent,
                historyRepository = history)
            edited.number("456")
            edited.search()
            runCurrent()
            // Normal single-result behavior for 456: the stale 123 intent
            // does not reject the candidate.
            assertEquals(listOf(candidate456.id), opened)
            assertFalse(edited.state.value.noCompatibleService)
            val recorded = assertIs<TrainSearchHistoryEntry>(history.observeSearchHistory().first().single())
            assertEquals(TrainNumber("456"), recorded.number)
            assertNull(recorded.originId)
            assertNull(recorded.originName)
            assertNull(recorded.operator)
            // Editing back to 123 restores the carried discrimination.
            edited.number("123")
            repositories.runsResult = DataResult.Data(listOf(testSummary), DataFreshness.Unknown)
            edited.search()
            runCurrent()
            val restored = history.observeSearchHistory().first()
                .filterIsInstance<TrainSearchHistoryEntry>()
                .first { it.number == TrainNumber("123") }
            assertEquals(romaFavorite.lookupIntent, restored.lookupIntent())
        } finally { lifecycle.destroy() }
    }

    @Test fun legacyNameOnlyRepeatWithholdsIncompatibleSameNumberCandidate() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val history = FakeRealtimeRepositories()
        // Record through history so the repeat carries exactly the persisted intent.
        val legacy = TrainLookupIntent(TrainNumber("123"), originId = null,
            originName = "Roma Termini", operator = trenitalia)
        val entry = history.recordTrainSearch(TrainNumber("123"),
            kotlinx.datetime.LocalDate.parse("2026-09-06"), legacy)
        assertEquals(legacy, entry.lookupIntent())
        // Only Napoli 123 runs today, conflicting with the stored Roma
        // name discrimination: the repeat must not silently open it.
        val napoliOnly = testSummary.copy(
            id = testRunId.copy(origin = ExternalStationRef("napoli-origin")),
            origin = napoliStation,
            operator = trenitalia,
        )
        repositories.runsResult = DataResult.Data(listOf(napoliOnly), DataFreshness.Unknown)
        val opened = mutableListOf<TrainRunId>()
        val component = TrainSearchComponent(DefaultComponentContext(lifecycle), FindTrainRuns(repositories),
            opened::add, StandardTestDispatcher(testScheduler),
            initialNumber = entry.number.value,
            serviceDate = entry.serviceDate,
            expected = entry.lookupIntent(),
            historyRepository = history)
        lifecycle.resume()
        try {
            // A repeat prefills for explicit execution; the later submission
            // re-applies the stored legacy discrimination unchanged.
            assertEquals("123", component.state.value.number)
            component.search()
            runCurrent()
            assertTrue(opened.isEmpty())
            assertTrue(component.state.value.noCompatibleService)
            assertTrue(component.state.value.results.data?.isEmpty() == true)
        } finally { lifecycle.destroy() }
    }

    @Test fun trainSearchFailureCanBeRetriedAndInvalidInputDoesNotNavigate() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        var opened: TrainRunId? = null
        val component = TrainSearchComponent(DefaultComponentContext(lifecycle), FindTrainRuns(repositories),
            { opened = it }, StandardTestDispatcher(testScheduler))
        component.number("abc")
        component.search()
        assertTrue(component.state.value.invalidNumber)
        assertNull(opened)
        component.number("123")
        repositories.runsResult = DataResult.Failure(DomainFailure.OFFLINE)
        component.search()
        runCurrent()
        assertTrue(component.state.value.results.failed)
        repositories.runsResult = DataResult.Data(listOf(testSummary), DataFreshness.Unknown)
        component.search()
        runCurrent()
        assertEquals(testRunId, opened)
        lifecycle.destroy()
    }

    @Test fun detailTogglesPersistentMonitoringAndRepresentsNotificationPermission() = runTest {
        val lifecycle = LifecycleRegistry()
        val realtime = FakeRealtimeRepositories()
        val monitoring = FakeMonitoringRepository()
        var permissionRequests = 0
        val component = TrainDetailComponent(
            DefaultComponentContext(lifecycle),
            testRunId,
            ObserveTrainRun(realtime),
            MutableStateFlow(false),
            StandardTestDispatcher(testScheduler),
            observeMonitor = ObserveTrainMonitor(monitoring),
            startMonitoring = StartTrainMonitoring(monitoring),
            stopMonitoring = StopTrainMonitoring(monitoring),
            requestNotificationPermission = { permissionRequests++; false },
        )
        runCurrent()
        component.toggleMonitoring()
        runCurrent()
        assertTrue(component.state.value.isMonitored)
        assertEquals(false, component.state.value.notificationPermissionGranted)
        assertEquals(1, permissionRequests)
        component.toggleMonitoring()
        runCurrent()
        assertFalse(component.state.value.isMonitored)
        lifecycle.destroy()
    }
}
