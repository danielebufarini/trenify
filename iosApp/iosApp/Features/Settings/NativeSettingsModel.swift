import SwiftUI
@preconcurrency import SharedApp

/// One Settings route's screen-level observation. Loading, the saved
/// notification preference, the effective OS permission, the threshold
/// draft, validation, saving/retry, the five editable event flags, the
/// shared strike opt-in and both deletion state machines remain shared;
/// this adapter never persists settings, computes permission, validates
/// policy, clears repositories, or owns durable Settings state.
@MainActor
final class NativeSettingsModel: ObservableObject {
    let session: NativeApplicationSession
    let identity: Int64
    @Published private(set) var settings: SettingsSettingsState?
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
        let facade: NativeSettingsPresentation? = entry?.settings
        guard facade !== currentFacade else { return }
        facadeTask?.cancel()
        facadeTask = nil
        currentFacade = facade
        settings = nil
        if let facade {
            settings = facade.state.value
            observe(facade.state, facade: facade) { $0.settings = $1 }
        }
        onUpdate?()
    }

    private func observe(_ states: SkieSwiftStateFlow<SettingsSettingsState>, facade: NativeSettingsPresentation,
                         publish: @escaping (NativeSettingsModel, SettingsSettingsState) -> Void) {
        facadeTask = Task { @MainActor [weak self] in
            for await next in states {
                guard !Task.isCancelled else { break }
                guard let self, !self.closed, self.currentFacade === facade else { break }
                publish(self, next)
                self.onUpdate?()
            }
        }
    }

    var thresholdText: String { settings?.delayThresholdText ?? "" }

    func setNotifications(_ enabled: Bool) { if !closed { entry?.settings?.setNotificationsEnabled(enabled: enabled) } }
    func editThreshold(_ text: String) { if !closed { entry?.settings?.editDelayThreshold(text: text) } }
    func saveThreshold() { if !closed { entry?.settings?.saveDelayThreshold() } }
    func setEventFlag(_ kind: DomainMonitorEventKind, enabled: Bool) {
        if !closed { entry?.settings?.setEventFlag(kind: kind, enabled: enabled) }
    }
    func toggleStrikeNotifications() { if !closed { entry?.settings?.toggleStrikeNotifications() } }
    func retry() { if !closed { entry?.settings?.retry() } }
    func requestHistoryDeletion() { if !closed { entry?.settings?.requestHistoryDeletion() } }
    func cancelHistoryDeletion() { if !closed { entry?.settings?.cancelHistoryDeletion() } }
    func confirmHistoryDeletion() { if !closed { entry?.settings?.confirmHistoryDeletion() } }
    func requestFavoritesDeletion() { if !closed { entry?.settings?.requestFavoritesDeletion() } }
    func cancelFavoritesDeletion() { if !closed { entry?.settings?.cancelFavoritesDeletion() } }
    func confirmFavoritesDeletion() { if !closed { entry?.settings?.confirmFavoritesDeletion() } }
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
