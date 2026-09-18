package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.domain.CorrelateJourneyLeg
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.LoadStrikes
import it.danielebufarini.trenify.core.domain.MonitoredTrainSnapshot
import it.danielebufarini.trenify.core.domain.ObserveActiveMonitors
import it.danielebufarini.trenify.core.domain.ObserveEndedMonitors
import it.danielebufarini.trenify.core.domain.SearchJourneys
import it.danielebufarini.trenify.core.domain.StopTrainMonitoring
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Journey
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.TrainNumber
import it.danielebufarini.trenify.core.model.TrainRun
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.model.TrainStop
import it.danielebufarini.trenify.core.provider.api.ProviderStrike
import it.danielebufarini.trenify.core.testing.FakeJourneyRepository
import it.danielebufarini.trenify.core.testing.FakeMonitoringRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.FakeStrikeProvider
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.journeyDestination
import it.danielebufarini.trenify.core.testing.journeyRequest
import it.danielebufarini.trenify.core.testing.journeyResult
import it.danielebufarini.trenify.core.testing.testJourney
import it.danielebufarini.trenify.core.testing.testRun
import it.danielebufarini.trenify.core.testing.testRunId
import it.danielebufarini.trenify.core.testing.testStation
import it.danielebufarini.trenify.core.testing.testSummary
import it.danielebufarini.trenify.data.SqlDelightStrikeRepository
import it.danielebufarini.trenify.database.TrenifyDatabase
import it.danielebufarini.trenify.feature.journey.JourneyDetailComponent
import it.danielebufarini.trenify.feature.journey.JourneyResultsComponent
import it.danielebufarini.trenify.feature.monitoring.DefaultMonitoringTabComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * T7.12-D no-N+1 proof at the provider boundary: journey results, monitor
 * cards and detail enrichment share one strike fetch per window and issue no
 * per-result/card/leg realtime requests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StrikeWarningNoNPlusOneTest {
    private val clock = MutableClock()

    private fun overlappingProviderStrike(): ProviderStrike {
        val at = journeyRequest.at
        return ProviderStrike(
            externalId = "9001",
            start = at - 2.hours,
            end = at + 30.hours,
            sector = "Ferroviario",
            workforce = "Personale Trenitalia",
            operators = listOf(Operator("Trenitalia")),
            mode = "24 ore",
            sourceUrl = "https://scioperi.mit.gov.it/9001",
        )
    }

    private suspend fun kotlinx.coroutines.test.TestScope.realStrikes(
        block: suspend kotlinx.coroutines.test.TestScope.(SqlDelightStrikeRepository, FakeStrikeProvider) -> Unit,
    ) {
        val driver = createOrderingTestDriver()
        val database = TrenifyDatabase(driver)
        val provider = FakeStrikeProvider(clock).apply { values = listOf(overlappingProviderStrike()) }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = SqlDelightStrikeRepository(database, provider, backgroundScope, clock = clock, dispatcher = dispatcher)
        try {
            block(repository, provider)
        } finally {
            driver.close()
        }
    }

    @Test
    fun journeyResultsShareOneProviderFetchAcrossManyJourneys() = runTest {
        realStrikes { repository, provider ->
            val lifecycle = LifecycleRegistry()
            val journeys = FakeJourneyRepository()
            val extra = listOf(1, 2, 4).map { shift ->
                val legs = testJourney.legs.map { leg ->
                    leg.copy(departure = leg.departure + shift.hours, arrival = leg.arrival + shift.hours)
                }
                Journey(legs, testJourney.sources)
            }
            journeys.state.value = DataResult.Data(
                journeyResult.copy(journeys = listOf(testJourney) + extra),
                DataFreshness.Fresh(clock.now(), null),
            )
            val component = JourneyResultsComponent(
                DefaultComponentContext(lifecycle),
                journeyRequest,
                SearchJourneys(journeys),
                {},
                StandardTestDispatcher(testScheduler),
                LoadStrikes(repository),
            )
            lifecycle.resume()
            try {
                advanceUntilIdle()
                assertEquals(4, component.state.value.journeys.size)
                // One provider fetch serves all four results — no per-card N+1.
                assertEquals(1, provider.calls)
                assertEquals(4, component.state.value.strikeWarnings.size)
            } finally {
                lifecycle.destroy()
            }
        }
    }

    @Test
    fun monitorCardsShareOneProviderFetch() = runTest {
        realStrikes { repository, provider ->
            val lifecycle = LifecycleRegistry()
            val monitors = FakeMonitoringRepository(clock)
            val at = journeyRequest.at
            listOf(testRunId, testRunId.copy(number = TrainNumber("456")), testRunId.copy(number = TrainNumber("789"))).forEachIndexed { index, id ->
                val monitor = monitors.createMonitor(id, expiresAt = clock.now() + 24.hours)
                val train = testRun.copy(
                    summary = testRun.summary.copy(
                        scheduledDeparture = at + index.hours,
                        scheduledArrival = at + (index + 2).hours,
                        status = TrainStatus.RUNNING,
                        operator = Operator("Trenitalia"),
                    ),
                )
                monitors.persistEvaluation(
                    monitor.id,
                    MonitoredTrainSnapshot(train, DataFreshness.Fresh(clock.now(), null), clock.now()),
                    emptyList(),
                    monitor.snapshotVersion,
                    (index + 1).toLong(),
                )
            }
            val component = DefaultMonitoringTabComponent(
                DefaultComponentContext(lifecycle),
                ObserveActiveMonitors(monitors),
                StopTrainMonitoring(monitors),
                {},
                StandardTestDispatcher(testScheduler),
                observeEndedMonitors = ObserveEndedMonitors(monitors),
                strikes = LoadStrikes(repository),
            )
            lifecycle.resume()
            try {
                advanceUntilIdle()
                assertEquals(3, component.state.value.active.size)
                // One provider fetch serves all three cards — no per-card N+1.
                assertEquals(1, provider.calls)
                assertTrue(component.state.value.active.all { it.strikeWarnings.isNotEmpty() })
            } finally {
                lifecycle.destroy()
            }
        }
    }

    @Test
    fun secondResultsComponentReusesSharedFreshness() = runTest {
        realStrikes { repository, provider ->
            val lifecycle = LifecycleRegistry()
            val journeys = FakeJourneyRepository()
            fun results() = JourneyResultsComponent(
                DefaultComponentContext(lifecycle),
                journeyRequest,
                SearchJourneys(journeys),
                {},
                StandardTestDispatcher(testScheduler),
                LoadStrikes(repository),
            )
            val first = results()
            lifecycle.resume()
            try {
                advanceUntilIdle()
                assertEquals(1, provider.calls)
                // A second component over the same covered window reuses the
                // shared freshness instead of fetching again.
                val second = results()
                advanceUntilIdle()
                assertEquals(1, provider.calls)
                assertEquals(
                    first.state.value.strikeWarnings.keys,
                    second.state.value.strikeWarnings.keys,
                )
            } finally {
                lifecycle.destroy()
            }
        }
    }

    @Test
    fun detailUsesOneStrikeFetchAndObservationOnlyEnrichment() = runTest {
        realStrikes { repository, provider ->
            val lifecycle = LifecycleRegistry()
            val trains = FakeRealtimeRepositories()
            val leg = testJourney.legs.single()
            trains.trainState.value = DataResult.Data(
                TrainRun(
                    testSummary.copy(operator = leg.operator, status = TrainStatus.RUNNING, delayMinutes = 10),
                    listOf(
                        TrainStop(testStation, scheduledDeparture = leg.departure),
                        TrainStop(journeyDestination, scheduledArrival = leg.arrival),
                    ),
                ),
                DataFreshness.Fresh(clock.now(), null),
            )
            val component = JourneyDetailComponent(
                DefaultComponentContext(lifecycle),
                testJourney,
                CorrelateJourneyLeg(trains),
                {},
                StandardTestDispatcher(testScheduler),
                strikes = LoadStrikes(repository),
                trains = trains,
            )
            lifecycle.resume()
            try {
                advanceUntilIdle()
                // Exactly one strike fetch for the whole detail; enrichment
                // observes the correlated cache and issues no realtime
                // refresh beyond correlation's own single cached read.
                assertEquals(1, provider.calls)
                assertEquals(1, trains.trainRefreshes)
                assertEquals(1, component.state.value.strikeWarnings[0]?.size)
                assertTrue((component.state.value.correlatedRuns[0] as DataResult.Data).value.summary.delayMinutes == 10)
            } finally {
                lifecycle.destroy()
            }
        }
    }
}
