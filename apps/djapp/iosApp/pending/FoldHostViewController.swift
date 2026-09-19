// STAGED, NOT COMPILED. Lives outside the xcodegen `DjApp` sources path because it needs the
// iOS 27.1 SDK (Xcode 27.1+). To adopt: move into DjApp/, regenerate the project, and have
// SceneDelegate set `window.rootViewController = FoldHostViewController()`. Untested: written
// from the API docs with no SDK or Duo simulator available.
import UIKit
#if USING_AI
import DjAppAi
#else
import DjAppShared
#endif

/// Hosts the Compose controller and reports the fold division to Kotlin's IosFold on each layout.
final class FoldHostViewController: UIViewController {
    private let compose = Main_iosKt.MainViewController()

    override func viewDidLoad() {
        super.viewDidLoad()
        addChild(compose)
        compose.view.frame = view.bounds
        compose.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(compose.view)
        compose.didMove(toParent: self)
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        guard #available(iOS 27.1, *) else { return }
        // Inactive regions are included so an opened-flat fold reports active = false
        // rather than vanishing, which keeps the Kotlin rule the single decision point.
        guard let fold = view.reservedRegions(kind: .division, options: [.includeInactive]).first else {
            IosFold.shared.clear()
            return
        }
        // TODO(duo): `frame` includes `margins`; Android's hinge bounds are bare. Decide on a
        // device whether to inset by the margins before reporting.
        IosFold.shared.report(
            active: fold.isActive,
            topPt: fold.frame.minY,
            bottomPt: fold.frame.maxY,
            widthPt: fold.frame.width,
            viewHeightPt: view.bounds.height,
            scale: view.window?.screen.scale ?? UIScreen.main.scale
        )
    }
}
