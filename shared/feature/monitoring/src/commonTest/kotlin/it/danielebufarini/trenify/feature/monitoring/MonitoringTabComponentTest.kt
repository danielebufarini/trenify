package it.danielebufarini.trenify.feature.monitoring

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.testing.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalCoroutinesApi::class)
class MonitoringTabComponentTest {
    @Test fun listReactsAndComponentOwnsStopAndOpenActions() = runTest {
        val clock = MutableClock()
        val repository = FakeMonitoringRepository(clock)
        val monitor = repository.createMonitor(testRunId, expiresAt = clock.now() + 1.hours)
        repository.persistEvaluation(
            monitor.id,
            MonitoredTrainSnapshot(testRun, DataFreshness.Unknown, clock.now()),
            emptyList(),
            monitor.snapshotVersion,
            0L,
        )
        val lifecycle = LifecycleRegistry()
        var opened = false
        val component = DefaultMonitoringTabComponent(
            DefaultComponentContext(lifecycle),
            ObserveActiveMonitors(repository),
            StopTrainMonitoring(repository),
            { opened = true },
            StandardTestDispatcher(testScheduler),
        )
        runCurrent()

        assertEquals(testRun, component.state.value.active.single().monitor.lastSnapshot?.train)
        component.open(testRunId)
        assertTrue(opened)
        component.stop(testRunId)
        runCurrent()
        assertTrue(component.state.value.active.isEmpty())
        lifecycle.destroy()
    }
}
