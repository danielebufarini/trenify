package it.danielebufarini.trenify.app

import it.danielebufarini.trenify.core.domain.DomainFailure
import it.danielebufarini.trenify.core.model.DataFreshness
import it.danielebufarini.trenify.core.model.TrainCategory
import it.danielebufarini.trenify.core.model.TrainStatus
import it.danielebufarini.trenify.core.model.trainSourceName
import it.danielebufarini.trenify.feature.monitoring.MonitorCardState
import it.danielebufarini.trenify.feature.monitoring.MonitoringTabComponent
import kotlinx.coroutines.flow.StateFlow

/**
 * T8.8: semantic Monitoring snapshots over the existing [MonitoringTabComponent].
 * No rendering, polling, persistence, retention or lifecycle logic is owned here.
 * Terminal/retention/stale-response behavior stays in the shared component and
 * repository layers; platforms only project [MonitorCardState] truth.
 *
 * Identity is the existing stable shared identity: monitor id plus the
 * provider-scoped dated [TrainRunId.key]. Never display strings or positions.
 */
data class NativeMonitorCard(
    val monitorId: String,
    val trainRunKey: String,
    val identity: NativeTrainRunIdentity,
    val trainNumber: String,
    val originName: String?,
    val destinationName: String?,
    val operatorName: String?,
    val category: TrainCategory?,
    val status: TrainStatus,
    val delayMinutes: Int?,
    val scheduledDepartureEpochSeconds: Long?,
    val scheduledArrivalEpochSeconds: Long?,
    val scheduledPlatform: String?,
    val actualPlatform: String?,
    val nextStopName: String?,
    val ended: Boolean,
    val endedAtEpochSeconds: Long?,
    val evaluatedAtEpochSeconds: Long?,
    val hasSnapshot: Boolean,
    val observation: NativeRealtimeObservation,
    val refreshFailure: DomainFailure?,
    val notificationsEnabled: Boolean,
    val notificationsPending: Boolean,
    val notificationsFailed: Boolean,
    val warnings: List<NativeServiceStrikeWarningPresentation>,
    val strikesStale: Boolean,
    val strikesUnknown: Boolean,
) {
    /** Active-only stop; ended cards expose remove instead. Derived from shared ended flag only. */
    val canStop: Boolean get() = !ended
    /** Ended-only manual removal. Derived from shared ended flag only. */
    val canRemove: Boolean get() = ended
    /** Notification toggle is active-only; ended cards are read-only. */
    val canToggleNotifications: Boolean get() = !ended
    val canRetryNotifications: Boolean get() = !ended && notificationsFailed
    /** Detail navigation is a plain shared callback; it never restarts monitoring. */
    val canOpen: Boolean get() = true
}

data class NativeMonitoringState(
    val loading: Boolean,
    val active: List<NativeMonitorCard>,
    val ended: List<NativeMonitorCard>,
)

/** Typed actions over one existing Monitoring component; all semantics stay in that component. */
class NativeMonitoringPresentation internal constructor(
    private val component: MonitoringTabComponent,
    private val owner: NativeProjectionOwner,
) {
    val state: StateFlow<NativeMonitoringState> = owner.projectSnapshot(component.state) {
        val current = component.state.value
        NativeMonitoringState(
            loading = current.loading,
            active = current.active.map { it.nativeCard(current.pendingNotifications, current.failedNotificationMonitor?.key) },
            ended = current.ended.map { it.nativeCard(current.pendingNotifications, current.failedNotificationMonitor?.key) },
        )
    }

    fun stop(trainRunKey: String) = act {
        val id = card(trainRunKey) ?: return@act
        if (id.monitor.endedAt != null) return@act
        component.stop(id.monitor.trainRunId)
    }

    fun open(trainRunKey: String) = act {
        val id = card(trainRunKey) ?: return@act
        component.open(id.monitor.trainRunId)
    }

    fun removeEnded(trainRunKey: String) = act {
        val id = card(trainRunKey) ?: return@act
        if (id.monitor.endedAt == null) return@act
        component.removeEnded(id.monitor.trainRunId)
    }

    fun setMonitorNotifications(trainRunKey: String, enabled: Boolean) = act {
        val id = card(trainRunKey) ?: return@act
        if (id.monitor.endedAt != null) return@act
        component.setMonitorNotifications(id.monitor.trainRunId, enabled)
    }

    fun retryMonitorNotifications() = act {
        // The failed id may belong to a monitor that has since terminated or
        // been removed. Retrying must never mutate an ended record: forward
        // only while the failed monitor is still ACTIVE in current state.
        val failed = component.state.value.failedNotificationMonitor ?: return@act
        if (component.state.value.active.none { it.monitor.trainRunId == failed }) return@act
        component.retryMonitorNotifications()
    }

    private fun card(trainRunKey: String): MonitorCardState? {
        val current = component.state.value
        return (current.active + current.ended).firstOrNull { it.monitor.trainRunId.key == trainRunKey }
    }

    private fun act(action: () -> Unit) { if (owner.job.isActive) action() }
}

private fun MonitorCardState.nativeCard(
    pending: Set<String>,
    failedKey: String?,
): NativeMonitorCard {
    val monitor = this.monitor
    val runId = monitor.trainRunId
    val summary = monitor.lastSnapshot?.train?.summary
    val ended = monitor.endedAt != null
    return NativeMonitorCard(
        monitorId = monitor.id.value,
        trainRunKey = runId.key,
        identity = NativeSemanticIdentity.trainRun(runId),
        trainNumber = runId.number.value,
        originName = summary?.origin?.name,
        destinationName = summary?.destinationName,
        operatorName = summary?.operator?.name,
        category = summary?.category,
        status = summary?.status ?: TrainStatus.UNKNOWN,
        delayMinutes = summary?.delayMinutes,
        scheduledDepartureEpochSeconds = summary?.scheduledDeparture?.epochSeconds,
        scheduledArrivalEpochSeconds = summary?.scheduledArrival?.epochSeconds,
        scheduledPlatform = summary?.scheduledPlatform,
        actualPlatform = summary?.actualPlatform,
        nextStopName = nextStopName,
        ended = ended,
        endedAtEpochSeconds = monitor.endedAt?.epochSeconds,
        evaluatedAtEpochSeconds = monitor.lastSnapshot?.evaluatedAt?.epochSeconds,
        hasSnapshot = monitor.lastSnapshot != null,
        observation = monitorObservation(),
        refreshFailure = refreshFailure,
        notificationsEnabled = monitor.notificationsEnabled,
        notificationsPending = runId.key in pending,
        notificationsFailed = failedKey == runId.key,
        warnings = strikeWarnings.map { it.projected() },
        strikesStale = strikesStale,
        strikesUnknown = strikesUnknown,
    )
}

private fun MonitorCardState.monitorObservation(): NativeRealtimeObservation {
    val snapshot = monitor.lastSnapshot
    val freshness = displayFreshness ?: snapshot?.freshness
    val fetched = when (freshness) {
        is DataFreshness.Fresh -> freshness.fetchedAt.epochSeconds
        is DataFreshness.Stale -> freshness.fetchedAt.epochSeconds
        else -> null
    }
    val source = when (freshness) {
        is DataFreshness.Fresh -> freshness.sourceTimestamp?.epochSeconds
        is DataFreshness.Stale -> freshness.sourceTimestamp?.epochSeconds
        else -> null
    }
    val classification = when {
        freshness == null || freshness is DataFreshness.Unknown -> NativeRealtimeFreshness.Unknown
        freshness is DataFreshness.Stale -> NativeRealtimeFreshness.Stale
        else -> NativeRealtimeFreshness.Fresh
    }
    // Genuine provider identity; never inferred from the operator.
    val provider = trainSourceName(monitor.trainRunId.provider)
    return NativeRealtimeObservation(
        loading = false,
        failure = refreshFailure,
        hasContent = snapshot != null,
        empty = false,
        freshness = classification,
        provenance = NativeRealtimeProvenancePresentation(
            providerName = provider,
            fetchedAtEpochSeconds = fetched,
            sourceTimestampEpochSeconds = source,
            stale = freshness is DataFreshness.Stale,
            degraded = refreshFailure != null,
        ),
    )
}
