// Opt-in per-track pitch rise: each note-on climbs by `pitch_rise` cents over
// `pitch_rise_time` seconds, then holds. Twin engines differing only in the rise atomics
// make the per-block difference of pulsar_track_note_debug the offset.
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/orpheus_graph.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

static constexpr int kBlockFrames = 512;
static constexpr int kRiseTrack = 4;     // melodic, no ducking
static constexpr int kRiseBlocks = 700;  // about 7.5 s at 48 kHz

struct RiseSetup {
    float rise_edm = 0.0f;
    float rise_space = 0.0f;
    float time = 1.0f;
    float vibrato_depth = 0.0f;
    int engine_edm = 8;
    int engine_space = 8;
    float energy = 0.8f;
};

struct RiseRun {
    std::vector<float> note;
    std::vector<float> progress;   // pitch_rise_progress after each block
};

static RiseRun run_rise(const RiseSetup& s) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    solo_track(engine, kRiseTrack);
    engine->pulsar_energy.store(s.energy, std::memory_order_relaxed);
    engine->pulsar_track_engine_edm[kRiseTrack].store(s.engine_edm, std::memory_order_relaxed);
    engine->pulsar_track_engine_space[kRiseTrack].store(s.engine_space, std::memory_order_relaxed);
    engine->pulsar_track_density_override[kRiseTrack].store(0.5f, std::memory_order_relaxed);
    // Long holds: 8 steps is two seconds at 60 bpm.
    engine->pulsar_track_hold_probability[kRiseTrack].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_track_hold_probability_space[kRiseTrack].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_track_hold_length_min[kRiseTrack].store(8, std::memory_order_relaxed);
    engine->pulsar_track_hold_length_min_space[kRiseTrack].store(8, std::memory_order_relaxed);
    engine->pulsar_track_hold_length_max[kRiseTrack].store(8, std::memory_order_relaxed);
    engine->pulsar_track_hold_length_max_space[kRiseTrack].store(8, std::memory_order_relaxed);
    engine->pulsar_track_pitch_rise[kRiseTrack].store(s.rise_edm, std::memory_order_relaxed);
    engine->pulsar_track_pitch_rise_space[kRiseTrack].store(s.rise_space, std::memory_order_relaxed);
    engine->pulsar_track_pitch_rise_time[kRiseTrack].store(s.time, std::memory_order_relaxed);
    engine->pulsar_track_pitch_rise_time_space[kRiseTrack].store(s.time, std::memory_order_relaxed);
    engine->pulsar_track_vibrato_depth[kRiseTrack].store(s.vibrato_depth, std::memory_order_relaxed);
    engine->pulsar_track_vibrato_depth_space[kRiseTrack].store(s.vibrato_depth, std::memory_order_relaxed);
    engine->clock_bpm.store(60.0f, std::memory_order_relaxed);
    trigger_vibe_load(engine);

    RiseRun r;
    for (int i = 0; i < kRiseBlocks; i++) {
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        const PulsarTrackState& ts = engine->pulsar_state->tracks[kRiseTrack];
        r.note.push_back(engine->pulsar_track_note_debug[kRiseTrack].load(std::memory_order_relaxed));
        r.progress.push_back(ts.pitch_rise_progress);
    }
    orpheus_engine_destroy(engine);
    return r;
}

// Blocks where the progress ramp was reset, i.e. a note-on landed. A reset block ends
// near 0, so it is the one where progress dropped; the rise is 1 s so it never wraps.
static std::vector<int> note_on_blocks(const RiseRun& r) {
    std::vector<int> on;
    for (size_t i = 1; i < r.progress.size(); i++)
        if (r.progress[i] < r.progress[i - 1]) on.push_back(static_cast<int>(i));
    return on;
}

static float delta(const RiseRun& base, const RiseRun& run, int i) { return run.note[i] - base.note[i]; }

static bool test_rise_unpushed_defaults() {
    printf("\n=== Test: unpushed pitch rise atomics are off with a 2 s time ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool ok = true;
    for (int t = 0; t < 8; t++) {
        ok &= (engine->pulsar_track_pitch_rise[t].load() == 0.0f);
        ok &= (engine->pulsar_track_pitch_rise_space[t].load() == 0.0f);
        ok &= (engine->pulsar_track_pitch_rise_time[t].load() == 2.0f);
        ok &= (engine->pulsar_track_pitch_rise_time_space[t].load() == 2.0f);
    }
    printf("Pitch rise defaults: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_rise_zero_leaves_the_note_alone() {
    printf("\n=== Test: rise 0 leaves a held note constant and the state untouched ===\n");
    RiseSetup a, b;
    a.time = 8.0f;
    b.time = 0.1f;
    const RiseRun ra = run_rise(a);
    const RiseRun rb = run_rise(b);
    float max_progress = 0.0f;
    for (float p : ra.progress) max_progress = std::max(max_progress, p);
    int best = 0, run = 1;
    for (size_t i = 1; i < ra.note.size(); i++) {
        run = (ra.note[i] == ra.note[i - 1]) ? run + 1 : 1;
        best = std::max(best, run);
    }
    printf("  time-independent=%d longest constant plateau=%d blocks max_progress=%.3f\n",
           (ra.note == rb.note) ? 1 : 0, best, max_progress);
    bool ok = (ra.note == rb.note) && best >= 120 && max_progress == 0.0f;
    printf("Pitch rise off: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_rise_climbs_then_holds() {
    printf("\n=== Test: 100 cents over 1 s starts flat, is +0.5 at half time, then holds at +1.0 ===\n");
    RiseSetup off, on;
    on.rise_edm = on.rise_space = 100.0f;
    const RiseRun base = run_rise(off);
    const RiseRun rise = run_rise(on);
    const std::vector<int> ons = note_on_blocks(rise);
    // The longest gap between note-ons is one long held note.
    int start = -1, length = 0;
    for (size_t i = 1; i < ons.size(); i++)
        if (ons[i] - ons[i - 1] > length) { length = ons[i] - ons[i - 1]; start = ons[i - 1]; }
    printf("  note-ons=%zu longest hold starts at block %d, %d blocks\n", ons.size(), start, length);
    bool ok = (ons.size() >= 3) && length >= 140;
    if (ok) {
        const float first = std::fabs(delta(base, rise, start));
        const int half = start + 47;        // 47 blocks = 0.50 s
        const float mid = delta(base, rise, half);
        // 94 blocks = 1.00 s; from there to the end of the hold the pitch stays raised.
        float lo = 9.0f, hi = -9.0f;
        for (int i = start + 95; i < start + length; i++) {
            lo = std::min(lo, delta(base, rise, i));
            hi = std::max(hi, delta(base, rise, i));
        }
        printf("  first=%.4f mid=%.4f top range %.4f..%.4f\n", first, mid, lo, hi);
        ok &= (first < 0.05f) && (mid > 0.45f && mid < 0.55f);
        ok &= (lo > 0.999f) && (hi < 1.0001f);
    }
    printf("Pitch rise climb: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_rise_restarts_on_each_note() {
    printf("\n=== Test: each note-on restarts the climb from 0 ===\n");
    RiseSetup off, on;
    on.rise_edm = on.rise_space = 100.0f;
    const RiseRun base = run_rise(off);
    const RiseRun rise = run_rise(on);
    const std::vector<int> ons = note_on_blocks(rise);
    bool ok = ons.size() >= 3;
    float worst_start = 0.0f;
    int reached_top = 0;
    for (size_t k = 0; k < ons.size(); k++) {
        worst_start = std::max(worst_start, std::fabs(delta(base, rise, ons[k])));
        if (k + 1 < ons.size() && ons[k + 1] - ons[k] > 100 &&
            delta(base, rise, ons[k + 1] - 1) > 0.999f) reached_top++;
    }
    printf("  note-ons=%zu worst deviation on a note-on block=%.4f, holds that reached +1.0: %d\n",
           ons.size(), worst_start, reached_top);
    ok &= (worst_start < 0.05f) && (reached_top >= 1);
    printf("Pitch rise restart: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// Max deviation from a rise-0 twin (the twin keeps whatever else the setup has).
static float max_rise_deviation(const RiseSetup& s) {
    RiseSetup off = s;
    off.rise_edm = off.rise_space = 0.0f;
    const RiseRun base = run_rise(off);
    const RiseRun run = run_rise(s);
    float peak = 0.0f;
    for (size_t i = 0; i < run.note.size(); i++)
        peak = std::max(peak, std::fabs(delta(base, run, static_cast<int>(i))));
    return peak;
}

// The slot rule is PULSAR_PICK's. Low energy hands the track to the SPACE engine.
static bool test_rise_follows_the_active_slot() {
    printf("\n=== Test: the active engine slot's rise applies ===\n");
    RiseSetup s;
    s.engine_edm = 8;
    s.engine_space = 14;
    s.rise_space = 100.0f;
    s.energy = 0.2f;   // SPACE active
    const float space_on_space = max_rise_deviation(s);
    s.energy = 0.8f;   // EDM active, only SPACE has a rise
    const float edm_on_space = max_rise_deviation(s);
    s.rise_edm = 100.0f;
    s.rise_space = 0.0f;
    const float edm_on_edm = max_rise_deviation(s);
    s.energy = 0.2f;
    const float space_on_edm = max_rise_deviation(s);
    printf("  SPACE active: space rise dev=%.4f, edm rise dev=%.4f\n", space_on_space, space_on_edm);
    printf("  EDM active:   edm rise dev=%.4f, space rise dev=%.4f\n", edm_on_edm, edm_on_space);
    bool ok = (space_on_space > 0.9f) && (space_on_edm == 0.0f);
    ok &= (edm_on_edm > 0.9f) && (edm_on_space == 0.0f);
    printf("Pitch rise slots: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// Rise and vibrato are separate features; with both on, the offsets simply add.
static bool test_rise_adds_to_vibrato() {
    printf("\n=== Test: rise and vibrato offsets add ===\n");
    RiseSetup off, rise_only, vib_only, both;
    rise_only.rise_edm = rise_only.rise_space = 100.0f;
    vib_only.vibrato_depth = 50.0f;
    both.rise_edm = both.rise_space = 100.0f;
    both.vibrato_depth = 50.0f;
    const RiseRun base = run_rise(off);
    const RiseRun r = run_rise(rise_only);
    const RiseRun v = run_rise(vib_only);
    const RiseRun b = run_rise(both);
    float worst = 0.0f, vib_peak = 0.0f;
    for (size_t i = 0; i < b.note.size(); i++) {
        const int k = static_cast<int>(i);
        worst = std::max(worst, std::fabs(delta(base, b, k) - (delta(base, r, k) + delta(base, v, k))));
        vib_peak = std::max(vib_peak, std::fabs(delta(base, v, k)));
    }
    printf("  worst |both - (rise + vibrato)|=%.6f, vibrato-only peak=%.4f\n", worst, vib_peak);
    bool ok = (worst < 1e-4f) && (vib_peak > 0.3f);
    printf("Pitch rise plus vibrato: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

bool run_pulsar_pitch_rise_tests() {
    printf("\n=== Pulsar Pitch Rise Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    if (test_rise_unpushed_defaults()) suite_pass++; else suite_fail++;
    if (test_rise_zero_leaves_the_note_alone()) suite_pass++; else suite_fail++;
    if (test_rise_climbs_then_holds()) suite_pass++; else suite_fail++;
    if (test_rise_restarts_on_each_note()) suite_pass++; else suite_fail++;
    if (test_rise_follows_the_active_slot()) suite_pass++; else suite_fail++;
    if (test_rise_adds_to_vibrato()) suite_pass++; else suite_fail++;
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
