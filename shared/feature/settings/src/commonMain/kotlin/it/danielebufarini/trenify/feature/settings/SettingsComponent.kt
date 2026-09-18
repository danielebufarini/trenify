package it.danielebufarini.trenify.feature.settings

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import it.danielebufarini.trenify.core.domain.ClearAllFavorites
import it.danielebufarini.trenify.core.domain.ClearSearchHistoryAndRecency
import it.danielebufarini.trenify.core.domain.MonitorEventKind
import it.danielebufarini.trenify.core.domain.MonitorThresholds
import it.danielebufarini.trenify.core.domain.NotificationSettings
import it.danielebufarini.trenify.core.domain.ObserveNotificationSettings
import it.danielebufarini.trenify.core.domain.ObserveStrikeNotifications
import it.danielebufarini.trenify.core.domain.SetDefaultMonitorThresholds
import it.danielebufarini.trenify.core.domain.SetNotificationsEnabled
import it.danielebufarini.trenify.core.domain.SetStrikeNotifications
import it.danielebufarini.trenify.core.platform.EffectiveNotificationPermission
import it.danielebufarini.trenify.core.platform.NotificationPermission
import it.danielebufarini.trenify.core.ui.componentScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ThresholdValidation {
    Valid,
    Invalid,
}

/**
 * Retryable save failure. The component never coerces invalid input into a
 * different policy: a threshold failure only exists for a valid value whose
 * persistence failed, while invalid text stays a validation state.
 */
sealed interface FailedSettingsSave {
    data class Global(val enabled: Boolean) : FailedSettingsSave
    data class Threshold(val minutes: Int) : FailedSettingsSave
    data class EventFlag(val kind: MonitorEventKind, val enabled: Boolean) : FailedSettingsSave
    data class Strike(val enabled: Boolean) : FailedSettingsSave
}

/**
 * Lifecycle of one Settings delete-all action (T7.8).
 *
 * - [Idle]: nothing requested;
 * - [Confirming]: the user was shown what will be removed and must confirm or cancel;
 * - [Pending]: the confirmed persistence operation is running; further
 *   confirmations are ignored so repeated taps run it exactly once;
 * - [Success]: the deletion completed and reactive observers show empty state;
 * - [Failed]: persistence failed without claiming success; confirming again retries.
 */
enum class PersonalDataDeletionStatus {
    Idle,
    Confirming,
    Pending,
    Success,
    Failed,
}

data class SettingsState(
    val loading: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val delayThresholdText: String = DEFAULT_THRESHOLD_TEXT,
    val delayThresholdValidation: ThresholdValidation = ThresholdValidation.Valid,
    val notifyDelay: Boolean = true,
    val notifyPlatform: Boolean = true,
    val notifyCancellation: Boolean = true,
    val notifyDeparture: Boolean = true,
    val notifyArrival: Boolean = true,
    val strikeNotificationsEnabled: Boolean = false,
    val strikePermissionRequestDenied: Boolean = false,
    val permission: EffectiveNotificationPermission? = null,
    val saving: Boolean = false,
    val failedSave: FailedSettingsSave? = null,
    val historyDeletion: PersonalDataDeletionStatus = PersonalDataDeletionStatus.Idle,
    val favoritesDeletion: PersonalDataDeletionStatus = PersonalDataDeletionStatus.Idle,
) {
    companion object {
        const val DEFAULT_THRESHOLD_TEXT = "15"
    }
}

interface SettingsComponent {
    val state: Value<SettingsState>
    fun setNotificationsEnabled(enabled: Boolean)
    fun editDelayThreshold(text: String)
    fun saveDelayThreshold()
    fun setEventFlag(kind: MonitorEventKind, enabled: Boolean)
    fun toggleStrikeNotifications()
    fun refreshPermission()
    fun retry()
    fun requestHistoryDeletion()
    fun cancelHistoryDeletion()
    fun confirmHistoryDeletion()
    fun requestFavoritesDeletion()
    fun cancelFavoritesDeletion()
    fun confirmFavoritesDeletion()
}

/**
 * Decompose-owned local notification and monitoring settings (T7.7).
 *
 * Owns loading, typed threshold validation, mutation-in-progress, retryable
 * mutation errors and the effective OS permission state. The screen stays
 * presentation-only. Strike state is observed from and written to the same
 * `StrikeNotificationRepository` used by Alerts; viewing never enables it.
 */
class DefaultSettingsComponent(
    componentContext: ComponentContext,
    private val observeSettings: ObserveNotificationSettings? = null,
    private val setNotifications: SetNotificationsEnabled? = null,
    private val setDefaultThresholds: SetDefaultMonitorThresholds? = null,
    private val observeStrikeNotifications: ObserveStrikeNotifications? = null,
    private val setStrikeNotifications: SetStrikeNotifications? = null,
    private val notificationPermission: NotificationPermission? = null,
    private val requestNotificationPermission: suspend () -> Boolean = { false },
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val clearSearchHistoryAndRecency: ClearSearchHistoryAndRecency? = null,
    private val clearAllFavorites: ClearAllFavorites? = null,
) : SettingsComponent, ComponentContext by componentContext {
    private val scope = componentScope(lifecycle, dispatcher)
    private val mutable = MutableValue(SettingsState())
    override val state: Value<SettingsState> = mutable
    private var observation: Job? = null
    private var committedThresholdText: String? = null

    /**
     * Latest persisted installation snapshot. Independent mutations (event
     * flags, threshold saves) are always based on this, never on the
     * unsaved threshold text draft, so a draft can never leak into an
     * unrelated write.
     */
    private var committed: NotificationSettings? = null

    init {
        observe()
        observeStrike()
        refreshPermission()
    }

    override fun setNotificationsEnabled(enabled: Boolean) {
        val set = setNotifications ?: return
        mutate(FailedSettingsSave.Global(enabled)) { set(enabled) }
    }

    override fun editDelayThreshold(text: String) {
        mutable.value = mutable.value.copy(
            delayThresholdText = text,
            delayThresholdValidation = validate(text),
        )
    }

    override fun saveDelayThreshold() {
        val set = setDefaultThresholds ?: return
        val minutes = mutable.value.delayThresholdText.toIntOrNull()?.takeIf { it > 0 }
        if (minutes == null) {
            mutable.value = mutable.value.copy(delayThresholdValidation = ThresholdValidation.Invalid)
            return
        }
        // No optimistic commit marker here: committedThresholdText must keep
        // representing observed persisted state, so a failed save stays
        // visibly retryable and later unrelated emissions cannot erase it.
        // The base is resolved at execution time from persisted state only:
        // without an authoritative or committed snapshot the save must fail
        // retryably instead of inventing unknown event flags from UI state.
        mutate(FailedSettingsSave.Threshold(minutes)) {
            val base = resolveThresholds()
            if (base == null) {
                mutable.value = mutable.value.copy(failedSave = FailedSettingsSave.Threshold(minutes))
            } else {
                set(base.copy(delayMinutes = minutes))
            }
        }
    }

    override fun setEventFlag(kind: MonitorEventKind, enabled: Boolean) {
        val set = setDefaultThresholds ?: return
        // Schedule, status and route-change events have no dedicated
        // installation flag (T7.7/T7.11): nothing to persist.
        if (kind == MonitorEventKind.SCHEDULE || kind == MonitorEventKind.STATUS || kind == MonitorEventKind.ROUTE_CHANGED) return
        // The base is resolved at execution time from persisted state only:
        // the unsaved threshold draft must never leak into an unrelated flag
        // write, and back-to-back mutations compose in execution order.
        mutate(FailedSettingsSave.EventFlag(kind, enabled)) {
            val base = resolveThresholds()
            if (base == null) {
                // Nothing authoritative to modify: fail retryably instead of
                // synthesizing persisted policy from the editable draft.
                mutable.value = mutable.value.copy(failedSave = FailedSettingsSave.EventFlag(kind, enabled))
            } else {
                set(
                    when (kind) {
                        MonitorEventKind.DELAY -> base.copy(notifyDelay = enabled)
                        MonitorEventKind.PLATFORM -> base.copy(notifyPlatform = enabled)
                        MonitorEventKind.CANCELLATION,
                        MonitorEventKind.PARTIAL_CANCELLATION,
                        -> base.copy(notifyCancellation = enabled)
                        MonitorEventKind.DEPARTURE -> base.copy(notifyDeparture = enabled)
                        MonitorEventKind.ARRIVAL -> base.copy(notifyArrival = enabled)
                        MonitorEventKind.SCHEDULE, MonitorEventKind.STATUS, MonitorEventKind.ROUTE_CHANGED -> base
                    },
                )
            }
        }
    }

    override fun toggleStrikeNotifications() {
        val set = setStrikeNotifications ?: return
        scope.launch {
            if (mutable.value.strikeNotificationsEnabled) {
                mutate(FailedSettingsSave.Strike(false)) { set(false) }
            } else {
                val granted = try {
                    requestNotificationPermission()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    false
                }
                mutable.value = mutable.value.copy(strikePermissionRequestDenied = !granted)
                if (granted) mutate(FailedSettingsSave.Strike(true)) { set(true) }
            }
        }
    }

    override fun refreshPermission() {
        val permission = notificationPermission ?: return
        scope.launch {
            try {
                val effective = permission.effective()
                mutable.value = mutable.value.copy(permission = effective)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Effective permission stays unknown; saved preferences are unaffected.
            }
        }
    }

    override fun retry() {
        when (val failed = mutable.value.failedSave) {
            is FailedSettingsSave.Global -> setNotificationsEnabled(failed.enabled)
            is FailedSettingsSave.Threshold -> {
                editDelayThreshold(failed.minutes.toString())
                saveDelayThreshold()
            }
            is FailedSettingsSave.EventFlag -> setEventFlag(failed.kind, failed.enabled)
            is FailedSettingsSave.Strike -> {
                val set = setStrikeNotifications ?: return
                mutate(failed) { set(failed.enabled) }
            }
            // A failed delete-all action retries through its own confirmation
            // path; unrelated settings saves keep their existing precedence.
            null -> when {
                mutable.value.historyDeletion == PersonalDataDeletionStatus.Failed -> confirmHistoryDeletion()
                mutable.value.favoritesDeletion == PersonalDataDeletionStatus.Failed -> confirmFavoritesDeletion()
                else -> observe()
            }
        }
    }

    override fun requestHistoryDeletion() {
        if (mutable.value.historyDeletion == PersonalDataDeletionStatus.Pending) return
        mutable.value = mutable.value.copy(historyDeletion = PersonalDataDeletionStatus.Confirming)
    }

    override fun cancelHistoryDeletion() {
        // Cancellation from confirmation is a true no-op: no persistence call runs.
        if (mutable.value.historyDeletion != PersonalDataDeletionStatus.Confirming) return
        mutable.value = mutable.value.copy(historyDeletion = PersonalDataDeletionStatus.Idle)
    }

    override fun confirmHistoryDeletion() {
        val clear = clearSearchHistoryAndRecency ?: return
        when (mutable.value.historyDeletion) {
            // Confirming runs the deletion; Failed confirms the retry.
            PersonalDataDeletionStatus.Confirming, PersonalDataDeletionStatus.Failed -> Unit
            else -> return
        }
        mutable.value = mutable.value.copy(historyDeletion = PersonalDataDeletionStatus.Pending)
        scope.launch {
            try {
                clear()
                mutable.value = mutable.value.copy(historyDeletion = PersonalDataDeletionStatus.Success)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutable.value = mutable.value.copy(historyDeletion = PersonalDataDeletionStatus.Failed)
            }
        }
    }

    override fun requestFavoritesDeletion() {
        if (mutable.value.favoritesDeletion == PersonalDataDeletionStatus.Pending) return
        mutable.value = mutable.value.copy(favoritesDeletion = PersonalDataDeletionStatus.Confirming)
    }

    override fun cancelFavoritesDeletion() {
        // Cancellation from confirmation is a true no-op: no persistence call runs.
        if (mutable.value.favoritesDeletion != PersonalDataDeletionStatus.Confirming) return
        mutable.value = mutable.value.copy(favoritesDeletion = PersonalDataDeletionStatus.Idle)
    }

    override fun confirmFavoritesDeletion() {
        val clear = clearAllFavorites ?: return
        when (mutable.value.favoritesDeletion) {
            // Confirming runs the deletion; Failed confirms the retry.
            PersonalDataDeletionStatus.Confirming, PersonalDataDeletionStatus.Failed -> Unit
            else -> return
        }
        mutable.value = mutable.value.copy(favoritesDeletion = PersonalDataDeletionStatus.Pending)
        scope.launch {
            try {
                clear()
                mutable.value = mutable.value.copy(favoritesDeletion = PersonalDataDeletionStatus.Success)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutable.value = mutable.value.copy(favoritesDeletion = PersonalDataDeletionStatus.Failed)
            }
        }
    }

    private fun observe() {
        val observe = observeSettings ?: run {
            mutable.value = mutable.value.copy(loading = false)
            return
        }
        if (observation?.isActive == true) return
        mutable.value = mutable.value.copy(loading = true)
        observation = scope.launch {
            observe().catch {
                mutable.value = mutable.value.copy(loading = false)
            }.collect { settings ->
                committed = settings
                val persisted = settings.defaultThresholds.delayMinutes?.toString()
                    ?: SettingsState.DEFAULT_THRESHOLD_TEXT
                if (committedThresholdText == null || committedThresholdText != persisted) {
                    committedThresholdText = persisted
                    mutable.value = mutable.value.copy(
                        loading = false,
                        notificationsEnabled = settings.notificationsEnabled,
                        delayThresholdText = persisted,
                        delayThresholdValidation = ThresholdValidation.Valid,
                        notifyDelay = settings.defaultThresholds.notifyDelay,
                        notifyPlatform = settings.defaultThresholds.notifyPlatform,
                        notifyCancellation = settings.defaultThresholds.notifyCancellation,
                        notifyDeparture = settings.defaultThresholds.notifyDeparture,
                        notifyArrival = settings.defaultThresholds.notifyArrival,
                    )
                } else {
                    mutable.value = mutable.value.copy(
                        loading = false,
                        notificationsEnabled = settings.notificationsEnabled,
                        notifyDelay = settings.defaultThresholds.notifyDelay,
                        notifyPlatform = settings.defaultThresholds.notifyPlatform,
                        notifyCancellation = settings.defaultThresholds.notifyCancellation,
                        notifyDeparture = settings.defaultThresholds.notifyDeparture,
                        notifyArrival = settings.defaultThresholds.notifyArrival,
                    )
                }
            }
        }
    }

    /**
     * Persisted thresholds at write-execution time, or null when neither an
     * authoritative read nor a previously committed snapshot is available.
     * The editable text draft is never consulted: without persisted state an
     * unrelated mutation must fail retryably instead of guessing policy.
     */
    private suspend fun resolveThresholds(): MonitorThresholds? {
        observeSettings?.let { observe ->
            try {
                return observe().first().defaultThresholds
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Fall through to the last committed snapshot below.
            }
        }
        return committed?.defaultThresholds
    }

    /**
     * Serialized mutation gate. A bare coroutine scope launches each mutation
     * independently, which would let back-to-back read-modify-write mutations
     * resolve from the same stale snapshot and lose one another; the mutex
     * makes each mutation observe the persisted state left by its
     * predecessors.
     */
    private val writeMutex = Mutex()

    private fun mutate(failed: FailedSettingsSave, block: suspend () -> Unit) {
        mutable.value = mutable.value.copy(saving = true, failedSave = null)
        scope.launch {
            writeMutex.withLock {
                try {
                    block()
                    mutable.value = mutable.value.copy(saving = false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    mutable.value = mutable.value.copy(saving = false, failedSave = failed)
                }
            }
        }
    }

    /**
     * Observes the single shared strike opt-in used by Alerts. Viewing
     * Settings never writes it.
     */
    private fun observeStrike() {
        val observe = observeStrikeNotifications ?: return
        scope.launch {
            try {
                observe().collect { enabled ->
                    mutable.value = mutable.value.copy(strikeNotificationsEnabled = enabled)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Strike switch keeps its last value; installation settings are unaffected.
            }
        }
    }

    private companion object {
        fun validate(text: String): ThresholdValidation =
            if (text.toIntOrNull()?.takeIf { it > 0 } != null) ThresholdValidation.Valid
            else ThresholdValidation.Invalid
    }
}
