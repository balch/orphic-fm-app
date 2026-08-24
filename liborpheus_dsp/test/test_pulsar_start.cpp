// Pulsar vibe start-sequence tests.
//
// A vibe load must engage the sequencer grid immediately: the downbeat
// (steps[0]) fires at the load instant, and the loop window adopts the NEW
// vibe's step_count before the first pass — never wrapping (or overrunning
// into stale steps) at the previous vibe's length.

#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/orpheus_graph.h"
#include "stmlib/utils/random.h"
#include <cstdio>
#include <cmath>
#include <cstring>

namespace {

constexpr float kSampleRate = 48000.0f;
constexpr int kBlock = 512;
constexpr float kBpm = 120.0f;  // samples_per_step = 6000 at 48kHz

// Observation block for downbeat checks. Deliberately short: gate_timer is
// decremented by num_frames once per block, so a full 512-sample block can
// retire a short hi-hat gate (duration 0.1 at 180 BPM = 100 samples) inside the
// very block that set it, and the witness reads as silent on correct code.
constexpr int kObserveBlock = 64;

GraphUnit make_start_unit() {
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    return unit;
}

OrpheusEngine* make_start_engine(void (*setup)(OrpheusEngine*),
                                 float energy = 1.0f,
                                 float bpm = kBpm,
                                 float mix = 1.0f) {
    OrpheusEngine* engine = orpheus_engine_create(kSampleRate);
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(mix, std::memory_order_relaxed);
    setup(engine);
    engine->pulsar_energy.store(energy, std::memory_order_relaxed);
    pin_pulsar_rngs(engine);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(bpm, std::memory_order_relaxed);
    return engine;
}

int blocks_for_steps(double steps, float bpm = kBpm) {
    return static_cast<int>(steps * pulsar_samples_per_step(bpm) / kBlock) + 1;
}

}  // namespace

// ── Test 1: the grid engages at the load instant, at every energy ──────────
// One block after a vibe load the window must already be adopted and every
// track whose pattern gates step 0 must be sounding.
//
// The energy sweep is load-bearing. The fire gate rolls step_hash(playhead,
// track, loop_count), and load_vibe zeroes both playhead and loop_count — so at
// the downbeat the hash collapses to t * 104729, a per-track CONSTANT
// independent of seed, vibe and pattern. Its rolls (t2=0.988, t3=0.997,
// t4=0.977) sit above the reachable fire_prob ceiling (0.95 percussive, 0.985
// at energy 0.95), so the bass and t2/t4 lost the downbeat on EVERY load below
// energy 0.99. Pinning energy=1.0 alone would test only the one value whose
// `energy >= 0.99f` bypass hides exactly that.
static bool test_start_engages_grid_at_load() {
    printf("\n=== Test: vibe load engages the grid at sample 0 ===\n");
    const float energies[] = {0.2f, 0.5f, 0.8f, 1.0f};
    const float bpms[] = {120.0f, 160.0f, 180.0f};
    bool all_ok = true;

    for (float bpm : bpms) {
        for (float e : energies) {
            OrpheusEngine* engine = make_start_engine(setup_fixture_dense_fast, e, bpm);
            GraphUnit unit = make_start_unit();
            unit_process_pulsar(&unit, engine, kObserveBlock, kSampleRate);
            PulsarState* state = engine->pulsar_state;

            bool window_ok = state->tracks[0].wrap_len == 16;
            bool any_downbeat = false, fired_all = true;
            int silent_track = -1;
            // Tracks 0..6 only: the FX track (7) gates on energy extremes by design.
            for (int t = 0; t < 7; t++) {
                PulsarTrackState& ts = state->tracks[t];
                if (!ts.steps[0].gate) continue;
                any_downbeat = true;
                if (!(ts.voice_active || ts.gate_timer > 0.0f)) {
                    fired_all = false;
                    if (silent_track < 0) silent_track = t;
                }
            }
            bool no_wrap = state->loop_count == 0;  // a load is not a bar
            bool ok = window_ok && any_downbeat && fired_all && no_wrap;
            if (!ok) {
                printf("  bpm=%.0f energy=%.2f: wrap_len=%d any=%d silent_track=%d loop_count=%d -- FAIL\n",
                       bpm, e, state->tracks[0].wrap_len, any_downbeat ? 1 : 0,
                       silent_track, state->loop_count);
                all_ok = false;
            }
            orpheus_engine_destroy(engine);
        }
    }
    if (all_ok)
        printf("  every step-0 gate sounds, window adopted, no wrap -- 4 energies x 3 BPMs -- PASS\n");
    return all_ok;
}

// ── Test 2: switching 16-step -> 32-step adopts the longer window ──────────
// The first pass of the new vibe must run all 32 steps. A stale 16-step
// window wraps the two-bar pattern halfway through bar 1.
static bool test_switch_to_longer_vibe_adopts_new_window() {
    printf("\n=== Test: 16->32 switch runs the full first pass ===\n");
    OrpheusEngine* engine = make_start_engine(setup_fixture_dense_fast);  // 16-step
    GraphUnit unit = make_start_unit();

    for (int b = 0; b < blocks_for_steps(3.5); b++)
        unit_process_pulsar(&unit, engine, kBlock, kSampleRate);
    PulsarState* state = engine->pulsar_state;
    bool precondition = state->tracks[0].wrap_len == 16 && state->tracks[0].playhead > 0;
    printf("  precondition: wrap_len=%d playhead=%d -- %s\n",
           state->tracks[0].wrap_len, state->tracks[0].playhead,
           precondition ? "PASS" : "FAIL");

    setup_fixture_blues(engine);  // 32-step
    engine->pulsar_energy.store(1.0f, std::memory_order_relaxed);
    pin_pulsar_rngs(engine);
    trigger_vibe_load(engine);
    unit_process_pulsar(&unit, engine, kBlock, kSampleRate);

    bool adopted = state->tracks[0].wrap_len == 32 && state->tracks[0].playhead == 0;
    printf("  after switch block: wrap_len=%d playhead=%d (want 32, 0) -- %s\n",
           state->tracks[0].wrap_len, state->tracks[0].playhead, adopted ? "PASS" : "FAIL");

    // Run ~18 steps: the playhead must climb past 16 without ever wrapping.
    int max_playhead = 0;
    bool early_wrap = false;
    int prev = state->tracks[0].playhead;
    for (int b = 0; b < blocks_for_steps(18.0); b++) {
        unit_process_pulsar(&unit, engine, kBlock, kSampleRate);
        int ph = state->tracks[0].playhead;
        if (ph < prev && prev < 31) early_wrap = true;
        if (ph > max_playhead) max_playhead = ph;
        prev = ph;
    }
    bool full_pass = !early_wrap && max_playhead >= 17;
    printf("  first pass: max playhead=%d early_wrap=%d (want >=17, no wrap) -- %s\n",
           max_playhead, early_wrap ? 1 : 0, full_pass ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return precondition && adopted && full_pass;
}

// ── Test 3: switching 32-step -> 16-step never reads the stale tail ────────
// Generators only write [0, step_count), so steps[16..31] still hold the
// previous vibe's bar 2. A stale 32-step window plays them verbatim.
static bool test_switch_to_shorter_vibe_never_reads_stale_tail() {
    printf("\n=== Test: 32->16 switch never plays the old vibe's bar 2 ===\n");
    OrpheusEngine* engine = make_start_engine(setup_fixture_blues);  // 32-step
    GraphUnit unit = make_start_unit();

    for (int b = 0; b < blocks_for_steps(3.5); b++)
        unit_process_pulsar(&unit, engine, kBlock, kSampleRate);
    PulsarState* state = engine->pulsar_state;
    bool precondition = state->tracks[0].wrap_len == 32 && state->tracks[0].playhead > 0;
    printf("  precondition: wrap_len=%d playhead=%d -- %s\n",
           state->tracks[0].wrap_len, state->tracks[0].playhead,
           precondition ? "PASS" : "FAIL");

    setup_fixture_dense_fast(engine);  // 16-step
    engine->pulsar_energy.store(1.0f, std::memory_order_relaxed);
    pin_pulsar_rngs(engine);
    trigger_vibe_load(engine);
    unit_process_pulsar(&unit, engine, kBlock, kSampleRate);

    bool adopted = state->tracks[0].wrap_len == 16 && state->tracks[0].playhead == 0;
    printf("  after switch block: wrap_len=%d playhead=%d (want 16, 0) -- %s\n",
           state->tracks[0].wrap_len, state->tracks[0].playhead, adopted ? "PASS" : "FAIL");

    // max_playhead <= 15 alone is one-sided — a frozen sequencer satisfies it.
    // Pair it with liveness: the playhead must actually reach the top of the new
    // window and wrap back, which is also what proves it wraps at 16 not 32.
    int max_playhead = 0;
    bool wrapped = false;
    int prev = state->tracks[0].playhead;
    for (int b = 0; b < blocks_for_steps(17.0); b++) {
        unit_process_pulsar(&unit, engine, kBlock, kSampleRate);
        int ph = state->tracks[0].playhead;
        if (ph > max_playhead) max_playhead = ph;
        if (ph < prev) wrapped = true;
        prev = ph;
    }
    bool stays_inside = max_playhead == 15 && wrapped;
    printf("  first pass: max playhead=%d wrapped=%d (want exactly 15, and a wrap) -- %s\n",
           max_playhead, wrapped ? 1 : 0, stays_inside ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return precondition && adopted && stays_inside;
}

// ── Test 4: the load boundary consumes no chord tick ───────────────────────
// The injected load boundary is not an elapsed 16th, so it must not advance the
// chord clock — otherwise every chord change lands one step early, forever.
//
// This asserts the actual invariant rather than
// `chord_step_counter == playhead % steps_per_chord`, which is NOT a system
// property: steps_per_chord is integer-truncated (step_count / chords_per_bar),
// so with a legal chords_per_bar=3 it does not divide step_count and the two
// clocks legitimately diverge at the first wrap.
static bool test_load_boundary_consumes_no_chord_tick() {
    printf("\n=== Test: the load boundary does not tick the chord clock ===\n");
    OrpheusEngine* engine = make_start_engine(setup_fixture_dense_fast);
    GraphUnit unit = make_start_unit();

    unit_process_pulsar(&unit, engine, kObserveBlock, kSampleRate);
    PulsarState* state = engine->pulsar_state;
    bool untick = state->chord_state.chord_step_counter == 0;
    printf("  after the load block: chord_step_counter=%d (want 0) -- %s\n",
           state->chord_state.chord_step_counter, untick ? "PASS" : "FAIL");

    // One natural boundary must then tick it exactly once.
    for (int b = 0; b < blocks_for_steps(1.0); b++)
        unit_process_pulsar(&unit, engine, kBlock, kSampleRate);
    bool ticks = state->chord_state.chord_step_counter == 1;
    printf("  after one elapsed step: chord_step_counter=%d (want 1) -- %s\n",
           state->chord_state.chord_step_counter, ticks ? "PASS" : "FAIL");

    // The mirror case: a resync armed at a NATURAL boundary (the JAM_INVERTED
    // section re-lock) rides on a real elapsed 16th, so it MUST still tick.
    // Gating the chord clock on resync_pending rather than on the injected
    // boundary silently drops this one and drifts the grid permanently.
    int before = state->chord_state.chord_step_counter;
    state->tracks[0].resync_pending = true;
    for (int b = 0; b < blocks_for_steps(1.0); b++)
        unit_process_pulsar(&unit, engine, kBlock, kSampleRate);
    int after = state->chord_state.chord_step_counter;
    bool relock_ticks = after != before;
    printf("  natural boundary with resync armed: %d -> %d (want a tick) -- %s\n",
           before, after, relock_ticks ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return untick && ticks && relock_ticks;
}

// ── Test 5: a load clears carried-over half-lick phase inversion ───────────
// Load hygiene: phase_inverted survives into the new vibe otherwise, and its
// only consumer is the section-flip re-lock, so a stale one makes the new
// vibe's first section flip re-lock a track that never inverted.
static bool test_load_clears_carried_phase_inversion() {
    printf("\n=== Test: vibe load clears carried-over phase_inverted ===\n");
    OrpheusEngine* engine = make_start_engine(setup_fixture_dense_fast);
    GraphUnit unit = make_start_unit();

    for (int b = 0; b < blocks_for_steps(2.5); b++)
        unit_process_pulsar(&unit, engine, kBlock, kSampleRate);
    PulsarState* state = engine->pulsar_state;
    state->tracks[4].phase_inverted = true;

    setup_fixture_blues(engine);
    engine->pulsar_energy.store(1.0f, std::memory_order_relaxed);
    pin_pulsar_rngs(engine);
    trigger_vibe_load(engine);
    unit_process_pulsar(&unit, engine, kBlock, kSampleRate);

    bool cleared = !state->tracks[4].phase_inverted;
    printf("  phase_inverted after switch: %d (want 0) -- %s\n",
           state->tracks[4].phase_inverted ? 1 : 0, cleared ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return cleared;
}

// ── Test 6: a load while stopped defers the downbeat to the first play block ─
// The `mix <= 0.001f || !playing` early return sits ABOVE the generation check,
// so a vibe swapped while stopped does not load at all — the OLD window stays
// live until playback opens, and only then does the new one engage.
static bool test_stopped_load_fires_downbeat_on_play() {
    printf("\n=== Test: load while stopped fires the downbeat when play opens ===\n");
    OrpheusEngine* engine = make_start_engine(setup_fixture_dense_fast);  // 16-step
    GraphUnit unit = make_start_unit();

    // Establish a live 16-step window with the playhead off the downbeat, so the
    // assertions below cannot be satisfied by a fresh engine's zeroed defaults.
    for (int b = 0; b < blocks_for_steps(3.5); b++)
        unit_process_pulsar(&unit, engine, kBlock, kSampleRate);
    PulsarState* state = engine->pulsar_state;
    int parked = state->tracks[0].playhead;
    bool precondition = state->tracks[0].wrap_len == 16 && parked > 0;
    printf("  precondition: wrap_len=%d playhead=%d -- %s\n",
           state->tracks[0].wrap_len, parked, precondition ? "PASS" : "FAIL");

    engine->pulsar_playing.store(0, std::memory_order_relaxed);
    setup_fixture_blues(engine);  // 32-step
    engine->pulsar_energy.store(1.0f, std::memory_order_relaxed);
    pin_pulsar_rngs(engine);
    trigger_vibe_load(engine);
    for (int b = 0; b < 3; b++) unit_process_pulsar(&unit, engine, kBlock, kSampleRate);

    bool held = state->tracks[0].wrap_len == 16 && state->tracks[0].playhead == parked;
    printf("  while stopped: wrap_len=%d playhead=%d (want 16, %d — old vibe frozen) -- %s\n",
           state->tracks[0].wrap_len, state->tracks[0].playhead, parked,
           held ? "PASS" : "FAIL");

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    unit_process_pulsar(&unit, engine, kObserveBlock, kSampleRate);
    bool fired = state->tracks[0].wrap_len == 32 && state->tracks[0].playhead == 0;
    printf("  first playing block: wrap_len=%d playhead=%d (want 32, 0) -- %s\n",
           state->tracks[0].wrap_len, state->tracks[0].playhead, fired ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return precondition && held && fired;
}

// ── Test 7: a load during a band solo is not ducked by the old solo ────────
// clear_solo_modifiers zeroes the solo TARGETS but not the smoothed _current
// twins the render path actually reads, and their only drain is the per-bar
// slew inside the wrap handler — a full bar into the new vibe, then ~6 bars to
// resolve a deep duck. So the new vibe used to open silent on ducked tracks.
static bool test_load_clears_inflight_solo_duck() {
    printf("\n=== Test: vibe load clears an in-flight solo duck ===\n");
    OrpheusEngine* engine = make_start_engine(setup_fixture_dense_fast, 0.5f);
    GraphUnit unit = make_start_unit();

    for (int b = 0; b < blocks_for_steps(2.5); b++)
        unit_process_pulsar(&unit, engine, kBlock, kSampleRate);
    PulsarState* state = engine->pulsar_state;
    for (int t = 1; t <= 4; t++) {
        state->tracks[t].solo_density_mod_current = -0.9f;
        state->tracks[t].solo_volume_mod_current = -0.6f;
    }

    setup_fixture_dense_fast(engine);
    engine->pulsar_energy.store(0.5f, std::memory_order_relaxed);
    pin_pulsar_rngs(engine);
    trigger_vibe_load(engine);
    unit_process_pulsar(&unit, engine, kObserveBlock, kSampleRate);

    bool drained = true, downbeat_ok = true;
    for (int t = 1; t <= 4; t++) {
        PulsarTrackState& ts = state->tracks[t];
        if (ts.solo_density_mod_current != 0.0f || ts.solo_volume_mod_current != 0.0f)
            drained = false;
        if (ts.steps[0].gate && !(ts.voice_active || ts.gate_timer > 0.0f))
            downbeat_ok = false;
    }
    printf("  smoothed solo mods after load: %s\n", drained ? "cleared -- PASS" : "STILL IN FLIGHT -- FAIL");
    printf("  step-0 gates sounding through the old duck: %s\n",
           downbeat_ok ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return drained && downbeat_ok;
}

// ── Test 8: the downbeat waits for a mix it can actually be heard at ───────
// MIX_GATED (Orpheus) forces mix to 0 on every vibe load and the user dials it
// back up. The `mix <= 0.001f` return sits above the generation check, so the
// downbeat used to be spent on the block where the knob crossed 0.001 -- about
// -60dB. The timeline (not just the boundary) holds until the mix is audible,
// so the vibe still opens ON step 0 rather than several steps in.
static bool test_start_waits_for_an_audible_mix() {
    printf("\n=== Test: the load downbeat waits for an audible mix ===\n");
    // 0.01 is past the mute point but well under the audibility floor.
    OrpheusEngine* engine = make_start_engine(setup_fixture_dense_fast, 1.0f, kBpm, 0.01f);
    GraphUnit unit = make_start_unit();

    // Well inside the hold budget (0.5s): ~0.25s of blocks.
    const int held_blocks = static_cast<int>(0.25f * kSampleRate) / kBlock;
    for (int b = 0; b < held_blocks; b++)
        unit_process_pulsar(&unit, engine, kBlock, kSampleRate);
    PulsarState* state = engine->pulsar_state;

    // wrap_len is the witness: load_vibe zeroes it and only an advance adopts a
    // window, so 0 means no boundary has fired and the timeline really is held.
    bool held = state->tracks[0].wrap_len == 0 && state->tracks[0].playhead == 0;
    printf("  inaudible mix for %d blocks: wrap_len=%d playhead=%d (want 0, 0) -- %s\n",
           held_blocks, state->tracks[0].wrap_len, state->tracks[0].playhead,
           held ? "PASS" : "FAIL");

    engine->pulsar_mix.store(0.6f, std::memory_order_relaxed);
    unit_process_pulsar(&unit, engine, kObserveBlock, kSampleRate);

    bool opened = state->tracks[0].wrap_len == 16 && state->tracks[0].playhead == 0;
    printf("  first audible block: wrap_len=%d playhead=%d (want 16, 0) -- %s\n",
           state->tracks[0].wrap_len, state->tracks[0].playhead, opened ? "PASS" : "FAIL");

    bool any = false, fired = true;
    for (int t = 0; t < 7; t++) {
        PulsarTrackState& ts = state->tracks[t];
        if (!ts.steps[0].gate) continue;
        any = true;
        if (!(ts.voice_active || ts.gate_timer > 0.0f)) fired = false;
    }
    printf("  step-0 gates sounding on the first audible block: %s\n",
           (any && fired) ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return held && opened && any && fired;
}

// ── Test 9: the audibility hold is bounded ────────────────────────────────
// A mix parked between the mute point and the floor is a visible knob position.
// It must not buy permanent silence -- the downbeat fires anyway once the hold
// budget runs out, still on step 0, just quietly.
static bool test_start_hold_is_bounded() {
    printf("\n=== Test: a mix parked below the floor still starts ===\n");
    OrpheusEngine* engine = make_start_engine(setup_fixture_dense_fast, 1.0f, kBpm, 0.01f);
    GraphUnit unit = make_start_unit();

    // Past the 0.5s budget, with the mix never rising.
    const int blocks = static_cast<int>(0.55f * kSampleRate) / kBlock;
    for (int b = 0; b < blocks; b++)
        unit_process_pulsar(&unit, engine, kBlock, kSampleRate);
    PulsarState* state = engine->pulsar_state;

    // The accumulator was frozen for the whole hold, so it restarts from 0 and
    // the next natural boundary is a full step away -- the playhead is still on
    // the downbeat, which is the point: bounded does not mean mid-pattern.
    bool started = state->tracks[0].wrap_len == 16 && state->tracks[0].playhead == 0;
    printf("  after %d blocks at mix=0.01: wrap_len=%d playhead=%d (want 16, 0) -- %s\n",
           blocks, state->tracks[0].wrap_len, state->tracks[0].playhead,
           started ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return started;
}

bool run_pulsar_start_tests() {
    printf("\n════════ PULSAR START-SEQUENCE TESTS ════════\n");
    // Restore the process-global stmlib RNG on exit: pin_pulsar_rngs() reseeds it
    // per engine, and later suites inherit whatever state they are handed.
    const uint32_t saved_rng = stmlib::Random::state();
    int pass = 0, fail = 0;
    auto tally = [&](bool ok) { ok ? pass++ : fail++; };
    tally(test_start_engages_grid_at_load());
    tally(test_switch_to_longer_vibe_adopts_new_window());
    tally(test_switch_to_shorter_vibe_never_reads_stale_tail());
    tally(test_load_boundary_consumes_no_chord_tick());
    tally(test_load_clears_carried_phase_inversion());
    tally(test_stopped_load_fires_downbeat_on_play());
    tally(test_load_clears_inflight_solo_duck());
    tally(test_start_waits_for_an_audible_mix());
    tally(test_start_hold_is_bounded());
    printf("\nPulsar start: %d/%d passed\n", pass, pass + fail);
    stmlib::Random::Seed(saved_rng);
    TEST_SUITE_RETURN(pass, fail);
}
