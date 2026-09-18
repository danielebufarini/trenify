import SwiftUI

@main
@MainActor
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(TrenifyAppDelegate.self) var appDelegate
    @StateObject private var shell = NativeShellModel()

    var body: some Scene {
        WindowGroup {
            ContentView(model: shell)
                .onAppear { appDelegate.shell = shell }
        }
    }
}
