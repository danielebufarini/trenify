package it.danielebufarini.trenify.navigation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.danielebufarini.trenify.R

private data class AppMetadata(val name: String, val version: String, val buildDate: String)

@Composable
internal fun TrenifyAboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val metadata = remember(context) {
        val manager = context.packageManager
        @Suppress("DEPRECATION")
        val info = manager.getPackageInfo(context.packageName, 0)
        AppMetadata(context.applicationInfo.loadLabel(manager).toString(), info.versionName.orEmpty(),
            context.assets.open("TrenifyBuildDate.txt").bufferedReader().use { it.readText().trim() })
    }
    AlertDialog(
        modifier = Modifier.testTag("about-dialog"),
        onDismissRequest = onDismiss,
        title = {
            Text(metadata.name, Modifier.fillMaxWidth().testTag("about-name"), textAlign = TextAlign.Center)
        },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(painterResource(R.drawable.about_app_icon), metadata.name,
                    Modifier.size(112.dp).clip(RoundedCornerShape(24.dp)).testTag("about-icon"))
                Text(metadata.version, Modifier.fillMaxWidth().testTag("about-version"), textAlign = TextAlign.Center)
                Text("${stringResource(R.string.about_build_date)}: ${metadata.buildDate}",
                    Modifier.fillMaxWidth().testTag("about-build-date"), textAlign = TextAlign.Center)
            }
        },
        confirmButton = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextButton(onDismiss, Modifier.testTag("about-close")) { Text(stringResource(R.string.about_close)) }
            }
        },
    )
}
