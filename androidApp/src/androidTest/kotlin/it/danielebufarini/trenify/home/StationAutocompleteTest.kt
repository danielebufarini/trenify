package it.danielebufarini.trenify.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import it.danielebufarini.trenify.app.*
import it.danielebufarini.trenify.core.model.StationId
import it.danielebufarini.trenify.core.testing.*
import it.danielebufarini.trenify.data.SqlDelightJourneyRepository
import it.danielebufarini.trenify.database.TrenifyDatabase
import it.danielebufarini.trenify.feature.journey.JourneyTabComponent
import it.danielebufarini.trenify.navigation.TrenifyAndroidShell
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StationAutocompleteTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun recordedCatalogRanksRenderedSuggestionsAndSelectsCorrectJourneyStation() {
        val lifecycle = LifecycleRegistry()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val catalog = StationAutocompleteFixture()
        val driver = AndroidSqliteDriver(TrenifyDatabase.Schema, compose.activity, null)
        val repository = SqlDelightJourneyRepository(TrenifyDatabase(driver), catalog.provider, scope)
        val repositories = FakeRealtimeRepositories()
        lateinit var root: RootComponent
        compose.runOnUiThread {
            root = DefaultRootComponent(DefaultComponentContext(lifecycle), AppComponentFactory(
                repositories, repositories, repositories, repositories, MutableStateFlow(false), journeyRepository = repository,
            ))
        }
        compose.setContent { TrenifyAndroidShell(root) }
        fun waitFor(id: String) = compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("home-suggestion-$id").fetchSemanticsNodes().isNotEmpty()
        }
        try {
            compose.onNodeWithTag("home-origin").performTextInput("milano")
            waitFor("location:italo-journeys:MC_")
            compose.onNodeWithTag("home-suggestion-location:italo-journeys:MC_").assertIsDisplayed().performClick()
            compose.onNodeWithTag("home-destination").performTextInput("roma")
            waitFor("location:italo-journeys:RTB")
            compose.onNodeWithTag("home-suggestion-location:italo-journeys:RMT").assertIsDisplayed()
            assertTrue(compose.onNodeWithTag("home-suggestion-location:italo-journeys:RMT").getUnclippedBoundsInRoot().top <
                compose.onNodeWithTag("home-suggestion-location:italo-journeys:RTB").getUnclippedBoundsInRoot().top)
            compose.onNodeWithTag("home-suggestion-location:italo-journeys:RTB").assertIsDisplayed().performClick()
            compose.runOnIdle {
                val main = (root.stack.value.active.instance as RootComponent.Child.Main).component
                val journey = (main.pages.value.items[MainTab.Journey.ordinal].instance as MainComponent.Child.Journey).component
                val search = (journey.stack.value.active.instance as JourneyTabComponent.Child.Search).component
                assertEquals(StationId("location:italo-journeys:MC_"), search.state.value.origin?.id)
                assertEquals(StationId("location:italo-journeys:RTB"), search.state.value.destination?.id)
                assertEquals("Roma Tiburtina", search.state.value.destinationText)
            }
            compose.onNodeWithTag("home-search").performClick()
            compose.waitUntil(5_000) { catalog.arrivalLocationIds.isNotEmpty() }
            assertEquals(830008217L, catalog.arrivalLocationIds.single())
            compose.runOnIdle {
                val main = (root.stack.value.active.instance as RootComponent.Child.Main).component
                (main.pages.value.items[MainTab.Journey.ordinal].instance as MainComponent.Child.Journey).component.back()
            }
            compose.onNodeWithTag("home-destination").performTextReplacement("terni")
            waitFor("location:trenitalia-journeys:830007226")
            compose.onNodeWithTag("home-suggestion-location:trenitalia-journeys:830007226").assertIsDisplayed().performClick()
            compose.runOnIdle {
                val main = (root.stack.value.active.instance as RootComponent.Child.Main).component
                val journey = (main.pages.value.items[MainTab.Journey.ordinal].instance as MainComponent.Child.Journey).component
                assertEquals(StationId("location:trenitalia-journeys:830007226"), (journey.stack.value.active.instance as JourneyTabComponent.Child.Search).component.state.value.destination?.id)
            }
        } finally {
            compose.runOnUiThread { lifecycle.destroy() }
            scope.cancel(); catalog.close(); driver.close()
        }
    }
}
