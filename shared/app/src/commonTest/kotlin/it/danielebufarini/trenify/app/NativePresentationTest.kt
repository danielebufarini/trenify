package it.danielebufarini.trenify.app

import com.arkivanov.decompose.Cancellation
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.Lifecycle
import it.danielebufarini.trenify.core.platform.ApplicationState
import it.danielebufarini.trenify.core.platform.NotificationDestination
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.testStation
import it.danielebufarini.trenify.feature.journey.JourneyTabComponent
import kotlinx.datetime.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NativePresentationTest {
    private class RecordingValue(initial: Int) : Value<Int>() {
        override var value = initial
        val observers = mutableListOf<(Int) -> Unit>()
        override fun subscribe(observer: (Int) -> Unit): Cancellation {
            observers += observer
            observer(value)
            return Cancellation { observers.remove(observer) }
        }
        fun publish(next: Int) { value = next; observers.toList().forEach { it(next) } }
    }

    @Test fun projectionHasInitialOrderedUpdatesAndOwnerCancellationTerminatesCollectors() = runTest {
        val value = RecordingValue(10)
        val owner = NativeProjectionOwner()
        val state = owner.project(value) { it }
        assertEquals(10, state.value)
        assertEquals(1, value.observers.size)
        val seen = mutableListOf<Int>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { state.collect { seen += it } }
        value.publish(11)
        value.publish(12)
        assertEquals(listOf(10, 11, 12), seen)
        owner.close()
        runCurrent()
        assertTrue(collector.isCancelled)
        assertEquals(0, value.observers.size)
        assertEquals(0, owner.probes.collectors.value)
        value.publish(13)
        assertEquals(12, state.value)
        owner.close()
        val late = launch { state.collect {} }
        runCurrent()
        assertTrue(late.isCancelled)
    }

    @Test fun cancellingOneObserverKeepsTheSharedProjectionAndOtherObserverAlive() = runTest {
        val value = RecordingValue(1)
        val owner = NativeProjectionOwner()
        val state = owner.project(value) { it }
        val first = launch(UnconfinedTestDispatcher(testScheduler)) { state.collect {} }
        val seen = mutableListOf<Int>()
        val second = launch(UnconfinedTestDispatcher(testScheduler)) { state.collect { seen += it } }
        first.cancel()
        runCurrent()
        assertEquals(1, owner.probes.collectors.value)
        assertEquals(1, value.observers.size)
        value.publish(2)
        assertEquals(listOf(1, 2), seen)
        assertTrue(second.isActive)
        owner.close()
        runCurrent()
    }

    @Test fun sameComponentActionsProjectionReplacementAndSessionLifetimeStayShared() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val root = DefaultRootComponent(DefaultComponentContext(lifecycle), AppComponentFactory(
            repositories, repositories, repositories, repositories, MutableStateFlow(false),
            StandardTestDispatcher(testScheduler)))
        val owner = NativeProjectionOwner()
        val transitions = mutableListOf<ApplicationState>()
        var detaches = 0
        var closes = 0
        val session = NativeApplicationSession(root, lifecycle, owner, transitions::add,
            root::onNotificationDestination, { detaches++ }, { closes++ })
        val main = assertNotNull(session.state.value.main)
        // The original persisted default remains Journey, rather than changing to four tabs.
        assertEquals(MainTab.Journey, main.state.value.selectedDestination)
        val journey = assertNotNull(main.state.value.journey)
        val input = assertNotNull(journey.state.value.journeySearch)
        input.setDate(LocalDate(2026, 9, 15))
        input.setTime(18, 30)
        val sharedJourney = assertIs<MainComponent.Child.Journey>(
            assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component.pages.value.items[MainTab.Journey.ordinal].instance,
        ).component
        val sharedInput = assertIs<JourneyTabComponent.Child.Search>(sharedJourney.stack.value.active.instance).component
        assertEquals(sharedInput.state.value.date, input.state.value.date)
        assertEquals(18, input.state.value.timeHour)
        assertEquals(30, input.state.value.timeMinute)
        assertNotNull(input.favoriteRoute)
        input.openHistory()
        assertNull(journey.state.value.journeySearch)
        input.setTime(19, 0) // The disposed facade cannot mutate the retained shared Search.
        assertEquals(18, sharedInput.state.value.timeHour)
        journey.back()
        val returning = assertNotNull(journey.state.value.journeySearch)
        assertNotSame(input, returning)
        assertEquals(18, returning.state.value.timeHour)
        session.foreground()
        session.foreground()
        session.background()
        session.background()
        session.foreground()
        assertEquals(listOf(ApplicationState.Foreground, ApplicationState.Background, ApplicationState.Foreground), transitions)
        main.select(NativePrimaryArea.Search)
        runCurrent()
        val home = assertNotNull(main.state.value.home)
        val sharedMain = assertIs<RootComponent.Child.Main>(root.stack.value.active.instance).component
        val sharedHome = assertIs<MainComponent.Child.Home>(sharedMain.pages.value.items[MainTab.Home.ordinal].instance).component
        repositories.setFavorite(testStation, true)
        runCurrent()
        assertEquals(sharedHome.state.value, home.state.value)
        assertEquals(listOf(testStation), home.state.value.favoriteStations)
        home.openTrainSearch()
        val searchIdentity = session.state.value.navigation.active.identity
        assertEquals(NativeDestination.TrainSearch, session.state.value.navigation.active.destination)
        session.back()
        home.openTrainSearch()
        assertNotEquals(searchIdentity, session.state.value.navigation.active.identity)
        session.back()
        main.select(NativePrimaryArea.Alerts)
        // Decompose destroys non-adjacent pages. Its old Home facade must become inert.
        runCurrent()
        assertNull(main.state.value.home)
        val retained = home.state.value
        repositories.setFavorite(testStation, false)
        runCurrent()
        assertEquals(retained, home.state.value)
        home.openTrainSearch()
        assertEquals(NativeDestination.Main, session.state.value.navigation.active.destination)
        session.deliverNotificationDestination(NotificationDestination.Strike("strike-a"))
        val alerts = assertNotNull(main.state.value.alerts)
        assertEquals(NativeDestination.StrikeDetail, alerts.state.value.active.destination)
        val identity = alerts.state.value.active.identity
        session.deliverNotificationDestination(NotificationDestination.Strike("strike-b"))
        session.deliverNotificationDestination(NotificationDestination.Strike("strike-a"))
        assertEquals(identity, alerts.state.value.active.identity)
        assertEquals(3, alerts.state.value.entries.size)
        alerts.back()
        assertEquals("strike-b", alerts.state.value.active.strikeId)
        session.close()
        session.close()
        session.foreground()
        assertEquals(1, detaches)
        assertEquals(1, closes)
        assertEquals(Lifecycle.State.DESTROYED, lifecycle.state)
        assertEquals(NativeSessionPhase.Closed, session.phase.value)
        assertEquals(0, owner.probes.subscriptions.value)
    }
}
