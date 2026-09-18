#if DEBUG
import SwiftUI
import SharedApp

private struct ShellExample: View {
    @State var selection: NativePrimaryArea
    let settings: Bool

    var body: some View {
        NativeShellNavigation(selection: $selection, path: { area in
            .constant(settings && area == selection ? [ShellRoute(NativeShellEntry(identity: 1,
                destination: .settings, available: false,
                journeyResults: nil, journeyDetail: nil, stationSearch: nil, stationBoard: nil, trainSearch: nil, trainDetail: nil, monitoring: nil, saved: nil, history: nil, alerts: nil, alertStrikeId: nil, settings: nil))] : [])
        }) { _, route in
            Color(uiColor: .systemGroupedBackground)
                .overlay(Text("Trenify").font(.title))
                .navigationTitle(Text(route == nil ? primaryTitle : "shell.settings", tableName: "Shell"))
                .navigationBarTitleDisplayMode(.inline)
        }
    }

    private var primaryTitle: LocalizedStringKey {
        switch selection {
        case .search: return "shell.search"
        case .monitoring: return "shell.monitoring"
        case .saved: return "shell.saved"
        case .alerts: return "shell.alerts"
        }
    }
}

struct TrenifyShellPreviews: PreviewProvider {
    static var previews: some View {
        ShellExample(selection: .search, settings: false).preferredColorScheme(.light).previewDisplayName("Search — native tabs")
        ShellExample(selection: .monitoring, settings: false).preferredColorScheme(.dark).previewDisplayName("Monitoring — dark")
        ShellExample(selection: .saved, settings: true).preferredColorScheme(.light).previewDisplayName("Saved — secondary Settings")
    }
}
#endif
