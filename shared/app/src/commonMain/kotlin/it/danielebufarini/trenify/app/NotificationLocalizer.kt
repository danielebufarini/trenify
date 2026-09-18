package it.danielebufarini.trenify.app

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

import it.danielebufarini.trenify.core.domain.StrikeChangeKind
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.ui.formatRailwayDateTime
import it.danielebufarini.trenify.core.ui.platformLocaleTag
import it.danielebufarini.trenify.core.ui.resources.*
import it.danielebufarini.trenify.core.ui.strikeRelevanceResource
import it.danielebufarini.trenify.core.ui.trainStatusResource
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.getString

/**
 * Shared presentation/application localizer for notification content
 * (T7.14-C).
 *
 * Coordinators create notification text outside Composable rendering, so
 * they resolve user-facing wording through this boundary instead of calling
 * Compose `stringResource()` from domain code or hardcoding English. The
 * canonical catalog stays Compose Resources; native presenters receive the
 * final localized title/body and implement no wording rules of their own.
 *
 * Notification/event identity never depends on rendered text: fingerprints,
 * dedup keys and destination payloads are built from the semantic event
 * only, so changing locale or wording creates no new event identity.
 */
@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
interface NotificationLocalizer {
    suspend fun trainTitle(number: String): String
    suspend fun delayChanged(previousMinutes: Int?, currentMinutes: Int): String
    suspend fun trainCancelled(): String
    suspend fun trainPartiallyCancelled(cancelledStops: List<String>): String
    suspend fun platformChanged(stationName: String?, platform: String): String
    suspend fun scheduleChanged(stationName: String): String
    suspend fun statusChanged(status: TrainStatus): String
    suspend fun trainDeparted(): String
    suspend fun trainArrived(): String
    suspend fun trainRouteChanged(): String
    suspend fun strikeTitle(kind: StrikeChangeKind): String
    suspend fun strikeBody(sector: String, area: String, start: String, end: String): String
    suspend fun strikeArea(regions: List<String>, relevance: it.danielebufarini.trenify.core.model.StrikeRelevance): String
    suspend fun strikeMoment(instant: kotlin.time.Instant): String
}

/**
 * Production localizer backed by the shared Compose Resources catalog for
 * the current platform locale. Railway instants passed in as preformatted
 * text must already use Europe/Rome interpretation.
 */
@OptIn(ExperimentalResourceApi::class, ExperimentalObjCRefinement::class)
@HiddenFromObjC
class ResourceNotificationLocalizer : NotificationLocalizer {
    override suspend fun trainTitle(number: String): String =
        getString(Res.string.notif_train_title, number)

    override suspend fun delayChanged(previousMinutes: Int?, currentMinutes: Int): String =
        // An unknown previous delay stays explicitly unknown: it must never
        // render as a fabricated zero (FR-DATA-002).
        if (previousMinutes == null) getString(Res.string.notif_delay_changed_unknown, currentMinutes)
        else getString(Res.string.notif_delay_changed, previousMinutes, currentMinutes)

    override suspend fun trainCancelled(): String = getString(Res.string.notif_cancelled)

    override suspend fun trainPartiallyCancelled(cancelledStops: List<String>): String =
        if (cancelledStops.isEmpty()) getString(Res.string.notif_partially_cancelled)
        else getString(Res.string.notif_partially_cancelled_stops, cancelledStops.joinToString())

    override suspend fun platformChanged(stationName: String?, platform: String): String =
        getString(Res.string.notif_platform_changed, stationName?.let { "$it: " }.orEmpty(), platform)

    override suspend fun scheduleChanged(stationName: String): String =
        getString(Res.string.notif_schedule_changed, stationName)

    override suspend fun statusChanged(status: TrainStatus): String =
        getString(Res.string.notif_status_changed, getString(trainStatusResource(status)))

    override suspend fun trainDeparted(): String = getString(Res.string.notif_departed)

    override suspend fun trainArrived(): String = getString(Res.string.notif_arrived)

    override suspend fun trainRouteChanged(): String = getString(Res.string.notif_route_changed)

    override suspend fun strikeTitle(kind: StrikeChangeKind): String = getString(when (kind) {
        StrikeChangeKind.SCHEDULED -> Res.string.notif_strike_scheduled
        StrikeChangeKind.MODIFIED -> Res.string.notif_strike_modified
        StrikeChangeKind.REVOKED -> Res.string.notif_strike_revoked
    })

    override suspend fun strikeBody(sector: String, area: String, start: String, end: String): String =
        getString(Res.string.notif_strike_body, sector, area, start, end)

    override suspend fun strikeArea(
        regions: List<String>,
        relevance: it.danielebufarini.trenify.core.model.StrikeRelevance,
    ): String {
        // Geographic regions win; otherwise the localized relevance label —
        // never a raw enum name. Unknown relevance maps to localized Unknown.
        val area = regions.joinToString()
        if (area.isNotBlank()) return area
        val resource = strikeRelevanceResource(relevance) ?: Res.string.unknown
        return getString(resource)
    }

    override suspend fun strikeMoment(instant: kotlin.time.Instant): String =
        formatRailwayDateTime(instant, platformLocaleTag())
}
