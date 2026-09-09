import SwiftUI
import WidgetKit

struct DjMediumView: View {
    let entry: DjEntry

    private var stale: Bool { entry.wire?.isStale(at: entry.date) ?? true }
    private var playing: Bool { !stale && (entry.wire?.isPlaying ?? false) }

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            artwork
            LinearGradient(
                stops: [
                    .init(color: .black.opacity(0.82), location: 0),
                    .init(color: .black.opacity(0.15), location: 0.38),
                    .init(color: .clear, location: 0.67),
                ],
                startPoint: .bottom, endPoint: .top
            )
            timerChip
            bottomBar
        }
        // Stale dims the WHOLE tile, not just the artwork — otherwise the vibe
        // name, album title and pills stay full-brightness over dimmed art and
        // the tile reads as partially loaded rather than as stale.
        .saturation(stale ? 0.15 : 1.0)
        .opacity(stale ? 0.55 : 1.0)
        .containerBackground(Color(white: 0.08), for: .widget)
    }

    @ViewBuilder private var artwork: some View {
        if let image = entry.wire?.artworkImage() {
            // .clipped() alone only clips rendering; it doesn't change the size
            // the view reports to its parent, so an aspect-fill Image can still
            // size the ZStack to its inflated frame and shift bottomBar. Pin the
            // reported size to Color.clear's (which fills the proposed space)
            // and clip the overlay content to that.
            Color.clear.overlay {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
            }
            .clipped()
        } else {
            Color(white: 0.16)
        }
    }

    @ViewBuilder private var timerChip: some View {
        if let wire = entry.wire, wire.timerActive, !stale {
            VStack {
                HStack {
                    Spacer()
                    HStack(spacing: 6) {
                        if wire.timerRunning {
                            Text(timerInterval: entry.date...wire.countdownDeadline(notBefore: entry.date), countsDown: true)
                                .font(.system(size: 11.5, weight: .semibold).monospacedDigit())
                        } else {
                            // Paused: a live interval has no pause semantics without
                            // pauseTime:, so render the frozen remaining time instead.
                            Text(wire.formattedRemaining)
                                .font(.system(size: 11.5, weight: .semibold).monospacedDigit())
                        }
                        if #available(iOS 17.0, *) {
                            Button(intent: DjTransportIntent(action: "timerStop")) {
                                Image(systemName: "xmark").font(.system(size: 9))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .foregroundStyle(.white)
                    .padding(.horizontal, 8).padding(.vertical, 4)
                    .background(.black.opacity(0.42), in: Capsule())
                }
                Spacer()
            }
            .padding(13)
        }
    }

    private var bottomBar: some View {
        HStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.wire?.currentVibe ?? "Orphic DJ")
                    .font(.system(size: 20, weight: .bold))
                    .lineLimit(1)
                Text(entry.wire?.albumTitle ?? "")
                    .font(.system(size: 12.5, weight: .medium))
                    .opacity(0.68)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            if #available(iOS 17.0, *) {
                HStack(spacing: 9) {
                    pill("backward.fill", "skipPrev", accent: false)
                    pill(playing ? "pause.fill" : "play.fill", playing ? "pause" : "play", accent: true)
                    pill("forward.fill", "skipNext", accent: false)
                }
            }
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 16)
        .padding(.bottom, 14)
    }

    @available(iOS 17.0, *)
    private func pill(_ symbol: String, _ action: String, accent: Bool) -> some View {
        Button(intent: DjTransportIntent(action: action)) {
            Image(systemName: symbol)
                .font(.system(size: accent ? 14 : 12))
                .frame(width: accent ? 40 : 34, height: accent ? 40 : 34)
                .background(accent ? Color(red: 0.91, green: 0.45, blue: 0.23)
                                   : Color.white.opacity(0.14),
                            in: Circle())
        }
        .buttonStyle(.plain)
    }
}
