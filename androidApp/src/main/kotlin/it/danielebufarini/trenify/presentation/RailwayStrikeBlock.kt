package it.danielebufarini.trenify.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import it.danielebufarini.trenify.R
import it.danielebufarini.trenify.app.NativeServiceStrikeWarningPresentation
import it.danielebufarini.trenify.design.component.TrenifyStatusPill
import it.danielebufarini.trenify.design.component.TrenifyStatusTone
import it.danielebufarini.trenify.design.theme.TrenifySpacing
import it.danielebufarini.trenify.design.theme.TrenifyTheme

/** Cross-feature rendering for the shared semantic strike-warning projection. */
@Composable
fun RailwayStrikeBlock(
    warnings: List<NativeServiceStrikeWarningPresentation>,
    stale: Boolean,
    failed: Boolean,
    unknown: Boolean,
    tagPrefix: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TrenifySpacing.s)) {
        warnings.forEach { warning ->
            Column(
                Modifier.testTag("$tagPrefix-strike-${warning.strikeId}"),
                verticalArrangement = Arrangement.spacedBy(TrenifySpacing.xs),
            ) {
                TrenifyStatusPill(
                    tone = TrenifyStatusTone.Warning,
                    label = railwayStrikeLabel(warning),
                    modifier = Modifier.testTag("$tagPrefix-strike-impact-${warning.strikeId}"),
                )
            }
        }
        // Without a warning, retain coverage status rather than imply no strike.
        if (warnings.isEmpty() && unknown) {
            Text(
                stringResource(R.string.journey_warning_unknown),
                Modifier.testTag("$tagPrefix-strikes-unknown"),
                style = TrenifyTheme.typography.status,
                color = TrenifyTheme.colors.statusWarning,
            )
        } else if (warnings.isEmpty() && (stale || failed)) {
            Text(
                stringResource(R.string.journey_warning_stale),
                Modifier.testTag("$tagPrefix-strikes-stale"),
                style = TrenifyTheme.typography.status,
                color = TrenifyTheme.colors.statusWarning,
            )
        }
    }
}

@Composable
fun railwayStrikeLabel(warning: NativeServiceStrikeWarningPresentation): String =
    stringResource(R.string.journey_strike_impact_none) + " · " + stringResource(
        R.string.journey_strike_interval,
        railwayDateTime(warning.startEpochSeconds),
        railwayDateTime(warning.endEpochSeconds),
    )
