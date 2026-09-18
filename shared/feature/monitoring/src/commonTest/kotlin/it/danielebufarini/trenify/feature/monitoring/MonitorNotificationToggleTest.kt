package it.danielebufarini.trenify.feature.monitoring

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import it.danielebufarini.trenify.core.domain.MonitorThresholds
import it.danielebufarini.trenify.core.domain.MonitoredTrainSnapshot
import it.danielebufarini.trenify.core.domain.MonitoringRepository
import it.danielebufarini.trenify.core.domain.ObserveActiveMonitors
import it.danielebufarini.trenify.core.domain.SetMonitorNotifications
import it.danielebufarini.trenify.core.domain.StopTrainMonitoring
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.model.TrainRunId
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * T7.7 per-train mute/unmute from the monitoring list: reactive, preserving
 * the monitor, with pending and retryable error state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitorNotificationToggleTest {
    private fun kotlinx.coroutines.test.TestScope.list(
        repository: MonitoringRepository = FakeMonitoringRepository(MutableClock()),
    ) = DefaultMonitoringTabComponent(
        DefaultComponentContext(LifecycleRegistry()),
        ObserveActiveMonitors(repository),
        StopTrainMonitoring(repository),
        {},
        StandardTestDispatcher(testScheduler),
        setMonitorNotifications = SetMonitorNotifications(repository),
    )

    @Test fun muteKeepsTheMonitorAndUnmuteRestoresDelivery() = runTest {
        val clock = MutableClock()
        val repository = FakeMonitoringRepository(clock)
        val monitor = repository.createMonitor(
            testRunId,
            MonitorThresholds(delayMinutes = 15),
            clock.now() + 1.hours,
        )
        repository.persistEvaluation(
            monitor.id,
            MonitoredTrainSnapshot(testRun, DataFreshness.Unknown, clock.now()),
            emptyList(),
            monitor.snapshotVersion,
            0L,
        )
        val component = list(repository)
        runCurrent()
        assertTrue(component.state.value.active.single().monitor.notificationsEnabled)

        component.setMonitorNotifications(testRunId, false)
        runCurrent()
        val muted = component.state.value.active.single().monitor
        assertFalse(muted.notificationsEnabled)
        assertEquals(monitor.id, muted.id)
        assertEquals(testRun, muted.lastSnapshot?.train)
        assertTrue(component.state.value.pendingNotifications.isEmpty())
        assertNull(component.state.value.failedNotificationMonitor)

        component.setMonitorNotifications(testRunId, true)
        runCurrent()
        assertTrue(component.state.value.active.single().monitor.notificationsEnabled)
    }

    @Test fun muteFailureIsRetryable() = runTest {
        val delegate = FakeMonitoringRepository(MutableClock())
        delegate.createMonitor(testRunId)
        val repository = object : MonitoringRepository by delegate {
            var fail = true
            override suspend fun setMonitorNotificationsEnabled(trainRunId: TrainRunId, enabled: Boolean) {
                if (fail) throw IllegalStateException("disk")
                delegate.setMonitorNotificationsEnabled(trainRunId, enabled)
            }
        }
        val component = list(repository)
        runCurrent()

        component.setMonitorNotifications(testRunId, false)
        runCurrent()
        assertEquals(testRunId, component.state.value.failedNotificationMonitor)
        assertTrue(component.state.value.pendingNotifications.isEmpty())
        // The persisted value is untouched by the failed write.
        assertTrue(component.state.value.active.single().monitor.notificationsEnabled)

        repository.fail = false
        component.retryMonitorNotifications()
        runCurrent()
        assertNull(component.state.value.failedNotificationMonitor)
        assertFalse(component.state.value.active.single().monitor.notificationsEnabled)
    }

    @Test fun unrelatedMonitorsAreUnaffectedByAMute() = runTest {
        val clock = MutableClock()
        val repository = FakeMonitoringRepository(clock)
        repository.createMonitor(testRunId)
        val other = testRunId.copy(number = TrainNumber("456"))
        repository.createMonitor(other)
        val component = list(repository)
        runCurrent()
        assertEquals(2, component.state.value.active.size)

        component.setMonitorNotifications(testRunId, false)
        runCurrent()
        val states = component.state.value.active.associate { it.monitor.trainRunId to it.monitor.notificationsEnabled }
        assertEquals(mapOf(testRunId to false, other to true), states)
    }

}
