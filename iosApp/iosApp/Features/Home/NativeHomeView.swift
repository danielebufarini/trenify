import SwiftUI
@preconcurrency import SharedApp

/// Native Home/Search (T8.5). One composed trip input with swap, native
/// date/time pickers, journey + train/station entries, recents and favorites.
/// Shared facades stay authoritative; this view owns only ephemeral text editing
/// and visual composition through the T8.3 design system.
///
/// Text binding rule: the TextFields write through HomeComposerTextState, so
/// only user keystrokes reach `stationText` (which clears the resolved
/// endpoint by design). Shared emissions apply via `applyShared` and never
/// feed back, keeping selected, swapped and prefilled stations resolved.
struct NativeHomeView: View {
    @ObservedObject var model: NativeHomeModel
    @State private var origin = HomeComposerTextState()
    @State private var destination = HomeComposerTextState()
    @State private var tabChromeOverlap: CGFloat = 0
    @FocusState private var focusedField: HomeField?
    private enum HomeField { case origin, destination }

    private var homeBundle: Bundle { Bundle(for: NativeHomeModel.self) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: TrenifySpacing.l) {
                Text("home.title", tableName: "Home", bundle: homeBundle)
                    .font(TrenifyTypography.hero)
                    .foregroundStyle(TrenifyColors.textPrimary)
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier("home-title")
                composerCard
                modeControl
                if model.journeyInput?.invalid == true {
                    Text("home.invalid", tableName: "Home", bundle: homeBundle)
                        .font(TrenifyTypography.status)
                        .foregroundStyle(TrenifyColors.statusCancelled)
                        .accessibilityIdentifier("home-invalid")
                }
                TrenifyPrimaryAction(
                    title: NSLocalizedString("home.search", tableName: "Home", bundle: homeBundle, comment: ""),
                    enabled: model.journeyInput != nil,
                    action: { model.search() }
                )
                .accessibilityIdentifier("home-search")
                routeFavoriteControl
                HStack(spacing: TrenifySpacing.s) {
                    TrenifySecondaryAction(
                        title: NSLocalizedString("home.trainEntry", tableName: "Home", bundle: homeBundle, comment: ""),
                        action: { model.openTrainSearch() }
                    )
                    .accessibilityIdentifier("home-train-entry")
                    TrenifySecondaryAction(
                        title: NSLocalizedString("home.stationEntry", tableName: "Home", bundle: homeBundle, comment: ""),
                        action: { model.openStations() }
                    )
                    .accessibilityIdentifier("home-station-entry")
                }
                TrenifySectionHeader(
                    title: NSLocalizedString("home.recent", tableName: "Home", bundle: homeBundle, comment: ""),
                    actionLabel: NSLocalizedString("home.history", tableName: "Home", bundle: homeBundle, comment: ""),
                    action: { model.openHomeHistory() }
                )
                .accessibilityIdentifier("home-recent-header")
                if model.homeState?.observationFailed == true {
                    Text("home.loadError", tableName: "Home", bundle: homeBundle)
                        .font(TrenifyTypography.status)
                        .foregroundStyle(TrenifyColors.statusCancelled)
                        .accessibilityIdentifier("home-load-error")
                }
                recents
                TrenifySectionHeader(title: NSLocalizedString("home.favorites", tableName: "Home", bundle: homeBundle, comment: ""))
                favorites
            }
            .padding(.horizontal, TrenifySpacing.screenHorizontal)
            .padding(.top, TrenifySpacing.l)
            .padding(.bottom, TrenifySpacing.xl)
        }
        .background(TabChromeOverlapReader(overlap: $tabChromeOverlap))
        .modifier(HomeTabReachabilityInset(overlap: tabChromeOverlap))
        .background(TrenifyColors.background)
        .onAppear {
            model.start()
            origin.applyShared(model.journeyInput?.originText ?? "")
            destination.applyShared(model.journeyInput?.destinationText ?? "")
        }
        .onChange(of: model.journeyInput?.originText ?? "") { origin.applyShared($0) }
        .onChange(of: model.journeyInput?.destinationText ?? "") { destination.applyShared($0) }
    }

    // MARK: - Composer

    private var composerCard: some View {
        VStack(spacing: TrenifySpacing.m) {
            HStack(alignment: .center, spacing: TrenifySpacing.s) {
                VStack(spacing: TrenifySpacing.s) {
                    stationField(
                        label: NSLocalizedString("home.from", tableName: "Home", bundle: homeBundle, comment: ""),
                        placeholder: NSLocalizedString("home.originPlaceholder", tableName: "Home", bundle: homeBundle, comment: ""),
                        text: Binding(
                            get: { origin.local },
                            set: { editOrigin($0) }
                        ),
                        field: .origin,
                        identifier: "home-origin"
                    )
                    stationField(
                        label: NSLocalizedString("home.to", tableName: "Home", bundle: homeBundle, comment: ""),
                        placeholder: NSLocalizedString("home.destinationPlaceholder", tableName: "Home", bundle: homeBundle, comment: ""),
                        text: Binding(
                            get: { destination.local },
                            set: { editDestination($0) }
                        ),
                        field: .destination,
                        identifier: "home-destination"
                    )
                }
                Button {
                    model.swap()
                } label: {
                    Text("⇅")
                        .font(TrenifyTypography.screenTitle)
                        .foregroundStyle(TrenifyColors.accent)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(TrenifyContentControlStyle())
                .accessibilityLabel(Text("home.swap", tableName: "Home", bundle: homeBundle))
                .accessibilityIdentifier("home-swap")
            }
            if model.journeyInput?.loading == true {
                HStack(spacing: TrenifySpacing.s) {
                    ProgressView()
                    Text("home.searchingStations", tableName: "Home", bundle: homeBundle)
                        .font(TrenifyTypography.caption)
                        .foregroundStyle(TrenifyColors.textSecondary)
                }
                .frame(minHeight: 44)
                .accessibilityIdentifier("home-search-loading")
            }
            if let failure = model.journeyInput?.failure {
                Text(homeLookupFailureMessage(failure, hasCached: !(model.journeyInput?.suggestions.isEmpty ?? true)))
                    .font(TrenifyTypography.status)
                    .foregroundStyle(TrenifyColors.statusCancelled)
                    .accessibilityIdentifier("home-search-error")
            }
            if let input = model.journeyInput, !input.suggestions.isEmpty {
                Text("home.suggestions", tableName: "Home", bundle: homeBundle)
                    .font(TrenifyTypography.label)
                    .foregroundStyle(TrenifyColors.textSecondary)
                    .accessibilityAddTraits(.isHeader)
                ForEach(Array(input.suggestions.prefix(5)), id: \.stationIdentity) { station in
                    Button {
                        model.selectStation(station)
                    } label: {
                        Text(station.name)
                            .font(TrenifyTypography.body)
                            .foregroundStyle(TrenifyColors.textPrimary)
                            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(TrenifyContentControlStyle())
                    .accessibilityIdentifier("home-suggestion-\(station.stationIdentity)")
                }
            }
            HStack(spacing: TrenifySpacing.s) {
                datePicker
                timePicker
            }
        }
        .padding(TrenifySpacing.l)
        .background(TrenifyColors.surfaceRaised, in: RoundedRectangle(cornerRadius: TrenifyShapes.cardLarge))
        .accessibilityIdentifier("home-composer")
    }

    private func editOrigin(_ new: String) {
        if let forward = origin.userEdited(new, shared: model.journeyInput?.originText ?? "") {
            model.stationText(origin: true, text: forward)
        }
    }

    private func editDestination(_ new: String) {
        if let forward = destination.userEdited(new, shared: model.journeyInput?.destinationText ?? "") {
            model.stationText(origin: false, text: forward)
        }
    }

    private func stationField(label: String, placeholder: String, text: Binding<String>, field: HomeField, identifier: String) -> some View {
        VStack(alignment: .leading, spacing: TrenifySpacing.xs) {
            Text(label).font(TrenifyTypography.label).foregroundStyle(TrenifyColors.textSecondary)
            TextField("", text: text, prompt: Text(placeholder))
                .font(TrenifyTypography.routeStation)
                .foregroundStyle(TrenifyColors.textPrimary)
                .frame(minHeight: 44)
                .padding(TrenifySpacing.s)
                .background(TrenifyColors.surface, in: RoundedRectangle(cornerRadius: TrenifyShapes.control))
                .overlay {
                    RoundedRectangle(cornerRadius: TrenifyShapes.control)
                        .strokeBorder(TrenifyColors.divider, lineWidth: 1)
                }
                .focused($focusedField, equals: field)
                .accessibilityIdentifier(identifier)
        }
    }

    private var datePicker: some View {
        VStack(alignment: .leading, spacing: TrenifySpacing.xs) {
            Text("home.date", tableName: "Home", bundle: homeBundle)
                .font(TrenifyTypography.label).foregroundStyle(TrenifyColors.textSecondary)
            if let input = model.journeyInput {
                DatePicker(
                    "",
                    selection: Binding(
                        get: {
                            RomeDateConversion.date(year: Int(input.date.year), month: Int(input.date.monthNumber), day: Int(input.date.day)) ?? Date()
                        },
                        set: { setRomeDate($0) }
                    ),
                    displayedComponents: .date
                )
                .datePickerStyle(.compact)
                .labelsHidden()
                .environment(\.calendar, RomeDateConversion.romeCalendar)
                .environment(\.timeZone, RomeDateConversion.romeTimeZone)
                .frame(minHeight: 44)
                .accessibilityLabel(Text("home.date", tableName: "Home", bundle: homeBundle))
                .accessibilityIdentifier("home-date")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(TrenifySpacing.m)
        .background(TrenifyColors.surface, in: RoundedRectangle(cornerRadius: TrenifyShapes.control))
    }

    private var timePicker: some View {
        VStack(alignment: .leading, spacing: TrenifySpacing.xs) {
            Text("home.time", tableName: "Home", bundle: homeBundle)
                .font(TrenifyTypography.label).foregroundStyle(TrenifyColors.textSecondary)
            if let input = model.journeyInput {
                DatePicker(
                    "",
                    selection: Binding(
                        get: {
                            RomeDateConversion.dateTime(year: Int(input.date.year), month: Int(input.date.monthNumber), day: Int(input.date.day), hour: Int(input.timeHour), minute: Int(input.timeMinute)) ?? Date()
                        },
                        set: { setRomeTime($0) }
                    ),
                    displayedComponents: .hourAndMinute
                )
                .datePickerStyle(.compact)
                .labelsHidden()
                .environment(\.calendar, RomeDateConversion.romeCalendar)
                .environment(\.timeZone, RomeDateConversion.romeTimeZone)
                .frame(minHeight: 44)
                .accessibilityLabel(Text("home.time", tableName: "Home", bundle: homeBundle))
                .accessibilityIdentifier("home-time")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(TrenifySpacing.m)
        .background(TrenifyColors.surface, in: RoundedRectangle(cornerRadius: TrenifyShapes.control))
    }

    private var modeControl: some View {
        Group {
            if let input = model.journeyInput {
                TrenifySegmentedControl(
                    options: [
                        TrenifySegment(id: ModelJourneySearchMode.departAfter, title: NSLocalizedString("home.departAfter", tableName: "Home", bundle: homeBundle, comment: "")),
                        TrenifySegment(id: ModelJourneySearchMode.arriveBy, title: NSLocalizedString("home.arriveBy", tableName: "Home", bundle: homeBundle, comment: "")),
                    ],
                    selection: Binding(
                        get: { input.mode },
                        set: { model.setMode($0) }
                    ),
                    accessibilityLabel: NSLocalizedString("home.searchMode", tableName: "Home", bundle: homeBundle, comment: "")
                )
                .accessibilityIdentifier("home-mode")
            }
        }
    }

    @ViewBuilder
    private var routeFavoriteControl: some View {
        if let favorite = model.favoriteRoute, favorite.available {
            TrenifySecondaryAction(
                title: NSLocalizedString(
                    favorite.favorite ? "home.removeRoute" : "home.saveRoute",
                    tableName: "Home", bundle: homeBundle, comment: ""
                ),
                enabled: !favorite.pending,
                action: { model.toggleFavoriteRoute() }
            )
            .accessibilityIdentifier("home-route-favorite")
            if favorite.failed {
                Text("home.routeFavoriteError", tableName: "Home", bundle: homeBundle)
                    .font(TrenifyTypography.status)
                    .foregroundStyle(TrenifyColors.statusCancelled)
                    .accessibilityIdentifier("home-route-favorite-error")
            }
        }
    }

    // MARK: - Recents and favorites (existing shared identities)

    @ViewBuilder
    private var recents: some View {
        if let home = model.homeState {
            if home.recentSearches.isEmpty {
                Text("home.recentEmpty", tableName: "Home", bundle: homeBundle)
                    .font(TrenifyTypography.body).foregroundStyle(TrenifyColors.textSecondary)
                    .accessibilityIdentifier("home-recent-empty")
            } else {
                ForEach(home.recentSearches, id: \.recentIdentity) { entry in
                    Button {
                        model.openRecent(entry)
                    } label: {
                        VStack(alignment: .leading, spacing: TrenifySpacing.xs) {
                            Text(recentTitle(entry))
                                .font(TrenifyTypography.trainIdentity)
                                .foregroundStyle(TrenifyColors.textPrimary)
                                .fixedSize(horizontal: false, vertical: true)
                            Text("home.history", tableName: "Home", bundle: homeBundle)
                                .font(TrenifyTypography.caption)
                                .foregroundStyle(TrenifyColors.textSecondary)
                        }
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .padding(TrenifySpacing.l)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(TrenifyContentControlStyle())
                    .background(TrenifyColors.surface, in: RoundedRectangle(cornerRadius: TrenifyShapes.card))
                    .accessibilityIdentifier("home-recent-\(entry.recentIdentity)")
                }
            }
        }
    }

    @ViewBuilder
    private var favorites: some View {
        if let home = model.homeState {
            if home.favoriteStations.isEmpty && home.favoriteRoutes.isEmpty && home.favoriteTrains.isEmpty {
                Text("home.favoritesEmpty", tableName: "Home", bundle: homeBundle)
                    .font(TrenifyTypography.body).foregroundStyle(TrenifyColors.textSecondary)
                    .accessibilityIdentifier("home-favorites-empty")
            } else {
                ForEach(home.favoriteStations, id: \.stationIdentity) { station in
                    favoriteButton(id: "home-fav-station-\(station.stationIdentity)", title: station.name) {
                        model.openStation(station)
                    }
                }
                ForEach(home.favoriteRoutes, id: \.routeIdentity) { route in
                    favoriteButton(id: "home-fav-route-\(route.routeIdentity)", title: "\(route.origin.name) → \(route.destination.name)") {
                        model.openRoute(route)
                    }
                }
                ForEach(home.favoriteTrains, id: \.trainIdentity) { train in
                    favoriteButton(id: "home-fav-train-\(train.trainIdentity)", title: trainTitle(train)) {
                        model.openTrain(train)
                    }
                }
            }
        }
    }

    private func favoriteButton(id: String, title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(TrenifyTypography.bodyEmphasized)
                .foregroundStyle(TrenifyColors.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                .padding(TrenifySpacing.l)
                .contentShape(Rectangle())
        }
        .buttonStyle(TrenifyContentControlStyle())
        .background(TrenifyColors.surface, in: RoundedRectangle(cornerRadius: TrenifyShapes.card))
        .accessibilityIdentifier(id)
    }

    private func recentTitle(_ entry: any ModelSearchHistoryEntry) -> String {
        if let journey = entry as? ModelJourneySearchHistoryEntry {
            return "\(journey.origin.name) → \(journey.destination.name)"
        }
        if let train = entry as? ModelTrainSearchHistoryEntry {
            let format = NSLocalizedString("home.trainNumber", tableName: "Home", bundle: homeBundle, comment: "")
            return String(format: format, String(describing: train.number))
        }
        return NSLocalizedString("home.history", tableName: "Home", bundle: homeBundle, comment: "")
    }

    private func trainTitle(_ train: ModelFavoriteTrain) -> String {
        let number = NativeSemanticIdentity.shared.recurringTrainNumber(train: train)
        let format = NSLocalizedString("home.trainNumber", tableName: "Home", bundle: homeBundle, comment: "")
        let title = String(format: format, number)
        if let origin = train.originName {
            return "\(title) · \(origin)"
        }
        return title
    }

    /// Station-lookup failure wording mirrors the shared taxonomy (cached
    /// suggestions keep rendering next to the error).
    private func homeLookupFailureMessage(_ failure: DomainDomainFailure, hasCached: Bool) -> String {
        let key: String
        switch failure {
        case .offline:
            key = hasCached ? "home.errorOfflineCached" : "home.errorOffline"
        case .temporary:
            key = "home.errorTemporary"
        case .notFound:
            key = "home.errorTrainNotFound"
        case .invalidRequest:
            key = "home.errorInvalidRequest"
        case .unsupported:
            key = "home.errorUnsupported"
        case .invalidResponse:
            key = "home.errorInvalidResponse"
        default:
            key = "home.errorTemporary"
        }
        return NSLocalizedString(key, tableName: "Home", bundle: homeBundle, comment: "")
    }

    private func setRomeDate(_ date: Date) {
        let parts = RomeDateConversion.wallParts(of: date)
        guard let year = parts.year, let month = parts.month, let day = parts.day else { return }
        model.setDate(Kotlinx_datetimeLocalDate(year: Int32(year), month: Int32(month), day: Int32(day)))
    }

    private func setRomeTime(_ date: Date) {
        let parts = RomeDateConversion.wallParts(of: date)
        guard let hour = parts.hour, let minute = parts.minute else { return }
        // The Rome calendar date stays authoritative in shared state; only the
        // wall time moves here. The date picker owns calendar-day changes.
        model.setTime(hour: Int32(hour), minute: Int32(minute))
    }
}

private extension ModelStation {
    var stationIdentity: String { NativeSemanticIdentity.shared.station(station: self) }
}

private extension ModelSearchHistoryEntry {
    var recentIdentity: String {
        if let journey = self as? ModelJourneySearchHistoryEntry {
            return NativeSemanticIdentity.shared.history(entry: journey)
        }
        if let train = self as? ModelTrainSearchHistoryEntry {
            return NativeSemanticIdentity.shared.history(entry: train)
        }
        return String(describing: self)
    }
}

private extension ModelFavoriteRoute {
    var routeIdentity: String { NativeSemanticIdentity.shared.favoriteRoute(route: self) }
}

private extension ModelFavoriteTrain {
    var trainIdentity: String { NativeSemanticIdentity.shared.favoriteTrain(train: self) }
}
