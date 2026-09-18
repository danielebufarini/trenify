package it.danielebufarini.trenify.app

import it.danielebufarini.trenify.core.domain.isRailwayRelevant
import it.danielebufarini.trenify.core.model.Strike
import it.danielebufarini.trenify.core.model.StrikeRelevance
import it.danielebufarini.trenify.core.model.StrikeStatus
import it.danielebufarini.trenify.feature.strikes.AlertsTabComponent
import it.danielebufarini.trenify.feature.strikes.StrikesState
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/**
 * T8.10: semantic Alerts/Strike snapshots over the existing [AlertsTabComponent].
 * No rendering, filtering-policy invention, correlation, revocation, freshness,
 * notification or URL-validation logic is owned here. The shared component owns
 * overview/detail navigation, strike selection identity, refresh, notification
 * actions, reference validation/opening and stale/loading/failure state;
 * platforms only project truth and forward actions.
 *
 * Stable identity is always the shared [Strike.id] value, never display text
 * or position. Platforms must use [NativeStrikeRow.strikeId] for Lazy keys /
 * ForEach IDs and for detail navigation.
 */
data class NativeStrikeRow(
    val strikeId: String,
    val sector: String,
    val status: StrikeStatus,
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
    val railwayRelevant: Boolean,
    val relevance: StrikeRelevance,
    val regions: List<String>,
    val provinces: List<String>,
    val operators: List<String>,
    val mode: String,
    val unions: List<String>,
    val workforce: String?,
    val notes: String?,
    val sourceLabel: String,
    val sourceUrl: String,
    val canOpenReference: Boolean,
)

data class NativeAlertsState(
    val loading: Boolean,
    /** Currently visible/upcoming alerts per the shared overview predicate. */
    val strikes: List<NativeStrikeRow>,
    /** Full shared list projection (visible plus revoked/completed) for detail lookup. */
    val allStrikes: List<NativeStrikeRow>,
    val observation: NativeRealtimeObservation,
    val notificationsEnabled: Boolean,
    val notificationPermissionDenied: Boolean,
    val referenceInProgress: Boolean,
    val referenceFailed: Boolean,
) {
    /** Overview empty state: no visible upcoming alert while not loading. */
    val visibleEmpty: Boolean get() = !loading && strikes.isEmpty()
    /** Retained useful content stays visible while the failure flag shows degradation. */
    val hasRetainedContent: Boolean get() = strikes.isNotEmpty() && observation.failure != null
}

/** Typed actions over one existing Alerts component; all semantics stay shared. */
class NativeAlertsPresentation internal constructor(
    private val component: AlertsTabComponent,
    private val owner: NativeProjectionOwner,
) {
    val state: StateFlow<NativeAlertsState> = owner.project(component.state) { it.nativeAlerts(component) }

    fun refresh() = act(component::refresh)

    fun open(strikeId: String) = act {
        component.state.value.realtime.data.orEmpty()
            .firstOrNull { it.id.value == strikeId }
            ?.let { component.open(it.id) }
    }

    fun back() = act(component::back)

    fun toggleNotifications() = act(component::toggleNotifications)

    fun openReference(url: String) = act { component.openReference(url) }

    fun canOpenReference(url: String?): Boolean = component.canOpenReference(url)

    /** Stable [StrikeId] detail lookup over current shared truth; null is the controlled not-found path. */
    fun detail(strikeId: String): NativeStrikeRow? =
        component.state.value.realtime.data.orEmpty()
            .firstOrNull { it.id.value == strikeId }
            ?.nativeRow(component)

    private fun act(action: () -> Unit) { if (owner.job.isActive) action() }
}

/**
 * Shared overview visibility predicate: an alert is currently
 * visible/upcoming while it ends after the
 * shared reference instant and is neither revoked nor completed. Modification
 * stays an explicit visible state; revocation/completion remove the row from
 * the overview while the detail lookup still resolves them.
 */
fun Strike.isVisibleAlert(at: Instant): Boolean =
    end > at && status != StrikeStatus.REVOKED && status != StrikeStatus.COMPLETED

private fun StrikesState.nativeAlerts(component: AlertsTabComponent): NativeAlertsState {
    val data = realtime.data.orEmpty()
    val visible = data.filter { it.isVisibleAlert(referenceTime) }
    return NativeAlertsState(
        loading = realtime.loading,
        strikes = visible.map { it.nativeRow(component) },
        allStrikes = data.map { it.nativeRow(component) },
        observation = realtime.observation(empty = !realtime.loading && visible.isEmpty()),
        notificationsEnabled = notificationsEnabled,
        notificationPermissionDenied = notificationPermissionGranted == false,
        referenceInProgress = referenceInProgress,
        referenceFailed = referenceFailed,
    )
}

private fun Strike.nativeRow(component: AlertsTabComponent): NativeStrikeRow = NativeStrikeRow(
    strikeId = id.value,
    sector = sector,
    status = status,
    startEpochSeconds = start.epochSeconds,
    endEpochSeconds = end.epochSeconds,
    railwayRelevant = isRailwayRelevant(),
    relevance = geography.relevance,
    regions = geography.regions,
    provinces = geography.provinces,
    operators = operators.map { it.name },
    mode = mode,
    unions = unions,
    workforce = workforce,
    notes = notes,
    sourceLabel = source.label,
    sourceUrl = source.url,
    canOpenReference = component.canOpenReference(source.url),
)
