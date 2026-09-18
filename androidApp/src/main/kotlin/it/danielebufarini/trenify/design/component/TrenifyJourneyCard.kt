package it.danielebufarini.trenify.design.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.danielebufarini.trenify.R
import it.danielebufarini.trenify.design.theme.TrenifyElevation
import it.danielebufarini.trenify.design.theme.TrenifyShapes
import it.danielebufarini.trenify.design.theme.TrenifySpacing
import it.danielebufarini.trenify.design.theme.TrenifyTheme

/** Display inputs only: times/duration/changes are formatted by the consuming presentation. */
@Composable
fun TrenifyJourneyCard(
    trainIdentity: String,
    departureTime: String,
    arrivalTime: String,
    origin: String,
    destination: String,
    durationLabel: String,
    changesLabel: String,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
    statusTone: TrenifyStatusTone = TrenifyStatusTone.Unknown,
    /** Null omits the status pill: neutral/default content carries no badge. */
    statusLabel: String? = stringResource(R.string.ds_status_unknown),
    platformLabel: String? = null,
    detailsLabel: String = stringResource(R.string.ds_details),
    departureLabel: String = stringResource(R.string.ds_departure),
    arrivalLabel: String = stringResource(R.string.ds_arrival),
    enabled: Boolean = true,
) {
    val colors = TrenifyTheme.colors
    val fontScale = LocalDensity.current.fontScale
    Surface(
        modifier = modifier.fillMaxWidth(), color = colors.surfaceRaised,
        contentColor = colors.textPrimary, shape = TrenifyShapes.card,
        shadowElevation = TrenifyElevation.raised,
    ) {
        Column(
            Modifier.padding(horizontal = TrenifySpacing.xl, vertical = TrenifySpacing.l),
            verticalArrangement = Arrangement.spacedBy(TrenifySpacing.s),
        ) {
            Text(trainIdentity, style = TrenifyTheme.typography.trainIdentity, color = colors.textSecondary)
            statusLabel?.let { TrenifyStatusPill(statusTone, it) }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (fontScale >= 1.5f || maxWidth < 280.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(TrenifySpacing.l)) {
                        RouteEndpoint(departureLabel, departureTime, origin)
                        RouteEndpoint(arrivalLabel, arrivalTime, destination)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(TrenifySpacing.l)) {
                        RouteEndpoint(departureLabel, departureTime, origin, Modifier.weight(1f))
                        RouteEndpoint(arrivalLabel, arrivalTime, destination, Modifier.weight(1f))
                    }
                }
            }
            HorizontalDivider(color = colors.divider)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TrenifySpacing.s),
            ) {
                Text(
                    journeyCardSummaryLabel(durationLabel, changesLabel),
                    modifier = Modifier.weight(1f),
                    style = TrenifyTheme.typography.bodyEmphasized,
                )
                TextButton(onClick = onDetails, enabled = enabled) {
                    Text(detailsLabel, style = TrenifyTheme.typography.bodyEmphasized)
                }
            }
            platformLabel?.let { Text(it, style = TrenifyTheme.typography.metadata, color = colors.textSecondary) }
        }
    }
}

internal fun journeyCardSummaryLabel(durationLabel: String, structureLabel: String): String =
    "$durationLabel · $structureLabel"

@Composable
private fun RouteEndpoint(role: String, time: String, station: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(TrenifySpacing.xs)) {
        Text(role, style = TrenifyTheme.typography.caption, color = TrenifyTheme.colors.textTertiary)
        Text(time, style = TrenifyTheme.typography.routeTime)
        Text(station, style = TrenifyTheme.typography.routeStation)
    }
}
