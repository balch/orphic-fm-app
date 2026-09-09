import Foundation
import UIKit

/// Mirror of Kotlin's DjWidgetWireSnapshot. Property names must match the
/// Kotlin field names exactly — the golden fixture test guards the drift.
/// Named `...Payload` (not `DjWidgetWire`) so it can't shadow the Kotlin
/// `DjWidgetWire` object that ships in the same `DjApp` target.
struct DjWidgetWirePayload: Codable, Equatable {
    let currentVibe: String
    let albumTitle: String
    let isPlaying: Bool
    let timerRunning: Bool
    let timerRemainingSeconds: Int64
    let timerStatus: String
    let artworkFile: String?
    let writtenAtEpochMs: Int64

    /// True while a timer is running or paused — Android's same tri-state rule
    /// (`DjWidget.kt`). A paused timer must keep its chip, not read as cancelled.
    var timerActive: Bool { timerRunning || timerStatus == "RUNNING" || timerStatus == "PAUSED" }

    static let appGroup = "group.org.balch.djapp"
    static let snapshotFile = "snapshot.json"

    static func containerURL() -> URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)
    }

    /// Nil on any failure — a missing or malformed file renders the idle widget,
    /// never a crash. A crashing widget is killed and shows a blank tile forever.
    static func load() -> DjWidgetWirePayload? {
        guard let dir = containerURL() else { return nil }
        let url = dir.appendingPathComponent(snapshotFile)
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(DjWidgetWirePayload.self, from: data)
    }

    func artworkImage() -> UIImage? {
        guard let file = artworkFile, let dir = Self.containerURL() else { return nil }
        return UIImage(contentsOfFile: dir.appendingPathComponent(file).path)
    }

    /// Anchored to the entry's date, not the wall clock: WidgetKit archives a
    /// rendered entry well before it displays it. A terminated app can't
    /// correct a stale tile, so anything this old stops claiming to play.
    func isStale(at date: Date) -> Bool {
        date.timeIntervalSince1970 - Double(writtenAtEpochMs) / 1000.0 >= Self.staleAfter
    }

    static let staleAfter: TimeInterval = 30 * 60

    /// Absolute countdown deadline (`writtenAtEpochMs` + `timerRemainingSeconds`),
    /// clamped to never precede `now`. Host-ticked to this absolute point rather
    /// than `Date()` + remaining: the app may be frozen or dead, and re-evaluating
    /// `Date()` on every re-render would restart the countdown. The clamp matters
    /// on its own: `ClosedRange` traps when lower > upper, and the deadline is
    /// already in the past once a timer has expired by the time an entry renders.
    func countdownDeadline(notBefore now: Date) -> Date {
        let raw = Date(timeIntervalSince1970: Double(writtenAtEpochMs) / 1000.0)
            .addingTimeInterval(TimeInterval(timerRemainingSeconds))
        return max(now, raw)
    }

    /// Static mm:ss for the paused state. `Text(timerInterval:)` has no pause
    /// semantics without `pauseTime:`, so a paused timer renders this instead of
    /// ticking the live interval.
    var formattedRemaining: String {
        let clamped = max(0, timerRemainingSeconds)
        return String(format: "%02d:%02d", clamped / 60, clamped % 60)
    }
}
