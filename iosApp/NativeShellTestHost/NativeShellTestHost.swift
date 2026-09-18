import SwiftUI

/// Local XCTest window scene only. No graph, providers or production entry point.
@main
struct NativeShellTestHost: App {
    var body: some Scene { WindowGroup { Color.clear } }
}
