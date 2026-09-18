import SwiftUI
@preconcurrency import SharedApp

/// One Alerts route's screen-level observation. Overview/detail navigation,
/// strike selection identity, refresh, notification actions, reference
/// validation/opening and stale/loading/failure state remain shared; this
/// adapter never filters strikes, decides impact, validates URLs, owns
/// persistence, performs provider calls, or implements navigation.
@MainActor
final class NativeAlertsModel: ObservableObject {
    let session: NativeApplicationSession
    let identity: Int64
    @Published private(set) var alerts: NativeAlertsState?
    @Published private(set) var strikeId: String?
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
        let facade: NativeAlertsPresentation? = entry?.alerts
        let selected: String? = entry?.alertStrikeId
        let facadeChanged = facade !== currentFacade
        if facadeChanged {
            facadeTask?.cancel()
            facadeTask = nil
            currentFacade = facade
            alerts = nil
        }
        strikeId = selected
        if let facade, facadeChanged {
            alerts = facade.state.value
            observe(facade.state, facade: facade) { $0.alerts = $1 }
        }
        onUpdate?()
    }

    private func observe(_ states: SkieSwiftStateFlow<NativeAlertsState>, facade: NativeAlertsPresentation,
                         publish: @escaping (NativeAlertsModel, NativeAlertsState) -> Void) {
        facadeTask = Task { @MainActor [weak self] in
            for await next in states {
                guard !Task.isCancelled else { break }
                guard let self, !self.closed, self.currentFacade === facade else { break }
                publish(self, next)
                self.onUpdate?()
            }
        }
    }

    /// Stable StrikeId detail lookup over current shared truth; nil is the controlled not-found path.
    var detail: NativeStrikeRow? {
        guard let alerts, let strikeId else { return nil }
        return alerts.allStrikes.first { $0.strikeId == strikeId }
    }

    func refresh() { if !closed { entry?.alerts?.refresh() } }
    func open(_ strikeId: String) { if !closed { entry?.alerts?.open(strikeId: strikeId) } }
    func back() { if !closed { session.shell.back() } }
    func toggleNotifications() { if !closed { entry?.alerts?.toggleNotifications() } }
    func openReference(_ url: String) { if !closed { entry?.alerts?.openReference(url: url) } }

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
