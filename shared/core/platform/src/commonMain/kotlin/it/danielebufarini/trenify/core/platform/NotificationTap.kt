package it.danielebufarini.trenify.core.platform

/**
 * Pure notification-tap transport contract shared by the Android content
 * intent and the iOS response callback (T7.13-F/G).
 *
 * Everything here is platform-type free so the encoding, stability and
 * validation rules are unit-tested on JVM/iOS without a device:
 * - [extras] carries only the minimal validated destination payload;
 * - [dataUri] makes taps for different destinations distinct so they never
 *   overwrite each other's destinations;
 * - [requestCode] is stable per destination for the same reason;
 * - [decode] validates and returns null for missing/malformed payloads,
 *   which hosts must safely ignore.
 *
 * Title/body are never consulted here: navigation never parses display
 * text, and the anti-spam identifier travels separately.
 */
object NotificationTap {
    const val ACTION = "it.danielebufarini.trenify.NOTIFICATION_TAP"
    const val DATA_SCHEME = "trenify"
    const val DATA_HOST = "notification"

    fun extras(destination: NotificationDestination): Map<String, String> =
        NotificationDestinationCodec.encode(destination)

    fun dataUri(destination: NotificationDestination?): String {
        val key = destination?.routingKey ?: "open"
        return "$DATA_SCHEME://$DATA_HOST/${key.urlEncoded()}"
    }

    fun requestCode(destination: NotificationDestination?, fallbackId: String): Int =
        (destination?.routingKey ?: fallbackId).hashCode()

    /**
     * Validates one tap payload. Null extras, missing kind and any invalid
     * field decode to null: the caller stays on the current screen.
     */
    fun decode(extras: Map<String, String?>?): NotificationDestination? {
        if (extras == null) return null
        val fields = extras.mapNotNull { (key, value) ->
            if (value == null) null else key to value
        }.toMap()
        if (fields.isEmpty()) return null
        return NotificationDestinationCodec.decode(fields)
    }

    /**
     * Minimal UTF-8 percent-encoding for the data-URI path segment, kept in
     * common code so URI stability is unit-tested without a device.
     */
    private fun String.urlEncoded(): String {
        val hex = "0123456789ABCDEF"
        return buildString {
            for (byte in this@urlEncoded.encodeToByteArray()) {
                val c = byte.toInt() and 0xFF
                val unreserved = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code ||
                    c in '0'.code..'9'.code || c == '-'.code || c == '_'.code ||
                    c == '.'.code || c == '~'.code || c == ':'.code
                if (unreserved) append(c.toChar())
                else {
                    append('%')
                    append(hex[c ushr 4])
                    append(hex[c and 0x0F])
                }
            }
        }
    }
}
