import WidgetKit
#if USING_AI
import DjAppAi
#else
import DjAppShared
#endif

/// WidgetCenter is Swift-only and invisible to Kotlin/Native, so the app hands
/// the reload call down to DjAppHost at launch.
enum WidgetReloadBridge {
    static func install() {
        DjAppHost.shared.setWidgetReloader {
            WidgetCenter.shared.reloadAllTimelines()
        }
    }
}
