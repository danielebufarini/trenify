import SwiftUI
import SharedApp

/// Native system navigation is outermost. Four SEMANTIC tags, never live child tokens.
struct NativeAppShell: View {
    @ObservedObject var model: NativeShellModel

    var body: some View {
        NativeShellNavigation(selection: model.selection, path: model.pathBinding) { area, route in
            Group {
                if let route {
                    destination(route)
                } else if model.state.primaryArea == area {
                    destination(ShellRoute(model.state.base))
                } else {
                    Color.clear
                }
            }
        }
    }

    private func destination(_ route: ShellRoute) -> some View {
        Group {
            if route.available {
                // T8.5: production Search-tab Home is native SwiftUI. T8.6:
                // production Journey Results/Detail are native SwiftUI.
                // T8.7: Station Search/Board, Train Search/Detail are native
                // SwiftUI. T8.8: Monitoring is native SwiftUI. T8.9: Saved is
                // native SwiftUI (corrective: History renders through the
                // native History presentation). T8.10: Alerts
                // overview and Strike Detail are native SwiftUI. T8.11:
                // Settings is native SwiftUI. T8.15 removed the legacy
                // Compose hosting path; every production destination above
                // renders natively. JourneySearch no longer appears as a separate
                // visual entry (composer lives in Home), but render it
                // natively defensively so no legacy Compose Home body returns.
                if route.destination == .home || route.destination == .journeySearch {
                    NativeHomeHost(session: model.session)
                        .id(route.identity)
                } else if route.destination == .saved {
                    NativeSavedHost(session: model.session, identity: route.identity)
                        .id(route.identity)
                } else if route.destination == .history {
                    NativeHistoryHost(session: model.session, identity: route.identity)
                        .id(route.identity)
                } else if route.destination == .monitoring {
                    NativeMonitoringHost(session: model.session, identity: route.identity)
                        .id(route.identity)
                } else if route.destination == .journeyResults {
                    NativeJourneyResultsHost(session: model.session, identity: route.identity)
                        .id(route.identity)
                } else if route.destination == .journeyDetail {
                    NativeJourneyDetailHost(session: model.session, identity: route.identity)
                        .id(route.identity)
                } else if route.destination == .stationSearch || route.destination == .stationBoard || route.destination == .trainSearch || route.destination == .trainDetail {
                    NativeStationTrainHost(session: model.session, identity: route.identity).id(route.identity)
                } else if route.destination == .alertsOverview || route.destination == .strikeDetail {
                    NativeAlertsHost(session: model.session, identity: route.identity).id(route.identity)
                } else if route.destination == .settings {
                    NativeSettingsHost(session: model.session, identity: route.identity).id(route.identity)
                } else {
                    // Structurally unreachable: every production destination has
                    // an explicit native host branch above. Render nothing
                    // rather than hosting a legacy renderer (removed in T8.15).
                    Color.clear
                        .id(route.identity)
                }
            } else {
                Color.clear
            }
        }
        // All production destinations use native chrome (T8.15 removed the
        // legacy back/title path that used to hide the navigation bar).
        .toolbar(.visible, for: .navigationBar)
        .navigationTitle(Text(title(route.destination), tableName: "Shell", bundle: Bundle(for: NativeShellModel.self)))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if route.destination != .settings {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(action: model.openSettings) {
                        Label { Text("shell.settings", tableName: "Shell", bundle: Bundle(for: NativeShellModel.self)) }
                            icon: { Image(systemName: "gearshape") }
                    }
                }
            }
        }
    }

    private func title(_ destination: NativeDestination) -> LocalizedStringKey {
        switch destination {
        case .main, .home: return "shell.search"
        case .trainSearch: return "shell.trainSearch"
        case .trainDetail: return "shell.trainDetail"
        case .stationSearch: return "shell.stationSearch"
        case .stationBoard: return "shell.stationBoard"
        case .journeySearch: return "shell.journeySearch"
        case .journeyResults: return "shell.journeyResults"
        case .journeyDetail: return "shell.journeyDetail"
        case .history: return "shell.history"
        case .monitoring: return "shell.monitoring"
        case .saved: return "shell.saved"
        case .alertsOverview: return "shell.alerts"
        case .strikeDetail: return "shell.strikeDetail"
        case .settings: return "shell.settings"
        }
    }
}

/// Borrowed-session native Home host. One session/root; the home model owns only
/// SKIE observation lifetime, never a graph or component.
private struct NativeHomeHost: View {
    let session: NativeApplicationSession
    @StateObject private var homeModel: NativeHomeModel

    init(session: NativeApplicationSession) {
        self.session = session
        _homeModel = StateObject(wrappedValue: NativeHomeModel(session: session))
    }

    var body: some View {
        NativeHomeView(model: homeModel)
    }
}

/// Reusable system chrome; deterministic development previews supply display fixtures only.
struct NativeShellNavigation<Content: View>: View {
    let selection: Binding<NativePrimaryArea>
    let path: (NativePrimaryArea) -> Binding<[ShellRoute]>
    @ViewBuilder let content: (NativePrimaryArea, ShellRoute?) -> Content

    var body: some View {
        TabView(selection: selection) {
            area(.search, label: "shell.search", symbol: "magnifyingglass")
            area(.monitoring, label: "shell.monitoring", symbol: "clock")
            area(.saved, label: "shell.saved", symbol: "bookmark")
            area(.alerts, label: "shell.alerts", symbol: "exclamationmark.triangle")
        }
    }

    private func area(_ area: NativePrimaryArea, label: LocalizedStringKey, symbol: String) -> some View {
        NavigationStack(path: path(area)) {
            content(area, nil)
                .navigationDestination(for: ShellRoute.self) { content(area, $0) }
        }
        .tabItem {
            Label { Text(label, tableName: "Shell", bundle: Bundle(for: NativeShellModel.self)) }
                icon: { Image(systemName: symbol) }
        }
        .tag(area)
    }
}
