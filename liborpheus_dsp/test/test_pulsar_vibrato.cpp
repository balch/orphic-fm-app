// Opt-in per-track pitch vibrato. Each case runs twin engines that differ only in the
// vibrato atomics, so the per-block difference of pulsar_track_note_debug IS the offset.
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/orpheus_graph.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

static constexpr int kBlockFrames = 512;
static constexpr int kVibTrack = 4;     // melodic, no ducking
static constexpr int kVibBlocks = 700;  // about 7.5 s at 48 kHz

struct VibratoSetup {
    float depth_edm = 0.0f;
    float depth_space = 0.0f;
    float rate = 5.0f;
    int engine_edm = 8;
    int engine_space = 8;
    float energy = 0.8f;
};

struct VibratoRun {
    std::vector<float> note;
    std::vector<float> onset;   // vibrato_onset after each block
    std::vector<float> phase;
};

static VibratoRun run_vibrato(const VibratoSetup& s) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    solo_track(engine, kVibTrack);
    engine->pulsar_energy.store(s.energy, std::memory_order_relaxed);
    engine->pulsar_track_engine_edm[kVibTrack].store(s.engine_edm, std::memory_order_relaxed);
    engine->pulsar_track_engine_space[kVibTrack].store(s.engine_space, std::memory_order_relaxed);
    engine->pulsar_track_density_override[kVibTrack].store(0.5f, std::memory_order_relaxed);
    // Long holds: 8 steps is a full second at 120 bpm.
    engine->pulsar_track_hold_probability[kVibTrack].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_track_hold_probability_space[kVibTrack].store(1.0f, std::memory_order_relaxed);
    engine->pulsar_track_hold_length_min[kVibTrack].store(8, std::memory_order_relaxed);
    engine->pulsar_track_hold_length_min_space[kVibTrack].store(8, std::memory_order_relaxed);
    engine->pulsar_track_hold_length_max[kVibTrack].store(8, std::memory_order_relaxed);
    engine->pulsar_track_hold_length_max_space[kVibTrack].store(8, std::memory_order_relaxed);
    engine->pulsar_track_vibrato_depth[kVibTrack].store(s.depth_edm, std::memory_order_relaxed);
    engine->pulsar_track_vibrato_depth_space[kVibTrack].store(s.depth_space, std::memory_order_relaxed);
    engine->pulsar_track_vibrato_rate[kVibTrack].store(s.rate, std::memory_order_relaxed);
    engine->pulsar_track_vibrato_rate_space[kVibTrack].store(s.rate, std::memory_order_relaxed);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);
    trigger_vibe_load(engine);

    VibratoRun r;
    for (int i = 0; i < kVibBlocks; i++) {
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        const PulsarTrackState& ts = engine->pulsar_state->tracks[kVibTrack];
        r.note.push_back(engine->pulsar_track_note_debug[kVibTrack].load(std::memory_order_relaxed));
        r.onset.push_back(ts.vibrato_onset);
        r.phase.push_back(ts.vibrato_phase);
    }
    orpheus_engine_destroy(engine);
    return r;
}

// Blocks where the onset ramp was reset, i.e. a note-on landed.
static std::vector<int> note_on_blocks(const VibratoRun& r) {
    std::vector<int> on;
    for (size_t i = 1; i < r.onset.size(); i++)
        if (r.onset[i] < r.onset[i - 1]) on.push_back(static_cast<int>(i));
    return on;
}

struct HoldWindow { int start = -1; int length = 0; };

// The longest gap between two consecutive note-ons: one held note.
static HoldWindow longest_hold(const std::vector<int>& on) {
    HoldWindow w;
    for (size_t i = 1; i < on.size(); i++) {
        const int len = on[i] - on[i - 1];
        if (len > w.length) { w.start = on[i - 1]; w.length = len; }
    }
    return w;
}

static bool test_vibrato_depth_zero_leaves_the_note_alone() {
    printf("\n=== Test: depth 0 leaves a held note constant and the state untouched ===\n");
    VibratoSetup a, b;
    a.rate = 12.0f;
    b.rate = 0.5f;
    const VibratoRun ra = run_vibrato(a);
    const VibratoRun rb = run_vibrato(b);
    bool same = (ra.note == rb.note);
    float max_phase = 0.0f, max_onset = 0.0f;
    for (float p : ra.phase) max_phase = std::max(max_phase, p);
    for (float o : ra.onset) max_onset = std::max(max_onset, o);

    // A held note: the note-on detector needs vibrato, so find the plateau directly.
    int best = 0, run = 1;
    for (size_t i = 1; i < ra.note.size(); i++) {
        run = (ra.note[i] == ra.note[i - 1]) ? run + 1 : 1;
        best = std::max(best, run);
    }
    printf("  rate-independent=%d longest constant plateau=%d blocks max_phase=%.3f max_onset=%.3f\n",
           same ? 1 : 0, best, max_phase, max_onset);
    bool ok = same && best >= 60 && max_phase == 0.0f && max_onset == 0.0f;
    printf("Vibrato off: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_vibrato_wavers_after_a_delayed_onset() {
    printf("\n=== Test: 50 cents at 5 Hz is silent at the note-on and swings 0.5 semitone later ===\n");
    VibratoSetup off;
    VibratoSetup on;
    on.depth_edm = on.depth_space = 50.0f;
    const VibratoRun base = run_vibrato(off);
    const VibratoRun vib = run_vibrato(on);
    const std::vector<int> ons = note_on_blocks(vib);
    const HoldWindow hold = longest_hold(ons);
    printf("  note-ons=%zu longest hold starts at block %d, %d blocks\n",
           ons.size(), hold.start, hold.length);
    bool ok = (ons.size() >= 3) && hold.length >= 60;
    if (ok) {
        const float first = std::fabs(vib.note[hold.start] - base.note[hold.start]);
        // Onset is 0.3 s = 29 blocks; a quarter period is 0.05 s, so by 40 blocks the
        // ramp is complete and a 5 Hz sine has crossed a peak.
        float peak = 0.0f;
        for (int i = hold.start + 40; i < hold.start + hold.length; i++)
            peak = std::max(peak, std::fabs(vib.note[i] - base.note[i]));
        // The base note is constant over the hold, so the deviation is the offset.
        printf("  first-block deviation=%.4f, peak after onset=%.4f semitones\n", first, peak);
        ok &= (first < 0.05f) && (peak > 0.45f) && (peak <= 0.5001f);
    }
    printf("Vibrato swing: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_vibrato_onset_restarts_on_each_note() {
    printf("\n=== Test: each note-on restarts the onset ramp and phase ===\n");
    VibratoSetup on;
    on.depth_edm = on.depth_space = 50.0f;
    const VibratoRun vib = run_vibrato(on);
    const std::vector<int> ons = note_on_blocks(vib);
    bool ok = ons.size() >= 3;
    float max_onset_at_on = 0.0f;
    float full = 0.0f;
    for (float o : vib.onset) full = std::max(full, o);
    for (int b : ons) max_onset_at_on = std::max(max_onset_at_on, vib.onset[b]);
    printf("  note-ons=%zu max onset on a note-on block=%.3f, max onset overall=%.3f\n",
           ons.size(), max_onset_at_on, full);
    ok &= (max_onset_at_on < 0.1f) && (full >= 0.999f);
    printf("Vibrato restart: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// Max deviation from a depth-0 twin over the second half, where onsets have settled.
static float max_deviation(const VibratoSetup& s) {
    const VibratoSetup off = [&] { VibratoSetup o = s; o.depth_edm = o.depth_space = 0.0f; return o; }();
    const VibratoRun base = run_vibrato(off);
    const VibratoRun vib = run_vibrato(s);
    float peak = 0.0f;
    for (size_t i = 0; i < vib.note.size(); i++)
        peak = std::max(peak, std::fabs(vib.note[i] - base.note[i]));
    return peak;
}

// The slot rule is PULSAR_PICK's: the EDM atomics apply while the EDM engine is active,
// the SPACE ones otherwise. Low energy hands the track to the SPACE engine.
static bool test_vibrato_follows_the_active_slot() {
    printf("\n=== Test: the active engine slot's depth applies ===\n");
    VibratoSetup s;
    s.engine_edm = 8;
    s.engine_space = 14;
    s.depth_space = 50.0f;

    s.energy = 0.2f;   // SPACE active
    const float space_active_space_depth = max_deviation(s);
    s.energy = 0.8f;   // EDM active, only SPACE has depth
    const float edm_active_space_depth = max_deviation(s);

    s.depth_edm = 50.0f;
    s.depth_space = 0.0f;
    s.energy = 0.8f;
    const float edm_active_edm_depth = max_deviation(s);
    s.energy = 0.2f;
    const float space_active_edm_depth = max_deviation(s);

    printf("  SPACE active: space-depth dev=%.4f, edm-depth dev=%.4f\n",
           space_active_space_depth, space_active_edm_depth);
    printf("  EDM active:   edm-depth dev=%.4f, space-depth dev=%.4f\n",
           edm_active_edm_depth, edm_active_space_depth);
    bool ok = (space_active_space_depth > 0.3f) && (space_active_edm_depth == 0.0f);
    ok &= (edm_active_edm_depth > 0.3f) && (edm_active_space_depth == 0.0f);
    printf("Vibrato slots: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_vibrato_unpushed_defaults() {
    printf("\n=== Test: unpushed vibrato atomics are off at 5.5 Hz ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool ok = true;
    for (int t = 0; t < 8; t++) {
        ok &= (engine->pulsar_track_vibrato_depth[t].load() == 0.0f);
        ok &= (engine->pulsar_track_vibrato_depth_space[t].load() == 0.0f);
        ok &= (engine->pulsar_track_vibrato_rate[t].load() == 5.5f);
        ok &= (engine->pulsar_track_vibrato_rate_space[t].load() == 5.5f);
    }
    printf("Vibrato defaults: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

bool run_pulsar_vibrato_tests() {
    printf("\n=== Pulsar Vibrato Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    if (test_vibrato_unpushed_defaults()) suite_pass++; else suite_fail++;
    if (test_vibrato_depth_zero_leaves_the_note_alone()) suite_pass++; else suite_fail++;
    if (test_vibrato_wavers_after_a_delayed_onset()) suite_pass++; else suite_fail++;
    if (test_vibrato_onset_restarts_on_each_note()) suite_pass++; else suite_fail++;
    if (test_vibrato_follows_the_active_slot()) suite_pass++; else suite_fail++;
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
