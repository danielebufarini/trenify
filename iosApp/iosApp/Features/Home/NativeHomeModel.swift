import SwiftUI
@preconcurrency import SharedApp

/// Native Home/Search content model (T8.5). Borrows one session, owns no graph,
/// component, repository or polling job. Observes the accepted T8.2 Main/Home/
/// Journey facades through SKIE AsyncSequence on the main actor and forwards
/// existing shared actions. Ephemeral text editing lives in the view; durable
/// validation, search, history and navigation stay shared.
///
/// Observation covers the full facade chain: Main pages emit the Home facade
/// and the Journey navigation facade; the navigation facade emits each live
/// Search facade (Search -> Results closes it, Back reconnects a fresh one
/// over the retained Search component). Every collector starts exactly once
/// and every task is cancelled on close/deallocation.
@MainActor
final class NativeHomeModel: ObservableObject {
    let session: NativeApplicationSession
    @Published private(set) var homeState: HomeHomeState?
    @Published private(set) var journeyInput: NativeJourneyInputState?
    @Published private(set) var favoriteRoute: JourneyRouteFavoriteState?
    private(set) var mainTask: Task<Void, Never>?
    private(set) var homeTask: Task<Void, Never>?
    private(set) var journeyNavTask: Task<Void, Never>?
    private(set) var journeyTask: Task<Void, Never>?
    private(set) var favoriteTask: Task<Void, Never>?
    private var currentHome: NativeHomePresentation?
    private var currentJourneyNav: NativeNavigationPresentation?
    private var currentJourney: NativeJourneySearchPresentation?
    private var closed = false
    var onUpdate: (() -> Void)?

    init(session: NativeApplicationSession) {
        self.session = session
    }

    func start() {
        guard !closed, mainTask == nil else { return }
        guard let main = session.state.value.main else { return }
        let states = main.state
        mainTask = Task { @MainActor [weak self] in
            for await next in states {
                guard !Task.isCancelled else { break }
                self?.reconcileMain(next)
            }
        }
        // Seed the whole chain synchronously so the initial live Home and
        // Journey collectors start exactly once, right here, rather than
        // waiting for the next Main emission.
        reconcileMain(main.state.value)
    }

    private func reconcileMain(_ mainState: NativeMainState) {
        let home = mainState.home
        if home !== currentHome {
            homeTask?.cancel()
            homeTask = nil
            currentHome = home
            if let home {
                homeState = home.state.value
                let states: SkieSwiftStateFlow<HomeHomeState> = home.state
                homeTask = Task { @MainActor [weak self] in
                    for await next in states {
                        guard !Task.isCancelled else { break }
                        self?.publishHome(next)
                    }
                }
            } else {
                homeState = nil
            }
        }
        let journeyNav = mainState.journey
        if journeyNav !== currentJourneyNav {
            journeyNavTask?.cancel()
            journeyNavTask = nil
            journeyTask?.cancel()
            journeyTask = nil
            favoriteTask?.cancel()
            favoriteTask = nil
            currentJourneyNav = journeyNav
            currentJourney = nil
            if let journeyNav {
                reconcileJourney(journeyNav.state.value.journeySearch)
                let navStates: SkieSwiftStateFlow<NativeNavigationState> = journeyNav.state
                journeyNavTask = Task { @MainActor [weak self] in
                    for await nav in navStates {
                        guard !Task.isCancelled else { break }
                        self?.reconcileJourney(nav.journeySearch)
                    }
                }
            } else {
                journeyInput = nil
                favoriteRoute = nil
            }
        }
        onUpdate?()
    }

    private func reconcileJourney(_ journey: NativeJourneySearchPresentation?) {
        if journey !== currentJourney {
            journeyTask?.cancel()
            journeyTask = nil
            favoriteTask?.cancel()
            favoriteTask = nil
            currentJourney = journey
            if let journey {
                journeyInput = journey.state.value
                let states: SkieSwiftStateFlow<NativeJourneyInputState> = journey.state
                journeyTask = Task { @MainActor [weak self] in
                    for await next in states {
                        guard !Task.isCancelled else { break }
                        self?.publishJourney(next)
                    }
                }
                if let flow = journey.favoriteRoute {
                    let favoriteStates: SkieSwiftStateFlow<JourneyRouteFavoriteState> = flow
                    favoriteRoute = favoriteStates.value
                    favoriteTask = Task { @MainActor [weak self] in
                        for await next in favoriteStates {
                            guard !Task.isCancelled else { break }
                            self?.publishFavorite(next)
                        }
                    }
                } else {
                    favoriteRoute = nil
                }
            } else {
                journeyInput = nil
                favoriteRoute = nil
            }
        }
        onUpdate?()
    }

    private func publishHome(_ next: HomeHomeState) {
        guard !closed else { return }
        homeState = next
        onUpdate?()
    }

    private func publishJourney(_ next: NativeJourneyInputState) {
        guard !closed else { return }
        journeyInput = next
        onUpdate?()
    }

    private func publishFavorite(_ next: JourneyRouteFavoriteState) {
        guard !closed else { return }
        favoriteRoute = next
        onUpdate?()
    }

    // MARK: - Journey composer actions (existing shared semantics)

    func stationText(origin: Bool, text: String) {
        guard !closed else { return }
        currentJourney?.stationText(origin: origin, text: text)
    }

    func selectStation(_ station: ModelStation) {
        guard !closed else { return }
        currentJourney?.selectStation(station: station)
    }

    func swap() {
        guard !closed else { return }
        currentJourney?.swap()
    }

    func setDate(_ date: Kotlinx_datetimeLocalDate) {
        guard !closed else { return }
        currentJourney?.setDate(date: date)
    }

    func setTime(hour: Int32, minute: Int32) {
        guard !closed else { return }
        currentJourney?.setTime(hour: hour, minute: minute)
    }

    func setMode(_ mode: ModelJourneySearchMode) {
        guard !closed else { return }
        currentJourney?.setMode(mode: mode)
    }

    func search() {
        guard !closed, let journey = currentJourney else { return }
        journey.search()
        // JourneySearchComponent.search() synchronously marks invalid criteria,
        // so only a valid submission reveals Results: selecting the Journey
        // branch shows them above the native Home base (idempotent when the
        // composer is already on Journey). Invalid input stays on Home with
        // no navigation and no history entry.
        if journey.state.value.invalid != true {
            currentHome?.openJourneySearch()
        }
    }

    func toggleFavoriteRoute() {
        guard !closed else { return }
        currentJourney?.toggleFavoriteRoute()
    }

    func openHistory() {
        guard !closed else { return }
        currentJourney?.openHistory()
    }

    // MARK: - Home shortcut actions (existing shared navigation)

    func openTrainSearch() {
        guard !closed else { return }
        currentHome?.openTrainSearch()
    }

    func openStations() {
        guard !closed else { return }
        currentHome?.openStations()
    }

    func openHomeHistory() {
        guard !closed else { return }
        currentHome?.openHistory()
    }

    func openRecent(_ entry: any ModelSearchHistoryEntry) {
        guard !closed else { return }
        currentHome?.openRecent(entry: entry)
    }

    func openStation(_ station: ModelStation) {
        guard !closed else { return }
        currentHome?.openStation(station: station)
    }

    func openRoute(_ route: ModelFavoriteRoute) {
        guard !closed else { return }
        currentHome?.openRoute(route: route)
    }

    func openTrain(_ train: ModelFavoriteTrain) {
        guard !closed else { return }
        currentHome?.openTrain(train: train)
    }

    @discardableResult
    func close() -> Task<Void, Never>? {
        guard !closed else { return nil }
        closed = true
        let cancelled = mainTask
        mainTask = nil
        homeTask?.cancel(); homeTask = nil
        journeyNavTask?.cancel(); journeyNavTask = nil
        journeyTask?.cancel(); journeyTask = nil
        favoriteTask?.cancel(); favoriteTask = nil
        cancelled?.cancel()
        currentHome = nil
        currentJourneyNav = nil
        currentJourney = nil
        return cancelled
    }

    deinit {
        mainTask?.cancel()
        homeTask?.cancel()
        journeyNavTask?.cancel()
        journeyTask?.cancel()
        favoriteTask?.cancel()
    }
}
