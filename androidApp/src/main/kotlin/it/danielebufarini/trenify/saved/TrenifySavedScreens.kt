package it.danielebufarini.trenify.saved

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
import it.danielebufarini.trenify.app.NativeFavoriteRouteRow
import it.danielebufarini.trenify.app.NativeFavoriteStationRow
import it.danielebufarini.trenify.app.NativeFavoriteTrainRow
import it.danielebufarini.trenify.app.NativeJourneyHistoryRow
import it.danielebufarini.trenify.app.NativeSavedState
import it.danielebufarini.trenify.app.NativeShellEntry
import it.danielebufarini.trenify.app.NativeTrainHistoryRow
import it.danielebufarini.trenify.core.model.JourneySearchMode
import it.danielebufarini.trenify.design.theme.TrenifyShapes
import it.danielebufarini.trenify.design.theme.TrenifySpacing
import it.danielebufarini.trenify.design.theme.TrenifyTheme
import it.danielebufarini.trenify.presentation.railwayDateTime
import it.danielebufarini.trenify.presentation.railwayServiceDateLabel

/** Android owns rendering only. The facade is cached by the shared shell's live component identity. */
@Composable
fun NativeSavedEntry(entry: NativeShellEntry) {
    val facade = entry.saved ?: return
    val state by facade.state.collectAsStateWithLifecycle()
    SavedContent(
        state,
        openStation = facade::openStation,
        openRoute = facade::openRoute,
        openTrain = facade::openTrain,
        removeStation = facade::removeStation,
        removeRoute = facade::removeRoute,
        removeTrain = facade::removeTrain,
        repeatJourney = facade::repeatJourney,
        repeatTrain = facade::repeatTrain,
        removeHistory = facade::removeHistory,
        clearHistory = facade::clearHistory,
        retryFavorites = facade::retryFavorites,
        retryHistory = facade::retryHistory,
    )
}

@Composable
fun SavedContent(
    state: NativeSavedState,
    openStation: (String) -> Unit,
    openRoute: (String) -> Unit,
    openTrain: (String) -> Unit,
    removeStation: (String) -> Unit,
    removeRoute: (String) -> Unit,
    removeTrain: (String) -> Unit,
    repeatJourney: (String) -> Unit,
    repeatTrain: (String) -> Unit,
    removeHistory: (String) -> Unit,
    clearHistory: () -> Unit,
    retryFavorites: () -> Unit,
    retryHistory: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = TrenifySpacing.screenHorizontal).testTag("native-saved"),
        verticalArrangement = Arrangement.spacedBy(TrenifySpacing.m),
    ) {
        if (state.loading || state.historyLoading) {
            item {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("saved-loading"))
                Text(stringResource(R.string.st_loading))
            }
        }
        // Retained content stays visible while the failure flag shows degradation.
        if (state.observationFailed || state.favoriteFailed) {
            item {
                Text(
                    stringResource(if (state.observationFailed) R.string.saved_loadError else R.string.saved_updateError),
                    Modifier.testTag("saved-error"),
                    color = TrenifyTheme.colors.statusCancelled,
                )
                TextButton(retryFavorites, Modifier.heightIn(min = 48.dp).testTag("saved-retry")) {
                    Text(stringResource(R.string.st_retry))
                }
            }
        }
        if (state.historyObservationFailed || state.historyFailed) {
            item {
                Text(
                    stringResource(if (state.historyObservationFailed) R.string.saved_loadError else R.string.saved_updateError),
                    Modifier.testTag("saved-history-error"),
                    color = TrenifyTheme.colors.statusCancelled,
                )
                TextButton(retryHistory, Modifier.heightIn(min = 48.dp).testTag("saved-history-retry")) {
                    Text(stringResource(R.string.st_retry))
                }
            }
        }
        if (!state.loading && !state.historyLoading && !state.observationFailed &&
            !state.historyObservationFailed && state.savedEmpty
        ) {
            item { Text(stringResource(R.string.saved_empty), Modifier.testTag("saved-empty")) }
        }
        // Degraded policy: retained favorites stay visible (and actionable)
        // during observation failure; only a failure with nothing retained
        // hides the section (the banner above carries the failure state).
        if (!state.loading && !(state.observationFailed && state.favoritesEmpty)) {
            item {
                Text(stringResource(R.string.saved_favorites),
                    Modifier.testTag("saved-favorites-header").semantics { heading() },
                    style = TrenifyTheme.typography.sectionTitle, color = TrenifyTheme.colors.textPrimary)
            }
            if (state.favoritesEmpty) {
                item { Text(stringResource(R.string.saved_favoritesEmpty), Modifier.testTag("saved-favorites-empty")) }
            } else {
                if (state.stations.isNotEmpty()) {
                    item {
                        Text(stringResource(R.string.saved_favoriteStations),
                            Modifier.testTag("saved-stations-header").semantics { heading() },
                            style = TrenifyTheme.typography.bodyEmphasized)
                    }
                }
                items(state.stations, key = { it.stationId }) { row ->
                    FavoriteStationCard(row, { openStation(row.stationId) }, { removeStation(row.stationId) })
                }
                if (state.routes.isNotEmpty()) {
                    item {
                        Text(stringResource(R.string.saved_favoriteRoutes),
                            Modifier.testTag("saved-routes-header").semantics { heading() },
                            style = TrenifyTheme.typography.bodyEmphasized)
                    }
                }
                items(state.routes, key = { it.identity }) { row ->
                    FavoriteRouteCard(row, { openRoute(row.identity) }, { removeRoute(row.identity) })
                }
                if (state.trains.isNotEmpty()) {
                    item {
                        Text(stringResource(R.string.saved_favoriteTrains),
                            Modifier.testTag("saved-trains-header").semantics { heading() },
                            style = TrenifyTheme.typography.bodyEmphasized)
                    }
                }
                // Stable semantic keys: FavoriteTrain.id, never row index.
                items(state.trains, key = { it.identity }) { row ->
                    FavoriteTrainCard(row, { openTrain(row.identity) }, { removeTrain(row.identity) })
                }
            }
        }
        // Same degraded policy for history: retained entries stay visible
        // during observation failure; a failure with nothing retained
        // hides the sections (the banner above carries the failure state).
        if (!state.historyLoading && !(state.historyObservationFailed &&
                state.journeyHistory.isEmpty() && state.trainHistory.isEmpty())
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.saved_journeyHistory),
                        Modifier.weight(1f).testTag("saved-journey-history-header").semantics { heading() },
                        style = TrenifyTheme.typography.sectionTitle, color = TrenifyTheme.colors.textPrimary)
                    if (state.journeyHistory.isNotEmpty() || state.trainHistory.isNotEmpty()) {
                        TextButton(clearHistory, Modifier.heightIn(min = 48.dp).testTag("saved-clear-history")) {
                            Text(stringResource(R.string.saved_clearHistory))
                        }
                    }
                }
            }
            if (state.journeyHistory.isEmpty()) {
                item {
                    Text(stringResource(R.string.saved_journeyHistoryEmpty),
                        Modifier.testTag("saved-journey-history-empty"))
                }
            }
            items(state.journeyHistory, key = { it.entryId }) { row ->
                JourneyHistoryCard(row, { repeatJourney(row.entryId) }, { removeHistory(row.entryId) })
            }
            item {
                Text(stringResource(R.string.saved_trainHistory),
                    Modifier.testTag("saved-train-history-header").semantics { heading() },
                    style = TrenifyTheme.typography.sectionTitle, color = TrenifyTheme.colors.textPrimary)
            }
            if (state.trainHistory.isEmpty()) {
                item {
                    Text(stringResource(R.string.saved_trainHistoryEmpty),
                        Modifier.testTag("saved-train-history-empty"))
                }
            }
            items(state.trainHistory, key = { it.entryId }) { row ->
                TrainHistoryCard(row, { repeatTrain(row.entryId) }, { removeHistory(row.entryId) })
            }
        }
        item { Spacer(Modifier.height(TrenifySpacing.l)) }
    }
}

@Composable
private fun FavoriteStationCard(row: NativeFavoriteStationRow, open: () -> Unit, remove: () -> Unit) {
    Surface(Modifier.fillMaxWidth().testTag("favorite-station-${row.stationId}").clickable(onClick = open),
        shape = TrenifyShapes.card, color = TrenifyTheme.colors.surfaceRaised) {
        Row(Modifier.fillMaxWidth().padding(TrenifySpacing.m).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(row.name, Modifier.weight(1f), style = TrenifyTheme.typography.bodyEmphasized)
            TextButton(remove, Modifier.heightIn(min = 48.dp).testTag("favorite-station-remove-${row.stationId}"),
                enabled = !row.pending) {
                Text(stringResource(R.string.saved_remove))
            }
        }
    }
}

@Composable
private fun FavoriteRouteCard(row: NativeFavoriteRouteRow, open: () -> Unit, remove: () -> Unit) {
    Surface(Modifier.fillMaxWidth().testTag("favorite-route-${row.identity}").clickable(onClick = open),
        shape = TrenifyShapes.card, color = TrenifyTheme.colors.surfaceRaised) {
        Row(Modifier.fillMaxWidth().padding(TrenifySpacing.m).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("${row.originName} → ${row.destinationName}", Modifier.weight(1f),
                style = TrenifyTheme.typography.bodyEmphasized)
            TextButton(remove, Modifier.heightIn(min = 48.dp).testTag("favorite-route-remove-${row.identity}"),
                enabled = !row.pending) {
                Text(stringResource(R.string.saved_remove))
            }
        }
    }
}

@Composable
private fun FavoriteTrainCard(row: NativeFavoriteTrainRow, open: () -> Unit, remove: () -> Unit) {
    Surface(Modifier.fillMaxWidth().testTag("favorite-train-${row.identity}").clickable(onClick = open),
        shape = TrenifyShapes.card, color = TrenifyTheme.colors.surfaceRaised) {
        Column(Modifier.fillMaxWidth().padding(TrenifySpacing.m), verticalArrangement = Arrangement.spacedBy(TrenifySpacing.xs)) {
            Text(stringResource(R.string.saved_trainNumber, row.number),
                Modifier.testTag("favorite-train-identity-${row.identity}"),
                style = TrenifyTheme.typography.trainIdentity)
            // Route/operator context distinguishes same-number favorites;
            // unknown values are omitted, never fabricated.
            val route = listOfNotNull(row.originName, row.destinationName).joinToString(" → ").takeIf { it.isNotEmpty() }
            route?.let {
                Text(it, Modifier.testTag("favorite-train-context-${row.identity}"),
                    style = TrenifyTheme.typography.bodyEmphasized)
            }
            row.operatorName?.let {
                Text("${stringResource(R.string.st_operator)} · $it",
                    Modifier.testTag("favorite-train-operator-${row.identity}"),
                    style = TrenifyTheme.typography.metadata)
            }
            if (row.failed) {
                Text(stringResource(R.string.saved_updateError),
                    Modifier.testTag("favorite-train-error-${row.identity}"),
                    color = TrenifyTheme.colors.statusCancelled)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(remove, Modifier.heightIn(min = 48.dp).testTag("favorite-train-remove-${row.identity}"),
                    enabled = row.canRemove) {
                    Text(stringResource(R.string.saved_remove))
                }
            }
        }
    }
}

@Composable
internal fun JourneyHistoryCard(row: NativeJourneyHistoryRow, repeat: () -> Unit, remove: () -> Unit) {
    Surface(Modifier.fillMaxWidth().testTag("journey-history-${row.entryId}"),
        shape = TrenifyShapes.card, color = TrenifyTheme.colors.surfaceRaised) {
        Column(Modifier.fillMaxWidth().padding(TrenifySpacing.m), verticalArrangement = Arrangement.spacedBy(TrenifySpacing.xs)) {
            Text("${row.originName} → ${row.destinationName}",
                Modifier.testTag("journey-history-title-${row.entryId}"),
                style = TrenifyTheme.typography.bodyEmphasized)
            val mode = stringResource(
                if (row.mode == JourneySearchMode.DEPART_AFTER) R.string.saved_departAfter else R.string.saved_arriveBy)
            Text("${railwayDateTime(row.atEpochSeconds)} · $mode",
                Modifier.testTag("journey-history-criteria-${row.entryId}"),
                style = TrenifyTheme.typography.metadata)
            Text(stringResource(R.string.saved_searchedAt, railwayDateTime(row.submittedAtEpochSeconds)),
                Modifier.testTag("journey-history-searched-${row.entryId}"),
                style = TrenifyTheme.typography.caption)
            Row(horizontalArrangement = Arrangement.spacedBy(TrenifySpacing.s)) {
                TextButton(repeat, Modifier.heightIn(min = 48.dp).testTag("journey-history-repeat-${row.entryId}")) {
                    Text(stringResource(R.string.saved_repeat))
                }
                TextButton(remove, Modifier.heightIn(min = 48.dp).testTag("journey-history-remove-${row.entryId}"),
                    enabled = !row.pending) {
                    Text(stringResource(R.string.saved_remove))
                }
            }
        }
    }
}

@Composable
internal fun TrainHistoryCard(row: NativeTrainHistoryRow, repeat: () -> Unit, remove: () -> Unit) {
    Surface(Modifier.fillMaxWidth().testTag("train-history-${row.entryId}"),
        shape = TrenifyShapes.card, color = TrenifyTheme.colors.surfaceRaised) {
        Column(Modifier.fillMaxWidth().padding(TrenifySpacing.m), verticalArrangement = Arrangement.spacedBy(TrenifySpacing.xs)) {
            Text(stringResource(R.string.saved_trainNumber, row.number),
                Modifier.testTag("train-history-title-${row.entryId}"),
                style = TrenifyTheme.typography.trainIdentity)
            Text(
                row.serviceDateString?.let { "${stringResource(R.string.st_serviceDate)} · ${railwayServiceDateLabel(it)}" }
                    ?: stringResource(R.string.saved_unknownDate),
                Modifier.testTag("train-history-date-${row.entryId}"),
                style = TrenifyTheme.typography.metadata,
            )
            // Stored route context only; a number-only search shows no invented origin.
            val route = listOfNotNull(row.originName, row.destinationName).joinToString(" → ").takeIf { it.isNotEmpty() }
            route?.let {
                Text(it, Modifier.testTag("train-history-context-${row.entryId}"),
                    style = TrenifyTheme.typography.bodyEmphasized)
            }
            row.operatorName?.let {
                Text("${stringResource(R.string.st_operator)} · $it",
                    Modifier.testTag("train-history-operator-${row.entryId}"),
                    style = TrenifyTheme.typography.metadata)
            }
            Text(stringResource(R.string.saved_searchedAt, railwayDateTime(row.submittedAtEpochSeconds)),
                Modifier.testTag("train-history-searched-${row.entryId}"),
                style = TrenifyTheme.typography.caption)
            Row(horizontalArrangement = Arrangement.spacedBy(TrenifySpacing.s)) {
                TextButton(repeat, Modifier.heightIn(min = 48.dp).testTag("train-history-repeat-${row.entryId}")) {
                    Text(stringResource(R.string.saved_repeat))
                }
                TextButton(remove, Modifier.heightIn(min = 48.dp).testTag("train-history-remove-${row.entryId}"),
                    enabled = !row.pending) {
                    Text(stringResource(R.string.saved_remove))
                }
            }
        }
    }
}
