import UIKit
import SharedApp

/// Platform launch boundary for notification delivery (T7.13-G).
///
/// Platform mechanics only: installs the shared Kotlin notification-center
/// delegate before the application finishes launching, as Apple requires.
/// No repository, train, strike or navigation decisions live here — the
/// installed delegate forwards validated payloads to shared routing, and
/// buffers cold-start responses until the shared graph attaches.
final class TrenifyAppDelegate: NSObject, UIApplicationDelegate {
    weak var shell: NativeShellModel?

    func applicationWillTerminate(_ application: UIApplication) {
        shell?.close()
    }
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        IosNotificationLaunch.shared.install()
        return true
    }
}
