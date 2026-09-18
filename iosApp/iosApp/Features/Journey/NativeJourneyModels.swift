import SwiftUI
@preconcurrency import SharedApp

/// Native Journey Results content model (T8.6). Borrows one session, owns no
/// graph, component, repository or polling job. Observes the shell snapshot
/// through the SKIE AsyncSequence, then the live Results facade for this
/// exact route identity. Every collector starts exactly once and every task
/// is cancelled on close/deallocation.
@MainActor
final class NativeJourneyResultsModel: ObservableObject {
    let session: NativeApplicationSession
    let identity: Int64
    @Published private(set) var state: NativeJourneyResultsState?
    private(set) var shellTask: Task<Void, Never>?
    private(set) var facadeTask: Task<Void, Never>?
    private var currentFacade: NativeJourneyResultsPresentation?
    private var closed = false
    var onUpdate: (() -> Void)?

    init(session: NativeApplicationSession, identity: Int64) {
        self.session = session
        self.identity = identity
    }

    func start() {
        guard !closed, shellTask == nil else { return }
        let states = session.shell.state
        shellTask = Task { @MainActor [weak self] in
            for await next in states {
                guard !Task.isCancelled else { break }
                self?.reconcile(next)
            }
        }
        reconcile(session.shell.state.value)
    }

    private func reconcile(_ shell: NativeShellState) {
        let facade = shell.path.first(where: { $0.identity == identity })?.journeyResults
            ?? (shell.base.identity == identity ? shell.base.journeyResults : nil)
        if facade !== currentFacade {
            facadeTask?.cancel()
            facadeTask = nil
            currentFacade = facade
            if let facade {
                state = facade.state.value
                let facadeStates: SkieSwiftStateFlow<NativeJourneyResultsState> = facade.state
                facadeTask = Task { @MainActor [weak self] in
                    for await next in facadeStates {
                        guard !Task.isCancelled else { break }
                        self?.publish(next)
                    }
                }
            } else {
                state = nil
            }
        }
        onUpdate?()
    }

    private func publish(_ next: NativeJourneyResultsState) {
        guard !closed else { return }
        state = next
        onUpdate?()
    }

    func refresh() {
        guard !closed else { return }
        currentFacade?.refresh()
    }

    func setSort(_ sort: ModelJourneySort) {
        guard !closed else { return }
        currentFacade?.setSort(sort: sort)
    }

    func select(index: Int32) {
        guard !closed else { return }
        currentFacade?.selectJourney(index: index)
    }

    func back() {
        guard !closed else { return }
        session.shell.back()
    }

    @discardableResult
    func close() -> Task<Void, Never>? {
        guard !closed else { return nil }
        closed = true
        let cancelled = shellTask
        shellTask = nil
        facadeTask?.cancel(); facadeTask = nil
        cancelled?.cancel()
        currentFacade = nil
        return cancelled
    }

    deinit {
        shellTask?.cancel()
        facadeTask?.cancel()
    }
}

/// Native Journey Detail content model (T8.6). Same observation contract as
/// the Results model: shell snapshot first, then the live Detail facade for
/// this exact route identity. Booking opens through the shared policy action;
/// train navigation forwards to the existing shared action (T8.7 owns Train).
@MainActor
final class NativeJourneyDetailModel: ObservableObject {
    let session: NativeApplicationSession
    let identity: Int64
    @Published private(set) var state: NativeJourneyDetailState?
    @Published private(set) var favoriteRoute: JourneyRouteFavoriteState?
    private(set) var shellTask: Task<Void, Never>?
    private(set) var facadeTask: Task<Void, Never>?
    private(set) var favoriteTask: Task<Void, Never>?
    private var currentFacade: NativeJourneyDetailPresentation?
    private var closed = false
    var onUpdate: (() -> Void)?

    init(session: NativeApplicationSession, identity: Int64) {
        self.session = session
        self.identity = identity
    }

    func start() {
        guard !closed, shellTask == nil else { return }
        let states = session.shell.state
        shellTask = Task { @MainActor [weak self] in
            for await next in states {
                guard !Task.isCancelled else { break }
                self?.reconcile(next)
            }
        }
        reconcile(session.shell.state.value)
    }

    private func reconcile(_ shell: NativeShellState) {
        let facade = shell.path.first(where: { $0.identity == identity })?.journeyDetail
            ?? (shell.base.identity == identity ? shell.base.journeyDetail : nil)
        if facade !== currentFacade {
            facadeTask?.cancel()
            facadeTask = nil
            favoriteTask?.cancel()
            favoriteTask = nil
            currentFacade = facade
            if let facade {
                state = facade.state.value
                let facadeStates: SkieSwiftStateFlow<NativeJourneyDetailState> = facade.state
                facadeTask = Task { @MainActor [weak self] in
                    for await next in facadeStates {
                        guard !Task.isCancelled else { break }
                        self?.publish(next)
                    }
                }
                if let favorites = facade.favoriteRoute {
                    let favoriteStates: SkieSwiftStateFlow<JourneyRouteFavoriteState> = favorites
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
                state = nil
                favoriteRoute = nil
            }
        }
        onUpdate?()
    }

    private func publish(_ next: NativeJourneyDetailState) {
        guard !closed else { return }
        state = next
        onUpdate?()
    }

    private func publishFavorite(_ next: JourneyRouteFavoriteState) {
        guard !closed else { return }
        favoriteRoute = next
        onUpdate?()
    }

    func openTrain(legIndex: Int32) {
        guard !closed else { return }
        currentFacade?.openTrain(legIndex: legIndex)
    }

    func buy() {
        guard !closed else { return }
        currentFacade?.buy()
    }

    func toggleFavoriteRoute() {
        guard !closed else { return }
        currentFacade?.toggleFavoriteRoute()
    }

    func back() {
        guard !closed else { return }
        session.shell.back()
    }

    @discardableResult
    func close() -> Task<Void, Never>? {
        guard !closed else { return nil }
        closed = true
        let cancelled = shellTask
        shellTask = nil
        facadeTask?.cancel(); facadeTask = nil
        favoriteTask?.cancel(); favoriteTask = nil
        cancelled?.cancel()
        currentFacade = nil
        return cancelled
    }

    deinit {
        shellTask?.cancel()
        facadeTask?.cancel()
        favoriteTask?.cancel()
    }
}
