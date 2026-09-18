import SwiftUI
@preconcurrency import SharedApp

/// One Saved route's screen-level observation. Favorites, history identity,
/// ordering, recording and repeat routing remain shared; this adapter never
/// builds a session/root, performs persistence, or owns observation beyond
/// the borrowed facade lifetime.
@MainActor
final class NativeSavedModel: ObservableObject {
    let session: NativeApplicationSession
    let identity: Int64
    @Published private(set) var saved: NativeSavedState?
    private(set) var shellTask: Task<Void, Never>?
    private(set) var facadeTask: Task<Void, Never>?
    private var entry: NativeShellEntry?
    private var currentFacade: AnyObject?
    private var closed = false
    var onUpdate: (() -> Void)?

    init(session: NativeApplicationSession, identity: Int64) {
        self.session = session
        self.identity = identity
    }

    func start() {
        guard !closed, shellTask == nil else { return }
        reconcile(session.shell.state.value)
        let states = session.shell.state
        shellTask = Task { @MainActor [weak self] in
            for await next in states {
                guard !Task.isCancelled else { break }
                self?.reconcile(next)
            }
        }
    }

    private func reconcile(_ state: NativeShellState) {
        guard !closed else { return }
        entry = ([state.base] + state.path).first { $0.identity == identity }
        let facade: NativeSavedPresentation? = entry?.saved
        guard facade !== currentFacade else { return }
        facadeTask?.cancel()
        facadeTask = nil
        currentFacade = facade
        saved = nil
        if let facade {
            saved = facade.state.value
            observe(facade.state, facade: facade) { $0.saved = $1 }
        }
        onUpdate?()
    }

    private func observe(_ states: SkieSwiftStateFlow<NativeSavedState>, facade: NativeSavedPresentation,
                         publish: @escaping (NativeSavedModel, NativeSavedState) -> Void) {
        facadeTask = Task { @MainActor [weak self] in
            for await next in states {
                guard !Task.isCancelled else { break }
                guard let self, !self.closed, self.currentFacade === facade else { break }
                publish(self, next)
                self.onUpdate?()
            }
        }
    }

    func openStation(_ stationId: String) { if !closed { entry?.saved?.openStation(stationId: stationId) } }
    func openRoute(_ identity: String) { if !closed { entry?.saved?.openRoute(identity: identity) } }
    func openTrain(_ identity: String) { if !closed { entry?.saved?.openTrain(identity: identity) } }
    func removeStation(_ stationId: String) { if !closed { entry?.saved?.removeStation(stationId: stationId) } }
    func removeRoute(_ identity: String) { if !closed { entry?.saved?.removeRoute(identity: identity) } }
    func removeTrain(_ identity: String) { if !closed { entry?.saved?.removeTrain(identity: identity) } }
    func repeatJourney(_ entryId: String) { if !closed { entry?.saved?.repeatJourney(entryId: entryId) } }
    func repeatTrain(_ entryId: String) { if !closed { entry?.saved?.repeatTrain(entryId: entryId) } }
    func removeHistory(_ entryId: String) { if !closed { entry?.saved?.removeHistory(entryId: entryId) } }
    func clearHistory() { if !closed { entry?.saved?.clearHistory() } }
    func retry() { if !closed { entry?.saved?.retry() } }
    func retryFavorites() { if !closed { entry?.saved?.retryFavorites() } }
    func retryHistory() { if !closed { entry?.saved?.retryHistory() } }
    func back() { if !closed { session.shell.back() } }

    /// Return all tasks so tests can acknowledge teardown rather than sleep.
    @discardableResult
    func close() -> [Task<Void, Never>] {
        guard !closed else { return [] }
        closed = true
        let tasks = [shellTask, facadeTask].compactMap { $0 }
        tasks.forEach { $0.cancel() }
        shellTask = nil; facadeTask = nil; currentFacade = nil; entry = nil
        return tasks
    }
    deinit { shellTask?.cancel(); facadeTask?.cancel() }
}
