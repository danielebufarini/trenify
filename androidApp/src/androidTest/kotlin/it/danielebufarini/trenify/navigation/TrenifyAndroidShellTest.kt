package it.danielebufarini.trenify.navigation

import android.graphics.Bitmap
import android.content.ContentValues
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import it.danielebufarini.trenify.app.*
import it.danielebufarini.trenify.core.platform.NotificationDestination
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.design.theme.TrenifyTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class TrenifyAndroidShellTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun fourTabsForwardSharedActionsAndExposeSelectedSemanticsWithoutRootRecreation() {
        val lifecycle = LifecycleRegistry()
        lateinit var root: RootComponent
        compose.runOnUiThread { root = root(lifecycle) }
        compose.setContent { TrenifyAndroidShell(root) }
        try {
            val original = root.stack.value.active.instance
            for ((area, tab) in listOf(NativePrimaryArea.Search to MainTab.Home,
                NativePrimaryArea.Monitoring to MainTab.Monitoring, NativePrimaryArea.Saved to MainTab.Favorites,
                NativePrimaryArea.Alerts to MainTab.Alerts)) {
                compose.onNodeWithTag("shell-tab-${area.name}").performClick().assertIsSelected()
                NativePrimaryArea.entries.filter { it != area }.forEach {
                    compose.onNodeWithTag("shell-tab-${it.name}").assertIsNotSelected()
                }
                compose.runOnIdle {
                    assertSame(original, root.stack.value.active.instance)
                    val main = (original as RootComponent.Child.Main).component
                    assertEquals(tab.ordinal, main.pages.value.selectedIndex)
                }
                compose.onNodeWithTag("shell-tab-${area.name}").performClick()
            }
            compose.onNodeWithTag("shell-tab-Search").performClick()
            // T8.5: production Search is Android-owned native Home, not legacy shared Home.
            compose.onNodeWithTag("home-composer").assertExists()
            compose.onNodeWithTag("home-search").assertExists()
            compose.onNodeWithTag("home-origin").assertExists()
            compose.onNodeWithTag("home-destination").assertExists()
            compose.onNodeWithTag("home-action-journeys").assertDoesNotExist()
            compose.onNodeWithTag("main-tab-Home").assertDoesNotExist()
        } finally { compose.runOnUiThread { lifecycle.destroy() } }
    }

    @Test fun sharedNotificationSelectionAndSystemBackFollowNestedDecomposeState() {
        val lifecycle = LifecycleRegistry()
        lateinit var root: RootComponent
        compose.runOnUiThread { root = root(lifecycle) }
        compose.setContent { TrenifyAndroidShell(root) }
        try {
            compose.runOnIdle { root.onNotificationDestination(NotificationDestination.Strike("a")) }
            compose.onNodeWithTag("shell-tab-Alerts").assertIsSelected()
            compose.runOnIdle { root.onNotificationDestination(NotificationDestination.Strike("b")) }
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.runOnIdle {
                val main = (root.stack.value.active.instance as RootComponent.Child.Main).component
                val alerts = (main.pages.value.items[MainTab.Alerts.ordinal].instance as MainComponent.Child.Alerts).component
                assertEquals("a", (alerts.stack.value.active.instance as it.danielebufarini.trenify.feature.strikes.AlertsTabComponent.Child.Detail).strikeId.value)
            }
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("shell-tab-Alerts").assertIsSelected()
            compose.runOnIdle {
                (root.stack.value.active.instance as RootComponent.Child.Main).component.openHistory()
            }
            compose.onNodeWithTag("shell-tab-Search").assertIsSelected()
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.runOnIdle {
                val main = (root.stack.value.active.instance as RootComponent.Child.Main).component
                val journey = (main.pages.value.items[MainTab.Journey.ordinal].instance as MainComponent.Child.Journey).component
                assertEquals(1, journey.stack.value.backStack.size + 1)
            }
        } finally { compose.runOnUiThread { lifecycle.destroy() } }
    }

    @Test fun shellReviewAllSelectionsLightDarkAndLargeText() {
        var selected by androidx.compose.runtime.mutableStateOf(NativePrimaryArea.Search)
        var dark by androidx.compose.runtime.mutableStateOf(false)
        var large by androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, if (large) 2f else 1f)) {
                TrenifyTheme(darkTheme = dark, reduceMotion = true) {
                    TrenifyShellLayout(NativeShellState(selected, NativeShellEntry(1, NativeDestination.Home, true), emptyList()),
                        { selected = it }, {}, {}) {
                        Box(Modifier.fillMaxSize().testTag("fixture-body")) { Text("Trenify") }
                    }
                }
            }
        }
        for (area in NativePrimaryArea.entries) {
            compose.onNodeWithTag("shell-tab-${area.name}").performClick().assertIsSelected()
            capture("android-${area.name.lowercase()}-light")
        }
        compose.runOnIdle { dark = true; selected = NativePrimaryArea.Monitoring }
        compose.onNodeWithTag("shell-tab-Monitoring").assertIsSelected()
        capture("android-monitoring-dark")
        compose.runOnIdle { large = true }
        NativePrimaryArea.entries.forEach { compose.onNodeWithTag("shell-tab-${it.name}").assertIsDisplayed() }
        capture("android-monitoring-dark-large-text")
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TrenifyT84")
        })!!
        resolver.openOutputStream(uri)!!.use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun root(lifecycle: LifecycleRegistry): RootComponent {
        val repositories = FakeRealtimeRepositories()
        return DefaultRootComponent(DefaultComponentContext(lifecycle), AppComponentFactory(
            repositories, repositories, repositories, repositories, MutableStateFlow(false)))
    }
}
