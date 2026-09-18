package it.danielebufarini.trenify.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import it.danielebufarini.trenify.R
import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.model.FavoriteTrain
import it.danielebufarini.trenify.core.model.JourneySearchHistoryEntry
import it.danielebufarini.trenify.core.model.JourneySearchMode
import it.danielebufarini.trenify.core.model.SearchHistoryEntry
import it.danielebufarini.trenify.core.model.Station
import it.danielebufarini.trenify.core.model.TrainSearchHistoryEntry
import it.danielebufarini.trenify.core.ui.platformLocaleTag
import it.danielebufarini.trenify.design.component.TrenifyPrimaryAction
import it.danielebufarini.trenify.design.component.TrenifySecondaryAction
import it.danielebufarini.trenify.design.component.TrenifySectionHeader
import it.danielebufarini.trenify.design.component.dpadExitSingleLine
import it.danielebufarini.trenify.design.component.TrenifySegment
import it.danielebufarini.trenify.design.component.TrenifySegmentedControl
import it.danielebufarini.trenify.design.theme.TrenifyShapes
import it.danielebufarini.trenify.design.theme.TrenifySpacing
import it.danielebufarini.trenify.design.theme.TrenifyTheme
import it.danielebufarini.trenify.feature.home.HomeComponent
import it.danielebufarini.trenify.feature.journey.JourneySearchComponent
import it.danielebufarini.trenify.feature.journey.formatSearchDate
import it.danielebufarini.trenify.feature.journey.formatSearchTime

/**
 * Android-owned native Home/Search (T8.5). One composed trip input with swap,
 * native date/time pickers, journey + train/station entries, recents and
 * favorites. Shared components stay authoritative for validation, search,
 * history and navigation; this file owns only visual composition.
 */
@Composable
fun TrenifyHomeScreen(
    home: HomeComponent,
    journeySearch: JourneySearchComponent?,
    modifier: Modifier = Modifier,
) {
    val homeState = home.state.subscribeAsState().value
    val journeyState = journeySearch?.state?.subscribeAsState()?.value
    val routeFavorite = journeySearch?.favoriteRouteState?.subscribeAsState()?.value
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = TrenifySpacing.screenHorizontal).testTag("home-list"),
        verticalArrangement = Arrangement.spacedBy(TrenifySpacing.l),
    ) {
        item {
            Text(
                stringResource(R.string.home_title),
                Modifier.padding(top = TrenifySpacing.l).semantics { heading() }.testTag("home-title"),
                style = TrenifyTheme.typography.hero,
                color = TrenifyTheme.colors.textPrimary,
            )
        }
        item {
            JourneyComposerCard(
                originText = journeyState?.originText.orEmpty(),
                destinationText = journeyState?.destinationText.orEmpty(),
                onOriginText = { journeySearch?.stationText(true, it) },
                onDestinationText = { journeySearch?.stationText(false, it) },
                onSwap = { journeySearch?.swap() },
                suggestionsLabel = stringResource(R.string.home_suggestions),
                suggestions = journeyState?.suggestions.orEmpty(),
                editingOrigin = journeyState?.editingOrigin ?: true,
                onSelectSuggestion = { journeySearch?.select(it) },
                dateLabel = stringResource(R.string.home_date),
                timeLabel = stringResource(R.string.home_time),
                journeySearch = journeySearch,
            )
        }
        item {
            val mode = journeyState?.mode ?: JourneySearchMode.DEPART_AFTER
            TrenifySegmentedControl(
                options = listOf(
                    TrenifySegment(JourneySearchMode.DEPART_AFTER, stringResource(R.string.home_mode_depart)),
                    TrenifySegment(JourneySearchMode.ARRIVE_BY, stringResource(R.string.home_mode_arrive)),
                ),
                selected = mode,
                onSelect = { journeySearch?.mode(it) },
                modifier = Modifier.testTag("home-mode"),
            )
        }
        item {
            if (journeyState?.invalid == true) {
                Text(
                    stringResource(R.string.home_invalid),
                    Modifier.testTag("home-invalid"),
                    style = TrenifyTheme.typography.status,
                    color = TrenifyTheme.colors.statusCancelled,
                )
            }
            TrenifyPrimaryAction(
                title = stringResource(R.string.home_search_trains),
                onClick = {
                    journeySearch?.search()
                    // The shared push lands on the Journey tab. Selecting it
                    // makes Results visible above the native Home base; when
                    // already on Journey this is idempotent. Invalid
                    // submissions stay on Home with the shared invalid flag.
                    if (journeySearch?.state?.value?.invalid == false) home.openJourneySearch()
                },
                enabled = journeySearch != null,
                modifier = Modifier.fillMaxWidth().testTag("home-search"),
            )
            if (routeFavorite?.available == true) {
                TrenifySecondaryAction(
                    title = stringResource(
                        if (routeFavorite.favorite) R.string.home_remove_route else R.string.home_save_route,
                    ),
                    onClick = { journeySearch?.toggleFavoriteRoute() },
                    enabled = !routeFavorite.pending,
                    modifier = Modifier.fillMaxWidth().padding(top = TrenifySpacing.s).testTag("home-route-favorite"),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(TrenifySpacing.s)) {
                TrenifySecondaryAction(
                    title = stringResource(R.string.home_train_entry),
                    onClick = home::openTrainSearch,
                    modifier = Modifier.weight(1f).testTag("home-train-entry"),
                )
                TrenifySecondaryAction(
                    title = stringResource(R.string.home_station_entry),
                    onClick = home::openStations,
                    modifier = Modifier.weight(1f).testTag("home-station-entry"),
                )
            }
        }
        // The observation failure never hides cached recents/favorites below.
        if (homeState.observationFailed) {
            item {
                Text(
                    stringResource(R.string.home_load_error),
                    Modifier.testTag("home-load-error"),
                    style = TrenifyTheme.typography.status,
                    color = TrenifyTheme.colors.statusCancelled,
                )
            }
        }
        item {
            TrenifySectionHeader(
                title = stringResource(R.string.home_recent),
                actionLabel = stringResource(R.string.home_history),
                onAction = home::openHistory,
                modifier = Modifier.testTag("home-recent-header"),
            )
        }
        if (homeState.recentSearches.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.home_recent_empty),
                    Modifier.testTag("home-recent-empty"),
                    style = TrenifyTheme.typography.body,
                    color = TrenifyTheme.colors.textSecondary,
                )
            }
        } else {
            items(homeState.recentSearches, key = { it.id.value }) { entry ->
                RecentRow(entry, onOpen = { home.openRecent(entry) })
            }
        }
        item {
            TrenifySectionHeader(title = stringResource(R.string.home_favorites))
        }
        if (homeState.favoriteStations.isEmpty() && homeState.favoriteRoutes.isEmpty() && homeState.favoriteTrains.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.home_favorites_empty),
                    Modifier.testTag("home-favorites-empty"),
                    style = TrenifyTheme.typography.body,
                    color = TrenifyTheme.colors.textSecondary,
                )
            }
        } else {
            items(homeState.favoriteStations, key = { "station-${it.id.value}" }) { station ->
                FavoriteRow("home-fav-station-${station.id.value}", station.name) { home.openStation(station) }
            }
            items(homeState.favoriteRoutes, key = { "route-${it.id.value}" }) { route ->
                FavoriteRow("home-fav-route-${route.id.value}", "${route.origin.name} → ${route.destination.name}") {
                    home.openRoute(route)
                }
            }
            items(homeState.favoriteTrains, key = { "train-${it.id.value}" }) { train ->
                FavoriteRow("home-fav-train-${train.id.value}", trainLabel(train)) { home.openTrain(train) }
            }
        }
        item {
            // Bottom spacing keeps the last favorite above the navigation bar.
            androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = TrenifySpacing.l))
        }
    }
}

@Composable
private fun trainLabel(train: FavoriteTrain): String {
    val number = stringResource(R.string.home_train_number, train.number.value)
    return train.originName?.let { "$number · $it" } ?: number
}

@Composable
private fun RecentRow(entry: SearchHistoryEntry, onOpen: () -> Unit) {
    val title = when (entry) {
        is JourneySearchHistoryEntry -> "${entry.origin.name} → ${entry.destination.name}"
        is TrainSearchHistoryEntry -> stringResource(R.string.home_train_number, entry.number.value)
    }
    Surface(
        Modifier.fillMaxWidth().testTag("home-recent-${entry.id.value}"),
        shape = TrenifyShapes.card,
        color = TrenifyTheme.colors.surface,
    ) {
        Column(
            Modifier.clickable(role = Role.Button, onClick = onOpen)
                .defaultMinSize(minHeight = 56.dp).padding(TrenifySpacing.l),
            verticalArrangement = Arrangement.spacedBy(TrenifySpacing.xs),
        ) {
            Text(title, style = TrenifyTheme.typography.trainIdentity, color = TrenifyTheme.colors.textPrimary)
            Text(
                stringResource(R.string.home_history),
                style = TrenifyTheme.typography.caption,
                color = TrenifyTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun FavoriteRow(tag: String, title: String, onOpen: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().testTag(tag),
        shape = TrenifyShapes.card,
        color = TrenifyTheme.colors.surface,
    ) {
        Text(
            title,
            Modifier.clickable(role = Role.Button, onClick = onOpen)
                .defaultMinSize(minHeight = 56.dp).padding(TrenifySpacing.l),
            style = TrenifyTheme.typography.bodyEmphasized,
            color = TrenifyTheme.colors.textPrimary,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JourneyComposerCard(
    originText: String,
    destinationText: String,
    onOriginText: (String) -> Unit,
    onDestinationText: (String) -> Unit,
    onSwap: () -> Unit,
    suggestionsLabel: String,
    suggestions: List<Station>,
    editingOrigin: Boolean,
    onSelectSuggestion: (Station) -> Unit,
    dateLabel: String,
    timeLabel: String,
    journeySearch: JourneySearchComponent?,
) {
    val colors = TrenifyTheme.colors
    val localeTag = platformLocaleTag()
    val journeyState = journeySearch?.state?.subscribeAsState()?.value
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth().testTag("home-composer"),
        shape = TrenifyShapes.cardLarge,
        color = colors.surfaceRaised,
    ) {
        Column(Modifier.padding(TrenifySpacing.l), verticalArrangement = Arrangement.spacedBy(TrenifySpacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TrenifySpacing.s)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TrenifySpacing.s)) {
                    StationQueryField(
                        label = stringResource(R.string.home_from),
                        value = originText,
                        placeholder = stringResource(R.string.home_from_placeholder),
                        onValue = onOriginText,
                        tag = "home-origin",
                    )
                    StationQueryField(
                        label = stringResource(R.string.home_to),
                        value = destinationText,
                        placeholder = stringResource(R.string.home_to_placeholder),
                        onValue = onDestinationText,
                        tag = "home-destination",
                    )
                }
                val swapLabel = stringResource(R.string.home_swap)
                TextButton(
                    onClick = onSwap,
                    modifier = Modifier.defaultMinSize(minWidth = 56.dp, minHeight = 56.dp)
                        .semantics { contentDescription = swapLabel }
                        .testTag("home-swap"),
                ) {
                    Text("⇅", style = TrenifyTheme.typography.screenTitle, color = colors.accent)
                }
            }
            val journeyFailure = journeyState?.failure
            if (journeyState?.loading == true) {
                Column(verticalArrangement = Arrangement.spacedBy(TrenifySpacing.xs)) {
                    androidx.compose.material3.LinearProgressIndicator(
                        Modifier.fillMaxWidth().testTag("home-search-loading"),
                        color = colors.accent,
                        trackColor = colors.divider,
                    )
                    Text(
                        stringResource(R.string.home_searching_stations),
                        style = TrenifyTheme.typography.caption,
                        color = colors.textSecondary,
                    )
                }
            }
            if (journeyFailure != null) {
                Text(
                    homeFailureMessage(journeyFailure, suggestions.isNotEmpty()),
                    Modifier.testTag("home-search-error"),
                    style = TrenifyTheme.typography.status,
                    color = colors.statusCancelled,
                )
            }
            if (suggestions.isNotEmpty()) {
                Text(
                    suggestionsLabel,
                    style = TrenifyTheme.typography.label,
                    color = colors.textSecondary,
                    modifier = Modifier.semantics { heading() },
                )
                suggestions.take(5).forEach { station ->
                    TextButton(
                        onClick = { onSelectSuggestion(station) },
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                            .testTag("home-suggestion-${station.id.value}"),
                    ) {
                        Text(
                            station.name,
                            Modifier.fillMaxWidth(),
                            style = TrenifyTheme.typography.body,
                            color = colors.textPrimary,
                        )
                    }
                }
                // Suggestions always belong to the currently edited side; the
                // shared component clears them on swap/select/new text.
                @Suppress("UNUSED_EXPRESSION") editingOrigin
            }
            Row(horizontalArrangement = Arrangement.spacedBy(TrenifySpacing.s)) {
                DateTimeButton(
                    label = dateLabel,
                    value = journeyState?.let { formatSearchDate(it.date, localeTag) }.orEmpty(),
                    onClick = { showDate = true },
                    tag = "home-date",
                    modifier = Modifier.weight(1f),
                )
                DateTimeButton(
                    label = timeLabel,
                    value = journeyState?.let { formatSearchTime(it.timeHour, it.timeMinute, localeTag) }.orEmpty(),
                    onClick = { showTime = true },
                    tag = "home-time",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    if (showDate && journeyState != null) {
        // UTC-date convention: the picker displays the UTC calendar day, so a
        // Rome-midnight instant would show the previous day in CET/CEST.
        val picker = rememberDatePickerState(initialSelectedDateMillis = datePickerMillis(journeyState.date))
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        picker.selectedDateMillis?.let { millis ->
                            journeySearch?.date(semanticDateFromPicker(millis).toString())
                        }
                        showDate = false
                    },
                    modifier = Modifier.testTag("home-date-confirm"),
                ) { Text(stringResource(android.R.string.ok)) }
            },
        ) { DatePicker(picker) }
    }
    if (showTime && journeyState != null) {
        val picker = rememberTimePickerState(journeyState.timeHour, journeyState.timeMinute, true)
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val hour = "%02d".format(picker.hour)
                        val minute = "%02d".format(picker.minute)
                        journeySearch?.time("$hour:$minute")
                        showTime = false
                    },
                    modifier = Modifier.testTag("home-time-confirm"),
                ) { Text(stringResource(android.R.string.ok)) }
            },
            text = { TimePicker(picker) },
        )
    }
}

/**
 * Station-lookup failure wording mirrors the shared taxonomy (cached
 * suggestions keep rendering next to the error, like the legacy form).
 */
@Composable
private fun homeFailureMessage(failure: DomainFailure, hasCachedContent: Boolean): String = when (failure) {
    DomainFailure.OFFLINE ->
        stringResource(if (hasCachedContent) R.string.home_error_offline_cached else R.string.home_error_offline)
    DomainFailure.TEMPORARY -> stringResource(R.string.home_error_temporary)
    DomainFailure.NOT_FOUND -> stringResource(R.string.home_error_train_not_found)
    DomainFailure.INVALID_REQUEST -> stringResource(R.string.home_error_invalid_request)
    DomainFailure.UNSUPPORTED -> stringResource(R.string.home_error_unsupported)
    DomainFailure.INVALID_RESPONSE -> stringResource(R.string.home_error_invalid_response)
}

@Composable
private fun StationQueryField(label: String, value: String, placeholder: String, onValue: (String) -> Unit, tag: String) {
    val colors = TrenifyTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(TrenifySpacing.xs)) {
        Text(label, style = TrenifyTheme.typography.label, color = colors.textSecondary)
        TextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).dpadExitSingleLine().testTag(tag),
            placeholder = { Text(placeholder, style = TrenifyTheme.typography.body, color = colors.textTertiary) },
            textStyle = TrenifyTheme.typography.routeStation.copy(color = colors.textPrimary),
            singleLine = true,
            shape = TrenifyShapes.control,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = colors.surface,
                unfocusedContainerColor = colors.surface,
                focusedIndicatorColor = colors.focus,
                unfocusedIndicatorColor = colors.divider,
            ),
        )
    }
}

@Composable
private fun DateTimeButton(label: String, value: String, onClick: () -> Unit, tag: String, modifier: Modifier = Modifier) {
    val colors = TrenifyTheme.colors
    Surface(modifier.testTag(tag), shape = TrenifyShapes.control, color = colors.surface) {
        Column(
            Modifier.clickable(role = Role.Button, onClick = onClick)
                .defaultMinSize(minHeight = 56.dp).padding(TrenifySpacing.m),
            verticalArrangement = Arrangement.spacedBy(TrenifySpacing.xs),
        ) {
            Text(label, style = TrenifyTheme.typography.label, color = colors.textSecondary)
            Text(value, style = TrenifyTheme.typography.bodyEmphasized, color = colors.textPrimary)
        }
    }
}


