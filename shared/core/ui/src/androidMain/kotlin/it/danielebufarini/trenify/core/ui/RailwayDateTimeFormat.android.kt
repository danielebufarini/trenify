package it.danielebufarini.trenify.core.ui

import kotlinx.datetime.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.time.Instant

private val rome: ZoneId = ZoneId.of("Europe/Rome")

actual fun platformLocaleTag(): String = Locale.getDefault().toLanguageTag()

private fun Instant.toJava(): java.time.Instant = java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong())

actual fun formatRailwayDateTime(instant: Instant, localeTag: String): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(Locale.forLanguageTag(localeTag))
        .withZone(rome)
        .format(instant.toJava())

actual fun formatRailwayDate(date: LocalDate, localeTag: String): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.forLanguageTag(localeTag))
        .format(java.time.LocalDate.of(date.year, date.monthNumber, date.dayOfMonth))

actual fun formatRailwayTime(instant: Instant, localeTag: String): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(Locale.forLanguageTag(localeTag))
        .withZone(rome)
        .format(instant.toJava())
