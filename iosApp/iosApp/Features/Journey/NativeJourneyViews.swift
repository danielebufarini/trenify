import SwiftUI
@preconcurrency import SharedApp

// MARK: - Hosts (one session/root; models own only observation lifetime)

/// Borrowed-session native Results host. The model owns only SKIE
/// observation lifetime, never a graph or component.
struct NativeJourneyResultsHost: View {
    let session: NativeApplicationSession
    let identity: Int64
    @StateObject private var model: NativeJourneyResultsModel

    init(session: NativeApplicationSession, identity: Int64) {
        self.session = session
        self.identity = identity
        _model = StateObject(wrappedValue: NativeJourneyResultsModel(session: session, identity: identity))
    }

    var body: some View {
        NativeJourneyResultsView(model: model)
            .onAppear { model.start() }
    }
}

/// Borrowed-session native Detail host. Same lifetime contract as Results.
struct NativeJourneyDetailHost: View {
    let session: NativeApplicationSession
    let identity: Int64
    @StateObject private var model: NativeJourneyDetailModel

    init(session: NativeApplicationSession, identity: Int64) {
        self.session = session
        self.identity = identity
        _model = StateObject(wrappedValue: NativeJourneyDetailModel(session: session, identity: identity))
    }

    var body: some View {
        NativeJourneyDetailView(model: model)
            .onAppear { model.start() }
    }
}

// MARK: - Results

private var journeyBundle: Bundle { Bundle(for: NativeJourneyResultsModel.self) }

private func journeyText(_ key: String) -> Text {
    Text(LocalizedStringKey(key), tableName: "Journey", bundle: journeyBundle)
}

private func journeyString(_ key: String) -> String {
    NSLocalizedString(key, tableName: "Journey", bundle: journeyBundle, comment: "")
}

/// Android-owned native Journey Results (T8.6): loading/refreshing with
/// content, results, empty, stale/cached and failure states; direct and
/// transfer cards with strike warnings. No prices, fares, classes or
/// purchase UI. Content cards stay readable surfaces, never glass.
struct NativeJourneyResultsView: View {
    @ObservedObject var model: NativeJourneyResultsModel

    var body: some View {
        // Back/title chrome belongs to the platform NavigationStack (Blocker 4):
        // migrated destinations expose no in-content Back control.
        ScrollView {
            // T8.14-R3: lazy vertical rendering for the card list (was an eager
            // VStack creating every card up front). Same alignment/spacing as
            // before; matches the ScrollView+LazyVStack pattern of the other
            // long-list surfaces. Stable identity still via `\.key`.
            LazyVStack(alignment: .leading, spacing: TrenifySpacing.l) {
                if let state = model.state {
                    resultsBody(state)
                } else {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                        .accessibilityLabel(Text(journeyString("journey.loading")))
                        .accessibilityIdentifier("journey-results-loading")
                }
            }
            .padding(.horizontal, TrenifySpacing.screenHorizontal)
            .padding(.bottom, TrenifySpacing.l)
        }
        // Card parity: the page carries the grouped background so each
        // TrenifyJourneyCard (surfaceRaised) reads as a distinct grouped
        // card, matching every other native list surface. Without this the
        // cards sit on the default window background and flatten into the
        // page. No content, spacing, or behavior changes.
        .background(TrenifyColors.background)
        .accessibilityIdentifier("journey-results")
    }

    @ViewBuilder
    private func resultsBody(_ state: NativeJourneyResultsState) -> some View {
        Text("\(state.originName) → \(state.destinationName)")
            .font(TrenifyTypography.screenTitle)
            .foregroundStyle(TrenifyColors.textPrimary)
            .accessibilityAddTraits(.isHeader)
            .accessibilityIdentifier("journey-results-route")
        Text("\(RailwayFormatting.dateTime(state.windowStartEpochSeconds)) – \(RailwayFormatting.dateTime(state.windowEndEpochSeconds))")
            .font(TrenifyTypography.metadata)
            .foregroundStyle(TrenifyColors.textSecondary)
            .accessibilityIdentifier("journey-results-window")
        if state.loading && !state.hasContent {
            ProgressView {
                journeyText("journey.loading")
            }
            .frame(maxWidth: .infinity)
            .accessibilityIdentifier("journey-results-loading")
        } else if state.failure != nil && !state.hasContent {
            journeyFailure(state: state, cached: false)
        } else {
            if state.loading {
                ProgressView {
                    journeyText("journey.refreshing")
                }
                .frame(maxWidth: .infinity)
                .accessibilityIdentifier("journey-results-refreshing")
            }
            if state.failure != nil {
                journeyFailure(state: state, cached: true)
            }
            sortControl(state)
            if state.empty {
                journeyText("journey.empty")
                    .font(TrenifyTypography.body)
                    .foregroundStyle(TrenifyColors.textSecondary)
                    .accessibilityIdentifier("journey-results-empty")
            } else {
                ForEach(state.journeys, id: \.key) { journey in
                    journeyCard(journey, state: state)
                }
            }
            TrenifySecondaryAction(
                title: journeyString("journey.refresh"),
                action: model.refresh
            )
            .accessibilityIdentifier("journey-refresh")
        }
    }

    @ViewBuilder
    private func journeyFailure(state: NativeJourneyResultsState, cached: Bool) -> some View {
        VStack(alignment: .leading, spacing: TrenifySpacing.s) {
            Text(failureMessage(state.failure, cached: cached))
                .font(TrenifyTypography.status)
                .foregroundStyle(TrenifyColors.statusCancelled)
                .accessibilityIdentifier(cached ? "journey-results-error-cached" : "journey-results-error")
            TrenifyPrimaryAction(title: journeyString("journey.retry"), action: model.refresh)
                .accessibilityIdentifier(cached ? "journey-results-error-cached-retry" : "journey-results-error-retry")
        }
    }

    private func failureMessage(_ failure: DomainDomainFailure?, cached: Bool) -> String {
        switch failure {
        case .offline: return journeyString(cached ? "journey.errorOfflineCached" : "journey.errorOffline")
        case .temporary: return journeyString("journey.errorTemporary")
        case .notFound: return journeyString("journey.errorNotFound")
        case .invalidRequest: return journeyString("journey.errorInvalid")
        case .unsupported: return journeyString("journey.errorUnsupported")
        case .invalidResponse: return journeyString("journey.errorInvalidResponse")
        case nil: return journeyString("journey.errorTemporary")
        @unknown default: return journeyString("journey.errorTemporary")
        }
    }

    /// Compact Journey-specific sort control: a native Menu showing the
    /// current sort, with the four shared sorts as options. Sort semantics
    /// are unchanged; only the presentation is compact. VoiceOver exposes
    /// the selected option through the native Picker.
    @ViewBuilder
    private func sortControl(_ state: NativeJourneyResultsState) -> some View {
        Menu {
            Picker(LocalizedStringKey(journeyString("journey.sort")), selection: Binding(
                get: { state.sort },
                set: { model.setSort($0) }
            )) {
                journeyText("journey.sortDeparture").tag(ModelJourneySort.departure)
                journeyText("journey.sortArrival").tag(ModelJourneySort.arrival)
                journeyText("journey.sortDuration").tag(ModelJourneySort.duration)
                journeyText("journey.sortChanges").tag(ModelJourneySort.changes)
            }
        } label: {
            Label {
                Text(String.localizedStringWithFormat(
                    journeyString("journey.sortCurrent"), sortTitle(state.sort)))
            } icon: {
                Image(systemName: "arrow.up.arrow.down")
            }
        }
        .tint(TrenifyColors.accent)
        .frame(minHeight: 44)
        .accessibilityIdentifier("journey-sort")
    }

    private func sortTitle(_ sort: ModelJourneySort) -> String {
        switch sort {
        case .departure: return journeyString("journey.sortDeparture")
        case .arrival: return journeyString("journey.sortArrival")
        case .duration: return journeyString("journey.sortDuration")
        case .changes: return journeyString("journey.sortChanges")
        @unknown default: return journeyString("journey.sortDeparture")
        }
    }

    @ViewBuilder
    private func journeyCard(_ journey: NativeJourneyCardPresentation, state: NativeJourneyResultsState) -> some View {
        VStack(alignment: .leading, spacing: TrenifySpacing.s) {
            TrenifyJourneyCard(
                trainIdentity: journey.trainIdentities.first
                    ?? journey.operatorNames.first
                    ?? journeyString("journey.trainUnknown"),
                departureTime: RailwayFormatting.time(journey.departureEpochSeconds),
                arrivalTime: RailwayFormatting.time(journey.arrivalEpochSeconds),
                origin: journey.originName,
                destination: journey.destinationName,
                durationLabel: RailwayFormatting.duration(minutes: journey.durationMinutes),
                changesLabel: changesLabel(journey),
                statusTone: journey.warningCount > 0 ? .warning : .unknown,
                // Neutral/default scheduled results carry no badge; only
                // genuine strike warnings render a status pill.
                statusLabel: warningLabel(journey),
                detailsLabel: journeyString("journey.details"),
                departureLabel: journeyString("journey.departure"),
                arrivalLabel: journeyString("journey.arrival")
            ) {
                model.select(index: journey.index)
            }
            .accessibilityIdentifier("journey-result-\(journey.index)")
            // FR-DATA-001 provider attribution remains in shared state for
            // diagnostics and detail presentation, but is not repeated on
            // every traveler-facing result card.
            // Full T7.12 warning semantics (Blocker 1/1B): every warning with
            // impact/sector/interval/source/partial wording, plus coverage
            // state truthful even with zero warnings. A warning never
            // implies cancellation.
            if !journey.warnings.isEmpty || state.strikesStale || state.strikesFailed || state.strikesUnknown {
                journeyStrikeBlock(
                    warnings: journey.warnings,
                    stale: state.strikesStale,
                    failed: state.strikesFailed,
                    unknown: state.strikesUnknown,
                    tagPrefix: "journey-\(journey.index)"
                )
            }
        }
    }

    /// Native T7.12 strike-warning presentation: one block per overlapping
    /// strike plus mutually exclusive coverage qualifiers (Unknown alone;
    /// Stale for stale or failed refresh). Renders nothing only when there
    /// are no warnings and coverage is fully known.
    @ViewBuilder
    private func journeyStrikeBlock(
        warnings: [NativeServiceStrikeWarningPresentation],
        stale: Bool,
        failed: Bool,
        unknown: Bool,
        tagPrefix: String
    ) -> some View {
        RailwayStrikeBlock(
            warnings: warnings, stale: stale, failed: failed, unknown: unknown, tagPrefix: tagPrefix)
    }

    private func strikeImpactLabel(_ warning: NativeServiceStrikeWarningPresentation) -> String {
        RailwayStrikeImpactLabel(warning)
    }


    private func changesLabel(_ journey: NativeJourneyCardPresentation) -> String {
        if journey.changes == 0 {
            if let first = journey.operatorNames.first {
                return "\(journeyString("journey.direct")) · \(first)"
            }
            return journeyString("journey.direct")
        }
        let count = Int(journey.changes)
        let changes = String.localizedStringWithFormat(
            journeyString(count == 1 ? "journey.change.one" : "journey.change.other"), count)
        var via: [String] = []
        for name in journey.legs.dropLast().map({ $0.destinationName }) where !via.contains(name) {
            via.append(name)
        }
        if via.isEmpty { return changes }
        return "\(changes) · \(String.localizedStringWithFormat(journeyString("journey.via"), via.joined(separator: ", ")))"
    }

    private func warningLabel(_ journey: NativeJourneyCardPresentation) -> String? {
        guard journey.warningCount > 0 else { return nil }
        if journey.confirmedWarning { return railwayPresentationString("journey.warningConfirmed") }
        let count = Int(journey.warningCount)
        return String.localizedStringWithFormat(
            journeyString(count == 1 ? "journey.warning.one" : "journey.warning.other"), count)
    }
}

// MARK: - Detail

/// Native Journey Detail (T8.6): route summary, per-leg schedule with
/// already-correlated realtime enrichment, strike warnings, platform info,
/// transfer guidance and the secondary official-operator booking handoff.
/// Train navigation forwards to the existing shared action (T8.7 owns Train).
struct NativeJourneyDetailView: View {
    @ObservedObject var model: NativeJourneyDetailModel

    var body: some View {
        // Back/title chrome belongs to the platform NavigationStack (Blocker 4):
        // migrated destinations expose no in-content Back control.
        ScrollView {
            VStack(alignment: .leading, spacing: TrenifySpacing.l) {
                if let state = model.state {
                    detailBody(state)
                } else {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                        .accessibilityLabel(Text(journeyString("journey.resolving")))
                        .accessibilityIdentifier("journey-detail-loading")
                }
            }
            .padding(.horizontal, TrenifySpacing.screenHorizontal)
            .padding(.bottom, TrenifySpacing.l)
        }
        .accessibilityIdentifier("journey-detail")
    }

    @ViewBuilder
    private func detailBody(_ state: NativeJourneyDetailState) -> some View {
        if state.resolving {
            ProgressView {
                journeyText("journey.resolving")
            }
            .frame(maxWidth: .infinity)
            .accessibilityIdentifier("journey-detail-loading")
        } else if state.notFound || state.originName == nil {
            journeyText("journey.notFound")
                .font(TrenifyTypography.body)
                .foregroundStyle(TrenifyColors.textSecondary)
                .accessibilityIdentifier("journey-detail-not-found")
        } else {
            detailSummary(state)
            if let favorite = model.favoriteRoute, favorite.available {
                VStack(alignment: .leading, spacing: TrenifySpacing.s) {
                    TrenifySecondaryAction(
                        title: journeyString(favorite.favorite ? "journey.removeRoute" : "journey.saveRoute"),
                        enabled: !favorite.pending,
                        action: model.toggleFavoriteRoute
                    )
                    .accessibilityIdentifier("journey-route-favorite")
                    // Restored T7-era behavior (Blocker 3): a failed update
                    // keeps content visible, shows the localized failure,
                    // invents no optimistic state and disables no unrelated
                    // action.
                    if favorite.failed {
                        Text(journeyString("journey.favoriteError"))
                            .font(TrenifyTypography.status)
                            .foregroundStyle(TrenifyColors.statusCancelled)
                            .accessibilityIdentifier("journey-route-favorite-error")
                    }
                }
            }
            bookingBlock(state)
            ForEach(Array(state.legs.enumerated()), id: \.offset) { index, leg in
                legSection(leg, index: index, total: state.legs.count, state: state)
            }
        }
    }

    @ViewBuilder
    private func detailSummary(_ state: NativeJourneyDetailState) -> some View {
        VStack(alignment: .leading, spacing: TrenifySpacing.s) {
            Text("\(state.originName ?? "") → \(state.destinationName ?? "")")
                .font(TrenifyTypography.screenTitle)
                .foregroundStyle(TrenifyColors.textPrimary)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("journey-detail-route")
            if let departure = state.departureEpochSeconds, let arrival = state.arrivalEpochSeconds {
                Text("\(RailwayFormatting.time(departure.int64Value)) → \(RailwayFormatting.time(arrival.int64Value)) · \(RailwayFormatting.date(departure.int64Value))")
                    .font(TrenifyTypography.routeTime)
                    .foregroundStyle(TrenifyColors.textPrimary)
                    .accessibilityIdentifier("journey-detail-times")
            }
            let changes = Int(state.changes?.intValue ?? 0)
            let duration = RailwayFormatting.duration(minutes: state.durationMinutes?.int64Value ?? 0)
            Text("\(duration) · \(changesText(changes))")
                .font(TrenifyTypography.bodyEmphasized)
                .foregroundStyle(TrenifyColors.textSecondary)
                .accessibilityIdentifier("journey-detail-meta")
        }
    }

    private func changesText(_ changes: Int) -> String {
        if changes == 0 { return journeyString("journey.direct") }
        return String.localizedStringWithFormat(
            journeyString(changes == 1 ? "journey.change.one" : "journey.change.other"), changes)
    }

    @ViewBuilder
    private func bookingBlock(_ state: NativeJourneyDetailState) -> some View {
        // Unavailable booking renders nothing: no explanatory or unavailable
        // copy. Availability and handoff still follow the shared T5.5 policy.
        if state.bookingAvailable {
            VStack(alignment: .leading, spacing: TrenifySpacing.s) {
                TrenifySecondaryAction(
                    title: String.localizedStringWithFormat(
                        journeyString("journey.buy"), state.bookingOperatorName ?? ""),
                    loading: state.bookingInProgress,
                    action: model.buy
                )
                .accessibilityIdentifier("journey-buy")
                if state.bookingFailed {
                    Text(journeyString("journey.buyFailed"))
                        .font(TrenifyTypography.status)
                        .foregroundStyle(TrenifyColors.statusCancelled)
                        .accessibilityIdentifier("journey-buy-failed")
                }
            }
        }
    }

    @ViewBuilder
    private func legSection(
        _ leg: NativeLegDetailPresentation, index: Int, total: Int, state: NativeJourneyDetailState
    ) -> some View {
        VStack(alignment: .leading, spacing: TrenifySpacing.s) {
            if index > 0, let wait = leg.transferWaitMinutesAfterPrevious {
                Text("\(String.localizedStringWithFormat(journeyString("journey.changeAt"), leg.originName)) · \(String.localizedStringWithFormat(journeyString("journey.wait"), Int(truncating: wait)))")
                    .font(TrenifyTypography.bodyEmphasized)
                    .foregroundStyle(TrenifyColors.textPrimary)
                    .accessibilityIdentifier("journey-transfer-\(index)")
            }
            TrenifySectionHeader(
                title: String.localizedStringWithFormat(journeyString("journey.leg"), index + 1, total),
                subtitle: "\(leg.originName) → \(leg.destinationName)"
            )
            .accessibilityIdentifier("journey-leg-\(index)")
            VStack(alignment: .leading, spacing: TrenifySpacing.s) {
                Text("\(RailwayFormatting.time(leg.departureEpochSeconds)) → \(RailwayFormatting.time(leg.arrivalEpochSeconds))")
                    .font(TrenifyTypography.routeTime)
                    .foregroundStyle(TrenifyColors.textPrimary)
                Text(leg.trainIdentity ?? journeyString("journey.trainUnknown"))
                    .font(TrenifyTypography.trainIdentity)
                    .foregroundStyle(TrenifyColors.textSecondary)
                // Operator renders only when authoritative and known; an
                // absent operator omits the row entirely, never a placeholder.
                if let operatorName = leg.operatorName {
                    Text(operatorName)
                        .font(TrenifyTypography.metadata)
                        .foregroundStyle(TrenifyColors.textSecondary)
                        .accessibilityIdentifier("journey-operator-\(index)")
                }
                legStatus(leg, index: index, state: state)
                // Expected/actual platforms, each only when available.
                if let expected = leg.scheduledPlatform {
                    Text(String.localizedStringWithFormat(
                        journeyString("journey.platformExpected"), expected))
                        .font(TrenifyTypography.metadata)
                        .foregroundStyle(TrenifyColors.textSecondary)
                        .accessibilityIdentifier("journey-platform-expected-\(index)")
                }
                if let actual = leg.actualPlatform {
                    Text(String.localizedStringWithFormat(
                        journeyString("journey.platformActual"), actual))
                        .font(TrenifyTypography.metadata)
                        .foregroundStyle(TrenifyColors.textSecondary)
                        .accessibilityIdentifier("journey-platform-actual-\(index)")
                }
                // Full T7.12 warning semantics per leg (Blocker 1/1B): every
                // warning with impact/sector/interval/source/partial wording
                // plus coverage state truthful even with zero warnings.
                if !leg.warnings.isEmpty || state.strikesStale || state.strikesFailed || state.strikesUnknown {
                    RailwayStrikeBlock(
                        warnings: leg.warnings,
                        stale: state.strikesStale,
                        failed: state.strikesFailed,
                        unknown: state.strikesUnknown,
                        tagPrefix: "journey-leg-\(index)"
                    )
                }
                if leg.hasTrainAction {
                    TrenifySecondaryAction(title: journeyString("journey.trainStatus")) {
                        model.openTrain(legIndex: Int32(index))
                    }
                    .accessibilityIdentifier("journey-realtime-\(index)")
                } else if state.correlating {
                    Text(journeyString("journey.checking"))
                        .font(TrenifyTypography.caption)
                        .foregroundStyle(TrenifyColors.textSecondary)
                        .accessibilityIdentifier("journey-correlating-\(index)")
                } else {
                    Text(journeyString("journey.realtimeUnavailable"))
                        .font(TrenifyTypography.caption)
                        .foregroundStyle(TrenifyColors.textSecondary)
                        .accessibilityIdentifier("journey-realtime-unavailable-\(index)")
                }
            }
            .padding(TrenifySpacing.l)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(TrenifyColors.surfaceRaised, in: RoundedRectangle(cornerRadius: TrenifyShapes.card, style: .continuous))
        }
    }

    @ViewBuilder
    private func legStatus(_ leg: NativeLegDetailPresentation, index: Int, state: NativeJourneyDetailState) -> some View {
        if leg.realtimeStatus == .unknown && !leg.realtimeFailed {
            TrenifyStatusPill(
                tone: .unknown,
                label: journeyString(state.correlating ? "journey.checking" : "journey.noRealtime")
            )
            .accessibilityIdentifier("journey-service-\(index)")
        } else {
            TrenifyStatusPill(
                tone: RailwayFormatting.statusTone(status: leg.realtimeStatus, delayMinutes: leg.delayMinutes),
                label: legStatusLabel(leg)
            )
            .accessibilityIdentifier("journey-service-\(index)")
        }
    }

    private func legStatusLabel(_ leg: NativeLegDetailPresentation) -> String {
        let delay = leg.delayMinutes.map { Int(truncating: $0) }
        if let delay, delay > 0,
           leg.realtimeStatus == .running || leg.realtimeStatus == .notDeparted {
            return String.localizedStringWithFormat(journeyString("journey.delayed"), delay)
        }
        switch leg.realtimeStatus {
        case .running: return journeyString(delay == 0 ? "journey.onTime" : "journey.running")
        case .notDeparted: return journeyString(delay == 0 ? "journey.onTime" : "journey.notDeparted")
        case .arrived: return journeyString("journey.arrived")
        case .cancelled: return journeyString("journey.cancelled")
        case .partiallyCancelled: return journeyString("journey.partiallyCancelled")
        case .diverted: return journeyString("journey.diverted")
        case .rescheduled: return journeyString("journey.rescheduled")
        case .unknown: return journeyString("journey.noRealtime")
        @unknown default: return journeyString("journey.noRealtime")
        }
    }

}

// MARK: - Shared railway presentation blocks live under iosApp/Presentation.
