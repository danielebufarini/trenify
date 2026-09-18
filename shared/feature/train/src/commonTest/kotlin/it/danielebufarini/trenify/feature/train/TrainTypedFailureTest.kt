package it.danielebufarini.trenify.feature.train

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.testing.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/**
 * Typed FS §23 failure presentation for train search and detail (T7.14-A):
 * empty candidates are the not-found condition (never a retryable failure),
 * refresh failures keep cached content with a typed warning, and retry
 * recovers to fresh data.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainTypedFailureTest {
    @Test fun searchEmptyIsNotFoundWithoutFailure() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        repositories.runsResult = DataResult.Data(emptyList(), DataFreshness.Unknown)
        val component = TrainSearchComponent(
            DefaultComponentContext(lifecycle), FindTrainRuns(repositories), {},
            StandardTestDispatcher(testScheduler),
        )
        try {
            component.number("9999")
            component.search()
            runCurrent()
            // A valid empty lookup is not a failure: the screen renders the
            // not-found message from data emptiness, never a retryable error.
            assertNull(component.state.value.results.failure)
            assertFalse(component.state.value.results.failed)
            assertEquals(emptyList(), component.state.value.results.data)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun searchOfflineIsTypedAndRetryRecovers() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        repositories.runsResult = DataResult.Failure(DomainFailure.OFFLINE)
        val component = TrainSearchComponent(
            DefaultComponentContext(lifecycle), FindTrainRuns(repositories), {},
            StandardTestDispatcher(testScheduler),
        )
        try {
            component.number("9624")
            component.search()
            runCurrent()
            assertEquals(DomainFailure.OFFLINE, component.state.value.results.failure)
            repositories.runsResult = DataResult.Data(listOf(testSummary), DataFreshness.Unknown)
            component.search()
            runCurrent()
            assertNull(component.state.value.results.failure)
            assertEquals(listOf(testSummary), component.state.value.results.data)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun searchWarningKeepsCandidatesWithTypedFailure() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        repositories.runsResult = DataResult.Data(
            listOf(testSummary), DataFreshness.Stale(MutableClock().now(), 90.seconds, null),
            DomainFailure.TEMPORARY,
        )
        val component = TrainSearchComponent(
            DefaultComponentContext(lifecycle), FindTrainRuns(repositories), {},
            StandardTestDispatcher(testScheduler),
        )
        try {
            component.number("9624")
            component.search()
            runCurrent()
            assertEquals(DomainFailure.TEMPORARY, component.state.value.results.failure)
            assertEquals(listOf(testSummary), component.state.value.results.data)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun detailStaleContentPlusTemporaryKeepsDataWithTypedWarning() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        repositories.trainState.value = DataResult.Data(
            testRun, DataFreshness.Stale(MutableClock().now(), 90.seconds, null), DomainFailure.TEMPORARY)
        val component = TrainDetailComponent(
            DefaultComponentContext(lifecycle), testRunId, ObserveTrainRun(repositories),
            MutableStateFlow(false), StandardTestDispatcher(testScheduler),
        )
        try {
            lifecycle.resume()
            runCurrent()
            assertEquals(testRun, component.state.value.data)
            assertEquals(DomainFailure.TEMPORARY, component.state.value.realtime.failure)
            assertTrue(component.state.value.stale)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun detailNoCacheFailureIsTypedAndRefreshRecovers() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        repositories.trainState.value = DataResult.Failure(DomainFailure.OFFLINE)
        val component = TrainDetailComponent(
            DefaultComponentContext(lifecycle), testRunId, ObserveTrainRun(repositories),
            MutableStateFlow(false), StandardTestDispatcher(testScheduler),
        )
        try {
            lifecycle.resume()
            runCurrent()
            assertNull(component.state.value.data)
            assertEquals(DomainFailure.OFFLINE, component.state.value.realtime.failure)
            repositories.trainState.value = DataResult.Data(
                testRun, DataFreshness.Fresh(MutableClock().now(), null))
            runCurrent()
            assertNull(component.state.value.realtime.failure)
            assertEquals(testRun, component.state.value.data)
        } finally {
            lifecycle.destroy()
        }
    }
}
