#include "test_harness.h"
#include <cmath>
#include "pulsar_score_sched.h"
#include "pulsar_score_clock.h"
#include "test_pulsar_helpers.h"
#include "orpheus_engine.h"
#include "orpheus_unit_pulsar.h"
#include <cstdio>
#include <cstring>

using ScoreEvent = OrpheusEngine::ScoreEvent;

static ScoreEvent mk(int tick, int pitch, bool hold = false) {
    ScoreEvent e{};
    e.tick = tick;
    e.duration = 24;
    e.pitch = static_cast<uint8_t>(pitch);
    e.velocity = 96;
    e.flags = hold ? 1 : 0;
    return e;
}

static bool test_fires_only_events_at_or_before_now() {
    printf("\n=== Test: fires events at or before now, and no others ===\n");
    ScoreEvent ev[3] = {mk(0, 60), mk(24, 62), mk(48, 64)};
    ScoreTrackCursor cur{};
    ScoreEvent out[8];
    int n = score_collect_due(ev, 3, cur, 24, out, 8);
    bool ok = (n == 2) && out[0].pitch == 60 && out[1].pitch == 62 && cur.index == 2;
    printf("  n=%d cursor=%d -- %s\n", n, cur.index, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_cursor_never_replays() {
    printf("\n=== Test: a second call at the same tick fires nothing ===\n");
    ScoreEvent ev[2] = {mk(0, 60), mk(24, 62)};
    ScoreTrackCursor cur{};
    ScoreEvent out[8];
    score_collect_due(ev, 2, cur, 24, out, 8);
    int n = score_collect_due(ev, 2, cur, 24, out, 8);
    bool ok = (n == 0) && cur.index == 2;
    printf("  n=%d -- %s\n", n, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_a_long_jump_fires_every_skipped_event() {
    printf("\n=== Test: a large time jump fires the whole span, never skips ===\n");
    // A block boundary can straddle many events at a fast tempo. Dropping the
    // intervening notes would silently thin the music.
    ScoreEvent ev[5] = {mk(0, 60), mk(6, 61), mk(12, 62), mk(18, 63), mk(24, 64)};
    ScoreTrackCursor cur{};
    ScoreEvent out[8];
    int n = score_collect_due(ev, 5, cur, 100, out, 8);
    bool ok = (n == 5) && out[4].pitch == 64;
    printf("  n=%d -- %s\n", n, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_output_cap_is_respected_and_cursor_stays_consistent() {
    printf("\n=== Test: output cap truncates without losing the cursor ===\n");
    ScoreEvent ev[5] = {mk(0, 60), mk(6, 61), mk(12, 62), mk(18, 63), mk(24, 64)};
    ScoreTrackCursor cur{};
    ScoreEvent out[2];
    int n = score_collect_due(ev, 5, cur, 100, out, 2);
    bool first_ok = (n == 2) && cur.index == 2;
    // The remainder must arrive on the next call, not vanish.
    int n2 = score_collect_due(ev, 5, cur, 100, out, 2);
    bool ok = first_ok && (n2 == 2) && cur.index == 4;
    printf("  n=%d n2=%d cursor=%d -- %s\n", n, n2, cur.index, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_end_of_score_stops_firing() {
    printf("\n=== Test: past the last event nothing fires and nothing reads out of bounds ===\n");
    ScoreEvent ev[2] = {mk(0, 60), mk(24, 62)};
    ScoreTrackCursor cur{};
    ScoreEvent out[8];
    score_collect_due(ev, 2, cur, 1000, out, 8);
    int n = score_collect_due(ev, 2, cur, 100000, out, 8);
    bool ok = (n == 0) && cur.index == 2;
    printf("  n=%d -- %s\n", n, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_hold_fires_its_event_then_blocks() {
    printf("\n=== Test: a hold event fires, then blocks the cursor ===\n");
    ScoreEvent ev[3] = {mk(0, 60), mk(24, 62, /*hold=*/true), mk(48, 64)};
    ScoreTrackCursor cur{};
    ScoreEvent out[8];
    int n = score_collect_due(ev, 3, cur, 1000, out, 8);
    // Both the plain event and the hold event fire; the one AFTER the hold does not.
    bool ok = (n == 2) && out[1].pitch == 62 && cur.held && cur.index == 2;
    printf("  n=%d held=%d cursor=%d -- %s\n", n, cur.held, cur.index, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_free_run_fires_a_hold_event_without_parking() {
    printf("\n=== Test: free-run fires a hold event without parking the cursor ===\n");
    ScoreEvent ev[3] = {mk(0, 60), mk(24, 62, /*hold=*/true), mk(48, 64)};
    ScoreTrackCursor cur{};
    ScoreEvent out[8];
    int n = score_collect_due(ev, 3, cur, 1000, out, 8, /*free_run=*/true);
    // All three in ONE call, not two: the park is what would have split them, and a chord
    // whose first note carries the hold must still collect whole rather than flam by a block.
    bool ok = (n == 3) && !cur.held && cur.index == 3;
    printf("  n=%d held=%d cursor=%d -- %s\n", n, cur.held, cur.index, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_release_resumes_from_the_held_event() {
    printf("\n=== Test: releasing a hold resumes at the next event ===\n");
    ScoreEvent ev[3] = {mk(0, 60), mk(24, 62, /*hold=*/true), mk(48, 64)};
    ScoreTrackCursor cur{};
    ScoreEvent out[8];
    score_collect_due(ev, 3, cur, 1000, out, 8);
    score_release_hold(cur);
    int n = score_collect_due(ev, 3, cur, 1000, out, 8);
    bool ok = (n == 1) && out[0].pitch == 64 && !cur.held;
    printf("  n=%d -- %s\n", n, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_reset_returns_to_the_first_event() {
    printf("\n=== Test: reset rewinds the cursor and clears any hold ===\n");
    ScoreEvent ev[2] = {mk(0, 60), mk(24, 62, /*hold=*/true)};
    ScoreTrackCursor cur{};
    ScoreEvent out[8];
    score_collect_due(ev, 2, cur, 1000, out, 8);
    score_cursor_reset(cur);
    bool ok = cur.index == 0 && !cur.held;
    printf("  cursor=%d held=%d -- %s\n", cur.index, cur.held, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_empty_part_is_inert() {
    printf("\n=== Test: a zero-event part never fires and never reads memory ===\n");
    ScoreTrackCursor cur{};
    ScoreEvent out[8];
    int n = score_collect_due(nullptr, 0, cur, 1000, out, 8);
    bool ok = (n == 0) && cur.index == 0;
    printf("  n=%d -- %s\n", n, ok ? "PASS" : "FAIL");
    return ok;
}

// ── Live-engine integration tests ────────────────────────────────────
// No test_make_engine()/test_process_blocks() exist anywhere in this codebase (checked);
// this mirrors test_lick_pool_round_trip's setup (test_pulsar_lick_select.cpp) inline
// instead, which is the real, working pattern for a real-engine Pulsar test.

// Builds a playing engine with a loaded vibe and warms PulsarState up. Warm-up happens
// BEFORE any score is published on purpose: it leaves the score clock parked at zero for
// the publish fence to start from, and it matches how a score really arrives (the user
// picks one while the band is already running).
static PulsarState* start_scored_engine(OrpheusEngine* engine, GraphUnit& unit) {
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);
    trigger_vibe_load(engine);
    // PulsarState is allocated lazily on the first process call — warm up BEFORE
    // touching engine->pulsar_state or this segfaults 100% of the time.
    for (int i = 0; i < 20; i++) unit_process_pulsar(&unit, engine, 512, 48000.0f);
    return engine->pulsar_state;
}

// Writes one part into the wire arrays exactly the way the routing layer does, then
// publishes it: events and count first, the per-track flag next, the generation fence last.
static void publish_part(OrpheusEngine* engine, int t,
                         const int* ticks, const int* pitches, int count, int duration) {
    for (int i = 0; i < count; i++) {
        engine->pulsar_score_events[t][i].tick     = ticks[i];
        engine->pulsar_score_events[t][i].duration = static_cast<uint16_t>(duration);
        engine->pulsar_score_events[t][i].pitch    = static_cast<uint8_t>(pitches[i]);
        engine->pulsar_score_events[t][i].velocity = 100;
        engine->pulsar_score_events[t][i].flags    = 0;
    }
    engine->pulsar_score_event_count[t] = count;
    engine->pulsar_track_score_driven[t].store(1, std::memory_order_release);
    engine->pulsar_score_generation.fetch_add(1, std::memory_order_release);
}

// Written notes now land on the engine's VOICE BANK, not on the score-driven Pulsar
// track's single voice. These four used to read ps->tracks[t].target_pitch/gate_timer,
// which the flat-pool rewrite removed: a chord needs a slot each, so a per-track pitch
// field could never express one. They read voice_params instead.

// True while any bank voice is gated at `pitch`.
static bool bank_sounds(OrpheusEngine* engine, int pitch) {
    for (int v = 0; v < kNumMainVoices; v++) {
        if (engine->voice_params[v].gate.load(std::memory_order_relaxed) != 0 &&
            static_cast<int>(engine->voice_params[v].tune.load(std::memory_order_relaxed)) == pitch)
            return true;
    }
    return false;
}

static bool test_score_driven_track_plays_written_pitches() {
    printf("\n=== Test: a score-driven track plays its written pitches ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    PulsarState* ps = start_scored_engine(engine, unit);
    if (ps == nullptr) { printf("  FAIL (no state)\n"); orpheus_engine_destroy(engine); return false; }

    const int t = 4;
    const int ticks[3]   = {0, kScorePpq, 2 * kScorePpq};
    const int pitches[3] = {64, 66, 68};
    publish_part(engine, t, ticks, pitches, 3, 48);

    bool saw_64 = false, saw_68 = false;
    for (int i = 0; i < 4000; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        if (bank_sounds(engine, 64)) saw_64 = true;
        if (saw_64 && bank_sounds(engine, 68)) saw_68 = true;
    }
    const bool ok = saw_64 && saw_68;
    printf("  saw64=%d saw68=%d -- %s\n", saw_64, saw_68, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_a_chord_sounds_on_distinct_voices() {
    printf("\n=== Test: three notes at one tick sound on three distinct voices ===\n");
    // The reason the per-track path had to go. Under last-wins this was one pitch.
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    PulsarState* ps = start_scored_engine(engine, unit);
    if (ps == nullptr) { printf("  FAIL (no state)\n"); orpheus_engine_destroy(engine); return false; }

    const int t = 4;
    const int ticks[3]   = {kScorePpq, kScorePpq, kScorePpq};   // one chord
    const int pitches[3] = {64, 67, 71};
    publish_part(engine, t, ticks, pitches, 3, 192);

    int best = 0;
    for (int i = 0; i < 2000; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        int n = 0;
        for (int p : pitches) if (bank_sounds(engine, p)) n++;
        if (n > best) best = n;
    }
    const bool ok = best == 3 && ps->score_pool.steals_from_held == 0;
    printf("  simultaneous chord tones=%d/3 held_steals=%u -- %s\n",
           best, ps->score_pool.steals_from_held, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_written_notes_release_when_their_duration_ends() {
    printf("\n=== Test: a written note releases when its duration ends ===\n");
    // Without expiry a voice stays gated forever, the pool fills, and the piece degenerates
    // into stealing. Duration is in ticks and release in samples, so nothing else connects
    // "the note is over" to "stop sounding".
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    PulsarState* ps = start_scored_engine(engine, unit);
    if (ps == nullptr) { printf("  FAIL (no state)\n"); orpheus_engine_destroy(engine); return false; }

    const int t = 4;
    const int ticks[1]   = {kScorePpq};
    const int pitches[1] = {64};
    publish_part(engine, t, ticks, pitches, 1, 24);        // a short note

    bool sounded = false, released = false;
    for (int i = 0; i < 3000; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        if (bank_sounds(engine, 64)) sounded = true;
        else if (sounded) { released = true; break; }
    }
    const bool ok = sounded && released;
    printf("  sounded=%d then released=%d -- %s\n", sounded, released, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_score_does_not_fire_before_its_publish_fence() {
    printf("\n=== Test: no written note sounds before the generation fence ===\n");
    // Events and count are plain arrays. Acting on them before the generation bump would
    // read a half-written score, which is exactly what the fence exists to prevent.
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    PulsarState* ps = start_scored_engine(engine, unit);
    if (ps == nullptr) { printf("  FAIL (no state)\n"); orpheus_engine_destroy(engine); return false; }

    const int t = 4;
    // Everything EXCEPT the generation bump: the score is staged but unpublished.
    engine->pulsar_score_events[t][0].tick = 0;
    engine->pulsar_score_events[t][0].duration = 480;
    engine->pulsar_score_events[t][0].pitch = 71;
    engine->pulsar_score_events[t][0].velocity = 100;
    engine->pulsar_score_events[t][0].flags = 0;
    engine->pulsar_score_event_count[t] = 1;
    engine->pulsar_track_score_driven[t].store(1, std::memory_order_release);

    bool unfenced = false;
    for (int i = 0; i < 200; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        if (bank_sounds(engine, 71)) unfenced = true;
    }
    engine->pulsar_score_generation.fetch_add(1, std::memory_order_release);
    bool after_fence = false;
    for (int i = 0; i < 400; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        if (bank_sounds(engine, 71)) after_fence = true;
    }
    const bool ok = !unfenced && after_fence;
    printf("  unfenced=%d after_fence=%d -- %s\n", unfenced, after_fence, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_band_hold_mutes_pulsar_until_a_cue_event() {
    printf("\n=== Test: the band stays silent until a score event cues it in ===\n");
    // The dramatic opening. flags bit 1 releases the hold; until then Pulsar is muted but
    // its clock must keep running, or the event that clears the hold never arrives.
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    PulsarState* ps = start_scored_engine(engine, unit);
    if (ps == nullptr) { printf("  FAIL (no state)\n"); orpheus_engine_destroy(engine); return false; }

    const int t = 4;
    engine->pulsar_score_events[t][0] = {0,   96, 64, 100, 0};   // motif alone
    engine->pulsar_score_events[t][1] = {480, 96, 67, 100, 2};   // bit 1: band in
    engine->pulsar_score_event_count[t] = 2;
    engine->pulsar_track_score_driven[t].store(1, std::memory_order_release);
    engine->pulsar_band_hold.store(1, std::memory_order_relaxed);
    engine->pulsar_score_generation.fetch_add(1, std::memory_order_release);

    double held_energy = 0.0;
    bool released = false;
    int blocks_to_release = 0;
    for (int i = 0; i < 3000; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        if (!released) {
            for (int k = 0; k < 512; k++) held_energy += std::fabs(unit.output_buffers[OPORT_OUT][k]);
            if (engine->pulsar_band_hold.load(std::memory_order_relaxed) == 0) {
                released = true; blocks_to_release = i;
            }
        }
    }
    double after_energy = 0.0;
    for (int i = 0; i < 200; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        for (int k = 0; k < 512; k++) after_energy += std::fabs(unit.output_buffers[OPORT_OUT][k]);
    }
    // Per-block, because the two windows differ in length. Held is not bit-exact zero: a
    // small residual survives the mute (~0.06/block against ~20/block once the band is in,
    // a 300x+ drop). The mute zeroes the unit's own output, so the remainder is upstream
    // state -- filter/limiter ringing is the likely source. Asserted as a ratio rather than
    // equality so the test states what it actually verified, with the residual visible.
    const double held_per_block  = blocks_to_release > 0 ? held_energy / blocks_to_release : held_energy;
    const double after_per_block = after_energy / 200.0;
    const bool ok = released && after_per_block > 50.0 * held_per_block && after_per_block > 0.0;
    printf("  released after %d blocks; band per-block held=%.4f after=%.4f (%.0fx) -- %s\n",
           blocks_to_release, held_per_block, after_per_block,
           held_per_block > 0 ? after_per_block / held_per_block : 0.0, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// The Watch-mode bug, end to end: a GATED opening with nobody conducting. Every motif note
// carries hold and the fermata also carries bandRelease, with band_hold armed exactly as
// A score host's applyScore arms it for a GATED first section. Without free-run the clock parks
// on the first note forever, so the tick-192 release never fires and the band never enters:
// one note, then permanent silence. Free-run is what Watch sets instead of a conductor.
static bool test_free_run_plays_a_gated_opening_with_no_conductor() {
    printf("\n=== Test: free-run plays a gated opening through, with no conductor ===\n");
    static constexpr const char* PULSAR_URI = "org.balch.orpheus.plugins.pulsar";
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    PulsarState* ps = start_scored_engine(engine, unit);
    if (ps == nullptr) { printf("  FAIL (no state)\n"); orpheus_engine_destroy(engine); return false; }
    (void)ps;

    // The Fifth's shape: three held pickups into a held fermata that cues the band in.
    const int t = 4;
    engine->pulsar_score_events[t][0] = {48,   48, 67, 100, /*hold=*/1};
    engine->pulsar_score_events[t][1] = {96,   48, 67, 100, /*hold=*/1};
    engine->pulsar_score_events[t][2] = {144,  48, 67, 100, /*hold=*/1};
    engine->pulsar_score_events[t][3] = {192, 192, 63, 100, /*hold+release=*/3};
    engine->pulsar_score_event_count[t] = 4;
    engine->pulsar_track_score_driven[t].store(1, std::memory_order_release);
    engine->pulsar_band_hold.store(1, std::memory_order_relaxed);
    engine->pulsar_score_generation.fetch_add(1, std::memory_order_release);

    orpheus_engine_set_port(engine, PULSAR_URI, "score_free_run", 1.0f);

    // 600 blocks at 512 frames / 48kHz is ~6.4s. The written opening ends at tick 192,
    // ~1s at the 120bpm default, so a free-running clock clears it many times over.
    bool released = false;
    int blocks_to_release = -1;
    for (int i = 0; i < 600; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        if (!released && engine->pulsar_band_hold.load(std::memory_order_relaxed) == 0) {
            released = true;
            blocks_to_release = i;
        }
    }
    const int pos = engine->pulsar_score_pos_tick.load(std::memory_order_relaxed);
    const int any_held = engine->pulsar_score_any_held.load(std::memory_order_relaxed);

    // Past the last written event AND never parked: position alone would pass on a clock
    // that advanced while a cursor sat held on some other track.
    const bool ok = released && pos > 192 && any_held == 0;
    printf("  released=%d after %d blocks; pos=%d any_held=%d -- %s\n",
           released, blocks_to_release, pos, any_held, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_untouched_tracks_still_generate() {
    printf("\n=== Test: flipping one track leaves the others on the pattern generator ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);
    trigger_vibe_load(engine);

    engine->pulsar_track_score_driven[4].store(1, std::memory_order_relaxed);
    engine->pulsar_score_event_count[4] = 0;   // score-driven but empty: silent
    for (int i = 0; i < 20; i++) unit_process_pulsar(&unit, engine, 512, 48000.0f);
    PulsarState* ps = engine->pulsar_state;
    if (ps == nullptr) {
        printf("  FAIL (no state)\n");
        orpheus_engine_destroy(engine);
        return false;
    }
    // Track 0 is the kick and must still be producing gated steps.
    bool any_gate = false;
    for (int s = 0; s < kMaxPulsarSteps; s++) {
        if (ps->tracks[0].steps[s].gate) { any_gate = true; break; }
    }
    printf("  track0_gates=%d -- %s\n", any_gate, any_gate ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return any_gate;
}

// Regression test for a review finding: the score_count_ routing branch stored the
// incoming port value with no upper bound, and score_collect_due trusts that count as
// its loop bound against the fixed-size pulsar_score_events[t][kMaxScoreEvents] array —
// an unclamped count above kMaxScoreEvents is an audio-thread out-of-bounds read. Goes
// through the real orpheus_engine_set_port() routing entry point (not the atomic
// directly) so it actually exercises the fix, not just the field it writes to.
static bool test_score_count_port_is_clamped_to_capacity() {
    printf("\n=== Test: score_count_ port clamps to [0, kMaxScoreEvents] ===\n");
    static constexpr const char* PULSAR_URI = "org.balch.orpheus.plugins.pulsar";
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    orpheus_engine_set_port(engine, PULSAR_URI, "score_count_4", 999999.0f);
    int over = engine->pulsar_score_event_count[4];

    orpheus_engine_set_port(engine, PULSAR_URI, "score_count_4", -50.0f);
    int under = engine->pulsar_score_event_count[4];

    orpheus_engine_set_port(engine, PULSAR_URI, "score_count_4", 100.0f);
    int in_range = engine->pulsar_score_event_count[4];

    bool ok = (over == kMaxScoreEvents) && (under == 0) && (in_range == 100);
    printf("  over=%d (cap %d) under=%d in_range=%d -- %s\n",
           over, kMaxScoreEvents, under, in_range, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Task 2: score_part_<t>_* routing must reach the mirrored voice, not just pitch/gate.
// Checks timbre lands verbatim and that moving the part's engine sets engine_changed --
// the flag that forces the LPG to retrigger instead of gliding into the new engine.
static bool test_score_part_timbre_and_engine_reach_the_voice_bank() {
    printf("\n=== Test: score_part_ timbre and engine changes reach voice_params[0] ===\n");
    static constexpr const char* PULSAR_URI = "org.balch.orpheus.plugins.pulsar";
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    PulsarState* ps = start_scored_engine(engine, unit);
    if (ps == nullptr) { printf("  FAIL (no state)\n"); orpheus_engine_destroy(engine); return false; }

    const int t = 2;
    orpheus_engine_set_port(engine, PULSAR_URI, "score_part_2_timbre", 0.9f);

    const int ticks[1]   = {0};
    const int pitches[1] = {64};
    publish_part(engine, t, ticks, pitches, 1, 96);
    unit_process_pulsar(&unit, engine, 512, 48000.0f);

    float timbre = engine->voice_params[0].timbre.load(std::memory_order_relaxed);
    int changed_before = engine->voice_params[0].engine_changed.load(std::memory_order_relaxed);

    orpheus_engine_set_port(engine, PULSAR_URI, "score_part_2_engine", 5.0f);
    unit_process_pulsar(&unit, engine, 512, 48000.0f);
    int changed_after = engine->voice_params[0].engine_changed.load(std::memory_order_relaxed);

    bool ok = std::fabs(timbre - 0.9f) < 0.001f && changed_before == 0 && changed_after == 1;
    printf("  timbre=%.3f changed_before=%d changed_after=%d -- %s\n",
           timbre, changed_before, changed_after, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Task 2: score_part_<t>_level scales the accent the mirror writes, on top of the
// existing velocity curve. A velocity-127 note reaches the top of that curve (factor
// 1.0) before scaling, so level=0.5 must halve it to exactly 0.5.
static bool test_score_part_level_scales_accent_for_max_velocity() {
    printf("\n=== Test: score_part_ level scales accent for a velocity-127 note ===\n");
    static constexpr const char* PULSAR_URI = "org.balch.orpheus.plugins.pulsar";
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    PulsarState* ps = start_scored_engine(engine, unit);
    if (ps == nullptr) { printf("  FAIL (no state)\n"); orpheus_engine_destroy(engine); return false; }

    const int t = 2;
    orpheus_engine_set_port(engine, PULSAR_URI, "score_part_2_level", 0.5f);

    engine->pulsar_score_events[t][0] = {0, 96, 64, 127, 0};
    engine->pulsar_score_event_count[t] = 1;
    engine->pulsar_track_score_driven[t].store(1, std::memory_order_release);
    engine->pulsar_score_generation.fetch_add(1, std::memory_order_release);
    unit_process_pulsar(&unit, engine, 512, 48000.0f);

    float accent = engine->voice_params[0].accent.load(std::memory_order_relaxed);
    bool ok = std::fabs(accent - 0.5f) < 0.001f;
    printf("  accent=%.4f (expected 0.5) -- %s\n", accent, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Task 5: a hold event parks its cursor, which must freeze the WHOLE ensemble clock (not
// just that track) so the held note's own end_tick never arrives -- it sustains for exactly
// as long as the conductor waits, at no cost to the pool. Block 1 fires the hold event and
// parks the cursor; block 2 is the first whose top-of-block state has learned of the park,
// so it's the first the clock actually skips advancing on -- position and gate must hold
// from there for as long as we keep processing.
static bool test_a_parked_cursor_freezes_the_clock_and_sustains_the_gate() {
    printf("\n=== Test: a parked cursor freezes the clock and sustains the gate ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    PulsarState* ps = start_scored_engine(engine, unit);
    if (ps == nullptr) { printf("  FAIL (no state)\n"); orpheus_engine_destroy(engine); return false; }

    const int t = 4;
    engine->pulsar_score_events[t][0] = {0,  96, 64, 100, /*flags hold=*/1};
    engine->pulsar_score_events[t][1] = {96, 96, 67, 100, 0};
    engine->pulsar_score_event_count[t] = 2;
    engine->pulsar_track_score_driven[t].store(1, std::memory_order_release);
    engine->pulsar_score_generation.fetch_add(1, std::memory_order_release);

    unit_process_pulsar(&unit, engine, 512, 48000.0f);
    unit_process_pulsar(&unit, engine, 512, 48000.0f);
    const int frozen_pos = engine->pulsar_score_pos_tick.load(std::memory_order_relaxed);
    const int any_held_flag = engine->pulsar_score_any_held.load(std::memory_order_relaxed);

    // 200 blocks at 512 frames / 48kHz is ~2.1s -- many times the note's nominal 96-tick
    // (~0.5s at 120bpm) duration, which would have expired the voice long ago without the
    // freeze.
    bool pos_stayed = true, gate_stayed = true;
    for (int i = 0; i < 200; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        if (engine->pulsar_score_pos_tick.load(std::memory_order_relaxed) != frozen_pos) pos_stayed = false;
        if (!bank_sounds(engine, 64)) gate_stayed = false;
    }

    bool ok = any_held_flag == 1 && pos_stayed && gate_stayed;
    printf("  any_held=%d frozen_pos=%d pos_stayed=%d gate_stayed=%d -- %s\n",
           any_held_flag, frozen_pos, pos_stayed, gate_stayed, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Task 5: a beat (score_hold_release, wired through the real routing entry point) must
// resume the clock and fire the freed cursor's next event exactly once -- no burst, since
// the frozen clock never built up a backlog to catch up on. next_age (bumped once per
// score_voice_start) is the precise "how many voices actually started" counter: it isolates
// a burst from a single correct fire in a way that scanning for the pitch alone cannot.
//
// Review follow-up: the clock used to resume from the HELD note's own tick and free-run
// up to the next event, rather than snapping straight to it -- correct eventually, but the
// note landed up to ~45 blocks (~0.5s at 120bpm) behind the conductor's beat. blocks_to_67
// pins that down to "within 2 blocks", which only the snap-forward fix satisfies.
static bool test_release_frees_the_parked_cursor_and_fires_once() {
    printf("\n=== Test: a release resumes the clock and fires the next event exactly once ===\n");
    static constexpr const char* PULSAR_URI = "org.balch.orpheus.plugins.pulsar";
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    PulsarState* ps = start_scored_engine(engine, unit);
    if (ps == nullptr) { printf("  FAIL (no state)\n"); orpheus_engine_destroy(engine); return false; }

    const int t = 4;
    engine->pulsar_score_events[t][0] = {0,  96, 64, 100, /*flags hold=*/1};
    engine->pulsar_score_events[t][1] = {96, 96, 67, 100, 0};
    engine->pulsar_score_event_count[t] = 2;
    engine->pulsar_track_score_driven[t].store(1, std::memory_order_release);
    engine->pulsar_score_generation.fetch_add(1, std::memory_order_release);

    // Park the cursor and let the freeze settle (mirrors the sustain test above).
    for (int i = 0; i < 50; i++) unit_process_pulsar(&unit, engine, 512, 48000.0f);
    const int frozen_pos = engine->pulsar_score_pos_tick.load(std::memory_order_relaxed);
    const bool held_before = engine->pulsar_score_any_held.load(std::memory_order_relaxed) == 1;
    const uint32_t age_before = ps->score_pool.next_age;

    orpheus_engine_set_port(engine, PULSAR_URI, "score_hold_release", 1.0f);

    bool resumed = false, saw_67 = false;
    int blocks_to_67 = -1;
    for (int i = 0; i < 400; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        if (engine->pulsar_score_pos_tick.load(std::memory_order_relaxed) != frozen_pos) resumed = true;
        if (!saw_67 && bank_sounds(engine, 67)) { saw_67 = true; blocks_to_67 = i; }
    }
    const uint32_t new_voices = ps->score_pool.next_age - age_before;
    const int any_held_after = engine->pulsar_score_any_held.load(std::memory_order_relaxed);

    const bool fired_promptly = saw_67 && blocks_to_67 >= 0 && blocks_to_67 <= 2;
    const bool ok = held_before && resumed && fired_promptly && any_held_after == 0 && new_voices == 1;
    printf("  held_before=%d resumed=%d saw_67=%d blocks_to_67=%d any_held_after=%d new_voices=%u -- %s\n",
           held_before, resumed, saw_67, blocks_to_67, any_held_after, new_voices, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Review follow-up: a fermata can hold for a full 240 ticks (vs. the 96-tick motif gap
// above) -- the pre-fix free-run clock scaled its catch-up lag with the gap, so this must
// resume just as promptly as the shorter gap, not slower.
static bool test_release_after_a_fermata_length_gap_still_fires_promptly() {
    printf("\n=== Test: a release after a fermata-length gap still fires within 2 blocks ===\n");
    static constexpr const char* PULSAR_URI = "org.balch.orpheus.plugins.pulsar";
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    PulsarState* ps = start_scored_engine(engine, unit);
    if (ps == nullptr) { printf("  FAIL (no state)\n"); orpheus_engine_destroy(engine); return false; }

    const int t = 4;
    engine->pulsar_score_events[t][0] = {0,   96, 64, 100, /*flags hold=*/1};
    engine->pulsar_score_events[t][1] = {240, 96, 67, 100, 0};
    engine->pulsar_score_event_count[t] = 2;
    engine->pulsar_track_score_driven[t].store(1, std::memory_order_release);
    engine->pulsar_score_generation.fetch_add(1, std::memory_order_release);

    for (int i = 0; i < 50; i++) unit_process_pulsar(&unit, engine, 512, 48000.0f);
    const bool held_before = engine->pulsar_score_any_held.load(std::memory_order_relaxed) == 1;

    orpheus_engine_set_port(engine, PULSAR_URI, "score_hold_release", 1.0f);

    bool saw_67 = false;
    int blocks_to_67 = -1;
    for (int i = 0; i < 400; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        if (!saw_67 && bank_sounds(engine, 67)) { saw_67 = true; blocks_to_67 = i; }
    }
    const bool ok = held_before && saw_67 && blocks_to_67 >= 0 && blocks_to_67 <= 2;
    printf("  held_before=%d saw_67=%d blocks_to_67=%d -- %s\n",
           held_before, saw_67, blocks_to_67, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Degenerate/defensive: a non-monotonic authored score -- the event right after the hold
// sits BEHIND the hold event's own tick -- must never rewind tick_pos on release. A naive
// "snap straight to the freed cursor's next tick" would pull the clock backward here; the
// fix only snaps FORWARD, so the position after release must be >= the frozen position.
static bool test_release_never_snaps_the_clock_backward() {
    printf("\n=== Test: release never snaps the clock backward, even with a non-monotonic score ===\n");
    static constexpr const char* PULSAR_URI = "org.balch.orpheus.plugins.pulsar";
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    PulsarState* ps = start_scored_engine(engine, unit);
    if (ps == nullptr) { printf("  FAIL (no state)\n"); orpheus_engine_destroy(engine); return false; }

    const int t = 4;
    // Deliberately non-monotonic: tick 20 sits BEHIND the hold event's own tick 48.
    engine->pulsar_score_events[t][0] = {48, 96, 64, 100, /*flags hold=*/1};
    engine->pulsar_score_events[t][1] = {20, 96, 67, 100, 0};
    engine->pulsar_score_event_count[t] = 2;
    engine->pulsar_track_score_driven[t].store(1, std::memory_order_release);
    engine->pulsar_score_generation.fetch_add(1, std::memory_order_release);

    for (int i = 0; i < 50; i++) unit_process_pulsar(&unit, engine, 512, 48000.0f);
    const int frozen_pos = engine->pulsar_score_pos_tick.load(std::memory_order_relaxed);
    const bool held_before = engine->pulsar_score_any_held.load(std::memory_order_relaxed) == 1;

    orpheus_engine_set_port(engine, PULSAR_URI, "score_hold_release", 1.0f);
    unit_process_pulsar(&unit, engine, 512, 48000.0f);
    const int pos_after = engine->pulsar_score_pos_tick.load(std::memory_order_relaxed);

    const bool ok = held_before && frozen_pos >= 48 && pos_after >= frozen_pos;
    printf("  frozen_pos=%d pos_after=%d -- %s\n", frozen_pos, pos_after, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Task 5: releasing when nothing is held must be a consumed no-op -- the counter drains
// (so a stray beat never lingers to fire on a LATER hold it had nothing to do with) but
// the clock is otherwise unaffected. This is the invariant the whole feature must preserve
// for every score published before this task existed.
static bool test_release_with_nothing_held_is_a_consumed_noop() {
    printf("\n=== Test: releasing with nothing held is a no-op that still drains the counter ===\n");
    static constexpr const char* PULSAR_URI = "org.balch.orpheus.plugins.pulsar";
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    PulsarState* ps = start_scored_engine(engine, unit);
    if (ps == nullptr) { printf("  FAIL (no state)\n"); orpheus_engine_destroy(engine); return false; }

    const int t = 4;
    // No hold flags anywhere: the pre-Task-5 shape every existing score has.
    engine->pulsar_score_events[t][0] = {0,   96, 60, 100, 0};
    engine->pulsar_score_events[t][1] = {192, 96, 62, 100, 0};
    engine->pulsar_score_event_count[t] = 2;
    engine->pulsar_track_score_driven[t].store(1, std::memory_order_release);
    engine->pulsar_score_generation.fetch_add(1, std::memory_order_release);

    unit_process_pulsar(&unit, engine, 512, 48000.0f);
    const int pos_before = engine->pulsar_score_pos_tick.load(std::memory_order_relaxed);

    orpheus_engine_set_port(engine, PULSAR_URI, "score_hold_release", 1.0f);
    const int staged = engine->pulsar_score_hold_release.load(std::memory_order_relaxed);

    unit_process_pulsar(&unit, engine, 512, 48000.0f);
    const int pos_after = engine->pulsar_score_pos_tick.load(std::memory_order_relaxed);
    const int drained = engine->pulsar_score_hold_release.load(std::memory_order_relaxed);
    const int any_held = engine->pulsar_score_any_held.load(std::memory_order_relaxed);

    const bool ok = staged == 1 && drained == 0 && any_held == 0 && pos_after > pos_before;
    printf("  staged=%d drained=%d any_held=%d pos_before=%d pos_after=%d -- %s\n",
           staged, drained, any_held, pos_before, pos_after, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Review follow-up: score_accent_scale's routing row had zero coverage through the real
// orpheus_engine_set_port() entry point -- nothing would have caught a copy-paste that
// turned the store into a fetch_add, or a wrong-field write. Velocity 127 and the default
// part level of 1.0 put the pre-existing velocity-curve*level product at exactly 1.0, so
// the mirrored accent equals accent_scale alone, with nothing else to obscure it. The
// second write must REPLACE the first, not accumulate on top of it -- that's the
// store-vs-fetch_add distinction the review called out, and is why this test writes twice.
static bool test_score_accent_scale_multiplies_accent_and_replaces_not_accumulates() {
    printf("\n=== Test: score_accent_scale multiplies accent via routing; store not fetch_add ===\n");
    static constexpr const char* PULSAR_URI = "org.balch.orpheus.plugins.pulsar";
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    PulsarState* ps = start_scored_engine(engine, unit);
    if (ps == nullptr) { printf("  FAIL (no state)\n"); orpheus_engine_destroy(engine); return false; }

    const int t = 4;
    engine->pulsar_score_events[t][0] = {0, 96, 64, 127, 0};   // velocity 127, part level 1.0 (default)
    engine->pulsar_score_event_count[t] = 1;
    engine->pulsar_track_score_driven[t].store(1, std::memory_order_release);
    engine->pulsar_score_generation.fetch_add(1, std::memory_order_release);

    orpheus_engine_set_port(engine, PULSAR_URI, "score_accent_scale", 0.5f);
    unit_process_pulsar(&unit, engine, 512, 48000.0f);
    float accent_a = engine->voice_params[0].accent.load(std::memory_order_relaxed);

    orpheus_engine_set_port(engine, PULSAR_URI, "score_accent_scale", 0.8f);
    unit_process_pulsar(&unit, engine, 512, 48000.0f);
    float accent_b = engine->voice_params[0].accent.load(std::memory_order_relaxed);

    bool ok = std::fabs(accent_a - 0.5f) < 0.001f && std::fabs(accent_b - 0.8f) < 0.001f;
    printf("  accent_a=%.4f (expected 0.5) accent_b=%.4f (expected 0.8, not 1.3) -- %s\n",
           accent_a, accent_b, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Task 8: the ABI getter (orpheus_engine_get_pulsar_arrangement) must carry the notated-
// score clock in out[6]/out[7] exactly like the raw pulsar_score_pos_tick/any_held atomics
// -- including with NO Pulsar arrangement configured, since start_scored_engine (like this
// suite's other live-engine tests) never calls setup_jam_arrangement. If the getter only
// wrote those two slots inside the sec>=0 branch, this out[0..5]=="inactive" scenario would
// read back stale zeros instead of the live clock.
static bool test_arrangement_getter_carries_score_position_and_hold_flag() {
    printf("\n=== Test: the arrangement getter carries score tick + hold flag ===\n");
    static constexpr const char* PULSAR_URI = "org.balch.orpheus.plugins.pulsar";
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    PulsarState* ps = start_scored_engine(engine, unit);
    if (ps == nullptr) { printf("  FAIL (no state)\n"); orpheus_engine_destroy(engine); return false; }

    const int t = 4;
    engine->pulsar_score_events[t][0] = {0,  96, 64, 100, /*flags hold=*/1};
    engine->pulsar_score_events[t][1] = {96, 96, 67, 100, 0};
    engine->pulsar_score_event_count[t] = 2;
    engine->pulsar_track_score_driven[t].store(1, std::memory_order_release);
    engine->pulsar_score_generation.fetch_add(1, std::memory_order_release);

    // Let the hold event fire and the cursor park (mirrors Task 5's freeze test).
    for (int i = 0; i < 50; i++) unit_process_pulsar(&unit, engine, 512, 48000.0f);

    int out[8] = {-99, -99, -99, -99, -99, -99, -99, -99};
    orpheus_engine_get_pulsar_arrangement(engine, out);
    const bool inactive_sections = out[0] == -1 && out[1] == 0 && out[2] == 0 &&
                                    out[3] == 0 && out[4] == -1 && out[5] == 0;
    const bool tick_positive = out[6] > 0;
    const bool held_flag_set = out[7] == 1;
    const bool matches_atomics_while_held =
        out[6] == engine->pulsar_score_pos_tick.load(std::memory_order_relaxed) &&
        out[7] == engine->pulsar_score_any_held.load(std::memory_order_relaxed);

    orpheus_engine_set_port(engine, PULSAR_URI, "score_hold_release", 1.0f);
    for (int i = 0; i < 50; i++) unit_process_pulsar(&unit, engine, 512, 48000.0f);
    orpheus_engine_get_pulsar_arrangement(engine, out);
    const bool held_flag_cleared = out[7] == 0;
    const bool matches_atomics_after_release =
        out[6] == engine->pulsar_score_pos_tick.load(std::memory_order_relaxed) &&
        out[7] == engine->pulsar_score_any_held.load(std::memory_order_relaxed);

    const bool ok = inactive_sections && tick_positive && held_flag_set &&
                     matches_atomics_while_held && held_flag_cleared &&
                     matches_atomics_after_release;
    printf("  inactive_sections=%d tick=%d held_set=%d held_cleared=%d "
           "matches_while_held=%d matches_after_release=%d -- %s\n",
           inactive_sections, out[6], held_flag_set, held_flag_cleared,
           matches_atomics_while_held, matches_atomics_after_release, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

bool run_pulsar_score_sched_tests() {
    printf("\n========== Pulsar Score Scheduler ==========\n");
    int passed = 0, failed = 0;
    auto run = [&](bool (*fn)()) { if (fn()) passed++; else failed++; };
    run(test_fires_only_events_at_or_before_now);
    run(test_cursor_never_replays);
    run(test_a_long_jump_fires_every_skipped_event);
    run(test_output_cap_is_respected_and_cursor_stays_consistent);
    run(test_end_of_score_stops_firing);
    run(test_hold_fires_its_event_then_blocks);
    run(test_free_run_fires_a_hold_event_without_parking);
    run(test_release_resumes_from_the_held_event);
    run(test_reset_returns_to_the_first_event);
    run(test_empty_part_is_inert);
    run(test_score_driven_track_plays_written_pitches);
    run(test_a_chord_sounds_on_distinct_voices);
    run(test_written_notes_release_when_their_duration_ends);
    run(test_score_does_not_fire_before_its_publish_fence);
    run(test_band_hold_mutes_pulsar_until_a_cue_event);
    run(test_free_run_plays_a_gated_opening_with_no_conductor);
    run(test_untouched_tracks_still_generate);
    run(test_score_count_port_is_clamped_to_capacity);
    run(test_score_part_timbre_and_engine_reach_the_voice_bank);
    run(test_score_part_level_scales_accent_for_max_velocity);
    run(test_a_parked_cursor_freezes_the_clock_and_sustains_the_gate);
    run(test_release_frees_the_parked_cursor_and_fires_once);
    run(test_release_after_a_fermata_length_gap_still_fires_promptly);
    run(test_release_never_snaps_the_clock_backward);
    run(test_release_with_nothing_held_is_a_consumed_noop);
    run(test_score_accent_scale_multiplies_accent_and_replaces_not_accumulates);
    run(test_arrangement_getter_carries_score_position_and_hold_flag);
    printf("\n  Pulsar Score Scheduler: %d passed, %d failed\n", passed, failed);
    TEST_SUITE_RETURN(passed, failed);
}
