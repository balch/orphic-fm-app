import UIKit
#if USING_AI
import DjAppAi
#else
import DjAppShared
#endif

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        // Runs on every launch, foreground or background. The window lives in
        // SceneDelegate, which a background launch never reaches, so the graph
        // boots here or not at all. Bridge installs first: bootGraph()
        // starts IosDjWidgetUpdater, and a reload in its first moments would find
        // widgetReloader nil if the order were reversed.
        WidgetReloadBridge.install()
        DjAppHost.shared.bootGraph()
        return true
    }
}

