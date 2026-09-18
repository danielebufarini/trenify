package it.danielebufarini.trenify.app

import it.danielebufarini.trenify.core.domain.StrikeChangeKind
import it.danielebufarini.trenify.core.model.StrikeRelevance
import it.danielebufarini.trenify.core.model.TrainStatus
import kotlin.time.Instant

/**
 * Fake-controlled [NotificationLocalizer] for deterministic coordinator
 * tests (T7.14-C). Records every call so tests can prove which template was
 * chosen (notably the unknown-previous-delay branch) while notification ids
 * and fingerprints stay locale-independent.
 */
internal class FakeNotificationLocalizer(
    private val tag: String = "L",
) : NotificationLocalizer {
    val calls = mutableListOf<String>()

    override suspend fun trainTitle(number: String): String {
        calls += "title:$number"
        return "[$tag] Train $number"
    }

    override suspend fun delayChanged(previousMinutes: Int?, currentMinutes: Int): String {
        calls += "delay:$previousMinutes->$currentMinutes"
        return if (previousMinutes == null) "[$tag] delay now $currentMinutes (was unknown)"
        else "[$tag] delay $previousMinutes->$currentMinutes"
    }

    override suspend fun trainCancelled(): String {
        calls += "cancelled"
        return "[$tag] cancelled"
    }

    override suspend fun trainPartiallyCancelled(cancelledStops: List<String>): String {
        calls += "partial:${cancelledStops.sorted().joinToString(",")}"
        return "[$tag] partial[${cancelledStops.joinToString()}]"
    }

    override suspend fun platformChanged(stationName: String?, platform: String): String {
        calls += "platform:$stationName->$platform"
        return "[$tag] platform $platform"
    }

    override suspend fun scheduleChanged(stationName: String): String {
        calls += "schedule:$stationName"
        return "[$tag] schedule $stationName"
    }

    override suspend fun statusChanged(status: TrainStatus): String {
        calls += "status:$status"
        return "[$tag] status $status"
    }

    override suspend fun trainDeparted(): String {
        calls += "departed"
        return "[$tag] departed"
    }

    override suspend fun trainArrived(): String {
        calls += "arrived"
        return "[$tag] arrived"
    }

    override suspend fun trainRouteChanged(): String {
        calls += "route"
        return "[$tag] route"
    }

    override suspend fun strikeTitle(kind: StrikeChangeKind): String {
        calls += "strikeTitle:$kind"
        return "[$tag] strike $kind"
    }

    override suspend fun strikeBody(sector: String, area: String, start: String, end: String): String {
        calls += "strikeBody:$sector|$area|$start|$end"
        return "[$tag] $sector|$area|$start|$end"
    }

    override suspend fun strikeArea(regions: List<String>, relevance: StrikeRelevance): String {
        calls += "strikeArea:${regions.joinToString()}|$relevance"
        return regions.joinToString().ifBlank { "[$tag] $relevance" }
    }

    override suspend fun strikeMoment(instant: Instant): String {
        calls += "strikeMoment:$instant"
        return "[$tag] $instant"
    }
}
