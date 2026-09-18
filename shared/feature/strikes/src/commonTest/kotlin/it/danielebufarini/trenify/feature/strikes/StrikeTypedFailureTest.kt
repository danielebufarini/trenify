package it.danielebufarini.trenify.feature.strikes

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.LoadStrikes
import it.danielebufarini.trenify.core.domain.StrikeRefresh
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testStrike
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Typed FS §23 failure presentation for the strike list (T7.14-A): cached
 * strikes survive refresh failures with a typed warning, no-cache failures
 * are typed with retry intact, a bare NOT_FOUND renders empty (never a
 * train message), and refresh recovers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StrikeTypedFailureTest {
    @Test fun staleStrikesPlusOfflineKeepContentWithTypedWarning() = runTest {
        val lifecycle = LifecycleRegistry()
        val repository = FakeStrikeRepository()
        val clock = MutableClock()
        repository.state.value = DataResult.Data(
            listOf(testStrike), DataFreshness.Stale(clock.now(), 90.seconds, null), DomainFailure.OFFLINE)
        repository.refreshResult = DataResult.Failure(DomainFailure.OFFLINE)
        val component = DefaultAlertsTabComponent(
            DefaultComponentContext(lifecycle), LoadStrikes(repository),
            clock = clock, dispatcher = StandardTestDispatcher(testScheduler),
        )
        lifecycle.resume()
        try {
            runCurrent()
            assertTrue(component.state.value.realtime.data?.any { it.id == testStrike.id } == true)
            assertEquals(DomainFailure.OFFLINE, component.state.value.realtime.failure)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun noCacheTemporaryIsTypedAndRefreshRecovers() = runTest {
        val lifecycle = LifecycleRegistry()
        val repository = FakeStrikeRepository()
        val clock = MutableClock()
        repository.state.value = DataResult.Failure(DomainFailure.TEMPORARY)
        repository.refreshResult = DataResult.Failure(DomainFailure.TEMPORARY)
        val component = DefaultAlertsTabComponent(
            DefaultComponentContext(lifecycle), LoadStrikes(repository),
            clock = clock, dispatcher = StandardTestDispatcher(testScheduler),
        )
        lifecycle.resume()
        try {
            runCurrent()
            assertEquals(DomainFailure.TEMPORARY, component.state.value.realtime.failure)
            repository.state.value = DataResult.Data(listOf(testStrike), DataFreshness.Fresh(clock.now(), null))
            repository.refreshResult =
                DataResult.Data(StrikeRefresh(listOf(testStrike)), DataFreshness.Fresh(clock.now(), null))
            component.refresh()
            runCurrent()
            assertNull(component.state.value.realtime.failure)
            assertTrue(component.state.value.realtime.data?.any { it.id == testStrike.id } == true)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun bareNotFoundRendersEmptyInsteadOfFailure() = runTest {
        val lifecycle = LifecycleRegistry()
        val repository = FakeStrikeRepository()
        val clock = MutableClock()
        repository.state.value = DataResult.Failure(DomainFailure.NOT_FOUND)
        repository.refreshResult = DataResult.Data(StrikeRefresh(emptyList()), DataFreshness.Unknown)
        val component = DefaultAlertsTabComponent(
            DefaultComponentContext(lifecycle), LoadStrikes(repository),
            clock = clock, dispatcher = StandardTestDispatcher(testScheduler),
        )
        lifecycle.resume()
        try {
            runCurrent()
            // The never-fetched sentinel is not a reportable failure; the
            // refresh resolves the empty list.
            assertNull(component.state.value.realtime.failure)
        } finally {
            lifecycle.destroy()
        }
    }
}
