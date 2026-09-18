package it.danielebufarini.trenify.journey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import it.danielebufarini.trenify.R
import it.danielebufarini.trenify.app.NativeJourneyCardPresentation
import it.danielebufarini.trenify.app.NativeJourneyResultsPresentation
import it.danielebufarini.trenify.app.NativeJourneyResultsState
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.model.JourneySort
import it.danielebufarini.trenify.design.component.TrenifyJourneyCard
import it.danielebufarini.trenify.design.component.TrenifyPrimaryAction
import it.danielebufarini.trenify.design.component.TrenifySecondaryAction
import it.danielebufarini.trenify.design.component.TrenifyStatusPill
import it.danielebufarini.trenify.design.component.TrenifyStatusTone
import it.danielebufarini.trenify.design.theme.TrenifySpacing
import it.danielebufarini.trenify.design.theme.TrenifyTheme
import it.danielebufarini.trenify.presentation.RailwayStrikeBlock
import it.danielebufarini.trenify.presentation.railwayDateTime
import it.danielebufarini.trenify.presentation.railwayDurationLabel
import it.danielebufarini.trenify.presentation.railwayTime

/**
 * Android-owned native Journey Results (T8.6). Renders the shared Results
 * component through the narrow native facade: loading/refreshing with
 * content, results, empty, stale/cached and failure states; direct and
 * transfer cards with strike warnings. No prices, fares, classes or purchase
 * UI. Selection navigates through the shared component only.
 */
@Composable
fun TrenifyJourneyResultsScreen(
    facade: NativeJourneyResultsPresentation,
    modifier: Modifier = Modifier,
) {
    // T8.14-C3: lifecycle-aware collection like every other native screen, so
    // stopped destinations stop observing instead of collecting in background.
    val state by facade.state.collectAsStateWithLifecycle()
    TrenifyJourneyResultsContent(
        state = state,
        onRefresh = facade::refresh,
        onSort = facade::setSort,
        onSelect = facade::selectJourney,
        modifier = modifier,
    )
}

/**
 * Pure state rendering for deterministic tests and review captures; the
 * Screen above is the only production entry point and always feeds it live
 * shared state.
 */
@Composable
fun TrenifyJourneyResultsContent(
    state: NativeJourneyResultsState,
    onRefresh: () -> Unit,
    onSort: (JourneySort) -> Unit,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = TrenifySpacing.screenHorizontal).testTag("journey-results"),
        verticalArrangement = Arrangement.spacedBy(TrenifySpacing.l),
    ) {
        item {
            Text(
                "${state.originName} → ${state.destinationName}",
                Modifier.semantics { heading() }.testTag("journey-results-route"),
                style = TrenifyTheme.typography.screenTitle,
                color = TrenifyTheme.colors.textPrimary,
            )
            Text(
                stringResource(
                    R.string.journey_window,
                    railwayDateTime(state.windowStartEpochSeconds),
                    railwayDateTime(state.windowEndEpochSeconds),
                ),
                Modifier.testTag("journey-results-window"),
                style = TrenifyTheme.typography.metadata,
                color = TrenifyTheme.colors.textSecondary,
            )
        }
        when {
            state.loading && !state.hasContent -> item {
                Column(verticalArrangement = Arrangement.spacedBy(TrenifySpacing.s)) {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().testTag("journey-results-loading"),
                        color = TrenifyTheme.colors.accent,
                        trackColor = TrenifyTheme.colors.divider,
                    )
                    Text(
                        stringResource(R.string.journey_loading),
                        style = TrenifyTheme.typography.body,
                        color = TrenifyTheme.colors.textSecondary,
                    )
                }
            }
            state.failure != null && !state.hasContent -> item {
                JourneyFailureBlock(
                    failure = state.failure!!,
                    hasCachedContent = false,
                    onRetry = onRefresh,
                    errorTag = "journey-results-error",
                )
            }
            else -> {
                if (state.loading) item {
                    Column(verticalArrangement = Arrangement.spacedBy(TrenifySpacing.xs)) {
                        LinearProgressIndicator(
                            Modifier.fillMaxWidth().testTag("journey-results-refreshing"),
                            color = TrenifyTheme.colors.accent,
                            trackColor = TrenifyTheme.colors.divider,
                        )
                        Text(
                            stringResource(R.string.journey_refreshing),
                            style = TrenifyTheme.typography.caption,
                            color = TrenifyTheme.colors.textSecondary,
                        )
                    }
                }
                if (state.failure != null) item {
                    JourneyFailureBlock(
                        failure = state.failure!!,
                        hasCachedContent = true,
                        onRetry = onRefresh,
                        errorTag = "journey-results-error-cached",
                    )
                }
                item {
                    JourneySortControl(selected = state.sort, onSort = onSort)
                }
                if (state.empty) {
                    item {
                        Text(
                            stringResource(R.string.journey_empty),
                            Modifier.testTag("journey-results-empty"),
                            style = TrenifyTheme.typography.body,
                            color = TrenifyTheme.colors.textSecondary,
                        )
                    }
                } else {
                    items(state.journeys, key = { it.key }) { journey ->
                        JourneyResultCard(journey, state, onDetails = { onSelect(journey.index) })
                    }
                }
                item {
                    TrenifySecondaryAction(
                        title = stringResource(R.string.journey_refresh),
                        onClick = onRefresh,
                        modifier = Modifier.fillMaxWidth().testTag("journey-refresh"),
                    )
                }
            }
        }
        item {
            androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = TrenifySpacing.l))
        }
    }
}

/**
 * Compact Journey-specific sort control: one secondary action showing the
 * current sort, opening a Material 3 menu with the four shared sort
 * options. Sort semantics are unchanged; only the presentation is compact.
 */
@Composable
private fun JourneySortControl(selected: JourneySort, onSort: (JourneySort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TrenifySecondaryAction(
            title = stringResource(R.string.journey_sort_current, journeySortLabel(selected)),
            onClick = { expanded = true },
            modifier = Modifier.testTag("journey-sort"),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            JourneySort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = {
                        Text(
                            (if (sort == selected) "✓ " else "") + journeySortLabel(sort),
                            style = TrenifyTheme.typography.body,
                            color = TrenifyTheme.colors.textPrimary,
                        )
                    },
                    onClick = { expanded = false; onSort(sort) },
                    modifier = Modifier.testTag("journey-sort-option-${sort.name.lowercase()}"),
                )
            }
        }
    }
}

@Composable
private fun journeySortLabel(sort: JourneySort): String = stringResource(
    when (sort) {
        JourneySort.DEPARTURE -> R.string.journey_sort_departure
        JourneySort.ARRIVAL -> R.string.journey_sort_arrival
        JourneySort.DURATION -> R.string.journey_sort_duration
        JourneySort.CHANGES -> R.string.journey_sort_changes
    },
)

@Composable
private fun JourneyResultCard(
    journey: NativeJourneyCardPresentation,
    state: NativeJourneyResultsState,
    onDetails: () -> Unit,
) {
    val warningLabel = when {
        journey.warningCount == 0 -> null
        journey.confirmedWarning -> stringResource(R.string.journey_warning_confirmed)
        else -> pluralStringResource(R.plurals.journey_warnings, journey.warningCount, journey.warningCount)
    }
    Column(verticalArrangement = Arrangement.spacedBy(TrenifySpacing.s)) {
        TrenifyJourneyCard(
            trainIdentity = journey.trainIdentities.firstOrNull()
                ?: journey.operatorNames.firstOrNull()
                ?: stringResource(R.string.journey_train_unknown),
            departureTime = railwayTime(journey.departureEpochSeconds),
            arrivalTime = railwayTime(journey.arrivalEpochSeconds),
            origin = journey.originName,
            destination = journey.destinationName,
            durationLabel = railwayDurationLabel(journey.durationMinutes),
            changesLabel = journeyChangesLabel(journey),
            onDetails = onDetails,
            statusTone = if (journey.warningCount > 0) TrenifyStatusTone.Warning else TrenifyStatusTone.Unknown,
            // Neutral/default scheduled results carry no badge; only genuine
            // strike warnings render a status pill.
            statusLabel = warningLabel,
            modifier = Modifier.testTag("journey-result-${journey.index}"),
        )
        // FR-DATA-001 provider attribution remains in shared state for
        // diagnostics and detail presentation, but is not repeated on every
        // traveler-facing result card.
        // Full T7.12 warning semantics (Blocker 1/1B): every warning with
        // impact/sector/interval/source/partial wording, plus coverage
        // state that stays truthful even with zero warnings. A warning
        // never implies cancellation.
        if (journey.warnings.isNotEmpty() || state.strikesStale || state.strikesFailed || state.strikesUnknown) {
            RailwayStrikeBlock(
                warnings = journey.warnings,
                stale = state.strikesStale,
                failed = state.strikesFailed,
                unknown = state.strikesUnknown,
                tagPrefix = "journey-${journey.index}",
            )
        }
    }
}

@Composable
private fun journeyChangesLabel(journey: NativeJourneyCardPresentation): String {
    val changes = pluralStringResource(R.plurals.journey_changes, journey.changes, journey.changes)
    return if (journey.changes == 0) {
        val operator = journey.operatorNames.firstOrNull()
        if (operator != null) "${stringResource(R.string.journey_direct)} · $operator" else stringResource(R.string.journey_direct)
    } else {
        val via = journey.legs.dropLast(1).map { it.destinationName }.distinct()
        if (via.isNotEmpty()) "$changes · ${stringResource(R.string.journey_via, via.joinToString(", "))}" else changes
    }
}

@Composable
private fun JourneyFailureBlock(failure: DomainFailure, hasCachedContent: Boolean, onRetry: () -> Unit, errorTag: String) {
    Column(verticalArrangement = Arrangement.spacedBy(TrenifySpacing.s)) {
        Text(
            journeyFailureMessage(failure, hasCachedContent),
            Modifier.testTag(errorTag),
            style = TrenifyTheme.typography.status,
            color = TrenifyTheme.colors.statusCancelled,
        )
        TrenifyPrimaryAction(
            title = stringResource(R.string.journey_retry),
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth().testTag("$errorTag-retry"),
        )
    }
}

@Composable
internal fun journeyFailureMessage(failure: DomainFailure, hasCachedContent: Boolean): String = when (failure) {
    DomainFailure.OFFLINE ->
        stringResource(if (hasCachedContent) R.string.journey_error_offline_cached else R.string.journey_error_offline)
    DomainFailure.TEMPORARY -> stringResource(R.string.journey_error_temporary)
    DomainFailure.NOT_FOUND -> stringResource(R.string.journey_error_not_found)
    DomainFailure.INVALID_REQUEST -> stringResource(R.string.journey_error_invalid_request)
    DomainFailure.UNSUPPORTED -> stringResource(R.string.journey_error_unsupported)
    DomainFailure.INVALID_RESPONSE -> stringResource(R.string.journey_error_invalid_response)
}
