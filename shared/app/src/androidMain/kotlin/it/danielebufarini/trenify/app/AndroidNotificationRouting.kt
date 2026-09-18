package it.danielebufarini.trenify.app

import android.content.Intent
import android.os.Bundle
import it.danielebufarini.trenify.core.platform.NotificationDestination
import it.danielebufarini.trenify.core.platform.NotificationDestinationCodec
import it.danielebufarini.trenify.core.platform.NotificationTap

/**
 * Thin Android intent bridge for notification taps (T7.13-F).
 *
 * The Activity is a bridge only: it extracts the transport extras and
 * validates them through the shared [NotificationTap] contract, then
 * forwards the typed destination to the shared root. Train/strike business
 * policy is never interpreted here, and no second navigation tree exists.
 */
fun Intent.notificationDestination(): NotificationDestination? {
    val bundle = extras ?: return null
    return NotificationTap.decode(bundle.toDestinationMap())
}

private fun Bundle.toDestinationMap(): Map<String, String?> = buildMap {
    put(NotificationDestinationCodec.KEY_KIND, getString(NotificationDestinationCodec.KEY_KIND))
    put(NotificationDestinationCodec.KEY_PROVIDER, getString(NotificationDestinationCodec.KEY_PROVIDER))
    put(NotificationDestinationCodec.KEY_NUMBER, getString(NotificationDestinationCodec.KEY_NUMBER))
    put(NotificationDestinationCodec.KEY_ORIGIN, getString(NotificationDestinationCodec.KEY_ORIGIN))
    put(NotificationDestinationCodec.KEY_SERVICE_DATE, getString(NotificationDestinationCodec.KEY_SERVICE_DATE))
    put(NotificationDestinationCodec.KEY_STRIKE_ID, getString(NotificationDestinationCodec.KEY_STRIKE_ID))
}
