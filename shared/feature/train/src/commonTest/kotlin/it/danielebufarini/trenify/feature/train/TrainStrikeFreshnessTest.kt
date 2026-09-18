package it.danielebufarini.trenify.feature.train

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.pause
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.LoadStrikes
import it.danielebufarini.trenify.core.domain.ObserveTrainRun
import it.danielebufarini.trenify.core.domain.StrikeImpact
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testRun
import it.danielebufarini.trenify.core.testing.testRunId
import it.danielebufarini.trenify.core.testing.testStrike
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Union-deadline aging for train-detail strike coverage (T7.14
 * corrective): the warning freshness ages through elapsed local time with
 * zero provider calls while warning identity/impact is otherwise preserved,
 * and pause/resume recomputes the secondary value.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainStrikeFreshnessTest {
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

    private fun kotlinx.coroutines.test.TestScope.detail(
        lifecycle: LifecycleRegistry,
        trains: FakeRealtimeRepositories,
        strikes: FakeStrikeRepository,
        clock: MutableClock,
    ) = TrainDetailComponent(
        DefaultComponentContext(lifecycle),
        testRunId,
        ObserveTrainRun(trains),
        MutableStateFlow(false),
        StandardTestDispatcher(testScheduler),
        clock = clock,
        strikes = LoadStrikes(strikes),
    )

    @Test fun strikeCoverageAgesAcrossCacheTtlWithoutEmission() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val trains = FakeRealtimeRepositories()
        trains.trainState.value = DataResult.Data(scheduledRun(clock), DataFreshness.Fresh(clock.now(), null))
        val strikes = FakeStrikeRepository()
        strikes.state.value = DataResult.Data(
            listOf(overlappingStrike(clock)),
            DataFreshness.Fresh(clock.now(), null),
        )
        val component = detail(lifecycle, trains, strikes, clock)
        lifecycle.resume()
        try {
            runCurrent()
            val before = component.state.value.strikeWarnings.single()
            assertEquals(overlappingStrike(clock).id, before.strike.id)
            assertEquals(StrikeImpact.LIKELY, before.assessment.impact)
            assertFalse(component.state.value.strikesStale)
            val strikeRefreshes = strikes.refreshes
            // The strike cache TTL (45 min) passes with no strike emission.
            clock.instant += 46.minutes
            advanceTimeBy(46 * 60_000L)
            runCurrent()
            assertTrue(component.state.value.strikesStale)
            val after = component.state.value.strikeWarnings.single()
            // Warning identity/interval/status is not otherwise mutated.
            assertEquals(before.strike.id, after.strike.id)
            assertEquals(before.strike.start, after.strike.start)
            assertEquals(before.strike.end, after.strike.end)
            assertEquals(before.assessment.impact, after.assessment.impact)
            assertEquals(StrikeImpact.LIKELY, after.assessment.impact)
            // The aging transition caused zero strike-provider calls.
            assertEquals(strikeRefreshes, strikes.refreshes)
            assertEquals(0, trains.trainRefreshes)
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun strikeFreshnessPausesAndResumes() = runTest {
        val lifecycle = LifecycleRegistry()
        val clock = MutableClock()
        val trains = FakeRealtimeRepositories()
        trains.trainState.value = DataResult.Data(scheduledRun(clock), DataFreshness.Fresh(clock.now(), null))
        val strikes = FakeStrikeRepository()
        strikes.state.value = DataResult.Data(
            listOf(overlappingStrike(clock)),
            DataFreshness.Fresh(clock.now(), null),
        )
        val component = detail(lifecycle, trains, strikes, clock)
        lifecycle.resume()
        try {
            runCurrent()
            assertFalse(component.state.value.strikesStale)
            lifecycle.pause()
            runCurrent()
            clock.instant += 60.minutes
            advanceTimeBy(60 * 60_000L)
            runCurrent()
            assertFalse(component.state.value.strikesStale)
            lifecycle.resume()
            runCurrent()
            assertTrue(component.state.value.strikesStale)
        } finally {
            lifecycle.destroy()
        }
    }
}
