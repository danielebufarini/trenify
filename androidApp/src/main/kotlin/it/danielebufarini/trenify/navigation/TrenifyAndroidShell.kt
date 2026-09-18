package it.danielebufarini.trenify.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import it.danielebufarini.trenify.R
import it.danielebufarini.trenify.app.*
import it.danielebufarini.trenify.design.component.TrenifyBottomNavigation
import it.danielebufarini.trenify.design.theme.TrenifyTheme

/** Activity owns the graph/root; this composition only borrows it. */
@Composable
fun TrenifyAndroidShell(root: RootComponent) {
    val shell = remember(root) { createShellPresentation(root) }
    DisposableEffect(shell) { onDispose { shell.close() } }
    // T8.14-C3: lifecycle-aware collection; the shell snapshot pauses with
    // the activity instead of collecting while stopped.
    val state by shell.state.collectAsStateWithLifecycle()
    BackHandler(state.canGoBack, shell::back)
    TrenifyTheme {
        TrenifyShellLayout(state, shell::select, shell::back, shell::openSettings) {
            val active = state.active
            key(active.identity) {
                // Every production destination renders through Android-owned
                // native UI (T8.5 Home/Search, T8.6 Journey Results/Detail,
                // T8.7 Station Search/Board + Train Search/Detail, T8.8
                // Monitoring, T8.9 Saved/Favorites/History, T8.10 Alerts
                // overview + Strike Detail, T8.11 Settings). T8.15 removed
                // the shared legacy renderer; no production fallback remains.
                if (active.destination == NativeDestination.Home) {
                    NativeHomeEntry(shell, state)
                } else if (active.destination == NativeDestination.JourneyResults ||
                    active.destination == NativeDestination.JourneyDetail
                ) {
                    NativeJourneyEntry(shell, state)
                } else if (active.stationSearch != null || active.stationBoard != null || active.trainSearch != null || active.trainDetail != null) {
                    it.danielebufarini.trenify.stationtrain.NativeStationTrainEntry(active)
                } else if (active.monitoring != null) {
                    it.danielebufarini.trenify.monitoring.NativeMonitoringEntry(active)
                } else if (active.saved != null) {
                    it.danielebufarini.trenify.saved.NativeSavedEntry(active)
                } else if (active.history != null) {
                    it.danielebufarini.trenify.saved.NativeHistoryEntry(active)
                } else if (active.alerts != null) {
                    it.danielebufarini.trenify.alerts.NativeAlertsEntry(active)
                } else if (active.settings != null) {
                    it.danielebufarini.trenify.settings.NativeSettingsEntry(active)
                } else {
                    // Structurally unreachable: every production destination
                    // has an explicit native entry above. Show nothing rather
                    // than hosting a removed legacy renderer.
                    androidx.compose.foundation.layout.Box(
                        Modifier.fillMaxSize().testTag("unavailable-destination"),
                    )
                }
            }
        }
    }
}

@Composable
private fun NativeJourneyEntry(shell: NativeShellPresentation, state: NativeShellState) {
    // Borrowed live facades for this exact route identity; no graph/root
    // construction, no second router. Shared components stay authoritative
    // for search, realtime, warnings, booking and train navigation.
    val active = state.active
    val results = remember(state) {
        if (active.destination == NativeDestination.JourneyResults) shell.journeyResults(active.identity) else null
    }
    val detail = remember(state) {
        if (active.destination == NativeDestination.JourneyDetail) shell.journeyDetail(active.identity) else null
    }
    if (results != null) {
        it.danielebufarini.trenify.journey.TrenifyJourneyResultsScreen(results)
    } else if (detail != null) {
        it.danielebufarini.trenify.journey.TrenifyJourneyDetailScreen(detail)
    } else {
        // Unavailable Journey anchor cannot happen for an active
        // Results/Detail entry; show nothing rather than legacy rendering.
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize().testTag("journey-loading"),
        )
    }
}

@Composable
private fun NativeHomeEntry(shell: NativeShellPresentation, state: NativeShellState) {
    // The collected shell state above already refreshes on every shared
    // navigation change, so these borrowed live components stay current.
    // No graph/root/component is constructed here.
    val home = remember(state) { shell.homeComponent() }
    val search = remember(state) { shell.journeySearchComponent() }
    if (home != null) {
        it.danielebufarini.trenify.home.TrenifyHomeScreen(home, search)
    } else {
        // Unavailable Home anchor cannot happen for an active Home in the
        // Search area; show nothing rather than legacy Home rendering.
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize().testTag("home-loading"),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrenifyShellLayout(
    state: NativeShellState,
    onSelect: (NativePrimaryArea) -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        modifier = Modifier.testTag("android-shell"),
        containerColor = TrenifyTheme.colors.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            // Every production destination uses this shell TopAppBar
            // Back/title native chrome.
            TopAppBar(
                title = { Text(stringResource(shellTitle(state)), style = TrenifyTheme.typography.sectionTitle) },
                navigationIcon = {
                    if (state.canGoBack) IconButton(onClick = onBack, modifier = Modifier.testTag("shell-back")) {
                        Icon(painterResource(R.drawable.shell_back), stringResource(R.string.shell_back))
                    }
                },
                actions = {
                    if (state.active.destination != NativeDestination.Settings) IconButton(onClick = onSettings,
                        modifier = Modifier.testTag("shell-settings")) {
                        Icon(painterResource(R.drawable.shell_settings), stringResource(R.string.shell_settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = TrenifyTheme.colors.background),
            )
        },
        bottomBar = { TrenifyBottomNavigation(state.primaryArea, onSelect) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { content() }
    }
}

internal val NativePrimaryArea.label: Int get() = when (this) {
    NativePrimaryArea.Search -> R.string.shell_search
    NativePrimaryArea.Monitoring -> R.string.shell_monitoring
    NativePrimaryArea.Saved -> R.string.shell_saved
    NativePrimaryArea.Alerts -> R.string.shell_alerts
}

/**
 * Shell title for migrated destinations: Journey Results/Detail show their
 * own titles rather than the generic primary-area label; everything else
 * keeps the existing area/Settings titles.
 */
internal fun shellTitle(state: NativeShellState): Int = when (state.active.destination) {
    NativeDestination.JourneyResults -> R.string.journey_results_title
    NativeDestination.JourneyDetail -> R.string.journey_detail_title
    NativeDestination.History -> R.string.home_history
    NativeDestination.StationSearch -> R.string.st_stationSearch
    NativeDestination.StationBoard -> R.string.st_stationBoard
    NativeDestination.TrainSearch -> R.string.st_trainSearch
    NativeDestination.TrainDetail -> R.string.st_trainDetail
    NativeDestination.AlertsOverview -> R.string.alerts_title
    NativeDestination.StrikeDetail -> R.string.alerts_detail_title
    NativeDestination.Settings -> R.string.shell_settings
    else -> state.primaryArea.label
}
