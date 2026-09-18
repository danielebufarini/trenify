package it.danielebufarini.trenify.app

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

import it.danielebufarini.trenify.core.domain.NotificationSettingsRepository
import it.danielebufarini.trenify.core.domain.StrikeChangeEvent
import it.danielebufarini.trenify.core.domain.StrikeNotificationRepository
import it.danielebufarini.trenify.core.domain.StrikePolicy
import it.danielebufarini.trenify.core.domain.StrikeRepository
import it.danielebufarini.trenify.core.platform.ApplicationState
import it.danielebufarini.trenify.core.platform.BackgroundTask
import it.danielebufarini.trenify.core.platform.ConnectivityStatus
import it.danielebufarini.trenify.core.platform.EffectiveNotificationPermission
import it.danielebufarini.trenify.core.platform.NotificationDestination
import it.danielebufarini.trenify.core.platform.NotificationMessage
import it.danielebufarini.trenify.core.platform.PlatformServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalObjCRefinement::class)
@HiddenFromObjC
class StrikeCoordinator(
    private val strikeRepository: StrikeRepository,
    private val notificationRepository: StrikeNotificationRepository,
    private val platformServices: PlatformServices,
    private val notificationSettings: NotificationSettingsRepository,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val policy: StrikePolicy = StrikePolicy(),
    private val localizer: NotificationLocalizer = ResourceNotificationLocalizer(),
) {
    init {
        platformServices.backgroundScheduler.register(BACKGROUND_TASK_ID, ::performBackgroundRefresh)
        scope.launch {
            combine(
                notificationRepository.observeNotificationsEnabled(),
                platformServices.applicationState.state(),
                platformServices.connectivity.status(),
            ) { enabled, applicationState, connectivity -> Triple(enabled, applicationState, connectivity) }
                .collectLatest { (enabled, applicationState, connectivity) ->
                    when {
                        !enabled -> platformServices.backgroundScheduler.cancel(BACKGROUND_TASK_ID)
                        applicationState == ApplicationState.Foreground && connectivity != ConnectivityStatus.Unavailable -> {
                            platformServices.backgroundScheduler.cancel(BACKGROUND_TASK_ID)
                            while (true) {
                                refreshOnce()
                                delay(policy.foregroundRefreshInterval)
                            }
                        }
                        applicationState == ApplicationState.Background -> scheduleBackgroundRefresh()
                    }
                }
        }
    }

    suspend fun refreshOnce() {
        val window = policy.currentWindow(clock)
        strikeRepository.refresh(window, force = true)
        dispatchPending(window.now)
    }

    suspend fun performBackgroundRefresh() {
        refreshOnce()
        if (notificationRepository.observeNotificationsEnabled().first()) scheduleBackgroundRefresh()
    }

    /**
     * Strike delivery precedence (T7.7): a strike notification is delivered
     * only when the installation-wide switch, the strike opt-in and the
     * effective OS permission all allow it. All three are resolved fresh at
     * dispatch time. The installation switch gates delivery only; strike
     * refresh and the strike list are unaffected.
     */
    private suspend fun dispatchPending(emittedAt: Instant) {
        if (!notificationSettings.observe().first().notificationsEnabled) return
        if (!notificationRepository.observeNotificationsEnabled().first()) return
        if (platformServices.notificationPermission.effective() != EffectiveNotificationPermission.GRANTED) return
        notificationRepository.pendingNotifications().forEach { event ->
            if (notificationRepository.claimNotification(event, emittedAt)) {
                platformServices.notifications.show(event.notification(localizer))
            }
        }
    }

    private suspend fun scheduleBackgroundRefresh() {
        platformServices.backgroundScheduler.schedule(
            BackgroundTask(
                BACKGROUND_TASK_ID,
                (clock.now() + policy.backgroundRefreshInterval).toEpochMilliseconds(),
            ),
        )
    }

    companion object {
        const val BACKGROUND_TASK_ID = "it.danielebufarini.trenify.strikes.refresh"
    }
}

private suspend fun StrikeChangeEvent.notification(localizer: NotificationLocalizer): NotificationMessage {
    val title = localizer.strikeTitle(kind)
    val area = localizer.strikeArea(strike.geography.regions, strike.geography.relevance)
    val body = localizer.strikeBody(
        strike.sector,
        area,
        localizer.strikeMoment(strike.start),
        localizer.strikeMoment(strike.end),
    )
    return NotificationMessage(
        // The fingerprint id is intentionally unchanged by routing metadata
        // and by localized wording: identity is the semantic event only.
        id = fingerprint,
        title = title,
        body = body,
        destination = NotificationDestination.Strike(strike.id.value),
    )
}
