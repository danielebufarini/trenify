package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.domain.LoadStrikes
import it.danielebufarini.trenify.core.domain.MonitoredTrainSnapshot
import it.danielebufarini.trenify.core.domain.ObserveActiveMonitors
import it.danielebufarini.trenify.core.domain.ObserveEndedMonitors
import it.danielebufarini.trenify.core.domain.StopTrainMonitoring
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.testing.FakeMonitoringRepository
import it.danielebufarini.trenify.core.testing.FakeStrikeProvider
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.testJourney
import it.danielebufarini.trenify.core.testing.testProviderStrike
import it.danielebufarini.trenify.core.testing.testRun
import it.danielebufarini.trenify.core.testing.testRunId
import it.danielebufarini.trenify.data.SqlDelightStrikeRepository
import it.danielebufarini.trenify.database.TrenifyDatabase
import it.danielebufarini.trenify.feature.journey.JourneyDetailComponent
import it.danielebufarini.trenify.feature.monitoring.DefaultMonitoringTabComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * T7.12 corrective pass, Blocker 4, production wiring: Journey Detail and
 * Monitoring cards react to local strike expiry while the screen stays
 * open — zero provider calls, no monitor lifecycle effects.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StrikeLocalExpiryWiringTest {
    private val strikeStart = Instant.parse("2026-09-05T07:00:00Z")
    private val strikeEnd = Instant.parse("2026-09-05T09:00:00Z")

    private fun expiringStrike(externalId: String) = testProviderStrike.copy(
        externalId = externalId,
        start = strikeStart,
        end = strikeEnd,
    )

    private suspend fun kotlinx.coroutines.test.TestScope.realStrikes(
        block: suspend kotlinx.coroutines.test.TestScope.(MutableClock, FakeStrikeProvider, SqlDelightStrikeRepository) -> Unit,
    ) {
        val driver = createOrderingTestDriver()
        val database = TrenifyDatabase(driver)
        val clock = MutableClock()
        val provider = FakeStrikeProvider(clock)
        val repository = SqlDelightStrikeRepository(
            database,
            provider,
            backgroundScope,
            clock = clock,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            block(clock, provider, repository)
        } finally {
            driver.close()
        }
    }

    @Test
    fun journeyDetailWarningDisappearsAtLocalExpiry() = runTest {
        realStrikes { clock, provider, repository ->
            val leg = testJourney.legs.single()
            provider.values = listOf(expiringStrike("exp-j"))
            val lifecycle = LifecycleRegistry()
            val component = JourneyDetailComponent(
                DefaultComponentContext(lifecycle),
                testJourney,
                correlate = null,
                onTrain = {},
                dispatcher = StandardTestDispatcher(testScheduler),
                strikes = LoadStrikes(repository),
            )
            lifecycle.resume()
            try {
                runCurrent()
                // The journey (08:00..11:00) overlaps the strike (07:00..09:00).
                assertEquals(leg.departure, Instant.parse("2026-09-05T08:00:00Z"))
                assertEquals(1, component.state.value.strikeWarnings[0]?.size)

                clock.instant = Instant.parse("2026-09-05T10:00:00Z")
                advanceTimeBy(2.hours)
                runCurrent()
                assertTrue(component.state.value.strikeWarnings.isEmpty())
                assertEquals(1, provider.calls)
            } finally {
                lifecycle.destroy()
            }
        }
    }

    @Test
    fun monitorCardWarningExpiresLocallyWithoutLifecycleEffects() = runTest {
        realStrikes { clock, provider, repository ->
            provider.values = listOf(expiringStrike("exp-m"))
            val monitoring = FakeMonitoringRepository(clock)
            val monitor = monitoring.createMonitor(testRunId, expiresAt = clock.now() + 24.hours)
            val train = testRun.copy(
                summary = testRun.summary.copy(
                    scheduledDeparture = Instant.parse("2026-09-05T07:30:00Z"),
                    scheduledArrival = Instant.parse("2026-09-05T08:30:00Z"),
                    status = TrainStatus.RUNNING,
                    operator = Operator("Trenitalia"),
                ),
            )
            monitoring.persistEvaluation(
                monitor.id,
                MonitoredTrainSnapshot(train, DataFreshness.Fresh(clock.now(), null), clock.now()),
                emptyList(),
                monitor.snapshotVersion,
                1L,
            )
            val lifecycle = LifecycleRegistry()
            val component = DefaultMonitoringTabComponent(
                DefaultComponentContext(lifecycle),
                ObserveActiveMonitors(monitoring),
                StopTrainMonitoring(monitoring),
                {},
                StandardTestDispatcher(testScheduler),
                observeEndedMonitors = ObserveEndedMonitors(monitoring),
                strikes = LoadStrikes(repository),
            )
            lifecycle.resume()
            try {
                runCurrent()
                assertEquals(1, component.state.value.active.single().strikeWarnings.size)

                clock.instant = Instant.parse("2026-09-05T10:00:00Z")
                advanceTimeBy(2.hours)
                runCurrent()
                assertTrue(component.state.value.active.single().strikeWarnings.isEmpty())
                // Local expiry is not a monitoring observation: the monitor
                // stays active, endedAt stays null, no events are claimed.
                assertNull(monitoring.monitors.value.single().endedAt)
                assertTrue(monitoring.persistedEvents.isEmpty())
                assertEquals(1, provider.calls)
            } finally {
                lifecycle.destroy()
            }
        }
    }

    @Test
    fun endedMonitorIsNotReactivatedByLocalExpiry() = runTest {
        realStrikes { clock, provider, repository ->
            provider.values = listOf(expiringStrike("exp-e"))
            val monitoring = FakeMonitoringRepository(clock)
            val monitor = monitoring.createMonitor(testRunId, expiresAt = clock.now() + 24.hours)
            val arrived = testRun.copy(
                summary = testRun.summary.copy(
                    scheduledDeparture = Instant.parse("2026-09-05T07:30:00Z"),
                    scheduledArrival = Instant.parse("2026-09-05T08:30:00Z"),
                    status = TrainStatus.ARRIVED,
                    operator = Operator("Trenitalia"),
                ),
            )
            monitoring.completeTerminally(
                monitor.id,
                MonitoredTrainSnapshot(arrived, DataFreshness.Fresh(clock.now(), null), clock.now()),
                emptyList(),
                clock.now(),
                1L,
            )
            val endedAt = monitoring.monitors.value.single().endedAt
            val lifecycle = LifecycleRegistry()
            val component = DefaultMonitoringTabComponent(
                DefaultComponentContext(lifecycle),
                ObserveActiveMonitors(monitoring),
                StopTrainMonitoring(monitoring),
                {},
                StandardTestDispatcher(testScheduler),
                observeEndedMonitors = ObserveEndedMonitors(monitoring),
                strikes = LoadStrikes(repository),
            )
            lifecycle.resume()
            try {
                runCurrent()
                assertEquals(1, component.state.value.ended.single().strikeWarnings.size)

                clock.instant = Instant.parse("2026-09-05T10:00:00Z")
                advanceTimeBy(2.hours)
                runCurrent()
                assertTrue(component.state.value.ended.single().strikeWarnings.isEmpty())
                assertTrue(component.state.value.active.isEmpty())
                assertEquals(endedAt, monitoring.monitors.value.single().endedAt)
                assertTrue(monitoring.persistedEvents.isEmpty())
                assertEquals(1, provider.calls)
            } finally {
                lifecycle.destroy()
            }
        }
    }
}
