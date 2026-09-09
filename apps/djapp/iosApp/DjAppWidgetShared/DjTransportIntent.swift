import AppIntents
#if canImport(DjAppShared)
import DjAppShared
#endif

@available(iOS 17.0, *)
struct DjTransportIntent: AudioPlaybackIntent {
    static var title: LocalizedStringResource = "Orphic DJ Transport"
    // Internal wiring, not a user-facing action: a free-text field in Shortcuts/Siri
    // would let an unknown string fall through perform()'s `when` to a bare reload.
    static var isDiscoverable: Bool { false }

    @Parameter(title: "Action")
    var action: String

    init() { self.action = "play" }
    init(action: String) { self.action = action }

    func perform() async throws -> some IntentResult {
        // AudioPlaybackIntent executes in the app process, so the Kotlin graph
        // is reachable here. In the extension this type is only a declaration.
        #if canImport(DjAppShared)
        try await DjAppHost.shared.perform(action: action)
        #endif
        return .result()
    }
}
