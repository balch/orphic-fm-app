import UIKit
import DjAppShared

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        window = UIWindow(frame: UIScreen.main.bounds)
        let rootVC = Main_iosKt.MainViewController()
        window?.rootViewController = rootVC
        window?.makeKeyAndVisible()
        return true
    }
}
