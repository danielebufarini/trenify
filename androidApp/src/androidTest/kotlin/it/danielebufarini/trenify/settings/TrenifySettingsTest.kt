package it.danielebufarini.trenify.settings

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.app.AppComponentFactory
import it.danielebufarini.trenify.app.DefaultRootComponent
import it.danielebufarini.trenify.app.MainTab
import it.danielebufarini.trenify.app.NativeDestination
import it.danielebufarini.trenify.app.NativePrimaryArea
import it.danielebufarini.trenify.app.NativeShellPresentation
import it.danielebufarini.trenify.R
import it.danielebufarini.trenify.app.RootComponent
import it.danielebufarini.trenify.app.createShellPresentation
import it.danielebufarini.trenify.core.domain.FavoritesRepository
import it.danielebufarini.trenify.core.domain.HistoryRepository
import it.danielebufarini.trenify.core.platform.EffectiveNotificationPermission
import it.danielebufarini.trenify.core.testing.FakeNotificationPermission
import it.danielebufarini.trenify.core.testing.FakeNotificationSettingsRepository
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.design.theme.TrenifyTheme
import it.danielebufarini.trenify.navigation.TrenifyAndroidShell
import it.danielebufarini.trenify.navigation.TrenifyShellLayout
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TrenifySettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val life = LifecycleRegistry()
    private val repo = FakeRealtimeRepositories()
    private val settings = FakeNotificationSettingsRepository()
    private val strikes = FakeStrikeRepository()
    private val permission = FakeNotificationPermission()
    private lateinit var root: RootComponent
    private lateinit var shell: NativeShellPresentation

    private fun mount(
        history: HistoryRepository = repo,
        favorites: FavoritesRepository = repo,
    ) {
        compose.runOnUiThread {
            root = DefaultRootComponent(
                DefaultComponentContext(life),
                AppComponentFactory(repo, repo, history, favorites, MutableStateFlow(false),
                    notificationSettingsRepository = settings,
                    notificationPermission = permission,
                    strikeNotificationRepository = strikes,
                    requestNotificationPermission = { true }),
            )
            shell = createShellPresentation(root)
            life.resume()
        }
        compose.setContent { TrenifyAndroidShell(root) }
    }

    private fun close() = compose.runOnUiThread { shell.close(); life.destroy() }

    private fun openSettings() {
        compose.onNodeWithTag("shell-settings").performClick()
        waitTag("native-settings")
    }

    private fun waitTag(tag: String) = compose.waitUntil(5000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    // Dismissal round-trips through shared state; wait for it instead of
    // asserting synchronously so loaded devices (e.g. large-font software
    // rendering) cannot flake on recomposition timing.
    private fun waitGone(tag: String) = compose.waitUntil(5000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }

    @Test fun thresholdFieldReleasesDpadToSave() {
        // T8.12 runtime finding: a directional controller inside the
        // single-line threshold field must be able to leave it; the cursor
        // swallows DPAD up/down, so the field forwards them to traversal.
        mount()
        openSettings()
        scrollTo("settings-threshold")
        compose.onNodeWithTag("settings-threshold").performClick()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("settings-threshold-save").assertIsFocused()
    }

    private fun scrollTo(tag: String) =
        compose.onNodeWithTag("native-settings", useUnmergedTree = true).performScrollToNode(hasTestTag(tag))

    @Test fun settingsIsNativeWithWiredTogglesThresholdFlagsAndStrike() {
        mount()
        try {
            openSettings()
            compose.runOnIdle {
                // Production Settings is the native facade, never a legacy lease.
                assertNotNull(shell.state.value.active.settings)
                assertEquals(NativeDestination.Settings, shell.state.value.active.destination)
            }
            scrollTo("settings-notifications")
            compose.onNodeWithTag("settings-notifications").performClick()
            compose.waitUntil(5000) { !settings.state.value.notificationsEnabled }
            scrollTo("settings-threshold")
            compose.onNodeWithTag("settings-threshold").performTextClearance()
            compose.onNodeWithTag("settings-threshold").performTextInput("30")
            scrollTo("settings-threshold-save")
            compose.onNodeWithTag("settings-threshold-save").performClick()
            compose.waitUntil(5000) { settings.state.value.defaultThresholds.delayMinutes == 30 }
            scrollTo("settings-flag-delay")
            compose.onNodeWithTag("settings-flag-delay").performClick()
            compose.waitUntil(5000) { !settings.state.value.defaultThresholds.notifyDelay }
            scrollTo("settings-strike")
            compose.onNodeWithTag("settings-strike").performClick()
            compose.waitUntil(5000) { strikes.notificationsEnabled.value }
            compose.runOnIdle { assertTrue(strikes.notificationsEnabled.value) }
        } finally { close() }
    }

    @Test fun invalidThresholdShowsErrorAndDisablesSave() {
        mount()
        try {
            openSettings()
            scrollTo("settings-threshold")
            compose.onNodeWithTag("settings-threshold").performTextClearance()
            compose.onNodeWithTag("settings-threshold").performTextInput("abc")
            // The hint/error supporting text merges into the field node, so
            // the error is asserted on the field itself (which is also how
            // TalkBack announces it: label, value and error together).
            compose.onNodeWithTag("settings-threshold").assertTextContains(
                compose.activity.getString(R.string.set_invalid_threshold), substring = true)
            compose.onNodeWithTag("settings-threshold-save").assertIsNotEnabled()
            compose.runOnIdle { assertTrue(settings.writes.isEmpty()) }
        } finally { close() }
    }

    @Test fun permissionDeniedHintKeepsMonitoringActive() {
        permission.effective = EffectiveNotificationPermission.DENIED
        mount()
        try {
            openSettings()
            scrollTo("settings-permission-hint")
            compose.onNodeWithTag("settings-permission-hint").assertIsDisplayed()
            // The saved preference stays on: denial gates delivery, never monitoring.
            compose.runOnIdle { assertTrue(settings.state.value.notificationsEnabled) }
            compose.onNodeWithTag("settings-notifications").assertIsOn()
        } finally { close() }
    }

    @Test fun saveFailureShowsRetryAndRecovers() {
        settings.failure = IllegalStateException("T8.11 settings save failure")
        mount()
        try {
            openSettings()
            scrollTo("settings-notifications")
            compose.onNodeWithTag("settings-notifications").performClick()
            scrollTo("settings-error")
            compose.onNodeWithTag("settings-error").assertIsDisplayed()
            compose.runOnUiThread { settings.failure = null }
            scrollTo("settings-retry")
            compose.onNodeWithTag("settings-retry").performClick()
            compose.waitUntil(5000) { !settings.state.value.notificationsEnabled }
            compose.onNodeWithTag("settings-error").assertDoesNotExist()
        } finally { close() }
    }

    @Test fun historyDeleteConfirmCancelAndSuccess() {
        mount()
        try {
            openSettings()
            scrollTo("settings-delete-history")
            compose.onNodeWithTag("settings-delete-history").performClick()
            compose.onNodeWithTag("settings-delete-history-prompt").assertIsDisplayed()
            compose.onNodeWithTag("settings-delete-history-cancel").performClick()
            waitGone("settings-delete-history-prompt")
            compose.onNodeWithTag("settings-delete-history-prompt").assertDoesNotExist()
            compose.onNodeWithTag("settings-delete-history").performClick()
            scrollTo("settings-delete-history-confirm")
            compose.onNodeWithTag("settings-delete-history-confirm").performClick()
            waitTag("settings-delete-history-success")
            compose.onNodeWithTag("settings-delete-history-success").assertIsDisplayed()
        } finally { close() }
    }

    @Test fun historyDeleteFailureShowsRetry() {
        val failing = object : HistoryRepository by repo {
            override suspend fun clearSearchHistoryAndRecency() =
                throw IllegalStateException("T8.11 history deletion failure")
        }
        mount(history = failing)
        try {
            openSettings()
            scrollTo("settings-delete-history")
            compose.onNodeWithTag("settings-delete-history").performClick()
            scrollTo("settings-delete-history-confirm")
            compose.onNodeWithTag("settings-delete-history-confirm").performClick()
            waitTag("settings-delete-history-error")
            compose.onNodeWithTag("settings-delete-history-error").assertIsDisplayed()
            compose.onNodeWithTag("settings-delete-history-retry").assertIsDisplayed()
        } finally { close() }
    }

    @Test fun favoritesDeleteConfirmCancelAndSuccess() {
        mount()
        try {
            openSettings()
            scrollTo("settings-delete-favorites")
            compose.onNodeWithTag("settings-delete-favorites").performClick()
            compose.onNodeWithTag("settings-delete-favorites-prompt").assertIsDisplayed()
            scrollTo("settings-delete-favorites-cancel")
            compose.onNodeWithTag("settings-delete-favorites-cancel").assertIsDisplayed().performClick()
            waitGone("settings-delete-favorites-prompt")
            compose.onNodeWithTag("settings-delete-favorites-prompt").assertDoesNotExist()
            compose.onNodeWithTag("settings-delete-favorites").performClick()
            scrollTo("settings-delete-favorites-confirm")
            compose.onNodeWithTag("settings-delete-favorites-confirm").performClick()
            waitTag("settings-delete-favorites-success")
            compose.onNodeWithTag("settings-delete-favorites-success").assertIsDisplayed()
        } finally { close() }
    }

    @Test fun settingsBackReturnsToSourceAndHidesSettingsAction() {
        mount()
        try {
            compose.onNodeWithTag("shell-tab-Saved").performClick()
            openSettings()
            // Settings is secondary: no fifth tab and no nested settings action.
            compose.onNodeWithTag("shell-settings").assertDoesNotExist()
            compose.onNodeWithTag("shell-back").performClick()
            compose.onNodeWithTag("shell-tab-Saved").assertIsSelected()
            compose.onNodeWithTag("shell-settings").assertExists()
            compose.runOnIdle {
                val main = (root.stack.value.active.instance as RootComponent.Child.Main).component
                assertEquals(MainTab.Favorites.ordinal, main.pages.value.selectedIndex)
                assertFalse(shell.state.value.canGoBack)
            }
        } finally { close() }
    }

    @Test fun deterministicNativeSettingsReviewCaptures() {
        mount()
        try {
            openSettings()
            capture("android-settings")
            scrollTo("settings-threshold")
            compose.onNodeWithTag("settings-threshold").performTextClearance()
            compose.onNodeWithTag("settings-threshold").performTextInput("abc")
            scrollTo("settings-threshold")
            capture("android-settings-invalid")
            // Drive the confirmation through the production UI like a user.
            scrollTo("settings-delete-history")
            compose.onNodeWithTag("settings-delete-history").performClick()
            waitTag("settings-delete-history-prompt")
            scrollTo("settings-delete-history-confirm")
            capture("android-settings-delete-confirm")
        } finally { close() }
    }

    @Test fun settingsReviewDarkAndLargeText() {
        var dark by mutableStateOf(true); var large by mutableStateOf(false)
        compose.runOnUiThread {
            root = DefaultRootComponent(
                DefaultComponentContext(life),
                AppComponentFactory(repo, repo, repo, repo, MutableStateFlow(false),
                    notificationSettingsRepository = settings,
                    notificationPermission = FakeNotificationPermission(
                        effective = EffectiveNotificationPermission.DENIED),
                    strikeNotificationRepository = strikes,
                    requestNotificationPermission = { true }),
            )
            shell = createShellPresentation(root)
            life.resume()
            shell.openSettings()
        }
        compose.setContent {
            val state by shell.state.collectAsState()
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, if (large) 2f else 1f)) {
                TrenifyTheme(darkTheme = dark, reduceMotion = true) {
                    TrenifyShellLayout(state, shell::select, shell::back, shell::openSettings) {
                        state.active.settings?.let { NativeSettingsEntry(state.active) }
                    }
                }
            }
        }
        try {
            waitTag("native-settings")
            capture("android-settings-dark")
            compose.runOnIdle { large = true }
            waitTag("native-settings")
            capture("android-settings-dark-large-text")
        } finally { close() }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TrenifyT811")
        })!!
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
