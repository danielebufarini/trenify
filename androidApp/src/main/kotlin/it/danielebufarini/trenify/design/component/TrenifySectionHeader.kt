package it.danielebufarini.trenify.design.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import it.danielebufarini.trenify.design.theme.TrenifySpacing
import it.danielebufarini.trenify.design.theme.TrenifyTheme

@Composable
fun TrenifySectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TrenifySpacing.xs)) {
        Text(title, Modifier.semantics { heading() }, style = TrenifyTheme.typography.sectionTitle,
            color = TrenifyTheme.colors.textPrimary)
        subtitle?.let { Text(it, style = TrenifyTheme.typography.metadata, color = TrenifyTheme.colors.textSecondary) }
        if (actionLabel != null && onAction != null) TextButton(onClick = onAction) {
            Text(actionLabel, style = TrenifyTheme.typography.label)
        }
    }
}
