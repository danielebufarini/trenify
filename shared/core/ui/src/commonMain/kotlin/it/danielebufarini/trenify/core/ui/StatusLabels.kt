package it.danielebufarini.trenify.core.ui

import it.danielebufarini.trenify.core.model.StrikeRelevance
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.ui.resources.*
import org.jetbrains.compose.resources.StringResource

/**
 * Explicit localized mappings for user-visible enum values (T7.14-C).
 *
 * Production UI must never expose `enum.name` (or lowercased/underscored
 * variants) as final user-visible text. These pure resource mappings keep
 * the wording in the Compose Resources catalog while letting non-Composable
 * presentation code (e.g. notification localizers) resolve the same labels.
 */
fun trainStatusResource(status: TrainStatus): StringResource = when (status) {
    TrainStatus.NOT_DEPARTED -> Res.string.not_departed
    TrainStatus.RUNNING -> Res.string.running
    TrainStatus.ARRIVED -> Res.string.arrived
    TrainStatus.CANCELLED -> Res.string.cancelled
    TrainStatus.PARTIALLY_CANCELLED -> Res.string.partially_cancelled
    TrainStatus.DIVERTED -> Res.string.diverted
    TrainStatus.RESCHEDULED -> Res.string.rescheduled
    TrainStatus.UNKNOWN -> Res.string.unknown
}

fun strikeRelevanceResource(relevance: StrikeRelevance): StringResource? = when (relevance) {
    StrikeRelevance.LOCAL -> Res.string.strike_relevance_local
    StrikeRelevance.PROVINCIAL -> Res.string.strike_relevance_provincial
    StrikeRelevance.REGIONAL -> Res.string.strike_relevance_regional
    StrikeRelevance.INTERREGIONAL -> Res.string.strike_relevance_interregional
    StrikeRelevance.NATIONAL -> Res.string.strike_relevance_national
    StrikeRelevance.UNKNOWN -> null
}
