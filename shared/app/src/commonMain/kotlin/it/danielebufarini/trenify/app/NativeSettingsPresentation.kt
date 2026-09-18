package it.danielebufarini.trenify.app

import it.danielebufarini.trenify.core.domain.MonitorEventKind
import it.danielebufarini.trenify.feature.settings.SettingsComponent
import it.danielebufarini.trenify.feature.settings.SettingsState
import kotlinx.coroutines.flow.StateFlow

/**
 * T8.11: typed Settings projection over the existing [SettingsComponent].
 * No rendering, persistence, validation, permission-policy, delivery or
 * deletion logic is owned here. Loading, the saved notification preference,
 * the effective OS permission, the editable threshold draft, validation,
 * saving/retry, the five editable monitor event flags, the shared strike
 * opt-in and both personal-data deletion state machines stay in the shared
 * component; platforms only project truth and forward actions.
 *
 * Identity is the surrounding shell entry identity; the component instance
 * stays Decompose-owned and the projection closes with its destination.
 */
class NativeSettingsPresentation internal constructor(
    private val component: SettingsComponent,
    private val owner: NativeProjectionOwner,
) {
    val state: StateFlow<SettingsState> = owner.project(component.state) { it }

    fun setNotificationsEnabled(enabled: Boolean) = act { component.setNotificationsEnabled(enabled) }

    fun editDelayThreshold(text: String) = act { component.editDelayThreshold(text) }

    fun saveDelayThreshold() = act(component::saveDelayThreshold)

    fun setEventFlag(kind: MonitorEventKind, enabled: Boolean) = act { component.setEventFlag(kind, enabled) }

    fun toggleStrikeNotifications() = act(component::toggleStrikeNotifications)

    fun refreshPermission() = act(component::refreshPermission)

    fun retry() = act(component::retry)

    fun requestHistoryDeletion() = act(component::requestHistoryDeletion)

    fun cancelHistoryDeletion() = act(component::cancelHistoryDeletion)

    fun confirmHistoryDeletion() = act(component::confirmHistoryDeletion)

    fun requestFavoritesDeletion() = act(component::requestFavoritesDeletion)

    fun cancelFavoritesDeletion() = act(component::cancelFavoritesDeletion)

    fun confirmFavoritesDeletion() = act(component::confirmFavoritesDeletion)

    private fun act(action: () -> Unit) { if (owner.job.isActive) action() }
}
