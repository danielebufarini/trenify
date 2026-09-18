package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.journeyDestination
import it.danielebufarini.trenify.core.testing.testStation
import it.danielebufarini.trenify.feature.journey.JourneyTabComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NativeHomeSearchTest {
    @Test fun journeySwapFacadeForwardsAndShellHidesSearchForm() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val root = DefaultRootComponent(
            DefaultComponentContext(lifecycle),
            AppComponentFactory(
                repositories, repositories, repositories, repositories,
                MutableStateFlow(false), StandardTestDispatcher(testScheduler),
            ),
        )
        val owner = NativeProjectionOwner()
        val session = NativeApplicationSession(
            root, lifecycle, owner, {}, root::onNotificationDestination, {}, {},
        )
        try {
            val main = assertNotNull(session.state.value.main)
            runCurrent()
            val journey = assertNotNull(main.state.value.journey)
            val input = assertNotNull(journey.state.value.journeySearch)
            val beforeOrigin = input.state.value.origin
            input.swap()
            runCurrent()
            // Swap is validated through the facade state; the shared Search
            // component below Results/History stays authoritative.
            val sharedJourney = assertIs<MainComponent.Child.Journey>(
                assertIs<RootComponent.Child.Main>(root.stack.value.active.instance)
                    .component.pages.value.items[MainTab.Journey.ordinal].instance,
            ).component
            val sharedSearch = assertIs<JourneyTabComponent.Child.Search>(
                sharedJourney.stack.value.active.instance,
            ).component
            assertEquals(sharedSearch.state.value.origin, input.state.value.origin)
            assertEquals(sharedSearch.state.value.destination, input.state.value.destination)
            assertTrue(beforeOrigin != input.state.value.origin || beforeOrigin == null)

            // T8.5 visual projection: Search form lives in the native Home base,
            // never as a separate shell path entry.
            val shell = createShellPresentation(root)
            try {
                assertEquals(NativeDestination.Home, shell.state.value.base.destination)
                assertTrue(shell.state.value.path.isEmpty())
                assertNotNull(shell.homeComponent())
                assertNotNull(shell.journeySearchComponent())
            } finally {
                shell.close()
            }
        } finally {
            session.close()
            lifecycle.destroy()
        }
    }

    @Test fun typedDateTimeModeAndHistoryEntryPreserveJourneySemantics() = runTest {
        val lifecycle = LifecycleRegistry()
        val repositories = FakeRealtimeRepositories()
        val root = DefaultRootComponent(
            DefaultComponentContext(lifecycle),
            AppComponentFactory(
                repositories, repositories, repositories, repositories,
                MutableStateFlow(false), StandardTestDispatcher(testScheduler),
            ),
        )
        val owner = NativeProjectionOwner()
        val session = NativeApplicationSession(
            root, lifecycle, owner, {}, root::onNotificationDestination, {}, {},
        )
        try {
            val main = assertNotNull(session.state.value.main)
            runCurrent()
            val input = assertNotNull(assertNotNull(main.state.value.journey).state.value.journeySearch)
            input.setDate(LocalDate(2026, 9, 15))
            input.setTime(18, 30)
            runCurrent()
            assertEquals(LocalDate(2026, 9, 15), input.state.value.date)
            assertEquals(18, input.state.value.timeHour)
            assertEquals(30, input.state.value.timeMinute)

            // History repeat still routes through the shared Journey flow with
            // the full recorded request; the shell shows History above Home.
            main.openHistory()
            runCurrent()
            val shell = createShellPresentation(root)
            try {
                assertEquals(listOf(NativeDestination.History), shell.state.value.path.map { it.destination })
                shell.back()
                runCurrent()
                assertEquals(NativeDestination.Home, shell.state.value.active.destination)
                val journey = assertIs<MainComponent.Child.Journey>(
                    assertIs<RootComponent.Child.Main>(root.stack.value.active.instance)
                        .component.pages.value.items[MainTab.Journey.ordinal].instance,
                ).component
                assertIs<JourneyTabComponent.Child.Search>(journey.stack.value.active.instance)
            } finally {
                shell.close()
            }

            // Favorites keep their identities through the Home facade.
            main.select(NativePrimaryArea.Search)
            runCurrent()
            val home = assertNotNull(main.state.value.home)
            repositories.setFavorite(testStation, true)
            runCurrent()
            assertTrue(home.state.value.favoriteStations.contains(testStation))
            assertNull(input.state.value.failure)
            assertEquals(testStation.name, "Roma Termini")
            assertEquals(journeyDestination.name, "Milano Centrale")
        } finally {
            session.close()
            lifecycle.destroy()
        }
    }
}
