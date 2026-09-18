package it.danielebufarini.trenify.design.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import it.danielebufarini.trenify.design.theme.TrenifyColors
import it.danielebufarini.trenify.design.theme.TrenifyShapes
import it.danielebufarini.trenify.design.theme.TrenifySpacing
import it.danielebufarini.trenify.design.theme.TrenifyTheme

/** Visual treatment only. Consumers supply the authoritative semantic label; no delay/status inference. */
enum class TrenifyStatusTone(val symbol: String) {
    OnTime("✓"), Delayed("+"), Cancelled("×"), Arrived("⚑"), Warning("!"), Unknown("?"),
}

fun TrenifyStatusTone.color(colors: TrenifyColors): Color = when (this) {
    TrenifyStatusTone.OnTime -> colors.statusOnTime
    TrenifyStatusTone.Delayed -> colors.statusDelayed
    TrenifyStatusTone.Cancelled -> colors.statusCancelled
    TrenifyStatusTone.Arrived -> colors.statusArrived
    TrenifyStatusTone.Warning -> colors.statusWarning
    TrenifyStatusTone.Unknown -> colors.textSecondary
}

@Composable
fun TrenifyStatusPill(
    tone: TrenifyStatusTone,
    label: String,
    modifier: Modifier = Modifier,
    accessibilityLabel: String = label,
) {
    Surface(
        modifier = modifier.clearAndSetSemantics { contentDescription = accessibilityLabel },
        shape = TrenifyShapes.statusPill, color = TrenifyTheme.colors.surfaceMuted,
        contentColor = tone.color(TrenifyTheme.colors),
    ) {
        Row(
            Modifier.padding(horizontal = TrenifySpacing.m, vertical = TrenifySpacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TrenifySpacing.s),
        ) {
            Text(tone.symbol, style = TrenifyTheme.typography.status)
            Text(label, style = TrenifyTheme.typography.status)
        }
    }
}
