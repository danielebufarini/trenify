package it.danielebufarini.trenify.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import it.danielebufarini.trenify.design.component.TrenifyStatusTone
import it.danielebufarini.trenify.design.component.color
import it.danielebufarini.trenify.design.theme.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TrenifyTokenTest {
    @Test fun readableStatusAndTextInBothPalettes() {
        listOf(TrenifyLightColors, TrenifyDarkColors).forEach { colors ->
            listOf(colors.textPrimary, colors.textSecondary, colors.textTertiary).forEach { foreground ->
                listOf(colors.background, colors.surface, colors.surfaceRaised, colors.surfaceMuted, colors.selection).forEach { surface ->
                    assertTrue(contrast(foreground, surface) >= 4.5f, "Text contrast: $foreground / $surface")
                }
            }
            TrenifyStatusTone.entries.forEach { tone ->
                assertTrue(contrast(tone.color(colors), colors.surfaceMuted) >= 4.5f, "Status contrast: $tone")
            }
            assertTrue(contrast(colors.accentOn, colors.accent) >= 4.5f)
            assertTrue(contrast(colors.focus, colors.surface) >= 3f)
        }
    }

    @Test fun unknownIsNeutralAndSymbolsRemainDistinct() {
        assertEquals(TrenifyStatusTone.entries.size, TrenifyStatusTone.entries.map { it.symbol }.distinct().size)
        listOf(TrenifyLightColors, TrenifyDarkColors).forEach { colors ->
            assertEquals(colors.textSecondary, TrenifyStatusTone.Unknown.color(colors))
            assertNotEquals(TrenifyStatusTone.OnTime.color(colors), TrenifyStatusTone.Unknown.color(colors))
        }
    }

    @Test fun materialSchemeKeepsTrenifyIdentityAndIntentionalDarkSurfaces() {
        listOf(false, true).forEach { dark ->
            val colors = if (dark) TrenifyDarkColors else TrenifyLightColors
            val scheme = colors.materialScheme(dark)
            assertEquals(colors.accent, scheme.primary)
            assertEquals(colors.statusCancelled, scheme.error)
            assertEquals(colors.surfaceRaised, scheme.surfaceContainerHigh)
            assertEquals(colors.textPrimary, scheme.onSurface)
        }
        assertNotEquals(TrenifyDarkColors.surface, TrenifyDarkColors.surfaceRaised)
    }

    private fun contrast(a: Color, b: Color): Float =
        (maxOf(a.luminance(), b.luminance()) + 0.05f) / (minOf(a.luminance(), b.luminance()) + 0.05f)
}
