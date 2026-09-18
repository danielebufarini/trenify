import SwiftUI
@preconcurrency import SharedApp

struct NativeHistoryHost: View {
    @StateObject private var model: NativeHistoryModel
    init(session: NativeApplicationSession, identity: Int64) {
        _model = StateObject(wrappedValue: NativeHistoryModel(session: session, identity: identity))
    }
    var body: some View { NativeHistoryView(model: model).onAppear { model.start() } }
}

@MainActor
struct NativeHistoryView: View {
    @ObservedObject var model: NativeHistoryModel

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: TrenifySpacing.m) {
                if let state = model.history { content(state) }
                else { ProgressView(savedString("loading")) }
            }
            .padding(.horizontal, TrenifySpacing.screenHorizontal)
            .padding(.bottom, TrenifySpacing.l)
        }
        .background(TrenifyColors.background)
        .accessibilityIdentifier("native-history")
    }

    @ViewBuilder
    private func content(_ state: NativeHistoryState) -> some View {
        if state.loading {
            ProgressView(savedString("loading")).accessibilityIdentifier("history-loading")
        }
        // Retained history stays visible while the failure flag shows degradation.
        if state.observationFailed || state.failedMutation {
            Text(savedString(state.observationFailed ? "loadError" : "updateError"))
                .foregroundStyle(TrenifyColors.statusCancelled)
                .accessibilityIdentifier("history-error")
            Button(savedString("retry")) { model.retryHistory() }
                .buttonStyle(.bordered).frame(minHeight: 44)
                .accessibilityIdentifier("history-retry")
        }
        // Degraded policy: retained entries stay visible (and actionable)
        // during observation failure; only a failure with nothing retained
        // hides the sections (the banner above carries the failure state).
        if !state.loading && !(state.observationFailed && state.historyEmpty) {
            HStack {
                Text(savedString("journeyHistory")).font(TrenifyTypography.sectionTitle)
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier("history-journey-header")
                Spacer()
                if !state.historyEmpty {
                    Button(savedString("clearHistory")) { model.clearHistory() }
                        .frame(minHeight: 44)
                        .accessibilityIdentifier("history-clear")
                }
            }
            if state.journeyHistoryEmpty {
                Text(savedString("journeyHistoryEmpty")).accessibilityIdentifier("history-journey-empty")
            }
            ForEach(state.journeyHistory, id: \.entryId) { row in
                journeyHistoryCard(row)
            }
            Text(savedString("trainHistory")).font(TrenifyTypography.sectionTitle)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("history-train-header")
            if state.trainHistoryEmpty {
                Text(savedString("trainHistoryEmpty")).accessibilityIdentifier("history-train-empty")
            }
            ForEach(state.trainHistory, id: \.entryId) { row in
                trainHistoryCard(row)
            }
        }
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
}
