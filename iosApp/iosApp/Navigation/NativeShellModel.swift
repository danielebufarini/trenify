import SwiftUI
@preconcurrency import SharedApp

/// iOS 26 publication of SKIE-observed state. Decompose owns every route.
@MainActor
final class NativeShellModel: ObservableObject {
    let session: NativeApplicationSession
    @Published private(set) var state: NativeShellState
    private(set) var task: Task<Void, Never>?
    private var closed = false
    var onUpdate: ((NativeShellState) -> Void)?

    init(makeSession: () -> NativeApplicationSession = {
        // Installation precedes graph attachment, including cold notification launches.
        IosNotificationLaunch.shared.install()
        return IosNativeApplication.shared.createSession()
    }) {
        session = makeSession()
        state = session.shell.state.value
    }

    func start() {
        guard !closed, task == nil else { return }
        let states = session.shell.state
        task = Task { @MainActor [weak self] in
            for await next in states {
                guard !Task.isCancelled else { break }
                self?.publish(next)
            }
        }
    }

    private func publish(_ next: NativeShellState) {
        // An action may synchronously advance Decompose while an older iterator result is queued.
        guard next.isEqual(session.shell.state.value) else { return }
        state = next
        onUpdate?(next)
    }

    var selection: Binding<NativePrimaryArea> {
        Binding(get: { self.state.primaryArea }, set: { self.select($0) })
    }

    func select(_ area: NativePrimaryArea) {
        session.shell.select(area: area)
        // Immediate authoritative snapshot prevents SwiftUI binding feedback before async delivery.
        state = session.shell.state.value
    }

    func path(for area: NativePrimaryArea) -> [ShellRoute] {
        guard state.primaryArea == area else { return [] }
        return state.path.map(ShellRoute.init)
    }

    func pathBinding(for area: NativePrimaryArea) -> Binding<[ShellRoute]> {
        let represented = path(for: area)
        return Binding(get: { self.path(for: area) }, set: {
            self.requestPath($0, area: area, represented: represented)
        })
    }

    func requestPath(_ path: [ShellRoute], area: NativePrimaryArea, represented: [ShellRoute]? = nil) {
        session.shell.requestPath(area: area, identities: path.map { KotlinLong(value: $0.identity) },
            expectedIdentities: (represented ?? self.path(for: area)).map { KotlinLong(value: $0.identity) })
        state = session.shell.state.value
    }

    func back() {
        session.shell.back()
        state = session.shell.state.value
    }

    func openSettings() {
        session.shell.openSettings()
        state = session.shell.state.value
    }

    func scenePhase(_ phase: ScenePhase) {
        guard !closed else { return }
        switch phase {
        case .active: session.foreground()
        case .inactive, .background: session.background()
        @unknown default: session.background()
        }
    }

    @discardableResult
    func close() -> Task<Void, Never>? {
        guard !closed else { return nil }
        closed = true
        let cancelled = task
        task = nil
        cancelled?.cancel()
        session.close()
        return cancelled
    }

    deinit {
        task?.cancel()
        if !closed {
            // Swift 5 deinit does not inherit global-actor isolation. Always honor Kotlin's
            // main-thread lifecycle contract, including an unexpected off-main final release.
            let ownedSession = session
            if Thread.isMainThread { ownedSession.close() }
            else { DispatchQueue.main.async { ownedSession.close() } }
        }
    }
}

/// Hashable visual token copied from a typed authoritative entry. No route insertion API.
struct ShellRoute: Hashable {
    let identity: Int64
    let destination: NativeDestination
    let available: Bool

    init(_ entry: NativeShellEntry) {
        identity = entry.identity
        destination = entry.destination
        available = entry.available
    }
}
