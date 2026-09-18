package it.danielebufarini.trenify.feature.train

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.LoadStrikes
import it.danielebufarini.trenify.core.domain.MonitoredTrainSnapshot
import it.danielebufarini.trenify.core.domain.ObserveTrainMonitor
import it.danielebufarini.trenify.core.domain.ObserveTrainRun
import it.danielebufarini.trenify.core.domain.StrikeImpact
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.testing.FakeMonitoringRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testRun
import it.danielebufarini.trenify.core.testing.testRunId
import it.danielebufarini.trenify.core.testing.testStrike
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
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * T7.12-C train detail warnings from the observed local snapshot, including
 * the retained-ended read-only path: no polling restart, no lifecycle
 * changes, no train provider access from strike evaluation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainDetailStrikeWarningTest {
    private val clock = MutableClock()
    private val departure = Instant.parse("2026-09-05T08:00:00Z")
    private val arrival = departure + 3.hours

    private fun snapshotTrain(dep: Instant = departure, arr: Instant = arrival) = testRun.copy(
        summary = testRun.summary.copy(
            scheduledDeparture = dep,
            scheduledArrival = arr,
            status = TrainStatus.RUNNING,
            operator = Operator("Trenitalia"),
        ),
    )

    private fun overlappingStrike() = testStrike.copy(
        start = departure - 1.hours,
        end = arrival + 1.hours,
        operators = listOf(Operator("Trenitalia")),
    )

    private fun freshStrikes() = FakeStrikeRepository().apply {
        state.value = DataResult.Data(listOf(overlappingStrike()), DataFreshness.Fresh(clock.now(), null))
    }

    @Test
    fun detailShowsSnapshotWarningWithoutTrainProviderAccess() = runTest {
        val lifecycle = LifecycleRegistry()
        val trains = FakeRealtimeRepositories()
        trains.trainState.value = DataResult.Data(snapshotTrain(), DataFreshness.Fresh(clock.now(), null))
        val strikes = freshStrikes()
        val component = TrainDetailComponent(
            DefaultComponentContext(lifecycle),
            testRunId,
            ObserveTrainRun(trains),
            MutableStateFlow(false),
            StandardTestDispatcher(testScheduler),
            strikes = LoadStrikes(strikes),
            clock = clock,
        )
        try {
            runCurrent()
            val warnings = component.state.value.strikeWarnings
            assertEquals(StrikeImpact.LIKELY, warnings.single().assessment.impact)
            assertEquals(overlappingStrike().id, warnings.single().strike.id)
            assertFalse(component.state.value.strikesStale)
            // One targeted strike refresh for the snapshot interval; strike
            // evaluation itself issues no train provider access.
            assertEquals(1, strikes.refreshes)
            assertEquals(departure to arrival, strikes.refreshWindows.single())
            assertEquals(0, trains.trainRefreshes)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun unknownCoverageSurfacesUnknownInsteadOfStale() = runTest {
        val lifecycle = LifecycleRegistry()
        val trains = FakeRealtimeRepositories()
        trains.trainState.value = DataResult.Data(snapshotTrain(), DataFreshness.Fresh(clock.now(), null))
        // Default fake state: Unknown freshness, non-overlapping fixture.
        val strikes = FakeStrikeRepository()
        val component = TrainDetailComponent(
            DefaultComponentContext(lifecycle),
            testRunId,
            ObserveTrainRun(trains),
            MutableStateFlow(false),
            StandardTestDispatcher(testScheduler),
            strikes = LoadStrikes(strikes),
            clock = clock,
        )
        try {
            runCurrent()
            assertTrue(component.state.value.strikeWarnings.isEmpty())
            assertTrue(component.state.value.strikesUnknown)
            assertFalse(component.state.value.strikesStale)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun updatedSnapshotRecomputesDetailWarnings() = runTest {
        val lifecycle = LifecycleRegistry()
        val trains = FakeRealtimeRepositories()
        trains.trainState.value = DataResult.Data(snapshotTrain(), DataFreshness.Fresh(clock.now(), null))
        val strikes = freshStrikes()
        val component = TrainDetailComponent(
            DefaultComponentContext(lifecycle),
            testRunId,
            ObserveTrainRun(trains),
            MutableStateFlow(false),
            StandardTestDispatcher(testScheduler),
            strikes = LoadStrikes(strikes),
            clock = clock,
        )
        try {
            runCurrent()
            assertEquals(1, component.state.value.strikeWarnings.size)
            trains.trainState.value = DataResult.Data(
                snapshotTrain(departure + 24.hours, arrival + 24.hours),
                DataFreshness.Fresh(clock.now(), null),
            )
            runCurrent()
            assertTrue(component.state.value.strikeWarnings.isEmpty())
            assertEquals(TrainStatus.RUNNING, trains.trainState.value.let {
                (it as DataResult.Data).value.summary.status
            })
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun endedDetailKeepsWarningsReadOnlyWithoutRestartingPolling() = runTest {
        val lifecycle = LifecycleRegistry()
        val trains = FakeRealtimeRepositories()
        val arrived = snapshotTrain().copy(summary = snapshotTrain().summary.copy(status = TrainStatus.ARRIVED))
        trains.trainState.value = DataResult.Data(arrived, DataFreshness.Fresh(clock.now(), null))
        val monitoring = FakeMonitoringRepository(clock)
        val monitor = monitoring.createMonitor(testRunId, expiresAt = clock.now() + 24.hours)
        monitoring.completeTerminally(
            monitor.id,
            MonitoredTrainSnapshot(arrived, DataFreshness.Fresh(clock.now(), null), clock.now()),
            emptyList(),
            clock.now(),
            1L,
        )
        val endedAt = monitoring.monitors.value.single().endedAt
        val strikes = freshStrikes()
        val component = TrainDetailComponent(
            DefaultComponentContext(lifecycle),
            testRunId,
            ObserveTrainRun(trains),
            MutableStateFlow(false),
            StandardTestDispatcher(testScheduler),
            observeMonitor = ObserveTrainMonitor(monitoring),
            strikes = LoadStrikes(strikes),
        )
        try {
            runCurrent()
            assertTrue(component.state.value.isEnded)
            assertEquals(1, component.state.value.strikeWarnings.size)
            // No polling restart, no lifecycle change, no monitor events.
            assertEquals(0, trains.trainRefreshes)
            assertEquals(endedAt, monitoring.monitors.value.single().endedAt)
            assertTrue(monitoring.persistedEvents.isEmpty())
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun strikeFailureKeepsDetailWarningsStale() = runTest {
        val lifecycle = LifecycleRegistry()
        val trains = FakeRealtimeRepositories()
        trains.trainState.value = DataResult.Data(snapshotTrain(), DataFreshness.Fresh(clock.now(), null))
        val strikes = freshStrikes()
        val component = TrainDetailComponent(
            DefaultComponentContext(lifecycle),
            testRunId,
            ObserveTrainRun(trains),
            MutableStateFlow(false),
            StandardTestDispatcher(testScheduler),
            strikes = LoadStrikes(strikes),
            clock = clock,
        )
        try {
            runCurrent()
            assertEquals(1, component.state.value.strikeWarnings.size)
            strikes.state.value = DataResult.Data(
                listOf(overlappingStrike()),
                DataFreshness.Stale(clock.now(), 2.hours, null),
                DomainFailure.TEMPORARY,
            )
            runCurrent()
            assertEquals(1, component.state.value.strikeWarnings.size)
            assertTrue(component.state.value.strikesStale)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun scheduledBoundsHaveExplicitEdgeSemantics() {
        assertNull(scheduledStrikeBounds(null, null))
        assertEquals(departure to arrival, scheduledStrikeBounds(departure, arrival))
        assertEquals(departure to arrival, scheduledStrikeBounds(arrival, departure))
        val point = requireNotNull(scheduledStrikeBounds(departure, departure))
        assertEquals(departure - 1.minutes, point.first)
        assertEquals(departure + 1.minutes, point.second)
        val single = requireNotNull(scheduledStrikeBounds(departure, null))
        assertEquals(departure - 1.minutes, single.first)
        assertEquals(departure + 1.minutes, single.second)
    }
}
