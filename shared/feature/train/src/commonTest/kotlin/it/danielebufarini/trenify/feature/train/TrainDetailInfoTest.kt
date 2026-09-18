package it.danielebufarini.trenify.feature.train

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.*
import it.danielebufarini.trenify.core.domain.*
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.core.testing.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Instant

// T7.10 component layer: the detail component carries the new provider-neutral
// fields through unchanged, preserving absence end to end. Presentation
// derivation (route progress) lives in the shared pure routeProgress helper.
@OptIn(ExperimentalCoroutinesApi::class)
class TrainDetailInfoTest {
    private val departure = Instant.parse("2026-09-05T04:30:00Z")
    private val arrival = Instant.parse("2026-09-05T07:40:00Z")
    private val observed = Instant.parse("2026-09-05T05:38:00Z")

    private fun fullRun() = TrainRun(
        testSummary.copy(
            category = TrainCategory.REG,
            scheduledDeparture = departure,
            scheduledArrival = arrival,
            status = TrainStatus.RUNNING,
            delayMinutes = 3,
        ),
        listOf(
            TrainStop(testStation, scheduledDeparture = departure, actualDeparture = observed,
                status = StopStatus.COMPLETED),
            TrainStop(Station(StationId("firenze"), "Firenze S.M.N."),
                scheduledArrival = arrival, status = StopStatus.SCHEDULED),
        ),
        OperationalPosition("BOLOGNA CENTRALE", observed),
    )

    @Test fun detailCarriesCategoryTerminalsPositionAndStopStates() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        repositories.trainState.value = DataResult.Data(fullRun(), DataFreshness.Unknown)
        val component = TrainDetailComponent(DefaultComponentContext(lifecycle), testRunId,
            ObserveTrainRun(repositories), MutableStateFlow(false), StandardTestDispatcher(testScheduler))
        lifecycle.resume()
        runCurrent()
        try {
            val data = component.state.value.data
            assertNotNull(data)
            assertEquals(TrainCategory.REG, data.summary.category)
            assertEquals(departure, data.summary.scheduledDeparture)
            assertEquals(arrival, data.summary.scheduledArrival)
            assertNotEquals(data.summary.scheduledDeparture, data.summary.scheduledArrival)
            assertEquals(OperationalPosition("BOLOGNA CENTRALE", observed), data.position)
            assertEquals(listOf(StopStatus.COMPLETED, StopStatus.SCHEDULED), data.stops.map { it.status })
            assertEquals(
                listOf(StopProgress.COMPLETED, StopProgress.NEXT),
                routeProgress(data.stops),
            )
        } finally { lifecycle.destroy() }
    }

    @Test fun unavailableFieldsStayUnavailableWithoutCopying() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        // Only the origin terminal is known: the destination must not copy it.
        repositories.trainState.value = DataResult.Data(
            TrainRun(testSummary.copy(scheduledDeparture = departure)), DataFreshness.Unknown)
        val component = TrainDetailComponent(DefaultComponentContext(lifecycle), testRunId,
            ObserveTrainRun(repositories), MutableStateFlow(false), StandardTestDispatcher(testScheduler))
        lifecycle.resume()
        runCurrent()
        try {
            val data = component.state.value.data
            assertNotNull(data)
            assertNull(data.summary.category)
            assertEquals(departure, data.summary.scheduledDeparture)
            assertNull(data.summary.scheduledArrival)
            assertNull(data.position)
        } finally { lifecycle.destroy() }
    }

    @Test fun cancellationStaysDistinctFromDelay() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        repositories.trainState.value = DataResult.Data(
            TrainRun(
                testSummary.copy(status = TrainStatus.CANCELLED, delayMinutes = null, category = TrainCategory.REG),
                listOf(TrainStop(testStation, status = StopStatus.CANCELLED)),
            ),
            DataFreshness.Unknown,
        )
        val component = TrainDetailComponent(DefaultComponentContext(lifecycle), testRunId,
            ObserveTrainRun(repositories), MutableStateFlow(false), StandardTestDispatcher(testScheduler))
        lifecycle.resume()
        runCurrent()
        try {
            val data = component.state.value.data
            assertNotNull(data)
            assertEquals(TrainStatus.CANCELLED, data.summary.status)
            assertNull(data.summary.delayMinutes)
            assertEquals(listOf(StopProgress.CANCELLED), routeProgress(data.stops))
        } finally { lifecycle.destroy() }
    }

    @Test fun rescheduledStatusPassesThroughUnchanged() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        repositories.trainState.value = DataResult.Data(
            TrainRun(testSummary.copy(status = TrainStatus.RESCHEDULED)), DataFreshness.Unknown)
        val component = TrainDetailComponent(DefaultComponentContext(lifecycle), testRunId,
            ObserveTrainRun(repositories), MutableStateFlow(false), StandardTestDispatcher(testScheduler))
        lifecycle.resume()
        runCurrent()
        try {
            assertEquals(TrainStatus.RESCHEDULED, component.state.value.data?.summary?.status)
        } finally { lifecycle.destroy() }
    }
}
