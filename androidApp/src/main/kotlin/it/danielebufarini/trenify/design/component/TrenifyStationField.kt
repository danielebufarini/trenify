package it.danielebufarini.trenify.design.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import it.danielebufarini.trenify.R
import it.danielebufarini.trenify.design.theme.TrenifyShapes
import it.danielebufarini.trenify.design.theme.TrenifySpacing
import it.danielebufarini.trenify.design.theme.TrenifyTheme

/** Station-selection surface. Text editing/search belongs to the later feature, not this primitive. */
@Composable
fun TrenifyStationField(
    roleLabel: String,
    stationName: String?,
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = stationName != null,
    enabled: Boolean = true,
    focused: Boolean = false,
    onClear: (() -> Unit)? = null,
    accessibilityLabel: String? = null,
    selectionStateLabel: String = stringResource(if (selected) R.string.ds_selected else R.string.ds_unselected),
    clearLabel: String = stringResource(R.string.ds_clear_station),
) {
    val colors = TrenifyTheme.colors
    var keyboardFocused by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(), shape = TrenifyShapes.control,
        color = if (selected) colors.surface else colors.surfaceMuted,
        border = BorderStroke(if (focused || keyboardFocused) 2.dp else 1.dp,
            if (focused || keyboardFocused) colors.focus else colors.divider),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1f).defaultMinSize(minHeight = 72.dp)
                    .onFocusChanged { keyboardFocused = it.isFocused }
                    .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                    .semantics {
                        stateDescription = selectionStateLabel
                        accessibilityLabel?.let { contentDescription = it }
                    }.padding(TrenifySpacing.l),
                verticalArrangement = Arrangement.spacedBy(TrenifySpacing.xs),
            ) {
                Text(roleLabel, style = TrenifyTheme.typography.label, color = colors.textSecondary)
                Text(stationName ?: placeholder, style = TrenifyTheme.typography.routeStation,
                    color = if (enabled && stationName != null) colors.textPrimary else colors.textTertiary)
            }
            if (onClear != null && stationName != null) {
                TextButton(onClick = onClear, enabled = enabled,
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = clearLabel }) {
                    Text("×", Modifier.clearAndSetSemantics {}, color = if (enabled) colors.accent else colors.textTertiary)
                }
            }
        }
    }
}
