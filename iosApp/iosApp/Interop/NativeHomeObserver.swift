import Observation
import SharedApp

/// T8.2 observation proof. Production ContentView does not instantiate this adapter.
@MainActor
@Observable
final class NativeHomeObserver {
    private(set) var state: HomeHomeState
    private(set) var updateCount = 0
    @ObservationIgnored private(set) var task: Task<Void, Never>?
    @ObservationIgnored private let presentation: NativeHomePresentation
    @ObservationIgnored var onUpdate: ((HomeHomeState) -> Void)?

    init(presentation: NativeHomePresentation) {
        self.presentation = presentation
        state = presentation.state.value
    }

    func start() {
        guard task == nil else { return }
        let states: SkieSwiftStateFlow<HomeHomeState> = presentation.state
        // Capture the sequence, but never retain this adapter across iterator suspension.
        task = Task { @MainActor [weak self] in
            for await state in states {
                guard !Task.isCancelled else { break }
                self?.publish(state)
            }
        }
    }

    private func publish(_ next: HomeHomeState) {
        MainActor.preconditionIsolated()
        state = next
        updateCount += 1
        onUpdate?(next)
    }

    func openTrainSearch() { presentation.openTrainSearch() }

    /// Return the cancelled task so a host/test can await complete iterator teardown.
    @discardableResult
    func stop() -> Task<Void, Never>? {
        let cancelled = task
        task = nil
        cancelled?.cancel()
        return cancelled
    }

    deinit { task?.cancel() }
}
