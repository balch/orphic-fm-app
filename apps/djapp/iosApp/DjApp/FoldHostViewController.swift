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

    override var childForStatusBarHidden: UIViewController? { compose }
    override var childForHomeIndicatorAutoHidden: UIViewController? { compose }

    // Reserved regions arrive with the iOS 27.1 SDK (UIKit 9127.0.85). Older SDKs compile the
    // host without fold reporting, so release builds on Xcode 27.0 keep working.
    #if canImport(UIKit, _version: 9127.0.85)
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        guard #available(iOS 27.1, *) else { return }
        // Inactive regions are included so an opened-flat fold reports active = false
        // rather than vanishing, which keeps the Kotlin rule the single decision point.
        guard let fold = view.reservedRegions(kind: .division, options: [.includeInactive]).first else {
            IosFold.shared.clear()
            return
        }
        // The frame includes Apple's margins (20pt each side of a zero-height crease on Duo),
        // which is the band interactive content should stay out of, so it is passed as is.
        IosFold.shared.report(
            active: fold.isActive,
            topPt: fold.frame.minY,
            bottomPt: fold.frame.maxY,
            widthPt: fold.frame.width,
            viewHeightPt: view.bounds.height,
            scale: Double(view.window?.screen.scale ?? UIScreen.main.scale)
        )
    }
    #endif
}
