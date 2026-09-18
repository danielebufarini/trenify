package it.danielebufarini.trenify.feature.monitoring

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.pause
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.LoadStrikes
import it.danielebufarini.trenify.core.domain.MonitorThresholds
import it.danielebufarini.trenify.core.domain.MonitoredTrainSnapshot
import it.danielebufarini.trenify.core.domain.ObserveActiveMonitors
import it.danielebufarini.trenify.core.domain.ObserveEndedMonitors
import it.danielebufarini.trenify.core.domain.StrikeImpact
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.testing.FakeMonitoringRepository
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testRun
import it.danielebufarini.trenify.core.testing.testRunId
import it.danielebufarini.trenify.core.testing.testStrike
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Union-deadline aging for monitoring strike coverage (T7.14 corrective):
 * card warnings age through elapsed local time with zero provider calls
 * while warning identity/impact is otherwise preserved, and
 * pause/resume/destroy behave like the card watcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitorStrikeFreshnessTest {
    private fun scheduledRun(clock: MutableClock) = testRun.copy(
        summary = testRun.summary.copy(
            scheduledDeparture = clock.now() + 1.hours,
            scheduledArrival = clock.now() + 2.hours,
            operator = Operator("Trenitalia"),
        ),
    )

    private fun overlappingStrike(clock: MutableClock) = testStrike.copy(
        start = clock.now() + 30.minutes,
        end = clock.now() + 90.minutes,
        operators = listOf(Operator("Trenitalia")),
    )

    private fun kotlinx.coroutines.test.TestScope.component(
        lifecycle: LifecycleRegistry,
        repository: FakeMonitoringRepository,
        strikes: FakeStrikeRepository,
        clock: MutableClock,
    ) = DefaultMonitoringTabComponent(
        DefaultComponentContext(lifecycle),
        ObserveActiveMonitors(repository),
        onTrain = {},
        dispatcher = StandardTestDispatcher(testScheduler),
        observeEndedMonitors = ObserveEndedMonitors(repository),
        strikes = LoadStrikes(strikes),
        policy = it.danielebufarini.trenify.core.domain.RealtimePolicy(),
        clock = clock,
    )

    private suspend fun stageActiveWithSnapshot(
        repository: FakeMonitoringRepository,
        clock: MutableClock,
    ) {
        val created = repository.createMonitor(testRunId, MonitorThresholds(delayMinutes = 15), clock.now() + 1.hours)
        repository.monitors.value = listOf(
            created.copy(
                lastSnapshot = MonitoredTrainSnapshot(
                    scheduledRun(clock),
                    DataFreshness.Fresh(clock.now(), null),
                    clock.now(),
                ),
            ),
        )
    }

    @Test fun cardStrikeCoverageAgesAcrossCacheTtlWithoutEmission() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val repository = FakeMonitoringRepository(clock)
        stageActiveWithSnapshot(repository, clock)
        val strikes = FakeStrikeRepository()
        strikes.state.value = DataResult.Data(
            listOf(overlappingStrike(clock)),
            DataFreshness.Fresh(clock.now(), null),
        )
        val component = component(lifecycle, repository, strikes, clock)
        lifecycle.resume()
        try {
            runCurrent()
            val before = component.state.value.active.single().strikeWarnings.single()
            assertEquals(overlappingStrike(clock).id, before.strike.id)
            assertEquals(StrikeImpact.LIKELY, before.assessment.impact)
            assertFalse(component.state.value.active.single().strikesStale)
            val strikeRefreshes = strikes.refreshes
            // The strike cache TTL (45 min) passes with no strike emission.
            clock.instant += 46.minutes
            advanceTimeBy(46 * 60_000L)
            runCurrent()
            val card = component.state.value.active.single()
            assertTrue(card.strikesStale)
            val after = card.strikeWarnings.single()
            // Warning identity/interval/status is not otherwise mutated.
            assertEquals(before.strike.id, after.strike.id)
            assertEquals(before.strike.start, after.strike.start)
            assertEquals(before.strike.end, after.strike.end)
            assertEquals(before.assessment.impact, after.assessment.impact)
            assertEquals(StrikeImpact.LIKELY, after.assessment.impact)
            // The aging transition caused zero strike-provider calls.
            assertEquals(strikeRefreshes, strikes.refreshes)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun cardStrikeFreshnessPausesResumesAndCancelsOnDestroy() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val repository = FakeMonitoringRepository(clock)
        stageActiveWithSnapshot(repository, clock)
        val strikes = FakeStrikeRepository()
        strikes.state.value = DataResult.Data(
            listOf(overlappingStrike(clock)),
            DataFreshness.Fresh(clock.now(), null),
        )
        val component = component(lifecycle, repository, strikes, clock)
        lifecycle.resume()
        try {
            runCurrent()
            assertFalse(component.state.value.active.single().strikesStale)
            lifecycle.pause()
            runCurrent()
            clock.instant += 60.minutes
            advanceTimeBy(60 * 60_000L)
            runCurrent()
            assertFalse(component.state.value.active.single().strikesStale)
            lifecycle.resume()
            runCurrent()
            assertTrue(component.state.value.active.single().strikesStale)
        } finally {
            lifecycle.destroy()
        }
        // After destroy the union deadline schedules nothing further.
        clock.instant += 60.minutes
        advanceTimeBy(60 * 60_000L)
        runCurrent()
    }
}
