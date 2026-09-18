package it.danielebufarini.trenify.stationtrain

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.danielebufarini.trenify.R
import it.danielebufarini.trenify.app.*
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.domain.MonitorEventKind
import it.danielebufarini.trenify.core.model.*
import it.danielebufarini.trenify.design.component.*
import it.danielebufarini.trenify.design.theme.*
import it.danielebufarini.trenify.presentation.RailwayStrikeBlock
import it.danielebufarini.trenify.presentation.railwayCategoryLabel
import it.danielebufarini.trenify.presentation.railwayDateTime
import it.danielebufarini.trenify.presentation.railwayServiceDateLabel
import it.danielebufarini.trenify.presentation.railwayStatusTone

/** Android owns rendering only. Facades are cached by the shared shell's live component identity. */
@Composable
fun NativeStationTrainEntry(entry: NativeShellEntry) {
    entry.stationSearch?.let { facade ->
        val state by facade.state.collectAsStateWithLifecycle()
        StationSearchContent(state, facade::editQuery, facade::selectStation, facade::toggleFavorite,
            facade::removeRecent, facade::clearRecent, facade::retry, facade::searchTrains)
    }
    entry.stationBoard?.let { facade ->
        val state by facade.state.collectAsStateWithLifecycle()
        StationBoardContent(state, facade::setDirection, facade::refresh, facade::toggleFavorite, facade::openTrain)
    }
    entry.trainSearch?.let { facade ->
        val state by facade.state.collectAsStateWithLifecycle()
        TrainSearchContent(state, facade::editNumber, facade::submit, facade::selectRun)
    }
    entry.trainDetail?.let { facade ->
        val state by facade.state.collectAsStateWithLifecycle()
        TrainDetailContent(state, facade::refresh, facade::toggleFavorite, facade::toggleMonitoring,
            facade::removeEndedMonitor, facade::setMonitorNotifications, facade::editMonitorThreshold,
            facade::saveMonitorThreshold, facade::setMonitorEvent, facade::retryMonitorPreferences)
    }
}

@Composable
private fun Heading(text: String, tag: String) = Text(text, Modifier.testTag(tag).semantics { heading() },
    style = TrenifyTheme.typography.sectionTitle, color = TrenifyTheme.colors.textPrimary)

private val screenModifier: Modifier get() = Modifier.fillMaxSize().padding(horizontal = TrenifySpacing.screenHorizontal)

@Composable
fun StationSearchContent(
    state: NativeStationSearchState, edit: (String) -> Unit, select: (String) -> Unit,
    favorite: (String) -> Unit, removeRecent: (String) -> Unit, clearRecent: () -> Unit,
    retry: () -> Unit, searchTrains: () -> Unit,
) {
    LazyColumn(screenModifier.testTag("native-station-search"), verticalArrangement = Arrangement.spacedBy(TrenifySpacing.m)) {
        item {
            OutlinedTextField(state.query, edit, Modifier.fillMaxWidth().dpadExitSingleLine().testTag("station-query"),
                label = { Text(stringResource(R.string.st_query)) }, singleLine = true,
                trailingIcon = { if (state.query.isNotEmpty()) TextButton({ edit("") }) { Text(stringResource(R.string.st_clearQuery)) } },
                shape = TrenifyShapes.control)
            TextButton(searchTrains, Modifier.heightIn(min = 48.dp).testTag("station-train-search")) { Text(stringResource(R.string.st_trainSearch)) }
        }
        if (state.query.isBlank()) {
            item {
                Heading(stringResource(R.string.st_recent), "recent-title")
                // A recency failure is an overlay: cached rows below remain visible and actionable.
                if (state.recencyFailed) Text(stringResource(R.string.st_recencyError), Modifier.testTag("recent-error"))
                if (state.recent.isEmpty()) Text(stringResource(R.string.st_recentEmpty), Modifier.testTag("recent-empty"))
                else TextButton(clearRecent, Modifier.testTag("recent-clear")) { Text(stringResource(R.string.st_clearRecent)) }
            }
            items(state.recent, key = { it.stationId }) { row ->
                StationRow(row, { select(row.stationId) }, null, { removeRecent(row.stationId) })
            }
        } else {
            item { Observation(state.observation, "station-search", retry) }
            if (state.observation.empty) item { Text(stringResource(R.string.st_empty), Modifier.testTag("station-search-empty")) }
            items(state.results, key = { it.stationId }) { row ->
                StationRow(row, { select(row.stationId) }, { favorite(row.stationId) })
            }
        }
        item { Spacer(Modifier.height(TrenifySpacing.l)) }
    }
}

@Composable
private fun StationRow(row: NativeStationRow, select: () -> Unit, favorite: (() -> Unit)?, remove: (() -> Unit)? = null) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(select, Modifier.weight(1f).heightIn(min = 48.dp).testTag("station-${row.stationId}")) {
                Text(row.name, style = TrenifyTheme.typography.routeStation)
            }
            remove?.let { TextButton(it, Modifier.testTag("recent-remove-${row.stationId}")) { Text(stringResource(R.string.st_removeRecent)) } }
        }
        favorite?.let {
            TextButton(it, Modifier.testTag("station-favorite-${row.stationId}"), enabled = !row.pending) {
                Text(stringResource(if (row.favorite) R.string.st_removeStation else R.string.st_saveStation))
            }
        }
        if (row.failed) Text(stringResource(R.string.st_favoriteError), Modifier.testTag("station-favorite-error-${row.stationId}"), color = TrenifyTheme.colors.statusCancelled)
        HorizontalDivider(color = TrenifyTheme.colors.divider)
    }
}

@Composable
fun StationBoardContent(state: NativeStationBoardState, direction: (BoardKind) -> Unit,
    refresh: () -> Unit, favorite: () -> Unit, openTrain: (String) -> Unit,
) {
    LazyColumn(screenModifier.testTag("native-station-board"), verticalArrangement = Arrangement.spacedBy(TrenifySpacing.m)) {
        item {
            state.station?.let { station ->
                Text(station.name, Modifier.semantics { heading() }, style = TrenifyTheme.typography.screenTitle)
                TextButton(favorite, Modifier.testTag("station-favorite-${state.stationId}"), enabled = !station.pending) {
                    Text(stringResource(if (station.favorite) R.string.st_removeStation else R.string.st_saveStation))
                }
                if (station.failed) Text(stringResource(R.string.st_favoriteError), Modifier.testTag("station-favorite-error-${state.stationId}"))
                Row(horizontalArrangement = Arrangement.spacedBy(TrenifySpacing.s)) {
                    BoardKind.entries.forEach { kind ->
                        FilterChip(state.direction == kind, { direction(kind) }, modifier = Modifier.testTag("board-chip-${kind.name.lowercase()}"),
                            label = { Text(stringResource(if (kind == BoardKind.DEPARTURES) R.string.st_departures else R.string.st_arrivals)) })
                    }
                }
                TextButton(refresh, Modifier.testTag("board-refresh")) { Text(stringResource(R.string.st_refresh)) }
                Observation(state.observation, "board", refresh)
            } ?: Text(stringResource(if (state.unavailable) R.string.st_stationUnavailable else R.string.st_loading), Modifier.testTag("station-board-unavailable"))
        }
        if (state.station != null && (state.observation.empty || (!state.observation.hasContent && !state.observation.loading && state.observation.failure == null))) {
            item { Text(stringResource(R.string.st_empty), Modifier.testTag("board-empty")) }
        }
        items(state.trains, key = { it.identity.key }) { train ->
            TrainRowContent(train, state.direction, { openTrain(train.identity.key) })
        }
        item { Spacer(Modifier.height(TrenifySpacing.l)) }
    }
}

@Composable
fun TrainSearchContent(state: NativeTrainSearchState, edit: (String) -> Unit, submit: () -> Unit, select: (String) -> Unit) {
    LazyColumn(screenModifier.testTag("native-train-search"), verticalArrangement = Arrangement.spacedBy(TrenifySpacing.m)) {
        item {
            Text("${stringResource(R.string.st_serviceDate)} · ${railwayServiceDateLabel(state.serviceDate)}", Modifier.testTag("train-service-date"))
            state.expectedOriginName?.let { Text("${stringResource(R.string.st_from)} · $it", Modifier.testTag("train-expected-origin")) }
            state.expectedOriginId?.takeIf { state.expectedOriginName == null }?.let { Text(stringResource(R.string.st_originFilter)) }
            state.expectedOperatorName?.let { Text("${stringResource(R.string.st_operator)} · $it", Modifier.testTag("train-expected-operator")) }
            OutlinedTextField(state.number, edit, Modifier.fillMaxWidth().dpadExitSingleLine().testTag("train-number"),
                label = { Text(stringResource(R.string.st_trainNumber)) }, singleLine = true, isError = state.invalidNumber,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = TrenifyShapes.control)
            if (state.invalidNumber) Text(stringResource(R.string.st_invalidNumber), Modifier.testTag("train-invalid-number"))
            TrenifyPrimaryAction(stringResource(R.string.st_search), submit, Modifier.fillMaxWidth().testTag("train-submit"), enabled = !state.observation.loading)
            Observation(state.observation, "train-search", submit)
            if (state.noCompatibleService) Text(stringResource(R.string.st_noCompatible), Modifier.testTag("train-no-compatible-service"))
            else if (state.observation.empty && !state.observation.loading && state.observation.failure == null) Text(stringResource(R.string.st_notFound), Modifier.testTag("train-not-found"))
            if (state.runs.isNotEmpty()) Heading(stringResource(R.string.st_selectRun), "native-train-run-picker")
        }
        items(state.runs, key = { it.identity.key }) { run -> TrainRowContent(run, null, { select(run.identity.key) }) }
        item { Spacer(Modifier.height(TrenifySpacing.l)) }
    }
}

@Composable
private fun TrainRowContent(train: NativeTrainRow, direction: BoardKind?, select: (() -> Unit)?, detail: Boolean = false) {
    val modifier = Modifier.fillMaxWidth().testTag("run-${train.identity.key}")
    Surface(if (select == null) modifier else modifier.clickable(onClick = select),
        shape = TrenifyShapes.card, color = TrenifyTheme.colors.surfaceRaised) {
        Column(Modifier.padding(TrenifySpacing.m), verticalArrangement = Arrangement.spacedBy(TrenifySpacing.s)) {
            Text("${train.category?.code.orEmpty()} ${train.identity.number}".trim(), style = TrenifyTheme.typography.trainIdentity)
            Status(train.status, train.delayMinutes, "train-status-${train.identity.key}")
            Text(if (direction == BoardKind.ARRIVALS) "${stringResource(R.string.st_from)} · ${train.originName}"
                else "${train.originName} → ${train.destinationName ?: "—"}", style = if (detail) TrenifyTheme.typography.routeStation else TrenifyTheme.typography.bodyEmphasized)
            train.category?.let { Text(railwayCategoryLabel(it), style = TrenifyTheme.typography.caption) }
            Text("${stringResource(R.string.st_operator)} · ${train.operatorName ?: stringResource(R.string.st_unknown)}", style = TrenifyTheme.typography.metadata)
            Text("${stringResource(R.string.st_serviceDate)} · ${railwayServiceDateLabel(train.serviceDate)}", style = TrenifyTheme.typography.metadata)
            if (direction != null) EventTimes(if (direction == BoardKind.DEPARTURES) R.string.st_departure else R.string.st_arrival,
                train.eventEpochSeconds, null, "board-event-time-${train.identity.key}", includeActual = false)
            else {
                EventTimes(R.string.st_departure, train.scheduledDepartureEpochSeconds, null, "train-terminal-departure", includeActual = false)
                EventTimes(R.string.st_arrival, train.scheduledArrivalEpochSeconds, null, "train-terminal-arrival", includeActual = false)
            }
            if (!detail) Platform(train.scheduledPlatform, train.actualPlatform, "train-platform-${train.identity.key}")
            train.delayMinutes?.let { Text("${stringResource(R.string.st_delay)} · $it ${stringResource(R.string.st_minutes)}", Modifier.testTag("train-delay")) }
            if (!detail) Text("${stringResource(R.string.st_source)} · ${train.providerName ?: stringResource(R.string.st_unknown)}", Modifier.testTag("train-source-${train.identity.key}"), style = TrenifyTheme.typography.caption)
        }
    }
}

@Composable
fun TrainDetailContent(
    state: NativeTrainDetailState, refresh: () -> Unit, favorite: () -> Unit, monitor: () -> Unit, removeEnded: () -> Unit,
    notifications: (Boolean) -> Unit, threshold: (String) -> Unit, saveThreshold: () -> Unit,
    event: (MonitorEventKind, Boolean) -> Unit, retryPreferences: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    LazyColumn(screenModifier.testTag("native-train-detail"), verticalArrangement = Arrangement.spacedBy(TrenifySpacing.m)) {
        item { state.summary?.let { TrainRowContent(it, null, null, detail = true) } }
        item {
            Observation(state.observation, "train-detail", if (state.canRefresh) refresh else null, identifiedTrain = true,
                showTechnicalMetadata = false)
            if (state.favorite.available) {
                TrenifySecondaryAction(stringResource(if (state.favorite.favorite) R.string.st_removeTrain else R.string.st_saveTrain), favorite,
                    Modifier.fillMaxWidth().testTag("train-favorite"), enabled = !state.favorite.pending)
                if (state.favorite.failed) Text(stringResource(R.string.st_favoriteError), Modifier.testTag("train-favorite-error"), color = TrenifyTheme.colors.statusCancelled)
            }
            if (state.ended) {
                Text(stringResource(R.string.st_ended), Modifier.testTag("monitor-ended-label"))
                TextButton(removeEnded, Modifier.testTag("monitor-remove")) { Text(stringResource(R.string.st_removeMonitor)) }
            } else if (state.lifecycleResolved) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(refresh, Modifier.testTag("train-refresh")) { Text(stringResource(R.string.st_refresh)) }
                    TextButton(monitor, Modifier.weight(1f).testTag("monitor-toggle")) { Text(stringResource(if (state.monitored) R.string.st_stopMonitoring else R.string.st_startMonitoring)) }
                }
            }
            if (state.notificationPermissionDenied) Text(stringResource(R.string.st_permission))
        }
        item {
            if (state.positionStationName != null && state.positionObservedAtEpochSeconds != null) {
                Text("${stringResource(R.string.st_position)} · ${state.positionStationName}", Modifier.testTag("train-position"))
                Text("${stringResource(R.string.st_positionObserved)} · ${railTime(state.positionObservedAtEpochSeconds)}", Modifier.testTag("train-position-observed-at"))
            } else Text(stringResource(R.string.st_positionUnavailable), Modifier.testTag("train-position-unavailable"))
            Heading(stringResource(R.string.st_route), "train-detail-stops")
        }
        items(state.stops, key = { it.key }) { stop ->
            Row(Modifier.fillMaxWidth().testTag("stop-${stop.key}"), horizontalArrangement = Arrangement.spacedBy(TrenifySpacing.m)) {
                Text(when (stop.progress) { StopProgress.COMPLETED -> "✓"; StopProgress.NEXT -> "→"; StopProgress.CANCELLED -> "×"; StopProgress.FUTURE -> "○"; StopProgress.UNKNOWN -> "?" }, modifier = Modifier.clearAndSetSemantics {}, style = TrenifyTheme.typography.routeStation)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TrenifySpacing.s)) {
                    Text(stop.name, Modifier.semantics { heading() }, style = TrenifyTheme.typography.routeStation)
                    Text(stringResource(progressLabel(stop.progress)), Modifier.testTag("stop-progress-${stop.key}"), style = TrenifyTheme.typography.status)
                    EventTimes(R.string.st_arrival, stop.scheduledArrivalEpochSeconds, stop.actualArrivalEpochSeconds, "stop-arrival-${stop.key}")
                    EventTimes(R.string.st_departure, stop.scheduledDepartureEpochSeconds, stop.actualDepartureEpochSeconds, "stop-departure-${stop.key}")
                    Platform(stop.scheduledPlatform, stop.actualPlatform, "stop-platform-${stop.key}", stopScoped = true)
                    stop.delayMinutes?.let { Text("${stringResource(R.string.st_delay)} · $it ${stringResource(R.string.st_minutes)}") }
                    HorizontalDivider(color = TrenifyTheme.colors.divider)
                }
            }
        }
        if (state.warnings.isNotEmpty() || state.strikesStale || state.strikesUnknown) item {
            RailwayStrikeBlock(state.warnings, state.strikesStale, false, state.strikesUnknown, "train")
        }
        if (state.canEditMonitorPreferences) item {
            TextButton({ expanded = !expanded }, Modifier.testTag("monitor-preferences")) { Text(stringResource(R.string.st_monitorPreferences)) }
            if (expanded) MonitorPreferences(state, notifications, threshold, saveThreshold, event, retryPreferences)
        }
        item { Spacer(Modifier.height(TrenifySpacing.l)) }
    }
}

@Composable
private fun MonitorPreferences(state: NativeTrainDetailState, notifications: (Boolean) -> Unit,
    threshold: (String) -> Unit, save: () -> Unit, event: (MonitorEventKind, Boolean) -> Unit, retry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TrenifySpacing.s)) {
        Toggle(stringResource(R.string.st_notifications), state.monitorNotificationsEnabled == true, !state.monitorPreferencesPending, notifications, "monitor-notifications")
        OutlinedTextField(state.monitorThresholdText, threshold, Modifier.fillMaxWidth().dpadExitSingleLine().testTag("monitor-threshold"),
            label = { Text(stringResource(R.string.st_threshold)) }, isError = state.monitorThresholdInvalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        if (state.monitorThresholdInvalid) Text(stringResource(R.string.st_invalidThreshold), Modifier.testTag("monitor-threshold-error"))
        TextButton(save, Modifier.testTag("monitor-threshold-save"), enabled = !state.monitorPreferencesPending && !state.monitorThresholdInvalid) { Text(stringResource(R.string.st_save)) }
        listOf(Triple(MonitorEventKind.DELAY, R.string.st_notifyDelay, state.notifyDelay),
            Triple(MonitorEventKind.PLATFORM, R.string.st_notifyPlatform, state.notifyPlatform),
            Triple(MonitorEventKind.CANCELLATION, R.string.st_notifyCancellation, state.notifyCancellation),
            Triple(MonitorEventKind.DEPARTURE, R.string.st_notifyDeparture, state.notifyDeparture),
            Triple(MonitorEventKind.ARRIVAL, R.string.st_notifyArrival, state.notifyArrival)).forEach { (kind, label, checked) ->
                Toggle(stringResource(label), checked, !state.monitorPreferencesPending, { event(kind, it) }, "monitor-flag-${kind.name.lowercase()}")
            }
        if (state.monitorPreferencesFailed) {
            Text(stringResource(R.string.st_preferencesError), Modifier.testTag("monitor-prefs-error"))
            TextButton(retry, Modifier.testTag("monitor-prefs-retry")) { Text(stringResource(R.string.st_retry)) }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, change, enabled = enabled, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun EventTimes(event: Int, scheduled: Long?, actual: Long?, tag: String, includeActual: Boolean = true) {
    Column(Modifier.testTag(tag)) {
        Text("${stringResource(event)} · ${stringResource(R.string.st_scheduled)}: ${railTime(scheduled)}", style = TrenifyTheme.typography.bodyEmphasized)
        if (includeActual) Text("${stringResource(event)} · ${stringResource(R.string.st_actual)}: ${railTime(actual)}", style = TrenifyTheme.typography.metadata)
    }
}

@Composable
private fun Platform(scheduled: String?, actual: String?, tag: String, stopScoped: Boolean = false) {
    if (stopScoped) {
        if (scheduled == null && actual == null) return
        Column(Modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(TrenifySpacing.xs)) {
            scheduled?.let { Text(stringResource(R.string.st_expectedPlatform, it), Modifier.testTag("$tag-expected"), style = TrenifyTheme.typography.metadata) }
            actual?.let { Text(stringResource(R.string.st_actualPlatform, it), Modifier.testTag("$tag-actual"), style = TrenifyTheme.typography.metadata) }
        }
    } else {
        Text("${stringResource(R.string.st_platform)} · ${stringResource(R.string.st_scheduled)}: ${scheduled ?: "—"} · ${stringResource(R.string.st_actual)}: ${actual ?: "—"}", Modifier.testTag(tag), style = TrenifyTheme.typography.metadata)
    }
}

@Composable
private fun Status(status: TrainStatus, delay: Int?, tag: String) {
    val label = stringResource(when (status) {
        TrainStatus.NOT_DEPARTED -> R.string.st_notDeparted; TrainStatus.RUNNING -> R.string.st_running
        TrainStatus.ARRIVED -> R.string.st_arrived; TrainStatus.CANCELLED -> R.string.st_cancelled
        TrainStatus.PARTIALLY_CANCELLED -> R.string.st_partiallyCancelled; TrainStatus.DIVERTED -> R.string.st_diverted
        TrainStatus.RESCHEDULED -> R.string.st_rescheduled; TrainStatus.UNKNOWN -> R.string.st_unknown
    })
    val presentation = if (delay != null && delay > 0) "$label · +$delay ${stringResource(R.string.st_minutes)}" else label
    TrenifyStatusPill(railwayStatusTone(status, delay), presentation, Modifier.testTag(tag))
}

@Composable
private fun Observation(observation: NativeRealtimeObservation, tag: String, retry: (() -> Unit)?, identifiedTrain: Boolean = false,
    showTechnicalMetadata: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(TrenifySpacing.xs)) {
        if (observation.loading) { LinearProgressIndicator(Modifier.fillMaxWidth().testTag("$tag-loading")); Text(stringResource(R.string.st_loading)) }
        if (showTechnicalMetadata) {
            if (observation.hasContent) Text(stringResource(when (observation.freshness) {
                NativeRealtimeFreshness.Fresh -> R.string.st_fresh; NativeRealtimeFreshness.Stale -> R.string.st_stale; NativeRealtimeFreshness.Unknown -> R.string.st_unknownFreshness
            }), Modifier.testTag("$tag-freshness"), style = TrenifyTheme.typography.caption)
            if (observation.freshness == NativeRealtimeFreshness.Unknown && observation.provenance.stale && observation.hasContent) Text(stringResource(R.string.st_stale), Modifier.testTag("$tag-stale"), style = TrenifyTheme.typography.caption)
            observation.provenance.providerName?.let { Text("${stringResource(R.string.st_source)} · $it", Modifier.testTag("$tag-source"), style = TrenifyTheme.typography.caption) }
            observation.provenance.fetchedAtEpochSeconds?.let { Text("${stringResource(R.string.st_fetched)} · ${railTime(it)}", Modifier.testTag("$tag-fetched"), style = TrenifyTheme.typography.caption) }
            observation.provenance.sourceTimestampEpochSeconds?.let { Text("${stringResource(R.string.st_sourceUpdate)} · ${railTime(it)}", Modifier.testTag("$tag-source-update"), style = TrenifyTheme.typography.caption) }
            if (observation.provenance.degraded && observation.hasContent) Text(stringResource(R.string.st_degraded), Modifier.testTag("$tag-degraded"), style = TrenifyTheme.typography.caption)
        }
        observation.failure?.let { failure ->
            Text(stringResource(when (failure) {
                DomainFailure.OFFLINE -> R.string.st_offline; DomainFailure.TEMPORARY -> R.string.st_temporary
                DomainFailure.NOT_FOUND -> if (identifiedTrain) R.string.st_realtimeUnavailable else R.string.st_notFound
                DomainFailure.UNSUPPORTED -> R.string.st_unsupported; else -> R.string.st_invalid
            }), Modifier.testTag("$tag-error"))
            retry?.let { TextButton(it, Modifier.testTag("$tag-retry")) { Text(stringResource(R.string.st_retry)) } }
        }
    }
}

internal fun railTime(seconds: Long?): String = seconds?.let(::railwayDateTime) ?: "—"
internal fun progressLabel(progress: StopProgress): Int = when (progress) {
    StopProgress.COMPLETED -> R.string.st_completed; StopProgress.NEXT -> R.string.st_next
    StopProgress.FUTURE -> R.string.st_future; StopProgress.CANCELLED -> R.string.st_stopCancelled; StopProgress.UNKNOWN -> R.string.st_unknown
}
