#pragma once
#include "orpheus_engine.h"

// Pure cursor logic for notated-score playback. Decides WHICH events are due; the caller
// does the firing. Keeping the decision engine-free is what lets the suite drive it with
// no audio and no PulsarState, which is allocated lazily and segfaults if touched early.

struct ScoreTrackCursor {
    int  index = 0;     // next event to consider
    bool held  = false; // parked on a hold event, awaiting release
};

inline void score_cursor_reset(ScoreTrackCursor& cursor) {
    cursor.index = 0;
    cursor.held = false;
}

inline void score_release_hold(ScoreTrackCursor& cursor) {
    cursor.held = false;
}

// Collects every event at or before now_tick into out, advancing the cursor past them.
// A block boundary can straddle many events at a fast tempo, so the whole span fires
// rather than only the last — skipping them would silently thin the music.
//
// Stopping at out_cap leaves the cursor exactly where it stopped, so the remainder
// arrives on the next call instead of vanishing.
//
// An event with flags bit 0 set FIRES and then parks the cursor until score_release_hold.
// The gated openings authored since phase A do set it -- see the Fifth's motif.
//
// free_run ignores that park: the written ticks already carry the rhythm, so a piece with
// nobody conducting it plays itself rather than waiting for a stroke that never comes.
// Checked here rather than at the call site so a chord whose first note carries the hold
// still collects whole, instead of flamming its remaining notes into the next block.
inline int score_collect_due(const OrpheusEngine::ScoreEvent* events,
                             int count,
                             ScoreTrackCursor& cursor,
                             int now_tick,
                             OrpheusEngine::ScoreEvent* out,
                             int out_cap,
                             bool free_run = false) {
    if (events == nullptr || count <= 0 || out_cap <= 0) return 0;
    int n = 0;
    while (!cursor.held && cursor.index < count && n < out_cap) {
        const OrpheusEngine::ScoreEvent& e = events[cursor.index];
        if (e.tick > now_tick) break;
        out[n++] = e;
        cursor.index++;
        if ((e.flags & 1) && !free_run) cursor.held = true;
    }
    return n;
}
