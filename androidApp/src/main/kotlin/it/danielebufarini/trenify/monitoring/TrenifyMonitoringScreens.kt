package it.danielebufarini.trenify.monitoring

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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.danielebufarini.trenify.R
import it.danielebufarini.trenify.app.NativeMonitorCard
import it.danielebufarini.trenify.app.NativeMonitoringState
import it.danielebufarini.trenify.app.NativeRealtimeFreshness
import it.danielebufarini.trenify.app.NativeShellEntry
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.design.component.TrenifyStatusPill
import it.danielebufarini.trenify.design.theme.TrenifyShapes
import it.danielebufarini.trenify.design.theme.TrenifySpacing
import it.danielebufarini.trenify.design.theme.TrenifyTheme
import it.danielebufarini.trenify.presentation.RailwayStrikeBlock
import it.danielebufarini.trenify.presentation.railwayCategoryLabel
import it.danielebufarini.trenify.presentation.railwayDateTime
import it.danielebufarini.trenify.presentation.railwayServiceDateLabel
import it.danielebufarini.trenify.presentation.railwayStatusTone

/** Android owns rendering only. The facade is cached by the shared shell's live component identity. */
@Composable
fun NativeMonitoringEntry(entry: NativeShellEntry) {
    val facade = entry.monitoring ?: return
    val state by facade.state.collectAsStateWithLifecycle()
    MonitoringContent(state, facade::open, facade::stop, facade::removeEnded,
        facade::setMonitorNotifications, facade::retryMonitorNotifications)
}

@Composable
fun MonitoringContent(
    state: NativeMonitoringState,
    open: (String) -> Unit,
    stop: (String) -> Unit,
    removeEnded: (String) -> Unit,
    setNotifications: (String, Boolean) -> Unit,
    retryNotifications: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = TrenifySpacing.screenHorizontal).testTag("native-monitoring"),
        verticalArrangement = Arrangement.spacedBy(TrenifySpacing.m),
    ) {
        item {
            Text(stringResource(R.string.mon_bestEffort), Modifier.testTag("monitoring-best-effort"),
                style = TrenifyTheme.typography.caption)
        }
        if (state.loading) {
            item {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("monitoring-loading"))
                Text(stringResource(R.string.st_loading))
            }
        } else {
            if (state.active.isEmpty() && state.ended.isEmpty()) {
                item { Text(stringResource(R.string.mon_empty), Modifier.testTag("monitoring-empty")) }
            }
            if (state.active.isNotEmpty()) {
                item {
                    Text(stringResource(R.string.mon_active),
                        Modifier.testTag("monitoring-active-header").semantics { heading() },
                        style = TrenifyTheme.typography.sectionTitle, color = TrenifyTheme.colors.textPrimary)
                }
            } else if (state.ended.isNotEmpty()) {
                item { Text(stringResource(R.string.mon_noActive), Modifier.testTag("monitoring-no-active")) }
            }
            items(state.active, key = { it.trainRunKey }) { card ->
                ActiveMonitorCard(card, { open(card.trainRunKey) }, { stop(card.trainRunKey) },
                    { setNotifications(card.trainRunKey, it) }, retryNotifications)
            }
            if (state.ended.isNotEmpty()) {
                item {
                    Text(stringResource(R.string.mon_recentlyEnded),
                        Modifier.testTag("monitoring-recently-ended-header").semantics { heading() },
                        style = TrenifyTheme.typography.sectionTitle, color = TrenifyTheme.colors.textPrimary)
                }
            }
            items(state.ended, key = { "ended-${it.trainRunKey}" }) { card ->
                EndedMonitorCard(card, { open(card.trainRunKey) }, { removeEnded(card.trainRunKey) })
            }
        }
        item { Spacer(Modifier.height(TrenifySpacing.l)) }
    }
}

@Composable
private fun MonitorSummary(card: NativeMonitorCard, tagPrefix: String) {
    Column(verticalArrangement = Arrangement.spacedBy(TrenifySpacing.s)) {
        Text("${card.category?.code.orEmpty()} ${card.identity.number}".trim(),
            Modifier.testTag("$tagPrefix-identity-${card.trainRunKey}"),
            style = TrenifyTheme.typography.trainIdentity)
        card.category?.let {
            Text(railwayCategoryLabel(it), style = TrenifyTheme.typography.caption)
        }
        Text("${stringResource(R.string.st_serviceDate)} · ${railwayServiceDateLabel(card.identity.serviceDate.toString())}",
            Modifier.testTag("$tagPrefix-service-date-${card.trainRunKey}"),
            style = TrenifyTheme.typography.metadata)
        val statusLabel = stringResource(when (card.status) {
            TrainStatus.NOT_DEPARTED -> R.string.st_notDeparted; TrainStatus.RUNNING -> R.string.st_running
            TrainStatus.ARRIVED -> R.string.st_arrived; TrainStatus.CANCELLED -> R.string.st_cancelled
            TrainStatus.PARTIALLY_CANCELLED -> R.string.st_partiallyCancelled; TrainStatus.DIVERTED -> R.string.st_diverted
            TrainStatus.RESCHEDULED -> R.string.st_rescheduled; TrainStatus.UNKNOWN -> R.string.st_unknown
        })
        val delay = card.delayMinutes
        val presentation = if (delay != null && delay > 0) "$statusLabel · +$delay ${stringResource(R.string.st_minutes)}" else statusLabel
        TrenifyStatusPill(railwayStatusTone(card.status, card.delayMinutes), presentation,
            Modifier.testTag("$tagPrefix-status-${card.trainRunKey}"))
        if (!card.hasSnapshot) {
            Text(stringResource(R.string.mon_noSnapshot), Modifier.testTag("$tagPrefix-no-snapshot-${card.trainRunKey}"),
                style = TrenifyTheme.typography.metadata)
        } else {
            Text("${card.originName ?: "—"} → ${card.destinationName ?: "—"}",
                style = TrenifyTheme.typography.bodyEmphasized)
            card.operatorName?.let {
                Text("${stringResource(R.string.st_operator)} · $it", style = TrenifyTheme.typography.metadata)
            }
            card.nextStopName?.let {
                Text("${stringResource(R.string.mon_nextStop)} · $it",
                    Modifier.testTag("$tagPrefix-next-stop-${card.trainRunKey}"))
            }
            card.scheduledDepartureEpochSeconds?.let {
                Text("${stringResource(R.string.st_departure)} · ${stringResource(R.string.st_scheduled)}: ${railwayDateTime(it)}",
                    Modifier.testTag("$tagPrefix-departure-${card.trainRunKey}"),
                    style = TrenifyTheme.typography.metadata)
            }
            card.scheduledArrivalEpochSeconds?.let {
                Text("${stringResource(R.string.st_arrival)} · ${stringResource(R.string.st_scheduled)}: ${railwayDateTime(it)}",
                    Modifier.testTag("$tagPrefix-arrival-${card.trainRunKey}"),
                    style = TrenifyTheme.typography.metadata)
            }
            if (card.scheduledPlatform != null || card.actualPlatform != null) {
                Text("${stringResource(R.string.st_platform)} · ${stringResource(R.string.st_scheduled)}: ${card.scheduledPlatform ?: "—"} · ${stringResource(R.string.st_actual)}: ${card.actualPlatform ?: "—"}",
                    Modifier.testTag("$tagPrefix-platform-${card.trainRunKey}"),
                    style = TrenifyTheme.typography.metadata)
            }
            card.evaluatedAtEpochSeconds?.let {
                Text("${stringResource(R.string.mon_lastChecked)} · ${railwayDateTime(it)}",
                    Modifier.testTag("$tagPrefix-last-checked-${card.trainRunKey}"),
                    style = TrenifyTheme.typography.metadata)
            }
        }
        MonitorProvenance(card, tagPrefix)
        if (card.observation.provenance.degraded && card.hasSnapshot) {
            Text(stringResource(R.string.st_degraded), Modifier.testTag("$tagPrefix-degraded-${card.trainRunKey}"),
                style = TrenifyTheme.typography.caption)
        }
        card.refreshFailure?.let { failure ->
            Text(stringResource(when (failure) {
                DomainFailure.OFFLINE -> R.string.st_offline; DomainFailure.TEMPORARY -> R.string.st_temporary
                DomainFailure.NOT_FOUND -> R.string.st_realtimeUnavailable
                DomainFailure.UNSUPPORTED -> R.string.st_unsupported; else -> R.string.st_invalid
            }), Modifier.testTag("$tagPrefix-error-${card.trainRunKey}"))
        }
        if (card.warnings.isNotEmpty() || card.strikesStale || card.strikesUnknown) {
            RailwayStrikeBlock(card.warnings, card.strikesStale, false, card.strikesUnknown, tagPrefix)
        }
    }
}

@Composable
private fun MonitorProvenance(card: NativeMonitorCard, tagPrefix: String) {
    Column(verticalArrangement = Arrangement.spacedBy(TrenifySpacing.xs)) {
        if (card.hasSnapshot) {
            Text(stringResource(when (card.observation.freshness) {
                NativeRealtimeFreshness.Fresh -> R.string.st_fresh
                NativeRealtimeFreshness.Stale -> R.string.st_stale
                NativeRealtimeFreshness.Unknown -> R.string.st_unknownFreshness
            }), Modifier.testTag("$tagPrefix-freshness-${card.trainRunKey}"), style = TrenifyTheme.typography.caption)
        }
        card.observation.provenance.providerName?.let {
            Text("${stringResource(R.string.st_source)} · $it",
                Modifier.testTag("$tagPrefix-source-${card.trainRunKey}"), style = TrenifyTheme.typography.caption)
        }
        card.observation.provenance.fetchedAtEpochSeconds?.let {
            Text("${stringResource(R.string.st_fetched)} · ${railwayDateTime(it)}",
                Modifier.testTag("$tagPrefix-fetched-${card.trainRunKey}"), style = TrenifyTheme.typography.caption)
        }
        card.observation.provenance.sourceTimestampEpochSeconds?.let {
            Text("${stringResource(R.string.st_sourceUpdate)} · ${railwayDateTime(it)}",
                Modifier.testTag("$tagPrefix-source-update-${card.trainRunKey}"), style = TrenifyTheme.typography.caption)
        }
    }
}

@Composable
private fun ActiveMonitorCard(
    card: NativeMonitorCard,
    open: () -> Unit,
    stop: () -> Unit,
    setNotifications: (Boolean) -> Unit,
    retryNotifications: () -> Unit,
) {
    Surface(Modifier.fillMaxWidth().testTag("monitor-${card.trainRunKey}").clickable(onClick = open),
        shape = TrenifyShapes.card, color = TrenifyTheme.colors.surfaceRaised) {
        Column(Modifier.padding(TrenifySpacing.m), verticalArrangement = Arrangement.spacedBy(TrenifySpacing.s)) {
            MonitorSummary(card, "monitor")
            if (card.canToggleNotifications) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.st_notifications), Modifier.weight(1f))
                    Switch(card.notificationsEnabled, setNotifications,
                        enabled = !card.notificationsPending,
                        modifier = Modifier.testTag("monitor-notifications-${card.trainRunKey}"))
                }
                if (card.notificationsFailed) {
                    Text(stringResource(R.string.mon_notificationsError),
                        Modifier.testTag("monitor-notifications-error-${card.trainRunKey}"),
                        color = TrenifyTheme.colors.statusCancelled)
                    TextButton(retryNotifications, Modifier.testTag("monitor-notifications-retry-${card.trainRunKey}")) {
                        Text(stringResource(R.string.st_retry))
                    }
                }
            }
            if (card.canStop) {
                TextButton(stop, Modifier.heightIn(min = 48.dp).testTag("monitor-stop-${card.trainRunKey}")) {
                    Text(stringResource(R.string.st_stopMonitoring))
                }
            }
        }
    }
}

@Composable
private fun EndedMonitorCard(card: NativeMonitorCard, open: () -> Unit, remove: () -> Unit) {
    Surface(Modifier.fillMaxWidth().testTag("monitor-ended-${card.trainRunKey}").clickable(onClick = open),
        shape = TrenifyShapes.card, color = TrenifyTheme.colors.surfaceMuted) {
        Column(Modifier.padding(TrenifySpacing.m), verticalArrangement = Arrangement.spacedBy(TrenifySpacing.s)) {
            Text(stringResource(R.string.st_ended), Modifier.testTag("monitor-ended-label-${card.trainRunKey}"),
                style = TrenifyTheme.typography.status)
            MonitorSummary(card, "monitor-ended")
            card.endedAtEpochSeconds?.let {
                Text("${stringResource(R.string.mon_endedAt)} · ${railwayDateTime(it)}",
                    Modifier.testTag("monitor-ended-at-${card.trainRunKey}"),
                    style = TrenifyTheme.typography.metadata)
            }
            if (card.canRemove) {
                TextButton(remove, Modifier.heightIn(min = 48.dp).testTag("monitor-remove-${card.trainRunKey}")) {
                    Text(stringResource(R.string.st_removeMonitor))
                }
            }
        }
    }
}
