package it.danielebufarini.trenify.feature.train

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.MonitorThresholds
import it.danielebufarini.trenify.core.domain.MonitoredTrainSnapshot
import it.danielebufarini.trenify.core.domain.MonitoringRepository
import it.danielebufarini.trenify.core.domain.ObserveTrainMonitor
import it.danielebufarini.trenify.core.domain.ObserveTrainRun
import it.danielebufarini.trenify.core.domain.RemoveEndedMonitor
import it.danielebufarini.trenify.core.domain.StartTrainMonitoring
import it.danielebufarini.trenify.core.domain.StopTrainMonitoring
import it.danielebufarini.trenify.core.domain.TrainMonitor
import it.danielebufarini.trenify.core.domain.TrainMonitorEvent
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.TrainRunId
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.testing.FakeMonitoringRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testRun
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
import kotlin.time.Duration.Companion.hours

/**
 * T7.11 corrective pass 2: the train detail exposes no Start/Stop, manual
 * Refresh, active preferences or ended Remove before the first persisted
 * lifecycle observation resolves — an ended route must never briefly render
 * active controls. Direct actions are no-ops while unresolved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainDetailLifecycleResolutionTest {
    private class GatedMonitoringRepository(
        private val delegate: FakeMonitoringRepository,
        private val gate: CompletableDeferred<Unit>,
    ) : MonitoringRepository by delegate {
        override fun observeMonitor(trainRunId: TrainRunId): Flow<TrainMonitor?> = flow {
            gate.await()
            emitAll(delegate.observeMonitor(trainRunId))
        }
    }

    private fun kotlinx.coroutines.test.TestScope.detail(
        trains: FakeRealtimeRepositories,
        monitoring: MonitoringRepository,
        lifecycle: LifecycleRegistry = LifecycleRegistry(),
    ): TrainDetailComponent = TrainDetailComponent(
        DefaultComponentContext(lifecycle),
        testRunId,
        ObserveTrainRun(trains),
        MutableStateFlow(true),
        StandardTestDispatcher(testScheduler),
        observeMonitor = ObserveTrainMonitor(monitoring),
        startMonitoring = StartTrainMonitoring(monitoring),
        stopMonitoring = StopTrainMonitoring(monitoring),
        removeEndedMonitor = RemoveEndedMonitor(monitoring),
    )

    private suspend fun endMonitor(monitoring: FakeMonitoringRepository, clock: MutableClock) {
        monitoring.createMonitor(testRunId, MonitorThresholds(delayMinutes = 15), clock.now() + 1.hours)
        val created = assertNotNull(monitoring.observeMonitor(testRunId).first())
        val arrived = testRun.copy(summary = testRun.summary.copy(status = TrainStatus.ARRIVED))
        monitoring.completeTerminally(
            created.id,
            MonitoredTrainSnapshot(arrived, DataFreshness.Unknown, clock.now()),
            listOf(TrainMonitorEvent.Arrived(testRunId)),
            clock.now(), 2L)
    }

    @Test fun unresolvedStateIsExposedWhileDirectStartStaysSafe() = runTest {
        val clock = MutableClock()
        val trains = FakeRealtimeRepositories()
        val delegate = FakeMonitoringRepository(clock)
        val gate = CompletableDeferred<Unit>()
        val lifecycle = LifecycleRegistry()
        val component = detail(trains, GatedMonitoringRepository(delegate, gate), lifecycle)
        try {
            assertFalse(component.state.value.monitorLifecycleResolved)

            // Starting before the first lifecycle observation still works
            // (creation resolves authoritative defaults, as pinned by the
            // preferences tests); the UI — not the call — is what stays
            // hidden until resolution.
            component.toggleMonitoring()
            runCurrent()
            assertFalse(component.state.value.monitorLifecycleResolved)
            assertNotNull(delegate.observeMonitor(testRunId).first())

            gate.complete(Unit)
            runCurrent()
            assertTrue(component.state.value.monitorLifecycleResolved)
            assertTrue(component.state.value.isMonitored)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun endedRouteNeverExposesActiveControlsBeforeResolution() = runTest {
        val clock = MutableClock()
        val trains = FakeRealtimeRepositories()
        trains.trainState.value = DataResult.Data(testRun, DataFreshness.Unknown)
        val delegate = FakeMonitoringRepository(clock)
        endMonitor(delegate, clock)
        val gate = CompletableDeferred<Unit>()
        val lifecycle = LifecycleRegistry()
        val component = detail(trains, GatedMonitoringRepository(delegate, gate), lifecycle)
        try {
            assertFalse(component.state.value.monitorLifecycleResolved)
            assertFalse(component.state.value.isEnded)

            // While unresolved a toggle for the ended route cannot
            // reactivate it (creation returns the retained row unchanged),
            // the manual refresh resolves the ended lifecycle before any
            // provider access (zero calls), and removal needs the resolved
            // ended state.
            component.toggleMonitoring()
            component.refresh()
            component.removeEndedMonitor()
            runCurrent()
            assertFalse(component.state.value.monitorLifecycleResolved)
            assertEquals(0, trains.trainRefreshes)
            val retained = assertNotNull(delegate.observeMonitor(testRunId).first())
            assertNotNull(retained.endedAt)
            assertFalse(component.state.value.isMonitored)

            gate.complete(Unit)
            runCurrent()
            assertTrue(component.state.value.monitorLifecycleResolved)
            assertTrue(component.state.value.isEnded)
            assertFalse(component.state.value.isMonitored)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun detailWithoutMonitorSourceKeepsLegacyResolvedBehavior() = runTest {
        val trains = FakeRealtimeRepositories()
        val lifecycle = LifecycleRegistry()
        val component = TrainDetailComponent(
            DefaultComponentContext(lifecycle),
            testRunId,
            ObserveTrainRun(trains),
            MutableStateFlow(true),
            StandardTestDispatcher(testScheduler),
        )
        try {
            runCurrent()
            assertTrue(component.state.value.monitorLifecycleResolved)
        } finally {
            lifecycle.destroy()
        }
    }
}
