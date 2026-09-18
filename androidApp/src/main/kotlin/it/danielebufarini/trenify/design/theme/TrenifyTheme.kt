package it.danielebufarini.trenify.design.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** Durations describe visual confirmation only; routeProgress is guidance for T8.7. */
enum class TrenifyMotionRole(val milliseconds: Int) {
    Quick(100), Standard(200), Emphasized(300), RouteProgress(450), Selection(160), StatusChange(200),
}

@Immutable
data class TrenifyMotion(val reduceMotion: Boolean) {
    fun <T> spec(role: TrenifyMotionRole): FiniteAnimationSpec<T> =
        if (reduceMotion) snap() else tween(role.milliseconds, easing = FastOutSlowInEasing)
}

private val LocalColors = staticCompositionLocalOf { TrenifyLightColors }
private val LocalTypography = staticCompositionLocalOf { TrenifyTypography() }
private val LocalMotion = staticCompositionLocalOf { TrenifyMotion(reduceMotion = false) }

object TrenifyTheme {
    val colors: TrenifyColors @Composable get() = LocalColors.current
    val typography: TrenifyTypography @Composable get() = LocalTypography.current
    val motion: TrenifyMotion @Composable get() = LocalMotion.current
}

@Composable
fun TrenifyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    reduceMotion: Boolean = systemReduceMotion(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) TrenifyDarkColors else TrenifyLightColors
    val typography = remember { TrenifyTypography() }
    CompositionLocalProvider(
        LocalColors provides colors,
        LocalTypography provides typography,
        LocalMotion provides TrenifyMotion(reduceMotion),
    ) {
        MaterialTheme(colorScheme = colors.materialScheme(darkTheme), typography = typography.material,
            shapes = TrenifyShapes.material, content = content)
    }
}

@Composable
private fun systemReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun reduced() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reduceMotion by remember(resolver) { mutableStateOf(reduced()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduceMotion = reduced() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduceMotion
}
