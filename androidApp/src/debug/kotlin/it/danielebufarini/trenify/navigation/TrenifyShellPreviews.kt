package it.danielebufarini.trenify.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import it.danielebufarini.trenify.app.*
import it.danielebufarini.trenify.design.theme.TrenifyTheme

@Preview(name = "Search")
@Composable private fun SearchShell() = ShellExample(NativePrimaryArea.Search)
@Preview(name = "Monitoring")
@Composable private fun MonitoringShell() = ShellExample(NativePrimaryArea.Monitoring)
@Preview(name = "Saved")
@Composable private fun SavedShell() = ShellExample(NativePrimaryArea.Saved)
@Preview(name = "Alerts")
@Composable private fun AlertsShell() = ShellExample(NativePrimaryArea.Alerts)
@Preview(name = "Dark monitoring", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable private fun DarkShell() = ShellExample(NativePrimaryArea.Monitoring, true)

@Composable private fun ShellExample(area: NativePrimaryArea, dark: Boolean = false) {
    TrenifyTheme(darkTheme = dark, reduceMotion = true) {
        TrenifyShellLayout(NativeShellState(area, NativeShellEntry(1, NativeDestination.Home, false), emptyList()), {}, {}, {}) {
            Text("Trenify")
        }
    }
}
