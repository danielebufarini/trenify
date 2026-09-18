package it.danielebufarini.trenify.design.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import it.danielebufarini.trenify.R
import it.danielebufarini.trenify.design.theme.TrenifyShapes
import it.danielebufarini.trenify.design.theme.TrenifySpacing
import it.danielebufarini.trenify.design.theme.TrenifyTheme

@Composable
fun TrenifyPrimaryAction(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    accessibilityLabel: String? = null,
    loadingLabel: String = stringResource(R.string.ds_loading),
) = TrenifyAction(title, onClick, modifier, enabled, loading, accessibilityLabel, loadingLabel, primary = true)

@Composable
fun TrenifySecondaryAction(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    accessibilityLabel: String? = null,
    loadingLabel: String = stringResource(R.string.ds_loading),
) = TrenifyAction(title, onClick, modifier, enabled, loading, accessibilityLabel, loadingLabel, primary = false)

@Composable
private fun TrenifyAction(
    title: String, onClick: () -> Unit, modifier: Modifier, enabled: Boolean,
    loading: Boolean, accessibilityLabel: String?, loadingLabel: String, primary: Boolean,
) {
    val colors = TrenifyTheme.colors
    Button(
        onClick = onClick, enabled = enabled && !loading,
        modifier = modifier.defaultMinSize(minHeight = 56.dp).semantics {
            accessibilityLabel?.let { contentDescription = it }
            if (loading) stateDescription = loadingLabel
        },
        shape = TrenifyShapes.control,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(TrenifySpacing.xl, TrenifySpacing.l),
        elevation = null,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) colors.accent else colors.surfaceMuted,
            contentColor = if (primary) colors.accentOn else colors.accent,
            disabledContainerColor = colors.surfaceMuted,
            disabledContentColor = colors.textTertiary,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TrenifySpacing.m)) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = colors.textTertiary, strokeWidth = 2.dp)
            Text(title, style = TrenifyTheme.typography.bodyEmphasized)
        }
    }
}
