package it.danielebufarini.trenify.feature.station

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

// T7.10 station boards: rows expose the source-backed category and the
// station-event time while terminal times stay absent without per-row
// detail lookups.
@OptIn(ExperimentalCoroutinesApi::class)
class StationBoardInfoTest {
    private val event = Instant.parse("2026-09-05T05:35:00Z")

    private fun boardRow(number: String, category: TrainCategory?) = testSummary.copy(
        id = testRunId.copy(number = TrainNumber(number)),
        scheduledTime = event,
        category = category,
    )

    @Test fun boardRowsExposeCategoryAndEventTimeWithoutTerminalsOrDetailLookups() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        repositories.boardState.value = DataResult.Data(
            StationBoard(testStation, BoardKind.DEPARTURES,
                listOf(boardRow("8412", TrainCategory.REG), boardRow("9624", TrainCategory.FR))),
            DataFreshness.Unknown,
        )
        val component = StationBoardComponent(DefaultComponentContext(lifecycle), testStation.id,
            ObserveStation(repositories), LoadStationBoard(repositories), repositories, MutableStateFlow(false), {},
            StandardTestDispatcher(testScheduler))
        lifecycle.resume()
        runCurrent()
        try {
            val rows = component.state.value.data?.trains
            assertNotNull(rows)
            assertEquals(2, rows.size)
            assertEquals(TrainCategory.REG, rows[0].category)
            assertEquals(TrainCategory.FR, rows[1].category)
            assertTrue(rows.all { it.scheduledTime == event })
            // Board rows never carry terminal times and never trigger detail loads.
            assertTrue(rows.all { it.scheduledDeparture == null && it.scheduledArrival == null })
            assertEquals(0, repositories.trainRefreshes)
        } finally { lifecycle.destroy() }
    }

    @Test fun arrivalsBoardKeepsArrivalEventTime() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        repositories.boardState.value = DataResult.Data(
            StationBoard(testStation, BoardKind.ARRIVALS, listOf(boardRow("9624", null))),
            DataFreshness.Unknown,
        )
        val component = StationBoardComponent(DefaultComponentContext(lifecycle), testStation.id,
            ObserveStation(repositories), LoadStationBoard(repositories), repositories, MutableStateFlow(false), {},
            StandardTestDispatcher(testScheduler))
        lifecycle.resume()
        runCurrent()
        try {
            val row = component.state.value.data?.trains?.single()
            assertNotNull(row)
            assertNull(row.category)
            assertEquals(event, row.scheduledTime)
            assertNull(row.scheduledDeparture)
            assertNull(row.scheduledArrival)
            assertEquals(0, repositories.trainRefreshes)
        } finally { lifecycle.destroy() }
    }
}
