package it.danielebufarini.trenify.feature.train

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import it.danielebufarini.trenify.core.domain.MonitorEventKind
import it.danielebufarini.trenify.core.domain.MonitorThresholds
import it.danielebufarini.trenify.core.domain.MonitoringRepository
import it.danielebufarini.trenify.core.domain.NotificationSettings
import it.danielebufarini.trenify.core.domain.NotificationSettingsRepository
import it.danielebufarini.trenify.core.domain.ObserveNotificationSettings
import it.danielebufarini.trenify.core.domain.ObserveTrainMonitor
import it.danielebufarini.trenify.core.domain.ObserveTrainRun
import it.danielebufarini.trenify.core.domain.SetMonitorNotifications
import it.danielebufarini.trenify.core.domain.StartTrainMonitoring
import it.danielebufarini.trenify.core.domain.StopTrainMonitoring
import it.danielebufarini.trenify.core.domain.UpdateMonitorThresholds
import it.danielebufarini.trenify.core.model.TrainRunId
import it.danielebufarini.trenify.core.testing.FakeMonitoringRepository
import it.danielebufarini.trenify.core.testing.FakeNotificationSettingsRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.testRunId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T7.7 monitor preference editing from train detail: new monitors inherit
 * installation defaults, and mute/threshold/flag edits preserve the monitor
 * with typed validation and retryable errors.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainMonitorPreferencesTest {
    private fun kotlinx.coroutines.test.TestScope.detail(
        realtime: FakeRealtimeRepositories = FakeRealtimeRepositories(),
        monitoring: MonitoringRepository = FakeMonitoringRepository(),
        settings: FakeNotificationSettingsRepository = FakeNotificationSettingsRepository(),
    ) = TrainDetailComponent(
        DefaultComponentContext(LifecycleRegistry()),
        testRunId,
        ObserveTrainRun(realtime),
        MutableStateFlow(false),
        StandardTestDispatcher(testScheduler),
        observeMonitor = ObserveTrainMonitor(monitoring),
        startMonitoring = StartTrainMonitoring(monitoring),
        stopMonitoring = StopTrainMonitoring(monitoring),
        requestNotificationPermission = { true },
        observeNotificationSettings = ObserveNotificationSettings(settings),
        setMonitorNotifications = SetMonitorNotifications(monitoring),
        updateMonitorThresholds = UpdateMonitorThresholds(monitoring),
    )

    @Test fun newMonitorInheritsInstallationDefaults() = runTest {
        val monitoring = FakeMonitoringRepository()
        val settings = FakeNotificationSettingsRepository(
            NotificationSettings(defaultThresholds = MonitorThresholds(delayMinutes = 45, notifyArrival = false)),
        )
        val component = detail(monitoring = monitoring, settings = settings)
        runCurrent()

        component.toggleMonitoring()
        runCurrent()
        assertTrue(component.state.value.isMonitored)
        val monitor = assertNotNull(monitoring.observeMonitor(testRunId).first())
        assertEquals(45, monitor.thresholds.delayMinutes)
        assertFalse(monitor.thresholds.notifyArrival)
    }

    @Test fun monitorCreationResolvesAuthoritativeDefaultsBeforeAnyObservation() = runTest {
        val persisted = NotificationSettings(
            defaultThresholds = MonitorThresholds(delayMinutes = 45, notifyArrival = false),
        )
        val settings = GatedSettingsRepository(persisted)
        val monitoring = FakeMonitoringRepository()
        val component = TrainDetailComponent(
            DefaultComponentContext(LifecycleRegistry()),
            testRunId,
            ObserveTrainRun(FakeRealtimeRepositories()),
            MutableStateFlow(false),
            StandardTestDispatcher(testScheduler),
            observeMonitor = ObserveTrainMonitor(monitoring),
            startMonitoring = StartTrainMonitoring(monitoring),
            stopMonitoring = StopTrainMonitoring(monitoring),
            observeNotificationSettings = ObserveNotificationSettings(settings),
            setMonitorNotifications = SetMonitorNotifications(monitoring),
            updateMonitorThresholds = UpdateMonitorThresholds(monitoring),
        )
        // Start monitoring before any cached observation could populate a
        // local default; creation itself must resolve the persisted values.
        component.toggleMonitoring()
        settings.gate.complete(Unit)
        runCurrent()
        runCurrent()

        val monitor = assertNotNull(monitoring.observeMonitor(testRunId).first())
        assertEquals(45, monitor.thresholds.delayMinutes)
        assertFalse(monitor.thresholds.notifyArrival)

        // Later installation default changes never rewrite the created monitor.
        settings.state.value = NotificationSettings(
            defaultThresholds = MonitorThresholds(delayMinutes = 5),
        )
        runCurrent()
        val untouched = assertNotNull(monitoring.observeMonitor(testRunId).first())
        assertEquals(monitor.id, untouched.id)
        assertEquals(45, untouched.thresholds.delayMinutes)
        assertFalse(untouched.thresholds.notifyArrival)
    }

    @Test fun muteThresholdAndFlagEditsPreserveTheMonitor() = runTest {
        val monitoring = FakeMonitoringRepository()
        val component = detail(monitoring = monitoring)
        runCurrent()
        component.toggleMonitoring()
        runCurrent()
        val created = assertNotNull(monitoring.observeMonitor(testRunId).first())
        runCurrent()
        assertNotNull(component.state.value.monitor)
        assertEquals("15", component.state.value.monitorThresholdText)

        component.setMonitorNotificationsEnabled(false)
        runCurrent()
        assertFalse(component.state.value.monitor?.notificationsEnabled ?: true)
        assertFalse(monitoring.observeMonitor(testRunId).first()?.notificationsEnabled ?: true)
        assertTrue(component.state.value.isMonitored)

        component.editMonitorThreshold("25")
        component.saveMonitorThreshold()
        runCurrent()
        val edited = assertNotNull(monitoring.observeMonitor(testRunId).first())
        assertEquals(created.id, edited.id)
        assertEquals(25, edited.thresholds.delayMinutes)
        assertFalse(edited.notificationsEnabled)
        assertTrue(edited.enabled)

        component.setMonitorEventFlag(MonitorEventKind.PLATFORM, false)
        runCurrent()
        val flagged = assertNotNull(monitoring.observeMonitor(testRunId).first())
        assertEquals(created.id, flagged.id)
        assertFalse(flagged.thresholds.notifyPlatform)
        assertTrue(flagged.thresholds.notifyDelay)
    }

    @Test fun invalidThresholdNeverReachesTheRepository() = runTest {
        val monitoring = FakeMonitoringRepository()
        val component = detail(monitoring = monitoring)
        runCurrent()
        component.toggleMonitoring()
        runCurrent()
        runCurrent()

        component.editMonitorThreshold("0")
        assertTrue(component.state.value.monitorThresholdInvalid)
        component.saveMonitorThreshold()
        runCurrent()
        assertTrue(component.state.value.monitorThresholdInvalid)
        assertEquals(15, monitoring.observeMonitor(testRunId).first()?.thresholds?.delayMinutes)
    }

    @Test fun preferenceFailureIsRetryable() = runTest {
        val delegate = FakeMonitoringRepository()
        delegate.createMonitor(testRunId)
        var fail = true
        val repository = object : MonitoringRepository by delegate {
            override suspend fun setMonitorNotificationsEnabled(trainRunId: TrainRunId, enabled: Boolean) {
                if (fail) throw IllegalStateException("disk")
                delegate.setMonitorNotificationsEnabled(trainRunId, enabled)
            }
        }
        val component = detail(monitoring = repository)
        runCurrent()
        runCurrent()
        assertNotNull(component.state.value.monitor)

        component.setMonitorNotificationsEnabled(false)
        runCurrent()
        assertTrue(component.state.value.monitorPrefsError)
        assertFalse(component.state.value.monitorNotificationsPending)

        fail = false
        component.retryMonitorPreferences()
        runCurrent()
        assertFalse(component.state.value.monitorPrefsError)
        assertFalse(delegate.observeMonitor(testRunId).first()?.notificationsEnabled ?: true)
    }

    /**
     * Settings source whose first emission is gated, proving monitor
     * creation cannot fall back to hardcoded defaults while observation is
     * still pending.
     */
    private class GatedSettingsRepository(initial: NotificationSettings) : NotificationSettingsRepository {
        val state = MutableStateFlow(initial)
        val gate = CompletableDeferred<Unit>()

        override fun observe(): Flow<NotificationSettings> = flow {
            gate.await()
            emitAll(state)
        }

        override suspend fun setNotificationsEnabled(enabled: Boolean) {
            state.value = state.value.copy(notificationsEnabled = enabled)
        }

        override suspend fun setDefaultThresholds(thresholds: MonitorThresholds) {
            state.value = state.value.copy(defaultThresholds = thresholds)
        }
    }
}
