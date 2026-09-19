import UIKit
#if USING_AI
import DjAppAi
#else
import DjAppShared
#endif

class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = FoldHostViewController()
        window.makeKeyAndVisible()
        self.window = window
    }
}
