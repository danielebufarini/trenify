import SwiftUI
@preconcurrency import SharedApp

struct NativeAlertsHost: View {
    @StateObject private var model: NativeAlertsModel
    init(session: NativeApplicationSession, identity: Int64) {
        _model = StateObject(wrappedValue: NativeAlertsModel(session: session, identity: identity))
    }
    var body: some View { NativeAlertsView(model: model).onAppear { model.start() } }
}

func alertsString(_ key: String) -> String {
    NSLocalizedString("al." + key, tableName: "Alerts", bundle: Bundle(for: NativeAlertsModel.self), comment: "")
}

@MainActor
struct NativeAlertsView: View {
    @ObservedObject var model: NativeAlertsModel

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: TrenifySpacing.m) {
                if let state = model.alerts {
                    if model.strikeId == nil { overview(state) }
                    else { detail(state) }
                } else { ProgressView(alertsString("loading")) }
            }
            .padding(.horizontal, TrenifySpacing.screenHorizontal)
            .padding(.bottom, TrenifySpacing.l)
        }
        .background(TrenifyColors.background)
        .accessibilityIdentifier(model.strikeId == nil ? "native-alerts" : "native-strike-detail")
    }

    @ViewBuilder
    private func overview(_ state: NativeAlertsState) -> some View {
        Toggle(alertsString("notifications"), isOn: Binding(
            get: { state.notificationsEnabled },
            set: { _ in model.toggleNotifications() }))
            .frame(minHeight: 44)
            .accessibilityIdentifier("alerts-notifications")
        if state.notificationPermissionDenied {
            Text(alertsString("permissionMissing"))
                .foregroundStyle(TrenifyColors.statusWarning)
                .font(TrenifyTypography.caption)
                .accessibilityIdentifier("alerts-permission-missing")
        }
        Button(alertsString("refresh")) { model.refresh() }
            .buttonStyle(.bordered).frame(minHeight: 44)
            .accessibilityIdentifier("alerts-refresh")
        freshness(state)
        if state.visibleEmpty {
            Text(alertsString("empty")).accessibilityIdentifier("alerts-empty")
        } else {
            // Stable shared StrikeId identity, never display text or position.
            ForEach(state.strikes, id: \.strikeId) { row in
                strikeCard(row)
            }
        }
    }

    @ViewBuilder
    private func detail(_ state: NativeAlertsState) -> some View {
        freshness(state)
        if let row = model.detail {
            strikeSummary(row, tagPrefix: "strike-detail")
            strikeScope(row, tagPrefix: "strike-detail", compact: false)
            strikeReference(row, state: state, tagPrefix: "strike-detail")
        } else {
            Text(alertsString("notFound")).accessibilityIdentifier("strike-detail-not-found")
        }
    }

    private func strikeCard(_ row: NativeStrikeRow) -> some View {
        VStack(alignment: .leading, spacing: TrenifySpacing.s) {
            strikeSummary(row, tagPrefix: "strike")
            strikeScope(row, tagPrefix: "strike", compact: true)
            if !row.canOpenReference {
                Text(alertsString("referenceUnavailable"))
                    .font(TrenifyTypography.caption)
                    .accessibilityIdentifier("strike-reference-unavailable-\(row.strikeId)")
            }
        }
        .padding(TrenifySpacing.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .foregroundStyle(TrenifyColors.textPrimary)
        .background(TrenifyColors.surfaceRaised, in: RoundedRectangle(cornerRadius: TrenifyShapes.card))
        .accessibilityIdentifier("strike-\(row.strikeId)")
        .onTapGesture { model.open(row.strikeId) }
    }

    @ViewBuilder
    private func strikeSummary(_ row: NativeStrikeRow, tagPrefix: String) -> some View {
        Text(row.sector).font(TrenifyTypography.bodyEmphasized)
        TrenifyStatusPill(tone: strikeTone(row.status), label: strikeStatus(row.status))
            .accessibilityIdentifier("\(tagPrefix)-status-pill-\(row.strikeId)")
        // Explicit status text with a non-color marker; revocation is never color-only.
        Text("\(strikeSymbol(row.status)) \(strikeStatus(row.status))")
            .font(TrenifyTypography.status)
            .accessibilityIdentifier("\(tagPrefix)-status-\(row.strikeId)")
        Text(String.localizedStringWithFormat(alertsString("interval"),
            RailwayFormatting.dateTime(row.startEpochSeconds), RailwayFormatting.dateTime(row.endEpochSeconds)))
            .font(TrenifyTypography.metadata)
            .foregroundStyle(TrenifyColors.textSecondary)
            .accessibilityIdentifier("\(tagPrefix)-interval-\(row.strikeId)")
        let area = strikeArea(row)
        Text(String.localizedStringWithFormat(alertsString("area"), area.isEmpty ? alertsString("relevanceUnknown") : area))
            .font(TrenifyTypography.metadata)
            .foregroundStyle(TrenifyColors.textSecondary)
            .accessibilityIdentifier("\(tagPrefix)-area-\(row.strikeId)")
        Text(alertsString(row.railwayRelevant ? "railwayRelevant" : "notRailwayRelevant"))
            .font(TrenifyTypography.caption)
            .accessibilityIdentifier("\(tagPrefix)-railway-\(row.strikeId)")
    }

    @ViewBuilder
    private func strikeScope(_ row: NativeStrikeRow, tagPrefix: String, compact: Bool) -> some View {
        Text(strikeRelevance(row.relevance))
            .font(TrenifyTypography.metadata)
            .accessibilityIdentifier("\(tagPrefix)-relevance-\(row.strikeId)")
        if !row.operators.isEmpty {
            Text(String.localizedStringWithFormat(alertsString("operators"), row.operators.joined(separator: ", ")))
                .font(TrenifyTypography.metadata)
                .accessibilityIdentifier("\(tagPrefix)-operators-\(row.strikeId)")
        }
        if !compact {
            Text(String.localizedStringWithFormat(alertsString("mode"), row.mode))
                .font(TrenifyTypography.metadata)
                .accessibilityIdentifier("\(tagPrefix)-mode-\(row.strikeId)")
            if !row.unions.isEmpty {
                Text(String.localizedStringWithFormat(alertsString("unions"), row.unions.joined(separator: ", ")))
                    .font(TrenifyTypography.metadata)
                    .accessibilityIdentifier("\(tagPrefix)-unions-\(row.strikeId)")
            }
            if let workforce = row.workforce {
                Text(String.localizedStringWithFormat(alertsString("workforce"), workforce))
                    .font(TrenifyTypography.metadata)
                    .accessibilityIdentifier("\(tagPrefix)-workforce-\(row.strikeId)")
            }
            if let notes = row.notes {
                Text(String.localizedStringWithFormat(alertsString("notes"), notes))
                    .font(TrenifyTypography.metadata)
                    .accessibilityIdentifier("\(tagPrefix)-notes-\(row.strikeId)")
            }
            Text(String.localizedStringWithFormat(alertsString("source"), [row.sourceLabel, row.sourceUrl].joined(separator: " · ")))
                .font(TrenifyTypography.metadata)
                .foregroundStyle(TrenifyColors.textSecondary)
                .accessibilityIdentifier("\(tagPrefix)-source-\(row.strikeId)")
        }
    }

    @ViewBuilder
    private func strikeReference(_ row: NativeStrikeRow, state: NativeAlertsState, tagPrefix: String) -> some View {
        if row.canOpenReference {
            Button(alertsString("referenceOpen")) { model.openReference(row.sourceUrl) }
                .buttonStyle(.bordered).frame(minHeight: 44)
                .disabled(state.referenceInProgress)
                .accessibilityIdentifier("\(tagPrefix)-reference-open")
            if state.referenceInProgress {
                ProgressView().accessibilityIdentifier("\(tagPrefix)-reference-loading")
            }
        } else {
            Text(alertsString("referenceUnavailable"))
                .accessibilityIdentifier("\(tagPrefix)-reference-unavailable")
        }
        if state.referenceFailed {
            Text(alertsString("referenceFailed"))
                .foregroundStyle(TrenifyColors.statusCancelled)
                .accessibilityIdentifier("\(tagPrefix)-reference-failed")
        }
        Text(alertsString("guaranteedUnavailable"))
            .font(TrenifyTypography.caption)
            .accessibilityIdentifier("\(tagPrefix)-guaranteed-unavailable")
    }

    @ViewBuilder
    private func freshness(_ state: NativeAlertsState) -> some View {
        if state.loading {
            ProgressView(alertsString("loading")).accessibilityIdentifier("alerts-loading")
        }
        switch state.observation.freshness {
        case .stale:
            Text(alertsString("stale")).font(TrenifyTypography.status)
                .foregroundStyle(TrenifyColors.statusWarning)
                .accessibilityIdentifier("alerts-stale")
        case .unknown:
            if !state.loading && state.observation.failure == nil {
                Text(alertsString("unknown")).font(TrenifyTypography.caption)
                    .accessibilityIdentifier("alerts-unknown")
            }
        case .fresh: EmptyView()
        @unknown default: EmptyView()
        }
        if let failure = state.observation.failure {
            Text(alertsFailure(failure)).accessibilityIdentifier("alerts-error")
            Button(alertsString("retry")) { model.refresh() }
                .buttonStyle(.bordered).frame(minHeight: 44)
                .accessibilityIdentifier("alerts-retry")
        }
        if let fetched = state.observation.provenance.fetchedAtEpochSeconds {
            Text("\(alertsString("fetched")) · \(RailwayFormatting.dateTime(fetched.int64Value))")
                .font(TrenifyTypography.caption)
                .accessibilityIdentifier("alerts-fetched")
        }
        if let source = state.observation.provenance.sourceTimestampEpochSeconds {
            Text("\(alertsString("sourceUpdate")) · \(RailwayFormatting.dateTime(source.int64Value))")
                .font(TrenifyTypography.caption)
                .accessibilityIdentifier("alerts-source-update")
        }
    }

}

func strikeTone(_ status: ModelStrikeStatus) -> TrenifyStatusTone {
    switch status {
    case .scheduled: return .warning
    case .modified: return .delayed
    case .revoked: return .cancelled
    case .completed: return .arrived
    @unknown default: return .unknown
    }
}

func strikeSymbol(_ status: ModelStrikeStatus) -> String {
    switch status {
    case .scheduled: return "●"
    case .modified: return "◆"
    case .revoked: return "×"
    case .completed: return "■"
    @unknown default: return "?"
    }
}

func strikeStatus(_ status: ModelStrikeStatus) -> String {
    switch status {
    case .scheduled: return alertsString("statusScheduled")
    case .modified: return alertsString("statusModified")
    case .revoked: return alertsString("statusRevoked")
    case .completed: return alertsString("statusCompleted")
    @unknown default: return alertsString("statusScheduled")
    }
}

func strikeRelevance(_ relevance: ModelStrikeRelevance) -> String {
    switch relevance {
    case .local: return alertsString("relevanceLocal")
    case .provincial: return alertsString("relevanceProvincial")
    case .regional: return alertsString("relevanceRegional")
    case .interregional: return alertsString("relevanceInterregional")
    case .national: return alertsString("relevanceNational")
    case .unknown: return alertsString("relevanceUnknown")
    @unknown default: return alertsString("relevanceUnknown")
    }
}

func strikeArea(_ row: NativeStrikeRow) -> String {
    (row.regions + row.provinces).joined(separator: ", ")
}

func alertsFailure(_ failure: DomainDomainFailure) -> String {
    switch failure {
    case .offline: return alertsString("offline")
    case .temporary: return alertsString("temporary")
    case .unsupported: return alertsString("unsupported")
    case .notFound: return alertsString("realtimeUnavailable")
    default: return alertsString("invalid")
    }
}
