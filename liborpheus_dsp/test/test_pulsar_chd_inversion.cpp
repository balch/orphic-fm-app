// A CHORDAL track on CHD (engine 14) with a section inversion: the override lands on timbre,
// which plaits' chord engine reads as the inversion, and leaves morph (its waveform) authored.
// The bank half checks each override value against ChordBank::ComputeChordInversion itself.
#include "test_harness.h"
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/orpheus_graph.h"
#include "../src/pulsar_comping.h"
#include "plaits/dsp/chords/chord_bank.h"
#include "stmlib/utils/buffer_allocator.h"
#include <cmath>
#include <cstdio>
#include <cstring>

namespace {

constexpr int kChdTrack = 6;
constexpr int kChdEngine = 14;
constexpr float kAuthoredTimbre = 0.7f;
constexpr float kAuthoredMorph = 0.3f;
constexpr float kAudible = 0.01f;   // amplitude below this counts as silent (kBaseGain is 0.25)

struct Voicing {
    float lowest;    // ratio to the played pitch
    int sounding;
};

Voicing voice_chord(plaits::ChordBank& bank, float timbre, float sounding_ratios[plaits::kChordNumVoices]) {
    float ratios[plaits::kChordNumVoices] = {};
    float amps[plaits::kChordNumVoices] = {};
    bank.ComputeChordInversion(timbre, ratios, amps);
    Voicing v{1.0e9f, 0};
    for (int i = 0; i < plaits::kChordNumVoices; i++) {
        if (amps[i] < kAudible) continue;
        sounding_ratios[v.sounding++] = ratios[i];
        if (ratios[i] < v.lowest) v.lowest = ratios[i];
    }
    return v;
}

bool near(float a, float b) { return std::fabs(a - b) < 1.0e-4f * b; }

bool has_ratio(const float* r, int n, float want) {
    for (int i = 0; i < n; i++) if (near(r[i], want)) return true;
    return false;
}

// Every chord in the bank, approached from both sides of each value as timbre's one-pole would.
bool test_values_land_on_inversions() {
    printf("\n=== Test: override values voice root/1st/2nd/open at the played pitch ===\n");
    char ram[256];
    stmlib::BufferAllocator allocator(ram, sizeof(ram));
    plaits::ChordBank bank;
    bank.Init(&allocator);
    bank.Reset();

    const SectionInversionId kInversions[] = {
        SectionInversionId::ROOT_POSITION, SectionInversionId::FIRST_INVERSION,
        SectionInversionId::SECOND_INVERSION, SectionInversionId::OPEN_VOICING};
    bool ok = true;
    for (int c = 0; c < plaits::kChordNumChords; c++) {
        // The quantizer has hysteresis, so settle on the cell centre before reading.
        const float select = (static_cast<float>(c) + 0.5f) / plaits::kChordNumChords / 1.02f;
        for (int i = 0; i < 4; i++) bank.set_chord(select);
        if (bank.chord_index() != c) { printf("  chord %d did not select\n", c); ok = false; continue; }

        for (int k = 0; k < 4; k++) {
            const float t = chd_inversion_timbre(kInversions[k], 0.0f);
            for (float side : {-1.0e-4f, 0.0f, 1.0e-4f}) {
                float r[plaits::kChordNumVoices];
                const Voicing v = voice_chord(bank, t + side, r);
                bool good;
                if (kInversions[k] == SectionInversionId::OPEN_VOICING) {
                    // Root in both octaves, plus the three upper tones.
                    good = v.sounding == 5 && near(v.lowest, bank.ratio(0))
                        && has_ratio(r, v.sounding, bank.ratio(0) * 2.0f);
                } else {
                    // Tone k is the bass; the tones under it moved up an octave.
                    good = v.sounding == 4 && near(v.lowest, bank.ratio(k));
                    for (int n = 0; n < 4; n++)
                        good &= has_ratio(r, v.sounding, bank.ratio(n) * (n < k ? 2.0f : 1.0f));
                }
                if (!good) {
                    printf("  chord %d inversion %d timbre %.5f: lowest %.4f, %d sounding\n",
                           c, k, t + side, v.lowest, v.sounding);
                    ok = false;
                }
            }
        }
    }
    printf("Values land on inversions: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

struct Published { float timbre; float morph; };

Published run_chd(int engine_id, SectionInversionId inversion) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    solo_track(engine, kChdTrack);
    engine->pulsar_track_role[kChdTrack].store(static_cast<int>(TrackRole::CHORDAL), std::memory_order_relaxed);
    engine->pulsar_track_comping_style[kChdTrack].store(static_cast<int>(CompingStyleId::PAD), std::memory_order_relaxed);
    engine->pulsar_track_arp_mode[kChdTrack].store(static_cast<int>(ArpModeId::NEVER), std::memory_order_relaxed);
    engine->pulsar_track_engine_edm[kChdTrack].store(engine_id, std::memory_order_relaxed);
    engine->pulsar_track_engine_space[kChdTrack].store(engine_id, std::memory_order_relaxed);
    engine->pulsar_track_timbre[kChdTrack].store(kAuthoredTimbre, std::memory_order_relaxed);
    engine->pulsar_track_timbre_space[kChdTrack].store(kAuthoredTimbre, std::memory_order_relaxed);
    engine->pulsar_track_pin_timbre[kChdTrack].store(1, std::memory_order_relaxed);
    engine->pulsar_track_pin_timbre_space[kChdTrack].store(1, std::memory_order_relaxed);
    engine->pulsar_track_morph[kChdTrack].store(kAuthoredMorph, std::memory_order_relaxed);
    engine->pulsar_track_morph_space[kChdTrack].store(kAuthoredMorph, std::memory_order_relaxed);
    engine->pulsar_track_pin_morph[kChdTrack].store(1, std::memory_order_relaxed);
    engine->pulsar_track_pin_morph_space[kChdTrack].store(1, std::memory_order_relaxed);
    engine->pulsar_track_inversion[kChdTrack].store(static_cast<int>(inversion), std::memory_order_relaxed);
    trigger_vibe_load(engine);

    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    for (int i = 0; i < 40; i++) unit_process_pulsar(&unit, engine, 512, 48000.0f);
    Published p{engine->pulsar_track_mod_timbre_debug[kChdTrack].load(std::memory_order_relaxed),
                engine->pulsar_track_mod_morph_debug[kChdTrack].load(std::memory_order_relaxed)};
    orpheus_engine_destroy(engine);
    return p;
}

bool test_override_writes_timbre_not_morph() {
    printf("\n=== Test: CHD inversion overrides timbre and keeps the authored morph ===\n");
    const SectionInversionId kInversions[] = {
        SectionInversionId::ROOT_POSITION, SectionInversionId::FIRST_INVERSION,
        SectionInversionId::SECOND_INVERSION, SectionInversionId::OPEN_VOICING};
    bool ok = true;
    for (SectionInversionId inv : kInversions) {
        const Published p = run_chd(kChdEngine, inv);
        const float want = chd_inversion_timbre(inv, kAuthoredTimbre);
        const bool good = std::fabs(p.timbre - want) < 1.0e-5f
            && std::fabs(p.morph - kAuthoredMorph) < 1.0e-5f;
        printf("  inversion %d: timbre %.4f (want %.4f), morph %.4f\n",
               static_cast<int>(inv), p.timbre, want, p.morph);
        ok &= good;
    }
    printf("Timbre carries the inversion: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

bool test_follow_and_other_engines_untouched() {
    printf("\n=== Test: FOLLOW_STYLE, and a non-CHD engine, keep the authored timbre ===\n");
    const Published follow = run_chd(kChdEngine, SectionInversionId::FOLLOW_STYLE);
    const Published va = run_chd(8, SectionInversionId::FIRST_INVERSION);
    printf("  CHD follow: timbre %.4f morph %.4f; VA first: timbre %.4f morph %.4f\n",
           follow.timbre, follow.morph, va.timbre, va.morph);
    const bool ok = std::fabs(follow.timbre - kAuthoredTimbre) < 1.0e-5f
        && std::fabs(follow.morph - kAuthoredMorph) < 1.0e-5f
        && std::fabs(va.timbre - kAuthoredTimbre) < 1.0e-5f
        && std::fabs(va.morph - kAuthoredMorph) < 1.0e-5f;
    printf("Untouched: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

}  // namespace

bool run_pulsar_chd_inversion_tests() {
    printf("\n=== Pulsar CHD Inversion Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    if (test_values_land_on_inversions()) suite_pass++; else suite_fail++;
    if (test_override_writes_timbre_not_morph()) suite_pass++; else suite_fail++;
    if (test_follow_and_other_engines_untouched()) suite_pass++; else suite_fail++;
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
