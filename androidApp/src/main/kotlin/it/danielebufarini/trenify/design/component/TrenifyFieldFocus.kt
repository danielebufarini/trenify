package it.danielebufarini.trenify.design.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager

/**
 * T8.12: lets directional-controller users leave a single-line entry field.
 *
 * A single-line field has no vertical cursor movement, so without this the
 * cursor swallows DPAD up/down with no way out (proven by TalkBack runtime
 * certification on the Settings threshold field). Forwarding them to focus
 * traversal loses nothing: left/right cursor movement is untouched, and
 * touch/linear screen-reader navigation never sends these keys.
 */
@Composable
fun Modifier.dpadExitSingleLine(): Modifier {
    val focus = LocalFocusManager.current
    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionUp -> {
                focus.moveFocus(FocusDirection.Up)
                true
            }
            Key.DirectionDown -> {
                focus.moveFocus(FocusDirection.Down)
                true
            }
            else -> false
        }
    }
}
