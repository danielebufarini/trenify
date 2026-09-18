import SwiftUI
@preconcurrency import SharedApp

/// One History route's screen-level observation over the existing
/// Journey-tab History child. Ordering, recording and repeat routing
/// remain shared; this adapter never builds a session/root, performs
/// persistence, or owns observation beyond the borrowed facade lifetime.
@MainActor
final class NativeHistoryModel: ObservableObject {
    let session: NativeApplicationSession
    let identity: Int64
    @Published private(set) var history: NativeHistoryState?
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
        let facade: NativeHistoryPresentation? = entry?.history
        guard facade !== currentFacade else { return }
        facadeTask?.cancel()
        facadeTask = nil
        currentFacade = facade
        history = nil
        if let facade {
            history = facade.state.value
            observe(facade.state, facade: facade) { $0.history = $1 }
        }
        onUpdate?()
    }

    private func observe(_ states: SkieSwiftStateFlow<NativeHistoryState>, facade: NativeHistoryPresentation,
                         publish: @escaping (NativeHistoryModel, NativeHistoryState) -> Void) {
        facadeTask = Task { @MainActor [weak self] in
            for await next in states {
                guard !Task.isCancelled else { break }
                guard let self, !self.closed, self.currentFacade === facade else { break }
                publish(self, next)
                self.onUpdate?()
            }
        }
    }

    func repeatJourney(_ entryId: String) { if !closed { entry?.history?.repeatJourney(entryId: entryId) } }
    func repeatTrain(_ entryId: String) { if !closed { entry?.history?.repeatTrain(entryId: entryId) } }
    func removeHistory(_ entryId: String) { if !closed { entry?.history?.removeHistory(entryId: entryId) } }
    func clearHistory() { if !closed { entry?.history?.clearHistory() } }
    /// History Retry is inherently history-scoped: the backing component
    /// owns no favorites state.
    func retryHistory() { if !closed { entry?.history?.retry() } }
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
