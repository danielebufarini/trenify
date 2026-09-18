package it.danielebufarini.trenify.saved

import androidx.compose.foundation.layout.Arrangement
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
import it.danielebufarini.trenify.app.NativeHistoryState
import it.danielebufarini.trenify.app.NativeShellEntry
import it.danielebufarini.trenify.design.theme.TrenifySpacing
import it.danielebufarini.trenify.design.theme.TrenifyTheme

/**
 * Android owns rendering only. The History destination is the existing
 * Journey-tab History child rendered through the shared
 * [NativeHistoryPresentation]; Decompose stays the navigation authority.
 */
@Composable
fun NativeHistoryEntry(entry: NativeShellEntry) {
    val facade = entry.history ?: return
    val state by facade.state.collectAsStateWithLifecycle()
    HistoryContent(
        state,
        repeatJourney = facade::repeatJourney,
        repeatTrain = facade::repeatTrain,
        removeHistory = facade::removeHistory,
        clearHistory = facade::clearHistory,
        retryHistory = facade::retry,
    )
}

@Composable
fun HistoryContent(
    state: NativeHistoryState,
    repeatJourney: (String) -> Unit,
    repeatTrain: (String) -> Unit,
    removeHistory: (String) -> Unit,
    clearHistory: () -> Unit,
    retryHistory: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = TrenifySpacing.screenHorizontal).testTag("native-history"),
        verticalArrangement = Arrangement.spacedBy(TrenifySpacing.m),
    ) {
        if (state.loading) {
            item {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("history-loading"))
                Text(stringResource(R.string.st_loading))
            }
        }
        // Retained history stays visible while the failure flag shows degradation.
        if (state.observationFailed || state.failedMutation) {
            item {
                Text(
                    stringResource(if (state.observationFailed) R.string.saved_loadError else R.string.saved_updateError),
                    Modifier.testTag("history-error"),
                    color = TrenifyTheme.colors.statusCancelled,
                )
                TextButton(retryHistory, Modifier.heightIn(min = 48.dp).testTag("history-retry")) {
                    Text(stringResource(R.string.st_retry))
                }
            }
        }
        // Degraded policy: retained entries stay visible (and actionable)
        // during observation failure; only a failure with nothing retained
        // hides the sections (the banner above carries the failure state).
        if (!state.loading && !(state.observationFailed && state.historyEmpty)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.saved_journeyHistory),
                        Modifier.weight(1f).testTag("history-journey-header").semantics { heading() },
                        style = TrenifyTheme.typography.sectionTitle, color = TrenifyTheme.colors.textPrimary)
                    if (!state.historyEmpty) {
                        TextButton(clearHistory, Modifier.heightIn(min = 48.dp).testTag("history-clear")) {
                            Text(stringResource(R.string.saved_clearHistory))
                        }
                    }
                }
            }
            if (state.journeyHistory.isEmpty()) {
                item {
                    Text(stringResource(R.string.saved_journeyHistoryEmpty),
                        Modifier.testTag("history-journey-empty"))
                }
            }
            items(state.journeyHistory, key = { it.entryId }) { row ->
                JourneyHistoryCard(row, { repeatJourney(row.entryId) }, { removeHistory(row.entryId) })
            }
            item {
                Text(stringResource(R.string.saved_trainHistory),
                    Modifier.testTag("history-train-header").semantics { heading() },
                    style = TrenifyTheme.typography.sectionTitle, color = TrenifyTheme.colors.textPrimary)
            }
            if (state.trainHistory.isEmpty()) {
                item {
                    Text(stringResource(R.string.saved_trainHistoryEmpty),
                        Modifier.testTag("history-train-empty"))
                }
            }
            items(state.trainHistory, key = { it.entryId }) { row ->
                TrainHistoryCard(row, { repeatTrain(row.entryId) }, { removeHistory(row.entryId) })
            }
        }
        item { Spacer(Modifier.height(TrenifySpacing.l)) }
    }
}
