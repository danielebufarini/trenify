package it.danielebufarini.trenify.core.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CancellationException

/**
 * Opens validated booking URLs in the user's browser. Validation stays in domain policy;
 * the HTTPS gate here is defense in depth at the platform boundary.
 *
 * No `resolveActivity()` pre-flight: on Android 11+ that query is filtered by package
 * visibility, so its absence must not be mistaken for "no handler". `startActivity()`
 * directly and map a missing handler to `false`.
 */
internal class AndroidExternalUrlLauncher(context: Context) : ExternalUrlLauncher {
    private val appContext = context.applicationContext

    override suspend fun open(url: String): Boolean {
        val uri = Uri.parse(url)
        if (uri.scheme != "https") return false
        return try {
            appContext.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
