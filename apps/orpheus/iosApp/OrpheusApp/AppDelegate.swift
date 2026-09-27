import UIKit
import OrpheusShared

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    /// Lives as long as the app: it owns the capture session.
    private var handBridge: HandTrackingBridge?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        connectHandTracking()

        window = UIWindow(frame: UIScreen.main.bounds)
        let rootVC = Main_iosKt.MainViewController()
        window?.rootViewController = rootVC
        window?.makeKeyAndVisible()
        return true
    }

    /// The tracker must be the graph's own singleton, the one the Compose UI observes.
    private func connectHandTracking() {
        guard let tracker = OrpheusIos.shared.graph.handTracker as? IosHandTracker else {
            NSLog("[HandTracking] graph did not supply an IosHandTracker")
            return
        }
        let bridge = HandTrackingBridge(tracker: tracker)
        handBridge = bridge
        tracker.onRunningChanged = { running in
            if running.boolValue { bridge.start() } else { bridge.stop() }
        }
    }
}
