// Mist as a Pulsar track engine. Without the dispatch branch, engine id 300 falls
// through to OrpheusVoice, which clamps it to 23 and plays the hi-hat.
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/pulsar_mist.h"
#include "../src/pulsar_kraken.h"
#include "../src/orpheus_graph.h"
#include <cmath>
#include <cstdio>
#include <cstring>
#include <initializer_list>
#include <vector>

static constexpr int kBlockFrames = 512;
static constexpr int kKeys = 4;   // the one track index with no ducking

static void make_unit(GraphUnit& unit) {
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
}

#define MIST_SET(field, track, value) do { \
    engine->pulsar_track_##field[track].store(value, std::memory_order_relaxed); \
    engine->pulsar_track_##field##_space[track].store(value, std::memory_order_relaxed); \
} while (0)

// `track` soloed on Mist in both slots, RNGs pinned, a busy pattern.
static void setup_mist_track(OrpheusEngine* engine, int track, int engine_id = kMistEngineId) {
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    solo_track(engine, track);
    engine->pulsar_track_engine_edm[track].store(engine_id, std::memory_order_relaxed);
    engine->pulsar_track_engine_space[track].store(engine_id, std::memory_order_relaxed);
    MIST_SET(volume, track, 0.85f);
    engine->pulsar_track_density_override[track].store(0.5f, std::memory_order_relaxed);
    MIST_SET(grain_mode, track, 0);
    MIST_SET(grain_scatter_mode, track, 0);
    MIST_SET(grain_voicing, track, 0);
    MIST_SET(grain_scatter, track, 0.0f);
    MIST_SET(grain_rate, track, 0.8f);
    MIST_SET(grain_random_timing, track, 0);
    MIST_SET(grain_shape, track, 0.5f);
    MIST_SET(grain_blend, track, 1.0f);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);
}

static float block_peak(const float* l, const float* r) {
    float p = 0.0f;
    for (int i = 0; i < kBlockFrames; i++)
        p = std::max(p, std::max(std::fabs(l[i]), std::fabs(r[i])));
    return p;
}

static bool test_pulsar_track_renders_mist() {
    printf("\n=== Test: a Pulsar track on engine 300 renders the Mist kernel ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    make_unit(unit);
    setup_mist_track(engine, kKeys);
    MIST_SET(grain_rate, kKeys, 0.9f);
    MIST_SET(grain_scatter, kKeys, 0.4f);
    MIST_SET(grain_scatter_mode, kKeys, 1);
    MIST_SET(grain_voicing, kKeys, 2);
    trigger_vibe_load(engine);

    float peak = 0.0f, keys_peak = 0.0f, bass_peak = 0.0f, min_note = 1e9f;
    for (int i = 0; i < 300; i++) {
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        peak = std::max(peak, block_peak(engine->pulsar_out_l, engine->pulsar_out_r));
        keys_peak = std::max(keys_peak, block_peak(engine->pulsar_bus_keys_l, engine->pulsar_bus_keys_r));
        bass_peak = std::max(bass_peak, block_peak(engine->pulsar_bus_bass_l, engine->pulsar_bus_bass_r));
        min_note = std::min(min_note,
            engine->pulsar_track_note_debug[kKeys].load(std::memory_order_relaxed));
    }
    const PulsarState* state = engine->pulsar_state;
    const PulsarTrackState& ts = state->tracks[kKeys];
    // sample_rate stays 0 until the kernel runs, so this proves the branch fired.
    const bool kernel_ran = (state->mist[kKeys].sample_rate == 48000.0f);
    printf("  peak=%.4f keys=%.4f bass=%.6f min_note=%.1f kernel_ran=%d\n",
           peak, keys_peak, bass_peak, min_note, kernel_ran ? 1 : 0);
    printf("  resolved rate=%.2f scatter=%.2f scatter_mode=%d voicing=%d\n",
           ts.grain_rate, ts.grain_scatter, ts.grain_scatter_mode, ts.grain_voicing);

    bool ok = kernel_ran && (peak > 0.001f);
    ok &= (keys_peak > 0.001f) && (bass_peak == 0.0f);          // keys bus, not bass
    ok &= (min_note >= static_cast<float>(kMistModRange.note_min));
    ok &= (std::fabs(ts.grain_rate - 0.9f) < 1e-6f);
    ok &= (std::fabs(ts.grain_scatter - 0.4f) < 1e-6f) && (ts.grain_scatter_mode == 1);
    ok &= (ts.grain_voicing == 2);
    printf("Pulsar Mist dispatch: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_pulsar_mist_on_track_3_uses_the_bass_bus() {
    printf("\n=== Test: Mist on track 3 stays on the bass bus ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    make_unit(unit);
    setup_mist_track(engine, 3);
    trigger_vibe_load(engine);
    float keys_peak = 0.0f, bass_peak = 0.0f;
    for (int i = 0; i < 300; i++) {
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        keys_peak = std::max(keys_peak, block_peak(engine->pulsar_bus_keys_l, engine->pulsar_bus_keys_r));
        bass_peak = std::max(bass_peak, block_peak(engine->pulsar_bus_bass_l, engine->pulsar_bus_bass_r));
    }
    printf("  keys=%.6f bass=%.4f\n", keys_peak, bass_peak);
    bool ok = (bass_peak > 0.001f) && (keys_peak == 0.0f);
    printf("Track 3 bus: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Counts blocks where the keys bus is silent, after the first audible one. `audible`
// counts the rest, so a run that never sounds cannot pass for one that never rests.
static int silent_blocks(float blend, int* audible) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    make_unit(unit);
    setup_mist_track(engine, kKeys);
    // Three notes a bar under the pinned seed, with gaps of a beat and more. At 0.1
    // the generator writes no notes at all and neither layer ever sounds.
    engine->pulsar_track_density_override[kKeys].store(0.25f, std::memory_order_relaxed);
    engine->clock_bpm.store(60.0f, std::memory_order_relaxed);
    MIST_SET(pin_morph, kKeys, 1);
    MIST_SET(morph, kKeys, 1.0f);        // 4 s grains
    MIST_SET(grain_rate, kKeys, 0.9f);
    MIST_SET(grain_blend, kKeys, blend);
    trigger_vibe_load(engine);
    int silent = 0;
    *audible = 0;
    for (int i = 0; i < 900; i++) {
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        const float p = block_peak(engine->pulsar_bus_keys_l, engine->pulsar_bus_keys_r);
        if (p > 1e-4f) (*audible)++;
        else if (*audible > 0) silent++;
    }
    orpheus_engine_destroy(engine);
    return silent;
}

// The oscillator layer takes Pulsar's envelope and goes quiet between notes. The
// grain layer joins after the envelope, so 4-second grains bridge the same gaps.
static bool test_pulsar_mist_grains_outlive_the_envelope() {
    printf("\n=== Test: grains outlive Pulsar's envelope, the oscillator does not ===\n");
    int osc_audible = 0, grain_audible = 0;
    const int osc_silent = silent_blocks(0.0f, &osc_audible);
    const int grain_silent = silent_blocks(1.0f, &grain_audible);
    printf("  silent blocks: oscillator %d, grains %d (audible: %d, %d)\n",
           osc_silent, grain_silent, osc_audible, grain_audible);
    bool ok = (osc_audible > 0) && (grain_audible > 0);
    // Grains enveloped like the oscillator rest about as often as it does, so the
    // margin is a factor of two rather than a single block.
    ok &= (osc_silent > 0) && (grain_silent * 2 < osc_silent);
    printf("Envelope layering: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// An engine recreated mid-session renders before Kotlin replays its ports. Zero-init
// would make that Mist all oscillator (blend 0) at one grain per note (rate 0).
static bool test_pulsar_mist_unpushed_defaults() {
    printf("\n=== Test: unpushed Mist atomics match the Kotlin defaults ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool ok = true;
    for (int t = 0; t < 8; t++) {
        ok &= (engine->pulsar_track_grain_mode[t].load() == 0);
        ok &= (engine->pulsar_track_grain_scatter_mode_space[t].load() == 0);
        ok &= (engine->pulsar_track_grain_voicing[t].load() == 0);
        ok &= (engine->pulsar_track_grain_voicing_space[t].load() == 0);
        ok &= (engine->pulsar_track_grain_scatter[t].load() == 0.0f);
        ok &= (engine->pulsar_track_grain_rate[t].load() == 0.5f);
        ok &= (engine->pulsar_track_grain_rate_space[t].load() == 0.5f);
        ok &= (engine->pulsar_track_grain_shape[t].load() == 0.5f);
        ok &= (engine->pulsar_track_grain_shape_space[t].load() == 0.5f);
        ok &= (engine->pulsar_track_grain_blend[t].load() == 1.0f);
        ok &= (engine->pulsar_track_grain_blend_space[t].load() == 1.0f);
    }
    printf("Unpushed defaults: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// The engine slot flips with the energy macro, and Mist is not rendered while the
// other slot plays. Grains frozen in that gap must not replay when it comes back.
static bool test_pulsar_mist_reentry_drops_stale_grains() {
    printf("\n=== Test: re-entering Mist drops grains frozen while it was away ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    make_unit(unit);
    setup_mist_track(engine, kKeys);
    MIST_SET(pin_morph, kKeys, 1);
    MIST_SET(morph, kKeys, 1.0f);
    MIST_SET(grain_rate, kKeys, 0.9f);
    trigger_vibe_load(engine);
    for (int i = 0; i < 200; i++) unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
    const int before = engine->pulsar_state->mist[kKeys].grains.CountActiveGrains();

    engine->pulsar_track_engine_edm[kKeys].store(8, std::memory_order_relaxed);    // VA
    engine->pulsar_track_engine_space[kKeys].store(8, std::memory_order_relaxed);
    for (int i = 0; i < 20; i++) unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
    const int frozen = engine->pulsar_state->mist[kKeys].grains.CountActiveGrains();

    engine->pulsar_track_engine_edm[kKeys].store(kMistEngineId, std::memory_order_relaxed);
    engine->pulsar_track_engine_space[kKeys].store(kMistEngineId, std::memory_order_relaxed);
    unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
    const int after = engine->pulsar_state->mist[kKeys].grains.CountActiveGrains();

    printf("  active grains: %d before leaving, %d frozen, %d one block after return\n",
           before, frozen, after);
    // One block at this rate can start at most two grains.
    bool ok = (before >= 6) && (frozen == before) && (after <= 3);
    printf("Re-entry: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// A vibe load changes key and bank under the track. Grains still ringing from the
// old vibe must not carry into the new one, even when the track stays on Mist.
static bool test_pulsar_mist_vibe_load_drops_old_grains() {
    printf("\n=== Test: a vibe load drops the previous vibe's grains ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    make_unit(unit);
    setup_mist_track(engine, kKeys);
    MIST_SET(pin_morph, kKeys, 1);
    MIST_SET(morph, kKeys, 1.0f);
    MIST_SET(grain_rate, kKeys, 0.9f);
    trigger_vibe_load(engine);
    for (int i = 0; i < 200; i++) unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
    const int before = engine->pulsar_state->mist[kKeys].grains.CountActiveGrains();

    trigger_vibe_load(engine);
    unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
    const int after = engine->pulsar_state->mist[kKeys].grains.CountActiveGrains();

    printf("  active grains: %d before the load, %d one block after (engine %d)\n",
           before, after, engine->pulsar_state->tracks[kKeys].engine_index);
    // The new vibe's downbeat lands in that block and can start at most two grains.
    bool ok = (before >= 6) && (after <= 3);
    ok &= (engine->pulsar_state->tracks[kKeys].engine_index == kMistEngineId);
    printf("Vibe load: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

struct LowRegisterRun {
    int gated = 0;
    int min_step = 128;           // lowest note the generator wrote
    float min_fired = 1e9f;       // lowest pitch a note fired at, before the render fold
    float min_rendered = 1e9f;    // lowest pitch the engine was handed
};

// Track range 12..36 in G on `engine_id`: an octave of legal notes under Mist's floor,
// with the keys triad centred on G1 (19). The floored window, 24..36, still spans an
// octave, so every pitch class has a place in it and the generator can honour the floor.
static LowRegisterRun run_low_register(int engine_id) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    make_unit(unit);
    setup_mist_track(engine, kKeys, engine_id);
    engine->pulsar_root_note.store(7, std::memory_order_relaxed);
    engine->pulsar_track_note_range_low[kKeys].store(12, std::memory_order_relaxed);
    engine->pulsar_track_note_range_high[kKeys].store(36, std::memory_order_relaxed);
    trigger_vibe_load(engine);
    LowRegisterRun r;
    for (int i = 0; i < 300; i++) {
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        const PulsarTrackState& ts = engine->pulsar_state->tracks[kKeys];
        for (int s = 0; s < ts.step_count; s++) {
            if (!ts.steps[s].gate) continue;
            if (i == 0) r.gated++;
            r.min_step = std::min(r.min_step, static_cast<int>(ts.steps[s].note));
        }
        r.min_fired = std::min(r.min_fired, ts.current_pitch);
        r.min_rendered = std::min(r.min_rendered,
            engine->pulsar_track_note_debug[kKeys].load(std::memory_order_relaxed));
    }
    orpheus_engine_destroy(engine);
    return r;
}

// kEngineModRanges cannot index 300, so Mist's note floor is a separate case at the
// generator (engine_note_floor) and at the render fold. Without them it has no floor.
static bool test_pulsar_mist_note_floor() {
    printf("\n=== Test: Mist's note floor reaches the generator and the render ===\n");
    const int floor_note = kMistModRange.note_min;
    const LowRegisterRun wsh = run_low_register(9);   // WSH, no floor
    const LowRegisterRun mist_run = run_low_register(kMistEngineId);
    printf("  WSH (no floor):  %d gated, lowest step %d\n", wsh.gated, wsh.min_step);
    printf("  Mist (floor %d): %d gated, lowest step %d, lowest fired %.1f, lowest rendered %.1f\n",
           floor_note, mist_run.gated, mist_run.min_step, mist_run.min_fired, mist_run.min_rendered);
    // The fixture must really reach under the floor, at the generator and at fire time.
    bool ok = (wsh.gated > 0) && (wsh.min_step < floor_note);
    ok &= (mist_run.gated > 0) && (mist_run.min_fired < static_cast<float>(floor_note));
    ok &= (mist_run.min_step >= floor_note);
    ok &= (mist_run.min_rendered >= static_cast<float>(floor_note));
    printf("Mist note floor: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// The mod LFO clamps to the engine's row. Id 300 has none, and clamping the id to the
// last row would pen Mist's timbre inside the hi-hat's 0.30..0.70.
static bool test_pulsar_mist_lfo_uses_its_own_range() {
    printf("\n=== Test: the mod LFO sweeps Mist over its own range, not the hi-hat's ===\n");
    const int kTexture = 5;   // the LFO block only runs on tracks 5 to 7
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    make_unit(unit);
    setup_mist_track(engine, kTexture);
    // Low energy picks the bipolar PolyLfo source at full texture depth; at rate 2 it
    // crosses its range several times in the 16 s rendered here.
    engine->pulsar_energy.store(0.2f, std::memory_order_relaxed);
    MIST_SET(mod_lfo_depth, kTexture, 1.0f);
    MIST_SET(mod_lfo_rate, kTexture, 2.0f);
    trigger_vibe_load(engine);
    float lo = 2.0f, hi = -2.0f;
    for (int i = 0; i < 1500; i++) {
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        const float v = engine->pulsar_track_mod_timbre_debug[kTexture].load(std::memory_order_relaxed);
        lo = std::min(lo, v);
        hi = std::max(hi, v);
    }
    const EngineModRange& hat = kEngineModRanges[23];
    const bool kernel_ran = (engine->pulsar_state->mist[kTexture].sample_rate == 48000.0f);
    printf("  timbre swept %.3f..%.3f (hi-hat row %.2f..%.2f, Mist row %.2f..%.2f) kernel_ran=%d\n",
           lo, hi, hat.timbre_min, hat.timbre_max,
           kMistModRange.timbre_min, kMistModRange.timbre_max, kernel_ran ? 1 : 0);
    // Past both edges of the hi-hat's window: its row clamps exactly at 0.30 and 0.70.
    bool ok = kernel_ran && (lo < hat.timbre_min - 0.05f) && (hi > hat.timbre_max + 0.05f);
    printf("Mist LFO range: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Bit n is pitch class n, C = 0.
static uint16_t pcs(std::initializer_list<int> classes) {
    uint16_t m = 0;
    for (int c : classes) m |= static_cast<uint16_t>(1u << c);
    return m;
}

static bool expect_key(const char* what, const mist::PitchContext& c,
                       uint16_t scale, uint16_t chord) {
    const bool ok = (c.scale_mask == scale) && (c.chord_mask == chord);
    printf("  %-30s scale 0x%03X (want 0x%03X), chord 0x%03X (want 0x%03X)%s\n",
           what, c.scale_mask, scale, c.chord_mask, chord, ok ? "" : "  FAIL");
    return ok;
}

// Under a Kraken shift the band plays in another key, and scattered grains snap to that
// key, not home. Home is C minor; each expected set is spelled out and worked by hand.
static bool test_mist_key_follows_kraken() {
    printf("\n=== Test: Mist's scale and chord masks follow the Kraken key ===\n");
    const int kC = 0, kMinor = 0, kMajor = 1, kTonic = 0, kFifth = 4;
    auto key = [&](int target, int scale, int degree) {
        return mist_key_context(target, kC, scale, degree, 0, 36, 72);
    };
    bool ok = true;
    // C minor: C D Eb F G Ab Bb. i = C Eb G.
    ok &= expect_key("home", key(kKrakenHome, kMinor, kTonic),
                     pcs({0, 2, 3, 5, 7, 8, 10}), pcs({0, 3, 7}));
    // Parallel is C major: C D E F G A B. I = C E G.
    ok &= expect_key("parallel", key(KRAKEN_PARALLEL, kMinor, kTonic),
                     pcs({0, 2, 4, 5, 7, 9, 11}), pcs({0, 4, 7}));
    // Relative is Eb major, the same seven notes as C minor. I = Eb G Bb.
    ok &= expect_key("relative", key(KRAKEN_RELATIVE, kMinor, kTonic),
                     pcs({0, 2, 3, 5, 7, 8, 10}), pcs({3, 7, 10}));
    // IV is F minor: F G Ab Bb C Db Eb. i = F Ab C.
    ok &= expect_key("IV", key(KRAKEN_IV, kMinor, kTonic),
                     pcs({0, 1, 3, 5, 7, 8, 10}), pcs({0, 5, 8}));
    // V is G minor: G A Bb C D Eb F. i = G Bb D.
    ok &= expect_key("V", key(KRAKEN_V, kMinor, kTonic),
                     pcs({0, 2, 3, 5, 7, 9, 10}), pcs({2, 7, 10}));
    // The bar's chord moves with the key: v of C minor is G Bb D, v of F minor is C Eb G.
    ok &= expect_key("home, v chord", key(kKrakenHome, kMinor, kFifth),
                     pcs({0, 2, 3, 5, 7, 8, 10}), pcs({2, 7, 10}));
    ok &= expect_key("IV, v chord", key(KRAKEN_IV, kMinor, kFifth),
                     pcs({0, 1, 3, 5, 7, 8, 10}), pcs({0, 3, 7}));
    // A negative offset: C major's relative is A minor, three semitones down. i = A C E.
    ok &= expect_key("relative of C major", key(KRAKEN_RELATIVE, kMajor, kTonic),
                     pcs({0, 2, 4, 5, 7, 9, 11}), pcs({0, 4, 9}));
    printf("Mist Kraken key: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// The climax key shift transposes every note the band fires, so the masks move with it.
static bool test_mist_key_follows_tension_key_shift() {
    printf("\n=== Test: Mist's masks follow the tension key shift ===\n");
    const int kC = 0, kMinor = 0;
    bool ok = true;
    // Up two is D minor: D E F G A Bb C. i = D F A.
    ok &= expect_key("+2", mist_key_context(kKrakenHome, kC, kMinor, 0, 2, 36, 72),
                     pcs({0, 2, 4, 5, 7, 9, 10}), pcs({2, 5, 9}));
    // Down two is Bb minor: Bb C Db Eb F Gb Ab. i = Bb Db F.
    ok &= expect_key("-2", mist_key_context(kKrakenHome, kC, kMinor, 0, -2, 36, 72),
                     pcs({0, 1, 3, 5, 6, 8, 10}), pcs({1, 5, 10}));
    // Up two under IV is G minor (0 + 2 + 5): G A Bb C D Eb F. i = G Bb D.
    ok &= expect_key("+2 under IV", mist_key_context(KRAKEN_IV, kC, kMinor, 0, 2, 36, 72),
                     pcs({0, 2, 3, 5, 7, 9, 10}), pcs({2, 7, 10}));
    // The shift only applies above 0.7 tension, as at the fire site.
    const bool gate = (tension_key_offset(3, 0.70f) == 0) && (tension_key_offset(3, 0.71f) == 3)
                   && (tension_key_offset(-2, 1.0f) == -2) && (tension_key_offset(0, 1.0f) == 0);
    printf("  offset is gated at 0.7 tension: %s\n", gate ? "yes" : "FAIL");
    ok &= gate;
    printf("Mist tension key: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// Grains stay in the track's register, and never under the engine's floor.
static bool test_mist_register_is_floored() {
    printf("\n=== Test: Mist's grain register is the track's, floored at note_min ===\n");
    const int floor_note = kMistModRange.note_min;
    const mist::PitchContext mid = mist_key_context(kKrakenHome, 0, 0, 0, 0, 36, 72);
    const mist::PitchContext low = mist_key_context(kKrakenHome, 0, 0, 0, 0, 12, 60);
    const mist::PitchContext under = mist_key_context(kKrakenHome, 0, 0, 0, 0, 12, 20);
    printf("  36..72 -> %d..%d, 12..60 -> %d..%d, 12..20 -> %d..%d (floor %d)\n",
           mid.note_lo, mid.note_hi, low.note_lo, low.note_hi, under.note_lo, under.note_hi,
           floor_note);
    bool ok = (mid.note_lo == 36) && (mid.note_hi == 72);
    ok &= (low.note_lo == floor_note) && (low.note_hi == 60);
    ok &= (under.note_lo == floor_note) && (under.note_hi == floor_note);
    printf("Mist register: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// The bus stem plus the delay and reverb sends, per block, of one render.
struct Stems { std::vector<float> l, r, rev_l, rev_r, dly_l, dly_r; };

// Renders 300 blocks of the keys bus stem and the fx sends. Both are taken before the
// master limiter, so left and right stay linear in the side signal. The send buffers
// are cleared at the top of each block and read only by later units, so they hold
// exactly this block's sends when the call returns.
static Stems render_keys_stem(int engine_id, float width, float scatter, int random_timing) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    make_unit(unit);
    setup_mist_track(engine, kKeys, engine_id);
    MIST_SET(grain_scatter, kKeys, scatter);
    MIST_SET(grain_random_timing, kKeys, random_timing);
    MIST_SET(reverb_send, kKeys, 0.3f);
    MIST_SET(delay_send, kKeys, 0.3f);
    engine->pulsar_mist_width_debug.store(width, std::memory_order_relaxed);
    trigger_vibe_load(engine);
    Stems s;
    for (int i = 0; i < 300; i++) {
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        s.l.insert(s.l.end(), engine->pulsar_bus_keys_l, engine->pulsar_bus_keys_l + kBlockFrames);
        s.r.insert(s.r.end(), engine->pulsar_bus_keys_r, engine->pulsar_bus_keys_r + kBlockFrames);
        s.rev_l.insert(s.rev_l.end(), engine->pulsar_reverb_send_l, engine->pulsar_reverb_send_l + kBlockFrames);
        s.rev_r.insert(s.rev_r.end(), engine->pulsar_reverb_send_r, engine->pulsar_reverb_send_r + kBlockFrames);
        s.dly_l.insert(s.dly_l.end(), engine->pulsar_delay_send_l, engine->pulsar_delay_send_l + kBlockFrames);
        s.dly_r.insert(s.dly_r.end(), engine->pulsar_delay_send_r, engine->pulsar_delay_send_r + kBlockFrames);
    }
    orpheus_engine_destroy(engine);
    return s;
}

static float max_abs_diff(const std::vector<float>& a, const std::vector<float>& b) {
    float m = 0.0f;
    for (size_t i = 0; i < a.size() && i < b.size(); i++) m = std::max(m, std::fabs(a[i] - b[i]));
    return m;
}

// Mid/side: the side signal changes left minus right and cancels out of left plus right.
static bool test_pulsar_mist_side_channel_is_mid_side() {
    printf("\n=== Test: the side channel widens the track and folds down to mono ===\n");
    const Stems wide = render_keys_stem(kMistEngineId, 1.0f, 0.6f, 1);
    const Stems mono = render_keys_stem(kMistEngineId, 0.0f, 0.6f, 1);
    std::vector<float> sum_w, sum_m, dif_w, dif_m;
    for (size_t i = 0; i < wide.l.size(); i++) {
        sum_w.push_back(wide.l[i] + wide.r[i]);
        sum_m.push_back(mono.l[i] + mono.r[i]);
        dif_w.push_back(wide.l[i] - wide.r[i]);
        dif_m.push_back(mono.l[i] - mono.r[i]);
    }
    const float sum_gap = max_abs_diff(sum_w, sum_m);
    const float dif_gap = max_abs_diff(dif_w, dif_m);
    printf("  sum differs by %.7f, difference by %.4f\n", sum_gap, dif_gap);
    bool ok = (sum_gap < 1e-4f) && (dif_gap > 1e-3f);
    printf("Mid/side: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static std::vector<float> sum_of(const std::vector<float>& l, const std::vector<float>& r) {
    std::vector<float> v(l.size());
    for (size_t i = 0; i < l.size(); i++) v[i] = l[i] + r[i];
    return v;
}

static std::vector<float> diff_of(const std::vector<float>& l, const std::vector<float>& r) {
    std::vector<float> v(l.size());
    for (size_t i = 0; i < l.size(); i++) v[i] = l[i] - r[i];
    return v;
}

// The delay and reverb sends hear the same mid/side split as the stem. The reverb
// one-pole is linear, so the fold-down still cancels the side and the difference keeps it.
static bool test_pulsar_mist_side_reaches_the_sends() {
    printf("\n=== Test: the side channel feeds the delay and reverb sends ===\n");
    const Stems wide = render_keys_stem(kMistEngineId, 1.0f, 0.6f, 1);
    const Stems mono = render_keys_stem(kMistEngineId, 0.0f, 0.6f, 1);
    const float rev_sum = max_abs_diff(sum_of(wide.rev_l, wide.rev_r), sum_of(mono.rev_l, mono.rev_r));
    const float rev_dif = max_abs_diff(diff_of(wide.rev_l, wide.rev_r), diff_of(mono.rev_l, mono.rev_r));
    const float dly_sum = max_abs_diff(sum_of(wide.dly_l, wide.dly_r), sum_of(mono.dly_l, mono.dly_r));
    const float dly_dif = max_abs_diff(diff_of(wide.dly_l, wide.dly_r), diff_of(mono.dly_l, mono.dly_r));
    printf("  reverb send: sum gap %.7f, difference gap %.5f\n", rev_sum, rev_dif);
    printf("  delay send:  sum gap %.7f, difference gap %.5f\n", dly_sum, dly_dif);
    bool ok = (rev_sum < 1e-4f) && (rev_dif > 1e-3f) && (dly_sum < 1e-4f) && (dly_dif > 1e-3f);
    printf("Side reaches sends: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// With nothing random, Mist pans every grain to the centre and width does nothing.
static bool test_pulsar_mist_centred_without_randomness() {
    printf("\n=== Test: a Mist track with no randomness is centred ===\n");
    const Stems wide = render_keys_stem(kMistEngineId, 1.0f, 0.0f, 0);
    const Stems mono = render_keys_stem(kMistEngineId, 0.0f, 0.0f, 0);
    const float gap = std::max(max_abs_diff(wide.l, mono.l), max_abs_diff(wide.r, mono.r));
    printf("  width changes the stem by %.8f\n", gap);
    bool ok = (gap < 1e-6f);
    printf("Centred: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// The side branch must not touch any other engine. VA rendered with the width at
// 1 and at 0 has to be the same samples, on the stem and on both sends.
static bool test_pulsar_other_engines_ignore_the_side_channel() {
    printf("\n=== Test: a non-Mist track is untouched by the side channel ===\n");
    const Stems a = render_keys_stem(8, 1.0f, 0.6f, 1);
    const Stems b = render_keys_stem(8, 0.0f, 0.6f, 1);
    bool same = (a.l.size() == b.l.size())
        && std::memcmp(a.l.data(), b.l.data(), a.l.size() * sizeof(float)) == 0
        && std::memcmp(a.r.data(), b.r.data(), a.r.size() * sizeof(float)) == 0
        && std::memcmp(a.rev_l.data(), b.rev_l.data(), a.rev_l.size() * sizeof(float)) == 0
        && std::memcmp(a.rev_r.data(), b.rev_r.data(), a.rev_r.size() * sizeof(float)) == 0
        && std::memcmp(a.dly_l.data(), b.dly_l.data(), a.dly_l.size() * sizeof(float)) == 0
        && std::memcmp(a.dly_r.data(), b.dly_r.data(), a.dly_r.size() * sizeof(float)) == 0;
    float peak = 0.0f;
    for (float x : a.l) peak = std::max(peak, std::fabs(x));
    printf("  identical=%d peak=%.4f\n", same ? 1 : 0, peak);
    bool ok = same && (peak > 0.001f);
    printf("Other engines untouched: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// CONTINUOUS rides the Pulsar step clock, not the track's notes. With density 0 no note
// ever fires, yet the cloud still grows; BURST on the same fixture has no notes to spray.
// This pins the tick wiring in the dispatch branch (mg.ticks, mg.tick_count).
struct ContinuousRun {
    float peak;
    int grains_early;
    int grains_late;
};

static ContinuousRun run_silent_track_cloud(int grain_mode) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    make_unit(unit);
    setup_mist_track(engine, kKeys);
    engine->pulsar_track_density_override[kKeys].store(0.0f, std::memory_order_relaxed);
    MIST_SET(pin_morph, kKeys, 1);
    MIST_SET(morph, kKeys, 1.0f);            // 4 s grains, so none expire during the run
    MIST_SET(grain_mode, kKeys, grain_mode);
    MIST_SET(grain_rate, kKeys, 1.0f);
    MIST_SET(grain_random_timing, kKeys, 0);
    trigger_vibe_load(engine);
    ContinuousRun r{0.0f, 0, 0};
    for (int i = 0; i < 200; i++) {          // about 2 s, 16 sixteenth steps at 120 BPM
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        r.peak = std::max(r.peak, block_peak(engine->pulsar_bus_keys_l, engine->pulsar_bus_keys_r));
        if (i == 40) r.grains_early = engine->pulsar_state->mist[kKeys].grains.CountActiveGrains();
    }
    r.grains_late = engine->pulsar_state->mist[kKeys].grains.CountActiveGrains();
    orpheus_engine_destroy(engine);
    return r;
}

static bool test_pulsar_mist_continuous_follows_the_pulsar_clock() {
    printf("\n=== Test: CONTINUOUS Mist follows the Pulsar clock with no notes ===\n");
    const ContinuousRun cont = run_silent_track_cloud(2);
    const ContinuousRun burst = run_silent_track_cloud(0);
    printf("  CONTINUOUS: peak %.5f, grains %d at block 40, %d at block 200\n",
           cont.peak, cont.grains_early, cont.grains_late);
    printf("  BURST:      peak %.5f, grains %d at block 40, %d at block 200\n",
           burst.peak, burst.grains_early, burst.grains_late);
    bool ok = (cont.peak > 1e-3f) && (cont.grains_late > cont.grains_early) && (cont.grains_early > 0);
    ok &= (burst.peak < 1e-4f) && (burst.grains_late == 0);
    printf("CONTINUOUS follows the clock: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

bool run_pulsar_mist_tests() {
    printf("\n=== Pulsar Mist Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    if (test_pulsar_track_renders_mist()) suite_pass++; else suite_fail++;
    if (test_pulsar_mist_on_track_3_uses_the_bass_bus()) suite_pass++; else suite_fail++;
    if (test_pulsar_mist_grains_outlive_the_envelope()) suite_pass++; else suite_fail++;
    if (test_pulsar_mist_unpushed_defaults()) suite_pass++; else suite_fail++;
    if (test_pulsar_mist_reentry_drops_stale_grains()) suite_pass++; else suite_fail++;
    if (test_pulsar_mist_vibe_load_drops_old_grains()) suite_pass++; else suite_fail++;
    if (test_pulsar_mist_note_floor()) suite_pass++; else suite_fail++;
    if (test_pulsar_mist_lfo_uses_its_own_range()) suite_pass++; else suite_fail++;
    if (test_mist_key_follows_kraken()) suite_pass++; else suite_fail++;
    if (test_mist_key_follows_tension_key_shift()) suite_pass++; else suite_fail++;
    if (test_mist_register_is_floored()) suite_pass++; else suite_fail++;
    if (test_pulsar_mist_side_channel_is_mid_side()) suite_pass++; else suite_fail++;
    if (test_pulsar_mist_centred_without_randomness()) suite_pass++; else suite_fail++;
    if (test_pulsar_other_engines_ignore_the_side_channel()) suite_pass++; else suite_fail++;
    if (test_pulsar_mist_side_reaches_the_sends()) suite_pass++; else suite_fail++;
    if (test_pulsar_mist_continuous_follows_the_pulsar_clock()) suite_pass++; else suite_fail++;
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
