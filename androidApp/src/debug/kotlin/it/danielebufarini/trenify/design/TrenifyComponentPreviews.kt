package it.danielebufarini.trenify.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import it.danielebufarini.trenify.design.component.*
import it.danielebufarini.trenify.design.theme.TrenifySpacing
import it.danielebufarini.trenify.design.theme.TrenifyTheme

/** Debug source set only; never registered in production navigation. */
@Preview(name = "Light / component family", showBackground = true, widthDp = 390, heightDp = 1600)
@Preview(name = "Large text / narrow", fontScale = 2f, showBackground = true, widthDp = 320, heightDp = 1800)
@Composable
private fun LightComponents() = Components(dark = false)

@Preview(name = "Dark / component family", showBackground = true, widthDp = 390, heightDp = 1600)
@Composable
private fun DarkComponents() = Components(dark = true)

@Composable
private fun Components(dark: Boolean) {
    TrenifyTheme(darkTheme = dark, reduceMotion = true) {
        Surface(color = TrenifyTheme.colors.background) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(TrenifySpacing.screenHorizontal),
                verticalArrangement = Arrangement.spacedBy(TrenifySpacing.xl)) {
                TrenifySectionHeader("Component family", subtitle = "Deterministic railway display fixtures")
                TrenifySegmentedControl(listOf(TrenifySegment("journey", "Journey"), TrenifySegment("train", "Train")), "journey", {})
                TrenifyStationField("From", "San Benedetto del Tronto Porto d’Ascoli", "Choose station", {}, selected = true, onClear = {})
                TrenifyStationField("To", null, "Choose station", {}, focused = true)
                TrenifyStationField("Station", "Roma Termini", "Choose station", {}, enabled = false)
                TrenifyPrimaryAction("Search trains", {})
                TrenifyPrimaryAction("Search trains", {}, loading = true)
                TrenifySecondaryAction("Change selection", {}, enabled = false)
                TrenifyJourneyCard("Trenitalia · Frecciarossa 9516", "14:10", "17:19",
                    "Milano Centrale", "Roma Termini", "3 h 09 min", "Direct", {},
                    statusTone = TrenifyStatusTone.OnTime, statusLabel = "On time", platformLabel = "Scheduled platform 8")
                TrenifyStatusPill(TrenifyStatusTone.Delayed, "Delayed +12 min")
                TrenifyStatusPill(TrenifyStatusTone.Cancelled, "Cancelled")
                TrenifyStatusPill(TrenifyStatusTone.Arrived, "Arrived")
                TrenifyStatusPill(TrenifyStatusTone.Warning, "Service warning")
                TrenifyStatusPill(TrenifyStatusTone.Unknown, "Status unavailable")
            }
        }
    }
}
