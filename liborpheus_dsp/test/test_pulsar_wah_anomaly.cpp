// Wah Anomaly — per-track LEAD-ONLY routing.
//
// The wah anomaly used to filter the summed master bus, which wah'd the drums. It is
// now a per-track insert armed only on eligible LEAD tracks. This suite pins that
// contract end to end, through the real unit_process_pulsar render path:
//
//   D1  eligible track  ==  role MELODIC  &&  lick_source != BASS  &&  t != kBassTrack.
//       Percussive, chordal, the hard-wired bass track, and a melodic track reading the
//       bass line channel must all stay BYTE-identical while a window is armed.
//   D2  an empty eligible mask must not arm anything AND must not consume the shared
//       anomaly RNG (the mask is resolved before the roll and before the bars draw).
//   D3  a lead that already carries the standing lick-wah insert is TAKEN OVER, never
//       cascaded: exactly one resonant bandpass runs per track at any instant, and the
//       output is continuous across both the arm and the disarm boundary.
//   D4  routing-only change: no authored wet/depth value is retuned anywhere.
//
// Every test here fails on the old master-bus behaviour. With the wah on the master
// bus a solo'd track's output differs for EVERY track type (so all four "untouched"
// cases fail), the window is not per-track at all (so the mask assertions fail), and
// there is no param lerp (so the takeover continuity assertions fail).
//
// MEASUREMENT DESIGN — this is what makes exact, non-flaky assertions possible:
//  * Drive unit_process_pulsar directly and read engine->pulsar_out_l. That keeps the
//    master bus, the reverb/delay returns and the limiter out of the measurement, i.e.
//    exactly the confound that made the old routing wrong.
//  * solo_track() zeroes every other track's volume. Volume is applied at the MIX,
//    AFTER the wah insert, so out_l is the solo'd track's post-wah buffer scaled by
//    vol and pan. NOTE volumes latch during load_vibe, so solo BEFORE triggering it.
//  * A/B runs are legitimately bit-comparable. master_anomaly_seed is used only by the
//    anomaly rolls and never by pattern generation, and with dur_min == dur_max the
//    bars draw provably consumes no RNG (test_pulsar_anomaly_arm.cpp pins that), so a
//    "wah declared" run and a "wah undeclared" run generate identical notes.
//  * Both RNGs are pinned per the project's anti-flake convention: pulsar_seed (the
//    pattern RNG, which load_vibe re-stirs from the wall clock when 0) and the global
//    stmlib::Random (the 808 hat/snare noise). stmlib::Random is global, so runs are
//    executed to completion one after another, never interleaved.
//  * pulsar_energy is pinned to 1.0. max_drift = (1 - energy) * 0.05, so at energy 1
//    the tempo drift is exactly 0 and samples_per_step is exactly sample_rate /
//    (bpm/60*4). Without this the armed sample count is not provable arithmetic.
//  * No absolute min/max window assertions anywhere: Pulsar's sparse tracks routinely
//    produce near-silent 512-sample blocks from ordinary pattern gaps. Every audible
//    claim is either a bitwise A/B against an identically seeded run, or is normalized
//    against a dry baseline over the same phase-anchored index range.
#include "test_harness.h"   // declares braids/plaits namespaces before orpheus_unit_pulsar.h
#include "test_pulsar_helpers.h"
#include "orpheus_engine.h"
#include "orpheus_unit_pulsar.h"
#include "pulsar_anomaly_arm.h"
#include "stmlib/utils/random.h"
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <cmath>
#include <vector>

// 240 BPM at 48 kHz on the 16th grid: steps_per_second = 16, so samples_per_step is
// exactly 3000 and one bar (16 steps) is exactly 48000 samples = 1.0 s = 93.75 blocks.
static constexpr float  kSR             = 48000.0f;
static constexpr int    kBlock          = 512;
static constexpr float  kBPM            = 240.0f;
static constexpr double kSamplesPerStep = 3000.0;
static constexpr int    kPatternSeed    = 4242;
static constexpr uint32_t kNoiseSeed    = 0x5A4F0BADu;

// Minimal two-section A/B arrangement. Third copy of this fixture (the originals are
// static/internal to test_pulsar_sections.cpp and test_pulsar_void.cpp). Section 0 -> 1
// -> 0, each bars_per_section arrangement bars long, hard-cut transitions, no solos.
// An active arrangement is mandatory: both wah arm sites live inside
// `if (state->arrangement.active)`.
static void push_two_section_ab_arrangement(OrpheusEngine* engine, int bars_per_section) {
    engine->pulsar_arrangement_active.store(1, std::memory_order_relaxed);
    engine->pulsar_arrangement_section_count.store(2, std::memory_order_relaxed);
    engine->pulsar_arrangement_intro_index.store(-1, std::memory_order_relaxed);
    engine->pulsar_arrangement_outro_index.store(-1, std::memory_order_relaxed);

    constexpr int kSectionStride = 21;
    float section_data[8 * kSectionStride] = {};
    for (int s = 0; s < 8; s++) {
        section_data[s * kSectionStride + 18] = -1;
        section_data[s * kSectionStride + 19] = -1;
        section_data[s * kSectionStride + 20] = -1;
        section_data[s * kSectionStride + 9]  = 0;
        section_data[s * kSectionStride + 5]  = -1;
        section_data[s * kSectionStride + 6]  = -1;
        section_data[s * kSectionStride + 7]  = -1;
        section_data[s * kSectionStride + 8]  = -1;
    }
    section_data[0] = (float)bars_per_section;
    section_data[1] = (float)bars_per_section;
    section_data[2] = 1;
    section_data[3] = 0.8f;
    section_data[4] = 1;
    int s1 = kSectionStride;
    section_data[s1 + 0] = (float)bars_per_section;
    section_data[s1 + 1] = (float)bars_per_section;
    section_data[s1 + 2] = 1;
    section_data[s1 + 3] = 0.8f;
    section_data[s1 + 4] = 1;
    for (int i = 0; i < 8 * kSectionStride; i++)
        engine->pulsar_section_data[i].store(section_data[i], std::memory_order_relaxed);

    float trans[8 * 8 * 3] = {};
    trans[0]  = 1; trans[1]  = 1.0f; trans[2]  = 0;
    trans[24] = 0; trans[25] = 1.0f; trans[26] = 0;
    for (int i = 0; i < 8 * 8 * 3; i++)
        engine->pulsar_section_transitions[i].store(trans[i], std::memory_order_relaxed);

    for (int s = 0; s < 8; s++) {
        engine->pulsar_section_progression_length[s].store(0, std::memory_order_relaxed);
        engine->pulsar_section_chords_per_bar[s].store(0, std::memory_order_relaxed);
        engine->pulsar_section_tension_active[s].store(0, std::memory_order_relaxed);
        for (int i = 0; i < 12; i++)
            engine->pulsar_section_progression_degrees[s * 12 + i].store(0, std::memory_order_relaxed);
    }
    for (int i = 0; i < 8 * 15; i++)
        engine->pulsar_track_solo_behavior[i].store(0.0f, std::memory_order_relaxed);
    for (int i = 0; i < 8 * 6; i++)
        engine->pulsar_track_ducking[i].store(0.0f, std::memory_order_relaxed);
    for (int i = 0; i < 8 * 15; i++)
        engine->pulsar_track_solo_markov[i].store(0.0f, std::memory_order_relaxed);

    engine->pulsar_arrangement_generation.store(1, std::memory_order_release);
}

// ── Case description + captured trace ───────────────────────────────────
// Every field is set explicitly at each call site (no reliance on engine defaults),
// per the DSP-test house rules.
struct WahCase {
    // The baseline fixture's own roles: {PERC,PERC,PERC,MEL,MEL,MEL,PERC,MEL}.
    // NOTE this makes tracks 3,4,5,7 melodic, so the default eligible mask is 0xB0
    // (4,5,7 — track 3 is excluded by the kBassTrack clause), NOT just track 4. Tests
    // that want a single eligible lead must say so explicitly; see kOneLeadOnTrack4.
    int   roles[8]        = {0, 0, 0, 1, 1, 1, 0, 1};
    int   lick_sources[8] = {0, 0, 0, 0, 0, 0, 0, 0};   // 0 = LEAD, 1 = BASS
    // Per-track pattern density, mirroring setup_fixture_baseline's own genre densities so
    // the default case is unchanged. Overridable because the shipped melodic densities
    // are sparse (track 4 = 0.30, track 6 = 0.15): across a 1-bar window that regularly
    // yields a silent measurement range, and "is the wah audible" cannot be measured on
    // silence. Any test that asserts an RMS delta raises the solo'd track's density.
    // Per-track density OVERRIDE (pulsar_track_density_override, the value threaded into
    // generate_track_pattern), 0 = no override, i.e. fall back to the genre density. The
    // genre densities for the melodic tracks are sparse, so a test that has to measure a
    // filtered lead raises its track here.
    float density[8]      = {0, 0, 0, 0, 0, 0, 0, 0};
    // Engine + envelope per track, defaulted to setup_fixture_baseline's own values so an
    // untouched case renders exactly as before. Overridable because giving a track the
    // MELODIC role does not give it a melodic VOICE: track 6 ships as NSE with envelope
    // profile 2 and renders silent as a lead, and a wah on silence is unmeasurable.
    int   engine_edm[8]   = {21, 22, 23, 9, 14, 14, 17, 20};
    int   engine_space[8] = {20, 17, 23, 19, 6, 14, 17, 19};
    int   envelopes[8]    = {0, 0, 0, 1, 1, 2, 2, 3};
    int   solo            = 4;
    bool  wah_declared    = true;
    float probability     = 0.0f;     // 0 => only the manual gesture can fire it
    float dur_bars        = 1.0f;     // dur_min == dur_max => draw consumes no RNG
    bool  manual          = true;
    uint8_t lick_wah_mask = 0;        // 0 => no standing lick-wah insert anywhere
    orpheus::WahParams lick_params{};
    orpheus::WahParams anom_params{};
    int   bars_per_section = 8;
    int   warm_blocks      = 120;
    int   cap_blocks       = 400;
};

struct Trace {
    std::vector<float> l;          // captured pulsar_out_l, capture phase only
    std::vector<int>   left;       // anomaly_wah_samples_left after each capture block
    int      arm_block  = -1;      // capture-block index where the window opened
    int      total      = 0;       // anomaly_wah_samples_total at arm
    uint8_t  mask       = 0;       // anomaly_wah_mask at arm
    uint32_t seed_before = 0;      // master_anomaly_seed just before the trigger
    uint32_t seed_after  = 0;      // master_anomaly_seed at the end of the capture
    double   anom_phase = 0.0;     // anomaly_wah_voice[solo].lfo_phase at the end
    double   lick_phase = 0.0;     // lick_wah_voice[solo].lfo_phase at the end
    bool     ever_armed = false;
    int      min_left   = 0;       // most negative samples_left ever observed
};

static void push_wah_bank(OrpheusEngine* e, const WahCase& c) {
    // Order mirrors the Kotlin marshal / the C++ unpack in load_vibe:
    // [0]=prob [1]=durMin [2]=durMax [3]=rate [4]=depth [5]=Q [6]=centerHz
    // [7]=sweepOct [8]=wet [9]=declared.
    e->pulsar_wah_data[0].store(c.probability, std::memory_order_relaxed);
    e->pulsar_wah_data[1].store(c.dur_bars, std::memory_order_relaxed);
    e->pulsar_wah_data[2].store(c.dur_bars, std::memory_order_relaxed);
    e->pulsar_wah_data[3].store(c.anom_params.rate_division, std::memory_order_relaxed);
    e->pulsar_wah_data[4].store(c.anom_params.depth, std::memory_order_relaxed);
    e->pulsar_wah_data[5].store(c.anom_params.resonance_q, std::memory_order_relaxed);
    e->pulsar_wah_data[6].store(c.anom_params.center_hz, std::memory_order_relaxed);
    e->pulsar_wah_data[7].store(c.anom_params.sweep_octaves, std::memory_order_relaxed);
    e->pulsar_wah_data[8].store(c.anom_params.wet, std::memory_order_relaxed);
    e->pulsar_wah_data[9].store(c.wah_declared ? 1.0f : 0.0f, std::memory_order_relaxed);
}

// Write one track's slot in the per-track lick-wah bank:
// [0]=mask, then kLickWahFields floats per track at 1 + t*kLickWahFields.
static void push_lick_wah_track(OrpheusEngine* e, int t, const orpheus::WahParams& p) {
    const int b = 1 + t * OrpheusEngine::kLickWahFields;
    e->pulsar_lick_wah_data[b + 0].store(p.rate_division, std::memory_order_relaxed);
    e->pulsar_lick_wah_data[b + 1].store(p.depth, std::memory_order_relaxed);
    e->pulsar_lick_wah_data[b + 2].store(p.resonance_q, std::memory_order_relaxed);
    e->pulsar_lick_wah_data[b + 3].store(p.center_hz, std::memory_order_relaxed);
    e->pulsar_lick_wah_data[b + 4].store(p.sweep_octaves, std::memory_order_relaxed);
    e->pulsar_lick_wah_data[b + 5].store(p.wet, std::memory_order_relaxed);
}

static void push_lick_wah_bank(OrpheusEngine* e, const WahCase& c) {
    e->pulsar_lick_wah_data[0].store((float)c.lick_wah_mask, std::memory_order_relaxed);
    // Mirrors the Kotlin marshal: every masked track gets params. The cases here voice
    // them all identically; PulsarWahPerTrackParams below is the one that differentiates.
    for (int t = 0; t < kNumPulsarTracks; t++) {
        if (c.lick_wah_mask & (1 << t)) push_lick_wah_track(e, t, c.lick_params);
    }
}

static Trace run_case(const WahCase& c) {
    Trace tr;
    OrpheusEngine* engine = orpheus_engine_create(kSR);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);

    setup_fixture_baseline(engine);
    for (int t = 0; t < 8; t++) {
        engine->pulsar_track_role[t].store(c.roles[t], std::memory_order_relaxed);
        engine->pulsar_track_lick_source[t].store(c.lick_sources[t], std::memory_order_relaxed);
        engine->pulsar_track_mute[t].store(0, std::memory_order_relaxed);
        // After setup_fixture_baseline (which writes the genre densities) and before the
        // vibe load, which is when the patterns are actually generated.
        engine->pulsar_track_density_override[t].store(c.density[t], std::memory_order_relaxed);
        engine->pulsar_track_engine_edm[t].store(c.engine_edm[t], std::memory_order_relaxed);
        engine->pulsar_track_engine_space[t].store(c.engine_space[t], std::memory_order_relaxed);
        engine->pulsar_track_envelope[t].store(c.envelopes[t], std::memory_order_relaxed);
    }
    push_two_section_ab_arrangement(engine, c.bars_per_section);
    push_wah_bank(engine, c);
    push_lick_wah_bank(engine, c);

    // solo AFTER setup_fixture_baseline (which writes the volumes) and BEFORE the vibe
    // load: ts.volume is latched once inside load_vibe, not re-read per block.
    solo_track(engine, c.solo);
    engine->pulsar_energy.store(1.0f, std::memory_order_relaxed);      // pins tempo drift to 0
    engine->pulsar_complexity.store(0.3f, std::memory_order_relaxed);
    engine->pulsar_space.store(0.4f, std::memory_order_relaxed);
    engine->pulsar_mood.store(0.5f, std::memory_order_relaxed);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    engine->clock_bpm.store(kBPM, std::memory_order_relaxed);
    engine->pulsar_bpm_override.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_seed.store(kPatternSeed, std::memory_order_relaxed);
    stmlib::Random::Seed(kNoiseSeed);
    trigger_vibe_load(engine);

    for (int i = 0; i < c.warm_blocks; i++)
        unit_process_pulsar(&unit, engine, kBlock, kSR);

    PulsarState* ps = engine->pulsar_state;   // valid: blocks already rendered
    tr.seed_before = ps->master_anomaly_seed;
    if (c.manual) engine->pulsar_anomaly_request.store(1, std::memory_order_release);

    tr.l.reserve((size_t)c.cap_blocks * kBlock);
    tr.left.reserve(c.cap_blocks);
    int prev_left = 0;
    for (int b = 0; b < c.cap_blocks; b++) {
        unit_process_pulsar(&unit, engine, kBlock, kSR);
        for (int i = 0; i < kBlock; i++) tr.l.push_back(engine->pulsar_out_l[i]);
        int left = ps->anomaly_wah_samples_left;
        tr.left.push_back(left);
        if (left < tr.min_left) tr.min_left = left;
        if (left > 0 && prev_left <= 0 && tr.arm_block < 0) {
            tr.arm_block = b;
            tr.total = ps->anomaly_wah_samples_total;
            tr.mask  = ps->anomaly_wah_mask;
        }
        if (left > 0 || ps->anomaly_wah_samples_total > 0) tr.ever_armed = true;
        prev_left = left;
    }
    tr.seed_after = ps->master_anomaly_seed;
    tr.anom_phase = ps->anomaly_wah_voice[c.solo].lfo_phase;
    tr.lick_phase = ps->lick_wah_voice[c.solo].lfo_phase;
    orpheus_engine_destroy(engine);
    return tr;
}

// ── Measurement helpers ─────────────────────────────────────────────────
static int first_diff(const std::vector<float>& a, const std::vector<float>& b) {
    size_t n = a.size() < b.size() ? a.size() : b.size();
    for (size_t i = 0; i < n; i++) if (a[i] != b[i]) return (int)i;
    return -1;
}
static bool identical(const std::vector<float>& a, const std::vector<float>& b) {
    return a.size() == b.size() &&
           std::memcmp(a.data(), b.data(), a.size() * sizeof(float)) == 0;
}
static float peak_range(const std::vector<float>& v, int lo, int hi) {
    float p = 0.0f;
    for (int i = lo; i < hi && i < (int)v.size(); i++) {
        float a = std::fabs(v[i]); if (a > p) p = a;
    }
    return p;
}
static float rms_range(const std::vector<float>& v, int lo, int hi) {
    double s = 0.0; int n = 0;
    for (int i = lo; i < hi && i < (int)v.size(); i++) { s += (double)v[i] * v[i]; n++; }
    return n ? (float)std::sqrt(s / n) : 0.0f;
}
static float rms_diff_range(const std::vector<float>& a, const std::vector<float>& b,
                            int lo, int hi) {
    double s = 0.0; int n = 0;
    for (int i = lo; i < hi && i < (int)a.size() && i < (int)b.size(); i++) {
        double d = (double)a[i] - b[i]; s += d * d; n++;
    }
    return n ? (float)std::sqrt(s / n) : 0.0f;
}
static float max_diff_range(const std::vector<float>& a, const std::vector<float>& b,
                            int lo, int hi) {
    float m = 0.0f;
    for (int i = lo; i < hi && i < (int)a.size() && i < (int)b.size(); i++) {
        float d = std::fabs(a[i] - b[i]); if (d > m) m = d;
    }
    return m;
}
static float max_jump(const std::vector<float>& v, int lo, int hi) {
    float m = 0.0f;
    if (lo < 1) lo = 1;
    for (int i = lo; i < hi && i < (int)v.size(); i++) {
        float d = std::fabs(v[i] - v[i - 1]); if (d > m) m = d;
    }
    return m;
}
static bool all_finite(const std::vector<float>& v) {
    for (float x : v) if (!std::isfinite(x)) return false;
    return true;
}

// Role layout with exactly ONE eligible lead, on track 4. Needed whenever a test pins
// the mask to a single bit: the baseline fixture's stock roles make 3,4,5,7 melodic, so the
// stock eligible mask is 0xB0. Track 3 stays melodic here on purpose, so these cases
// still exercise the kBassTrack exclusion rather than sidestepping it.
static const int kOneLeadOnTrack4[8] = {0, 0, 0, 1, 1, 0, 0, 0};

// Density for a solo'd lead that has to be audible. Raising it is necessary but NOT
// sufficient: a melodic track does not start sounding until well into the run and then
// rests for stretches of ~25k samples, so a 1-bar (48000 sample) window regularly lands
// entirely inside silence. Measured profile of a solo'd track-4 lead, dry, per eighth of
// a 204800-sample capture:  rms 0, 0, 0, 0.0502, 0.00004, 0.0205, 0, 0.0168.
static constexpr float kDenseLead = 0.95f;

// So any test that measures "the wah is audible" arms a FOUR bar window instead, which
// is long enough to span those rests, and captures long enough to hold it. This is a
// measurement-window choice only: the shipped durationBars range is untouched (D4), and
// the exact-duration tests below deliberately still use 1 bar.
static constexpr float kAudibleWindowBars   = 4.0f;
static constexpr int   kAudibleCaptureBlocks = 640;   // 327680 samples > arm + 4 bars

// Distinct, deliberately audible parameter sets. The anomaly must be clearly different
// from the standing lick wah so a takeover is measurable. These are TEST fixtures, not
// a retune of any shipped vibe (D4: no authored value is touched by this change).
static orpheus::WahParams lick_fixture() {
    orpheus::WahParams p;
    p.rate_division = 4.0f; p.depth = 0.85f; p.resonance_q = 3.0f;
    p.center_hz = 750.0f;   p.sweep_octaves = 1.2f; p.wet = 0.9f;
    return p;
}
static orpheus::WahParams anom_fixture() {
    orpheus::WahParams p;
    p.rate_division = 8.0f; p.depth = 1.0f; p.resonance_q = 5.0f;
    p.center_hz = 400.0f;   p.sweep_octaves = 1.5f; p.wet = 0.9f;
    return p;
}

// ═══════════════════════════════════════════════════════════════════════
// D1 — ineligible tracks stay BYTE-identical while a window is armed.
// A/B: run A leaves the wah undeclared, run B declares + fires it. Because the bars
// draw is degenerate (dur_min == dur_max) neither run consumes the anomaly RNG, so
// the two runs generate identical notes and any difference is the wah insert alone.
// On the old master-bus routing every one of these fails: the master wah filtered the
// summed mix, so a solo'd percussive/chordal/bass track differed too.
// ═══════════════════════════════════════════════════════════════════════
static bool ineligible_track_case(const char* label, int solo, int role, int lick_source) {
    printf("\n=== wah_anomaly: %s stays byte-identical while armed ===\n", label);
    WahCase c;
    c.solo = solo;
    c.roles[solo] = role;
    c.lick_sources[solo] = lick_source;
    c.anom_params = anom_fixture();

    c.wah_declared = false;  Trace a = run_case(c);
    c.wah_declared = true;   Trace b = run_case(c);

    int fd = first_diff(a.l, b.l);
    float pk = peak_range(a.l, 0, (int)a.l.size());
    bool armed_ok    = b.arm_block >= 0 && b.total > 0;      // the window really opened
    bool bit_ok      = identical(a.l, b.l);
    bool mask_ok     = (b.mask & (1 << solo)) == 0;          // this track is NOT in it
    bool nonvacuous  = pk > 0.02f;                           // the track actually sounds
    bool ok = armed_ok && bit_ok && mask_ok && nonvacuous;
    printf("  solo=%d role=%d lick_source=%d armed_total=%d mask=0x%02X\n",
           solo, role, lick_source, b.total, b.mask);
    printf("  peak(dry)=%.4f first_diff=%d (expect -1) bit_identical=%d mask_bit_clear=%d -- %s\n",
           pk, fd, bit_ok, mask_ok, ok ? "PASS" : "FAIL");
    if (!armed_ok) printf("  FAIL: no window ever armed, so the identity claim is vacuous\n");
    if (!nonvacuous) printf("  FAIL: track is silent, so the identity claim is vacuous\n");
    return ok;
}

static bool test_percussive_track_untouched() {
    // Track 0, PERC. The headline bug: the master-bus wah wah'd the drums.
    return ineligible_track_case("percussive track (role PERC)", 0, 0, 0);
}
static bool test_chordal_track_untouched() {
    return ineligible_track_case("chordal track (role CHORDAL)", 5, 2, 0);
}
static bool test_bass_track_3_never_eligible() {
    // Track 3 is hard-wired to the bass bus in every scene. MELODIC + LEAD here, so
    // ONLY the `t != kBassTrack` clause can exclude it.
    return ineligible_track_case("track 3 (MELODIC + LEAD, but the bass track)", 3, 1, 0);
}
static bool test_bass_lick_source_track_untouched() {
    // Track 4 is MELODIC and is not track 3, so ONLY the LickSource.BASS clause
    // can exclude it. This pins the bass-line-channel half of the predicate.
    return ineligible_track_case("melodic track reading the BASS lick channel", 4, 1, 1);
}

// ═══════════════════════════════════════════════════════════════════════
// A lead with no standing wah IS filtered while armed, and is dry again after.
// ═══════════════════════════════════════════════════════════════════════
static bool test_lead_is_filtered_and_returns_to_dry() {
    printf("\n=== wah_anomaly: lead track is filtered while armed, dry again after ===\n");
    WahCase c;
    c.solo = 4;
    std::memcpy(c.roles, kOneLeadOnTrack4, sizeof(c.roles));   // mask must be exactly 1<<4
    c.lick_sources[4] = 0;
    c.density[4] = kDenseLead;                  // so the sustain range is not a pattern gap
    c.dur_bars = kAudibleWindowBars;
    c.cap_blocks = kAudibleCaptureBlocks;
    c.anom_params = anom_fixture();
    c.lick_wah_mask = 0;                        // fresh voice, no standing insert

    c.wah_declared = false;  Trace a = run_case(c);
    c.wah_declared = true;   Trace b = run_case(c);

    if (b.arm_block < 0) { printf("  FAIL: window never armed\n"); return false; }
    const int arm = b.arm_block * kBlock;       // the env fill is block aligned
    const int end = arm + b.total - 1;          // last armed sample
    const int s_lo = arm + b.total / 4;
    const int s_hi = arm + (b.total * 3) / 4;

    // (a) the mask + the drawn duration are exactly the arithmetic they claim to be.
    const int expect_total = anomaly_arm_samples(c.dur_bars, kSamplesPerStep);
    bool mask_ok  = b.mask == (uint8_t)(1 << 4);
    bool total_ok = b.total == expect_total;

    // (b) nothing before the arm, and the FIRST armed sample is bit-identical: the
    // trapezoid starts at env == 0 exactly, so wet == 0 and process_sample returns
    // its input unchanged. That is the no-step-at-the-arm guarantee in its strongest
    // form, and it makes the arm boundary click-free by construction.
    int fd = first_diff(a.l, b.l);
    bool pre_ok   = fd < 0 || fd > arm;
    bool edge_ok  = b.l[arm] == a.l[arm];

    // (c) audible while armed, and bounded.
    float rms_dry  = rms_range(a.l, s_lo, s_hi);
    float rms_wet  = rms_range(b.l, s_lo, s_hi);
    float rms_delta = rms_diff_range(a.l, b.l, s_lo, s_hi);
    bool audible = rms_dry > 1e-3f && rms_delta > 0.10f * rms_dry;
    bool bounded = peak_range(b.l, 0, (int)b.l.size()) < 2.0f && all_finite(b.l);

    // (d) the closing edge is near-identity: env on the last armed sample is
    // (1/total)/0.15 ~ 1.4e-4, so the wah has already faded out. It is NOT exactly
    // zero (the trapezoid never reaches 0 on a sample it still owns), hence a bound
    // rather than an equality.
    float close_delta = std::fabs(b.l[end] - a.l[end]);
    bool close_ok = close_delta < 1e-3f;

    // (e) after the window the insert is gated off entirely, so the wah stops acting.
    // This is deliberately NOT a bitwise-dry assertion. The Pulsar bus ends in two
    // persistent high-pass filters (state->output_hpf_l / output_hpf2_l, applied to
    // out_l near the end of unit_process_pulsar). While the window was open the two runs
    // fed those IIRs different input, so their internal state diverged, and once the wah
    // is off they ring down from that divergence rather than snapping back. A residual
    // that is orders of magnitude below the in-window effect AND decaying is exactly what
    // "the wah stopped" looks like through a stateful bus; demanding bitwise equality
    // would be asserting something the signal chain never promised.
    const int tail_lo  = end + 1;
    const int tail_len = (int)a.l.size() - tail_lo;
    float in_window_max = max_diff_range(a.l, b.l, s_lo, s_hi);
    float tail_max   = max_diff_range(a.l, b.l, tail_lo, (int)a.l.size());
    float tail_early = max_diff_range(a.l, b.l, tail_lo, tail_lo + tail_len / 4);
    float tail_late  = max_diff_range(a.l, b.l, (int)a.l.size() - tail_len / 4, (int)a.l.size());
    bool tail_small   = tail_max < 1e-3f * in_window_max;
    bool tail_decays  = tail_late < tail_early;
    bool tail_ok = tail_small && tail_decays;

    // (f) white box: the fresh voice really ran, and the untouched lick voice did not.
    bool voice_ok = b.anom_phase != 0.0 && b.lick_phase == 0.0;

    bool ok = mask_ok && total_ok && pre_ok && edge_ok && audible && bounded
              && close_ok && tail_ok && voice_ok;
    printf("  mask=0x%02X (expect 0x10) total=%d (expect %d) arm@%d end@%d\n",
           b.mask, b.total, expect_total, arm, end);
    printf("  pre_arm_clean=%d (first_diff=%d) arm_sample_bit_identical=%d\n",
           pre_ok, fd, edge_ok);
    printf("  sustain rms dry=%.5f wet=%.5f delta=%.5f (need > %.5f) audible=%d\n",
           rms_dry, rms_wet, rms_delta, 0.10f * rms_dry, audible);
    printf("  close_edge_delta=%.3e (<1e-3)=%d bounded=%d\n", close_delta, close_ok, bounded);
    printf("  tail: max=%.3e vs in_window=%.3e (ratio %.2e, need <1e-3)=%d "
           "early=%.3e late=%.3e decaying=%d\n",
           tail_max, in_window_max, in_window_max > 0 ? tail_max / in_window_max : 0.0f,
           tail_small, tail_early, tail_late, tail_decays);
    printf("  anomaly_voice_phase=%.6f lick_voice_phase=%.6f (fresh path) -- %s\n",
           b.anom_phase, b.lick_phase, ok ? "PASS" : "FAIL");
    return ok;
}

// ═══════════════════════════════════════════════════════════════════════
// D2 — no eligible lead => the anomaly does NOT fire, and does not even draw.
// ═══════════════════════════════════════════════════════════════════════
static bool test_empty_lead_mask_never_arms_on_the_gesture() {
    printf("\n=== wah_anomaly: empty lead mask never arms on the manual gesture ===\n");
    WahCase c;
    for (int t = 0; t < 8; t++) { c.roles[t] = 0; c.lick_sources[t] = 0; }  // all PERC
    c.solo = 4;
    c.anom_params = anom_fixture();

    c.wah_declared = false;  Trace a = run_case(c);
    c.wah_declared = true;   Trace b = run_case(c);

    bool never_armed = !b.ever_armed && b.arm_block < 0 && b.mask == 0;
    bool seed_ok     = b.seed_after == b.seed_before;   // the bars draw never happened
    bool bit_ok      = identical(a.l, b.l);
    bool nonvacuous  = peak_range(a.l, 0, (int)a.l.size()) > 0.02f;
    bool ok = never_armed && seed_ok && bit_ok && nonvacuous;
    printf("  ever_armed=%d mask=0x%02X seed %u -> %u (unchanged=%d) bit_identical=%d -- %s\n",
           b.ever_armed, b.mask, b.seed_before, b.seed_after, seed_ok, bit_ok,
           ok ? "PASS" : "FAIL");
    return ok;
}

// The strict reading of D2: the mask must be resolved BEFORE pattern_rand01 draws the
// probability roll. With probability 1.0 and no eligible lead, crossing several section
// boundaries must leave master_anomaly_seed untouched. An implementation that rolls
// first and masks second passes every other test in this file and fails only this one.
// The eligible-lead counterpart runs the same setup with one melodic lead restored, so
// the "seed unchanged" claim cannot pass vacuously.
static bool test_empty_lead_mask_does_not_consume_the_probability_roll() {
    printf("\n=== wah_anomaly: empty lead mask does not consume the auto roll ===\n");
    WahCase c;
    for (int t = 0; t < 8; t++) { c.roles[t] = 0; c.lick_sources[t] = 0; }  // all PERC
    c.solo = 0;
    c.anom_params = anom_fixture();
    c.probability = 1.0f;         // would fire on EVERY section boundary if eligible
    c.manual = false;             // auto path only
    c.bars_per_section = 2;       // 2 loop cycles = 96000 samples = 187.5 blocks
    c.warm_blocks = 40;
    c.cap_blocks = 800;           // crosses several boundaries

    Trace empty = run_case(c);

    // Counterpart: restore one eligible lead. Same probability, same boundaries.
    c.roles[4] = 1; c.solo = 4;
    Trace lead = run_case(c);

    bool empty_never   = !empty.ever_armed && empty.mask == 0;
    bool empty_no_draw = empty.seed_after == empty.seed_before;
    bool lead_armed    = lead.ever_armed && (lead.mask & (1 << 4)) != 0;
    bool lead_drew     = lead.seed_after != lead.seed_before;
    bool ok = empty_never && empty_no_draw && lead_armed && lead_drew;
    printf("  empty mask: ever_armed=%d mask=0x%02X seed %u -> %u unchanged=%d\n",
           empty.ever_armed, empty.mask, empty.seed_before, empty.seed_after, empty_no_draw);
    printf("  one lead:   ever_armed=%d mask=0x%02X seed %u -> %u changed=%d (non-vacuous)\n",
           lead.ever_armed, lead.mask, lead.seed_before, lead.seed_after, lead_drew);
    printf("  %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// ═══════════════════════════════════════════════════════════════════════
// D3 — TAKEOVER. Exactly one bandpass; continuous at both boundaries.
// ═══════════════════════════════════════════════════════════════════════

// The structural proof, and the sharpest test in the file. Give the anomaly EXACTLY the
// standing lick wah's params. A correct takeover then lerps from p to p, i.e. runs the
// same single voice with the same params for the whole window, so the armed run must be
// BYTE-identical to the un-armed one over the entire capture — including the tail, since
// neither the filter state nor the LFO phase was disturbed.
//
// This one assertion rejects every plausible wrong implementation at once:
//   * cascading a second bandpass          -> the sustain is filtered twice, differs;
//   * Init()ing / re-seating the voice     -> the phase and Svf state reset, differs;
//   * switching params in one step         -> identical params, but a reset still shows;
//   * arming a master-bus stage instead    -> the whole mix is filtered, differs.
static bool test_takeover_with_identical_params_is_a_byte_identical_noop() {
    printf("\n=== wah_anomaly: takeover with identical params is a byte-identical no-op ===\n");
    WahCase c;
    c.solo = 4;
    std::memcpy(c.roles, kOneLeadOnTrack4, sizeof(c.roles));   // mask must be exactly 1<<4
    c.lick_sources[4] = 0;
    c.density[4] = kDenseLead;                  // a silent track would pass this vacuously
    c.lick_wah_mask = (uint8_t)(1 << 4);
    c.lick_params = lick_fixture();
    c.anom_params = c.lick_params;              // the whole point

    c.wah_declared = false;  Trace a = run_case(c);   // standing lick wah only
    c.wah_declared = true;   Trace b = run_case(c);   // + anomaly armed on top

    bool armed_ok = b.arm_block >= 0 && b.total > 0 && b.mask == (uint8_t)(1 << 4);
    bool bit_ok   = identical(a.l, b.l);
    // The spare per-track voice must never have been touched on a takeover track.
    bool spare_untouched = b.anom_phase == 0.0;
    // The standing voice ran in both, and ended on the same phase (no reset, no drift).
    bool phase_ok = b.lick_phase == a.lick_phase && b.lick_phase != 0.0;
    bool nonvacuous = peak_range(a.l, 0, (int)a.l.size()) > 0.02f;
    bool ok = armed_ok && bit_ok && spare_untouched && phase_ok && nonvacuous;
    int fd = first_diff(a.l, b.l);
    printf("  armed mask=0x%02X total=%d arm_block=%d\n", b.mask, b.total, b.arm_block);
    printf("  first_diff=%d (expect -1) bit_identical=%d spare_voice_phase=%.6f (expect 0)\n",
           fd, bit_ok, b.anom_phase);
    printf("  lick_voice_phase A=%.6f B=%.6f equal=%d -- %s\n",
           a.lick_phase, b.lick_phase, phase_ok, ok ? "PASS" : "FAIL");
    return ok;
}

// The behavioural half: with DIFFERENT params the takeover must be audible, must enter
// and leave without a step, and must still be exactly ONE bandpass.
//
// "Exactly one bandpass" is measured against the fresh-voice path on the same track with
// the same anomaly params (test above). Both are one bandpass with the anomaly's params;
// they differ only in filter history and LFO phase, so their sustain RMS relative to dry
// must land within a small factor of each other. Two mismatched bandpasses in series
// (750 Hz then 400 Hz, each at wet 0.9) each reject the other's passband and collapse
// the RMS by roughly an order of magnitude, landing far outside this window.
static bool test_takeover_is_one_bandpass_and_continuous() {
    printf("\n=== wah_anomaly: takeover is one bandpass, continuous at both edges ===\n");
    WahCase base;
    base.solo = 4;
    std::memcpy(base.roles, kOneLeadOnTrack4, sizeof(base.roles));
    base.lick_sources[4] = 0;
    base.density[4] = kDenseLead;   // every assertion below is an RMS ratio: needs signal
    base.dur_bars = kAudibleWindowBars;
    base.cap_blocks = kAudibleCaptureBlocks;
    base.anom_params = anom_fixture();

    // DRY: no standing wah, no anomaly.
    WahCase dry_c = base; dry_c.lick_wah_mask = 0; dry_c.wah_declared = false;
    Trace dry = run_case(dry_c);

    // FRESH: no standing wah, anomaly on a fresh voice.
    WahCase fresh_c = base; fresh_c.lick_wah_mask = 0; fresh_c.wah_declared = true;
    Trace fresh = run_case(fresh_c);

    // A: standing lick wah only.   B: standing lick wah + anomaly takeover.
    WahCase take_c = base;
    take_c.lick_wah_mask = (uint8_t)(1 << 4);
    take_c.lick_params = lick_fixture();
    take_c.wah_declared = false;  Trace a = run_case(take_c);
    take_c.wah_declared = true;   Trace b = run_case(take_c);

    if (b.arm_block < 0 || fresh.arm_block < 0) {
        printf("  FAIL: window never armed (takeover=%d fresh=%d)\n",
               b.arm_block, fresh.arm_block);
        return false;
    }
    const int arm = b.arm_block * kBlock;
    const int end = arm + b.total - 1;
    const int s_lo = arm + b.total / 4;
    const int s_hi = arm + (b.total * 3) / 4;

    // Both runs must have armed at the same place, or the comparisons below are apples
    // to oranges. They should: the arm is driven by the sequencer, not by the wah.
    bool aligned = fresh.arm_block == b.arm_block && fresh.total == b.total;

    // (a) entry continuity, exact. env == 0 on the first armed sample, so the lerp
    // returns the standing params bit-for-bit and the SAME running voice produces the
    // SAME sample. Combined with a bit-identical pre-arm region this makes the arm
    // boundary jump identical to the un-armed signal's own jump: no click, by proof
    // rather than by threshold.
    int fd = first_diff(a.l, b.l);
    bool pre_ok  = fd < 0 || fd > arm;
    bool edge_ok = b.l[arm] == a.l[arm];

    // (b) audible during the sustain.
    float rms_a  = rms_range(a.l, s_lo, s_hi);
    float rms_b  = rms_range(b.l, s_lo, s_hi);
    float delta  = rms_diff_range(a.l, b.l, s_lo, s_hi);
    bool audible = rms_a > 1e-3f && delta > 0.10f * rms_a;

    // (c) exactly one bandpass, measured against the fresh-voice path.
    float rms_dry   = rms_range(dry.l, s_lo, s_hi);
    float rms_fresh = rms_range(fresh.l, s_lo, s_hi);
    float r_take  = rms_dry > 0.0f ? rms_b / rms_dry : 0.0f;
    float r_fresh = rms_dry > 0.0f ? rms_fresh / rms_dry : 0.0f;
    float ratio   = r_fresh > 0.0f ? r_take / r_fresh : 0.0f;
    bool one_bandpass = rms_dry > 1e-3f && ratio > 0.33f && ratio < 3.0f;

    // (d) white box: the spare voice was never Init'd or advanced on a takeover track,
    // so only ONE WahVoice can possibly have run on it.
    bool spare_untouched = b.anom_phase == 0.0;

    // (e) exit continuity. The takeover's phase has legitimately diverged from A's by
    // the end of the window (rate_division differed), so the exit cannot be compared
    // against A. Compare it against B's own signal instead: the sample-to-sample jump
    // across the disarm must not stand out from the jumps the filtered signal already
    // makes. A hard param switch or a voice teardown would show up here as a step.
    float jump_exit = std::fabs(b.l[end + 1] - b.l[end]);
    float typical   = max_jump(b.l, arm + 64, end - 64);
    bool exit_ok = jump_exit <= 2.0f * typical + 1e-3f;

    bool bounded = peak_range(b.l, 0, (int)b.l.size()) < 2.0f && all_finite(b.l);
    bool ok = aligned && pre_ok && edge_ok && audible && one_bandpass
              && spare_untouched && exit_ok && bounded;

    printf("  arm_block take=%d fresh=%d total take=%d fresh=%d aligned=%d\n",
           b.arm_block, fresh.arm_block, b.total, fresh.total, aligned);
    printf("  entry: first_diff=%d pre_arm_clean=%d arm_sample_bit_identical=%d\n",
           fd, pre_ok, edge_ok);
    printf("  sustain rms: dry=%.5f standing=%.5f takeover=%.5f fresh=%.5f delta(A,B)=%.5f\n",
           rms_dry, rms_a, rms_b, rms_fresh, delta);
    printf("  one-bandpass: r_take=%.4f r_fresh=%.4f ratio=%.4f (need 0.33..3.0) -> %d\n",
           r_take, r_fresh, ratio, one_bandpass);
    printf("  exit: jump=%.3e typical_jump=%.3e ok=%d spare_voice_phase=%.6f\n",
           jump_exit, typical, exit_ok, b.anom_phase);
    printf("  audible=%d bounded=%d -- %s\n", audible, bounded, ok ? "PASS" : "FAIL");
    return ok;
}

// ═══════════════════════════════════════════════════════════════════════
// Armed duration matches the drawn bars — the once-per-BLOCK bookkeeping guard.
// The arm runs inside the per-track loop, whose body executes 8 times per block. A
// naive per-track decrement would burn 8 * num_frames per block, ending the window
// ~8x early and desyncing the eight tracks' LFO phases. This asserts the exact
// per-block decrement, the exact block count, and the exact total.
// ═══════════════════════════════════════════════════════════════════════
static bool duration_case(const char* label, WahCase c) {
    printf("\n=== wah_anomaly: armed duration matches drawn bars (%s) ===\n", label);
    Trace t = run_case(c);
    if (t.arm_block < 0) { printf("  FAIL: window never armed\n"); return false; }

    const int expect_total  = anomaly_arm_samples(c.dur_bars, kSamplesPerStep);
    const int expect_blocks = (expect_total + kBlock - 1) / kBlock;
    const int expect_tail   = expect_total - (expect_blocks - 1) * kBlock;

    bool total_ok = t.total == expect_total;
    // Walk the observed countdown. left[] is sampled AFTER each block.
    bool step_ok = true;
    int armed_blocks = 0;
    int prev = t.total;
    int bad_block = -1, bad_delta = 0;
    for (int b = t.arm_block; b < (int)t.left.size(); b++) {
        int cur = t.left[b];
        int delta = prev - cur;
        armed_blocks++;
        bool last = (cur == 0);
        int want = last ? expect_tail : kBlock;
        if (delta != want && step_ok) { step_ok = false; bad_block = b; bad_delta = delta; }
        prev = cur;
        if (last) break;
    }
    bool blocks_ok = armed_blocks == expect_blocks;
    bool never_negative = t.min_left >= 0;
    bool landed_zero = prev == 0;
    bool ok = total_ok && step_ok && blocks_ok && never_negative && landed_zero;
    printf("  bars=%.2f samples_per_step=%.1f total=%d (expect %d)\n",
           c.dur_bars, kSamplesPerStep, t.total, expect_total);
    printf("  armed_blocks=%d (expect %d) tail=%d per-block decrement exact=%d\n",
           armed_blocks, expect_blocks, expect_tail, step_ok);
    if (!step_ok)
        printf("  FAIL: block %d consumed %d samples, expected %d "
               "(a per-track decrement would consume %d)\n",
               bad_block, bad_delta, kBlock, kBlock * 8);
    printf("  min_left=%d (>=0)=%d landed_on_zero=%d -- %s\n",
           t.min_left, never_negative, landed_zero, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_armed_duration_single_lead() {
    WahCase c;
    c.solo = 4; c.roles[4] = 1; c.lick_sources[4] = 0;
    c.anom_params = anom_fixture();
    c.wah_declared = true;
    c.dur_bars = 1.0f;
    return duration_case("one eligible lead", c);
}

static bool test_armed_duration_two_leads_still_decrements_once_per_block() {
    // Two eligible leads. If the countdown were committed per eligible TRACK instead of
    // once per block, this case would drain twice as fast as the single-lead case above,
    // so the pair together pins "exactly once per block" rather than "once per arm".
    WahCase c;
    c.roles[4] = 1; c.roles[6] = 1;
    c.lick_sources[4] = 0; c.lick_sources[6] = 0;
    c.solo = 4;
    c.anom_params = anom_fixture();
    c.wah_declared = true;
    c.dur_bars = 1.0f;
    return duration_case("two eligible leads", c);
}

// ═══════════════════════════════════════════════════════════════════════
// The mask covers EVERY eligible lead, and each runs its own voice.
// Shaped like the shipped Odysseus Lore layout: one wah'd lead (takeover) plus a
// second bare melodic lead (fresh voice), a melodic BASS-channel track, a chordal
// track, and drums. Expected mask = tracks 4 and 6 = 0b01010000 = 0x50.
// ═══════════════════════════════════════════════════════════════════════
static bool test_mask_covers_every_eligible_lead() {
    printf("\n=== wah_anomaly: mask covers every eligible lead, others untouched ===\n");
    WahCase base;
    base.roles[0] = 0; base.roles[1] = 0; base.roles[2] = 0;
    base.roles[3] = 1; base.lick_sources[3] = 1;   // melodic, but the BASS channel
    base.roles[4] = 1; base.lick_sources[4] = 0;   // lead, carries the standing wah
    base.roles[5] = 2;                             // chordal
    base.roles[6] = 1; base.lick_sources[6] = 0;   // lead, bare
    base.roles[7] = 0;
    // Both eligible leads carry an RMS-delta assertion below, so both need to play,
    // and the window has to be long enough to span a melodic track's rests.
    base.density[4] = kDenseLead;
    base.density[6] = kDenseLead;
    // Track 6 ships as NSE / envelope 2 and renders silent under the MELODIC role, which
    // would make its "is filtered" assertion vacuously bit-identical. Give it track 4's
    // lead voice so the second eligible lead is an audible one.
    base.engine_edm[6]   = base.engine_edm[4];
    base.engine_space[6] = base.engine_space[4];
    base.envelopes[6]    = base.envelopes[4];
    base.dur_bars = kAudibleWindowBars;
    base.cap_blocks = kAudibleCaptureBlocks;
    base.anom_params = anom_fixture();
    base.lick_wah_mask = (uint8_t)(1 << 4);
    base.lick_params = lick_fixture();

    const uint8_t expect_mask = (uint8_t)((1 << 4) | (1 << 6));
    bool ok = true;
    uint8_t seen_mask = 0;

    struct Expect { int track; bool filtered; const char* why; };
    const Expect table[] = {
        {0, false, "PERC"},
        {3, false, "MELODIC but BASS channel + bass track"},
        {4, true,  "LEAD with standing wah (takeover)"},
        {5, false, "CHORDAL"},
        {6, true,  "LEAD, bare (fresh voice)"},
        {7, false, "PERC"},
    };

    for (const Expect& e : table) {
        WahCase c = base; c.solo = e.track;
        c.wah_declared = false;  Trace a = run_case(c);
        c.wah_declared = true;   Trace b = run_case(c);
        if (b.arm_block < 0) { printf("  FAIL: no window armed for solo=%d\n", e.track); ok = false; continue; }
        seen_mask = b.mask;
        const int arm  = b.arm_block * kBlock;
        const int s_lo = arm + b.total / 4;
        const int s_hi = arm + (b.total * 3) / 4;
        bool bit_same  = identical(a.l, b.l);
        bool bit_set   = (b.mask & (1 << e.track)) != 0;
        float rms_ref  = rms_range(a.l, s_lo, s_hi);
        float delta    = rms_diff_range(a.l, b.l, s_lo, s_hi);
        bool track_ok;
        if (e.filtered) {
            track_ok = bit_set && !bit_same && rms_ref > 1e-3f && delta > 0.10f * rms_ref;
        } else {
            track_ok = !bit_set && bit_same;
        }
        ok &= track_ok;
        printf("  t=%d %-40s mask_bit=%d bit_identical=%d rms_delta=%.5f (ref %.5f) -- %s\n",
               e.track, e.why, bit_set, bit_same, delta, rms_ref, track_ok ? "ok" : "FAIL");
    }

    bool mask_ok = seen_mask == expect_mask;
    ok &= mask_ok;
    printf("  mask=0x%02X expect 0x%02X (tracks 4 and 6) -- %s\n",
           seen_mask, expect_mask, ok ? "PASS" : "FAIL");
    return ok;
}

// Per-track wah voicing: two tracks that both carry the standing insert must unpack to
// DIFFERENT params and sweep at DIFFERENT rates. This is what lets a bass rock a bar-long
// pedal down low while a lead works a quarter-note pedal up in the vowel range.
//
// Fails on the old single shared lick_wah_params: there both tracks read one struct, so
// the params would compare equal and both LFOs would advance in lockstep (ratio 1.0).
//
// Arithmetic: rate_division 1.0 => period 16 steps = 48000 samples; 0.25 => 64 steps =
// 192000. 80 blocks = 40960 samples, so neither phase reaches 1.0 and never wraps, and
// the phase ratio is exactly the rate ratio, 4.0. Phase is a pure function of elapsed
// samples, so this needs no audio measurement and cannot flake on sparse patterns.
static bool test_per_track_wah_params_are_independent() {
    OrpheusEngine* engine = orpheus_engine_create(kSR);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);

    setup_fixture_baseline(engine);
    // No solo here: solo_track() zeroes the other volumes, and this case needs BOTH
    // wah'd tracks rendering at once.
    for (int t = 0; t < kNumPulsarTracks; t++) {
        engine->pulsar_track_mute[t].store(0, std::memory_order_relaxed);
        engine->pulsar_track_density_override[t].store(0.0f, std::memory_order_relaxed);
    }
    engine->pulsar_track_density_override[4].store(0.9f, std::memory_order_relaxed);
    engine->pulsar_track_density_override[5].store(0.9f, std::memory_order_relaxed);
    push_two_section_ab_arrangement(engine, 8);

    // Standing insert on tracks 4 and 5, voiced four-to-one apart. Anomaly bank left
    // undeclared so nothing can take either voice over mid-run.
    const int bank_size = (int)(sizeof(engine->pulsar_lick_wah_data) /
                                sizeof(engine->pulsar_lick_wah_data[0]));
    for (int i = 0; i < bank_size; i++)
        engine->pulsar_lick_wah_data[i].store(0.0f, std::memory_order_relaxed);
    orpheus::WahParams fast{}; fast.rate_division = 1.0f;  fast.center_hz = 900.0f;
    orpheus::WahParams slow{}; slow.rate_division = 0.25f; slow.center_hz = 300.0f;
    engine->pulsar_lick_wah_data[0].store((float)((1 << 4) | (1 << 5)), std::memory_order_relaxed);
    push_lick_wah_track(engine, 4, fast);
    push_lick_wah_track(engine, 5, slow);
    engine->pulsar_wah_data[0].store(0.0f, std::memory_order_relaxed);   // probability
    engine->pulsar_wah_data[9].store(0.0f, std::memory_order_relaxed);   // declared

    engine->pulsar_energy.store(1.0f, std::memory_order_relaxed);   // pins tempo drift to 0
    engine->pulsar_complexity.store(0.3f, std::memory_order_relaxed);
    engine->pulsar_space.store(0.4f, std::memory_order_relaxed);
    engine->pulsar_mood.store(0.5f, std::memory_order_relaxed);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    engine->clock_bpm.store(kBPM, std::memory_order_relaxed);
    engine->pulsar_bpm_override.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_seed.store(kPatternSeed, std::memory_order_relaxed);
    stmlib::Random::Seed(kNoiseSeed);
    trigger_vibe_load(engine);

    for (int i = 0; i < 80; i++) unit_process_pulsar(&unit, engine, kBlock, kSR);

    PulsarState* ps = engine->pulsar_state;
    const bool params_ok =
        ps->lick_wah_params[4].rate_division == 1.0f  &&
        ps->lick_wah_params[5].rate_division == 0.25f &&
        ps->lick_wah_params[4].center_hz    == 900.0f &&
        ps->lick_wah_params[5].center_hz    == 300.0f;
    const double ph_fast = ps->lick_wah_voice[4].lfo_phase;
    const double ph_slow = ps->lick_wah_voice[5].lfo_phase;
    const bool no_wrap   = ph_fast < 1.0 && ph_slow > 0.0 && ph_slow < 1.0;
    const double ratio   = ph_slow > 0.0 ? ph_fast / ph_slow : 0.0;
    const bool ratio_ok  = no_wrap && std::fabs(ratio - 4.0) < 0.05;

    printf("  unpack t4 rate=%.2f center=%.0f | t5 rate=%.2f center=%.0f -- %s\n",
           ps->lick_wah_params[4].rate_division, ps->lick_wah_params[4].center_hz,
           ps->lick_wah_params[5].rate_division, ps->lick_wah_params[5].center_hz,
           params_ok ? "PASS" : "FAIL");
    printf("  phase t4=%.5f t5=%.5f ratio=%.3f expect 4.000 -- %s\n",
           ph_fast, ph_slow, ratio, ratio_ok ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return params_ok && ratio_ok;
}

bool run_pulsar_wah_anomaly_tests() {
    bool ok = true;
    ok &= test_per_track_wah_params_are_independent();
    ok &= test_percussive_track_untouched();
    ok &= test_chordal_track_untouched();
    ok &= test_bass_track_3_never_eligible();
    ok &= test_bass_lick_source_track_untouched();
    ok &= test_lead_is_filtered_and_returns_to_dry();
    ok &= test_empty_lead_mask_never_arms_on_the_gesture();
    ok &= test_empty_lead_mask_does_not_consume_the_probability_roll();
    ok &= test_takeover_with_identical_params_is_a_byte_identical_noop();
    ok &= test_takeover_is_one_bandpass_and_continuous();
    ok &= test_armed_duration_single_lead();
    ok &= test_armed_duration_two_leads_still_decrements_once_per_block();
    ok &= test_mask_covers_every_eligible_lead();
    return ok;
}
