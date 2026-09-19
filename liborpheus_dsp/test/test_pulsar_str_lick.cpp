// A Plaits String (19) or Modal (20) voice on a Pulsar lick line must sound on every note.
// Both are struck: a note is one excitation, loudest at its onset. Pulsar's own envelope attack
// ate that onset, so STR went silent on TIDES, low-energy BLEND and DRONE-profile tracks.
#include "test_harness.h"
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/orpheus_viz.h"
#include "stmlib/utils/buffer_allocator.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <vector>

namespace {

constexpr int kTrack = 4;                 // the lick slot outside the 5-7 texture notch
constexpr int kBlockFrames = 512;
constexpr float kDotThreshold = 0.02f;    // DJ app activity dot: trackLevels[t] > 0.02
constexpr int kNoteWindowBlocks = 12;     // ~128 ms at 48 kHz: where a pluck lives

enum class EnvCase { AD, TIDES, BLEND, DRONE_PROFILE };

const char* env_name(EnvCase e) {
    switch (e) {
        case EnvCase::AD: return "AD";
        case EnvCase::TIDES: return "TIDES";
        case EnvCase::BLEND: return "BLEND";
        case EnvCase::DRONE_PROFILE: return "DRONE profile";
    }
    return "?";
}

// Track 4 plays a BASS-channel Fill lick: a 3-beat rest, then two back-to-back notes (the
// second is a note-on under a gate that never falls). Timbre and morph are pinned where the
// string rings clearly, and the LPG is bypassed, so Pulsar's envelope is the only shaper.
// Returns each note's track peak over its first kNoteWindowBlocks.
std::vector<float> run_lick(int engine_id, EnvCase env) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    solo_track(engine, kTrack);

    engine->pulsar_root_note.store(4, std::memory_order_relaxed);     // E
    engine->pulsar_scale_index.store(13, std::memory_order_relaxed);  // Blues
    engine->pulsar_step_count.store(32, std::memory_order_relaxed);
    // Below 0.5, BLEND resolves to the TIDES generator.
    engine->pulsar_energy.store(0.45f, std::memory_order_relaxed);
    engine->pulsar_space.store(0.45f, std::memory_order_relaxed);
    engine->pulsar_envelope_mode.store(
        env == EnvCase::TIDES ? 1 : (env == EnvCase::BLEND ? 2 : 0), std::memory_order_relaxed);
    // Tension pinned at 0 and no velocity scaling, so every note fires at its authored level.
    engine->pulsar_tension_inner_bars.store(1, std::memory_order_relaxed);
    engine->pulsar_tension_outer_bars.store(0, std::memory_order_relaxed);
    engine->pulsar_tension_volume.store(0.0f, std::memory_order_relaxed);

    const int t = kTrack;
    engine->pulsar_track_engine_edm[t].store(engine_id, std::memory_order_relaxed);
    engine->pulsar_track_engine_space[t].store(engine_id, std::memory_order_relaxed);
    engine->pulsar_track_role[t].store(1, std::memory_order_relaxed);          // MELODIC
    engine->pulsar_track_lick_mode[t].store(2, std::memory_order_relaxed);     // FILL
    engine->pulsar_track_lick_source[t].store(1, std::memory_order_relaxed);   // BASS
    engine->pulsar_track_chord_follow[t].store(2, std::memory_order_relaxed);  // FIXED
    engine->pulsar_track_envelope[t].store(env == EnvCase::DRONE_PROFILE ? 4 : 1,
                                           std::memory_order_relaxed);
    engine->pulsar_track_note_range_low[t].store(52, std::memory_order_relaxed);
    engine->pulsar_track_note_range_high[t].store(64, std::memory_order_relaxed);
    engine->pulsar_track_lpg_mode[t].store(0, std::memory_order_relaxed);        // BYPASS
    engine->pulsar_track_lpg_mode_space[t].store(0, std::memory_order_relaxed);
    engine->pulsar_track_pin_harmonics[t].store(1, std::memory_order_relaxed);
    engine->pulsar_track_pin_harmonics_space[t].store(1, std::memory_order_relaxed);
    engine->pulsar_track_pin_timbre[t].store(1, std::memory_order_relaxed);
    engine->pulsar_track_pin_timbre_space[t].store(1, std::memory_order_relaxed);
    engine->pulsar_track_pin_morph[t].store(1, std::memory_order_relaxed);
    engine->pulsar_track_pin_morph_space[t].store(1, std::memory_order_relaxed);
    engine->pulsar_track_harmonics[t].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_harmonics_space[t].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_track_timbre[t].store(0.7f, std::memory_order_relaxed);
    engine->pulsar_track_timbre_space[t].store(0.7f, std::memory_order_relaxed);
    engine->pulsar_track_morph[t].store(0.6f, std::memory_order_relaxed);
    engine->pulsar_track_morph_space[t].store(0.6f, std::memory_order_relaxed);

    const struct { int8_t degree; float beats; } figure[] = {{-1, 3.0f}, {5, 1.0f}, {4, 1.5f}};
    for (int i = 0; i < 3; i++) {
        engine->pulsar_bass_line[i].scale_degree = figure[i].degree;
        engine->pulsar_bass_line[i].duration = figure[i].beats;
        engine->pulsar_bass_line[i].velocity = 0.8f;
        engine->pulsar_bass_line[i].glide_rate = -1.0f;
        engine->pulsar_bass_line[i].hit_probability = 1.0f;
    }
    engine->pulsar_bass_line_loop.store(8, std::memory_order_relaxed);
    engine->pulsar_bass_line_mutation.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_bass_line_octave.store(-1, std::memory_order_relaxed);
    engine->pulsar_bass_line_length.store(3, std::memory_order_release);  // publish last

    trigger_vibe_load(engine);
    engine->clock_bpm.store(80.0f, std::memory_order_relaxed);

    std::vector<float> peaks;
    auto& ring = engine->viz_rings[VIZ_PULSAR_TRACK_0 + t];
    int window_left = 0;
    float window_peak = 0.0f;
    int prev_head_origin = 0;
    const int blocks = static_cast<int>(3 * 6.0 * 48000.0 / kBlockFrames);  // 3 loops of 8 beats
    for (int i = 0; i < blocks; i++) {
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        const PulsarTrackState& ts = engine->pulsar_state->tracks[t];
        // A trigger-path note-on sets head_origin inside the block and the block end rebases
        // it by -frames, so a fresh value is one the rebase alone does not explain. Unlike
        // pending_retrig, this survives the TIDES stage consuming the note-on.
        const bool note_on = ts.head_origin != prev_head_origin - kBlockFrames;
        prev_head_origin = ts.head_origin;
        if (note_on) {
            if (window_left > 0) peaks.push_back(window_peak);
            window_left = kNoteWindowBlocks;
            window_peak = 0.0f;
        }
        if (window_left > 0) {
            const float v = ring.buf[(ring.write_count.load() - 1) % VizRing::kVizBufSize];
            if (v > window_peak) window_peak = v;
            if (--window_left == 0) peaks.push_back(window_peak);
        }
    }
    if (window_left > 0) peaks.push_back(window_peak);
    orpheus_engine_destroy(engine);
    return peaks;
}

// The contract: a struck engine is not shaped by Pulsar's envelope, so each note comes out
// the same whether the vibe runs AD, TIDES, BLEND or a DRONE profile. AD is the reference.
// Before the fix TIDES and the DRONE swell ate the onset outright.
bool test_struck_engine_lick_notes_ignore_the_pulsar_envelope() {
    printf("\n=== Test: STR/MOD lick notes are the same under every Pulsar envelope ===\n");
    bool ok = true;
    for (int engine_id : {19, 20}) {
        const std::vector<float> reference = run_lick(engine_id, EnvCase::AD);
        int audible = 0;
        for (float p : reference) if (p > kDotThreshold) audible++;
        printf("  %s AD            notes=%zu audible=%d\n", engine_name(engine_id),
               reference.size(), audible);
        // Two notes per loop over three loops; fewer means the fixture stopped firing.
        if (reference.size() < 6 || audible != static_cast<int>(reference.size())) {
            printf("    FAIL: the AD reference is not playing the figure audibly\n");
            ok = false;
            continue;
        }
        for (EnvCase env : {EnvCase::TIDES, EnvCase::BLEND, EnvCase::DRONE_PROFILE}) {
            const std::vector<float> peaks = run_lick(engine_id, env);
            float worst = 1.0f;
            for (size_t n = 0; n < std::min(peaks.size(), reference.size()); n++)
                worst = std::min(worst, peaks[n] / reference[n]);
            printf("  %s %-13s notes=%zu quietest note vs AD = %.1f dB\n", engine_name(engine_id),
                   env_name(env), peaks.size(), 20.0f * std::log10(std::max(worst, 1e-6f)));
            if (peaks.size() != reference.size()) {
                printf("    FAIL: %zu notes against %zu under AD\n", peaks.size(), reference.size());
                ok = false;
            } else if (worst < 0.9f) {
                printf("    FAIL: the envelope took more than 1 dB off a note\n");
                ok = false;
            }
        }
    }
    printf("Struck engines ignore the Pulsar envelope: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// Peak of the 512 frames after a back-to-back STR note-on at `trig_off`, rendered the way
// Pulsar splits a block: the pre-boundary segment, then the onset segment with retrigger.
float back_to_back_onset_peak(int trig_off) {
    stmlib::Random::Seed(0xBEA7u << 16);  // the string's strike is a burst of this noise
    constexpr int kAllocBytes = 32768;
    std::vector<uint8_t> ram(kAllocBytes, 0);
    stmlib::BufferAllocator allocator(ram.data(), kAllocBytes);
    auto voice = std::make_unique<OrpheusVoice>();
    voice->Init(&allocator);

    float buf[kBlockFrames];
    constexpr float kNote = 60.0f, kHarm = 0.5f, kTimbre = 0.8f, kAccent = 0.8f;
    // A short first note (morph 0 dies in milliseconds), struck and then held for a second,
    // leaves the voice mid-remainder with the gate still high.
    voice->Render(19, 1, kNote, kHarm, kTimbre, 0.0f, kAccent, buf, 500, LPG_BYPASS, 0.5f, 0.5f, true);
    for (int i = 0; i < 94; i++)
        voice->Render(19, 1, kNote, kHarm, kTimbre, 0.0f, kAccent, buf, kBlockFrames);

    voice->Render(19, 1, kNote, kHarm, kTimbre, 0.5f, kAccent, buf, trig_off);
    float peak = 0.0f;
    voice->Render(19, 1, kNote, kHarm, kTimbre, 0.5f, kAccent, buf, kBlockFrames - trig_off,
                  LPG_BYPASS, 0.5f, 0.5f, true);
    for (int i = 0; i < kBlockFrames - trig_off; i++) peak = std::max(peak, std::fabs(buf[i]));
    voice->Render(19, 1, kNote, kHarm, kTimbre, 0.5f, kAccent, buf, kBlockFrames);
    for (int i = 0; i < kBlockFrames; i++) peak = std::max(peak, std::fabs(buf[i]));
    return peak;
}

// A note-on in a block's last few samples can get an onset segment shorter than the voice's
// buffered remainder. That call rendered no fresh block and dropped the retrigger, and with the
// gate already high there was no edge left to find, so a struck engine lost the note outright.
bool test_back_to_back_note_on_inside_the_remainder_is_kept() {
    printf("\n=== Test: a note-on shorter than the voice's remainder still strikes ===\n");
    bool ok = true;
    for (int trig_off : {300, 488, 505, 509, 511}) {
        const float peak = back_to_back_onset_peak(trig_off);
        const bool struck = peak > 0.05f;
        printf("  onset at %3d (segment %3d frames): peak after = %.4f %s\n", trig_off,
               kBlockFrames - trig_off, peak, struck ? "" : "<- note lost");
        if (!struck) ok = false;
    }
    printf("Note-on inside the remainder: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

}  // namespace

bool run_pulsar_str_lick_tests() {
    printf("\n========== PULSAR STR LICK TESTS ==========\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_struck_engine_lick_notes_ignore_the_pulsar_envelope());
    tally(test_back_to_back_note_on_inside_the_remainder_is_kept());
    printf("\nPulsar STR lick tests: %s\n", suite_fail == 0 ? "ALL PASSED" : "SOME FAILED");
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
