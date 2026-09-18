package it.danielebufarini.trenify.feature.monitoring

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.MonitorThresholds
import it.danielebufarini.trenify.core.domain.MonitoredTrainSnapshot
import it.danielebufarini.trenify.core.domain.ObserveActiveMonitors
import it.danielebufarini.trenify.core.domain.ObserveEndedMonitors
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.testing.FakeMonitoringRepository
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testRun
import it.danielebufarini.trenify.core.testing.testRunId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Failed-refresh presentation on monitor cards (T7.14 corrective): the
 * retained snapshot stays visible, becomes immediately Stale with the
 * typed OFFLINE/TEMPORARY warning, and recreation preserves the degraded
 * truth without any new fetch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitorRefreshFailurePresentationTest {
    private fun kotlinx.coroutines.test.TestScope.component(
        lifecycle: LifecycleRegistry,
        repository: FakeMonitoringRepository,
        clock: MutableClock,
    ) = DefaultMonitoringTabComponent(
        DefaultComponentContext(lifecycle),
        ObserveActiveMonitors(repository),
        onTrain = {},
        dispatcher = StandardTestDispatcher(testScheduler),
        observeEndedMonitors = ObserveEndedMonitors(repository),
        policy = it.danielebufarini.trenify.core.domain.RealtimePolicy(),
        clock = clock,
    )

    private suspend fun stageDegraded(
        repository: FakeMonitoringRepository,
        clock: MutableClock,
        failure: DomainFailure,
    ) {
        val created = repository.createMonitor(testRunId, MonitorThresholds(delayMinutes = 15), clock.now() + 1.hours)
        repository.monitors.value = listOf(
            created.copy(
                lastSnapshot = MonitoredTrainSnapshot(
                    testRun,
                    DataFreshness.Fresh(clock.now(), null),
                    clock.now(),
                ),
            ),
        )
        repository.recordRefreshFailure(created.id, failure, clock.now())
    }

    @Test fun offlineFailureShowsStaleSnapshotWithTypedWarning() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val repository = FakeMonitoringRepository(clock)
        stageDegraded(repository, clock, DomainFailure.OFFLINE)
        val component = component(lifecycle, repository, clock)
        lifecycle.resume()
        try {
            runCurrent()
            val card = component.state.value.active.single()
            // The cached snapshot remains visible...
            assertNotNull(card.monitor.lastSnapshot)
            // ...but presentation is immediately Stale with the typed warning.
            assertTrue(card.displayFreshness is DataFreshness.Stale)
            assertEquals(DomainFailure.OFFLINE, card.refreshFailure)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun notFoundFailureKeepsKnownTrainContext() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val repository = FakeMonitoringRepository(clock)
        stageDegraded(repository, clock, DomainFailure.NOT_FOUND)
        val component = component(lifecycle, repository, clock)
        lifecycle.resume()
        try {
            runCurrent()
            val card = component.state.value.active.single()
            assertTrue(card.displayFreshness is DataFreshness.Stale)
            // The typed failure survives for the realtime-unavailable
            // wording; the train itself is never questioned.
            assertEquals(DomainFailure.NOT_FOUND, card.refreshFailure)
            assertNotNull(card.monitor.lastSnapshot)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun temporaryFailureShowsDistinguishableWarning() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val repository = FakeMonitoringRepository(clock)
        stageDegraded(repository, clock, DomainFailure.TEMPORARY)
        val component = component(lifecycle, repository, clock)
        lifecycle.resume()
        try {
            runCurrent()
            val card = component.state.value.active.single()
            assertTrue(card.displayFreshness is DataFreshness.Stale)
            assertEquals(DomainFailure.TEMPORARY, card.refreshFailure)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun recreationPreservesDegradedTruthWithoutNewFetch() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val repository = FakeMonitoringRepository(clock)
        stageDegraded(repository, clock, DomainFailure.OFFLINE)
        val first = component(lifecycle, repository, clock)
        lifecycle.resume()
        runCurrent()
        assertEquals(DomainFailure.OFFLINE, first.state.value.active.single().refreshFailure)
        lifecycle.destroy()

        val recreated = LifecycleRegistry()
        val second = component(recreated, repository, clock)
        recreated.resume()
        try {
            runCurrent()
            // No successful fetch happened: the degraded truth survives recreation.
            val card = second.state.value.active.single()
            assertTrue(card.displayFreshness is DataFreshness.Stale)
            assertEquals(DomainFailure.OFFLINE, card.refreshFailure)
            assertNotNull(card.monitor.lastSnapshot)
        } finally {
            recreated.destroy()
        }
    }
}
