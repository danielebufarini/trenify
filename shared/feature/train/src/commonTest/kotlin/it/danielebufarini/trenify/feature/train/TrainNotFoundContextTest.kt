package it.danielebufarini.trenify.feature.train

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.ObserveTrainRun
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testRun
import it.danielebufarini.trenify.core.testing.testRunId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contextual NOT_FOUND for an already-identified train (T7.14 final
 * pass): the repository taxonomy stays `NOT_FOUND`, while the detail
 * keeps that failure typed for the realtime-unavailable presentation —
 * never the train-number search wording. Cached data is retained and
 * marked Stale; bare failures stay unstale with no invented content.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainNotFoundContextTest {
    @Test fun bareNotFoundKeepsTypedFailureWithNoInventedContent() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        repositories.trainState.value = DataResult.Failure(DomainFailure.NOT_FOUND)
        val component = TrainDetailComponent(
            DefaultComponentContext(lifecycle),
            testRunId,
            ObserveTrainRun(repositories),
            MutableStateFlow(false),
            StandardTestDispatcher(testScheduler),
            clock = MutableClock(),
        )
        lifecycle.resume()
        try {
            runCurrent()
            assertEquals(DomainFailure.NOT_FOUND, component.state.value.realtime.failure)
            assertNull(component.state.value.data)
            assertFalse(component.state.value.stale)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun cachedNotFoundRetainsSnapshotAndMarksItStale() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val repositories = FakeRealtimeRepositories()
        repositories.trainState.value = DataResult.Data(
            testRun,
            DataFreshness.Fresh(clock.now(), null),
        )
        val component = TrainDetailComponent(
            DefaultComponentContext(lifecycle),
            testRunId,
            ObserveTrainRun(repositories),
            MutableStateFlow(false),
            StandardTestDispatcher(testScheduler),
            clock = clock,
        )
        lifecycle.resume()
        try {
            runCurrent()
            assertEquals(testRun, component.state.value.data)
            // The provider now reports the known run as not found.
            repositories.trainState.value = DataResult.Failure(DomainFailure.NOT_FOUND)
            runCurrent()
            assertEquals(testRun, component.state.value.data)
            assertEquals(DomainFailure.NOT_FOUND, component.state.value.realtime.failure)
            assertTrue(component.state.value.stale)
        } finally {
            lifecycle.destroy()
        }
    }
}
