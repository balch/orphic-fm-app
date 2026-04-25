// Pulsar glide priority and chord-edge tracking
//
// Verifies the resolved per-track glide_rate selection in unit_process_pulsar
// (orpheus_unit_pulsar.cpp:2053-2078) honors the documented priority:
//   1) Per-lick-step glide (step.glide_rate >= 0)
//   2) Per-chord glide on chord-transition edge (one-shot)
//   3) Per-track default
// Also covers the regression for the chord-edge tracker initial state:
// last_chord_index == -1 means "no previous chord" so the very first gated
// step (and the first step after a section transition) must NOT consume
// progression_glides[0]. See orpheus_unit_pulsar.cpp:809, :532.

#include "test_harness.h"
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"

#include <cmath>
#include <cstring>

namespace {

// Map glide_param (0..1) to the per-sample glide_rate that the engine stores.
// Mirror of the formula in orpheus_unit_pulsar.cpp:
//   glide_rate = 0.001 * pow(0.02, glide_param)   when glide_param > 0.001
//              = 0                                  otherwise
float expected_glide_rate(float glide_param) {
    if (glide_param <= 0.001f) return 0.0f;
    return 0.001f * std::pow(0.02f, glide_param);
}

// Process enough samples to advance the lick playhead past N step boundaries
// at the given BPM. lick step durations are in beats; the longest in our tests
// is 1 beat, so 4 beats * (60/BPM) seconds gives us 4 steps of headroom.
void process_for_beats(OrpheusEngine* engine, GraphUnit* unit,
                       float beats, float bpm, float sample_rate) {
    const int total_samples = static_cast<int>((beats * 60.0f / bpm) * sample_rate);
    const int frames_per_call = 256;
    int processed = 0;
    while (processed < total_samples) {
        unit_process_pulsar(unit, engine, frames_per_call, sample_rate);
        processed += frames_per_call;
    }
}

void make_unit(GraphUnit* u) {
    std::memset(u, 0, sizeof(GraphUnit));
    u->type = UNIT_PULSAR;
    u->enabled = true;
}

// Set a 4-step lick. Step 0..2 use the supplied glide_rate; step 3 is a rest.
void push_lick(OrpheusEngine* engine, float step_glide) {
    engine->pulsar_lick[0] = {0, 0.5f, 0.8f, step_glide};
    engine->pulsar_lick[1] = {2, 0.5f, 0.8f, step_glide};
    engine->pulsar_lick[2] = {4, 0.5f, 0.8f, step_glide};
    engine->pulsar_lick[3] = {0, 0.5f, 0.8f, step_glide};
    engine->pulsar_lick_length.store(4, std::memory_order_release);
}

// LickMode enum values from orpheus_unit_pulsar.h: 0=NONE, 1=SQUASH, 2=FILL.
constexpr int kLickModeFill = 2;

// Tracks 3, 4, 5, 7 are MELODIC in setup_cosmic_techno. Pick track 3 as the
// designated lick track for these tests.
constexpr int kLickTrack = 3;

void minimal_engine_setup(OrpheusEngine* engine, float bpm) {
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_energy.store(0.7f, std::memory_order_relaxed);
    engine->pulsar_complexity.store(0.5f, std::memory_order_relaxed);
    engine->pulsar_space.store(0.4f, std::memory_order_relaxed);
    engine->pulsar_mood.store(0.5f, std::memory_order_relaxed);
    engine->clock_bpm.store(bpm, std::memory_order_relaxed);
    setup_cosmic_techno(engine);
    engine->pulsar_lick_mutation.store(0.0f, std::memory_order_relaxed);
    // Enable FILL lick on a melodic track so the lick steps actually gate.
    engine->pulsar_track_lick_mode[kLickTrack].store(
        kLickModeFill, std::memory_order_relaxed);
}

}  // namespace

// ── Test: last_chord_index starts at -1 after vibe load ─────────────────────
static bool test_last_chord_index_initial_state() {
    printf("\n  Test: last_chord_index initialized to -1 across all tracks on vibe load\n");
    bool pass = true;

    OrpheusEngine* engine = orpheus_engine_create(48000);
    GraphUnit unit; make_unit(&unit);

    minimal_engine_setup(engine, 120.0f);
    push_lick(engine, -1.0f);
    trigger_vibe_load(engine);

    // Single short process tick to materialize state without advancing any step.
    unit_process_pulsar(&unit, engine, 64, 48000.0f);

    PulsarState* state = engine->pulsar_state;
    if (!state) {
        printf("    FAIL: pulsar_state was not allocated\n");
        orpheus_engine_destroy(engine);
        return false;
    }
    for (int t = 0; t < 8; t++) {
        if (state->tracks[t].last_chord_index != -1) {
            printf("    FAIL: track %d last_chord_index = %d (expected -1)\n",
                   t, state->tracks[t].last_chord_index);
            pass = false;
        }
    }
    if (pass) printf("    PASS: all 8 tracks have last_chord_index == -1 immediately after vibe load\n");

    orpheus_engine_destroy(engine);
    return pass;
}

// ── Test: first gated step does not consume progression_glides[0] ───────────
// Configures a custom progression with progression_glides[0] = 0.7 and verifies
// that the FIRST gated chord transition (-1 -> 0) does NOT apply that glide
// (no previous chord to slide from). The track-level glide is held at 0 to
// isolate the chord-glide path; if the bug regresses, the resolved glide_rate
// will be non-zero on the first gate.
static bool test_first_chord_edge_skips_glide() {
    printf("\n  Test: first chord transition (-1 -> 0) skips progression_glides[0]\n");
    bool pass = true;

    const float bpm = 480.0f;  // 4× tempo so we cross several lick steps quickly
    OrpheusEngine* engine = orpheus_engine_create(48000);
    GraphUnit unit; make_unit(&unit);

    minimal_engine_setup(engine, bpm);
    push_lick(engine, -1.0f);  // step glide = sentinel (use track default)

    // Track default glide = 0 so any non-zero resolved value can only come
    // from the chord-glide path.
    for (int t = 0; t < 8; t++) {
        engine->pulsar_track_glide_rate[t].store(0.0f, std::memory_order_relaxed);
    }

    // Enable a custom progression with a non-zero glide on chord 0. If the
    // initial-state regression returns, the first gate will pick this up.
    engine->pulsar_custom_progression_active.store(1, std::memory_order_relaxed);
    engine->pulsar_custom_progression_length.store(3, std::memory_order_relaxed);
    engine->pulsar_custom_progression[0].store(0, std::memory_order_relaxed);
    engine->pulsar_custom_progression[1].store(4, std::memory_order_relaxed);
    engine->pulsar_custom_progression[2].store(5, std::memory_order_relaxed);
    engine->pulsar_custom_progression_glide[0].store(0.7f, std::memory_order_relaxed);
    engine->pulsar_custom_progression_glide[1].store(0.0f, std::memory_order_relaxed);
    engine->pulsar_custom_progression_glide[2].store(0.0f, std::memory_order_relaxed);

    trigger_vibe_load(engine);

    // Process a single beat — long enough for the first step to gate but not
    // long enough to cross a chord boundary.
    process_for_beats(engine, &unit, 1.0f, bpm, 48000.0f);

    PulsarState* state = engine->pulsar_state;
    const PulsarTrackState& ts = state->tracks[kLickTrack];
    if (!ts.prev_step_gated) {
        printf("    FAIL: lick track %d never gated within 1 beat — test setup issue\n",
               kLickTrack);
        pass = false;
    } else {
        if (ts.glide_rate > 0.0f) {
            printf("    FAIL: track %d glide_rate = %.6f after first chord edge "
                   "(should be 0; chord_glide must not fire on -1 -> 0)\n",
                   kLickTrack, ts.glide_rate);
            pass = false;
        }
        if (ts.last_chord_index != 0) {
            printf("    FAIL: track %d last_chord_index = %d after first gate "
                   "(expected 0)\n", kLickTrack, ts.last_chord_index);
            pass = false;
        }
        if (pass) printf("    PASS: first chord transition skipped chord glide as designed\n");
    }

    orpheus_engine_destroy(engine);
    return pass;
}

// ── Test: per-step glide_rate overrides per-track glide ─────────────────────
static bool test_step_glide_overrides_track() {
    printf("\n  Test: lick step.glide_rate >= 0 overrides per-track glide\n");
    bool pass = true;

    const float bpm = 480.0f;
    OrpheusEngine* engine = orpheus_engine_create(48000);
    GraphUnit unit; make_unit(&unit);

    minimal_engine_setup(engine, bpm);
    // Step glide = 0.4 (explicit override). Track glide = 0.9 (much slower).
    push_lick(engine, 0.4f);
    for (int t = 0; t < 8; t++) {
        engine->pulsar_track_glide_rate[t].store(0.9f, std::memory_order_relaxed);
    }
    trigger_vibe_load(engine);

    // Run long enough for multiple gated steps so prev_step_gated is true.
    process_for_beats(engine, &unit, 2.0f, bpm, 48000.0f);

    PulsarState* state = engine->pulsar_state;
    const PulsarTrackState& ts = state->tracks[kLickTrack];
    const float want = expected_glide_rate(0.4f);
    const float fallback = expected_glide_rate(0.9f);
    if (!ts.prev_step_gated) {
        printf("    FAIL: lick track %d never gated within 2 beats\n", kLickTrack);
        pass = false;
    } else if (std::fabs(ts.glide_rate - fallback) < fallback * 0.05f) {
        printf("    FAIL: track %d glide_rate matches track default (%.6f) "
               "instead of step override (%.6f)\n",
               kLickTrack, ts.glide_rate, want);
        pass = false;
    } else if (std::fabs(ts.glide_rate - want) > want * 0.10f) {
        printf("    FAIL: track %d glide_rate=%.6f does not match expected step "
               "override %.6f\n", kLickTrack, ts.glide_rate, want);
        pass = false;
    } else {
        printf("    PASS: step.glide_rate overrides track default (resolved=%.6f)\n",
               ts.glide_rate);
    }

    orpheus_engine_destroy(engine);
    return pass;
}

// ── Test: step.glide_rate == -1 falls through to per-track glide ─────────────
static bool test_step_sentinel_falls_through_to_track() {
    printf("\n  Test: lick step.glide_rate == -1 (sentinel) falls through to track default\n");
    bool pass = true;

    const float bpm = 480.0f;
    OrpheusEngine* engine = orpheus_engine_create(48000);
    GraphUnit unit; make_unit(&unit);

    minimal_engine_setup(engine, bpm);
    push_lick(engine, -1.0f);                                  // sentinel
    for (int t = 0; t < 8; t++) {
        engine->pulsar_track_glide_rate[t].store(0.5f, std::memory_order_relaxed);
    }
    trigger_vibe_load(engine);

    process_for_beats(engine, &unit, 2.0f, bpm, 48000.0f);

    PulsarState* state = engine->pulsar_state;
    const PulsarTrackState& ts = state->tracks[kLickTrack];
    const float want = expected_glide_rate(0.5f);
    if (!ts.prev_step_gated) {
        printf("    FAIL: lick track %d never gated within 2 beats\n", kLickTrack);
        pass = false;
    } else if (std::fabs(ts.glide_rate - want) > want * 0.10f) {
        printf("    FAIL: track %d glide_rate=%.6f does not match track default %.6f "
               "(sentinel step.glide_rate did not fall through)\n",
               kLickTrack, ts.glide_rate, want);
        pass = false;
    } else {
        printf("    PASS: sentinel step.glide_rate falls through to track default "
               "(resolved=%.6f)\n", ts.glide_rate);
    }

    orpheus_engine_destroy(engine);
    return pass;
}

// ── Suite runner ────────────────────────────────────────────────────────────
bool run_pulsar_glide_tests() {
    printf("\n═══ Pulsar Glide Priority & Chord-Edge Tracking ═══\n");
    int passed = 0, failed = 0;

    auto run = [&](bool (*fn)()) {
        if (fn()) passed++; else failed++;
    };

    run(test_last_chord_index_initial_state);
    run(test_first_chord_edge_skips_glide);
    run(test_step_glide_overrides_track);
    run(test_step_sentinel_falls_through_to_track);

    printf("\n  Pulsar Glide: %d passed, %d failed\n", passed, failed);
    TEST_SUITE_RETURN(passed, failed);
}
