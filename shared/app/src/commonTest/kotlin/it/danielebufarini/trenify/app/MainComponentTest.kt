package it.danielebufarini.trenify.app

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.journeyDestination
import it.danielebufarini.trenify.core.testing.testStation
import it.danielebufarini.trenify.core.model.JourneySearchIntent
import it.danielebufarini.trenify.feature.journey.JourneyTabComponent
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

class MainComponentTest {
    @Test
    fun selectsTopLevelTabsThroughDecomposePagesNavigation() = runTest {
        val repositories = FakeRealtimeRepositories()
        val component = DefaultMainComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            componentFactory = AppComponentFactory(repositories, repositories, repositories, repositories,
                MutableStateFlow(true), StandardTestDispatcher(testScheduler)),
        )

        assertEquals(MainTab.Journey.ordinal, component.pages.value.selectedIndex)

        component.select(MainTab.Monitoring)

        assertEquals(MainTab.Monitoring.ordinal, component.pages.value.selectedIndex)
    }

    @Test
    fun sharedJourneySearchIntentSelectsJourneyAndPrefillsEndpoints() = runTest {
        val repositories = FakeRealtimeRepositories()
        val component = DefaultMainComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            componentFactory = AppComponentFactory(
                repositories,
                repositories,
                repositories,
                repositories,
                MutableStateFlow(true),
                StandardTestDispatcher(testScheduler),
            ),
        )
        component.select(MainTab.Favorites)

        component.openJourneySearch(JourneySearchIntent(testStation, journeyDestination))

        assertEquals(MainTab.Journey.ordinal, component.pages.value.selectedIndex)
        val journey = assertIs<MainComponent.Child.Journey>(
            component.pages.value.items[MainTab.Journey.ordinal].instance,
        ).component
        val search = assertIs<JourneyTabComponent.Child.Search>(journey.stack.value.active.instance).component
        assertEquals(testStation, search.state.value.origin)
        assertEquals(journeyDestination, search.state.value.destination)

        component.openJourneySearch(JourneySearchIntent(testStation, journeyDestination))
        val relaunched = assertIs<JourneyTabComponent.Child.Search>(journey.stack.value.active.instance).component
        assertNotSame(search, relaunched)
    }
}
