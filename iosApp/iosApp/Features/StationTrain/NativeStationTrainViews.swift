import SwiftUI
@preconcurrency import SharedApp

struct NativeStationTrainHost: View {
    @StateObject private var model: NativeStationTrainModel
    init(session: NativeApplicationSession, identity: Int64) {
        _model = StateObject(wrappedValue: NativeStationTrainModel(session: session, identity: identity))
    }
    var body: some View { NativeStationTrainView(model: model).onAppear { model.start() } }
}

func stationTrainString(_ key: String) -> String {
    NSLocalizedString("st." + key, tableName: "StationTrain", bundle: Bundle(for: NativeStationTrainModel.self), comment: "")
}

enum StationTrainDetailPresentationPolicy {
    /// Freshness and provider provenance remain in the shared state and are
    /// available to diagnostics, but are not traveler-facing Train Details UI.
    static let hidesTechnicalMetadata = true
}

@MainActor
struct NativeStationTrainView: View {
    @ObservedObject var model: NativeStationTrainModel
    @State private var preferencesExpanded = false

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: TrenifySpacing.m) {
                if let state = model.stationSearch { stationSearch(state) }
                else if let state = model.stationBoard { stationBoard(state) }
                else if let state = model.trainSearch { trainSearch(state) }
                else if let state = model.trainDetail { trainDetail(state) }
                else { ProgressView(stationTrainString("loading")) }
            }
            .padding(.horizontal, TrenifySpacing.screenHorizontal)
            .padding(.bottom, TrenifySpacing.l)
        }
        .background(TrenifyColors.background)
    }

    @ViewBuilder
    private func stationSearch(_ state: NativeStationSearchState) -> some View {
        VStack(alignment: .leading, spacing: TrenifySpacing.s) {
            TextField(stationTrainString("query"), text: Binding(get: { model.stationSearch?.query ?? "" }, set: { model.editQuery($0) }))
                .textInputAutocapitalization(.words)
                .padding(TrenifySpacing.m)
                .background(TrenifyColors.surfaceRaised, in: RoundedRectangle(cornerRadius: TrenifyShapes.control))
                .accessibilityIdentifier("station-query")
            if !state.query.isEmpty {
                action("clearQuery", id: "station-query-clear") { model.editQuery("") }
            }
            action("trainSearch", id: "station-train-search", model.searchTrains)
        }
        .accessibilityIdentifier("native-station-search")
        if state.query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            heading("recent", id: "recent-title")
            // A recency failure is an overlay: cached rows below remain visible and actionable.
            if state.recencyFailed { Text(stationTrainString("recencyError")).accessibilityIdentifier("recent-error") }
            if state.recent.isEmpty { Text(stationTrainString("recentEmpty")).accessibilityIdentifier("recent-empty") }
            else { action("clearRecent", id: "recent-clear", model.clearRecent) }
            ForEach(state.recent, id: \.stationId) { row in
                stationRow(row, recent: true)
            }
        } else {
            StationTrainObservation(observation: state.observation, id: "station-search", retry: model.retryStationSearch)
            if state.observation.empty { Text(stationTrainString("empty")).accessibilityIdentifier("station-search-empty") }
            ForEach(state.results, id: \.stationId) { stationRow($0, recent: false) }
        }
    }

    private func stationRow(_ row: NativeStationRow, recent: Bool) -> some View {
        VStack(alignment: .leading, spacing: TrenifySpacing.s) {
            Button { model.selectStation(row.stationId) } label: {
                Text(row.name).font(TrenifyTypography.routeStation).frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            }
            .accessibilityIdentifier("station-\(row.stationId)")
            if recent {
                action("removeRecent", id: "recent-remove-\(row.stationId)") { model.removeRecent(row.stationId) }
            } else {
                action(row.favorite ? "removeStation" : "saveStation", id: "station-favorite-\(row.stationId)") { model.toggleStationFavorite(row.stationId) }
                    .disabled(row.pending)
                if row.failed { Text(stationTrainString("favoriteError")).foregroundStyle(TrenifyColors.statusCancelled).accessibilityIdentifier("station-favorite-error-\(row.stationId)") }
            }
            Divider()
        }
    }

    @ViewBuilder
    private func stationBoard(_ state: NativeStationBoardState) -> some View {
        if let station = state.station {
            Text(station.name).font(TrenifyTypography.screenTitle).accessibilityAddTraits(.isHeader).accessibilityIdentifier("native-station-board")
            action(station.favorite ? "removeStation" : "saveStation", id: "station-favorite-\(state.stationId)", model.favoriteBoardStation).disabled(station.pending)
            if station.failed { Text(stationTrainString("favoriteError")).accessibilityIdentifier("station-favorite-error-\(state.stationId)") }
            Picker(stationTrainString("stationBoard"), selection: Binding(get: { model.stationBoard?.direction ?? .departures }, set: { model.setDirection($0) })) {
                Text(stationTrainString("departures")).tag(ModelBoardKind.departures)
                Text(stationTrainString("arrivals")).tag(ModelBoardKind.arrivals)
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("board-direction")
            action("refresh", id: "board-refresh", model.refreshBoard)
            StationTrainObservation(observation: state.observation, id: "board", retry: model.refreshBoard)
            if state.observation.empty || (!state.observation.hasContent && !state.observation.loading && state.observation.failure == nil) {
                Text(stationTrainString("empty")).accessibilityIdentifier("board-empty")
            }
            ForEach(state.trains, id: \.identity.key) { train in
                Button { model.openBoardTrain(train.identity.key) } label: {
                    StationTrainRow(train: train, direction: state.direction)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("run-\(train.identity.key)")
            }
        } else {
            Text(stationTrainString(state.unavailable ? "stationUnavailable" : "loading")).accessibilityIdentifier("station-board-unavailable")
        }
    }

    @ViewBuilder
    private func trainSearch(_ state: NativeTrainSearchState) -> some View {
        VStack(alignment: .leading, spacing: TrenifySpacing.m) {
            Text("\(stationTrainString("serviceDate")) · \(StationTrainFormatting.serviceDate(state.serviceDate))").accessibilityIdentifier("train-service-date")
            if let origin = state.expectedOriginName {
                Text("\(stationTrainString("from")) · \(origin)").accessibilityIdentifier("train-expected-origin")
            } else if state.expectedOriginId != nil {
                Text(stationTrainString("originFilter")).accessibilityIdentifier("train-expected-origin")
            }
            if let op = state.expectedOperatorName {
                Text("\(stationTrainString("operator")) · \(op)").accessibilityIdentifier("train-expected-operator")
            }
            TextField(stationTrainString("trainNumber"), text: Binding(get: { model.trainSearch?.number ?? "" }, set: { model.editNumber($0) }))
                .keyboardType(.numberPad)
                .padding(TrenifySpacing.m)
                .background(TrenifyColors.surfaceRaised, in: RoundedRectangle(cornerRadius: TrenifyShapes.control))
                .accessibilityIdentifier("train-number")
            if state.invalidNumber { Text(stationTrainString("invalidNumber")).accessibilityIdentifier("train-invalid-number") }
            TrenifyPrimaryAction(title: stationTrainString("search"), enabled: !state.observation.loading, action: model.submitTrainSearch)
                .accessibilityIdentifier("train-submit")
            StationTrainObservation(observation: state.observation, id: "train-search", retry: model.submitTrainSearch)
            if state.noCompatibleService { Text(stationTrainString("noCompatible")).accessibilityIdentifier("train-no-compatible-service") }
            else if state.observation.empty && !state.observation.loading && state.observation.failure == nil {
                Text(stationTrainString("notFound")).accessibilityIdentifier("train-not-found")
            }
            if !state.runs.isEmpty { heading("selectRun", id: "native-train-run-picker") }
        }
        .accessibilityIdentifier("native-train-search")
        ForEach(state.runs, id: \.identity.key) { run in
            Button { model.selectRun(run.identity.key) } label: { StationTrainRow(train: run, direction: nil) }
                .buttonStyle(.plain)
                .accessibilityIdentifier("run-\(run.identity.key)")
        }
    }

    @ViewBuilder
    private func trainDetail(_ state: NativeTrainDetailState) -> some View {
        if let summary = state.summary { StationTrainRow(train: summary, direction: nil, detail: true).accessibilityIdentifier("native-train-detail") }
        StationTrainObservation(observation: state.observation, id: "train-detail", retry: state.canRefresh ? { model.refreshTrain() } : nil,
                                identifiedTrain: true, showTechnicalMetadata: !StationTrainDetailPresentationPolicy.hidesTechnicalMetadata)
        if state.favorite.available {
            action(state.favorite.favorite ? "removeTrain" : "saveTrain", id: "train-favorite", model.favoriteTrain).disabled(state.favorite.pending)
            if state.favorite.failed { Text(stationTrainString("favoriteError")).foregroundStyle(TrenifyColors.statusCancelled).accessibilityIdentifier("train-favorite-error") }
        }
        if state.ended {
            Text(stationTrainString("ended")).accessibilityIdentifier("monitor-ended-label")
            action("removeMonitor", id: "monitor-remove", model.removeEndedMonitor)
        } else if state.lifecycleResolved {
            action("refresh", id: "train-refresh", model.refreshTrain)
            action(state.monitored ? "stopMonitoring" : "startMonitoring", id: "monitor-toggle", model.toggleMonitoring)
        }
        if state.notificationPermissionDenied { Text(stationTrainString("permission")) }
        if let station = state.positionStationName, let observed = state.positionObservedAtEpochSeconds {
            Text("\(stationTrainString("position")) · \(station)").accessibilityIdentifier("train-position")
            Text("\(stationTrainString("positionObserved")) · \(StationTrainFormatting.time(observed))").accessibilityIdentifier("train-position-observed-at")
        } else { Text(stationTrainString("positionUnavailable")).accessibilityIdentifier("train-position-unavailable") }
        heading("route", id: "train-detail-stops")
        ForEach(state.stops, id: \.key) { stop in
            HStack(alignment: .top, spacing: TrenifySpacing.m) {
                Text(progressSymbol(stop.progress)).font(TrenifyTypography.routeStation).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: TrenifySpacing.s) {
                    Text(stop.name).font(TrenifyTypography.routeStation)
                    Text(stationTrainString(progressKey(stop.progress))).font(TrenifyTypography.status).accessibilityIdentifier("stop-progress-\(stop.key)")
                    StationTrainTimes(event: "arrival", scheduled: stop.scheduledArrivalEpochSeconds, actual: stop.actualArrivalEpochSeconds)
                    StationTrainTimes(event: "departure", scheduled: stop.scheduledDepartureEpochSeconds, actual: stop.actualDepartureEpochSeconds)
                    StationTrainPlatform(scheduled: stop.scheduledPlatform, actual: stop.actualPlatform, stopScoped: true)
                    if let delay = stop.delayMinutes { Text("\(stationTrainString("delay")) · \(delay) \(stationTrainString("minutes"))") }
                    Divider()
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("stop-\(stop.key)")
        }
        if !state.warnings.isEmpty || state.strikesStale || state.strikesUnknown {
            RailwayStrikeBlock(warnings: state.warnings, stale: state.strikesStale, failed: false, unknown: state.strikesUnknown, tagPrefix: "train")
        }
        if state.canEditMonitorPreferences {
            DisclosureGroup(isExpanded: $preferencesExpanded) { monitorPreferences(state) } label: {
                Text(stationTrainString("monitorPreferences")).font(TrenifyTypography.sectionTitle)
            }
            .accessibilityIdentifier("monitor-preferences")
        }
    }

    private func monitorPreferences(_ state: NativeTrainDetailState) -> some View {
        VStack(alignment: .leading, spacing: TrenifySpacing.m) {
            Toggle(stationTrainString("notifications"), isOn: Binding(get: { model.trainDetail?.monitorNotificationsEnabled?.boolValue ?? false }, set: { model.setMonitorNotifications($0) }))
                .accessibilityIdentifier("monitor-notifications")
            TextField(stationTrainString("threshold"), text: Binding(get: { model.trainDetail?.monitorThresholdText ?? "" }, set: { model.editThreshold($0) }))
                .keyboardType(.numberPad).accessibilityIdentifier("monitor-threshold")
            if state.monitorThresholdInvalid { Text(stationTrainString("invalidThreshold")).accessibilityIdentifier("monitor-threshold-error") }
            action("save", id: "monitor-threshold-save", model.saveThreshold).disabled(state.monitorThresholdInvalid)
            monitorFlag("notifyDelay", kind: .delay, checked: state.notifyDelay)
            monitorFlag("notifyPlatform", kind: .platform, checked: state.notifyPlatform)
            monitorFlag("notifyCancellation", kind: .cancellation, checked: state.notifyCancellation)
            monitorFlag("notifyDeparture", kind: .departure, checked: state.notifyDeparture)
            monitorFlag("notifyArrival", kind: .arrival, checked: state.notifyArrival)
            if state.monitorPreferencesFailed {
                Text(stationTrainString("preferencesError")).accessibilityIdentifier("monitor-prefs-error")
                action("retry", id: "monitor-prefs-retry", model.retryPreferences)
            }
        }
        .disabled(state.monitorPreferencesPending)
    }

    private func monitorFlag(_ label: String, kind: DomainMonitorEventKind, checked: Bool) -> some View {
        Toggle(stationTrainString(label), isOn: Binding(get: { checked }, set: { model.setMonitorEvent(kind, $0) }))
            .frame(minHeight: 44)
    }
    private func action(_ label: String, id: String, _ action: @escaping () -> Void) -> some View {
        Button(stationTrainString(label), action: action).buttonStyle(.bordered).frame(minHeight: 44).accessibilityIdentifier(id)
    }
    private func heading(_ key: String, id: String) -> some View {
        Text(stationTrainString(key)).font(TrenifyTypography.sectionTitle).accessibilityAddTraits(.isHeader).accessibilityIdentifier(id)
    }
}

private struct StationTrainRow: View {
    let train: NativeTrainRow
    let direction: ModelBoardKind?
    var detail = false
    var body: some View {
        VStack(alignment: .leading, spacing: TrenifySpacing.s) {
            Text("\(train.category?.code ?? "") \(train.identity.number)".trimmingCharacters(in: .whitespaces)).font(TrenifyTypography.trainIdentity)
            TrenifyStatusPill(tone: RailwayFormatting.statusTone(status: train.status, delayMinutes: train.delayMinutes), label: railwayStatusLabel(train.status, delay: train.delayMinutes))
                .accessibilityIdentifier("train-status-\(train.identity.key)")
            Text(direction == .arrivals ? "\(stationTrainString("from")) · \(train.originName)" : "\(train.originName) → \(train.destinationName ?? "—")")
                .font(detail ? TrenifyTypography.routeStation : TrenifyTypography.bodyEmphasized)
            if let category = train.category { Text(categoryLabel(category)).font(TrenifyTypography.caption) }
            Text("\(stationTrainString("operator")) · \(train.operatorName ?? stationTrainString("unknown"))").font(TrenifyTypography.metadata)
            Text("\(stationTrainString("serviceDate")) · \(StationTrainFormatting.serviceDate(train.serviceDate))").font(TrenifyTypography.metadata)
            if let direction {
                StationTrainTimes(event: direction == .departures ? "departure" : "arrival", scheduled: train.eventEpochSeconds, actual: nil, includeActual: false)
            } else {
                StationTrainTimes(event: "departure", scheduled: train.scheduledDepartureEpochSeconds, actual: nil, includeActual: false)
                StationTrainTimes(event: "arrival", scheduled: train.scheduledArrivalEpochSeconds, actual: nil, includeActual: false)
            }
            if !detail { StationTrainPlatform(scheduled: train.scheduledPlatform, actual: train.actualPlatform) }
            if let delay = train.delayMinutes { Text("\(stationTrainString("delay")) · \(delay) \(stationTrainString("minutes"))").accessibilityIdentifier("train-delay") }
            if !detail { Text("\(stationTrainString("source")) · \(train.providerName ?? stationTrainString("unknown"))").font(TrenifyTypography.caption).accessibilityIdentifier("train-source-\(train.identity.key)") }
        }
        .padding(TrenifySpacing.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .foregroundStyle(TrenifyColors.textPrimary)
        .background(TrenifyColors.surfaceRaised, in: RoundedRectangle(cornerRadius: TrenifyShapes.card))
        .accessibilityElement(children: .combine)
    }
}

private struct StationTrainTimes: View {
    let event: String
    let scheduled: KotlinLong?
    let actual: KotlinLong?
    var includeActual = true
    var body: some View {
        VStack(alignment: .leading) {
            Text("\(stationTrainString(event)) · \(stationTrainString("scheduled")): \(StationTrainFormatting.eventTime(scheduled))").font(TrenifyTypography.bodyEmphasized)
            if includeActual { Text("\(stationTrainString(event)) · \(stationTrainString("actual")): \(StationTrainFormatting.eventTime(actual))").font(TrenifyTypography.metadata) }
        }
    }
}
private struct StationTrainPlatform: View {
    let scheduled: String?
    let actual: String?
    var stopScoped = false
    var body: some View {
        if stopScoped {
            ForEach(Array(stationTrainStopPlatformLines(scheduled: scheduled, actual: actual).enumerated()), id: \.offset) { _, line in
                switch line {
                case .expected(let value):
                    Text(stationTrainPlatformLabel("expectedPlatform", value: value))
                        .font(TrenifyTypography.metadata)
                case .actual(let value):
                    Text(stationTrainPlatformLabel("actualPlatform", value: value))
                        .font(TrenifyTypography.metadata)
                }
            }
        } else {
            Text("\(stationTrainString("platform")) · \(stationTrainString("scheduled")): \(scheduled ?? "—") · \(stationTrainString("actual")): \(actual ?? "—")").font(TrenifyTypography.metadata)
        }
    }
}

enum StationTrainPlatformLine: Equatable {
    case expected(String)
    case actual(String)
}

func stationTrainPlatformLabel(_ key: String, value: String) -> String {
    String.localizedStringWithFormat(stationTrainString(key), value)
}

func stationTrainStopPlatformLines(scheduled: String?, actual: String?) -> [StationTrainPlatformLine] {
    [scheduled.map(StationTrainPlatformLine.expected), actual.map(StationTrainPlatformLine.actual)].compactMap { $0 }
}

private struct StationTrainObservation: View {
    let observation: NativeRealtimeObservation
    let id: String
    let retry: (() -> Void)?
    var identifiedTrain = false
    var showTechnicalMetadata = true
    var body: some View {
        VStack(alignment: .leading, spacing: TrenifySpacing.xs) {
            if observation.loading { ProgressView(stationTrainString("loading")).accessibilityIdentifier("\(id)-loading") }
            if showTechnicalMetadata {
                if observation.hasContent { Text(stationTrainString(freshnessKey(observation.freshness))).accessibilityIdentifier("\(id)-freshness") }
                if observation.freshness == .unknown && observation.provenance.stale && observation.hasContent { Text(stationTrainString("stale")).accessibilityIdentifier("\(id)-stale") }
                if let source = observation.provenance.providerName { Text("\(stationTrainString("source")) · \(source)").accessibilityIdentifier("\(id)-source") }
                if let fetched = observation.provenance.fetchedAtEpochSeconds { Text("\(stationTrainString("fetched")) · \(StationTrainFormatting.time(fetched))").accessibilityIdentifier("\(id)-fetched") }
                if let source = observation.provenance.sourceTimestampEpochSeconds { Text("\(stationTrainString("sourceUpdate")) · \(StationTrainFormatting.time(source))").accessibilityIdentifier("\(id)-source-update") }
                if observation.provenance.degraded && observation.hasContent { Text(stationTrainString("degraded")).accessibilityIdentifier("\(id)-degraded") }
            }
            if let failure = observation.failure {
                Text(stationTrainString(failureKey(failure, identifiedTrain: identifiedTrain))).accessibilityIdentifier("\(id)-error")
                if let retry { Button(stationTrainString("retry"), action: retry).buttonStyle(.bordered).frame(minHeight: 44).accessibilityIdentifier("\(id)-retry") }
            }
        }
        .font(TrenifyTypography.caption)
        .foregroundStyle(TrenifyColors.textSecondary)
    }
}

func progressKey(_ progress: ModelStopProgress) -> String {
    switch progress { case .completed: return "completed"; case .next: return "next"; case .future: return "future"; case .cancelled: return "stopCancelled"; case .unknown: return "unknown"; @unknown default: return "unknown" }
}
private func progressSymbol(_ progress: ModelStopProgress) -> String {
    switch progress { case .completed: return "✓"; case .next: return "→"; case .future: return "○"; case .cancelled: return "×"; case .unknown: return "?"; @unknown default: return "?" }
}
func statusLabel(_ status: ModelTrainStatus) -> String {
    let key: String
    switch status { case .running: key = "running"; case .notDeparted: key = "notDeparted"; case .arrived: key = "arrived"; case .cancelled: key = "cancelled"; case .partiallyCancelled: key = "partiallyCancelled"; case .diverted: key = "diverted"; case .rescheduled: key = "rescheduled"; case .unknown: key = "unknown"; @unknown default: key = "unknown" }
    return stationTrainString(key)
}
private func freshnessKey(_ freshness: NativeRealtimeFreshness) -> String {
    switch freshness { case .fresh: return "fresh"; case .stale: return "stale"; case .unknown: return "unknownFreshness"; @unknown default: return "unknownFreshness" }
}
private func failureKey(_ failure: DomainDomainFailure, identifiedTrain: Bool) -> String {
    switch failure { case .offline: return "offline"; case .notFound: return identifiedTrain ? "realtimeUnavailable" : "notFound"; case .unsupported: return "unsupported"; case .temporary: return "temporary"; default: return "invalid" }
}

@MainActor
enum StationTrainFormatting {
    private static let formatter: DateFormatter = {
        let value = DateFormatter(); value.timeZone = TimeZone(identifier: "Europe/Rome")
        value.locale = .autoupdatingCurrent; value.dateStyle = .medium; value.timeStyle = .short; return value
    }()
    private static let eventFormatter: DateFormatter = {
        let value = DateFormatter(); value.timeZone = TimeZone(identifier: "Europe/Rome")
        value.locale = .autoupdatingCurrent; value.dateStyle = .short; value.timeStyle = .short; return value
    }()
    static func eventTime(_ value: KotlinLong?) -> String {
        value.map { eventFormatter.string(from: Date(timeIntervalSince1970: TimeInterval($0.int64Value))) } ?? "—"
    }
    private static let dateFormatter: DateFormatter = {
        let value = DateFormatter(); value.timeZone = TimeZone(identifier: "Europe/Rome")
        value.locale = .autoupdatingCurrent; value.dateStyle = .medium; return value
    }()
    static func time(_ value: KotlinLong?) -> String {
        value.map { formatter.string(from: Date(timeIntervalSince1970: TimeInterval($0.int64Value))) } ?? "—"
    }
    static func serviceDate(_ iso: String) -> String {
        let parts = iso.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return iso }
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(identifier: "Europe/Rome")!
        guard let date = calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2], hour: 12)) else { return iso }
        return dateFormatter.string(from: date)
    }
}

private func categoryLabel(_ category: ModelTrainCategory) -> String {
    switch category {
    case .reg: return stationTrainString("categoryREG")
    case .ic: return stationTrainString("categoryIC")
    case .ec: return stationTrainString("categoryEC")
    case .en: return stationTrainString("categoryEN")
    case .fr: return stationTrainString("categoryFR")
    case .fa: return stationTrainString("categoryFA")
    case .fb: return stationTrainString("categoryFB")
    default: return category.code
    }
}

private func railwayStatusLabel(_ status: ModelTrainStatus, delay: KotlinInt?) -> String {
    let label = statusLabel(status)
    guard let delay, delay.int32Value > 0 else { return label }
    return "\(label) · +\(delay.int32Value) \(stationTrainString("minutes"))"
}
