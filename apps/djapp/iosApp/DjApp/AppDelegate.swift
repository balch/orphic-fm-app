import UIKit
#if USING_AI
import DjAppAi
#else
import DjAppShared
#endif

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        // Runs on every launch, foreground or background. The Compose content
        // does not compose until the window renders, so a background launch
        // would otherwise reach no graph at all. Bridge installs first: bootGraph()
        // starts IosDjWidgetUpdater, and a reload in its first moments would find
        // widgetReloader nil if the order were reversed.
        WidgetReloadBridge.install()
        DjAppHost.shared.bootGraph()

        window = UIWindow(frame: UIScreen.main.bounds)
        let rootVC = Main_iosKt.MainViewController()
        window?.rootViewController = rootVC
        window?.makeKeyAndVisible()
        return true
    }
}

