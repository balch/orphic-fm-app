// Opt-in per-track harmonics rise: each note-on adds up to `harmonics_rise` to the track's
// harmonics over `harmonics_rise_time` seconds, then holds. Read from the per-block
// harmonics debug publish, with the harmonics pinned so nothing else moves it.
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

struct HRiseSetup {
    float rise_edm = 0.0f;
    float rise_space = 0.0f;
    float time = 1.0f;
    float pinned = 0.2f;
    int engine_edm = 8;
    int engine_space = 8;
    float energy = 0.8f;
};

struct HRiseRun {
    std::vector<float> harmonics;
    std::vector<float> progress;   // harmonics_rise_progress after each block
};

static HRiseRun run_hrise(const HRiseSetup& s) {
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
    engine->pulsar_track_harmonics[kRiseTrack].store(s.pinned, std::memory_order_relaxed);
    engine->pulsar_track_harmonics_space[kRiseTrack].store(s.pinned, std::memory_order_relaxed);
    engine->pulsar_track_pin_harmonics[kRiseTrack].store(1, std::memory_order_relaxed);
    engine->pulsar_track_pin_harmonics_space[kRiseTrack].store(1, std::memory_order_relaxed);
    engine->pulsar_track_harmonics_rise[kRiseTrack].store(s.rise_edm, std::memory_order_relaxed);
    engine->pulsar_track_harmonics_rise_space[kRiseTrack].store(s.rise_space, std::memory_order_relaxed);
    engine->pulsar_track_harmonics_rise_time[kRiseTrack].store(s.time, std::memory_order_relaxed);
    engine->pulsar_track_harmonics_rise_time_space[kRiseTrack].store(s.time, std::memory_order_relaxed);
    engine->clock_bpm.store(60.0f, std::memory_order_relaxed);
    trigger_vibe_load(engine);

    HRiseRun r;
    for (int i = 0; i < kRiseBlocks; i++) {
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        const PulsarTrackState& ts = engine->pulsar_state->tracks[kRiseTrack];
        r.harmonics.push_back(engine->pulsar_track_mod_harmonics_debug[kRiseTrack].load(std::memory_order_relaxed));
        r.progress.push_back(ts.harmonics_rise_progress);
    }
    orpheus_engine_destroy(engine);
    return r;
}

// Blocks where the progress ramp was reset, i.e. a note-on landed. The rise is 1 s so it
// never wraps; a reset block is the one where progress dropped.
static std::vector<int> hrise_note_ons(const HRiseRun& r) {
    std::vector<int> on;
    for (size_t i = 1; i < r.progress.size(); i++)
        if (r.progress[i] < r.progress[i - 1]) on.push_back(static_cast<int>(i));
    return on;
}

// Start block and length of the longest gap between note-ons.
static bool longest_hold(const std::vector<int>& ons, int* start, int* length) {
    *start = -1;
    *length = 0;
    for (size_t i = 1; i < ons.size(); i++)
        if (ons[i] - ons[i - 1] > *length) { *length = ons[i] - ons[i - 1]; *start = ons[i - 1]; }
    return ons.size() >= 3 && *length >= 140;
}

static bool test_hrise_unpushed_defaults() {
    printf("\n=== Test: unpushed harmonics rise atomics are off with a 2 s time ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool ok = true;
    for (int t = 0; t < 8; t++) {
        ok &= (engine->pulsar_track_harmonics_rise[t].load() == 0.0f);
        ok &= (engine->pulsar_track_harmonics_rise_space[t].load() == 0.0f);
        ok &= (engine->pulsar_track_harmonics_rise_time[t].load() == 2.0f);
        ok &= (engine->pulsar_track_harmonics_rise_time_space[t].load() == 2.0f);
    }
    printf("Harmonics rise defaults: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_hrise_zero_leaves_harmonics_alone() {
    printf("\n=== Test: rise 0 leaves a held note's harmonics constant and the state untouched ===\n");
    HRiseSetup a, b;
    a.time = 8.0f;
    b.time = 0.1f;
    const HRiseRun ra = run_hrise(a);
    const HRiseRun rb = run_hrise(b);
    float max_progress = 0.0f;
    for (float p : ra.progress) max_progress = std::max(max_progress, p);
    int best = 0, run = 1;
    for (size_t i = 1; i < ra.harmonics.size(); i++) {
        run = (ra.harmonics[i] == ra.harmonics[i - 1]) ? run + 1 : 1;
        best = std::max(best, run);
    }
    printf("  time-independent=%d longest plateau=%d blocks max_progress=%.3f level=%.4f\n",
           (ra.harmonics == rb.harmonics) ? 1 : 0, best, max_progress, ra.harmonics[300]);
    bool ok = (ra.harmonics == rb.harmonics) && best >= 600 && max_progress == 0.0f
           && std::fabs(ra.harmonics[300] - 0.2f) < 1e-4f;
    printf("Harmonics rise off: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_hrise_climbs_then_holds() {
    printf("\n=== Test: +0.4 over 1 s from a pinned 0.2 continues the last note, is 0.4 at 0.5 s, then holds at 0.6 ===\n");
    HRiseSetup on;
    on.rise_edm = on.rise_space = 0.4f;
    const HRiseRun rise = run_hrise(on);
    int start, length;
    bool ok = longest_hold(hrise_note_ons(rise), &start, &length);
    printf("  longest hold starts at block %d, %d blocks\n", start, length);
    if (ok) {
        // The note-on block carries on from the last note; the return glide is over long before 0.5 s.
        const float step = std::fabs(rise.harmonics[start] - rise.harmonics[start - 1]);
        const float mid = rise.harmonics[start + 47];   // 47 blocks = 0.50 s
        float lo = 9.0f, hi = -9.0f;
        for (int i = start + 95; i < start + length; i++) {
            lo = std::min(lo, rise.harmonics[i]);
            hi = std::max(hi, rise.harmonics[i]);
        }
        printf("  note-on step=%.4f mid=%.4f top range %.4f..%.4f\n", step, mid, lo, hi);
        ok &= step < 0.07f;
        ok &= std::fabs(mid - 0.4f) < 0.01f;
        ok &= (lo > 0.5995f) && (hi < 0.6005f);
    }
    printf("Harmonics rise climb: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_hrise_negative_moves_down() {
    printf("\n=== Test: -0.3 over 1 s from a pinned 0.5 ends at 0.2 ===\n");
    HRiseSetup on;
    on.pinned = 0.5f;
    on.rise_edm = on.rise_space = -0.3f;
    const HRiseRun rise = run_hrise(on);
    int start, length;
    bool ok = longest_hold(hrise_note_ons(rise), &start, &length);
    if (ok) {
        const float step = std::fabs(rise.harmonics[start] - rise.harmonics[start - 1]);
        const float mid = rise.harmonics[start + 47];
        const float end = rise.harmonics[start + length - 1];
        printf("  note-on step=%.4f mid=%.4f end=%.4f\n", step, mid, end);
        ok &= step < 0.07f;
        ok &= std::fabs(mid - 0.35f) < 0.01f;
        ok &= std::fabs(end - 0.2f) < 0.0005f;
    }
    printf("Harmonics rise negative: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// 80 ms of 512-frame blocks at 48 kHz, rounded up: the return glide is over by then.
static constexpr int kReturnBlocks = 8;

static bool test_hrise_restarts_on_each_note() {
    printf("\n=== Test: each note-on glides back onto a restarted rise from the pinned value ===\n");
    HRiseSetup on;
    on.rise_edm = on.rise_space = 0.4f;
    const HRiseRun rise = run_hrise(on);
    const std::vector<int> ons = hrise_note_ons(rise);
    bool ok = ons.size() >= 3;
    float worst = 0.0f;
    int reached_top = 0;
    for (size_t k = 0; k < ons.size(); k++) {
        // Past the glide the note is on the fresh ramp: pinned plus rise times elapsed.
        const int at = ons[k] + kReturnBlocks;
        if (k + 1 < ons.size() && at >= ons[k + 1]) continue;
        if (at >= static_cast<int>(rise.harmonics.size())) continue;
        const float ramp = 0.2f + 0.4f * std::min(1.0f, (kReturnBlocks + 1) * kBlockFrames / 48000.0f);
        worst = std::max(worst, std::fabs(rise.harmonics[at] - ramp));
        if (k + 1 < ons.size() && ons[k + 1] - ons[k] > 100 &&
            rise.harmonics[ons[k + 1] - 1] > 0.5995f) reached_top++;
    }
    printf("  note-ons=%zu worst deviation from the fresh ramp after the glide=%.4f, holds that reached 0.6: %d\n",
           ons.size(), worst, reached_top);
    ok &= (worst < 0.01f) && (reached_top >= 1);
    printf("Harmonics rise restart: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_hrise_note_on_never_jumps() {
    printf("\n=== Test: a note-on mid-sound glides harmonics back instead of jumping (no click) ===\n");
    HRiseSetup on;
    on.rise_edm = on.rise_space = 0.4f;
    const HRiseRun rise = run_hrise(on);
    // The old reset dropped a held 0.6 to 0.2 in one block. The glide spreads 0.4 over
    // about 7.5 blocks, about 0.053 each; the climb itself moves under 0.005 a block.
    float worst = 0.0f;
    for (size_t i = 1; i < rise.harmonics.size(); i++)
        worst = std::max(worst, std::fabs(rise.harmonics[i] - rise.harmonics[i - 1]));
    printf("  largest block-to-block harmonics step=%.4f\n", worst);
    const bool ok = hrise_note_ons(rise).size() >= 3 && worst < 0.07f;
    printf("Harmonics rise no jump: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// The slot rule is PULSAR_PICK's. Low energy hands the track to the SPACE engine.
static float peak_rise(const HRiseSetup& s) {
    const HRiseRun run = run_hrise(s);
    float peak = 0.0f;
    for (float h : run.harmonics) peak = std::max(peak, std::fabs(h - s.pinned));
    return peak;
}

static bool test_hrise_follows_the_active_slot() {
    printf("\n=== Test: the active engine slot's rise applies ===\n");
    HRiseSetup s;
    s.engine_edm = 8;
    s.engine_space = 14;
    s.rise_space = 0.4f;
    s.energy = 0.2f;   // SPACE active
    const float space_on_space = peak_rise(s);
    s.energy = 0.8f;   // EDM active, only SPACE has a rise
    const float edm_on_space = peak_rise(s);
    s.rise_edm = 0.4f;
    s.rise_space = 0.0f;
    const float edm_on_edm = peak_rise(s);
    s.energy = 0.2f;
    const float space_on_edm = peak_rise(s);
    printf("  SPACE active: space rise peak=%.4f, edm rise peak=%.4f\n", space_on_space, space_on_edm);
    printf("  EDM active:   edm rise peak=%.4f, space rise peak=%.4f\n", edm_on_edm, edm_on_space);
    bool ok = (space_on_space > 0.35f) && (space_on_edm < 1e-4f);
    ok &= (edm_on_edm > 0.35f) && (edm_on_space < 1e-4f);
    printf("Harmonics rise slots: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

bool run_pulsar_harmonics_rise_tests() {
    printf("\n=== Pulsar Harmonics Rise Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    if (test_hrise_unpushed_defaults()) suite_pass++; else suite_fail++;
    if (test_hrise_zero_leaves_harmonics_alone()) suite_pass++; else suite_fail++;
    if (test_hrise_climbs_then_holds()) suite_pass++; else suite_fail++;
    if (test_hrise_negative_moves_down()) suite_pass++; else suite_fail++;
    if (test_hrise_restarts_on_each_note()) suite_pass++; else suite_fail++;
    if (test_hrise_note_on_never_jumps()) suite_pass++; else suite_fail++;
    if (test_hrise_follows_the_active_slot()) suite_pass++; else suite_fail++;
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
