package it.danielebufarini.trenify.journey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import it.danielebufarini.trenify.R
import it.danielebufarini.trenify.app.NativeJourneyDetailPresentation
import it.danielebufarini.trenify.app.NativeJourneyDetailState
import it.danielebufarini.trenify.app.NativeLegDetailPresentation
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.feature.journey.RouteFavoriteState
import it.danielebufarini.trenify.design.component.TrenifySecondaryAction
import it.danielebufarini.trenify.design.component.TrenifySectionHeader
import it.danielebufarini.trenify.design.component.TrenifyStatusPill
import it.danielebufarini.trenify.design.component.TrenifyStatusTone
import it.danielebufarini.trenify.design.theme.TrenifyShapes
import it.danielebufarini.trenify.design.theme.TrenifySpacing
import it.danielebufarini.trenify.design.theme.TrenifyTheme
import it.danielebufarini.trenify.presentation.RailwayStrikeBlock
import it.danielebufarini.trenify.presentation.railwayDate
import it.danielebufarini.trenify.presentation.railwayDurationLabel
import it.danielebufarini.trenify.presentation.railwayStatusTone
import it.danielebufarini.trenify.presentation.railwayTime

/**
 * Android-owned native Journey Detail (T8.6). Route summary, per-leg schedule
 * with already-correlated realtime enrichment, strike warnings, platform info,
 * transfer guidance and the secondary official-operator booking handoff. Train
 * navigation forwards to the existing shared action (T8.7 owns Train Detail).
 * No prices, fares, classes or purchase UI.
 */
@Composable
fun TrenifyJourneyDetailScreen(
    facade: NativeJourneyDetailPresentation,
    modifier: Modifier = Modifier,
) {
    // T8.14-C3: lifecycle-aware collection like every other native screen, so
    // stopped destinations stop observing instead of collecting in background.
    val state by facade.state.collectAsStateWithLifecycle()
    val routeFavorite by facade.favoriteRoute?.collectAsStateWithLifecycle()
        ?: androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<RouteFavoriteState?>(null) }
    TrenifyJourneyDetailContent(
        state = state,
        routeFavorite = routeFavorite,
        onToggleFavorite = facade::toggleFavoriteRoute,
        onBuy = facade::buy,
        onTrain = facade::openTrain,
        modifier = modifier,
    )
}

/**
 * Pure state rendering for deterministic tests and review captures; the
 * Screen above is the only production entry point and always feeds it live
 * shared state.
 */
@Composable
fun TrenifyJourneyDetailContent(
    state: NativeJourneyDetailState,
    routeFavorite: RouteFavoriteState?,
    onToggleFavorite: () -> Unit,
    onBuy: () -> Unit,
    onTrain: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Back/title chrome belongs to the platform shell TopAppBar (Blocker 4):
    // migrated destinations expose no in-content Back control.
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = TrenifySpacing.screenHorizontal).testTag("journey-detail"),
        verticalArrangement = Arrangement.spacedBy(TrenifySpacing.l),
    ) {
        when {
            state.resolving -> item {
                Column(verticalArrangement = Arrangement.spacedBy(TrenifySpacing.s)) {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().testTag("journey-detail-loading"),
                        color = TrenifyTheme.colors.accent,
                        trackColor = TrenifyTheme.colors.divider,
                    )
                    Text(
                        stringResource(R.string.journey_resolving),
                        style = TrenifyTheme.typography.body,
                        color = TrenifyTheme.colors.textSecondary,
                    )
                }
            }
            state.notFound || state.originName == null -> item {
                Text(
                    stringResource(R.string.journey_not_found),
                    Modifier.testTag("journey-detail-not-found"),
                    style = TrenifyTheme.typography.body,
                    color = TrenifyTheme.colors.textSecondary,
                )
            }
            else -> {
                item {
                    DetailSummary(
                        originName = state.originName!!,
                        destinationName = state.destinationName!!,
                        departureEpochSeconds = state.departureEpochSeconds!!,
                        arrivalEpochSeconds = state.arrivalEpochSeconds!!,
                        durationMinutes = state.durationMinutes!!,
                        changes = state.changes!!,
                    )
                }
                if (routeFavorite?.available == true) {
                    item {
                        val favorite = routeFavorite!!
                        Column(verticalArrangement = Arrangement.spacedBy(TrenifySpacing.s)) {
                            TrenifySecondaryAction(
                                title = stringResource(
                                    if (favorite.favorite) R.string.home_remove_route else R.string.home_save_route,
                                ),
                                onClick = onToggleFavorite,
                                enabled = !favorite.pending,
                                modifier = Modifier.fillMaxWidth().testTag("journey-route-favorite"),
                            )
                            // Restored T7-era behavior (Blocker 3): a failed
                            // update keeps content visible, shows the
                            // localized failure, invents no optimistic state
                            // and disables no unrelated action.
                            if (favorite.failed) {
                                Text(
                                    stringResource(R.string.journey_favorite_error),
                                    Modifier.testTag("journey-route-favorite-error"),
                                    style = TrenifyTheme.typography.status,
                                    color = TrenifyTheme.colors.statusCancelled,
                                )
                            }
                        }
                    }
                }
                if (state.bookingAvailable) {
                    item {
                        BookingBlock(
                            available = true,
                            inProgress = state.bookingInProgress,
                            failed = state.bookingFailed,
                            operatorName = state.bookingOperatorName,
                            onBuy = onBuy,
                        )
                    }
                }
                state.legs.forEachIndexed { index, leg ->
                    item {
                        LegSection(
                            leg = leg,
                            index = index,
                            total = state.legs.size,
                            correlating = state.correlating,
                            strikesStale = state.strikesStale,
                            strikesFailed = state.strikesFailed,
                            strikesUnknown = state.strikesUnknown,
                            onTrain = { onTrain(index) },
                        )
                    }
                }
            }
        }
        item {
            androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = TrenifySpacing.l))
        }
    }
}

@Composable
private fun DetailSummary(
    originName: String,
    destinationName: String,
    departureEpochSeconds: Long,
    arrivalEpochSeconds: Long,
    durationMinutes: Long,
    changes: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TrenifySpacing.s)) {
        Text(
            "$originName → $destinationName",
            Modifier.semantics { heading() }.testTag("journey-detail-route"),
            style = TrenifyTheme.typography.screenTitle,
            color = TrenifyTheme.colors.textPrimary,
        )
        Text(
            "${railwayTime(departureEpochSeconds)} → ${railwayTime(arrivalEpochSeconds)} · ${railwayDate(departureEpochSeconds)}",
            Modifier.testTag("journey-detail-times"),
            style = TrenifyTheme.typography.routeTime,
            color = TrenifyTheme.colors.textPrimary,
        )
        Text(
            "${railwayDurationLabel(durationMinutes)} · " +
                if (changes == 0) stringResource(R.string.journey_direct)
                else pluralStringResource(R.plurals.journey_changes, changes, changes),
            Modifier.testTag("journey-detail-meta"),
            style = TrenifyTheme.typography.bodyEmphasized,
            color = TrenifyTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun BookingBlock(
    available: Boolean,
    inProgress: Boolean,
    failed: Boolean,
    operatorName: String?,
    onBuy: () -> Unit,
) {
    // Unavailable booking renders nothing: no explanatory or unavailable
    // copy. Availability and handoff still follow the shared T5.5 policy.
    if (!available) return
    Column(verticalArrangement = Arrangement.spacedBy(TrenifySpacing.s)) {
        TrenifySecondaryAction(
            title = stringResource(R.string.journey_buy, operatorName.orEmpty()),
            onClick = onBuy,
            loading = inProgress,
            modifier = Modifier.fillMaxWidth().testTag("journey-buy"),
        )
        if (failed) {
            Text(
                stringResource(R.string.journey_buy_failed),
                Modifier.testTag("journey-buy-failed"),
                style = TrenifyTheme.typography.status,
                color = TrenifyTheme.colors.statusCancelled,
            )
        }
    }
}

@Composable
private fun LegSection(
    leg: NativeLegDetailPresentation,
    index: Int,
    total: Int,
    correlating: Boolean,
    strikesStale: Boolean,
    strikesFailed: Boolean,
    strikesUnknown: Boolean,
    onTrain: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TrenifySpacing.s)) {
        val transferWait = leg.transferWaitMinutesAfterPrevious
        if (index > 0 && transferWait != null) {
            Text(
                "${stringResource(R.string.journey_change_at, leg.originName)} · " +
                    stringResource(R.string.journey_wait, transferWait.toInt()),
                Modifier.testTag("journey-transfer-$index"),
                style = TrenifyTheme.typography.bodyEmphasized,
                color = TrenifyTheme.colors.textPrimary,
            )
        }
        TrenifySectionHeader(
            title = stringResource(R.string.journey_leg_title, index + 1, total),
            subtitle = "${leg.originName} → ${leg.destinationName}",
            modifier = Modifier.testTag("journey-leg-$index"),
        )
        Surface(
            Modifier.fillMaxWidth(),
            shape = TrenifyShapes.card,
            color = TrenifyTheme.colors.surfaceRaised,
        ) {
            Column(Modifier.padding(TrenifySpacing.l), verticalArrangement = Arrangement.spacedBy(TrenifySpacing.s)) {
                Text(
                    "${railwayTime(leg.departureEpochSeconds)} → ${railwayTime(leg.arrivalEpochSeconds)}",
                    style = TrenifyTheme.typography.routeTime,
                    color = TrenifyTheme.colors.textPrimary,
                )
                Text(
                    leg.trainIdentity ?: stringResource(R.string.journey_train_unknown),
                    style = TrenifyTheme.typography.trainIdentity,
                    color = TrenifyTheme.colors.textSecondary,
                )
                // Operator renders only when authoritative and known; an
                // absent operator omits the row entirely, never a placeholder.
                leg.operatorName?.let {
                    Text(
                        it,
                        Modifier.testTag("journey-operator-$index"),
                        style = TrenifyTheme.typography.metadata,
                        color = TrenifyTheme.colors.textSecondary,
                    )
                }
                LegStatusRow(leg = leg, index = index, correlating = correlating)
                // Expected/actual platforms, each only when available.
                leg.scheduledPlatform?.let {
                    Text(
                        stringResource(R.string.journey_platform_expected, it),
                        Modifier.testTag("journey-platform-expected-$index"),
                        style = TrenifyTheme.typography.metadata,
                        color = TrenifyTheme.colors.textSecondary,
                    )
                }
                leg.actualPlatform?.let {
                    Text(
                        stringResource(R.string.journey_platform_actual, it),
                        Modifier.testTag("journey-platform-actual-$index"),
                        style = TrenifyTheme.typography.metadata,
                        color = TrenifyTheme.colors.textSecondary,
                    )
                }
                // Full T7.12 warning semantics per leg (Blocker 1/1B): every
                // warning with impact/sector/interval/source/partial wording
                // plus coverage state truthful even with zero warnings.
                if (leg.warnings.isNotEmpty() || strikesStale || strikesFailed || strikesUnknown) {
                    RailwayStrikeBlock(
                        warnings = leg.warnings,
                        stale = strikesStale,
                        failed = strikesFailed,
                        unknown = strikesUnknown,
                        tagPrefix = "journey-leg-$index",
                    )
                }
                if (leg.hasTrainAction) {
                    TrenifySecondaryAction(
                        title = stringResource(R.string.journey_train_action),
                        onClick = onTrain,
                        modifier = Modifier.fillMaxWidth().testTag("journey-realtime-$index"),
                    )
                } else if (correlating) {
                    Text(
                        stringResource(R.string.journey_status_checking),
                        Modifier.testTag("journey-correlating-$index"),
                        style = TrenifyTheme.typography.caption,
                        color = TrenifyTheme.colors.textSecondary,
                    )
                } else {
                    Text(
                        stringResource(R.string.journey_realtime_unavailable),
                        Modifier.testTag("journey-realtime-unavailable-$index"),
                        style = TrenifyTheme.typography.caption,
                        color = TrenifyTheme.colors.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun LegStatusRow(leg: NativeLegDetailPresentation, index: Int, correlating: Boolean) {
    if (leg.realtimeStatus == TrainStatus.UNKNOWN && !leg.realtimeFailed) {
        TrenifyStatusPill(
            tone = TrenifyStatusTone.Unknown,
            label = if (correlating) stringResource(R.string.journey_status_checking)
            else stringResource(R.string.journey_status_no_realtime),
            modifier = Modifier.testTag("journey-service-$index"),
        )
        return
    }
    val tone = railwayStatusTone(leg.realtimeStatus, leg.delayMinutes)
    TrenifyStatusPill(
        tone = tone,
        label = legStatusLabel(leg),
        modifier = Modifier.testTag("journey-service-$index"),
    )
}

@Composable
private fun legStatusLabel(leg: NativeLegDetailPresentation): String {
    val delay = leg.delayMinutes
    if (delay != null && delay > 0 && (leg.realtimeStatus == TrainStatus.RUNNING || leg.realtimeStatus == TrainStatus.NOT_DEPARTED)) {
        return stringResource(R.string.journey_status_delayed, delay)
    }
    return stringResource(
        when (leg.realtimeStatus) {
            TrainStatus.RUNNING -> if (delay == 0) R.string.journey_status_on_time else R.string.journey_status_running
            TrainStatus.NOT_DEPARTED -> if (delay == 0) R.string.journey_status_on_time else R.string.journey_status_not_departed
            TrainStatus.ARRIVED -> R.string.journey_status_arrived
            TrainStatus.CANCELLED, TrainStatus.PARTIALLY_CANCELLED ->
                if (leg.realtimeStatus == TrainStatus.CANCELLED) R.string.journey_status_cancelled
                else R.string.journey_status_partially_cancelled
            TrainStatus.DIVERTED -> R.string.journey_status_diverted
            TrainStatus.RESCHEDULED -> R.string.journey_status_rescheduled
            TrainStatus.UNKNOWN -> R.string.journey_status_no_realtime
        },
    )
}

