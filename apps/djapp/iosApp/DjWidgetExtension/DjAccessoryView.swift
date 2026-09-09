import SwiftUI
import WidgetKit

/// Lock Screen widgets render vibrant: a single translucent tint, no artwork
/// and no accent colour, since accessory families flatten colour anyway. This
/// accessory is deliberately non-interactive: no buttons, tapping opens the
/// app. It is also deliberately timer-first rather than transport: during
/// playback iOS already shows its own Now Playing card with full colour
/// controls, and a vibrant duplicate here would only lose next to it. The
/// sleep timer is the one thing that card cannot show.
struct DjAccessoryView: View {
    let entry: DjEntry

    private var stale: Bool { entry.wire?.isStale(at: entry.date) ?? true }

    var body: some View {
        let wire = entry.wire

        VStack(alignment: .leading, spacing: 1) {
            if let wire, wire.timerActive, !stale {
                Text(wire.currentVibe)
                    .font(.system(size: 11.5))
                    .opacity(0.7)
                    .lineLimit(1)
                if wire.timerRunning {
                    Text(timerInterval: entry.date...wire.countdownDeadline(notBefore: entry.date), countsDown: true)
                        .font(.system(size: 28, weight: .bold).monospacedDigit())
                        .lineLimit(1)
                } else {
                    // Paused: a live interval has no pause semantics without
                    // pauseTime:, so render the frozen remaining time instead.
                    Text(wire.formattedRemaining)
                        .font(.system(size: 28, weight: .bold).monospacedDigit())
                        .lineLimit(1)
                }
            } else {
                Text(wire?.currentVibe ?? "Orphic DJ")
                    .font(.system(size: 14.5, weight: .bold))
                    .lineLimit(1)
                Text(wire?.albumTitle ?? "")
                    .font(.system(size: 11.5))
                    .opacity(0.6)
                    .lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .containerBackground(.clear, for: .widget)
    }
}
