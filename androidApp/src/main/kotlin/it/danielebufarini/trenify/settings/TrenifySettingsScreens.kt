package it.danielebufarini.trenify.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.danielebufarini.trenify.R
import it.danielebufarini.trenify.app.NativeSettingsPresentation
import it.danielebufarini.trenify.app.NativeShellEntry
import it.danielebufarini.trenify.core.domain.MonitorEventKind
import it.danielebufarini.trenify.core.platform.EffectiveNotificationPermission
import it.danielebufarini.trenify.design.component.TrenifySectionHeader
import it.danielebufarini.trenify.design.component.dpadExitSingleLine
import it.danielebufarini.trenify.design.theme.TrenifySpacing
import it.danielebufarini.trenify.design.theme.TrenifyTheme
import it.danielebufarini.trenify.feature.settings.PersonalDataDeletionStatus
import it.danielebufarini.trenify.feature.settings.SettingsState
import it.danielebufarini.trenify.feature.settings.ThresholdValidation

/** Android owns rendering only. The facade is cached by the shared shell's live component identity. */
@Composable
fun NativeSettingsEntry(entry: NativeShellEntry) {
    val facade = entry.settings ?: return
    val state by facade.state.collectAsStateWithLifecycle()
    SettingsContent(
        state = state,
        onToggleNotifications = facade::setNotificationsEnabled,
        onEditThreshold = facade::editDelayThreshold,
        onSaveThreshold = facade::saveDelayThreshold,
        onSetEventFlag = facade::setEventFlag,
        onToggleStrike = facade::toggleStrikeNotifications,
        onRetry = facade::retry,
        onRequestHistory = facade::requestHistoryDeletion,
        onCancelHistory = facade::cancelHistoryDeletion,
        onConfirmHistory = facade::confirmHistoryDeletion,
        onRequestFavorites = facade::requestFavoritesDeletion,
        onCancelFavorites = facade::cancelFavoritesDeletion,
        onConfirmFavorites = facade::confirmFavoritesDeletion,
    )
}

@Composable
fun SettingsContent(
    state: SettingsState,
    onToggleNotifications: (Boolean) -> Unit,
    onEditThreshold: (String) -> Unit,
    onSaveThreshold: () -> Unit,
    onSetEventFlag: (MonitorEventKind, Boolean) -> Unit,
    onToggleStrike: () -> Unit,
    onRetry: () -> Unit,
    onRequestHistory: () -> Unit,
    onCancelHistory: () -> Unit,
    onConfirmHistory: () -> Unit,
    onRequestFavorites: () -> Unit,
    onCancelFavorites: () -> Unit,
    onConfirmFavorites: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = TrenifySpacing.screenHorizontal).testTag("native-settings"),
        verticalArrangement = Arrangement.spacedBy(TrenifySpacing.m),
    ) {
        item {
            Text(stringResource(R.string.set_best_effort), Modifier.testTag("settings-best-effort"),
                style = TrenifyTheme.typography.caption, color = TrenifyTheme.colors.textSecondary)
        }
        if (state.loading) {
            item {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("settings-loading"))
                Text(stringResource(R.string.st_loading), style = TrenifyTheme.typography.caption,
                    color = TrenifyTheme.colors.textSecondary)
            }
            item { Spacer(Modifier.height(TrenifySpacing.l)) }
            return@LazyColumn
        }
        item {
            TrenifySectionHeader(title = stringResource(R.string.set_notifications_section))
        }
        item {
            SettingSwitch(
                label = stringResource(R.string.set_notifications),
                description = stringResource(R.string.set_notifications_description),
                checked = state.notificationsEnabled,
                enabled = !state.saving,
                testTag = "settings-notifications",
                onCheckedChange = onToggleNotifications,
            )
        }
        if (state.permission == EffectiveNotificationPermission.DENIED) {
            item {
                // A denied OS permission never implies monitoring is disabled:
                // the saved preference above stays authoritative for delivery choice.
                Text(stringResource(R.string.set_permission_denied),
                    Modifier.testTag("settings-permission-hint"),
                    style = TrenifyTheme.typography.bodyEmphasized,
                    color = TrenifyTheme.colors.textPrimary)
            }
        }
        item {
            TrenifySectionHeader(title = stringResource(R.string.set_defaults_section))
        }
        item {
            val invalid = state.delayThresholdValidation == ThresholdValidation.Invalid
            OutlinedTextField(
                value = state.delayThresholdText,
                onValueChange = onEditThreshold,
                modifier = Modifier.fillMaxWidth().dpadExitSingleLine().testTag("settings-threshold"),
                label = { Text(stringResource(R.string.set_delay_threshold)) },
                supportingText = {
                    // Merged into the field's semantics by design, so
                    // TalkBack announces label, value and hint/error together.
                    Text(
                        stringResource(if (invalid) R.string.set_invalid_threshold else R.string.set_threshold_hint),
                    )
                },
                isError = invalid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        item {
            if (state.saving) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("settings-saving"))
                Text(stringResource(R.string.set_saving), style = TrenifyTheme.typography.caption,
                    color = TrenifyTheme.colors.textSecondary)
            } else {
                Button(
                    onClick = onSaveThreshold,
                    enabled = state.delayThresholdValidation == ThresholdValidation.Valid,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("settings-threshold-save"),
                ) {
                    Text(stringResource(R.string.set_save))
                }
            }
        }
        if (state.failedSave != null) {
            item {
                Text(stringResource(R.string.set_save_error),
                    Modifier.testTag("settings-error"),
                    style = TrenifyTheme.typography.bodyEmphasized,
                    color = MaterialTheme.colorScheme.error)
            }
            item {
                TextButton(onClick = onRetry, Modifier.heightIn(min = 48.dp).testTag("settings-retry")) {
                    Text(stringResource(R.string.st_retry))
                }
            }
        }
        item {
            TrenifySectionHeader(
                title = stringResource(R.string.set_events_section),
                subtitle = stringResource(R.string.set_events_description),
            )
        }
        item {
            SettingSwitch(
                label = stringResource(R.string.set_event_delay),
                description = null,
                checked = state.notifyDelay,
                enabled = !state.saving,
                testTag = "settings-flag-delay",
                onCheckedChange = { onSetEventFlag(MonitorEventKind.DELAY, it) },
            )
        }
        item {
            SettingSwitch(
                label = stringResource(R.string.set_event_platform),
                description = null,
                checked = state.notifyPlatform,
                enabled = !state.saving,
                testTag = "settings-flag-platform",
                onCheckedChange = { onSetEventFlag(MonitorEventKind.PLATFORM, it) },
            )
        }
        item {
            SettingSwitch(
                label = stringResource(R.string.set_event_cancellation),
                description = null,
                checked = state.notifyCancellation,
                enabled = !state.saving,
                testTag = "settings-flag-cancellation",
                onCheckedChange = { onSetEventFlag(MonitorEventKind.CANCELLATION, it) },
            )
        }
        item {
            SettingSwitch(
                label = stringResource(R.string.set_event_departure),
                description = null,
                checked = state.notifyDeparture,
                enabled = !state.saving,
                testTag = "settings-flag-departure",
                onCheckedChange = { onSetEventFlag(MonitorEventKind.DEPARTURE, it) },
            )
        }
        item {
            SettingSwitch(
                label = stringResource(R.string.set_event_arrival),
                description = null,
                checked = state.notifyArrival,
                enabled = !state.saving,
                testTag = "settings-flag-arrival",
                onCheckedChange = { onSetEventFlag(MonitorEventKind.ARRIVAL, it) },
            )
        }
        item {
            TrenifySectionHeader(
                title = stringResource(R.string.set_strike_section),
                subtitle = stringResource(R.string.set_strike_description),
            )
        }
        item {
            SettingSwitch(
                label = stringResource(R.string.set_strike),
                description = null,
                checked = state.strikeNotificationsEnabled,
                enabled = !state.saving,
                testTag = "settings-strike",
                onCheckedChange = { onToggleStrike() },
            )
        }
        if (state.strikePermissionRequestDenied) {
            item {
                Text(stringResource(R.string.set_strike_permission_denied),
                    Modifier.testTag("settings-strike-denied"),
                    style = TrenifyTheme.typography.bodyEmphasized,
                    color = TrenifyTheme.colors.textPrimary)
            }
        }
        item {
            TrenifySectionHeader(title = stringResource(R.string.set_data_section))
        }
        item {
            DeletionSection(
                title = stringResource(R.string.set_delete_history_title),
                description = stringResource(R.string.set_delete_history_description),
                confirmText = stringResource(R.string.set_delete_history_confirm),
                status = state.historyDeletion,
                tagPrefix = "settings-delete-history",
                onRequest = onRequestHistory,
                onCancel = onCancelHistory,
                onConfirm = onConfirmHistory,
            )
        }
        item {
            DeletionSection(
                title = stringResource(R.string.set_delete_favorites_title),
                description = stringResource(R.string.set_delete_favorites_description),
                confirmText = stringResource(R.string.set_delete_favorites_confirm),
                status = state.favoritesDeletion,
                tagPrefix = "settings-delete-favorites",
                onRequest = onRequestFavorites,
                onCancel = onCancelFavorites,
                onConfirm = onConfirmFavorites,
            )
        }
        item { Spacer(Modifier.height(TrenifySpacing.l)) }
    }
}

/**
 * One labelled switch row. The row owns the toggle action so TalkBack
 * announces label, description, role and state together; the Material
 * switch stays the visual state indicator.
 */
@Composable
private fun SettingSwitch(
    label: String,
    description: String?,
    checked: Boolean,
    enabled: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    val stateLabel = stringResource(if (checked) R.string.set_switch_on else R.string.set_switch_off)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .testTag(testTag)
            .semantics(mergeDescendants = true) { stateDescription = stateLabel },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = TrenifySpacing.m)) {
            Text(label, style = TrenifyTheme.typography.bodyEmphasized,
                color = TrenifyTheme.colors.textPrimary)
            description?.let {
                Text(it, style = TrenifyTheme.typography.caption,
                    color = TrenifyTheme.colors.textSecondary)
            }
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

@Composable
private fun DeletionSection(
    title: String,
    description: String,
    confirmText: String,
    status: PersonalDataDeletionStatus,
    tagPrefix: String,
    onRequest: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.medium, color = TrenifyTheme.colors.surfaceRaised) {
        Column(Modifier.fillMaxWidth().padding(TrenifySpacing.m),
            verticalArrangement = Arrangement.spacedBy(TrenifySpacing.s)) {
            Text(title, style = TrenifyTheme.typography.bodyEmphasized,
                color = TrenifyTheme.colors.textPrimary)
            Text(description, style = TrenifyTheme.typography.caption,
                color = TrenifyTheme.colors.textSecondary)
            when (status) {
                PersonalDataDeletionStatus.Idle, PersonalDataDeletionStatus.Success -> {
                    if (status == PersonalDataDeletionStatus.Success) {
                        Text(stringResource(R.string.set_delete_success),
                            Modifier.testTag("$tagPrefix-success"),
                            style = TrenifyTheme.typography.bodyEmphasized,
                            color = TrenifyTheme.colors.textPrimary)
                    }
                    OutlinedButton(onClick = onRequest,
                        Modifier.heightIn(min = 48.dp).testTag(tagPrefix)) {
                        Text(stringResource(R.string.set_delete))
                    }
                }
                PersonalDataDeletionStatus.Confirming -> {
                    // Native rendering of the shared Confirming state: explicit
                    // confirmation drives the shared action, cancel is a no-op.
                    Text(confirmText, Modifier.testTag("$tagPrefix-prompt"),
                        style = TrenifyTheme.typography.bodyEmphasized,
                        color = TrenifyTheme.colors.textPrimary)
                    Row(horizontalArrangement = Arrangement.spacedBy(TrenifySpacing.s)) {
                        Button(
                            onClick = onConfirm,
                            Modifier.heightIn(min = 48.dp).testTag("$tagPrefix-confirm"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) {
                            Text(stringResource(R.string.set_delete_confirm))
                        }
                        TextButton(onClick = onCancel,
                            Modifier.heightIn(min = 48.dp).testTag("$tagPrefix-cancel")) {
                            Text(stringResource(R.string.set_delete_cancel))
                        }
                    }
                }
                PersonalDataDeletionStatus.Pending -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("$tagPrefix-pending"))
                }
                PersonalDataDeletionStatus.Failed -> {
                    Text(stringResource(R.string.set_delete_error),
                        Modifier.testTag("$tagPrefix-error"),
                        style = TrenifyTheme.typography.bodyEmphasized,
                        color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onConfirm,
                        Modifier.heightIn(min = 48.dp).testTag("$tagPrefix-retry")) {
                        Text(stringResource(R.string.st_retry))
                    }
                }
            }
        }
    }
}
