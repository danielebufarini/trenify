package it.danielebufarini.trenify.design

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import it.danielebufarini.trenify.design.theme.TrenifyLightColors
import it.danielebufarini.trenify.design.theme.TrenifyDarkColors
import it.danielebufarini.trenify.design.theme.TrenifySpacing
import it.danielebufarini.trenify.design.component.*
import it.danielebufarini.trenify.design.theme.TrenifyTheme
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class TrenifyComponentTest {
    @Test fun lightThemeComponentReviewAndTargets() = reviewFamily(dark = false)
    @Test fun darkThemeComponentReviewAndTargets() = reviewFamily(dark = true)

    @Test fun journeyCardKeepsDurationAndStructureInlineAndDetailsActionable() {
        var detailsCalls = 0
        compose.setContent {
            TrenifyTheme(darkTheme = false, reduceMotion = true) {
                Column(verticalArrangement = Arrangement.spacedBy(TrenifySpacing.l)) {
                    TrenifyJourneyCard(
                        trainIdentity = "Regionale 2823",
                        departureTime = "14:07",
                        arrivalTime = "14:20",
                        origin = "Monza",
                        destination = "Milano Centrale",
                        durationLabel = "13m",
                        changesLabel = "Direct",
                        onDetails = { detailsCalls++ },
                        statusLabel = null,
                        detailsLabel = "Details",
                    )
                    TrenifyJourneyCard(
                        trainIdentity = "Intercity 901",
                        departureTime = "09:00",
                        arrivalTime = "09:45",
                        origin = "Monza",
                        destination = "Milano Centrale",
                        durationLabel = "45m",
                        changesLabel = "1 change · via Bologna Centrale",
                        onDetails = { detailsCalls++ },
                        statusLabel = null,
                        detailsLabel = "Details",
                    )
                }
            }
        }

        compose.onNodeWithText("13m · Direct").assertIsDisplayed()
        compose.onNodeWithText("45m · 1 change · via Bologna Centrale").assertIsDisplayed()
        compose.onAllNodesWithText("Details").assertCountEquals(2)
        compose.onAllNodesWithText("Details")[0].assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, detailsCalls) }
    }

    private fun reviewFamily(dark: Boolean) {
        val palette = if (dark) TrenifyDarkColors else TrenifyLightColors
        compose.setContent {
            TrenifyTheme(darkTheme = dark, reduceMotion = true) {
                assertEquals(palette.accent, MaterialTheme.colorScheme.primary)
                Surface(color = TrenifyTheme.colors.background) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(TrenifySpacing.screenHorizontal),
                        verticalArrangement = Arrangement.spacedBy(TrenifySpacing.l)) {
                        TrenifySectionHeader("Component family", subtitle = "Deterministic fixtures")
                        TrenifySegmentedControl(listOf(TrenifySegment("journey", "Journey"), TrenifySegment("train", "Train")), "journey", {})
                        TrenifyStationField("From", "San Benedetto del Tronto Porto d’Ascoli", "Choose station", {}, onClear = {})
                        TrenifyPrimaryAction("Search trains", {})
                        TrenifySecondaryAction("Change selection", {})
                        TrenifyStatusPill(TrenifyStatusTone.Delayed, "Delayed +12 min")
                        TrenifyStatusPill(TrenifyStatusTone.Cancelled, "Cancelled")
                        TrenifyJourneyCard("Trenitalia · Frecciarossa 9516", "14:10", "17:19", "Milano Centrale", "Roma Termini",
                            "3 h 09 min", "Direct", {}, statusTone = TrenifyStatusTone.OnTime, statusLabel = "On time", detailsLabel = "Details")
                    }
                }
            }
        }
        compose.onNodeWithText("Search trains").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Change selection").assertHeightIsAtLeast(48.dp)
        saveReview("android-${if (dark) "dark" else "light"}-controls")
        compose.onNodeWithText("Details").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("On time").assertExists()
        saveReview("android-${if (dark) "dark" else "light"}-journey")
    }

    private fun saveReview(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @get:Rule val compose = createComposeRule()

    @Test fun statusAlwaysExposesTextIncludingUnknown() {
        compose.setContent {
            TrenifyTheme {
                Column { TrenifyStatusTone.entries.forEach { TrenifyStatusPill(it, "Status ${it.name}") } }
            }
        }
        TrenifyStatusTone.entries.forEach { compose.onNodeWithContentDescription("Status ${it.name}").assertExists() }
    }

    @Test fun segmentedSelectionIsSemanticAndDisabledOptionDoesNotAct() {
        var calls = 0
        lateinit var inputMode: InputModeManager
        compose.setContent {
            TrenifyTheme {
                inputMode = LocalInputModeManager.current
                var selected by remember { mutableStateOf("journey") }
                TrenifySegmentedControl(
                    listOf(TrenifySegment("journey", "Journey"), TrenifySegment("train", "Train"), TrenifySegment("disabled", "Disabled", false)),
                    selected, { selected = it; calls++ },
                )
            }
        }
        compose.onNodeWithText("Journey").assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        compose.onNodeWithText("Train").performClick().assertIsSelected()
        compose.onNodeWithText("Journey").assertIsNotSelected()
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        compose.onNodeWithText("Journey").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .assertIsFocused().performKeyInput { pressKey(Key.Enter) }.assertIsSelected()
        compose.onNodeWithText("Disabled").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(2, calls) }
    }

    @Test fun loadingAndDisabledActionsDoNotDispatch() {
        var calls = 0
        compose.setContent {
            TrenifyTheme {
                Column {
                    TrenifyPrimaryAction("Search", { calls++ }, loading = true, loadingLabel = "Loading")
                    TrenifySecondaryAction("Change", { calls++ }, enabled = false)
                }
            }
        }
        compose.onNodeWithText("Search").assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Loading")).performClick()
        compose.onNodeWithText("Change").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun stationSelectionAndClearAreIndependentAccessibleActions() {
        var selections = 0
        var clears = 0
        compose.setContent {
            TrenifyTheme {
                TrenifyStationField("From", "Milano Centrale", "Choose", { selections++ },
                    onClear = { clears++ }, selectionStateLabel = "Selected", clearLabel = "Clear origin")
            }
        }
        compose.onNodeWithText("Milano Centrale")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected")).performClick()
        compose.onNodeWithContentDescription("Clear origin").assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, selections); assertEquals(1, clears) }
    }

    @Test fun darkLargeTextKeepsLongEndpointsAndDetailActionReachable() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                TrenifyTheme(darkTheme = true, reduceMotion = true) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        TrenifyJourneyCard("Frecciarossa 9516", "14:10", "17:19", "San Benedetto del Tronto Porto d’Ascoli",
                            "Reggio di Calabria Centrale", "3 h 09 min", "Direct", {}, detailsLabel = "Details")
                    }
                }
            }
        }
        compose.onNodeWithText("San Benedetto del Tronto Porto d’Ascoli").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Reggio di Calabria Centrale").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Details").performScrollTo().assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithContentDescription("Status unavailable").assertExists()
    }
}
