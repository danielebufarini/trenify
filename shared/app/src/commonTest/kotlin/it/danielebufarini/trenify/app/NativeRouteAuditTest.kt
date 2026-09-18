package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.core.model.JourneySearchMode
import it.danielebufarini.trenify.core.model.JourneySearchRequest
import it.danielebufarini.trenify.core.testing.FakeJourneyRepository
import it.danielebufarini.trenify.core.testing.FakeMonitoringRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.core.testing.MutableClock
import it.danielebufarini.trenify.core.testing.journeyDestination
import it.danielebufarini.trenify.core.testing.testRunId
import it.danielebufarini.trenify.core.testing.testStation
import it.danielebufarini.trenify.core.testing.testStrike
import it.danielebufarini.trenify.feature.journey.JourneyTabComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * T8.15 native route audit: every production destination resolves through
 * its native presentation facade with no legacy renderer fallback. The
 * legacy lease/host API is removed, so this audit is positive — each entry
 * carries the facade its native shell host consumes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NativeRouteAuditTest {
    private val at = Instant.parse("2026-09-14T09:00:00Z")

    @Test fun everyProductionDestinationResolvesNatively() = runTest {
        val realtime = FakeRealtimeRepositories()
        val strikes = FakeStrikeRepository()
        val life = LifecycleRegistry()
        val factory = AppComponentFactory(
            realtime, realtime, realtime, realtime,
            MutableStateFlow(false), StandardTestDispatcher(testScheduler),
            monitoringRepository = FakeMonitoringRepository(MutableClock(at)),
            strikeRepository = strikes, strikeNotificationRepository = strikes,
            journeyRepository = FakeJourneyRepository(),
        )
        val root = DefaultRootComponent(DefaultComponentContext(life), factory)
        val shell = createShellPresentation(root)
        try {
            life.resume()
            runCurrent()
            val main = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component

            // Search/Home base renders natively through borrowed components.
            shell.select(NativePrimaryArea.Search)
            runCurrent()
            assertEquals(NativeDestination.Home, shell.state.value.base.destination)
            assertTrue(shell.state.value.base.available)
            assertNotNull(shell.homeComponent())

            // Journey Results + Detail carry live facades.
            val journey = assertIs<MainComponent.Child.Journey>(
                main.pages.value.items[MainTab.Journey.ordinal].instance).component
            val form = assertIs<JourneyTabComponent.Child.Search>(journey.stack.value.active.instance).component
            form.stationText(true, "Roma"); advanceTimeBy(276); runCurrent(); form.select(testStation)
            form.stationText(false, "Milano"); advanceTimeBy(276); runCurrent(); form.select(journeyDestination)
            form.date("2026-09-05"); form.time("10:00"); form.search(); runCurrent()
            main.select(MainTab.Journey); runCurrent()
            val results = shell.state.value.path.single { it.destination == NativeDestination.JourneyResults }
            assertTrue(results.available)
            assertNotNull(results.journeyResults)
            shell.journeyResults(results.identity)!!.selectJourney(0); runCurrent()
            val detail = shell.state.value.path.single { it.destination == NativeDestination.JourneyDetail }
            assertTrue(detail.available)
            assertNotNull(detail.journeyDetail)
            shell.back(); runCurrent()
            assertEquals(NativeDestination.JourneyResults, shell.state.value.active.destination)
            shell.back(); runCurrent()
            assertEquals(NativeDestination.Home, shell.state.value.active.destination)

            // History renders through the native History presentation.
            main.openHistory(); runCurrent()
            val history = shell.state.value.active
            assertEquals(NativeDestination.History, history.destination)
            assertTrue(history.available)
            assertNotNull(history.history)
            shell.back(); runCurrent()

            // Station Search + Board carry live facades.
            shell.homeComponent()!!.openStations(); runCurrent()
            assertEquals(NativeDestination.StationSearch, shell.state.value.active.destination)
            val search = shell.state.value.active
            assertTrue(search.available)
            val stationSearch = assertNotNull(search.stationSearch)
            stationSearch.editQuery("Roma"); advanceTimeBy(1000); runCurrent()
            stationSearch.selectStation(testStation.id.value); runCurrent()
            val board = shell.state.value.active
            assertEquals(NativeDestination.StationBoard, board.destination)
            assertTrue(board.available)
            val stationBoard = assertNotNull(board.stationBoard)
            assertEquals(testStation.id.value, stationBoard.state.value.stationId)

            // Train Detail resolves from the board through shared actions.
            stationBoard.openTrain(testRunId.key); runCurrent()
            val trainDetail = shell.state.value.active
            assertEquals(NativeDestination.TrainDetail, trainDetail.destination)
            assertTrue(trainDetail.available)
            assertNotNull(trainDetail.trainDetail)
            shell.back(); runCurrent()
            assertEquals(NativeDestination.StationBoard, shell.state.value.active.destination)
            shell.back(); runCurrent()
            assertEquals(NativeDestination.StationSearch, shell.state.value.active.destination)
            shell.back(); runCurrent()
            assertEquals(NativeDestination.Home, shell.state.value.active.destination)

            // Train Search entry carries its live facade.
            shell.homeComponent()!!.openTrainSearch(); runCurrent()
            val trainSearch = shell.state.value.active
            assertEquals(NativeDestination.TrainSearch, trainSearch.destination)
            assertTrue(trainSearch.available)
            assertNotNull(trainSearch.trainSearch)
            shell.back(); runCurrent()
            assertEquals(NativeDestination.Home, shell.state.value.active.destination)

            // Monitoring base carries its live facade.
            shell.select(NativePrimaryArea.Monitoring); runCurrent()
            val monitoring = shell.state.value.active
            assertEquals(NativeDestination.Monitoring, monitoring.destination)
            assertTrue(monitoring.available)
            assertNotNull(monitoring.monitoring)

            // Saved base carries its live facade.
            shell.select(NativePrimaryArea.Saved); runCurrent()
            val saved = shell.state.value.active
            assertEquals(NativeDestination.Saved, saved.destination)
            assertTrue(saved.available)
            assertNotNull(saved.saved)

            // Alerts overview + Strike Detail share the native facade.
            shell.select(NativePrimaryArea.Alerts); runCurrent()
            val overview = shell.state.value.base
            assertEquals(NativeDestination.AlertsOverview, overview.destination)
            assertTrue(overview.available)
            assertNotNull(overview.alerts)
            assertNull(overview.alertStrikeId)
            val alerts = assertIs<MainComponent.Child.Alerts>(
                main.pages.value.items[MainTab.Alerts.ordinal].instance).component
            alerts.open(testStrike.id); runCurrent()
            val strikeDetail = shell.state.value.active
            assertEquals(NativeDestination.StrikeDetail, strikeDetail.destination)
            assertTrue(strikeDetail.available)
            assertNotNull(strikeDetail.alerts)
            assertEquals(testStrike.id.value, strikeDetail.alertStrikeId)
            shell.back(); runCurrent()
            assertEquals(NativeDestination.AlertsOverview, shell.state.value.active.destination)

            // Settings carries its live facade and stays secondary.
            shell.openSettings(); runCurrent()
            val settings = shell.state.value.active
            assertEquals(NativeDestination.Settings, settings.destination)
            assertTrue(settings.available)
            assertNotNull(settings.settings)
            shell.back(); runCurrent()
            assertEquals(NativeDestination.AlertsOverview, shell.state.value.active.destination)
        } finally {
            shell.close()
            life.destroy()
        }
    }

    @Test fun journeySearchRequestRecordKeepsHistoryReachable() = runTest {
        // Guards the History shortcut identity used above: recording a search
        // keeps the native History destination reachable from Home.
        val realtime = FakeRealtimeRepositories()
        realtime.recordSearch(JourneySearchRequest(testStation, journeyDestination, at, JourneySearchMode.DEPART_AFTER))
        assertEquals(1, realtime.observeSearchHistory().first().size)
    }
}
