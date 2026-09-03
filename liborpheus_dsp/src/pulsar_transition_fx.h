#pragma once

// Generic section transition effects.
//
// A vibe authors effects to fire around a section flip at three granularities, all
// landing in the same bank: per OUTGOING SECTION EDGE (`edge_idx` = that edge), per
// SECTION EXIT (`edge_idx` = kTransFxEdgeAny, whatever edge is taken), and per SECTION
// ENTRY (`edge_idx` = kTransFxEdgeEntry, whatever edge arrived). Rows cross the wire in
// `pulsar_trans_fx_data` (7 floats each) and unpack into TransFxRow. When a section
// becomes active, the edge it plans to leave by is staged: every matching row becomes a
// PendingTransFx counting bar boundaries down to its fire point. Entry rows are keyed to
// arrivals, never to departures, so they are staged at the flip instead (see
// stage_entry_transition_fx) and skipped entirely by the outgoing-edge staging below.
//
// The three are a UNION, not an override — the departing section's exit rows, the taken
// edge's rows and the arriving section's entry rows all fire at one flip. Together they
// still owe the kMaxPendingFx budget below; the Kotlin authoring layer rejects any
// combination whose total could exceed it, because staging here just stops at the cap.
//
// A row's three payload slots are per-type, spelled out on TransFxType below. Only the
// strike uses all three: p2 is its SUB-BAR delay in milliseconds, applied on top of the
// bar-quantised offset_bars, which is what lets a vibe author two strikes as a sequence
// instead of a collision. Mirrored as StrikeEffect.delayMs on the Kotlin side.
//
// offset_bars is measured from the flip: 0 fires WITH the flip, -1 one bar earlier,
// +1 one bar INTO the section the flip enters. Positives cap at +1 (the incoming
// section owns everything past that) and an over-long negative clamps to the owning
// section's start, so an effect spans at most its own section plus the first bar of
// the next. Offsets are counted in whole bars — a fractional value never reaches a
// boundary of its own, so a fractional negative falls through to the flip and a
// fractional positive resolves to the one boundary after it. On an ENTRY row the flip
// IS the section's first downbeat, so 0 and +1 read the same way — but a negative has
// no departing section to reach back into and collapses to the flip.
//
// Engine-free by design: arming the master effects needs OrpheusEngine, so that lives
// in orpheus_unit_pulsar.cpp. Everything here is pure row bookkeeping.

static constexpr int kMaxTransFxRows = 24;
static constexpr int kTransFxRowFields = 7;
// Simultaneous effects per flip, section-level and edge-level together. Four is already
// a stacked exit; more would just be mush, and the fixed bound keeps PulsarState
// allocation-free. Mirrored as TransitionEffect.MAX_PER_FLIP on the Kotlin side.
static constexpr int kMaxPendingFx = 4;

// Wire arity contract with the Kotlin marshaller (trans_fx_data_0 .. _167).
static constexpr int kTransFxBankSize = kMaxTransFxRows * kTransFxRowFields;
static_assert(kTransFxRowFields == 7,
              "row layout: section_idx, edge_idx, type_id, offset_bars, p0, p1, p2");
static_assert(kTransFxBankSize == 168, "trans_fx bank is 24 rows x 7 fields");

// Row type ids. 0 is an unauthored row: the bank is fixed-size, so most of it is
// padding that must never stage.
enum TransFxType : int {
    TRANS_FX_NONE      = 0,
    TRANS_FX_SCRATCH   = 1,   // p0 = milliseconds
    TRANS_FX_TAPE_STOP = 2,   // p0 = milliseconds
    TRANS_FX_STRIKE    = 3,   // p0 = intensity, p1 = distance, p2 = sub-bar delay, milliseconds
};

// Negative `edge_idx` sentinels; a non-negative value names one outgoing edge slot.
// Mirrored as TransitionFxWire.EDGE_ANY / EDGE_ENTRY on the Kotlin side.
static constexpr int kTransFxEdgeAny = -1;    // Section.exitEffects: any outgoing edge.
static constexpr int kTransFxEdgeEntry = -2;  // Section.entryEffects: any arrival into it.

struct TransFxRow {
    int section = -1;
    int edge = kTransFxEdgeAny;
    int type = TRANS_FX_NONE;
    float offset_bars = 0.0f;
    float p0 = 0.0f, p1 = 0.0f, p2 = 0.0f;
};

struct PendingTransFx {
    int type = TRANS_FX_NONE;
    float p0 = 0.0f, p1 = 0.0f, p2 = 0.0f;
    float bars_until_fire = 0.0f;
    bool armed = false;
    // Positive offset: this row belongs to the section the flip ENTERS, so the host
    // carries it past the flip instead of firing it there.
    bool after_flip = false;
};

// Unpack one wire row (7 consecutive floats) into its struct.
inline TransFxRow trans_fx_row_from_wire(const float* fields) {
    TransFxRow row;
    row.section     = static_cast<int>(fields[0]);
    row.edge        = static_cast<int>(fields[1]);
    row.type        = static_cast<int>(fields[2]);
    row.offset_bars = fields[3];
    row.p0          = fields[4];
    row.p1          = fields[5];
    row.p2          = fields[6];
    return row;
}

// Stage every authored row on `edge` out of `section`. `bars_remaining` is the
// section's full drawn length at entry, so offset 0 lands exactly on the flip.
// Returns the number of pending slots written; the rest are reset.
inline int stage_transition_fx(const TransFxRow* rows, int row_count,
                               int section, int edge, int bars_remaining,
                               PendingTransFx* out, int max_out) {
    const float span = static_cast<float>(bars_remaining);
    int n = 0;
    for (int i = 0; i < row_count && n < max_out; i++) {
        const TransFxRow& row = rows[i];
        if (row.type == TRANS_FX_NONE) continue;
        if (row.section != section) continue;
        // kTransFxEdgeAny matches every outgoing edge; every OTHER negative sentinel
        // (kTransFxEdgeEntry) is an arrival's row and must not leave with the section.
        if (row.edge != kTransFxEdgeAny && row.edge != edge) continue;
        float bars = span + row.offset_bars;
        if (bars < 0.0f) bars = 0.0f;
        if (bars > span + 1.0f) bars = span + 1.0f;
        out[n].type = row.type;
        out[n].p0 = row.p0;
        out[n].p1 = row.p1;
        out[n].p2 = row.p2;
        out[n].bars_until_fire = bars;
        out[n].armed = true;
        out[n].after_flip = bars > span;
        n++;
    }
    for (int i = n; i < max_out; i++) out[i] = PendingTransFx{};
    return n;
}

// Stage every ENTRY row authored on `section`, for the flip that has just entered it.
// Offsets are measured from the section's own first downbeat, so unlike the outgoing
// staging above there is no span to count down from: <= 0 fires here, positive carries
// to the next boundary (the +1 cap is the whole of what a positive offset can mean).
// Writes `out[0..n)` and leaves the rest alone — the caller appends into a shared list.
inline int stage_entry_transition_fx(const TransFxRow* rows, int row_count, int section,
                                     PendingTransFx* out, int max_out) {
    int n = 0;
    for (int i = 0; i < row_count && n < max_out; i++) {
        const TransFxRow& row = rows[i];
        if (row.type == TRANS_FX_NONE) continue;
        if (row.section != section || row.edge != kTransFxEdgeEntry) continue;
        const bool after = row.offset_bars > 0.0f;
        out[n].type = row.type;
        out[n].p0 = row.p0;
        out[n].p1 = row.p1;
        out[n].p2 = row.p2;
        out[n].bars_until_fire = after ? 1.0f : 0.0f;
        out[n].armed = true;
        out[n].after_flip = after;
        n++;
    }
    return n;
}

// One elapsed bar boundary. Only called on bars that are NOT the flip: the flip
// fires whatever is still armed, so the countdown never has to represent it.
inline void tick_transition_fx_bar(PendingTransFx* pending, int count) {
    for (int i = 0; i < count; i++) {
        if (pending[i].armed) pending[i].bars_until_fire -= 1.0f;
    }
}
