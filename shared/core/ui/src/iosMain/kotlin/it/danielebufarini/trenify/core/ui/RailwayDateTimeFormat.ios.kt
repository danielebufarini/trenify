package it.danielebufarini.trenify.core.ui

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localeIdentifier
import platform.Foundation.timeZoneWithName
import kotlin.time.Instant

actual fun platformLocaleTag(): String = NSLocale.currentLocale.localeIdentifier

private fun Instant.toNSDate(): NSDate =
    NSDate.dateWithTimeIntervalSince1970(epochSeconds.toDouble() + nanosecondsOfSecond / 1_000_000_000.0)

private fun formatter(
    localeTag: String,
    dateStyle: ULong,
    timeStyle: ULong,
): NSDateFormatter = NSDateFormatter().apply {
    locale = NSLocale(localeIdentifier = localeTag)
    timeZone = requireNotNull(NSTimeZone.timeZoneWithName("Europe/Rome")) { "Europe/Rome must resolve" }
    this.dateStyle = dateStyle
    this.timeStyle = timeStyle
}

actual fun formatRailwayDateTime(instant: Instant, localeTag: String): String =
    formatter(localeTag, NSDateFormatterMediumStyle, NSDateFormatterShortStyle)
        .stringFromDate(instant.toNSDate())

actual fun formatRailwayDate(date: LocalDate, localeTag: String): String =
    formatter(localeTag, NSDateFormatterMediumStyle, NSDateFormatterNoStyle)
        .stringFromDate(date.atStartOfDayIn(TimeZone.of("Europe/Rome")).toNSDate())

actual fun formatRailwayTime(instant: Instant, localeTag: String): String =
    formatter(localeTag, NSDateFormatterNoStyle, NSDateFormatterShortStyle)
        .stringFromDate(instant.toNSDate())
