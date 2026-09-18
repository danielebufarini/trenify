package it.danielebufarini.trenify.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import it.danielebufarini.trenify.R
import it.danielebufarini.trenify.core.model.TrainCategory
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.design.component.TrenifyStatusTone
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val ROME: ZoneId = ZoneId.of("Europe/Rome")
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Day/month formatters follow the device locale (month abbreviations are
 * user-visible words), mirroring iOS `.autoupdatingCurrent` behavior.
 * Built per call so a runtime locale change is honored; interpretation
 * stays Europe/Rome regardless of the host timezone.
 */
private fun dateFormat(): DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

private fun dateTimeFormat(): DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.getDefault())

/**
 * Cross-feature railway presentation formatting. Railway wall times always
 * render in Europe/Rome, independent of the host timezone; durations stay in
 * whole minutes from shared state. No business logic lives here.
 */
fun railwayTime(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds).atZone(ROME).format(TIME_FORMAT)

fun railwayDate(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds).atZone(ROME).format(dateFormat())

fun railwayDateTime(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds).atZone(ROME).format(dateTimeFormat())

/** Localized service-date label from an ISO-8601 date; locale medium format. */
fun railwayServiceDateLabel(iso: String): String =
    LocalDate.parse(iso).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/** Localized train-category label from shared category identity. */
@Composable
fun railwayCategoryLabel(category: TrainCategory): String = when (category) {
    TrainCategory.REG -> stringResource(R.string.st_categoryREG)
    TrainCategory.IC -> stringResource(R.string.st_categoryIC)
    TrainCategory.EC -> stringResource(R.string.st_categoryEC)
    TrainCategory.EN -> stringResource(R.string.st_categoryEN)
    TrainCategory.FR -> stringResource(R.string.st_categoryFR)
    TrainCategory.FA -> stringResource(R.string.st_categoryFA)
    TrainCategory.FB -> stringResource(R.string.st_categoryFB)
    else -> category.code
}

fun railwayDurationLabel(durationMinutes: Long): String {
    val hours = durationMinutes / 60
    val minutes = durationMinutes % 60
    return if (hours > 0) "${hours}h ${minutes.toString().padStart(2, '0')}m" else "${minutes}m"
}

/**
 * Semantic realtime tone for a railway service. Scheduled Journey legs without
 * correlated enrichment stay Unknown; a null delay never implies on-time.
 */
fun railwayStatusTone(status: TrainStatus, delayMinutes: Int?): TrenifyStatusTone = when (status) {
    TrainStatus.CANCELLED, TrainStatus.PARTIALLY_CANCELLED -> TrenifyStatusTone.Cancelled
    TrainStatus.ARRIVED -> TrenifyStatusTone.Arrived
    TrainStatus.DIVERTED, TrainStatus.RESCHEDULED -> TrenifyStatusTone.Warning
    TrainStatus.RUNNING, TrainStatus.NOT_DEPARTED -> when {
        (delayMinutes ?: 0) > 0 -> TrenifyStatusTone.Delayed
        delayMinutes == 0 -> TrenifyStatusTone.OnTime
        else -> TrenifyStatusTone.Unknown
    }
    TrainStatus.UNKNOWN -> TrenifyStatusTone.Unknown
}
