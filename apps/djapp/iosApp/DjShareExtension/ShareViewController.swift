import Social
import UniformTypeIdentifiers
import UIKit

/// Saves shared text (or a page URL) to the app group for Orphic DJ AI to pick up on its next
/// activation. Links no Kotlin: the file is the whole contract between the two processes.
class ShareViewController: SLComposeServiceViewController {
    private static let appGroup = "group.org.balch.djapp.ai"
    private static let fileName = "shared-tab.txt"

    override func isContentValid() -> Bool { true }

    // A text share pre-fills the compose box, which hides any placeholder, so the hint is a sheet row.
    override func configurationItems() -> [Any]! {
        guard let hint = SLComposeSheetConfigurationItem() else { return [] }
        hint.title = "Open Orphic DJ AI to use it"
        return [hint]
    }

    override func didSelectPost() {
        // The compose box is pre-filled from the host's attributedContentText (often the shared text
        // itself, or a page title), so only text that differs from a title counts as typed by the user.
        let prefilled = Set((extensionContext?.inputItems as? [NSExtensionItem] ?? [])
            .compactMap { $0.attributedContentText?.string.trimmingCharacters(in: .whitespacesAndNewlines) })
        let content = contentText?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        Task { @MainActor in
            let shared = await loadSharedText() ?? ""
            let sharedTrimmed = shared.trimmingCharacters(in: .whitespacesAndNewlines)
            let text: String
            if !sharedTrimmed.isEmpty && prefilled.contains(sharedTrimmed) {
                // The box holds the tab itself, so an edit there replaces it rather than doubling it.
                text = content.isEmpty ? shared : content
            } else {
                let typed = prefilled.contains(content) || content == sharedTrimmed ? "" : content
                text = [typed, shared].filter { !$0.isEmpty }.joined(separator: "\n\n")
            }
            if !text.isEmpty { Self.write(text) }
            extensionContext?.completeRequest(returningItems: [], completionHandler: nil)
        }
    }

    private func loadSharedText() async -> String? {
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? [])
            .flatMap { $0.attachments ?? [] }
        if let provider = providers.first(where: { $0.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) }),
           let text = await load(provider, UTType.plainText.identifier) {
            return text
        }
        if let provider = providers.first(where: { $0.hasItemConformingToTypeIdentifier(UTType.url.identifier) }),
           let text = await load(provider, UTType.url.identifier) {
            return text
        }
        return nil
    }

    private func load(_ provider: NSItemProvider, _ type: String) async -> String? {
        guard let item = try? await provider.loadItem(forTypeIdentifier: type) else { return nil }
        switch item {
        case let text as String: return text
        case let data as Data: return String(data: data, encoding: .utf8)
        case let url as URL where url.isFileURL && type == UTType.plainText.identifier:
            return try? String(contentsOf: url, encoding: .utf8)
        case let url as URL: return url.absoluteString
        default: return nil
        }
    }

    private static func write(_ text: String) {
        guard let dir = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)
        else { return }
        try? Data(text.utf8).write(to: dir.appendingPathComponent(fileName), options: .atomic)
    }
}
