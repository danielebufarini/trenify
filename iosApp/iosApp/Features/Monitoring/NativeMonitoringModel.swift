import SwiftUI
@preconcurrency import SharedApp

/// One Monitoring route's screen-level observation. Navigation, polling,
/// retention and lifecycle remain shared; this adapter never builds a
/// session/root, performs persistence, or owns polling.
@MainActor
final class NativeMonitoringModel: ObservableObject {
    let session: NativeApplicationSession
    let identity: Int64
    @Published private(set) var monitoring: NativeMonitoringState?
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
        let facade: NativeMonitoringPresentation? = entry?.monitoring
        guard facade !== currentFacade else { return }
        facadeTask?.cancel()
        facadeTask = nil
        currentFacade = facade
        monitoring = nil
        if let facade {
            monitoring = facade.state.value
            observe(facade.state, facade: facade) { $0.monitoring = $1 }
        }
        onUpdate?()
    }

    private func observe(_ states: SkieSwiftStateFlow<NativeMonitoringState>, facade: NativeMonitoringPresentation,
                         publish: @escaping (NativeMonitoringModel, NativeMonitoringState) -> Void) {
        facadeTask = Task { @MainActor [weak self] in
            for await next in states {
                guard !Task.isCancelled else { break }
                guard let self, !self.closed, self.currentFacade === facade else { break }
                publish(self, next)
                self.onUpdate?()
            }
        }
    }

    func stopMonitor(_ key: String) { if !closed { entry?.monitoring?.stop(trainRunKey: key) } }
    func openMonitor(_ key: String) { if !closed { entry?.monitoring?.open(trainRunKey: key) } }
    func removeEnded(_ key: String) { if !closed { entry?.monitoring?.removeEnded(trainRunKey: key) } }
    func setNotifications(_ key: String, enabled: Bool) { if !closed { entry?.monitoring?.setMonitorNotifications(trainRunKey: key, enabled: enabled) } }
    func retryNotifications() { if !closed { entry?.monitoring?.retryMonitorNotifications() } }
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
