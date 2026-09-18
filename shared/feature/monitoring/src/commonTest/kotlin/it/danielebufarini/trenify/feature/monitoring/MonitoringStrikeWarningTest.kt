package it.danielebufarini.trenify.feature.monitoring

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.LoadStrikes
import it.danielebufarini.trenify.core.domain.MonitorId
import it.danielebufarini.trenify.core.domain.MonitorThresholds
import it.danielebufarini.trenify.core.domain.MonitoredTrainSnapshot
import it.danielebufarini.trenify.core.domain.ObserveActiveMonitors
import it.danielebufarini.trenify.core.domain.ObserveEndedMonitors
import it.danielebufarini.trenify.core.domain.StopTrainMonitoring
import it.danielebufarini.trenify.core.domain.StrikeImpact
import it.danielebufarini.trenify.core.domain.StrikeRefresh
import it.danielebufarini.trenify.core.domain.TrainMonitor
import it.danielebufarini.trenify.core.model.TrainRunId
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.StrikeStatus
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.testing.FakeMonitoringRepository
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testRun
import it.danielebufarini.trenify.core.testing.testRunId
import it.danielebufarini.trenify.core.testing.testStation
import it.danielebufarini.trenify.core.testing.testStrike
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * T7.12-C monitored-train warnings from local snapshots: list/ended cards
 * expose warnings through one union refresh, react to strike and snapshot
 * changes, and never disturb the T7.11 terminal lifecycle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitoringStrikeWarningTest {
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
        end = arrival + 6.hours,
        operators = listOf(Operator("Trenitalia")),
    )

    private fun freshStrikes() = FakeStrikeRepository().apply {
        state.value = DataResult.Data(listOf(overlappingStrike()), DataFreshness.Fresh(clock.now(), null))
    }

    private suspend fun kotlinx.coroutines.test.TestScope.monitorWithSnapshot(
        monitors: FakeMonitoringRepository,
        dep: Instant = departure,
        arr: Instant = arrival,
        id: TrainRunId = testRunId,
    ) = monitors.createMonitor(id, expiresAt = clock.now() + 24.hours).also { monitor ->
        monitors.persistEvaluation(
            monitor.id,
            MonitoredTrainSnapshot(snapshotTrain(dep, arr), DataFreshness.Fresh(clock.now(), null), clock.now()),
            emptyList(),
            monitor.snapshotVersion,
            1L,
        )
    }

    private fun kotlinx.coroutines.test.TestScope.component(
        lifecycle: LifecycleRegistry,
        monitors: FakeMonitoringRepository,
        strikes: FakeStrikeRepository,
    ) = DefaultMonitoringTabComponent(
        DefaultComponentContext(lifecycle),
        ObserveActiveMonitors(monitors),
        StopTrainMonitoring(monitors),
        {},
        StandardTestDispatcher(testScheduler),
        observeEndedMonitors = ObserveEndedMonitors(monitors),
        strikes = LoadStrikes(strikes),
        clock = clock,
    )

    @Test
    fun cardsExposeSnapshotWarningsThroughOneUnionRefresh() = runTest {
        val lifecycle = LifecycleRegistry()
        val monitors = FakeMonitoringRepository(clock)
        monitorWithSnapshot(monitors)
        val secondId = testRunId.copy(number = TrainNumber("456"))
        monitorWithSnapshot(monitors, departure + 5.hours, arrival + 5.hours, secondId)
        val strikes = freshStrikes()
        val component = component(lifecycle, monitors, strikes)
        lifecycle.resume()
        try {
            runCurrent()
            assertEquals(2, component.state.value.active.size)
            component.state.value.active.forEach { card ->
                assertEquals(StrikeImpact.LIKELY, card.strikeWarnings.single().assessment.impact)
                assertEquals(overlappingStrike().id, card.strikeWarnings.single().strike.id)
                assertFalse(card.strikesStale)
            }
            // One union refresh for all cards — never per-card polling.
            assertEquals(1, strikes.refreshes)
            assertEquals(departure to arrival + 5.hours, strikes.refreshWindows.single())
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun unknownCoverageSurfacesUnknownInsteadOfStale() = runTest {
        val lifecycle = LifecycleRegistry()
        val monitors = FakeMonitoringRepository(clock)
        monitorWithSnapshot(monitors)
        // Default fake state: Unknown freshness, non-overlapping fixture.
        val strikes = FakeStrikeRepository()
        val component = component(lifecycle, monitors, strikes)
        lifecycle.resume()
        try {
            runCurrent()
            val card = component.state.value.active.single()
            assertTrue(card.strikeWarnings.isEmpty())
            assertTrue(card.strikesUnknown)
            assertFalse(card.strikesStale)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun strikeModificationRevocationExpiryAndFailureReactTruthfully() = runTest {
        val lifecycle = LifecycleRegistry()
        val monitors = FakeMonitoringRepository(clock)
        monitorWithSnapshot(monitors)
        val strikes = freshStrikes()
        val component = component(lifecycle, monitors, strikes)
        lifecycle.resume()
        try {
            runCurrent()
            assertEquals(1, component.state.value.active.single().strikeWarnings.size)

            // Modification keeps warning; revocation removes it reactively.
            strikes.state.value = DataResult.Data(
                listOf(overlappingStrike().copy(status = StrikeStatus.MODIFIED)),
                DataFreshness.Fresh(clock.now(), null),
            )
            runCurrent()
            assertEquals(1, component.state.value.active.single().strikeWarnings.size)
            strikes.state.value = DataResult.Data(
                listOf(overlappingStrike().copy(status = StrikeStatus.REVOKED)),
                DataFreshness.Fresh(clock.now(), null),
            )
            runCurrent()
            assertTrue(component.state.value.active.single().strikeWarnings.isEmpty())

            // Failure retains cached warnings as stale without touching monitors.
            strikes.state.value = DataResult.Data(
                listOf(overlappingStrike()),
                DataFreshness.Stale(clock.now(), 2.hours, null),
                DomainFailure.TEMPORARY,
            )
            strikes.refreshResult = DataResult.Data(
                StrikeRefresh(listOf(overlappingStrike())),
                DataFreshness.Stale(clock.now(), 2.hours, null),
                DomainFailure.TEMPORARY,
            )
            runCurrent()
            val card = component.state.value.active.single()
            assertEquals(1, card.strikeWarnings.size)
            assertTrue(card.strikesStale)
            assertEquals(1, monitors.monitors.value.size)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun updatedSnapshotRecomputesWarnings() = runTest {
        val lifecycle = LifecycleRegistry()
        val monitors = FakeMonitoringRepository(clock)
        val monitor = monitorWithSnapshot(monitors)
        val strikes = freshStrikes()
        val component = component(lifecycle, monitors, strikes)
        lifecycle.resume()
        try {
            runCurrent()
            assertEquals(1, component.state.value.active.single().strikeWarnings.size)
            // The train is re-timed outside the strike: warnings clear from
            // the locally accepted snapshot, with no provider access.
            val current = monitors.monitors.value.single()
            monitors.persistEvaluation(
                current.id,
                MonitoredTrainSnapshot(
                    snapshotTrain(departure + 24.hours, arrival + 24.hours),
                    DataFreshness.Fresh(clock.now(), null),
                    clock.now(),
                ),
                emptyList(),
                current.snapshotVersion,
                2L,
            )
            runCurrent()
            assertTrue(component.state.value.active.single().strikeWarnings.isEmpty())
            assertEquals(monitor.id, monitors.monitors.value.single().id)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun endedMonitorKeepsFinalSnapshotWarningWithoutLifecycleChanges() = runTest {
        val lifecycle = LifecycleRegistry()
        val monitors = FakeMonitoringRepository(clock)
        val monitor = monitorWithSnapshot(monitors)
        val strikes = freshStrikes()
        val component = component(lifecycle, monitors, strikes)
        lifecycle.resume()
        try {
            runCurrent()
            val endedAt = clock.now()
            val arrived = snapshotTrain().copy(summary = snapshotTrain().summary.copy(status = TrainStatus.ARRIVED))
            monitors.completeTerminally(
                monitor.id,
                MonitoredTrainSnapshot(arrived, DataFreshness.Fresh(clock.now(), null), clock.now()),
                emptyList(),
                endedAt,
                2L,
            )
            runCurrent()
            val ended = component.state.value.ended.single()
            assertEquals(StrikeImpact.LIKELY, ended.strikeWarnings.single().assessment.impact)

            // Strike changes never reactivate, move endedAt, or create events.
            val eventsBefore = monitors.persistedEvents.size
            strikes.state.value = DataResult.Data(
                listOf(overlappingStrike().copy(status = StrikeStatus.REVOKED)),
                DataFreshness.Fresh(clock.now(), null),
            )
            runCurrent()
            assertTrue(component.state.value.ended.single().strikeWarnings.isEmpty())
            assertTrue(component.state.value.active.isEmpty())
            assertEquals(endedAt, monitors.monitors.value.single().endedAt)
            assertEquals(eventsBefore, monitors.persistedEvents.size)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test
    fun unionBoundsCoverSnapshotsWithExplicitEdgeSemantics() {
        assertNull(unionStrikeBounds(emptyList()))
        val monitor = testMonitorWithTimes(null, null)
        assertNull(unionStrikeBounds(listOf(monitor)))
        val point = testMonitorWithTimes(departure, departure)
        val widened = requireNotNull(unionStrikeBounds(listOf(point)))
        assertEquals(departure - 1.minutes, widened.first)
        assertEquals(departure + 1.minutes, widened.second)
    }

    private fun testMonitorWithTimes(dep: Instant?, arr: Instant?): TrainMonitor {
        val train = snapshotTrain().copy(
            summary = snapshotTrain().summary.copy(scheduledDeparture = dep, scheduledArrival = arr),
        )
        return TrainMonitor(
            MonitorId("m"),
            testRunId,
            true,
            MonitorThresholds(),
            clock.now(),
            null,
            lastSnapshot = MonitoredTrainSnapshot(train, DataFreshness.Unknown, clock.now()),
        )
    }
}
