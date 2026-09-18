import SwiftUI
@preconcurrency import SharedApp

/// One route's screen-level observation. Navigation and component lifetimes remain shared.
/// This adapter never builds a session/root, performs persistence, or owns polling.
@MainActor
final class NativeStationTrainModel: ObservableObject {
    let session: NativeApplicationSession
    let identity: Int64
    @Published private(set) var stationSearch: NativeStationSearchState?
    @Published private(set) var stationBoard: NativeStationBoardState?
    @Published private(set) var trainSearch: NativeTrainSearchState?
    @Published private(set) var trainDetail: NativeTrainDetailState?
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
        let facade: AnyObject? = entry?.stationSearch ?? entry?.stationBoard ?? entry?.trainSearch ?? entry?.trainDetail
        guard facade !== currentFacade else { return }
        facadeTask?.cancel()
        facadeTask = nil
        currentFacade = facade
        stationSearch = nil; stationBoard = nil; trainSearch = nil; trainDetail = nil
        if let facade = entry?.stationSearch {
            stationSearch = facade.state.value
            observe(facade.state, facade: facade) { $0.stationSearch = $1 }
        } else if let facade = entry?.stationBoard {
            stationBoard = facade.state.value
            observe(facade.state, facade: facade) { $0.stationBoard = $1 }
        } else if let facade = entry?.trainSearch {
            trainSearch = facade.state.value
            observe(facade.state, facade: facade) { $0.trainSearch = $1 }
        } else if let facade = entry?.trainDetail {
            trainDetail = facade.state.value
            observe(facade.state, facade: facade) { $0.trainDetail = $1 }
        }
        onUpdate?()
    }

    private func observe<T: AnyObject>(_ states: SkieSwiftStateFlow<T>, facade: AnyObject,
                                      publish: @escaping (NativeStationTrainModel, T) -> Void) {
        facadeTask = Task { @MainActor [weak self] in
            for await next in states {
                guard !Task.isCancelled else { break }
                guard let self, !self.closed, self.currentFacade === facade else { break }
                publish(self, next)
                self.onUpdate?()
            }
        }
    }

    func editQuery(_ query: String) { if !closed { entry?.stationSearch?.editQuery(query: query) } }
    func retryStationSearch() { if !closed { entry?.stationSearch?.retry() } }
    func selectStation(_ id: String) { if !closed { entry?.stationSearch?.selectStation(stationId: id) } }
    func toggleStationFavorite(_ id: String) { if !closed { entry?.stationSearch?.toggleFavorite(stationId: id) } }
    func removeRecent(_ id: String) { if !closed { entry?.stationSearch?.removeRecent(stationId: id) } }
    func clearRecent() { if !closed { entry?.stationSearch?.clearRecent() } }
    func searchTrains() { if !closed { entry?.stationSearch?.searchTrains() } }
    func setDirection(_ direction: ModelBoardKind) { if !closed { entry?.stationBoard?.setDirection(direction: direction) } }
    func refreshBoard() { if !closed { entry?.stationBoard?.refresh() } }
    func favoriteBoardStation() { if !closed { entry?.stationBoard?.toggleFavorite() } }
    func openBoardTrain(_ key: String) { if !closed { entry?.stationBoard?.openTrain(key: key) } }
    func editNumber(_ number: String) { if !closed { entry?.trainSearch?.editNumber(number: number) } }
    func submitTrainSearch() { if !closed { entry?.trainSearch?.submit() } }
    func selectRun(_ key: String) { if !closed { entry?.trainSearch?.selectRun(key: key) } }
    func refreshTrain() { if !closed { entry?.trainDetail?.refresh() } }
    func favoriteTrain() { if !closed { entry?.trainDetail?.toggleFavorite() } }
    func toggleMonitoring() { if !closed { entry?.trainDetail?.toggleMonitoring() } }
    func removeEndedMonitor() { if !closed { entry?.trainDetail?.removeEndedMonitor() } }
    func setMonitorNotifications(_ enabled: Bool) { if !closed { entry?.trainDetail?.setMonitorNotifications(enabled: enabled) } }
    func editThreshold(_ text: String) { if !closed { entry?.trainDetail?.editMonitorThreshold(text: text) } }
    func saveThreshold() { if !closed { entry?.trainDetail?.saveMonitorThreshold() } }
    func setMonitorEvent(_ kind: DomainMonitorEventKind, _ enabled: Bool) { if !closed { entry?.trainDetail?.setMonitorEvent(kind: kind, enabled: enabled) } }
    func retryPreferences() { if !closed { entry?.trainDetail?.retryMonitorPreferences() } }
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
