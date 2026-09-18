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
import it.danielebufarini.trenify.core.domain.StrikeImpact
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
                    label = railwayStrikeImpactLabel(warning),
                    modifier = Modifier.testTag("$tagPrefix-strike-impact-${warning.strikeId}"),
                )
                Text(warning.sector, style = TrenifyTheme.typography.bodyEmphasized, color = TrenifyTheme.colors.textPrimary)
                Text(
                    stringResource(
                        R.string.journey_strike_interval,
                        railwayDateTime(warning.startEpochSeconds),
                        railwayDateTime(warning.endEpochSeconds),
                    ),
                    Modifier.testTag("$tagPrefix-strike-interval-${warning.strikeId}"),
                    style = TrenifyTheme.typography.metadata,
                    color = TrenifyTheme.colors.textSecondary,
                )
                warning.sourceUrl?.let { url ->
                    Text(
                        stringResource(R.string.journey_strike_source, listOfNotNull(warning.sourceLabel, url).joinToString(" · ")),
                        Modifier.testTag("$tagPrefix-strike-source-${warning.strikeId}"),
                        style = TrenifyTheme.typography.metadata,
                        color = TrenifyTheme.colors.textSecondary,
                    )
                }
                if (warning.partialContext) {
                    Text(
                        stringResource(R.string.journey_strike_partial),
                        Modifier.testTag("$tagPrefix-strike-partial-${warning.strikeId}"),
                        style = TrenifyTheme.typography.caption,
                        color = TrenifyTheme.colors.textSecondary,
                    )
                }
            }
        }
        // Unknown means no authoritative coverage. Stale also covers failed
        // refreshes with retained content; neither state implies no issue.
        if (unknown) {
            Text(
                stringResource(R.string.journey_warning_unknown),
                Modifier.testTag("$tagPrefix-strikes-unknown"),
                style = TrenifyTheme.typography.status,
                color = TrenifyTheme.colors.statusWarning,
            )
        } else if (stale || failed) {
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
private fun railwayStrikeImpactLabel(warning: NativeServiceStrikeWarningPresentation): String =
    if (warning.officiallyConfirmed) stringResource(R.string.journey_warning_confirmed)
    else stringResource(
        when (warning.impact) {
            StrikeImpact.NONE -> R.string.journey_strike_impact_none
            StrikeImpact.POTENTIAL -> R.string.journey_strike_impact_potential
            StrikeImpact.LIKELY -> R.string.journey_strike_impact_likely
            StrikeImpact.CONFIRMED_BY_OPERATOR -> R.string.journey_warning_confirmed
        },
    )
