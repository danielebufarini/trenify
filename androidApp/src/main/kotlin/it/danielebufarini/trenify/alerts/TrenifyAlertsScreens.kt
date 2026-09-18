package it.danielebufarini.trenify.alerts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.danielebufarini.trenify.R
import it.danielebufarini.trenify.app.NativeAlertsState
import it.danielebufarini.trenify.app.NativeRealtimeFreshness
import it.danielebufarini.trenify.app.NativeShellEntry
import it.danielebufarini.trenify.app.NativeStrikeRow
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.model.StrikeRelevance
import it.danielebufarini.trenify.core.model.StrikeStatus
import it.danielebufarini.trenify.design.component.TrenifyStatusPill
import it.danielebufarini.trenify.design.component.TrenifyStatusTone
import it.danielebufarini.trenify.design.theme.TrenifyShapes
import it.danielebufarini.trenify.design.theme.TrenifySpacing
import it.danielebufarini.trenify.design.theme.TrenifyTheme
import it.danielebufarini.trenify.presentation.railwayDateTime

/** Android owns rendering only. The facade is cached by the shared shell's live component identity. */
@Composable
fun NativeAlertsEntry(entry: NativeShellEntry) {
    val facade = entry.alerts ?: return
    val state by facade.state.collectAsStateWithLifecycle()
    if (entry.destination == it.danielebufarini.trenify.app.NativeDestination.StrikeDetail) {
        StrikeDetailContent(
            state = state,
            strikeId = entry.alertStrikeId,
            openReference = facade::openReference,
            refresh = facade::refresh,
        )
    } else {
        AlertsOverviewContent(
            state = state,
            open = facade::open,
            refresh = facade::refresh,
            toggleNotifications = facade::toggleNotifications,
            openReference = facade::openReference,
        )
    }
}

@Composable
fun AlertsOverviewContent(
    state: NativeAlertsState,
    open: (String) -> Unit,
    refresh: () -> Unit,
    toggleNotifications: () -> Unit,
    openReference: (String) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = TrenifySpacing.screenHorizontal).testTag("native-alerts"),
        verticalArrangement = Arrangement.spacedBy(TrenifySpacing.m),
    ) {
        item {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.alerts_notifications), Modifier.weight(1f))
                Switch(
                    checked = state.notificationsEnabled,
                    onCheckedChange = { toggleNotifications() },
                    modifier = Modifier.testTag("alerts-notifications"),
                )
            }
            if (state.notificationPermissionDenied) {
                Text(
                    stringResource(R.string.alerts_permission_missing),
                    Modifier.testTag("alerts-permission-missing"),
                    style = TrenifyTheme.typography.caption,
                    color = TrenifyTheme.colors.statusWarning,
                )
            }
        }
        item {
            TextButton(refresh, Modifier.heightIn(min = 48.dp).testTag("alerts-refresh")) {
                Text(stringResource(R.string.st_refresh))
            }
        }
        item { AlertsFreshness(state, refresh) }
        if (state.visibleEmpty) {
            // Empty is distinct from failure: the failure banner above stays
            // visible while this carries the empty upcoming-alert wording.
            item { Text(stringResource(R.string.alerts_empty), Modifier.testTag("alerts-empty")) }
        } else {
            items(state.strikes, key = { it.strikeId }) { row ->
                StrikeCard(row, { open(row.strikeId) }, openReference)
            }
        }
        item { Spacer(Modifier.height(TrenifySpacing.l)) }
    }
}

@Composable
fun StrikeDetailContent(
    state: NativeAlertsState,
    strikeId: String?,
    openReference: (String) -> Unit,
    refresh: () -> Unit,
) {
    val row = strikeId?.let { id -> state.allStrikes.firstOrNull { it.strikeId == id } }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = TrenifySpacing.screenHorizontal).testTag("native-strike-detail"),
        verticalArrangement = Arrangement.spacedBy(TrenifySpacing.m),
    ) {
        item { AlertsFreshness(state, refresh) }
        if (row == null) {
            item { Text(stringResource(R.string.alerts_not_found), Modifier.testTag("strike-detail-not-found")) }
        } else {
            item { StrikeSummaryBlock(row, "strike-detail") }
            item { StrikeScopeBlock(row, "strike-detail") }
            item { StrikeReferenceBlock(row, state.referenceInProgress, state.referenceFailed, openReference, "strike-detail") }
        }
        item { Spacer(Modifier.height(TrenifySpacing.l)) }
    }
}

@Composable
private fun AlertsFreshness(state: NativeAlertsState, refresh: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(TrenifySpacing.xs)) {
        if (state.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("alerts-loading"))
            Text(stringResource(R.string.st_loading), style = TrenifyTheme.typography.caption)
        }
        val observation = state.observation
        when (observation.freshness) {
            NativeRealtimeFreshness.Stale -> Text(
                stringResource(R.string.st_stale),
                Modifier.testTag("alerts-stale"),
                style = TrenifyTheme.typography.status,
                color = TrenifyTheme.colors.statusWarning,
            )
            NativeRealtimeFreshness.Unknown -> if (!state.loading && observation.failure == null) {
                Text(
                    stringResource(R.string.st_unknownFreshness),
                    Modifier.testTag("alerts-unknown"),
                    style = TrenifyTheme.typography.caption,
                )
            }
            NativeRealtimeFreshness.Fresh -> Unit
        }
        // A provider/source failure is never presented as fresh operator
        // confirmation: the failure banner renders while retained cards stay.
        observation.failure?.let { failure ->
            Text(
                stringResource(
                    when (failure) {
                        DomainFailure.OFFLINE -> R.string.st_offline
                        DomainFailure.TEMPORARY -> R.string.st_temporary
                        DomainFailure.UNSUPPORTED -> R.string.st_unsupported
                        DomainFailure.NOT_FOUND -> R.string.st_realtimeUnavailable
                        else -> R.string.st_invalid
                    },
                ),
                Modifier.testTag("alerts-error"),
                style = TrenifyTheme.typography.bodyEmphasized,
            )
            TextButton(refresh, Modifier.heightIn(min = 48.dp).testTag("alerts-retry")) {
                Text(stringResource(R.string.st_retry))
            }
        }
        observation.provenance.fetchedAtEpochSeconds?.let {
            Text(
                "${stringResource(R.string.st_fetched)} · ${railwayDateTime(it)}",
                Modifier.testTag("alerts-fetched"),
                style = TrenifyTheme.typography.caption,
            )
        }
        observation.provenance.sourceTimestampEpochSeconds?.let {
            Text(
                "${stringResource(R.string.st_sourceUpdate)} · ${railwayDateTime(it)}",
                Modifier.testTag("alerts-source-update"),
                style = TrenifyTheme.typography.caption,
            )
        }
    }
}

@Composable
private fun StrikeCard(row: NativeStrikeRow, open: () -> Unit, openReference: (String) -> Unit) {
    Surface(
        Modifier.fillMaxWidth().testTag("strike-${row.strikeId}").clickable(onClick = open),
        shape = TrenifyShapes.card,
        color = TrenifyTheme.colors.surfaceRaised,
    ) {
        Column(Modifier.padding(TrenifySpacing.m), verticalArrangement = Arrangement.spacedBy(TrenifySpacing.s)) {
            StrikeSummaryBlock(row, "strike")
            StrikeScopeBlock(row, "strike", compact = true)
            // Reference affordance truthfully reflects availability; the card
            // itself navigates to detail for the full reference actions.
            if (!row.canOpenReference) {
                Text(
                    stringResource(R.string.alerts_reference_unavailable),
                    Modifier.testTag("strike-reference-unavailable-${row.strikeId}"),
                    style = TrenifyTheme.typography.caption,
                )
            }
        }
    }
}

/**
 * Explicit status semantics: every card carries a text pill plus a status
 * line with an icon-equivalent symbol, so revocation/cancellation is never
 * color-only.
 */
@Composable
private fun StrikeSummaryBlock(row: NativeStrikeRow, tagPrefix: String) {
    Column(verticalArrangement = Arrangement.spacedBy(TrenifySpacing.xs)) {
        Text(row.sector, style = TrenifyTheme.typography.bodyEmphasized, color = TrenifyTheme.colors.textPrimary)
        TrenifyStatusPill(
            tone = strikeTone(row.status),
            label = strikeStatusLabel(row.status),
            modifier = Modifier.testTag("$tagPrefix-status-pill-${row.strikeId}"),
        )
        // Redundant explicit status text with a non-color marker for
        // screen readers and monochrome rendering.
        Text(
            "${strikeStatusSymbol(row.status)} ${strikeStatusLabel(row.status)}",
            Modifier.testTag("$tagPrefix-status-${row.strikeId}"),
            style = TrenifyTheme.typography.status,
        )
        Text(
            stringResource(R.string.alerts_interval, railwayDateTime(row.startEpochSeconds), railwayDateTime(row.endEpochSeconds)),
            Modifier.testTag("$tagPrefix-interval-${row.strikeId}"),
            style = TrenifyTheme.typography.metadata,
        )
        Text(
            stringResource(
                R.string.alerts_area,
                strikeAreaLabel(row).ifBlank { stringResource(R.string.alerts_relevance_unknown) },
            ),
            Modifier.testTag("$tagPrefix-area-${row.strikeId}"),
            style = TrenifyTheme.typography.metadata,
        )
        Text(
            stringResource(if (row.railwayRelevant) R.string.alerts_railway_relevant else R.string.alerts_not_railway_relevant),
            Modifier.testTag("$tagPrefix-railway-${row.strikeId}"),
            style = TrenifyTheme.typography.caption,
        )
    }
}

@Composable
private fun StrikeScopeBlock(row: NativeStrikeRow, tagPrefix: String, compact: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(TrenifySpacing.xs)) {
        Text(
            strikeRelevanceLabel(row.relevance),
            Modifier.testTag("$tagPrefix-relevance-${row.strikeId}"),
            style = TrenifyTheme.typography.metadata,
        )
        if (row.operators.isNotEmpty()) {
            Text(
                stringResource(R.string.alerts_operators, row.operators.joinToString()),
                Modifier.testTag("$tagPrefix-operators-${row.strikeId}"),
                style = TrenifyTheme.typography.metadata,
            )
        }
        if (compact) return
        Text(
            stringResource(R.string.alerts_mode, row.mode),
            Modifier.testTag("$tagPrefix-mode-${row.strikeId}"),
            style = TrenifyTheme.typography.metadata,
        )
        if (row.unions.isNotEmpty()) {
            Text(
                stringResource(R.string.alerts_unions, row.unions.joinToString()),
                Modifier.testTag("$tagPrefix-unions-${row.strikeId}"),
                style = TrenifyTheme.typography.metadata,
            )
        }
        row.workforce?.let {
            Text(
                stringResource(R.string.alerts_workforce, it),
                Modifier.testTag("$tagPrefix-workforce-${row.strikeId}"),
                style = TrenifyTheme.typography.metadata,
            )
        }
        row.notes?.let {
            Text(
                stringResource(R.string.alerts_notes, it),
                Modifier.testTag("$tagPrefix-notes-${row.strikeId}"),
                style = TrenifyTheme.typography.metadata,
            )
        }
        Text(
            stringResource(R.string.alerts_source, listOf(row.sourceLabel, row.sourceUrl).joinToString(" · ")),
            Modifier.testTag("$tagPrefix-source-${row.strikeId}"),
            style = TrenifyTheme.typography.metadata,
        )
    }
}

@Composable
private fun StrikeReferenceBlock(
    row: NativeStrikeRow,
    inProgress: Boolean,
    failed: Boolean,
    openReference: (String) -> Unit,
    tagPrefix: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TrenifySpacing.s)) {
        if (row.canOpenReference) {
            TextButton(
                onClick = { openReference(row.sourceUrl) },
                enabled = !inProgress,
                modifier = Modifier.heightIn(min = 48.dp).testTag("$tagPrefix-reference-open"),
            ) {
                Text(stringResource(R.string.alerts_reference_open))
            }
            if (inProgress) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("$tagPrefix-reference-loading"))
            }
        } else {
            Text(
                stringResource(R.string.alerts_reference_unavailable),
                Modifier.testTag("$tagPrefix-reference-unavailable"),
                style = TrenifyTheme.typography.bodyEmphasized,
            )
        }
        if (failed) {
            Text(
                stringResource(R.string.alerts_reference_failed),
                Modifier.testTag("$tagPrefix-reference-failed"),
                color = TrenifyTheme.colors.statusCancelled,
            )
        }
        // No guaranteed-service data exists in current provider data; the
        // absence is stated honestly instead of fabricated.
        Text(
            stringResource(R.string.alerts_guaranteed_unavailable),
            Modifier.testTag("$tagPrefix-guaranteed-unavailable"),
            style = TrenifyTheme.typography.caption,
        )
    }
}

private fun strikeTone(status: StrikeStatus): TrenifyStatusTone = when (status) {
    StrikeStatus.SCHEDULED -> TrenifyStatusTone.Warning
    StrikeStatus.MODIFIED -> TrenifyStatusTone.Delayed
    StrikeStatus.REVOKED -> TrenifyStatusTone.Cancelled
    StrikeStatus.COMPLETED -> TrenifyStatusTone.Arrived
}

private fun strikeStatusSymbol(status: StrikeStatus): String = when (status) {
    StrikeStatus.SCHEDULED -> "●"
    StrikeStatus.MODIFIED -> "◆"
    StrikeStatus.REVOKED -> "×"
    StrikeStatus.COMPLETED -> "■"
}

@Composable
private fun strikeStatusLabel(status: StrikeStatus): String = stringResource(
    when (status) {
        StrikeStatus.SCHEDULED -> R.string.alerts_status_scheduled
        StrikeStatus.MODIFIED -> R.string.alerts_status_modified
        StrikeStatus.REVOKED -> R.string.alerts_status_revoked
        StrikeStatus.COMPLETED -> R.string.alerts_status_completed
    },
)

@Composable
private fun strikeRelevanceLabel(relevance: StrikeRelevance): String = stringResource(
    when (relevance) {
        StrikeRelevance.LOCAL -> R.string.alerts_relevance_local
        StrikeRelevance.PROVINCIAL -> R.string.alerts_relevance_provincial
        StrikeRelevance.REGIONAL -> R.string.alerts_relevance_regional
        StrikeRelevance.INTERREGIONAL -> R.string.alerts_relevance_interregional
        StrikeRelevance.NATIONAL -> R.string.alerts_relevance_national
        StrikeRelevance.UNKNOWN -> R.string.alerts_relevance_unknown
    },
)

private fun strikeAreaLabel(row: NativeStrikeRow): String =
    (row.regions + row.provinces).joinToString()
