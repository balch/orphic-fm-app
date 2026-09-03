// Generic per-edge section transition effects: bank -> staging -> dispatch.
// Drives the real pulsar unit so the tests cover the flip handler, not just the
// scan helpers. Master effects are NOT processed by unit_process_pulsar (the
// master chain lives downstream in orpheus_unit_basic.cpp), so is_active()
// latches an arm until a test drains it deliberately — which is what makes the
// arbitration test below able to tell a re-arm from the original arm.
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/pulsar_transition_fx.h"
#include "../src/orpheus_graph.h"
#include <cstdio>
#include <cstring>

static GraphUnit make_trans_fx_unit() {
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    return unit;
}

// Deterministic 2-section ping-pong on top of the JAM fixture: both sections are
// exactly 2 bars (bars_min == bars_max draws no RNG), and each has one weighted
// edge (always taken) plus one zero-weight edge (select_next_section skips zero
// weights, so it is never taken).
//
// `weighted_edge_s0` moves which of section 0's two outgoing edge SLOTS carries the
// weight. Both variants take the same s0 -> s1 flip; only the edge index changes, which
// is what lets a wildcard row be told apart from an edge-0-specific one.
static void setup_deterministic_edges(OrpheusEngine* engine, int weighted_edge_s0 = 0) {
    // Without an intro the opening section is drawn at random, which decides whether
    // the first flip leaves section 0 at all. Pin it so every test below starts on the
    // edge it authored a row for.
    engine->pulsar_arrangement_intro_index.store(0, std::memory_order_relaxed);
    constexpr int kSectionStride = kSectionDataFields;
    for (int s = 0; s < 2; s++) {
        const int b = s * kSectionStride;
        engine->pulsar_section_data[b + 0].store(2.0f, std::memory_order_relaxed);  // bars_min
        engine->pulsar_section_data[b + 1].store(2.0f, std::memory_order_relaxed);  // bars_max
        engine->pulsar_section_data[b + 2].store(1.0f, std::memory_order_relaxed);  // bar_step
        engine->pulsar_section_data[b + 4].store(2.0f, std::memory_order_relaxed);  // transition_count
        engine->pulsar_section_data[b + 15].store(0.0f, std::memory_order_relaxed); // slot 15 retired, keep zeroed
    }
    // s0 variant 0: edge 0 -> s1 (weight 1), edge 1 -> s0 (weight 0, never chosen).
    // s0 variant 1: the same pair swapped, so the live edge out of s0 is index 1. The
    //   dead edge must NOT also target s1 — find_planned_edge_index matches on target
    //   index and would hand back edge 0 either way.
    // s1: edge 0 -> s0 (weight 1), edge 1 -> s1 (weight 0, never chosen).
    const float s0_edges_a[6] = {1.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f};
    const float s0_edges_b[6] = {0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f};
    const float* s0_edges = weighted_edge_s0 == 0 ? s0_edges_a : s0_edges_b;
    const float s1_edges[6] = {0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 0.0f};
    for (int i = 0; i < 6; i++) {
        engine->pulsar_section_transitions[i].store(s0_edges[i], std::memory_order_relaxed);
        engine->pulsar_section_transitions[kMaxSectionTransitions * 3 + i].store(
            s1_edges[i], std::memory_order_relaxed);
    }
    engine->pulsar_arrangement_generation.store(2, std::memory_order_release);
}

static void clear_trans_fx_bank(OrpheusEngine* engine) {
    for (int i = 0; i < kTransFxBankSize; i++)
        engine->pulsar_trans_fx_data[i].store(0.0f, std::memory_order_relaxed);
}

static void write_trans_fx_row(OrpheusEngine* engine, int row, int section, int edge,
                               int type, float offset_bars,
                               float p0, float p1 = 0.0f, float p2 = 0.0f) {
    const int b = row * kTransFxRowFields;
    engine->pulsar_trans_fx_data[b + 0].store(static_cast<float>(section), std::memory_order_relaxed);
    engine->pulsar_trans_fx_data[b + 1].store(static_cast<float>(edge), std::memory_order_relaxed);
    engine->pulsar_trans_fx_data[b + 2].store(static_cast<float>(type), std::memory_order_relaxed);
    engine->pulsar_trans_fx_data[b + 3].store(offset_bars, std::memory_order_relaxed);
    engine->pulsar_trans_fx_data[b + 4].store(p0, std::memory_order_relaxed);
    engine->pulsar_trans_fx_data[b + 5].store(p1, std::memory_order_relaxed);
    engine->pulsar_trans_fx_data[b + 6].store(p2, std::memory_order_relaxed);
}

// Fixture + deterministic arrangement + a pinned RNG, ready to render.
static OrpheusEngine* make_trans_fx_engine(int weighted_edge_s0 = 0) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    setup_jam_arrangement(engine);
    setup_deterministic_edges(engine, weighted_edge_s0);
    clear_trans_fx_bank(engine);
    engine->clock_bpm.store(180.0f, std::memory_order_relaxed);
    return engine;
}

// A 2-bar section at 180 BPM is 128000 samples; 600 blocks of 512 covers ~4 bars.
static constexpr int kMaxBlocks = 600;

// ── (a) offset 0 on the taken edge arms the master scratch AT the flip ────────
static bool test_scratch_row_arms_at_flip() {
    printf("\n=== Test: scratch row (offset 0, taken edge) arms master scratch at the section flip ===\n");
    OrpheusEngine* engine = make_trans_fx_engine();
    write_trans_fx_row(engine, 0, /*section*/0, /*edge*/0, TRANS_FX_SCRATCH, /*offset*/0.0f, /*ms*/400.0f);
    trigger_vibe_load(engine);

    GraphUnit unit = make_trans_fx_unit();
    bool armed_before_flip = false;
    bool armed_at_flip = false;
    bool flipped = false;
    for (int i = 0; i < kMaxBlocks && !flipped; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        flipped = engine->pulsar_state->section_state.current_section != 0;
        if (flipped) armed_at_flip = engine->master_scratch_l.is_active();
        else if (engine->master_scratch_l.is_active()) armed_before_flip = true;
    }

    bool ok = true;
    if (!flipped) { printf("  FAIL: section never flipped within %d blocks\n", kMaxBlocks); ok = false; }
    if (armed_before_flip) { printf("  FAIL: scratch armed BEFORE the flip (offset 0 must land on it)\n"); ok = false; }
    if (!armed_at_flip) { printf("  FAIL: master_scratch_l not active at the flip block\n"); ok = false; }
    if (!engine->master_scratch_r.is_active()) { printf("  FAIL: master_scratch_r not armed (stereo pair)\n"); ok = false; }
    if (ok) printf("  PASS: scratch armed on both channels exactly at the flip\n");

    orpheus_engine_destroy(engine);
    return ok;
}

// ── (b) a row on an edge that is never taken never fires ─────────────────────
static bool test_row_on_untaken_edge_never_fires() {
    printf("\n=== Test: row on the zero-weight (never taken) edge never fires ===\n");
    OrpheusEngine* engine = make_trans_fx_engine();
    // Edge 1 out of section 0 carries weight 0, so select_next_section skips it.
    // Tape stop rather than scratch: an armed scratch freezes the pulsar clock,
    // which would stop the arrangement before it could prove anything.
    write_trans_fx_row(engine, 0, /*section*/0, /*edge*/1, TRANS_FX_TAPE_STOP, /*offset*/0.0f, /*ms*/400.0f);
    trigger_vibe_load(engine);

    GraphUnit unit = make_trans_fx_unit();
    int flips = 0;
    int prev_section = 0;
    bool ever_armed = false;
    for (int i = 0; i < kMaxBlocks; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        int sec = engine->pulsar_state->section_state.current_section;
        if (sec != prev_section) { flips++; prev_section = sec; }
        if (engine->master_tape_stop_l.is_active()) ever_armed = true;
    }

    bool ok = true;
    if (flips < 2) { printf("  FAIL: only %d flips in %d blocks — test proves nothing\n", flips, kMaxBlocks); ok = false; }
    if (ever_armed) { printf("  FAIL: tape stop armed from an edge that was never taken\n"); ok = false; }
    if (ok) printf("  PASS: %d flips, untaken edge stayed silent\n", flips);

    orpheus_engine_destroy(engine);
    return ok;
}

// ── (c) transitions win: fire arms unconditionally, even mid-anomaly ─────────
static bool test_transition_rearms_over_running_effect() {
    printf("\n=== Test: a transition effect re-arms over an already-running one (no is_active guard) ===\n");
    OrpheusEngine* engine = make_trans_fx_engine();
    // 40ms transition tape stop; the pre-arm below is 30s. If the fire path
    // guarded on is_active() the long window would survive the flip.
    write_trans_fx_row(engine, 0, /*section*/0, /*edge*/0, TRANS_FX_TAPE_STOP, /*offset*/0.0f, /*ms*/40.0f);
    trigger_vibe_load(engine);

    const int kLongArm = 30 * 48000;
    engine->master_tape_stop_l.arm(kLongArm);
    engine->master_tape_stop_r.arm(kLongArm);

    // Drain the master effect the way the master chain does, so samples_left_
    // actually counts down and "still active" means something.
    GraphUnit unit = make_trans_fx_unit();
    float drain[512];
    bool flipped = false;
    int blocks = 0;
    for (; blocks < kMaxBlocks && !flipped; blocks++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        std::memset(drain, 0, sizeof(drain));
        engine->master_tape_stop_l.process(drain, 512);
        flipped = engine->pulsar_state->section_state.current_section != 0;
    }
    bool active_at_flip = engine->master_tape_stop_l.is_active();

    // 40ms is 1920 samples: four more drained blocks retire it if — and only if —
    // the transition actually re-armed over the 30s window.
    for (int i = 0; i < 8; i++) {
        std::memset(drain, 0, sizeof(drain));
        engine->master_tape_stop_l.process(drain, 512);
    }
    bool still_active = engine->master_tape_stop_l.is_active();

    bool ok = true;
    if (!flipped) { printf("  FAIL: section never flipped within %d blocks\n", kMaxBlocks); ok = false; }
    if (!active_at_flip) { printf("  FAIL: tape stop not active at the flip\n"); ok = false; }
    if (still_active) {
        printf("  FAIL: effect still running after the short window — the transition did not re-arm\n");
        ok = false;
    }
    if (ok) printf("  PASS: the transition's 40ms arm replaced the 30s window in flight\n");

    orpheus_engine_destroy(engine);
    return ok;
}

// ── (d) type 2 arms the tape stop ───────────────────────────────────────────
static bool test_tape_row_arms_tape_stop() {
    printf("\n=== Test: tape-stop row (type 2) arms master_tape_stop at the flip ===\n");
    OrpheusEngine* engine = make_trans_fx_engine();
    write_trans_fx_row(engine, 0, /*section*/0, /*edge*/0, TRANS_FX_TAPE_STOP, /*offset*/0.0f, /*ms*/300.0f);
    trigger_vibe_load(engine);

    GraphUnit unit = make_trans_fx_unit();
    bool flipped = false;
    bool armed_before_flip = false;
    for (int i = 0; i < kMaxBlocks && !flipped; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        flipped = engine->pulsar_state->section_state.current_section != 0;
        if (!flipped && engine->master_tape_stop_l.is_active()) armed_before_flip = true;
    }

    bool ok = true;
    if (!flipped) { printf("  FAIL: section never flipped within %d blocks\n", kMaxBlocks); ok = false; }
    if (armed_before_flip) { printf("  FAIL: tape stop armed before the flip\n"); ok = false; }
    if (!engine->master_tape_stop_l.is_active() || !engine->master_tape_stop_r.is_active()) {
        printf("  FAIL: master_tape_stop not armed on both channels at the flip\n");
        ok = false;
    }
    // The scratch must stay untouched — types must not cross-fire.
    if (engine->master_scratch_l.is_active()) { printf("  FAIL: type 2 also armed the scratch\n"); ok = false; }
    if (ok) printf("  PASS: tape stop armed at the flip, scratch untouched\n");

    orpheus_engine_destroy(engine);
    return ok;
}

// ── (e) rows are re-read and re-staged on every vibe load ───────────────────
static bool test_rows_cleared_and_restaged_on_vibe_load() {
    printf("\n=== Test: trans-fx rows are re-unpacked and re-staged on vibe load ===\n");
    OrpheusEngine* engine = make_trans_fx_engine();
    write_trans_fx_row(engine, 0, /*section*/0, /*edge*/0, TRANS_FX_SCRATCH, /*offset*/0.0f, /*ms*/400.0f);
    trigger_vibe_load(engine);

    GraphUnit unit = make_trans_fx_unit();
    unit_process_pulsar(&unit, engine, 512, 48000.0f);
    const PulsarState* st = engine->pulsar_state;
    int loaded_rows = st->trans_fx_count;
    int staged = st->pending_fx_count;
    float bars_until = staged > 0 ? st->pending_fx[0].bars_until_fire : -1.0f;

    // Second vibe: the bank is empty, so nothing must survive from the first.
    clear_trans_fx_bank(engine);
    trigger_vibe_load(engine);
    unit_process_pulsar(&unit, engine, 512, 48000.0f);
    int rows_after = st->trans_fx_count;
    int staged_after = st->pending_fx_count;

    bool ok = true;
    if (loaded_rows != 1) { printf("  FAIL: trans_fx_count = %d (expected 1)\n", loaded_rows); ok = false; }
    if (staged != 1) { printf("  FAIL: pending_fx_count = %d (expected 1 staged at load)\n", staged); ok = false; }
    // Section 0 is 2 bars and the offset is 0, so the fire point is the flip.
    if (staged > 0 && bars_until != 2.0f) {
        printf("  FAIL: bars_until_fire = %.2f (expected 2 — the section's full length)\n", bars_until);
        ok = false;
    }
    if (rows_after != 0 || staged_after != 0) {
        printf("  FAIL: after an empty reload rows=%d staged=%d (expected 0/0)\n", rows_after, staged_after);
        ok = false;
    }
    if (ok) printf("  PASS: 1 row staged at 2 bars, both cleared by the empty reload\n");

    orpheus_engine_destroy(engine);
    return ok;
}

// ── (f) a negative offset fires a bar EARLY, before the flip ────────────────
static bool test_negative_offset_fires_before_flip() {
    printf("\n=== Test: offset_bars = -1 fires one bar boundary before the flip ===\n");
    OrpheusEngine* engine = make_trans_fx_engine();
    write_trans_fx_row(engine, 0, /*section*/0, /*edge*/0, TRANS_FX_TAPE_STOP, /*offset*/-1.0f, /*ms*/200.0f);
    trigger_vibe_load(engine);

    GraphUnit unit = make_trans_fx_unit();
    bool fired_early = false;
    int bars_left_at_fire = -1;
    bool flipped = false;
    for (int i = 0; i < kMaxBlocks && !flipped; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        flipped = engine->pulsar_state->section_state.current_section != 0;
        if (!flipped && !fired_early && engine->master_tape_stop_l.is_active()) {
            fired_early = true;
            bars_left_at_fire = engine->pulsar_state->section_state.bars_remaining;
        }
    }

    bool ok = true;
    if (!flipped) { printf("  FAIL: section never flipped within %d blocks\n", kMaxBlocks); ok = false; }
    if (!fired_early) { printf("  FAIL: never fired while section 0 was still current\n"); ok = false; }
    // The section is 2 bars, so the -1 offset lands on the bar with 1 left.
    if (fired_early && bars_left_at_fire != 1) {
        printf("  FAIL: fired with bars_remaining = %d (expected 1)\n", bars_left_at_fire);
        ok = false;
    }
    if (ok) printf("  PASS: fired one bar early, still inside the outgoing section\n");

    orpheus_engine_destroy(engine);
    return ok;
}

// ── (g) a positive offset fires one bar boundary INTO the new section ────────
static bool test_positive_offset_fires_after_flip() {
    printf("\n=== Test: offset_bars = +1 fires one bar boundary after the flip ===\n");
    OrpheusEngine* engine = make_trans_fx_engine();
    write_trans_fx_row(engine, 0, /*section*/0, /*edge*/0, TRANS_FX_TAPE_STOP, /*offset*/1.0f, /*ms*/200.0f);
    trigger_vibe_load(engine);

    GraphUnit unit = make_trans_fx_unit();
    int flip_block = -1, fire_block = -1;
    int section_at_fire = -1, bars_left_at_fire = -1;
    for (int i = 0; i < kMaxBlocks && fire_block < 0; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        const SectionState& ss = engine->pulsar_state->section_state;
        if (flip_block < 0 && ss.current_section != 0) flip_block = i;
        if (engine->master_tape_stop_l.is_active()) {
            fire_block = i;
            section_at_fire = ss.current_section;
            bars_left_at_fire = ss.bars_remaining;
        }
    }

    bool ok = true;
    if (flip_block < 0) { printf("  FAIL: section never flipped within %d blocks\n", kMaxBlocks); ok = false; }
    if (fire_block < 0) { printf("  FAIL: the row never fired\n"); ok = false; }
    if (fire_block >= 0 && flip_block >= 0 && fire_block <= flip_block) {
        printf("  FAIL: fired at block %d, at or before the flip (block %d)\n", fire_block, flip_block);
        ok = false;
    }
    // Section 1 is 2 bars, so one elapsed bar of it leaves 1 remaining.
    if (fire_block >= 0 && (section_at_fire != 1 || bars_left_at_fire != 1)) {
        printf("  FAIL: fired in section %d with bars_remaining %d (expected section 1, 1 bar left)\n",
               section_at_fire, bars_left_at_fire);
        ok = false;
    }
    if (ok) printf("  PASS: carried through the flip, fired one bar into section 1\n");

    orpheus_engine_destroy(engine);
    return ok;
}

// ── (h) type 3 strikes the pulsar's own storm voice, exactly at the flip ─────
static bool test_strike_row_fires_storm_voice_at_flip() {
    printf("\n=== Test: strike row (type 3) triggers the storm voice at the flip ===\n");
    OrpheusEngine* engine = make_trans_fx_engine();
    write_trans_fx_row(engine, 0, /*section*/0, /*edge*/0, TRANS_FX_STRIKE, /*offset*/0.0f,
                       /*intensity*/0.9f, /*distance*/0.2f);
    trigger_vibe_load(engine);

    GraphUnit unit = make_trans_fx_unit();
    bool active_before_flip = false;
    bool active_at_flip = false;
    bool flipped = false;
    for (int i = 0; i < kMaxBlocks && !flipped; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        const bool active = engine->pulsar_state->storm_voice.strike_active();
        flipped = engine->pulsar_state->section_state.current_section != 0;
        if (flipped) active_at_flip = active;
        else if (active) active_before_flip = true;
    }

    bool ok = true;
    if (!flipped) { printf("  FAIL: section never flipped within %d blocks\n", kMaxBlocks); ok = false; }
    if (active_before_flip) { printf("  FAIL: the storm struck BEFORE the flip\n"); ok = false; }
    if (!active_at_flip) { printf("  FAIL: storm_voice.strike_active() false at the flip block\n"); ok = false; }
    // Types must not cross-fire: a strike is not a master effect.
    if (engine->master_scratch_l.is_active() || engine->master_tape_stop_l.is_active()) {
        printf("  FAIL: type 3 also armed a master effect\n"); ok = false;
    }
    if (ok) printf("  PASS: storm struck at the flip, master chain untouched\n");

    orpheus_engine_destroy(engine);
    return ok;
}

// ── (h2) a strike row's p2 reaches the storm voice as a sub-bar delay ────────
static bool test_strike_row_p2_delays_the_storm_voice() {
    printf("\n=== Test: strike row p2 (ms) queues the storm voice instead of firing it ===\n");
    OrpheusEngine* engine = make_trans_fx_engine();
    // 500 ms at 180 BPM is ~1.5 beats: comfortably sub-bar, and long enough that the
    // wait spans many 512-sample blocks whatever the flip lands on.
    write_trans_fx_row(engine, 0, /*section*/0, /*edge*/0, TRANS_FX_STRIKE, /*offset*/0.0f,
                       /*intensity*/0.9f, /*distance*/0.2f, /*delay ms*/500.0f);
    trigger_vibe_load(engine);

    GraphUnit unit = make_trans_fx_unit();
    bool queued_at_flip = false, claps_at_flip = false;
    int flip_block = -1, drain_block = -1;
    for (int i = 0; i < kMaxBlocks; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        const PulsarState* ps = engine->pulsar_state;
        if (flip_block < 0) {
            if (ps->section_state.current_section == 0) continue;
            flip_block = i;
            queued_at_flip = ps->storm_voice.strike_queued();
            claps_at_flip = !queued_at_flip;
        } else if (drain_block < 0 && !ps->storm_voice.strike_queued()) {
            drain_block = i;
        }
    }

    bool ok = true;
    const float waited_ms = (drain_block >= 0 && flip_block >= 0)
                                ? (drain_block - flip_block) * 512.f * 1000.f / 48000.f : -1.f;
    if (flip_block < 0) { printf("  FAIL: section never flipped within %d blocks\n", kMaxBlocks); ok = false; }
    if (!queued_at_flip) { printf("  FAIL: the strike was not queued at the flip\n"); ok = false; }
    if (claps_at_flip) { printf("  FAIL: p2 was ignored and the strike fired at the flip\n"); ok = false; }
    // Block-quantised measurement (10.7 ms per block) against a 500 ms delay measured from
    // somewhere inside the flip block, so this is a "waited roughly that long" bound.
    if (drain_block < 0 || waited_ms < 450.f || waited_ms > 560.f) {
        printf("  FAIL: fired after %.0f ms, expected ~500\n", waited_ms); ok = false;
    }
    if (ok) printf("  PASS: queued at the flip, struck %.0f ms later\n", waited_ms);

    orpheus_engine_destroy(engine);
    return ok;
}

// ── (i) parity: a ScratchEffect(500) row reproduces the retired Section.exitScratchMs
//      behavior end to end -- armed AND clock-frozen, not just armed ────────────────
static bool test_scratch_row_freezes_clock_like_legacy_exit_scratch() {
    printf("\n=== Test: ScratchEffect(500) row freezes the pulsar clock at the flip (exitScratchMs parity) ===\n");
    OrpheusEngine* engine = make_trans_fx_engine();
    write_trans_fx_row(engine, 0, /*section*/0, /*edge*/0, TRANS_FX_SCRATCH, /*offset*/0.0f, /*ms*/500.0f);
    trigger_vibe_load(engine);

    GraphUnit unit = make_trans_fx_unit();
    bool flipped = false;
    for (int i = 0; i < kMaxBlocks && !flipped; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        flipped = engine->pulsar_state->section_state.current_section != 0;
    }

    bool ok = true;
    if (!flipped) { printf("  FAIL: section never flipped within %d blocks\n", kMaxBlocks); ok = false; }
    if (!engine->master_scratch_l.is_active() || !engine->master_scratch_r.is_active()) {
        printf("  FAIL: master scratch not armed on both channels at the flip\n"); ok = false;
    }

    // Same idiom as test_master_scratch_freezes_pulsar_clock (test_pulsar_sections.cpp):
    // snapshot every playhead, render more blocks, confirm none advanced. unit_process_pulsar
    // never drains the master effect (see file header), so is_active() latches for the window.
    PulsarState* ps = engine->pulsar_state;
    int ph_before[kNumPulsarTracks];
    for (int t = 0; t < kNumPulsarTracks; t++) ph_before[t] = ps->tracks[t].playhead;
    for (int i = 0; i < 30; i++) unit_process_pulsar(&unit, engine, 512, 48000.0f);
    bool frozen = true;
    for (int t = 0; t < kNumPulsarTracks; t++)
        if (ps->tracks[t].playhead != ph_before[t]) frozen = false;
    if (!frozen) { printf("  FAIL: a track playhead advanced while the scratch was active\n"); ok = false; }
    if (!engine->master_scratch_l.is_active()) {
        printf("  FAIL: scratch no longer active after the freeze window (test invariant broken)\n"); ok = false;
    }

    if (ok) {
        printf("  PASS: armed on both channels + clock frozen at the flip -- the generic path"
               " reproduces the retired exitScratchMs behavior\n");
    }

    orpheus_engine_destroy(engine);
    return ok;
}

// ── (j) edge = -1 is the section-level wildcard: fires on whichever edge is taken ──
// Section 0's live outgoing edge moves between slot 0 and slot 1 across the two runs,
// so the SAME wildcard row has to match a different edge index each time. The paired
// edge-0 row is what makes that discriminating: it must fall silent in the swapped run.
struct FlipProbe {
    bool flipped = false;
    bool armed_at_flip = false;
};

static FlipProbe run_trans_fx_to_first_flip(int row_edge, int weighted_edge_s0,
                                            int row_section = 0) {
    OrpheusEngine* engine = make_trans_fx_engine(weighted_edge_s0);
    write_trans_fx_row(engine, 0, row_section, row_edge, TRANS_FX_TAPE_STOP,
                       /*offset*/0.0f, /*ms*/200.0f);
    trigger_vibe_load(engine);

    GraphUnit unit = make_trans_fx_unit();
    FlipProbe probe;
    for (int i = 0; i < kMaxBlocks && !probe.flipped; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        probe.flipped = engine->pulsar_state->section_state.current_section != 0;
        if (probe.flipped) probe.armed_at_flip = engine->master_tape_stop_l.is_active();
    }
    orpheus_engine_destroy(engine);
    return probe;
}

static bool test_wildcard_edge_row_fires_on_any_taken_edge() {
    printf("\n=== Test: edge -1 wildcard row fires on whichever edge the section exits by ===\n");
    bool ok = true;

    // Staging helper first: the wildcard matches both edges, the specific row only its own.
    TransFxRow rows[2];
    rows[0] = TransFxRow{0, -1, TRANS_FX_TAPE_STOP, 0.0f, 200.0f, 0.0f, 0.0f};
    rows[1] = TransFxRow{0,  0, TRANS_FX_TAPE_STOP, 0.0f, 200.0f, 0.0f, 0.0f};
    PendingTransFx pending[kMaxPendingFx];
    int on_edge0 = stage_transition_fx(rows, 2, /*section*/0, /*edge*/0, 4, pending, kMaxPendingFx);
    int on_edge1 = stage_transition_fx(rows, 2, /*section*/0, /*edge*/1, 4, pending, kMaxPendingFx);
    if (on_edge0 != 2) { printf("  FAIL: edge 0 staged %d rows (expected wildcard + specific)\n", on_edge0); ok = false; }
    if (on_edge1 != 1) { printf("  FAIL: edge 1 staged %d rows (expected the wildcard only)\n", on_edge1); ok = false; }

    // Then end to end through the real flip handler, on both edge layouts.
    for (int variant = 0; variant < 2; variant++) {
        FlipProbe wild = run_trans_fx_to_first_flip(/*row_edge*/-1, variant);
        FlipProbe specific = run_trans_fx_to_first_flip(/*row_edge*/0, variant);
        if (!wild.flipped || !specific.flipped) {
            printf("  FAIL: variant %d never flipped within %d blocks\n", variant, kMaxBlocks);
            ok = false;
            continue;
        }
        if (!wild.armed_at_flip) {
            printf("  FAIL: wildcard row did not fire with the live edge at slot %d\n", variant);
            ok = false;
        }
        // The edge-0 row fires only when slot 0 is the edge actually taken.
        const bool specific_expected = (variant == 0);
        if (specific.armed_at_flip != specific_expected) {
            printf("  FAIL: edge-0 row fired=%d with the live edge at slot %d (expected %d) —"
                   " the fixture no longer distinguishes the two edges\n",
                   specific.armed_at_flip, variant, specific_expected);
            ok = false;
        }
    }

    if (ok) printf("  PASS: the wildcard fired on both edge layouts; the edge-0 row only on its own\n");
    return ok;
}

// ── (k) union: a wildcard row and an edge-specific row both fire at one flip ──
static bool test_wildcard_and_edge_row_both_fire_at_one_flip() {
    printf("\n=== Test: a wildcard row and an edge row on the taken edge both fire at the flip ===\n");
    OrpheusEngine* engine = make_trans_fx_engine();
    // Different targets so both firings are separately observable.
    write_trans_fx_row(engine, 0, /*section*/0, /*edge*/-1, TRANS_FX_TAPE_STOP, /*offset*/0.0f, /*ms*/200.0f);
    write_trans_fx_row(engine, 1, /*section*/0, /*edge*/0, TRANS_FX_STRIKE, /*offset*/0.0f,
                       /*intensity*/0.9f, /*distance*/0.2f);
    trigger_vibe_load(engine);

    GraphUnit unit = make_trans_fx_unit();
    int staged = -1;
    bool flipped = false;
    bool tape_at_flip = false, strike_at_flip = false;
    for (int i = 0; i < kMaxBlocks && !flipped; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        // pulsar_state is allocated on the first process call, so read the staging after it.
        if (i == 0) staged = engine->pulsar_state->pending_fx_count;
        const bool strike = engine->pulsar_state->storm_voice.strike_active();
        flipped = engine->pulsar_state->section_state.current_section != 0;
        if (flipped) {
            tape_at_flip = engine->master_tape_stop_l.is_active();
            strike_at_flip = strike;
        }
    }

    bool ok = true;
    if (staged != 2) { printf("  FAIL: staged %d rows at load (expected 2 — union, not override)\n", staged); ok = false; }
    if (!flipped) { printf("  FAIL: section never flipped within %d blocks\n", kMaxBlocks); ok = false; }
    if (!tape_at_flip) { printf("  FAIL: the wildcard tape stop did not fire\n"); ok = false; }
    if (!strike_at_flip) { printf("  FAIL: the edge-specific strike did not fire\n"); ok = false; }
    if (ok) printf("  PASS: both rows staged and both fired at the same flip\n");

    orpheus_engine_destroy(engine);
    return ok;
}

// ── (l) the wildcard only widens the EDGE match — the section still discriminates ──
static bool test_wildcard_row_still_discriminates_by_section() {
    printf("\n=== Test: a section-0 wildcard row stays silent when section 1 flips ===\n");
    OrpheusEngine* engine = make_trans_fx_engine();
    // 40ms so the window can be drained back out before section 1's own flip is watched.
    write_trans_fx_row(engine, 0, /*section*/0, /*edge*/-1, TRANS_FX_TAPE_STOP, /*offset*/0.0f, /*ms*/40.0f);
    trigger_vibe_load(engine);

    GraphUnit unit = make_trans_fx_unit();
    float drain[512];
    // Phase 1: to the first flip, draining the master effect the way the master chain
    // does so is_active() tracks the real window instead of latching (see file header).
    bool flipped = false, armed_at_first_flip = false;
    for (int i = 0; i < kMaxBlocks && !flipped; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        std::memset(drain, 0, sizeof(drain));
        engine->master_tape_stop_l.process(drain, 512);
        flipped = engine->pulsar_state->section_state.current_section != 0;
        if (flipped) armed_at_first_flip = engine->master_tape_stop_l.is_active();
    }
    const int staged_for_section_1 = engine->pulsar_state->pending_fx_count;

    // Phase 2: retire the 40ms (1920-sample) window.
    for (int d = 0; d < 8; d++) {
        std::memset(drain, 0, sizeof(drain));
        engine->master_tape_stop_l.process(drain, 512);
    }
    const bool retired = !engine->master_tape_stop_l.is_active();

    // Phase 3: through section 1 and its own flip back to section 0. Nothing may arm.
    bool armed_in_section_1 = false, second_flip = false;
    for (int i = 0; i < kMaxBlocks && !second_flip; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        std::memset(drain, 0, sizeof(drain));
        engine->master_tape_stop_l.process(drain, 512);
        if (engine->master_tape_stop_l.is_active()) armed_in_section_1 = true;
        second_flip = engine->pulsar_state->section_state.current_section == 0;
    }

    bool ok = true;
    if (!flipped) { printf("  FAIL: section 0 never flipped within %d blocks\n", kMaxBlocks); ok = false; }
    if (!armed_at_first_flip) { printf("  FAIL: the wildcard row did not fire on its OWN section's flip\n"); ok = false; }
    if (staged_for_section_1 != 0) {
        printf("  FAIL: %d row(s) staged for section 1 (expected 0 — the row names section 0)\n",
               staged_for_section_1);
        ok = false;
    }
    if (!retired) { printf("  FAIL: the 40ms window never drained (test invariant broken)\n"); ok = false; }
    if (!second_flip) { printf("  FAIL: section 1 never flipped within %d blocks\n", kMaxBlocks); ok = false; }
    if (armed_in_section_1) { printf("  FAIL: the section-0 row fired again on section 1's flip\n"); ok = false; }
    if (ok) printf("  PASS: fired on section 0's flip only; section 1 staged nothing\n");

    orpheus_engine_destroy(engine);
    return ok;
}

// ── (m) an entry row fires however its section is reached ───────────────────
// The contrast is the point: section 0's live outgoing edge moves between slot 0 and
// slot 1 across the two runs, and the same s0 -> s1 flip happens either way. An entry
// row on section 1 must fire in BOTH; an edge-0 row on section 0 only in the first.
static bool test_entry_row_fires_however_the_section_is_entered() {
    printf("\n=== Test: an entry row fires whichever edge enters its section ===\n");
    bool ok = true;

    for (int variant = 0; variant < 2; variant++) {
        FlipProbe entry = run_trans_fx_to_first_flip(kTransFxEdgeEntry, variant, /*row_section*/1);
        FlipProbe specific = run_trans_fx_to_first_flip(/*row_edge*/0, variant, /*row_section*/0);
        if (!entry.flipped || !specific.flipped) {
            printf("  FAIL: variant %d never flipped within %d blocks\n", variant, kMaxBlocks);
            ok = false;
            continue;
        }
        if (!entry.armed_at_flip) {
            printf("  FAIL: the section-1 entry row did not fire with the live edge at slot %d\n",
                   variant);
            ok = false;
        }
        // Only when slot 0 is the edge actually taken — this is what makes the entry
        // row's insensitivity to the edge a real result rather than a coincidence.
        const bool specific_expected = (variant == 0);
        if (specific.armed_at_flip != specific_expected) {
            printf("  FAIL: edge-0 row fired=%d with the live edge at slot %d (expected %d) —"
                   " the fixture no longer distinguishes the two edges\n",
                   specific.armed_at_flip, variant, specific_expected);
            ok = false;
        }
    }

    if (ok) printf("  PASS: the entry row fired on both edge layouts; the edge-0 row only on its own\n");
    return ok;
}

// ── (n) an entry row is deaf to other arrivals and to its own section's exit ──
static bool test_entry_row_ignores_other_arrivals_and_its_own_exit() {
    printf("\n=== Test: a section-1 entry row stays silent entering section 0 / leaving section 1 ===\n");
    OrpheusEngine* engine = make_trans_fx_engine();
    // 40ms so the window can be drained back out before the second flip is watched.
    write_trans_fx_row(engine, 0, /*section*/1, kTransFxEdgeEntry, TRANS_FX_TAPE_STOP,
                       /*offset*/0.0f, /*ms*/40.0f);
    trigger_vibe_load(engine);

    GraphUnit unit = make_trans_fx_unit();
    float drain[512];
    // Phase 1: to the s0 -> s1 flip, draining the master effect the way the master chain
    // does so is_active() tracks the real window instead of latching (see file header).
    bool flipped = false, armed_at_first_flip = false;
    int staged_leaving_s0 = -1;
    for (int i = 0; i < kMaxBlocks && !flipped; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        // Section 0 is current at load: an entry row must never reach the outgoing
        // staging list, whichever section it names.
        if (i == 0) staged_leaving_s0 = engine->pulsar_state->pending_fx_count;
        std::memset(drain, 0, sizeof(drain));
        engine->master_tape_stop_l.process(drain, 512);
        flipped = engine->pulsar_state->section_state.current_section != 0;
        if (flipped) armed_at_first_flip = engine->master_tape_stop_l.is_active();
    }
    const int staged_leaving_s1 = engine->pulsar_state->pending_fx_count;

    // Phase 2: retire the 40ms (1920-sample) window.
    for (int d = 0; d < 8; d++) {
        std::memset(drain, 0, sizeof(drain));
        engine->master_tape_stop_l.process(drain, 512);
    }
    const bool retired = !engine->master_tape_stop_l.is_active();

    // Phase 3: through section 1 and its flip back to section 0. That single boundary is
    // both "some other section is entered" and "section 1 is left" — neither may arm.
    bool armed_on_second_flip = false, second_flip = false;
    for (int i = 0; i < kMaxBlocks && !second_flip; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        std::memset(drain, 0, sizeof(drain));
        engine->master_tape_stop_l.process(drain, 512);
        if (engine->master_tape_stop_l.is_active()) armed_on_second_flip = true;
        second_flip = engine->pulsar_state->section_state.current_section == 0;
    }

    bool ok = true;
    if (!flipped) { printf("  FAIL: section 0 never flipped within %d blocks\n", kMaxBlocks); ok = false; }
    if (!armed_at_first_flip) { printf("  FAIL: the entry row did not fire on the arrival it names\n"); ok = false; }
    if (staged_leaving_s0 != 0 || staged_leaving_s1 != 0) {
        printf("  FAIL: outgoing staging picked up an entry row (s0=%d, s1=%d, expected 0/0)\n",
               staged_leaving_s0, staged_leaving_s1);
        ok = false;
    }
    if (!retired) { printf("  FAIL: the 40ms window never drained (test invariant broken)\n"); ok = false; }
    if (!second_flip) { printf("  FAIL: section 1 never flipped within %d blocks\n", kMaxBlocks); ok = false; }
    if (armed_on_second_flip) {
        printf("  FAIL: the section-1 entry row fired entering section 0 / leaving section 1\n");
        ok = false;
    }
    if (ok) printf("  PASS: fired only on the arrival into section 1; never staged as a departure\n");

    orpheus_engine_destroy(engine);
    return ok;
}

// ── (n2) the OPENING section's entry rows do not fire at song start ─────────
// There is no transition into the arrangement's first section, and entry rows only ever
// reach the dispatcher from the flip handler. The same row must still fire once section 0
// is genuinely arrived at, which is what keeps this from passing on a dead row.
static bool test_entry_row_does_not_fire_at_song_start() {
    printf("\n=== Test: an entry row on the OPENING section stays silent until it is really entered ===\n");
    OrpheusEngine* engine = make_trans_fx_engine();
    write_trans_fx_row(engine, 0, /*section*/0, kTransFxEdgeEntry, TRANS_FX_TAPE_STOP,
                       /*offset*/0.0f, /*ms*/40.0f);
    trigger_vibe_load(engine);

    GraphUnit unit = make_trans_fx_unit();
    float drain[512];
    // Phase 1: song start through the s0 -> s1 flip. Section 0 is current the whole way,
    // and it is never ENTERED — nothing may arm, at the downbeat or anywhere after it.
    bool armed_before_leaving_s0 = false, first_flip = false;
    for (int i = 0; i < kMaxBlocks && !first_flip; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        std::memset(drain, 0, sizeof(drain));
        engine->master_tape_stop_l.process(drain, 512);
        if (engine->master_tape_stop_l.is_active()) armed_before_leaving_s0 = true;
        first_flip = engine->pulsar_state->section_state.current_section != 0;
    }

    // Phase 2: on to the s1 -> s0 flip, the first real arrival into section 0.
    bool armed_on_return = false, second_flip = false;
    for (int i = 0; i < kMaxBlocks && !second_flip; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        second_flip = engine->pulsar_state->section_state.current_section == 0;
        if (second_flip) armed_on_return = engine->master_tape_stop_l.is_active();
        std::memset(drain, 0, sizeof(drain));
        engine->master_tape_stop_l.process(drain, 512);
    }

    bool ok = true;
    if (!first_flip) { printf("  FAIL: section 0 never flipped within %d blocks\n", kMaxBlocks); ok = false; }
    if (armed_before_leaving_s0) {
        printf("  FAIL: the opening section's entry row fired at song start\n");
        ok = false;
    }
    if (!second_flip) { printf("  FAIL: never returned to section 0 within %d blocks\n", kMaxBlocks); ok = false; }
    if (!armed_on_return) {
        printf("  FAIL: the row did not fire on the real arrival back into section 0\n");
        ok = false;
    }
    if (ok) printf("  PASS: silent through the opening section, fired on the first arrival into it\n");

    orpheus_engine_destroy(engine);
    return ok;
}

// ── (o) union across all three lists at a single flip ───────────────────────
static bool test_exit_edge_and_entry_rows_all_fire_at_one_flip() {
    printf("\n=== Test: the departing section's exit row, the edge's row and the destination's entry row all fire ===\n");
    OrpheusEngine* engine = make_trans_fx_engine();
    // Three distinct targets so each firing is separately observable.
    write_trans_fx_row(engine, 0, /*section*/0, kTransFxEdgeAny, TRANS_FX_TAPE_STOP,
                       /*offset*/0.0f, /*ms*/200.0f);
    write_trans_fx_row(engine, 1, /*section*/0, /*edge*/0, TRANS_FX_STRIKE, /*offset*/0.0f,
                       /*intensity*/0.9f, /*distance*/0.2f);
    write_trans_fx_row(engine, 2, /*section*/1, kTransFxEdgeEntry, TRANS_FX_SCRATCH,
                       /*offset*/0.0f, /*ms*/400.0f);
    trigger_vibe_load(engine);

    GraphUnit unit = make_trans_fx_unit();
    int staged = -1;
    bool flipped = false;
    bool tape_at_flip = false, strike_at_flip = false, scratch_at_flip = false;
    for (int i = 0; i < kMaxBlocks && !flipped; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        // pulsar_state is allocated on the first process call, so read the staging after it.
        if (i == 0) staged = engine->pulsar_state->pending_fx_count;
        const bool strike = engine->pulsar_state->storm_voice.strike_active();
        flipped = engine->pulsar_state->section_state.current_section != 0;
        if (flipped) {
            tape_at_flip = engine->master_tape_stop_l.is_active();
            strike_at_flip = strike;
            scratch_at_flip = engine->master_scratch_l.is_active();
        }
    }

    bool ok = true;
    // The entry row belongs to the arrival, so only the two departure rows stage up front.
    if (staged != 2) { printf("  FAIL: staged %d departure rows at load (expected 2)\n", staged); ok = false; }
    if (!flipped) { printf("  FAIL: section never flipped within %d blocks\n", kMaxBlocks); ok = false; }
    if (!tape_at_flip) { printf("  FAIL: the exit (wildcard) tape stop did not fire\n"); ok = false; }
    if (!strike_at_flip) { printf("  FAIL: the edge-specific strike did not fire\n"); ok = false; }
    if (!scratch_at_flip) { printf("  FAIL: the destination's entry scratch did not fire\n"); ok = false; }
    if (ok) printf("  PASS: exit, edge and entry all landed on the same flip\n");

    orpheus_engine_destroy(engine);
    return ok;
}

// ── (p) a positive offset on an entry row fires one bar INTO its section ────
static bool test_entry_row_positive_offset_fires_one_bar_in() {
    printf("\n=== Test: an entry row with offset +1 fires a bar into its section, not at the flip ===\n");
    OrpheusEngine* engine = make_trans_fx_engine();
    write_trans_fx_row(engine, 0, /*section*/1, kTransFxEdgeEntry, TRANS_FX_TAPE_STOP,
                       /*offset*/1.0f, /*ms*/200.0f);
    trigger_vibe_load(engine);

    GraphUnit unit = make_trans_fx_unit();
    int flip_block = -1, fire_block = -1;
    int section_at_fire = -1, bars_left_at_fire = -1;
    for (int i = 0; i < kMaxBlocks && fire_block < 0; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        const SectionState& ss = engine->pulsar_state->section_state;
        if (flip_block < 0 && ss.current_section != 0) flip_block = i;
        if (engine->master_tape_stop_l.is_active()) {
            fire_block = i;
            section_at_fire = ss.current_section;
            bars_left_at_fire = ss.bars_remaining;
        }
    }

    bool ok = true;
    if (flip_block < 0) { printf("  FAIL: section never flipped within %d blocks\n", kMaxBlocks); ok = false; }
    if (fire_block < 0) { printf("  FAIL: the entry row never fired\n"); ok = false; }
    if (fire_block >= 0 && flip_block >= 0 && fire_block <= flip_block) {
        printf("  FAIL: fired at block %d, at or before the flip (block %d)\n", fire_block, flip_block);
        ok = false;
    }
    // Section 1 is 2 bars, so one elapsed bar of it leaves 1 remaining.
    if (fire_block >= 0 && (section_at_fire != 1 || bars_left_at_fire != 1)) {
        printf("  FAIL: fired in section %d with bars_remaining %d (expected section 1, 1 bar left)\n",
               section_at_fire, bars_left_at_fire);
        ok = false;
    }
    if (ok) printf("  PASS: carried past the flip, fired one bar into the section it entered\n");

    orpheus_engine_destroy(engine);
    return ok;
}

// ── Bank plumbing: the wire symbol reaches the atomics ──────────────────────
static bool test_trans_fx_bank_routing() {
    printf("\n=== Test: trans_fx_data_$i routes to the engine bank and bounds-checks ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    const char* uri = "org.balch.orpheus.plugins.pulsar";

    orpheus_engine_set_port(engine, uri, "trans_fx_data_0", 3.0f);
    orpheus_engine_set_port(engine, uri, "trans_fx_data_167", 7.5f);
    orpheus_engine_set_port(engine, uri, "trans_fx_data_168", 9.0f);  // out of range, dropped

    bool ok = true;
    float first = engine->pulsar_trans_fx_data[0].load(std::memory_order_relaxed);
    float last  = engine->pulsar_trans_fx_data[kTransFxBankSize - 1].load(std::memory_order_relaxed);
    if (first != 3.0f) { printf("  FAIL: slot 0 = %.2f (expected 3.0)\n", first); ok = false; }
    if (last != 7.5f)  { printf("  FAIL: slot 167 = %.2f (expected 7.5)\n", last); ok = false; }
    if (kTransFxBankSize != 168) { printf("  FAIL: bank size = %d (expected 168)\n", kTransFxBankSize); ok = false; }
    if (ok) printf("  PASS: 168-slot bank routed, out-of-range write dropped\n");

    orpheus_engine_destroy(engine);
    return ok;
}

// ── Staging math, independent of the audio path ─────────────────────────────
static bool test_staging_offsets_and_filtering() {
    printf("\n=== Test: stage_transition_fx filters by section/edge and clamps the offset ===\n");
    TransFxRow rows[5];
    rows[0] = TransFxRow{0, 0, TRANS_FX_SCRATCH,   0.0f, 400.0f, 0.0f, 0.0f};
    rows[1] = TransFxRow{0, 0, TRANS_FX_TAPE_STOP, -1.0f, 200.0f, 0.0f, 0.0f};
    rows[2] = TransFxRow{0, 1, TRANS_FX_SCRATCH,   0.0f, 400.0f, 0.0f, 0.0f};  // other edge
    rows[3] = TransFxRow{1, 0, TRANS_FX_SCRATCH,   0.0f, 400.0f, 0.0f, 0.0f};  // other section
    rows[4] = TransFxRow{0, 0, TRANS_FX_STRIKE,    3.0f, 0.8f, 0.4f, 0.0f};    // clamps to +1 bar

    PendingTransFx pending[kMaxPendingFx];
    int n = stage_transition_fx(rows, 5, /*section*/0, /*edge*/0, /*bars_remaining*/4,
                                pending, kMaxPendingFx);

    bool ok = true;
    if (n != 3) { printf("  FAIL: staged %d rows (expected 3 on section 0 / edge 0)\n", n); ok = false; }
    if (n > 0 && pending[0].bars_until_fire != 4.0f) {
        printf("  FAIL: offset 0 staged at %.1f (expected 4)\n", pending[0].bars_until_fire); ok = false;
    }
    if (n > 1 && pending[1].bars_until_fire != 3.0f) {
        printf("  FAIL: offset -1 staged at %.1f (expected 3)\n", pending[1].bars_until_fire); ok = false;
    }
    // A positive offset fires INTO the section the flip enters, capped at one bar:
    // +3 stages at span + 1, flagged so the flip carries it instead of firing it.
    if (n > 2 && pending[2].bars_until_fire != 5.0f) {
        printf("  FAIL: offset +3 staged at %.1f (expected clamp to span + 1 = 5)\n",
               pending[2].bars_until_fire); ok = false;
    }
    if (n > 2 && !pending[2].after_flip) {
        printf("  FAIL: offset +3 not flagged after_flip\n"); ok = false;
    }
    if (n > 0 && pending[0].after_flip) {
        printf("  FAIL: offset 0 flagged after_flip (it fires AT the flip)\n"); ok = false;
    }
    if (n > 1 && pending[1].after_flip) {
        printf("  FAIL: offset -1 flagged after_flip\n"); ok = false;
    }

    // A far-negative offset clamps at 0 rather than going negative.
    TransFxRow early[1] = { TransFxRow{0, 0, TRANS_FX_TAPE_STOP, -9.0f, 100.0f, 0.0f, 0.0f} };
    int m = stage_transition_fx(early, 1, 0, 0, 4, pending, kMaxPendingFx);
    if (m != 1 || pending[0].bars_until_fire != 0.0f) {
        printf("  FAIL: offset -9 staged %d row(s) at %.1f (expected 1 at 0)\n", m, pending[0].bars_until_fire);
        ok = false;
    }

    // Type 0 rows are unauthored padding and must not stage.
    TransFxRow none[1] = { TransFxRow{0, 0, TRANS_FX_NONE, 0.0f, 0.0f, 0.0f, 0.0f} };
    if (stage_transition_fx(none, 1, 0, 0, 4, pending, kMaxPendingFx) != 0) {
        printf("  FAIL: a type 0 row staged\n"); ok = false;
    }

    // Entry rows belong to arrivals: the outgoing staging must skip them even though
    // their edge is negative like the exit wildcard's, and stage_entry_transition_fx
    // must pick up only its own section's.
    TransFxRow entry_rows[3];
    entry_rows[0] = TransFxRow{0, kTransFxEdgeEntry, TRANS_FX_SCRATCH,   0.0f, 400.0f, 0.0f, 0.0f};
    entry_rows[1] = TransFxRow{0, kTransFxEdgeEntry, TRANS_FX_TAPE_STOP, 1.0f, 200.0f, 0.0f, 0.0f};
    entry_rows[2] = TransFxRow{1, kTransFxEdgeEntry, TRANS_FX_SCRATCH,   0.0f, 400.0f, 0.0f, 0.0f};
    if (stage_transition_fx(entry_rows, 3, /*section*/0, /*edge*/0, 4, pending, kMaxPendingFx) != 0) {
        printf("  FAIL: an entry row staged on an outgoing edge\n"); ok = false;
    }
    int e = stage_entry_transition_fx(entry_rows, 3, /*section*/0, pending, kMaxPendingFx);
    if (e != 2) { printf("  FAIL: entry staging returned %d rows (expected section 0's 2)\n", e); ok = false; }
    // Offsets are measured from the section's own first downbeat: <= 0 lands on it,
    // positive carries to the next boundary.
    if (e > 0 && (pending[0].bars_until_fire != 0.0f || pending[0].after_flip)) {
        printf("  FAIL: entry offset 0 staged at %.1f after_flip=%d (expected 0.0 / false)\n",
               pending[0].bars_until_fire, pending[0].after_flip);
        ok = false;
    }
    if (e > 1 && (pending[1].bars_until_fire != 1.0f || !pending[1].after_flip)) {
        printf("  FAIL: entry offset +1 staged at %.1f after_flip=%d (expected 1.0 / true)\n",
               pending[1].bars_until_fire, pending[1].after_flip);
        ok = false;
    }

    // The countdown only touches armed slots.
    PendingTransFx tick[2];
    tick[0] = PendingTransFx{TRANS_FX_SCRATCH, 0, 0, 0, 2.0f, true};
    tick[1] = PendingTransFx{TRANS_FX_SCRATCH, 0, 0, 0, 2.0f, false};
    tick_transition_fx_bar(tick, 2);
    if (tick[0].bars_until_fire != 1.0f || tick[1].bars_until_fire != 2.0f) {
        printf("  FAIL: tick armed=%.1f disarmed=%.1f (expected 1.0 / 2.0)\n",
               tick[0].bars_until_fire, tick[1].bars_until_fire);
        ok = false;
    }
    if (ok) printf("  PASS: filtering, clamping and the bar countdown all hold\n");
    return ok;
}

bool run_pulsar_transition_fx_tests() {
    printf("\n=== Pulsar Transition FX Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    uint32_t saved_random = stmlib::Random::state();

    if (test_trans_fx_bank_routing()) suite_pass++; else suite_fail++;
    if (test_staging_offsets_and_filtering()) suite_pass++; else suite_fail++;
    if (test_scratch_row_arms_at_flip()) suite_pass++; else suite_fail++;
    if (test_scratch_row_freezes_clock_like_legacy_exit_scratch()) suite_pass++; else suite_fail++;
    if (test_row_on_untaken_edge_never_fires()) suite_pass++; else suite_fail++;
    if (test_transition_rearms_over_running_effect()) suite_pass++; else suite_fail++;
    if (test_tape_row_arms_tape_stop()) suite_pass++; else suite_fail++;
    if (test_rows_cleared_and_restaged_on_vibe_load()) suite_pass++; else suite_fail++;
    if (test_negative_offset_fires_before_flip()) suite_pass++; else suite_fail++;
    if (test_positive_offset_fires_after_flip()) suite_pass++; else suite_fail++;
    if (test_strike_row_fires_storm_voice_at_flip()) suite_pass++; else suite_fail++;
    if (test_strike_row_p2_delays_the_storm_voice()) suite_pass++; else suite_fail++;
    if (test_wildcard_edge_row_fires_on_any_taken_edge()) suite_pass++; else suite_fail++;
    if (test_wildcard_and_edge_row_both_fire_at_one_flip()) suite_pass++; else suite_fail++;
    if (test_wildcard_row_still_discriminates_by_section()) suite_pass++; else suite_fail++;
    if (test_entry_row_fires_however_the_section_is_entered()) suite_pass++; else suite_fail++;
    if (test_entry_row_ignores_other_arrivals_and_its_own_exit()) suite_pass++; else suite_fail++;
    if (test_entry_row_does_not_fire_at_song_start()) suite_pass++; else suite_fail++;
    if (test_exit_edge_and_entry_rows_all_fire_at_one_flip()) suite_pass++; else suite_fail++;
    if (test_entry_row_positive_offset_fires_one_bar_in()) suite_pass++; else suite_fail++;

    stmlib::Random::Seed(saved_random);
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
