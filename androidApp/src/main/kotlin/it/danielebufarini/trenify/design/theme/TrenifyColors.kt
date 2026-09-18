package it.danielebufarini.trenify.design.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class TrenifyColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceMuted: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accentOn: Color,
    val divider: Color,
    val statusOnTime: Color,
    val statusDelayed: Color,
    val statusCancelled: Color,
    val statusArrived: Color,
    val statusWarning: Color,
    val selection: Color,
    val focus: Color,
)

val TrenifyLightColors = TrenifyColors(
    background = Color(0xFFF3F6F5), surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFFFFFFF), surfaceMuted = Color(0xFFE7EEEC),
    textPrimary = Color(0xFF182C30), textSecondary = Color(0xFF465B60),
    textTertiary = Color(0xFF596A6F), accent = Color(0xFF005F73),
    accentOn = Color.White, divider = Color(0xFFBBCAC8),
    statusOnTime = Color(0xFF176343), statusDelayed = Color(0xFF855000),
    statusCancelled = Color(0xFFB32634), statusArrived = Color(0xFF315E89),
    statusWarning = Color(0xFF794E10), selection = Color(0xFFD0F1F5),
    focus = Color(0xFF005F73),
)

val TrenifyDarkColors = TrenifyColors(
    background = Color(0xFF101B1E), surface = Color(0xFF19282C),
    surfaceRaised = Color(0xFF23353A), surfaceMuted = Color(0xFF293C41),
    textPrimary = Color(0xFFEDF4F3), textSecondary = Color(0xFFB9CBCF),
    textTertiary = Color(0xFFA0B6BB), accent = Color(0xFF81D7E6),
    accentOn = Color(0xFF00343F), divider = Color(0xFF4B646A),
    statusOnTime = Color(0xFF8CDBB1), statusDelayed = Color(0xFFF5C16C),
    statusCancelled = Color(0xFFFFADB5), statusArrived = Color(0xFFA7CDF2),
    statusWarning = Color(0xFFF0C780), selection = Color(0xFF204B55),
    focus = Color(0xFF81D7E6),
)

/** Material behavior uses the same semantic palette; brand/status colors stay deterministic. */
fun TrenifyColors.materialScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent, onPrimary = accentOn,
        primaryContainer = selection, onPrimaryContainer = textPrimary,
        secondary = accent, onSecondary = accentOn,
        secondaryContainer = surfaceMuted, onSecondaryContainer = textPrimary,
        tertiary = statusArrived, onTertiary = background,
        tertiaryContainer = surfaceMuted, onTertiaryContainer = textPrimary,
        background = background, onBackground = textPrimary,
        surface = surface, onSurface = textPrimary,
        surfaceVariant = surfaceMuted, onSurfaceVariant = textSecondary,
        surfaceTint = accent, inverseSurface = textPrimary, inverseOnSurface = background,
        inversePrimary = if (dark) TrenifyLightColors.accent else TrenifyDarkColors.accent,
        error = statusCancelled, onError = background,
        errorContainer = surfaceMuted, onErrorContainer = statusCancelled,
        outline = textTertiary, outlineVariant = divider,
        surfaceDim = background, surfaceBright = surfaceRaised,
        surfaceContainerLowest = background, surfaceContainerLow = surface,
        surfaceContainer = surface, surfaceContainerHigh = surfaceRaised,
        surfaceContainerHighest = surfaceMuted,
    )
}
