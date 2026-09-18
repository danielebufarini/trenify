import SwiftUI
@preconcurrency import SharedApp

struct NativeSavedHost: View {
    @StateObject private var model: NativeSavedModel
    init(session: NativeApplicationSession, identity: Int64) {
        _model = StateObject(wrappedValue: NativeSavedModel(session: session, identity: identity))
    }
    var body: some View { NativeSavedView(model: model).onAppear { model.start() } }
}

func savedString(_ key: String) -> String {
    NSLocalizedString("sv." + key, tableName: "Saved", bundle: Bundle(for: NativeSavedModel.self), comment: "")
}

@MainActor
struct NativeSavedView: View {
    @ObservedObject var model: NativeSavedModel

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: TrenifySpacing.m) {
                if let state = model.saved { saved(state) }
                else { ProgressView(savedString("loading")) }
            }
            .padding(.horizontal, TrenifySpacing.screenHorizontal)
            .padding(.bottom, TrenifySpacing.l)
        }
        .background(TrenifyColors.background)
        .accessibilityIdentifier("native-saved")
    }

    @ViewBuilder
    private func saved(_ state: NativeSavedState) -> some View {
        if state.loading || state.historyLoading {
            ProgressView(savedString("loading")).accessibilityIdentifier("saved-loading")
        }
        // Retained content stays visible while the failure flag shows degradation.
        if state.observationFailed || state.favoriteFailed {
            Text(savedString(state.observationFailed ? "loadError" : "updateError"))
                .foregroundStyle(TrenifyColors.statusCancelled)
                .accessibilityIdentifier("saved-error")
            Button(savedString("retry")) { model.retryFavorites() }
                .buttonStyle(.bordered).frame(minHeight: 44)
                .accessibilityIdentifier("saved-retry")
        }
        if state.historyObservationFailed || state.historyFailed {
            Text(savedString(state.historyObservationFailed ? "loadError" : "updateError"))
                .foregroundStyle(TrenifyColors.statusCancelled)
                .accessibilityIdentifier("saved-history-error")
            Button(savedString("retry")) { model.retryHistory() }
                .buttonStyle(.bordered).frame(minHeight: 44)
                .accessibilityIdentifier("saved-history-retry")
        }
        if !state.loading && !state.historyLoading && !state.observationFailed &&
            !state.historyObservationFailed && state.savedEmpty {
            Text(savedString("empty")).accessibilityIdentifier("saved-empty")
        }
        // Degraded policy: retained favorites stay visible (and actionable)
        // during observation failure; only a failure with nothing retained
        // hides the section (the banner above carries the failure state).
        if !state.loading && !(state.observationFailed && state.favoritesEmpty) {
            heading("favorites", id: "saved-favorites-header")
            if state.favoritesEmpty {
                Text(savedString("favoritesEmpty")).accessibilityIdentifier("saved-favorites-empty")
            } else {
                if !state.stations.isEmpty {
                    subheading("favoriteStations", id: "saved-stations-header")
                }
                // Stable semantic identity: station/route/favorite IDs, never indices.
                ForEach(state.stations, id: \.stationId) { row in
                    favoriteStationCard(row)
                }
                if !state.routes.isEmpty {
                    subheading("favoriteRoutes", id: "saved-routes-header")
                }
                ForEach(state.routes, id: \.identity) { row in
                    favoriteRouteCard(row)
                }
                if !state.trains.isEmpty {
                    subheading("favoriteTrains", id: "saved-trains-header")
                }
                ForEach(state.trains, id: \.identity) { row in
                    favoriteTrainCard(row)
                }
            }
        }
        // Same degraded policy for history: retained entries stay visible
        // during observation failure; a failure with nothing retained
        // hides the sections (the banner above carries the failure state).
        if !state.historyLoading && !(state.historyObservationFailed && state.journeyHistoryEmpty && state.trainHistoryEmpty) {
            HStack {
                Text(savedString("journeyHistory")).font(TrenifyTypography.sectionTitle)
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier("saved-journey-history-header")
                Spacer()
                if !state.journeyHistory.isEmpty || !state.trainHistory.isEmpty {
                    Button(savedString("clearHistory")) { model.clearHistory() }
                        .frame(minHeight: 44)
                        .accessibilityIdentifier("saved-clear-history")
                }
            }
            if state.journeyHistoryEmpty {
                Text(savedString("journeyHistoryEmpty")).accessibilityIdentifier("saved-journey-history-empty")
            }
            ForEach(state.journeyHistory, id: \.entryId) { row in
                journeyHistoryCard(row)
            }
            heading("trainHistory", id: "saved-train-history-header")
            if state.trainHistoryEmpty {
                Text(savedString("trainHistoryEmpty")).accessibilityIdentifier("saved-train-history-empty")
            }
            ForEach(state.trainHistory, id: \.entryId) { row in
                trainHistoryCard(row)
            }
        }
    }

    private func favoriteStationCard(_ row: NativeFavoriteStationRow) -> some View {
        HStack {
            Text(row.name).font(TrenifyTypography.bodyEmphasized)
            Spacer()
            Button(savedString("remove")) { model.removeStation(row.stationId) }
                .buttonStyle(.bordered).frame(minHeight: 44)
                .disabled(row.pending)
                .accessibilityIdentifier("favorite-station-remove-\(row.stationId)")
        }
        .padding(TrenifySpacing.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .foregroundStyle(TrenifyColors.textPrimary)
        .background(TrenifyColors.surfaceRaised, in: RoundedRectangle(cornerRadius: TrenifyShapes.card))
        .accessibilityIdentifier("favorite-station-\(row.stationId)")
        .onTapGesture { model.openStation(row.stationId) }
    }

    private func favoriteRouteCard(_ row: NativeFavoriteRouteRow) -> some View {
        HStack {
            Text("\(row.originName) → \(row.destinationName)").font(TrenifyTypography.bodyEmphasized)
            Spacer()
            Button(savedString("remove")) { model.removeRoute(row.identity) }
                .buttonStyle(.bordered).frame(minHeight: 44)
                .disabled(row.pending)
                .accessibilityIdentifier("favorite-route-remove-\(row.identity)")
        }
        .padding(TrenifySpacing.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .foregroundStyle(TrenifyColors.textPrimary)
        .background(TrenifyColors.surfaceRaised, in: RoundedRectangle(cornerRadius: TrenifyShapes.card))
        .accessibilityIdentifier("favorite-route-\(row.identity)")
        .onTapGesture { model.openRoute(row.identity) }
    }

    private func favoriteTrainCard(_ row: NativeFavoriteTrainRow) -> some View {
        VStack(alignment: .leading, spacing: TrenifySpacing.xs) {
            Text(String(format: savedString("trainNumber"), row.number))
                .font(TrenifyTypography.trainIdentity)
                .accessibilityIdentifier("favorite-train-identity-\(row.identity)")
            // Route/operator context distinguishes same-number favorites;
            // unknown values are omitted, never fabricated.
            if let route = savedRouteLabel(origin: row.originName, destination: row.destinationName) {
                Text(route).font(TrenifyTypography.bodyEmphasized)
                    .accessibilityIdentifier("favorite-train-context-\(row.identity)")
            }
            if let op = row.operatorName {
                Text("\(savedString("operator")) · \(op)").font(TrenifyTypography.metadata)
                    .accessibilityIdentifier("favorite-train-operator-\(row.identity)")
            }
            if row.failed {
                Text(savedString("updateError")).foregroundStyle(TrenifyColors.statusCancelled)
                    .accessibilityIdentifier("favorite-train-error-\(row.identity)")
            }
            HStack {
                Spacer()
                Button(savedString("remove")) { model.removeTrain(row.identity) }
                    .buttonStyle(.bordered).frame(minHeight: 44)
                    .disabled(!row.canRemove)
                    .accessibilityIdentifier("favorite-train-remove-\(row.identity)")
            }
        }
        .padding(TrenifySpacing.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .foregroundStyle(TrenifyColors.textPrimary)
        .background(TrenifyColors.surfaceRaised, in: RoundedRectangle(cornerRadius: TrenifyShapes.card))
        .accessibilityIdentifier("favorite-train-\(row.identity)")
        .onTapGesture { model.openTrain(row.identity) }
    }

    private func journeyHistoryCard(_ row: NativeJourneyHistoryRow) -> some View {
        VStack(alignment: .leading, spacing: TrenifySpacing.xs) {
            Text("\(row.originName) → \(row.destinationName)").font(TrenifyTypography.bodyEmphasized)
                .accessibilityIdentifier("journey-history-title-\(row.entryId)")
            Text("\(RailwayFormatting.dateTime(row.atEpochSeconds)) · \(savedJourneyMode(row.mode))")
                .font(TrenifyTypography.metadata)
                .accessibilityIdentifier("journey-history-criteria-\(row.entryId)")
            Text(String(format: savedString("searchedAt"), RailwayFormatting.dateTime(row.submittedAtEpochSeconds)))
                .font(TrenifyTypography.caption)
                .accessibilityIdentifier("journey-history-searched-\(row.entryId)")
            HStack(spacing: TrenifySpacing.s) {
                Button(savedString("repeat")) { model.repeatJourney(row.entryId) }
                    .buttonStyle(.bordered).frame(minHeight: 44)
                    .accessibilityIdentifier("journey-history-repeat-\(row.entryId)")
                Button(savedString("remove")) { model.removeHistory(row.entryId) }
                    .buttonStyle(.bordered).frame(minHeight: 44)
                    .disabled(row.pending)
                    .accessibilityIdentifier("journey-history-remove-\(row.entryId)")
            }
        }
        .padding(TrenifySpacing.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .foregroundStyle(TrenifyColors.textPrimary)
        .background(TrenifyColors.surfaceRaised, in: RoundedRectangle(cornerRadius: TrenifyShapes.card))
        .accessibilityIdentifier("journey-history-\(row.entryId)")
    }

    private func trainHistoryCard(_ row: NativeTrainHistoryRow) -> some View {
        VStack(alignment: .leading, spacing: TrenifySpacing.xs) {
            Text(String(format: savedString("trainNumber"), row.number))
                .font(TrenifyTypography.trainIdentity)
                .accessibilityIdentifier("train-history-title-\(row.entryId)")
            if let date = row.serviceDateString {
                Text("\(savedString("serviceDate")) · \(RailwayFormatting.serviceDate(date))")
                    .font(TrenifyTypography.metadata)
                    .accessibilityIdentifier("train-history-date-\(row.entryId)")
            } else {
                Text(savedString("unknownDate")).font(TrenifyTypography.metadata)
                    .accessibilityIdentifier("train-history-date-\(row.entryId)")
            }
            // Stored route context only; a number-only search shows no invented origin.
            if let route = savedRouteLabel(origin: row.originName, destination: row.destinationName) {
                Text(route).font(TrenifyTypography.bodyEmphasized)
                    .accessibilityIdentifier("train-history-context-\(row.entryId)")
            }
            if let op = row.operatorName {
                Text("\(savedString("operator")) · \(op)").font(TrenifyTypography.metadata)
                    .accessibilityIdentifier("train-history-operator-\(row.entryId)")
            }
            Text(String(format: savedString("searchedAt"), RailwayFormatting.dateTime(row.submittedAtEpochSeconds)))
                .font(TrenifyTypography.caption)
                .accessibilityIdentifier("train-history-searched-\(row.entryId)")
            HStack(spacing: TrenifySpacing.s) {
                Button(savedString("repeat")) { model.repeatTrain(row.entryId) }
                    .buttonStyle(.bordered).frame(minHeight: 44)
                    .accessibilityIdentifier("train-history-repeat-\(row.entryId)")
                Button(savedString("remove")) { model.removeHistory(row.entryId) }
                    .buttonStyle(.bordered).frame(minHeight: 44)
                    .disabled(row.pending)
                    .accessibilityIdentifier("train-history-remove-\(row.entryId)")
            }
        }
        .padding(TrenifySpacing.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .foregroundStyle(TrenifyColors.textPrimary)
        .background(TrenifyColors.surfaceRaised, in: RoundedRectangle(cornerRadius: TrenifyShapes.card))
        .accessibilityIdentifier("train-history-\(row.entryId)")
    }

    private func heading(_ key: String, id: String) -> some View {
        Text(savedString(key)).font(TrenifyTypography.sectionTitle).accessibilityAddTraits(.isHeader).accessibilityIdentifier(id)
    }

    private func subheading(_ key: String, id: String) -> some View {
        Text(savedString(key)).font(TrenifyTypography.bodyEmphasized).accessibilityAddTraits(.isHeader).accessibilityIdentifier(id)
    }
}

func savedRouteLabel(origin: String?, destination: String?) -> String? {
    let parts = [origin, destination].compactMap { $0?.isEmpty == false ? $0 : nil }
    return parts.isEmpty ? nil : parts.joined(separator: " → ")
}

func savedJourneyMode(_ mode: ModelJourneySearchMode) -> String {
    switch mode {
    case .departAfter: return savedString("departAfter")
    case .arriveBy: return savedString("arriveBy")
    @unknown default: return savedString("departAfter")
    }
}
