import SwiftUI

struct ContentView: View {
    @ObservedObject var model: NativeShellModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NativeAppShell(model: model)
            .onAppear { model.start(); model.scenePhase(scenePhase) }
            .onChange(of: scenePhase) { model.scenePhase($0) }
    }
}
