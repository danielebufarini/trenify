package it.danielebufarini.trenify.home

import java.time.LocalDate as JavaLocalDate
import java.time.ZoneOffset
import kotlinx.datetime.LocalDate

/**
 * Material DatePicker millis use UTC-date semantics: the shown calendar day
 * is the UTC date of the instant. A Rome-midnight instant would display the
 * previous UTC day in CET/CEST, so the semantic Rome date is represented as
 * UTC midnight of the same calendar day, and confirmation maps the UTC
 * calendar day back 1:1. The shared RailwayTime semantics never change.
 */
internal fun datePickerMillis(date: LocalDate): Long =
    JavaLocalDate.of(date.year, date.monthNumber, date.dayOfMonth)
        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun semanticDateFromPicker(millis: Long): LocalDate {
    val utc = java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
    return LocalDate(utc.year, utc.monthValue, utc.dayOfMonth)
}
