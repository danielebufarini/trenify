package it.danielebufarini.trenify.design

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import it.danielebufarini.trenify.app.NativePrimaryArea
import it.danielebufarini.trenify.design.component.TrenifyBottomNavigation
import it.danielebufarini.trenify.design.theme.TrenifyTheme
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import kotlin.test.assertTrue

/**
 * Shell-wide bottom-navigation accessibility: full localized labels at normal
 * scales, icon-focused variant with complete TalkBack names at very large
 * text. The switch depends only on the requested font scale, never on
 * destination identity. System font scaling is never suppressed.
 */
class TrenifyBottomNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val en = mapOf(
        NativePrimaryArea.Search to "Search",
        NativePrimaryArea.Monitoring to "Monitoring",
        NativePrimaryArea.Saved to "Saved",
        NativePrimaryArea.Alerts to "Alerts",
    )
    private val it = mapOf(
        NativePrimaryArea.Search to "Cerca",
        NativePrimaryArea.Monitoring to "Monitoraggio",
        NativePrimaryArea.Saved to "Salvati",
        NativePrimaryArea.Alerts to "Avvisi",
    )

    private var fontScale by mutableStateOf(1f)
    private var locale by mutableStateOf(Locale.ENGLISH)
    private var selected by mutableStateOf(NativePrimaryArea.Monitoring)
    private val clicks = mutableListOf<NativePrimaryArea>()

    private fun mount() {
        clicks.clear()
        fontScale = 1f
        locale = Locale.ENGLISH
        selected = NativePrimaryArea.Monitoring
        compose.setContent {
            val config = Configuration(compose.activity.resources.configuration)
            config.setLocales(LocaleList(locale))
            val localized = compose.activity.createConfigurationContext(config)
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
                LocalContext provides localized,
            ) {
                TrenifyTheme {
                    Box(Modifier.requiredWidth(360.dp)) {
                        TrenifyBottomNavigation(selected) { clicks.add(it); selected = it }
                    }
                }
            }
        }
    }

    private fun tab(area: NativePrimaryArea) = compose.onNodeWithTag("shell-tab-${area.name}")

    private fun setCase(scale: Float, tag: Locale) {
        compose.runOnIdle { fontScale = scale; locale = tag }
    }

    @Test fun normalScaleRendersFourLabeledDestinations() {
        mount()
        en.forEach { (area, name) ->
            compose.onNodeWithText(name, useUnmergedTree = true).assertIsDisplayed()
            tab(area).assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
        }
        tab(NativePrimaryArea.Monitoring).assertIsSelected()
        tab(NativePrimaryArea.Search).assertIsNotSelected()
        NativePrimaryArea.entries.forEach { tab(it).performClick() }
        compose.runOnIdle { assertTrue(clicks.toList() == NativePrimaryArea.entries) }
    }

    private fun assertLabeled(names: Map<NativePrimaryArea, String>, scale: Float) {
        // Single-line caption is 18sp: anything at/above 1.5 lines is an ugly wrap.
        val singleLineMax = 27f * scale
        names.values.forEach { name ->
            val text = compose.onNodeWithText(name, useUnmergedTree = true)
            text.assertIsDisplayed()
            val bounds = text.getBoundsInRoot()
            assertTrue(text.getUnclippedBoundsInRoot() == bounds, "clipped label $name at $scale")
            assertTrue(((bounds.bottom - bounds.top).value) < singleLineMax, "wrapped label $name at $scale")
        }
        assertNoTabOverlap()
    }

    private fun assertCompact(names: Map<NativePrimaryArea, String>) {
        // No visible label text anywhere: nothing to truncate, clip or split mid-word.
        names.values.forEach { name ->
            compose.onAllNodesWithText(name, substring = true).assertCountEquals(0)
            compose.onNodeWithContentDescription(name).assertIsDisplayed().assertHasClickAction()
                .assertHeightIsAtLeast(48.dp)
        }
        assertNoTabOverlap()
    }

    @Test fun fittingLabelsStayLabeledEnglish() {
        mount()
        listOf(1f, 1.3f).forEach { scale ->
            setCase(scale, Locale.ENGLISH)
            assertLabeled(en, scale)
        }
    }

    @Test fun fittingLabelsStayLabeledItalianAtNormalScale() {
        mount()
        setCase(1f, Locale.ITALIAN)
        assertLabeled(it, 1f)
    }

    @Test fun compactScaleKeepsFourReachableDestinationsWithFullSemanticsEnglish() {
        mount()
        setCase(2f, Locale.ENGLISH)
        assertCompact(en)
        compose.onNodeWithContentDescription("Monitoring").assertIsSelected()
        compose.onNodeWithContentDescription("Search").assertIsNotSelected()
        NativePrimaryArea.entries.forEach {
            compose.onNodeWithContentDescription(en.getValue(it)).performClick()
        }
        compose.runOnIdle { assertTrue(clicks.toList() == NativePrimaryArea.entries) }
    }

    @Test fun compactScaleKeepsFourReachableDestinationsWithFullSemanticsItalian() {
        mount()
        setCase(2f, Locale.ITALIAN)
        assertCompact(it)
        compose.onNodeWithContentDescription("Monitoraggio").assertIsSelected()
    }

    @Test fun overflowingLabelsGoCompactWithoutSinglingOutMonitoring() {
        mount()
        // Italian at 1.3 no longer fits "Monitoraggio" on one line: the whole
        // bar adapts, not just the Monitoring tab.
        setCase(1.3f, Locale.ITALIAN)
        assertCompact(it)
        compose.onNodeWithContentDescription("Monitoraggio").assertIsSelected()
        // Another selected tab proves the compact variant is shell-wide.
        compose.runOnIdle { selected = NativePrimaryArea.Saved }
        assertCompact(it)
        compose.onNodeWithContentDescription("Salvati").assertIsSelected()
    }

    private fun assertNoTabOverlap() {
        val bounds = NativePrimaryArea.entries.map { tab(it).getBoundsInRoot() }
        bounds.zipWithNext { left, right ->
            assertTrue(left.right <= right.left, "navigation overlap $left vs $right")
        }
    }

    @Test fun deterministicShellNavigationReviewCaptures() {
        mount()
        capture("shell-navigation-en-1", 1f, Locale.ENGLISH, NativePrimaryArea.Monitoring)
        capture("shell-navigation-en-2-monitoring", 2f, Locale.ENGLISH, NativePrimaryArea.Monitoring)
        capture("shell-navigation-it-2-monitoraggio", 2f, Locale.ITALIAN, NativePrimaryArea.Monitoring)
        capture("shell-navigation-en-2-saved", 2f, Locale.ENGLISH, NativePrimaryArea.Saved)
    }

    private fun capture(name: String, fontScale: Float, tag: Locale, area: NativePrimaryArea) {
        setCase(fontScale, tag)
        compose.runOnIdle { selected = area }
        compose.onNodeWithTag("shell-navigation").assertIsDisplayed()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uri = resolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TrenifyShell")
            },
        )!!
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
