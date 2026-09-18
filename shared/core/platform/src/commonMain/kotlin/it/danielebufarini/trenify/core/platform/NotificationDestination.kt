package it.danielebufarini.trenify.core.platform

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Minimal typed local-notification destination (T7.13-D).
 *
 * This is routing metadata only, kept strictly distinct from notification
 * display text (title/body) and from the anti-spam identifier/fingerprint:
 * adding a destination must never change an existing notification `id`.
 *
 * Only destinations for notifications the app genuinely produces exist:
 * - [Train]: monitoring (train/monitor) notifications open one normalized
 *   train run. The train number alone never identifies the run, so the full
 *   normalized identity travels: provider, number, origin reference and the
 *   Europe/Rome service date. These are opaque routing strings here; the
 *   shared root validates and interprets them. Native hosts only
 *   encode/decode this transport form and never decide business semantics.
 * - [Strike]: strike notifications open one strike detail by [StrikeId].
 *
 * No provider DTOs travel here: the strings are the already normalized
 * identity parts, never provider wire payloads.
 */
@Serializable
sealed interface NotificationDestination {
    /** Stable key used for duplicate-callback suppression. Never parsed for display. */
    val routingKey: String

    @Serializable
    @SerialName("train")
    data class Train(
        val provider: String,
        val number: String,
        val origin: String,
        /** ISO-8601 Europe/Rome service date, e.g. `2026-09-05`. */
        val serviceDate: String,
    ) : NotificationDestination {
        override val routingKey: String get() = "train:$provider:$number:$origin:$serviceDate"
    }

    @Serializable
    @SerialName("strike")
    data class Strike(val strikeId: String) : NotificationDestination {
        override val routingKey: String get() = "strike:$strikeId"
    }
}

/**
 * Transport codec between the typed destination and the platform payload
 * representation (Android intent extras, iOS notification userInfo).
 *
 * The map carries only the minimal validated fields under the `trenify.dest.*`
 * namespace. Title/body are never parsed to determine navigation.
 */
object NotificationDestinationCodec {
    const val KEY_KIND = "trenify.dest.kind"
    const val KIND_TRAIN = "train"
    const val KIND_STRIKE = "strike"
    const val KEY_PROVIDER = "trenify.dest.provider"
    const val KEY_NUMBER = "trenify.dest.number"
    const val KEY_ORIGIN = "trenify.dest.origin"
    const val KEY_SERVICE_DATE = "trenify.dest.service_date"
    const val KEY_STRIKE_ID = "trenify.dest.strike_id"

    fun encode(destination: NotificationDestination): Map<String, String> = when (destination) {
        is NotificationDestination.Train -> mapOf(
            KEY_KIND to KIND_TRAIN,
            KEY_PROVIDER to destination.provider,
            KEY_NUMBER to destination.number,
            KEY_ORIGIN to destination.origin,
            KEY_SERVICE_DATE to destination.serviceDate,
        )
        is NotificationDestination.Strike -> mapOf(
            KEY_KIND to KIND_STRIKE,
            KEY_STRIKE_ID to destination.strikeId,
        )
    }

    /**
     * Decodes and validates a transport payload. Returns null for missing,
     * malformed or otherwise invalid destinations; callers must recover
     * safely (remain on the current screen) and never crash or guess a
     * similar-looking target.
     */
    fun decode(fields: Map<String, String>): NotificationDestination? = when (fields[KEY_KIND]) {
        KIND_TRAIN -> {
            val provider = fields[KEY_PROVIDER]?.takeIf { it.isNotBlank() } ?: return null
            val number = fields[KEY_NUMBER]?.takeIf { it.isNotBlank() && it.all(Char::isDigit) } ?: return null
            val origin = fields[KEY_ORIGIN]?.takeIf { it.isNotBlank() } ?: return null
            val serviceDate = fields[KEY_SERVICE_DATE]?.takeIf { isValidServiceDate(it) } ?: return null
            NotificationDestination.Train(provider, number, origin, serviceDate)
        }
        KIND_STRIKE -> {
            val strikeId = fields[KEY_STRIKE_ID]?.takeIf { it.isNotBlank() } ?: return null
            NotificationDestination.Strike(strikeId)
        }
        else -> null
    }

    private fun isValidServiceDate(value: String): Boolean =
        runCatching { LocalDate.parse(value) }.isSuccess
}
