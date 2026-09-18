package it.danielebufarini.trenify.design.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import it.danielebufarini.trenify.design.theme.TrenifyMotionRole
import it.danielebufarini.trenify.design.theme.TrenifyShapes
import it.danielebufarini.trenify.design.theme.TrenifySpacing
import it.danielebufarini.trenify.design.theme.TrenifyTheme

/** IDs are semantic choices, never live Decompose child-instance identity. */
data class TrenifySegment<T>(val id: T, val title: String, val enabled: Boolean = true)

@Composable
fun <T> TrenifySegmentedControl(
    options: List<TrenifySegment<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    require(options.isNotEmpty() && options.map { it.id }.distinct().size == options.size)
    require(options.any { it.id == selected })
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth().clip(TrenifyShapes.control).background(TrenifyTheme.colors.surfaceMuted)
        .padding(TrenifySpacing.xs).selectableGroup()) {
        if (fontScale >= 1.5f || maxWidth < (options.size * 100).dp) {
            Column(verticalArrangement = Arrangement.spacedBy(TrenifySpacing.xs)) {
                options.forEach { Segment(it, it.id == selected, enabled, onSelect, Modifier.fillMaxWidth()) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(TrenifySpacing.xs)) {
                options.forEach { Segment(it, it.id == selected, enabled, onSelect, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun <T> Segment(option: TrenifySegment<T>, selected: Boolean, enabled: Boolean, onSelect: (T) -> Unit, modifier: Modifier) {
    val colors = TrenifyTheme.colors
    var focused by remember { mutableStateOf(false) }
    val background by animateColorAsState(
        if (selected) colors.surfaceRaised else colors.surfaceMuted,
        TrenifyTheme.motion.spec(TrenifyMotionRole.Selection), label = "Trenify selection",
    )
    Row(
        modifier.defaultMinSize(minHeight = 48.dp).clip(TrenifyShapes.controlSmall)
            .background(background)
            .border(if (focused) 3.dp else if (selected) 2.dp else 0.dp,
                if (focused) colors.focus else if (selected) colors.accent else ColorTransparent, TrenifyShapes.controlSmall)
            .onFocusChanged { focused = it.isFocused }
            .selectable(selected, enabled = enabled && option.enabled, role = Role.RadioButton, onClick = { onSelect(option.id) })
            .padding(TrenifySpacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TrenifySpacing.s, Alignment.CenterHorizontally),
    ) {
        if (selected) Text("✓", Modifier.clearAndSetSemantics {}, style = TrenifyTheme.typography.label, color = colors.accent)
        Text(option.title, style = TrenifyTheme.typography.label,
            color = if (enabled && option.enabled) colors.textPrimary else colors.textTertiary)
    }
}

private val ColorTransparent = androidx.compose.ui.graphics.Color.Transparent
