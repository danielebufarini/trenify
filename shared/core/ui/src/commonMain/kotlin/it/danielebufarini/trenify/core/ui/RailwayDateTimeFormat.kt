package it.danielebufarini.trenify.core.ui

import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * Locale-aware railway date/time presentation (T7.14-C).
 *
 * Railway interpretation is always `Europe/Rome` ([RailwayTime.zone]
 * semantics live in the platform actuals); only ordering, month/day names
 * and separators follow the given BCP-47 locale tag. Persisted instants,
 * route/search semantics and service dates never change with locale.
 */
expect fun platformLocaleTag(): String

expect fun formatRailwayDateTime(instant: Instant, localeTag: String): String

expect fun formatRailwayDate(date: LocalDate, localeTag: String): String

expect fun formatRailwayTime(instant: Instant, localeTag: String): String
