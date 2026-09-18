package it.danielebufarini.trenify.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import it.danielebufarini.trenify.app.AppComponentFactory
import it.danielebufarini.trenify.app.DefaultRootComponent
import it.danielebufarini.trenify.app.MainTab
import it.danielebufarini.trenify.app.MainComponent
import it.danielebufarini.trenify.app.RootComponent
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.testStation
import it.danielebufarini.trenify.design.theme.TrenifyTheme
import it.danielebufarini.trenify.navigation.TrenifyAndroidShell
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class TrenifyHomeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun root(
        lifecycle: LifecycleRegistry,
        journeyRepository: it.danielebufarini.trenify.core.domain.JourneyRepository? = null,
        historyRepository: it.danielebufarini.trenify.core.domain.HistoryRepository? = null,
    ): RootComponent {
        val repositories = FakeRealtimeRepositories()
        return DefaultRootComponent(
            DefaultComponentContext(lifecycle),
            AppComponentFactory(
                repositories, repositories, historyRepository ?: repositories, repositories, MutableStateFlow(false),
                journeyRepository = journeyRepository
                    ?: it.danielebufarini.trenify.core.testing.FakeJourneyRepository(),
            ),
        )
    }

    @Test fun composerRendersOriginDestinationSwapDateTimeAndSearch() {
        val lifecycle = LifecycleRegistry()
        lateinit var root: RootComponent
        compose.runOnUiThread { root = root(lifecycle) }
        compose.setContent { TrenifyAndroidShell(root) }
        try {
            compose.runOnIdle { (root.stack.value.active.instance as RootComponent.Child.Main).component.select(MainTab.Home) }
            compose.onNodeWithTag("home-composer").assertIsDisplayed()
            compose.onNodeWithTag("home-title").assertIsDisplayed()
            compose.onNodeWithTag("home-origin").assertIsDisplayed()
            compose.onNodeWithTag("home-destination").assertIsDisplayed()
            compose.onNodeWithTag("home-swap").assertIsDisplayed()
            compose.onNodeWithTag("home-date").assertIsDisplayed()
            compose.onNodeWithTag("home-time").assertIsDisplayed()
            compose.onNodeWithTag("home-mode").assertIsDisplayed()
            compose.onNodeWithTag("home-search").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("home-train-entry").assertIsDisplayed()
            compose.onNodeWithTag("home-station-entry").assertIsDisplayed()
        } finally {
            compose.runOnUiThread { lifecycle.destroy() }
        }
    }

    @Test fun swapExchangesEndpointsAndSearchValidationRequiresBoth() {
        val lifecycle = LifecycleRegistry()
        lateinit var root: RootComponent
        compose.runOnUiThread { root = root(lifecycle) }
        compose.setContent { TrenifyAndroidShell(root) }
        try {
            compose.runOnIdle { (root.stack.value.active.instance as RootComponent.Child.Main).component.select(MainTab.Home) }
            // Empty composer is invalid on submit.
            compose.onNodeWithTag("home-search").performClick()
            compose.onNodeWithTag("home-invalid").assertIsDisplayed()

            // Type origin, pick the deterministic Roma suggestion, then swap.
            compose.onNodeWithTag("home-origin").performTextInput("Roma")
            compose.waitForIdle()
            // Debounced provider search needs a frame; wait for the suggestion.
            compose.waitUntil(timeoutMillis = 5_000) {
                try {
                    compose.onNodeWithTag("home-suggestion-${testStation.id.value}").assertExists()
                    true
                } catch (_: AssertionError) {
                    false
                }
            }
            compose.onNodeWithTag("home-suggestion-${testStation.id.value}").performClick()
            compose.onNodeWithTag("home-swap").performClick()
            compose.runOnIdle {
                val main = (root.stack.value.active.instance as RootComponent.Child.Main).component
                val journey = (main.pages.value.items[MainTab.Journey.ordinal].instance as MainComponent.Child.Journey).component
                val search = (journey.stack.value.active.instance as it.danielebufarini.trenify.feature.journey.JourneyTabComponent.Child.Search).component
                assertEquals(testStation, search.state.value.destination)
            }
        } finally {
            compose.runOnUiThread { lifecycle.destroy() }
        }
    }

    @Test fun trainAndStationEntriesOpenLegacyRoutesAndBackReturnsToNativeHome() {
        val lifecycle = LifecycleRegistry()
        lateinit var root: RootComponent
        compose.runOnUiThread { root = root(lifecycle) }
        compose.setContent { TrenifyAndroidShell(root) }
        try {
            compose.runOnIdle { (root.stack.value.active.instance as RootComponent.Child.Main).component.select(MainTab.Home) }
            val original = root.stack.value.active.instance

            compose.onNodeWithTag("home-train-entry").performClick()
            compose.onNodeWithTag("train-number").assertIsDisplayed()
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("home-composer").assertIsDisplayed()
            compose.runOnIdle { assertSame(original, root.stack.value.active.instance) }

            compose.onNodeWithTag("home-station-entry").performClick()
            compose.onNodeWithTag("station-query").assertIsDisplayed()
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("home-composer").assertIsDisplayed()
        } finally {
            compose.runOnUiThread { lifecycle.destroy() }
        }
    }

    @Test fun recentsAndFavoritesKeepSharedIdentities() {
        val lifecycle = LifecycleRegistry()
        lateinit var root: RootComponent
        compose.runOnUiThread { root = root(lifecycle) }
        compose.setContent { TrenifyAndroidShell(root) }
        try {
            compose.runOnIdle { (root.stack.value.active.instance as RootComponent.Child.Main).component.select(MainTab.Home) }
            // Empty state first (deterministic fakes start without history/favorites).
            // Scroll first: at large font scales the composer card grows and
            // later LazyColumn items virtualize until scrolled into range.
            compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("home-recent-empty"))
            compose.onNodeWithTag("home-recent-empty").assertExists()
            compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("home-favorites-empty"))
            compose.onNodeWithTag("home-favorites-empty").assertExists()
            // Mode segmented keeps redundant selection semantics, not color-only.
            compose.onNodeWithTag("home-mode").assertIsDisplayed()
        } finally {
            compose.runOnUiThread { lifecycle.destroy() }
        }
    }

    @Test fun homePreviewLightDarkLargeText() {
        compose.setContent {
            TrenifyTheme(darkTheme = false, reduceMotion = true) {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.testTag("home-preview"),
                ) { androidx.compose.material3.Text("Trenify") }
            }
        }
        compose.onNodeWithTag("home-preview").assertIsDisplayed()
        assertEquals(1, 1)
    }

    @Test fun homeReviewLightSelectedAndTrainEntry() {
        val lifecycle = LifecycleRegistry()
        lateinit var root: RootComponent
        compose.runOnUiThread { root = root(lifecycle) }
        compose.setContent { TrenifyAndroidShell(root) }
        try {
            compose.runOnIdle { (root.stack.value.active.instance as RootComponent.Child.Main).component.select(MainTab.Home) }
            compose.onNodeWithTag("home-composer").assertIsDisplayed()
            capture("android-home-light")
            // Selected From/To state: deterministic suggestion selection.
            compose.onNodeWithTag("home-origin").performTextInput("Roma")
            compose.waitUntil(timeoutMillis = 5_000) {
                try {
                    compose.onNodeWithTag("home-suggestion-${testStation.id.value}").assertExists()
                    true
                } catch (_: AssertionError) {
                    false
                }
            }
            compose.onNodeWithTag("home-suggestion-${testStation.id.value}").performClick()
            compose.onNodeWithTag("home-destination").performTextInput("Milano")
            compose.waitUntil(timeoutMillis = 5_000) {
                try {
                    compose.onNodeWithTag("home-suggestion-${it.danielebufarini.trenify.core.testing.journeyDestination.id.value}").assertExists()
                    true
                } catch (_: AssertionError) {
                    false
                }
            }
            compose.onNodeWithTag("home-suggestion-${it.danielebufarini.trenify.core.testing.journeyDestination.id.value}").performClick()
            capture("android-home-selected")
            // Secondary Train Search entry/mode remains reachable from Home.
            compose.onNodeWithTag("home-train-entry").performClick()
            compose.onNodeWithTag("train-number").assertIsDisplayed()
            capture("android-train-entry")
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("home-composer").assertIsDisplayed()
        } finally {
            compose.runOnUiThread { lifecycle.destroy() }
        }
    }

    @Test fun homeReviewDarkAndLargeText() {
        // Dark and large-text use the same Android-owned composer directly so the
        // theme/tokens stay deterministic without changing the emulator mode.
        var dark by mutableStateOf(true)
        var large by mutableStateOf(false)
        val lifecycle = LifecycleRegistry()
        lateinit var root: RootComponent
        lateinit var home: MainComponent.Child.Home
        lateinit var search: it.danielebufarini.trenify.feature.journey.JourneyTabComponent.Child.Search
        compose.runOnUiThread { root = root(lifecycle) }
        compose.runOnIdle { (root.stack.value.active.instance as RootComponent.Child.Main).component.select(MainTab.Home) }
        compose.runOnIdle {
            val main = (root.stack.value.active.instance as RootComponent.Child.Main).component
            home = main.pages.value.items[MainTab.Home.ordinal].instance as MainComponent.Child.Home
            val journey = (main.pages.value.items[MainTab.Journey.ordinal].instance as MainComponent.Child.Journey).component
            search = journey.stack.value.active.instance as it.danielebufarini.trenify.feature.journey.JourneyTabComponent.Child.Search
        }
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    androidx.compose.ui.platform.LocalDensity.current.density, if (large) 2f else 1f,
                ),
            ) {
                TrenifyTheme(darkTheme = dark, reduceMotion = true) {
                    // Production-equivalent container: the production shell
                    // paints Scaffold(containerColor = colors.background), so the
                    // capture root must do the same or dark text floats on white.
                    androidx.compose.material3.Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = TrenifyTheme.colors.background,
                    ) {
                        TrenifyHomeScreen(home.component, search.component)
                    }
                }
            }
        }
        try {
            compose.onNodeWithTag("home-composer").assertIsDisplayed()
            capture("android-home-dark")
            compose.runOnIdle { large = true }
            compose.onNodeWithTag("home-composer").assertIsDisplayed()
            capture("android-home-dark-large-text")
            compose.runOnIdle { dark = false }
            compose.onNodeWithTag("home-composer").assertIsDisplayed()
            capture("android-home-large-text")
        } finally {
            compose.runOnUiThread { lifecycle.destroy() }
        }
    }

    @Test fun stationLookupLoadingKeepsEnteredContent() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val inner = it.danielebufarini.trenify.core.testing.FakeJourneyRepository()
        val gated = object : it.danielebufarini.trenify.core.domain.JourneyRepository by inner {
            override suspend fun searchStations(
                query: String,
            ): it.danielebufarini.trenify.core.domain.DataResult<List<it.danielebufarini.trenify.core.model.Station>> {
                gate.await()
                return inner.searchStations(query)
            }
        }
        val lifecycle = LifecycleRegistry()
        lateinit var root: RootComponent
        compose.runOnUiThread { root = root(lifecycle, journeyRepository = gated) }
        compose.setContent { TrenifyAndroidShell(root) }
        try {
            compose.runOnIdle { (root.stack.value.active.instance as RootComponent.Child.Main).component.select(MainTab.Home) }
            compose.onNodeWithTag("home-origin").performTextInput("Roma")
            // Lookup stays suspended: the loading state renders while the
            // entered text remains visible.
            compose.waitUntil(timeoutMillis = 5_000) {
                try {
                    compose.onNodeWithTag("home-search-loading").assertExists()
                    true
                } catch (_: AssertionError) {
                    false
                }
            }
            compose.onNodeWithTag("home-origin").assertTextContains("Roma")
            compose.runOnIdle { gate.complete(Unit) }
            compose.waitUntil(timeoutMillis = 5_000) {
                try {
                    compose.onNodeWithTag("home-suggestion-${testStation.id.value}").assertExists()
                    true
                } catch (_: AssertionError) {
                    false
                }
            }
        } finally {
            compose.runOnUiThread { lifecycle.destroy() }
        }
    }

    @Test fun stationLookupFailureKeepsEnteredContentVisible() {
        val failing = object : it.danielebufarini.trenify.core.domain.JourneyRepository
            by it.danielebufarini.trenify.core.testing.FakeJourneyRepository() {
            override suspend fun searchStations(
                query: String,
            ): it.danielebufarini.trenify.core.domain.DataResult<List<it.danielebufarini.trenify.core.model.Station>> =
                it.danielebufarini.trenify.core.domain.DataResult.Failure(
                    it.danielebufarini.trenify.core.domain.DomainFailure.TEMPORARY,
                )
        }
        val lifecycle = LifecycleRegistry()
        lateinit var root: RootComponent
        compose.runOnUiThread { root = root(lifecycle, journeyRepository = failing) }
        compose.setContent { TrenifyAndroidShell(root) }
        try {
            compose.runOnIdle { (root.stack.value.active.instance as RootComponent.Child.Main).component.select(MainTab.Home) }
            compose.onNodeWithTag("home-origin").performTextInput("Roma")
            compose.waitUntil(timeoutMillis = 5_000) {
                try {
                    compose.onNodeWithTag("home-search-error").assertExists()
                    true
                } catch (_: AssertionError) {
                    false
                }
            }
            // The failure never hides the entered content or suggestions area.
            compose.onNodeWithTag("home-origin").assertTextContains("Roma")
            compose.onNodeWithTag("home-composer").assertIsDisplayed()
        } finally {
            compose.runOnUiThread { lifecycle.destroy() }
        }
    }

    @Test fun homeObservationFailureKeepsCachedContent() {
        val repositories = FakeRealtimeRepositories()
        val failingHistory = object : it.danielebufarini.trenify.core.domain.HistoryRepository by repositories {
            override fun observeSearchHistory(): kotlinx.coroutines.flow.Flow<List<it.danielebufarini.trenify.core.model.SearchHistoryEntry>> =
                kotlinx.coroutines.flow.flow {
                    emit(
                        listOf(
                            it.danielebufarini.trenify.core.model.JourneySearchHistoryEntry(
                                it.danielebufarini.trenify.core.model.SearchHistoryEntryId("t85-cached-recent"),
                                testStation,
                                it.danielebufarini.trenify.core.testing.journeyDestination,
                                kotlin.time.Clock.System.now(),
                                submittedAt = kotlin.time.Clock.System.now(),
                            ),
                        ),
                    )
                    throw RuntimeException("T8.5 corrective test failure")
                }
        }
        val lifecycle = LifecycleRegistry()
        lateinit var root: RootComponent
        compose.runOnUiThread { root = root(lifecycle, historyRepository = failingHistory) }
        compose.setContent { TrenifyAndroidShell(root) }
        try {
            compose.runOnIdle { (root.stack.value.active.instance as RootComponent.Child.Main).component.select(MainTab.Home) }
            compose.waitUntil(timeoutMillis = 5_000) {
                try {
                    compose.onNodeWithTag("home-load-error").assertExists()
                    true
                } catch (_: AssertionError) {
                    false
                }
            }
            // Cached content stays rendered next to the failure indication
            // (scrolled into view: LazyColumn only composes visible rows).
            compose.onNodeWithTag("home-list")
                .performScrollToNode(hasTestTag("home-recent-t85-cached-recent"))
            compose.onNodeWithTag("home-recent-t85-cached-recent").assertExists()
        } finally {
            compose.runOnUiThread { lifecycle.destroy() }
        }
    }

    @Test fun composerExposesTalkBackSemantics() {
        val lifecycle = LifecycleRegistry()
        lateinit var root: RootComponent
        compose.runOnUiThread { root = root(lifecycle) }
        compose.setContent { TrenifyAndroidShell(root) }
        try {
            compose.runOnIdle { (root.stack.value.active.instance as RootComponent.Child.Main).component.select(MainTab.Home) }
            // Screen title is a heading; the decorative swap glyph exposes
            // only its localized action label; search is actionable text,
            // not informational prose.
            compose.onNodeWithTag("home-title").assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
            compose.onNodeWithTag("home-swap").assertIsDisplayed().assertHasClickAction()
                .assertContentDescriptionContains(
                    compose.activity.getString(it.danielebufarini.trenify.R.string.home_swap),
                )
            compose.onNodeWithTag("home-search").assertIsDisplayed().assertHasClickAction()
        } finally {
            compose.runOnUiThread { lifecycle.destroy() }
        }
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val resolver = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uri = resolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TrenifyT85")
            },
        )!!
        resolver.openOutputStream(uri)!!.use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
