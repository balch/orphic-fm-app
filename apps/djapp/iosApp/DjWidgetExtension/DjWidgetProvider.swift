import WidgetKit
import SwiftUI

struct DjEntry: TimelineEntry {
    let date: Date
    let wire: DjWidgetWirePayload?
}

struct DjProvider: TimelineProvider {
    func placeholder(in context: Context) -> DjEntry {
        DjEntry(date: Date(), wire: nil)
    }

    func getSnapshot(in context: Context, completion: @escaping (DjEntry) -> Void) {
        completion(DjEntry(date: Date(), wire: DjWidgetWirePayload.load()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<DjEntry>) -> Void) {
        let now = Date()
        let wire = DjWidgetWirePayload.load()
        let entry = DjEntry(date: now, wire: wire)

        // Re-enter exactly at the staleness boundary so it's evaluated in real
        // time, not guessed at write time. Gated on not-yet-stale: scheduling
        // this unconditionally would burn ~48 refreshes/day against WidgetKit's
        // shared ~40-70/day budget, versus about one per app session gated.
        if let wire, !wire.isStale(at: now) {
            let staleAt = Date(timeIntervalSince1970: Double(wire.writtenAtEpochMs) / 1000.0)
                .addingTimeInterval(DjWidgetWirePayload.staleAfter)
            completion(Timeline(entries: [entry], policy: .after(staleAt)))
        } else {
            completion(Timeline(entries: [entry], policy: .never))
        }
    }
}

struct DjWidgetEntryView: View {
    @Environment(\.widgetFamily) private var family
    let entry: DjEntry

    var body: some View {
        switch family {
        case .accessoryRectangular: DjAccessoryView(entry: entry)
        default: DjMediumView(entry: entry)
        }
    }
}

struct DjWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "DjWidget", provider: DjProvider()) { entry in
            DjWidgetEntryView(entry: entry)
        }
        .configurationDisplayName("Orphic DJ")
        .description("Current vibe, transport, and sleep timer.")
        .supportedFamilies([.systemMedium, .accessoryRectangular])
    }
}
