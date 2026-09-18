import SwiftUI
@preconcurrency import SharedApp

struct NativeSettingsHost: View {
    @StateObject private var model: NativeSettingsModel
    init(session: NativeApplicationSession, identity: Int64) {
        _model = StateObject(wrappedValue: NativeSettingsModel(session: session, identity: identity))
    }
    var body: some View { NativeSettingsView(model: model).onAppear { model.start() } }
}

func settingsString(_ key: String) -> String {
    NSLocalizedString("se." + key, tableName: "Settings", bundle: Bundle(for: NativeSettingsModel.self), comment: "")
}

@MainActor
struct NativeSettingsView: View {
    @ObservedObject var model: NativeSettingsModel

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: TrenifySpacing.m) {
                if let state = model.settings { settings(state) }
                else { ProgressView(settingsString("loading")) }
            }
            .padding(.horizontal, TrenifySpacing.screenHorizontal)
            .padding(.bottom, TrenifySpacing.l)
        }
        .background(TrenifyColors.background)
        .accessibilityIdentifier("native-settings")
    }

    @ViewBuilder
    private func settings(_ state: SettingsSettingsState) -> some View {
        Text(settingsString("bestEffort")).font(TrenifyTypography.caption)
            .accessibilityIdentifier("settings-best-effort")
        if state.loading {
            ProgressView(settingsString("loading")).accessibilityIdentifier("settings-loading")
        } else {
            notificationsSection(state)
            defaultsSection(state)
            eventsSection(state)
            strikeSection(state)
            dataSection(state)
        }
    }

    private func notificationsSection(_ state: SettingsSettingsState) -> some View {
        Section {
            Toggle(settingsString("notifications"), isOn: Binding(
                get: { state.notificationsEnabled },
                set: { model.setNotifications($0) }
            ))
            .disabled(state.saving)
            .accessibilityIdentifier("settings-notifications")
            Text(settingsString("notificationsDescription")).font(TrenifyTypography.caption)
            if state.permission == .denied {
                // A denied OS permission never implies monitoring is disabled:
                // the saved preference above stays authoritative for delivery choice.
                Text(settingsString("permissionDenied")).font(TrenifyTypography.bodyEmphasized)
                    .accessibilityIdentifier("settings-permission-hint")
            }
        } header: {
            Text(settingsString("notificationsSection")).font(TrenifyTypography.sectionTitle)
                .accessibilityAddTraits(.isHeader)
        }
    }

    private func defaultsSection(_ state: SettingsSettingsState) -> some View {
        Section {
            TextField(settingsString("delayThreshold"), text: Binding(
                get: { state.delayThresholdText },
                set: { model.editThreshold($0) }
            ))
            .keyboardType(.numberPad)
            .textFieldStyle(.roundedBorder)
            .accessibilityIdentifier("settings-threshold")
            if state.delayThresholdValidation == .invalid {
                Text(settingsString("invalidThreshold")).foregroundStyle(TrenifyColors.statusCancelled)
                    .accessibilityIdentifier("settings-threshold-error")
            } else {
                Text(settingsString("thresholdHint")).font(TrenifyTypography.caption)
                    .accessibilityIdentifier("settings-threshold-hint")
            }
            if state.saving {
                ProgressView(settingsString("saving")).accessibilityIdentifier("settings-saving")
            } else {
                Button(settingsString("save")) { model.saveThreshold() }
                    .disabled(state.delayThresholdValidation == .invalid)
                    .accessibilityIdentifier("settings-threshold-save")
            }
            if state.failedSave != nil {
                Text(settingsString("saveError")).foregroundStyle(TrenifyColors.statusCancelled)
                    .accessibilityIdentifier("settings-error")
                Button(settingsString("retry")) { model.retry() }
                    .accessibilityIdentifier("settings-retry")
            }
        } header: {
            Text(settingsString("defaultsSection")).font(TrenifyTypography.sectionTitle)
                .accessibilityAddTraits(.isHeader)
        }
    }

    private func eventsSection(_ state: SettingsSettingsState) -> some View {
        Section {
            Text(settingsString("eventsDescription")).font(TrenifyTypography.caption)
            eventToggle(label: settingsString("eventDelay"), id: "settings-flag-delay",
                checked: state.notifyDelay, saving: state.saving) { model.setEventFlag(.delay, enabled: $0) }
            eventToggle(label: settingsString("eventPlatform"), id: "settings-flag-platform",
                checked: state.notifyPlatform, saving: state.saving) { model.setEventFlag(.platform, enabled: $0) }
            eventToggle(label: settingsString("eventCancellation"), id: "settings-flag-cancellation",
                checked: state.notifyCancellation, saving: state.saving) { model.setEventFlag(.cancellation, enabled: $0) }
            eventToggle(label: settingsString("eventDeparture"), id: "settings-flag-departure",
                checked: state.notifyDeparture, saving: state.saving) { model.setEventFlag(.departure, enabled: $0) }
            eventToggle(label: settingsString("eventArrival"), id: "settings-flag-arrival",
                checked: state.notifyArrival, saving: state.saving) { model.setEventFlag(.arrival, enabled: $0) }
        } header: {
            Text(settingsString("eventsSection")).font(TrenifyTypography.sectionTitle)
                .accessibilityAddTraits(.isHeader)
        }
    }

    private func eventToggle(label: String, id: String, checked: Bool, saving: Bool, set: @escaping (Bool) -> Void) -> some View {
        Toggle(label, isOn: Binding(get: { checked }, set: { set($0) }))
            .disabled(saving)
            .accessibilityIdentifier(id)
    }

    private func strikeSection(_ state: SettingsSettingsState) -> some View {
        Section {
            Text(settingsString("strikeDescription")).font(TrenifyTypography.caption)
            Toggle(settingsString("strike"), isOn: Binding(
                get: { state.strikeNotificationsEnabled },
                set: { _ in model.toggleStrikeNotifications() }
            ))
            .disabled(state.saving)
            .accessibilityIdentifier("settings-strike")
            if state.strikePermissionRequestDenied {
                Text(settingsString("strikePermissionDenied")).font(TrenifyTypography.bodyEmphasized)
                    .accessibilityIdentifier("settings-strike-denied")
            }
        } header: {
            Text(settingsString("strikeSection")).font(TrenifyTypography.sectionTitle)
                .accessibilityAddTraits(.isHeader)
        }
    }

    private func dataSection(_ state: SettingsSettingsState) -> some View {
        Section {
            deletionBlock(
                title: settingsString("deleteHistoryTitle"),
                description: settingsString("deleteHistoryDescription"),
                confirmMessage: settingsString("deleteHistoryConfirm"),
                status: state.historyDeletion,
                isConfirming: state.historyDeletion == .confirming,
                tagPrefix: "settings-delete-history",
                request: model.requestHistoryDeletion,
                cancel: model.cancelHistoryDeletion,
                confirm: model.confirmHistoryDeletion
            )
            deletionBlock(
                title: settingsString("deleteFavoritesTitle"),
                description: settingsString("deleteFavoritesDescription"),
                confirmMessage: settingsString("deleteFavoritesConfirm"),
                status: state.favoritesDeletion,
                isConfirming: state.favoritesDeletion == .confirming,
                tagPrefix: "settings-delete-favorites",
                request: model.requestFavoritesDeletion,
                cancel: model.cancelFavoritesDeletion,
                confirm: model.confirmFavoritesDeletion
            )
        } header: {
            Text(settingsString("dataSection")).font(TrenifyTypography.sectionTitle)
                .accessibilityAddTraits(.isHeader)
        }
    }

    @ViewBuilder
    private func deletionBlock(title: String, description: String, confirmMessage: String,
                               status: SettingsPersonalDataDeletionStatus, isConfirming: Bool,
                               tagPrefix: String, request: @escaping () -> Void,
                               cancel: @escaping () -> Void, confirm: @escaping () -> Void) -> some View {
        VStack(alignment: .leading, spacing: TrenifySpacing.s) {
            Text(title).font(TrenifyTypography.bodyEmphasized)
            Text(description).font(TrenifyTypography.caption)
            switch status {
            case .idle, .success:
                if status == .success {
                    Text(settingsString("deleteSuccess")).accessibilityIdentifier("\(tagPrefix)-success")
                }
                Button(role: .destructive, action: request) {
                    Text(settingsString("delete"))
                }
                .accessibilityIdentifier(tagPrefix)
            case .confirming, .pending, .failed:
                if status == .pending {
                    ProgressView().accessibilityIdentifier("\(tagPrefix)-pending")
                }
                if status == .failed {
                    Text(settingsString("deleteError")).foregroundStyle(TrenifyColors.statusCancelled)
                        .accessibilityIdentifier("\(tagPrefix)-error")
                    Button(settingsString("retry"), action: confirm)
                        .accessibilityIdentifier("\(tagPrefix)-retry")
                }
            @unknown default:
                EmptyView()
            }
        }
        // Native rendering of the shared Confirming state: the dialog only
        // appears while shared truth is Confirming, and every dismissal
        // drives the shared cancel (a no-op unless Confirming).
        .confirmationDialog(title, isPresented: Binding(
            get: { isConfirming },
            set: { if !$0 { cancel() } }
        )) {
            Button(settingsString("deleteConfirm"), role: .destructive, action: confirm)
                .accessibilityIdentifier("\(tagPrefix)-confirm")
            Button(settingsString("deleteCancel"), role: .cancel, action: cancel)
                .accessibilityIdentifier("\(tagPrefix)-cancel")
        } message: {
            Text(confirmMessage).accessibilityIdentifier("\(tagPrefix)-prompt")
        }
    }
}
