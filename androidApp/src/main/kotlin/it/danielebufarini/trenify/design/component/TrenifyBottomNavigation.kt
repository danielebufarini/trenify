package it.danielebufarini.trenify.design.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import it.danielebufarini.trenify.R
import it.danielebufarini.trenify.app.NativePrimaryArea
import it.danielebufarini.trenify.design.theme.TrenifySpacing
import it.danielebufarini.trenify.design.theme.TrenifyTheme
import it.danielebufarini.trenify.navigation.label

/**
 * Material tabs retain native selected semantics, ripple, scalable labels and system insets.
 *
 * System font scaling is always respected: the bar measures every localized
 * label at the requested scale and shows full text labels only while all four
 * fit on one line in their slots. When any label would need an arbitrary
 * mid-word break, the whole bar switches to an icon-focused variant with the
 * complete localized name exposed to accessibility services. The switch
 * depends only on measured layout fit, never on destination identity.
 */
@Composable
fun TrenifyBottomNavigation(selected: NativePrimaryArea, onSelect: (NativePrimaryArea) -> Unit) {
    BoxWithConstraints(Modifier.testTag("shell-navigation")) {
        val names = NativePrimaryArea.entries.map { stringResource(it.label) }
        val measurer = rememberTextMeasurer()
        val caption = TrenifyTheme.typography.caption
        val density = LocalDensity.current
        // Uniform bar: labels appear only when every destination fits on one
        // line, so no tab is ever singled out by language or name length.
        val showLabels = remember(names, maxWidth, density) {
            val slotPx = with(density) { (maxWidth / 4 - 8.dp).toPx() }
            names.all { measurer.measure(AnnotatedString(it), style = caption).size.width <= slotPx }
        }
        NavigationBar(
            containerColor = TrenifyTheme.colors.surfaceRaised,
            tonalElevation = 0.dp,
            modifier = Modifier.clip(RoundedCornerShape(topStart = TrenifySpacing.xl, topEnd = TrenifySpacing.xl)),
        ) {
            NativePrimaryArea.entries.forEachIndexed { index, area ->
                val name = names[index]
                NavigationBarItem(
                    modifier = Modifier.padding(vertical = TrenifySpacing.xs).heightIn(min = 56.dp)
                        .semantics { if (!showLabels) contentDescription = name }
                        .testTag("shell-tab-${area.name}"),
                    selected = area == selected,
                    onClick = { onSelect(area) },
                    alwaysShowLabel = showLabels,
                    icon = {
                        Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
                            Icon(painterResource(area.icon), contentDescription = null)
                            if (area == selected) {
                                Icon(
                                    painterResource(R.drawable.shell_check),
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp).align(Alignment.TopEnd),
                                )
                            }
                        }
                    },
                    label = if (showLabels) {
                        { Text(name, style = TrenifyTheme.typography.caption) }
                    } else {
                        null
                    },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = TrenifyTheme.colors.selection,
                        selectedIconColor = TrenifyTheme.colors.accent,
                        selectedTextColor = TrenifyTheme.colors.textPrimary,
                        unselectedIconColor = TrenifyTheme.colors.textSecondary,
                        unselectedTextColor = TrenifyTheme.colors.textSecondary,
                    ),
                )
            }
        }
    }
}

private val NativePrimaryArea.icon: Int get() = when (this) {
    NativePrimaryArea.Search -> R.drawable.shell_search
    NativePrimaryArea.Monitoring -> R.drawable.shell_monitoring
    NativePrimaryArea.Saved -> R.drawable.shell_saved
    NativePrimaryArea.Alerts -> R.drawable.shell_alerts
}
