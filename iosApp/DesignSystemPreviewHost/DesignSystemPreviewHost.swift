import SwiftUI

/// Standalone development gallery and deterministic UIKit-backed component test host.
/// This target is independent of iosApp and never constructs a KMP application session.
@main
struct DesignSystemPreviewHost: App {
    var body: some Scene {
        WindowGroup {
#if DEBUG
            TrenifyComponentExamples()
#else
            Text("Development component gallery")
#endif
        }
    }
}
