package it.danielebufarini.trenify.alerts

import android.graphics.Bitmap
import android.content.ContentValues
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
import it.danielebufarini.trenify.design.theme.TrenifyTheme
import it.danielebufarini.trenify.navigation.TrenifyShellLayout
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import it.danielebufarini.trenify.app.*
import it.danielebufarini.trenify.core.domain.DataResult
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.OpenStrikeReference
import it.danielebufarini.trenify.core.domain.StrikeRefresh
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.ProviderId
import it.danielebufarini.trenify.core.model.Strike
import it.danielebufarini.trenify.core.model.StrikeGeography
import it.danielebufarini.trenify.core.model.StrikeId
import it.danielebufarini.trenify.core.model.StrikeRelevance
import it.danielebufarini.trenify.core.model.StrikeSource
import it.danielebufarini.trenify.core.model.StrikeStatus
import it.danielebufarini.trenify.core.testing.FakeRealtimeRepositories
import it.danielebufarini.trenify.core.testing.FakeStrikeRepository
import it.danielebufarini.trenify.feature.strikes.AlertsTabComponent
import it.danielebufarini.trenify.navigation.TrenifyAndroidShell
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class TrenifyAlertsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val life = LifecycleRegistry()
    private val repo = FakeRealtimeRepositories()
    private val strikes = FakeStrikeRepository()
    private lateinit var root: RootComponent
    private lateinit var shell: NativeShellPresentation

    private fun futureStrike(
        id: String = "alert-1",
        status: StrikeStatus = StrikeStatus.SCHEDULED,
        operators: List<Operator> = listOf(Operator("Trenitalia")),
    ): Strike {
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        return Strike(
            id = StrikeId(id),
            externalId = id,
            start = now + 1.hours,
            end = now + 25.hours,
            sector = "Ferroviario",
            unions = listOf("ORSA Ferrovie"),
            workforce = "Personale Trenitalia",
            operators = operators,
            geography = StrikeGeography(StrikeRelevance.REGIONAL, regions = listOf("Piemonte")),
            mode = "24 ore",
            status = status,
            notes = null,
            source = StrikeSource(ProviderId("mit-strikes"), "MIT", "https://scioperi.mit.gov.it/$id"),
            sourceUpdatedAt = null,
            contentFingerprint = "fp-$id-${status.name}",
        )
    }

    private fun stage(vararg list: Strike) {
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        strikes.state.value = DataResult.Data(list.toList(), DataFreshness.Fresh(now, null))
        strikes.refreshResult = DataResult.Data(StrikeRefresh(list.toList()), DataFreshness.Fresh(now, null))
    }

    private fun mount() {
        compose.runOnUiThread {
            root = DefaultRootComponent(
                DefaultComponentContext(life),
                AppComponentFactory(repo, repo, repo, repo, MutableStateFlow(false),
                    strikeRepository = strikes, strikeNotificationRepository = strikes,
                    requestNotificationPermission = { true },
                    openStrikeReference = OpenStrikeReference(openUrl = { true })),
            )
            shell = createShellPresentation(root)
            life.resume()
        }
        compose.setContent { TrenifyAndroidShell(root) }
    }

    private fun close() = compose.runOnUiThread { shell.close(); life.destroy() }

    private fun openAlerts() {
        compose.onNodeWithTag("shell-tab-Alerts").performClick()
        waitTag("native-alerts")
    }

    private fun waitTag(tag: String) = compose.waitUntil(5000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test fun alertsOverviewIsNativeWithReadableCards() {
        stage(futureStrike())
        mount()
        try {
            openAlerts()
            compose.onNodeWithTag("native-alerts").assertIsDisplayed()
            val id = "alert-1"
            compose.onNodeWithTag("strike-$id", useUnmergedTree = true).assertIsDisplayed()
            // Status is explicit text, never color-only.
            compose.onNodeWithTag("strike-status-$id", useUnmergedTree = true)
                .assertTextContains(compose.activity.getString(it.danielebufarini.trenify.R.string.alerts_status_scheduled), substring = true)
            compose.onNodeWithTag("strike-interval-$id", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("strike-area-$id", useUnmergedTree = true).assertTextContains("Piemonte", substring = true)
            compose.onNodeWithTag("strike-relevance-$id", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("strike-operators-$id", useUnmergedTree = true).assertTextContains("Trenitalia", substring = true)
            compose.onNodeWithTag("strike-railway-$id", useUnmergedTree = true)
                .assertTextContains(compose.activity.getString(it.danielebufarini.trenify.R.string.alerts_railway_relevant), substring = true)
            compose.runOnIdle {
                // Production Alerts overview is the native facade, never a legacy lease.
                assertNotNull(shell.state.value.active.alerts)
                assertEquals(NativeDestination.AlertsOverview, shell.state.value.active.destination)
            }
        } finally { close() }
    }

    @Test fun modifiedStatusIsExplicitText() {
        stage(futureStrike(id = "alert-mod", status = StrikeStatus.MODIFIED))
        mount()
        try {
            openAlerts()
            compose.onNodeWithTag("strike-status-alert-mod", useUnmergedTree = true)
                .assertTextContains(compose.activity.getString(it.danielebufarini.trenify.R.string.alerts_status_modified), substring = true)
        } finally { close() }
    }

    @Test fun multipleOperatorsStayVisible() {
        stage(futureStrike(operators = listOf(Operator("Trenitalia"), Operator("Italo"))))
        mount()
        try {
            openAlerts()
            compose.onNodeWithTag("strike-operators-alert-1", useUnmergedTree = true).assertTextContains("Trenitalia", substring = true)
            compose.onNodeWithTag("strike-operators-alert-1", useUnmergedTree = true).assertTextContains("Italo", substring = true)
        } finally { close() }
    }

    @Test fun overviewToDetailAndBack() {
        stage(futureStrike())
        mount()
        try {
            openAlerts()
            compose.onNodeWithTag("strike-alert-1", useUnmergedTree = true).performClick()
            waitTag("native-strike-detail")
            compose.onNodeWithTag("native-strike-detail").assertIsDisplayed()
            compose.onNodeWithTag("strike-detail-status-alert-1", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("strike-detail-mode-alert-1", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("strike-detail-source-alert-1", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("strike-detail-reference-open").assertIsDisplayed()
            compose.onNodeWithTag("strike-detail-guaranteed-unavailable").assertIsDisplayed()
            compose.runOnIdle {
                assertNotNull(shell.state.value.active.alerts)
                assertEquals(NativeDestination.StrikeDetail, shell.state.value.active.destination)
            }
            compose.onNodeWithTag("shell-back").performClick()
            waitTag("native-alerts")
            compose.onNodeWithTag("strike-alert-1", useUnmergedTree = true).assertIsDisplayed()
        } finally { close() }
    }

    @Test fun revokedDetailIsExplicitNotColorOnly() {
        stage(futureStrike(), futureStrike(id = "revoked-1", status = StrikeStatus.REVOKED))
        mount()
        try {
            openAlerts()
            // Revoked leaves the overview (shared filter) but resolves as detail.
            compose.onNodeWithTag("strike-revoked-1").assertDoesNotExist()
            compose.runOnIdle { root.alertsComponent().open(StrikeId("revoked-1")) }
            waitTag("native-strike-detail")
            compose.onNodeWithTag("strike-detail-status-revoked-1", useUnmergedTree = true)
                .assertTextContains(compose.activity.getString(it.danielebufarini.trenify.R.string.alerts_status_revoked), substring = true)
        } finally { close() }
    }

    @Test fun emptyAlertsShowsEmptyState() {
        stage()
        mount()
        try {
            openAlerts()
            compose.onNodeWithTag("alerts-empty").assertIsDisplayed()
        } finally { close() }
    }

    @Test fun staleCachedDataPreservesContent() {
        val strike = futureStrike()
        stage(strike)
        mount()
        try {
            openAlerts()
            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            compose.runOnIdle {
                strikes.state.value = DataResult.Data(listOf(strike), DataFreshness.Stale(now, 1.hours, null), DomainFailure.TEMPORARY)
            }
            waitTag("alerts-stale")
            compose.onNodeWithTag("alerts-stale").assertIsDisplayed()
            compose.onNodeWithTag("alerts-error").assertIsDisplayed()
            compose.onNodeWithTag("strike-alert-1", useUnmergedTree = true).assertIsDisplayed()
        } finally { close() }
    }

    @Test fun offlineFailureWithRetainedContent() {
        val strike = futureStrike()
        stage(strike)
        mount()
        try {
            openAlerts()
            compose.runOnIdle { strikes.state.value = DataResult.Failure(DomainFailure.OFFLINE) }
            waitTag("alerts-error")
            compose.onNodeWithTag("alerts-error").assertTextContains(
                compose.activity.getString(it.danielebufarini.trenify.R.string.st_offline), substring = true,
            )
            compose.onNodeWithTag("strike-alert-1", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("alerts-retry").assertIsDisplayed()
        } finally { close() }
    }

    @Test fun notificationToggleWiredToSharedAction() {
        stage(futureStrike())
        mount()
        try {
            openAlerts()
            compose.onNodeWithTag("alerts-notifications").assertIsOff()
            compose.onNodeWithTag("alerts-notifications").performClick()
            compose.waitUntil(5000) { strikes.notificationsEnabled.value }
            compose.onNodeWithTag("alerts-notifications").assertIsOn()
        } finally { close() }
    }

    @Test fun unavailableReferenceRendersTruthfully() {
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val bad = futureStrike().copy(source = StrikeSource(ProviderId("mit-strikes"), "MIT", "https://www.trenitalia.com"))
        strikes.state.value = DataResult.Data(listOf(bad), DataFreshness.Fresh(now, null))
        strikes.refreshResult = DataResult.Data(StrikeRefresh(listOf(bad)), DataFreshness.Fresh(now, null))
        mount()
        try {
            openAlerts()
            compose.onNodeWithTag("strike-reference-unavailable-alert-1", useUnmergedTree = true).assertIsDisplayed()
            compose.runOnIdle { root.alertsComponent().open(StrikeId("alert-1")) }
            waitTag("native-strike-detail")
            compose.onNodeWithTag("strike-detail-reference-unavailable").assertIsDisplayed()
            compose.onNodeWithTag("strike-detail-reference-open").assertDoesNotExist()
        } finally { close() }
    }

    @Test fun referenceOpenFailureSurfaces() {
        stage(futureStrike())
        compose.runOnUiThread {
            root = DefaultRootComponent(
                DefaultComponentContext(life),
                AppComponentFactory(repo, repo, repo, repo, MutableStateFlow(false),
                    strikeRepository = strikes, strikeNotificationRepository = strikes,
                    requestNotificationPermission = { true },
                    openStrikeReference = OpenStrikeReference(openUrl = { false })),
            )
            shell = createShellPresentation(root)
            life.resume()
        }
        compose.setContent { TrenifyAndroidShell(root) }
        try {
            openAlerts()
            compose.runOnIdle { root.alertsComponent().open(StrikeId("alert-1")) }
            waitTag("native-strike-detail")
            compose.onNodeWithTag("strike-detail-reference-open").performClick()
            waitTag("strike-detail-reference-failed")
            compose.onNodeWithTag("strike-detail-reference-failed").assertIsDisplayed()
        } finally { close() }
    }

    @Test fun deterministicNativeAlertsReviewCaptures() {
        stage(futureStrike(), futureStrike(id = "alert-mod", status = StrikeStatus.MODIFIED))
        mount()
        try {
            openAlerts()
            capture("android-alerts-overview")
            compose.runOnIdle { root.alertsComponent().open(StrikeId("alert-1")) }
            waitTag("native-strike-detail")
            capture("android-alerts-detail")
            compose.onNodeWithTag("shell-back").performClick()
            waitTag("native-alerts")
            compose.runOnIdle {
                strikes.state.value = DataResult.Data(
                    listOf(futureStrike(id = "revoked-1", status = StrikeStatus.REVOKED)),
                    DataFreshness.Fresh(Instant.fromEpochMilliseconds(System.currentTimeMillis()), null),
                )
                root.alertsComponent().open(StrikeId("revoked-1"))
            }
            waitTag("native-strike-detail")
            capture("android-alerts-revoked")
        } finally { close() }
    }

    @Test fun alertsReviewDarkAndLargeText() {
        stage(futureStrike(), futureStrike(id = "alert-mod", status = StrikeStatus.MODIFIED))
        var dark by mutableStateOf(true); var large by mutableStateOf(false)
        compose.runOnUiThread {
            root = DefaultRootComponent(
                DefaultComponentContext(life),
                AppComponentFactory(repo, repo, repo, repo, MutableStateFlow(false),
                    strikeRepository = strikes, strikeNotificationRepository = strikes,
                    requestNotificationPermission = { true },
                    openStrikeReference = OpenStrikeReference(openUrl = { true })),
            )
            shell = createShellPresentation(root)
            life.resume()
            shell.select(NativePrimaryArea.Alerts)
        }
        compose.setContent {
            val state by shell.state.collectAsState()
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, if (large) 2f else 1f)) {
                TrenifyTheme(darkTheme = dark, reduceMotion = true) {
                    TrenifyShellLayout(state, shell::select, shell::back, shell::openSettings) {
                        state.active.alerts?.let { NativeAlertsEntry(state.active) }
                    }
                }
            }
        }
        try {
            waitTag("native-alerts")
            capture("android-alerts-dark")
            compose.runOnIdle { large = true }
            waitTag("native-alerts")
            capture("android-alerts-large-text")
        } finally { close() }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TrenifyT810")
        })!!
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun RootComponent.alertsComponent(): AlertsTabComponent {
        val main = (stack.value.active.instance as RootComponent.Child.Main).component
        return (main.pages.value.items[MainTab.Alerts.ordinal].instance as MainComponent.Child.Alerts).component
    }
}
