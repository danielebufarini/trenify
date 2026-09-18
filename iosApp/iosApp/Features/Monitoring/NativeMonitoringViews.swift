import SwiftUI
@preconcurrency import SharedApp

struct NativeMonitoringHost: View {
    @StateObject private var model: NativeMonitoringModel
    init(session: NativeApplicationSession, identity: Int64) {
        _model = StateObject(wrappedValue: NativeMonitoringModel(session: session, identity: identity))
    }
    var body: some View { NativeMonitoringView(model: model).onAppear { model.start() } }
}

func monitoringString(_ key: String) -> String {
    NSLocalizedString("mon." + key, tableName: "Monitoring", bundle: Bundle(for: NativeMonitoringModel.self), comment: "")
}

@MainActor
struct NativeMonitoringView: View {
    @ObservedObject var model: NativeMonitoringModel

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: TrenifySpacing.m) {
                if let state = model.monitoring { monitoring(state) }
                else { ProgressView(monitoringString("loading")) }
            }
            .padding(.horizontal, TrenifySpacing.screenHorizontal)
            .padding(.bottom, TrenifySpacing.l)
        }
        .background(TrenifyColors.background)
        .accessibilityIdentifier("native-monitoring")
    }

    @ViewBuilder
    private func monitoring(_ state: NativeMonitoringState) -> some View {
        Text(monitoringString("bestEffort")).font(TrenifyTypography.caption).accessibilityIdentifier("monitoring-best-effort")
        if state.loading {
            ProgressView(monitoringString("loading")).accessibilityIdentifier("monitoring-loading")
        } else {
            if state.active.isEmpty && state.ended.isEmpty {
                Text(monitoringString("empty")).accessibilityIdentifier("monitoring-empty")
            }
            if !state.active.isEmpty {
                heading("active", id: "monitoring-active-header")
            } else if !state.ended.isEmpty {
                Text(monitoringString("noActive")).accessibilityIdentifier("monitoring-no-active")
            }
            ForEach(state.active, id: \.trainRunKey) { card in
                activeCard(card)
            }
            if !state.ended.isEmpty {
                heading("recentlyEnded", id: "monitoring-recently-ended-header")
            }
            ForEach(state.ended, id: \.trainRunKey) { card in
                endedCard(card)
            }
        }
    }

    private func activeCard(_ card: NativeMonitorCard) -> some View {
        VStack(alignment: .leading, spacing: TrenifySpacing.s) {
            monitorSummary(card, tagPrefix: "monitor")
            if card.canToggleNotifications {
                Toggle(monitoringString("notifications"), isOn: Binding(
                    get: { card.notificationsEnabled },
                    set: { model.setNotifications(card.trainRunKey, enabled: $0) }))
                    .frame(minHeight: 44)
                    .disabled(card.notificationsPending)
                    .accessibilityIdentifier("monitor-notifications-\(card.trainRunKey)")
                if card.notificationsFailed {
                    Text(monitoringString("notificationsError")).foregroundStyle(TrenifyColors.statusCancelled)
                        .accessibilityIdentifier("monitor-notifications-error-\(card.trainRunKey)")
                    Button(monitoringString("retry")) { model.retryNotifications() }
                        .buttonStyle(.bordered).frame(minHeight: 44)
                        .accessibilityIdentifier("monitor-notifications-retry-\(card.trainRunKey)")
                }
            }
            if card.canStop {
                Button(monitoringString("stopMonitoring")) { model.stopMonitor(card.trainRunKey) }
                    .buttonStyle(.bordered).frame(minHeight: 44)
                    .accessibilityIdentifier("monitor-stop-\(card.trainRunKey)")
            }
        }
        .padding(TrenifySpacing.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .foregroundStyle(TrenifyColors.textPrimary)
        .background(TrenifyColors.surfaceRaised, in: RoundedRectangle(cornerRadius: TrenifyShapes.card))
        .accessibilityIdentifier("monitor-\(card.trainRunKey)")
        .onTapGesture { model.openMonitor(card.trainRunKey) }
    }

    private func endedCard(_ card: NativeMonitorCard) -> some View {
        VStack(alignment: .leading, spacing: TrenifySpacing.s) {
            Text(monitoringString("ended")).font(TrenifyTypography.status)
                .accessibilityIdentifier("monitor-ended-label-\(card.trainRunKey)")
            monitorSummary(card, tagPrefix: "monitor-ended")
            if let endedAt = card.endedAtEpochSeconds {
                Text("\(monitoringString("endedAt")) · \(RailwayFormatting.dateTime(endedAt.int64Value))")
                    .font(TrenifyTypography.metadata)
                    .accessibilityIdentifier("monitor-ended-at-\(card.trainRunKey)")
            }
            if card.canRemove {
                Button(monitoringString("removeMonitor")) { model.removeEnded(card.trainRunKey) }
                    .buttonStyle(.bordered).frame(minHeight: 44)
                    .accessibilityIdentifier("monitor-remove-\(card.trainRunKey)")
            }
        }
        .padding(TrenifySpacing.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .foregroundStyle(TrenifyColors.textPrimary)
        .background(TrenifyColors.surfaceMuted, in: RoundedRectangle(cornerRadius: TrenifyShapes.card))
        .accessibilityIdentifier("monitor-ended-\(card.trainRunKey)")
        .onTapGesture { model.openMonitor(card.trainRunKey) }
    }

    @ViewBuilder
    private func monitorSummary(_ card: NativeMonitorCard, tagPrefix: String) -> some View {
        Text("\(card.category?.code ?? "") \(card.identity.number)".trimmingCharacters(in: .whitespaces))
            .font(TrenifyTypography.trainIdentity)
            .accessibilityIdentifier("\(tagPrefix)-identity-\(card.trainRunKey)")
        if let category = card.category {
            Text(monitoringCategoryLabel(category)).font(TrenifyTypography.caption)
        }
        Text("\(monitoringString("serviceDate")) · \(RailwayFormatting.serviceDate("\(card.identity.serviceDate)"))")
            .font(TrenifyTypography.metadata)
            .accessibilityIdentifier("\(tagPrefix)-service-date-\(card.trainRunKey)")
        TrenifyStatusPill(tone: RailwayFormatting.statusTone(status: card.status, delayMinutes: card.delayMinutes),
            label: monitoringStatusLabel(card.status, delay: card.delayMinutes))
            .accessibilityIdentifier("\(tagPrefix)-status-\(card.trainRunKey)")
        if !card.hasSnapshot {
            Text(monitoringString("noSnapshot")).font(TrenifyTypography.metadata)
                .accessibilityIdentifier("\(tagPrefix)-no-snapshot-\(card.trainRunKey)")
        } else {
            Text("\(card.originName ?? "—") → \(card.destinationName ?? "—")").font(TrenifyTypography.bodyEmphasized)
            if let op = card.operatorName { Text("\(monitoringString("operator")) · \(op)").font(TrenifyTypography.metadata) }
            if let next = card.nextStopName {
                Text("\(monitoringString("nextStop")) · \(next)").accessibilityIdentifier("\(tagPrefix)-next-stop-\(card.trainRunKey)")
            }
            if let departure = card.scheduledDepartureEpochSeconds {
                Text("\(monitoringString("departure")) · \(monitoringString("scheduled")): \(RailwayFormatting.dateTime(departure.int64Value))")
                    .font(TrenifyTypography.metadata)
                    .accessibilityIdentifier("\(tagPrefix)-departure-\(card.trainRunKey)")
            }
            if let arrival = card.scheduledArrivalEpochSeconds {
                Text("\(monitoringString("arrival")) · \(monitoringString("scheduled")): \(RailwayFormatting.dateTime(arrival.int64Value))")
                    .font(TrenifyTypography.metadata)
                    .accessibilityIdentifier("\(tagPrefix)-arrival-\(card.trainRunKey)")
            }
            if card.scheduledPlatform != nil || card.actualPlatform != nil {
                Text("\(monitoringString("platform")) · \(monitoringString("scheduled")): \(card.scheduledPlatform ?? "—") · \(monitoringString("actual")): \(card.actualPlatform ?? "—")")
                    .font(TrenifyTypography.metadata)
                    .accessibilityIdentifier("\(tagPrefix)-platform-\(card.trainRunKey)")
            }
            if let checked = card.evaluatedAtEpochSeconds {
                Text("\(monitoringString("lastChecked")) · \(RailwayFormatting.dateTime(checked.int64Value))")
                    .font(TrenifyTypography.metadata)
                    .accessibilityIdentifier("\(tagPrefix)-last-checked-\(card.trainRunKey)")
            }
        }
        MonitoringObservation(observation: card.observation, id: "\(tagPrefix)-\(card.trainRunKey)", identifiedTrain: true)
        if let failure = card.refreshFailure {
            Text(monitoringString(monitoringFailureKey(failure)))
                .accessibilityIdentifier("\(tagPrefix)-error-\(card.trainRunKey)")
        }
        if !card.warnings.isEmpty || card.strikesStale || card.strikesUnknown {
            RailwayStrikeBlock(warnings: card.warnings, stale: card.strikesStale, failed: false, unknown: card.strikesUnknown, tagPrefix: tagPrefix)
        }
    }

    private func heading(_ key: String, id: String) -> some View {
        Text(monitoringString(key)).font(TrenifyTypography.sectionTitle).accessibilityAddTraits(.isHeader).accessibilityIdentifier(id)
    }
}

private struct MonitoringObservation: View {
    let observation: NativeRealtimeObservation
    let id: String
    var identifiedTrain = false
    var body: some View {
        VStack(alignment: .leading, spacing: TrenifySpacing.xs) {
            if observation.hasContent { Text(monitoringString(monitoringFreshnessKey(observation.freshness))).accessibilityIdentifier("\(id)-freshness") }
            if let source = observation.provenance.providerName { Text("\(monitoringString("source")) · \(source)").accessibilityIdentifier("\(id)-source") }
            if let fetched = observation.provenance.fetchedAtEpochSeconds { Text("\(monitoringString("fetched")) · \(RailwayFormatting.dateTime(fetched.int64Value))").accessibilityIdentifier("\(id)-fetched") }
            if let source = observation.provenance.sourceTimestampEpochSeconds { Text("\(monitoringString("sourceUpdate")) · \(RailwayFormatting.dateTime(source.int64Value))").accessibilityIdentifier("\(id)-source-update") }
            if observation.provenance.degraded && observation.hasContent { Text(monitoringString("degraded")).accessibilityIdentifier("\(id)-degraded") }
        }
        .font(TrenifyTypography.caption)
        .foregroundStyle(TrenifyColors.textSecondary)
    }
}

private func monitoringCategoryLabel(_ category: ModelTrainCategory) -> String {
    switch category {
    case .reg: return monitoringString("categoryREG")
    case .met: return monitoringString("categoryMET")
    case .ir: return monitoringString("categoryIR")
    case .ic: return monitoringString("categoryIC")
    case .icn: return monitoringString("categoryICN")
    case .ec: return monitoringString("categoryEC")
    case .en: return monitoringString("categoryEN")
    case .exp: return monitoringString("categoryEXP")
    case .ncl: return monitoringString("categoryNCL")
    case .fr: return monitoringString("categoryFR")
    case .fa: return monitoringString("categoryFA")
    case .fb: return monitoringString("categoryFB")
    @unknown default: return category.code
    }
}

private func monitoringStatusLabel(_ status: ModelTrainStatus, delay: KotlinInt?) -> String {
    let key: String
    switch status {
    case .running: key = "running"
    case .notDeparted: key = "notDeparted"
    case .arrived: key = "arrived"
    case .cancelled: key = "cancelled"
    case .partiallyCancelled: key = "partiallyCancelled"
    case .diverted: key = "diverted"
    case .rescheduled: key = "rescheduled"
    case .unknown: key = "unknown"
    @unknown default: key = "unknown"
    }
    let label = monitoringString(key)
    guard let delay, delay.int32Value > 0 else { return label }
    return "\(label) · +\(delay.int32Value) \(monitoringString("minutes"))"
}

private func monitoringFreshnessKey(_ freshness: NativeRealtimeFreshness) -> String {
    switch freshness {
    case .fresh: return "fresh"
    case .stale: return "stale"
    case .unknown: return "unknownFreshness"
    @unknown default: return "unknownFreshness"
    }
}

private func monitoringFailureKey(_ failure: DomainDomainFailure) -> String {
    switch failure {
    case .offline: return "offline"
    case .notFound: return "realtimeUnavailable"
    case .unsupported: return "unsupported"
    case .temporary: return "temporary"
    default: return "invalid"
    }
}
