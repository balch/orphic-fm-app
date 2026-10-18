// Mist grain engine: Beads parts under the Orpheus build, and the Mist kernel.
#include "test_harness.h"
#include "stmlib/utils/gate_flags.h"
#include "stmlib/utils/random.h"
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

#include "../src/pulsar_mist.h"

// test_harness.h brings in plaits/resources.h; Beads' tables must not redefine its macros.
static_assert(WAV_INTEGRATED_WAVES_SIZE == 25344,
              "Beads tables must not redefine the Plaits WAV_INTEGRATED_WAVES_SIZE macro");

static int count_triggers(const beads::TriggerGenerator::Output* out, int n, int* first) {
    int count = 0;
    for (int i = 0; i < n; i++) {
        if (!out[i].trigger) continue;
        if (count == 0 && first) *first = i;
        count++;
    }
    return count;
}

// The three ways Mist drives the generator. Pins the behaviour the kernel relies on,
// so an upstream change to the density map fails here and not by ear.
static bool test_trigger_generator_modes() {
    printf("\n=== Test: Beads trigger generator modes ===\n");
    const int n = 512;
    stmlib::GateFlags flags[n];
    beads::TriggerGenerator::Output out[n];
    bool ok = true;

    // Gated, centre density: one tap on the rising edge and nothing after.
    {
        beads::TriggerGenerator tg;
        tg.Init(48000.0f);
        for (int i = 0; i < n; i++)
            flags[i] = i >= 100 ? stmlib::GATE_FLAG_HIGH : stmlib::GATE_FLAG_LOW;
        tg.Render(beads::SEED_BUTTON_STATE_OFF, flags, 0.5f, 0.0f, 0.0f, out, n);
        int first = -1;
        const int taps = count_triggers(out, n, &first);
        printf("  single: %d taps, first at %d\n", taps, first);
        ok &= (taps == 1) && (first == 100);
    }
    // Gated, density 0: an even train at 130.8 Hz, a 367-sample period.
    {
        beads::TriggerGenerator tg;
        tg.Init(48000.0f);
        for (int i = 0; i < n; i++)
            flags[i] = i >= 100 ? stmlib::GATE_FLAG_HIGH : stmlib::GATE_FLAG_LOW;
        tg.Render(beads::SEED_BUTTON_STATE_OFF, flags, 0.0f, 0.0f, 0.0f, out, n);
        const int taps = count_triggers(out, n, nullptr);
        printf("  burst: %d taps in 412 held samples\n", taps);
        ok &= (taps == 2);
    }
    // Latched and clocked, density 0: every tick fires. Ticks are 6000 samples apart,
    // slower than the generator's 1000-sample audio-rate threshold.
    {
        beads::TriggerGenerator tg;
        tg.Init(48000.0f);
        int taps = 0;
        long pos = 0;
        for (int b = 0; b < 24; b++) {
            for (int i = 0; i < n; i++) {
                flags[i] = ((pos + i) % 6000 == 0)
                    ? (stmlib::GATE_FLAG_RISING | stmlib::GATE_FLAG_HIGH)
                    : stmlib::GATE_FLAG_LOW;
            }
            tg.Render(beads::SEED_BUTTON_STATE_LOCKED, flags, 0.0f, 0.0f, 0.0f, out, n);
            taps += count_triggers(out, n, nullptr);
            pos += n;
        }
        printf("  clocked: %d taps for 3 ticks\n", taps);
        ok &= (taps == 3);
    }
    printf("Trigger generator modes: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_beads_tables_link() {
    printf("\n=== Test: Beads lookup tables link ===\n");
    // lut_sine is 1024 samples per cycle plus a quarter-cycle guard.
    const float quarter = beads::lut_sine[256];
    bool any_wave = false;
    for (int i = 0; i < 1024; i++) any_wave |= (beads::wav_integrated_waves[i] != 0);
    printf("  lut_sine[256]=%.5f any_wave=%d\n", quarter, any_wave ? 1 : 0);
    bool ok = (std::fabs(quarter - 1.0f) < 1e-3f) && any_wave;
    printf("Beads tables: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static const uint8_t kMajor[7] = {0, 2, 4, 5, 7, 9, 11};

static bool test_pitch_masks() {
    printf("\n=== Test: chord and scale pitch-class masks ===\n");
    const uint16_t scale = mist::pitch_class_mask(0, kMajor, 7);
    const uint16_t tonic = mist::chord_mask(0, 0, kMajor, 7);      // C E G
    const uint16_t dominant = mist::chord_mask(0, 4, kMajor, 7);   // G B D
    const uint16_t in_d = mist::chord_mask(2, 0, kMajor, 7);       // D F# A
    printf("  scale=0x%03x tonic=0x%03x dominant=0x%03x in_d=0x%03x\n",
           scale, tonic, dominant, in_d);
    bool ok = (scale == 0x0AB5) && (tonic == 0x0091) && (dominant == 0x0884)
           && (in_d == 0x0244);
    printf("Pitch masks: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_pick_pitch_lands_on_the_mask() {
    printf("\n=== Test: scattered grains land on chord or scale tones ===\n");
    mist::PitchContext c;
    c.scale_mask = mist::pitch_class_mask(0, kMajor, 7);
    c.chord_mask = mist::chord_mask(0, 0, kMajor, 7);
    c.note_lo = 48;
    c.note_hi = 84;
    uint32_t rng = 0x1234;
    bool ok = true;
    int distinct_chord = 0;
    bool seen[128] = {};
    for (int i = 0; i < 2000; i++) {
        const float chord = mist::pick_pitch(60.0f, mist::kScatterChord, 0.5f, mist::kVoicingEven, c, rng);
        const float scale = mist::pick_pitch(60.0f, mist::kScatterScale, 0.5f, mist::kVoicingEven, c, rng);
        const int cn = static_cast<int>(chord), sn = static_cast<int>(scale);
        ok &= (c.chord_mask >> (cn % 12)) & 1;
        ok &= (c.scale_mask >> (sn % 12)) & 1;
        // scatter 0.5 is 12 semitones either side, then the 48..84 note range.
        ok &= (cn >= 48 && cn <= 72) && (sn >= 48 && sn <= 72);
        if (!seen[cn]) { seen[cn] = true; distinct_chord++; }
    }
    printf("  %d distinct chord tones drawn\n", distinct_chord);
    ok &= (distinct_chord >= 3);   // 48 52 55 60 64 67 72 are available
    printf("Pick lands on the mask: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_pick_pitch_edges() {
    printf("\n=== Test: pitch pick edge cases ===\n");
    mist::PitchContext c;
    c.scale_mask = mist::pitch_class_mask(0, kMajor, 7);
    c.chord_mask = mist::chord_mask(0, 0, kMajor, 7);
    uint32_t rng = 7;
    bool ok = true;
    // No scatter: the played note comes back even when it is not a chord tone.
    ok &= (mist::pick_pitch(61.0f, mist::kScatterChord, 0.0f, mist::kVoicingEven, c, rng) == 61.0f);
    // RAW never snaps; Beads' own randomizer scatters it later.
    ok &= (mist::pick_pitch(61.0f, mist::kScatterRaw, 1.0f, mist::kVoicingEven, c, rng) == 61.0f);
    // A note range with no chord tone in reach falls back to the played note.
    c.note_lo = 61;
    c.note_hi = 63;
    ok &= (mist::pick_pitch(62.0f, mist::kScatterChord, 1.0f, mist::kVoicingEven, c, rng) == 62.0f);
    // An empty mask must not divide by zero or loop.
    c.note_lo = 0;
    c.note_hi = 127;
    c.chord_mask = 0;
    ok &= (mist::pick_pitch(60.0f, mist::kScatterChord, 1.0f, mist::kVoicingEven, c, rng) == 60.0f);
    printf("Pick edges: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

namespace {

constexpr float kSr = 48000.0f;
constexpr int kTickSamples = 6000;   // a 16th note at 120 bpm

struct Capture { std::vector<float> osc, mid, side; };

mist::Params base_params() {
    mist::Params p{};
    p.note = 60.0f;
    p.harmonics = 0.3f;
    p.timbre = 0.5f;
    p.morph = 0.4f;
    p.mode = mist::kModeBurst;
    p.scatter_mode = mist::kScatterChord;
    p.voicing = mist::kVoicingEven;
    p.scatter = 0.0f;
    p.rate = 0.8f;
    p.random_timing = false;
    p.shape = 0.5f;
    p.blend = 1.0f;
    return p;
}

mist::PitchContext c_major() {
    mist::PitchContext c;
    c.scale_mask = mist::pitch_class_mask(0, kMajor, 7);
    c.chord_mask = mist::chord_mask(0, 0, kMajor, 7);
    return c;
}

// `gate_blocks` with the gate held (note-on at sample 0), then `tail_blocks` released.
// `clocked` sends a step tick every kTickSamples for the first `clock_blocks` blocks.
Capture run(PulsarMistState& st, const mist::Params& p, int gate_blocks, int tail_blocks,
            bool clocked = false, float sr = kSr, int block = 512, int clock_blocks = 1 << 30) {
    Capture c;
    const mist::PitchContext pc = c_major();
    float osc[512], mid[512], side[512];
    long pos = 0;
    for (int b = 0; b < gate_blocks + tail_blocks; b++) {
        const bool held = b < gate_blocks;
        int ticks[8];
        int n = 0;
        if (clocked && b < clock_blocks) {
            for (int i = 0; i < block && n < 8; i++)
                if ((pos + i) % kTickSamples == 0) ticks[n++] = i;
        }
        mist::Gate g;
        g.trig_off = 0;
        g.gate_pre = held && b > 0;
        g.gate_post = held;
        g.note_on = (b == 0);
        g.ticks = ticks;
        g.tick_count = n;
        mist::process_mist_block(st, p, g, pc, sr, osc, mid, side, block);
        c.osc.insert(c.osc.end(), osc, osc + block);
        c.mid.insert(c.mid.end(), mid, mid + block);
        c.side.insert(c.side.end(), side, side + block);
        pos += block;
    }
    return c;
}

float peak_of(const std::vector<float>& v, size_t from = 0) {
    float p = 0.0f;
    for (size_t i = from; i < v.size(); i++) p = std::max(p, std::fabs(v[i]));
    return p;
}

double mean_of(const std::vector<float>& v) {
    double s = 0.0;
    for (float x : v) s += x;
    return v.empty() ? 0.0 : s / static_cast<double>(v.size());
}

// Bit test: the release build is -ffast-math, which folds std::isfinite to true.
bool all_finite(const std::vector<float>& v) {
    for (float x : v) {
        uint32_t bits;
        std::memcpy(&bits, &x, sizeof bits);
        if (((bits >> 23) & 0xFFu) == 0xFFu) return false;
    }
    return true;
}

double rms_diff(const std::vector<float>& a, const std::vector<float>& b) {
    double s = 0.0;
    const size_t n = std::min(a.size(), b.size());
    for (size_t i = 0; i < n; i++) { const double d = a[i] - b[i]; s += d * d; }
    return n ? std::sqrt(s / static_cast<double>(n)) : 0.0;
}

// Fundamental by autocorrelation between 110 and 880 Hz.
float estimate_hz(const std::vector<float>& v, size_t from, float sr) {
    const int lo = static_cast<int>(sr / 880.0f), hi = static_cast<int>(sr / 110.0f);
    const size_t n = 8192;
    double best = -1e30;
    int best_lag = lo;
    for (int lag = lo; lag <= hi; lag++) {
        double acc = 0.0;
        for (size_t i = 0; i < n; i++) acc += v[from + i] * v[from + i + lag];
        if (acc > best) { best = acc; best_lag = lag; }
    }
    return sr / static_cast<float>(best_lag);
}

void seed_all() { stmlib::Random::Seed(0x1234); }

}  // namespace

static bool test_mist_renders_each_mode() {
    printf("\n=== Test: Mist renders in burst, single and continuous ===\n");
    bool ok = true;
    for (int mode = 0; mode < 3; mode++) {
        seed_all();
        PulsarMistState st;
        mist::Params p = base_params();
        p.mode = mode;
        const Capture c = run(st, p, 40, 0, mode == mist::kModeContinuous);
        const float pk = peak_of(c.mid);
        printf("  mode %d: peak %.4f\n", mode, pk);
        ok &= (pk > 0.01f) && all_finite(c.mid) && all_finite(c.side);
    }
    printf("Renders each mode: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_mist_single_is_one_grain_burst_is_many() {
    printf("\n=== Test: SINGLE fires one grain per note, BURST repeats ===\n");
    seed_all();
    PulsarMistState single, burst;
    mist::Params p = base_params();
    p.morph = 1.0f;   // 4 s grains, so every grain fired is still alive
    p.mode = mist::kModeSingle;
    run(single, p, 40, 0);
    p.mode = mist::kModeBurst;
    run(burst, p, 40, 0);
    const int one = single.grains.CountActiveGrains();
    const int many = burst.grains.CountActiveGrains();
    printf("  single=%d burst=%d active grains after 40 held blocks\n", one, many);
    bool ok = (one == 1) && (many >= 8);
    printf("Single vs burst: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_mist_level_and_dc() {
    printf("\n=== Test: Mist output level and DC ===\n");
    seed_all();
    PulsarMistState st;
    const Capture c = run(st, base_params(), 100, 0);
    const float pk = peak_of(c.mid, 4096);
    const double dc = mean_of(c.mid);
    printf("  peak %.4f dc %.5f\n", pk, dc);
    bool ok = (pk >= 0.15f) && (pk <= 0.95f) && (std::fabs(dc) < 0.01);
    printf("Level and DC: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// Review Focus 1: 32 in-phase 4-second grains must not stack past full scale.
static bool test_mist_dense_cloud_is_bounded() {
    printf("\n=== Test: a dense coherent cloud stays bounded ===\n");
    seed_all();
    PulsarMistState st;
    mist::Params p = base_params();
    p.rate = 1.0f;
    p.morph = 1.0f;
    p.shape = 0.0f;
    const Capture c = run(st, p, 200, 0);
    const float pk = peak_of(c.mid);
    printf("  peak %.4f with %d active grains\n", pk, st.grains.CountActiveGrains());
    bool ok = (pk <= 1.0f) && (pk > 0.05f) && all_finite(c.mid);
    printf("Dense cloud bound: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_mist_burst_tail_outlives_the_gate() {
    printf("\n=== Test: burst grains ring out after the gate closes ===\n");
    seed_all();
    PulsarMistState st;
    mist::Params p = base_params();
    p.morph = 0.7f;
    const Capture c = run(st, p, 20, 500);
    const size_t gate_end = 20 * 512;
    const float just_after = peak_of(std::vector<float>(c.mid.begin() + gate_end,
                                                        c.mid.begin() + gate_end + 2048));
    const float long_after = peak_of(c.mid, c.mid.size() - 4096);
    printf("  just after the gate %.4f, 5 s later %.6f\n", just_after, long_after);
    bool ok = (just_after > 0.005f) && (long_after < 1e-5f);
    printf("Burst tail: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// Review Focus 4: a continuous cloud stops with the clock and restarts after a gap.
static bool test_mist_continuous_follows_the_clock() {
    printf("\n=== Test: continuous mode needs the clock and resumes after a gap ===\n");
    seed_all();
    mist::Params p = base_params();
    p.mode = mist::kModeContinuous;
    p.rate = 1.0f;
    PulsarMistState unclocked;
    const float silent = peak_of(run(unclocked, p, 100, 0, false).mid);

    PulsarMistState st;
    const float playing = peak_of(run(st, p, 100, 0, true).mid);
    // Five seconds with no ticks: past the 4 s grain length and the divider's resync.
    const Capture gap = run(st, p, 470, 0, false);
    const float after_gap = peak_of(gap.mid, gap.mid.size() - 4096);
    const float resumed = peak_of(run(st, p, 60, 0, true).mid);
    printf("  unclocked %.6f playing %.4f after gap %.6f resumed %.4f\n",
           silent, playing, after_gap, resumed);
    bool ok = (silent == 0.0f) && (playing > 0.01f) && (after_gap < 1e-5f) && (resumed > 0.01f);

    // rate 0 is centre density: nothing fires, by the module's own rule.
    PulsarMistState zero;
    p.rate = 0.0f;
    ok &= (peak_of(run(zero, p, 100, 0, true).mid) == 0.0f);
    printf("Continuous clock: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// A field that is pushed but changes nothing is the usual Pulsar failure. Every
// field and macro must move the audio.
static bool test_mist_every_field_changes_the_audio() {
    printf("\n=== Test: every Mist field changes the output ===\n");
    struct Variant { const char* name; void (*apply)(mist::Params&); };
    const Variant variants[] = {
        {"harmonics",       [](mist::Params& p) { p.harmonics = 0.8f; }},
        {"timbre",          [](mist::Params& p) { p.timbre = 0.95f; }},
        {"morph",           [](mist::Params& p) { p.morph = 0.8f; }},
        {"mode single",     [](mist::Params& p) { p.mode = mist::kModeSingle; }},
        {"mode continuous", [](mist::Params& p) { p.mode = mist::kModeContinuous; }},
        {"scatter scale",   [](mist::Params& p) { p.scatter_mode = mist::kScatterScale; }},
        {"scatter raw",     [](mist::Params& p) { p.scatter_mode = mist::kScatterRaw; }},
        {"scatter amount",  [](mist::Params& p) { p.scatter = 1.0f; }},
        {"voicing centred", [](mist::Params& p) { p.voicing = mist::kVoicingCentered; }},
        {"voicing rising",  [](mist::Params& p) { p.voicing = mist::kVoicingRising; }},
        {"rate",            [](mist::Params& p) { p.rate = 0.2f; }},
        {"random timing",   [](mist::Params& p) { p.random_timing = true; }},
        {"shape",           [](mist::Params& p) { p.shape = 0.05f; }},
        {"blend",           [](mist::Params& p) { p.blend = 0.3f; }},
    };
    mist::Params base = base_params();
    base.scatter = 0.5f;
    base.rate = 0.6f;
    seed_all();
    PulsarMistState a;
    const Capture ref = run(a, base, 60, 0, true);
    bool ok = true;
    for (const Variant& v : variants) {
        mist::Params p = base;
        v.apply(p);
        seed_all();
        PulsarMistState b;
        const Capture got = run(b, p, 60, 0, true);
        const double d = rms_diff(ref.mid, got.mid) + rms_diff(ref.osc, got.osc)
                       + rms_diff(ref.side, got.side);
        printf("  %-16s diff %.5f\n", v.name, d);
        ok &= (d > 1e-4);
    }
    printf("Every field changes the audio: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// Review Focus 3: the oscillator layer holds its pitch at both host rates.
static bool test_mist_pitch_is_sample_rate_independent() {
    printf("\n=== Test: pitch is the same at 44.1 kHz and 48 kHz ===\n");
    mist::Params p = base_params();
    p.note = 69.0f;      // A4
    p.blend = 0.0f;      // oscillator layer only
    p.harmonics = 0.0f;
    p.timbre = 0.0f;
    bool ok = true;
    for (float sr : {44100.0f, 48000.0f}) {
        seed_all();
        PulsarMistState st;
        const Capture c = run(st, p, 60, 0, false, sr);
        const float hz = estimate_hz(c.osc, 8192, sr);
        printf("  %.0f Hz host: %.1f Hz\n", sr, hz);
        ok &= (std::fabs(hz - 440.0f) < 440.0f * 0.03f);
    }
    printf("Sample rate independence: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// Beads only pans a grain when something about it is random.
static bool test_mist_side_needs_randomness() {
    printf("\n=== Test: the side channel is silent until grains are randomised ===\n");
    seed_all();
    PulsarMistState centred;
    const float flat = peak_of(run(centred, base_params(), 60, 0).side);

    seed_all();
    PulsarMistState scattered;
    mist::Params p = base_params();
    p.scatter = 0.6f;
    const float wide_scatter = peak_of(run(scattered, p, 60, 0).side);

    seed_all();
    PulsarMistState jittered;
    p = base_params();
    p.random_timing = true;
    const float wide_timing = peak_of(run(jittered, p, 60, 0).side);

    printf("  centred %.7f, scatter %.4f, random timing %.4f\n",
           flat, wide_scatter, wide_timing);
    bool ok = (flat < 1e-6f) && (wide_scatter > 1e-3f) && (wide_timing > 1e-3f);
    printf("Side needs randomness: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// Review Focus 3, and the all-settings sweep: every corner, at awkward block sizes.
static bool test_mist_sweep_is_finite() {
    printf("\n=== Test: all-settings sweep is finite at every block size ===\n");
    const float vals[] = {0.0f, 0.5f, 1.0f};
    const float notes[] = {0.0f, 60.0f, 127.0f};
    const int blocks[] = {1, 17, 64, 512};
    int checked = 0;
    bool ok = true;
    for (int mode = 0; mode < 3; mode++)
    for (int sm = 0; sm < 3; sm++)
    for (float v : vals)
    for (float note : notes)
    for (int block : blocks) {
        mist::Params p = base_params();
        p.mode = mode;
        p.scatter_mode = sm;
        p.note = note;
        p.harmonics = p.timbre = p.morph = v;
        p.scatter = p.rate = p.shape = p.blend = v;
        p.random_timing = (v > 0.75f);
        seed_all();
        PulsarMistState st;
        const Capture c = run(st, p, 8192 / block, 4096 / block, true, kSr, block);
        ok &= all_finite(c.mid) && all_finite(c.side) && all_finite(c.osc);
        ok &= (peak_of(c.mid) <= 1.5f) && (peak_of(c.osc) <= 1.5f);
        checked++;
    }
    printf("  swept %d combinations\n", checked);
    printf("All-settings sweep: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_mist_wake_clears_stale_grains() {
    printf("\n=== Test: wake clears the grain pool ===\n");
    seed_all();
    PulsarMistState st;
    mist::Params p = base_params();
    p.morph = 1.0f;
    run(st, p, 40, 0);
    const int before = st.grains.CountActiveGrains();
    mist::wake(st);
    const int after = st.grains.CountActiveGrains();
    PulsarMistState fresh;
    mist::wake(fresh);   // must be safe before the first render
    printf("  %d active before, %d after\n", before, after);
    bool ok = (before >= 8) && (after == 0);
    printf("Wake: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// The gate mirror must track what the trigger generator last saw on its gated path,
// or a note-on after a continuous stretch or a wake arrives without a rising edge.
static bool test_mist_note_on_after_continuous_fires() {
    printf("\n=== Test: a note-on after continuous mode still fires ===\n");
    bool ok = true;
    for (int mode : {static_cast<int>(mist::kModeSingle), static_cast<int>(mist::kModeBurst)}) {
        seed_all();
        PulsarMistState st;
        mist::Params p = base_params();
        p.morph = 1.0f;
        p.mode = mode;
        run(st, p, 4, 0);                 // gate still high when it ends
        p.mode = mist::kModeContinuous;
        run(st, p, 3, 0, false);
        p.mode = mode;
        const int before = st.grains.CountActiveGrains();
        run(st, p, 1, 0);                 // one block, note-on at sample 0
        const int added = st.grains.CountActiveGrains() - before;
        printf("  mode %d: %d new grains in the note-on block\n", mode, added);
        ok &= (mode == mist::kModeSingle) ? (added == 1) : (added >= 1);
    }
    printf("Note-on after continuous: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_mist_note_on_after_wake_fires() {
    printf("\n=== Test: a note-on after wake still fires ===\n");
    bool ok = true;
    for (int mode : {static_cast<int>(mist::kModeSingle), static_cast<int>(mist::kModeBurst)}) {
        seed_all();
        PulsarMistState st;
        mist::Params p = base_params();
        p.morph = 1.0f;
        p.mode = mode;
        run(st, p, 4, 0);                 // gate still high when it ends
        mist::wake(st);
        const int cleared = st.grains.CountActiveGrains();
        run(st, p, 1, 0);
        const int added = st.grains.CountActiveGrains();
        printf("  mode %d: %d grains after wake, %d after the note-on block\n",
               mode, cleared, added);
        ok &= (cleared == 0);
        ok &= (mode == mist::kModeSingle) ? (added == 1) : (added >= 1);
    }
    printf("Note-on after wake: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// The oscillator test only covers the steady layer. A grain's pitch goes through
// a different conversion, so it gets its own check.
static bool test_mist_grain_pitch() {
    printf("\n=== Test: a grain plays the requested pitch ===\n");
    mist::Params p = base_params();
    p.mode = mist::kModeSingle;
    p.blend = 1.0f;
    p.scatter = 0.0f;
    p.morph = 0.8f;       // a grain of about a second
    p.harmonics = 0.0f;
    p.timbre = 0.0f;
    struct Case { float note, hz; };
    bool ok = true;
    for (const Case c : {Case{69.0f, 440.0f}, Case{57.0f, 220.0f}}) {
        for (float sr : {44100.0f, 48000.0f}) {
            p.note = c.note;
            seed_all();
            PulsarMistState st;
            const Capture cap = run(st, p, 120, 0, false, sr);
            const float hz = estimate_hz(cap.mid, 12000, sr);
            printf("  note %.0f at %.0f Hz host: %.1f Hz\n", c.note, sr, hz);
            ok &= (std::fabs(hz - c.hz) < c.hz * 0.03f);
        }
    }
    printf("Grain pitch: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// The release build is -ffast-math, so a NaN or wild note must be caught by bits, and
// the proof is that it renders exactly like the clamped note.
static bool test_mist_bad_note_is_clamped() {
    printf("\n=== Test: a NaN or out-of-range note is clamped ===\n");
    uint32_t nan_bits = 0x7FC00000u, inf_bits = 0x7F800000u;
    float nan_note, inf_note;
    std::memcpy(&nan_note, &nan_bits, sizeof nan_note);
    std::memcpy(&inf_note, &inf_bits, sizeof inf_note);
    struct Case { float note, same_as; };
    bool ok = true;
    for (const Case c : {Case{nan_note, 60.0f}, Case{inf_note, 60.0f},
                         Case{1000.0f, 127.0f}, Case{-1000.0f, 0.0f}}) {
        mist::Params p = base_params();
        p.note = c.note;
        seed_all();
        PulsarMistState a;
        const Capture got = run(a, p, 20, 0);
        p.note = c.same_as;
        seed_all();
        PulsarMistState b;
        const Capture want = run(b, p, 20, 0);
        const double d = rms_diff(got.mid, want.mid) + rms_diff(got.osc, want.osc);
        printf("  note %g vs %g: diff %.7f\n", static_cast<double>(c.note),
               static_cast<double>(c.same_as), d);
        ok &= all_finite(got.mid) && all_finite(got.osc) && (d == 0.0);
    }
    printf("Bad note clamp: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// 13 candidates with the played note in the middle, 13000 draws per voicing.
static bool test_voicing_shapes() {
    printf("\n=== Test: the three voicings have the shapes they are named for ===\n");
    const int count = 13, nearest = 6, draws = 13000;
    int hist[3][13] = {};
    uint32_t rng = 0xC0FFEE;
    bool ok = true;
    for (int v = 0; v < 3; v++) {
        for (int i = 0; i < draws; i++) {
            const int c = mist::choose_candidate(count, nearest, v, rng);
            if (c < 0 || c >= count) { ok = false; continue; }
            hist[v][c]++;
        }
    }
    const int mean = draws / count;   // 1000
    // EVEN: every tone within 20% of an equal share.
    for (int i = 0; i < count; i++) ok &= (hist[0][i] > mean * 8 / 10) && (hist[0][i] < mean * 12 / 10);
    // CENTERED: the played note is the most likely, the edges the least, both sides used.
    for (int i = 0; i < count; i++) ok &= (hist[1][nearest] >= hist[1][i]);
    ok &= (hist[1][nearest] > 3 * hist[1][0]) && (hist[1][nearest] > 3 * hist[1][count - 1]);
    ok &= (hist[1][nearest - 2] > 0) && (hist[1][nearest + 2] > 0);
    // RISING: nothing below the played note, and the top of the span is reached.
    for (int i = 0; i < nearest; i++) ok &= (hist[2][i] == 0);
    ok &= (hist[2][nearest] > 0) && (hist[2][count - 1] > 0);
    printf("  even   mid=%d edge=%d\n", hist[0][nearest], hist[0][0]);
    printf("  centred mid=%d edge=%d/%d\n", hist[1][nearest], hist[1][0], hist[1][count - 1]);
    printf("  rising below=%d mid=%d top=%d\n", hist[2][nearest - 1], hist[2][nearest], hist[2][count - 1]);
    for (int v = 0; v < 3; v++) {
        printf("  hist[%d]:", v);
        for (int i = 0; i < count; i++) printf(" %d", hist[v][i]);
        printf("\n");
    }
    printf("Voicing shapes: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// CENTERED must peak on the played note wherever it sits in the span: the weight falls
// one step per index on both sides and the short side is cut off, not folded onto its edge.
static bool test_voicing_centered_off_centre() {
    printf("\n=== Test: centered voicing peaks on the played note off-centre ===\n");
    struct Case { int count, nearest; };
    const Case cases[] = {{13, 4}, {49, 10}, {9, 0}, {9, 8}, {2, 0}};
    const int draws = 400000;
    bool ok = true;
    for (const Case cs : cases) {
        int hist[49] = {};
        uint32_t rng = 0xBADC0DEu ^ static_cast<uint32_t>(cs.count * 131 + cs.nearest);
        for (int i = 0; i < draws; i++) {
            const int c = mist::choose_candidate(cs.count, cs.nearest, mist::kVoicingCentered, rng);
            if (c < 0 || c >= cs.count) { ok = false; continue; }
            hist[c]++;
        }
        const int reach = std::max(cs.nearest, cs.count - 1 - cs.nearest);
        double expected[49];
        double total = 0.0;
        for (int i = 0; i < cs.count; i++) {
            expected[i] = static_cast<double>(reach + 1 - std::abs(i - cs.nearest));
            total += expected[i];
        }
        bool case_ok = true;
        for (int i = 0; i < cs.count; i++) {
            expected[i] = expected[i] / total * draws;
            case_ok &= (hist[i] >= 1);
            if (expected[i] >= 2000.0)
                case_ok &= std::fabs(hist[i] - expected[i]) <= 0.08 * expected[i];
        }
        printf("  count %d nearest %d: %s\n", cs.count, cs.nearest, case_ok ? "ok" : "BAD");
        if ((cs.count == 13 && cs.nearest == 4) || (cs.count == 49 && cs.nearest == 10)) {
            for (int i = cs.nearest - 1; i <= cs.nearest + 1; i++)
                printf("    index %d: measured %d expected %.0f\n", i, hist[i], expected[i]);
        }
        ok &= case_ok;
    }
    printf("Centered off-centre: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// RISING anchors on the first candidate at or above the played note, even when the
// note is not a candidate itself.
static bool test_voicing_rising_anchor() {
    printf("\n=== Test: rising voicing never dips below an off-chord note ===\n");
    mist::PitchContext c;
    c.chord_mask = mist::chord_mask(0, 0, kMajor, 7);
    uint32_t rng = 4242;
    bool ok = true;
    float lowest = 1000.0f;
    bool seen[128] = {};
    int distinct = 0;
    for (int i = 0; i < 2000; i++) {
        const float n = mist::pick_pitch(61.0f, mist::kScatterChord, 1.0f, mist::kVoicingRising, c, rng);
        ok &= (n >= 61.0f);
        lowest = std::min(lowest, n);
        const int k = static_cast<int>(n);
        if (k >= 0 && k < 128 && !seen[k]) { seen[k] = true; distinct++; }
    }
    ok &= (distinct >= 2);
    printf("  note 61: lowest %.0f, %d distinct tones\n", static_cast<double>(lowest), distinct);
    // Every candidate is below the note: the highest one is the anchor.
    c.note_lo = 48;
    c.note_hi = 60;
    int off_top = 0;
    for (int i = 0; i < 2000; i++) {
        const float n = mist::pick_pitch(62.0f, mist::kScatterChord, 1.0f, mist::kVoicingRising, c, rng);
        if (n != 60.0f) off_top++;
    }
    printf("  note 62 with range 48..60: %d of 2000 not 60\n", off_top);
    ok &= (off_top == 0);
    printf("Rising anchor: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_voicing_edges() {
    printf("\n=== Test: voicings stay in range when the played note is at an edge ===\n");
    uint32_t rng = 99;
    bool ok = true;
    for (int v = 0; v < 3; v++) {
        for (int i = 0; i < 2000; i++) {
            ok &= (mist::choose_candidate(1, 0, v, rng) == 0);             // one candidate
            const int low = mist::choose_candidate(9, 0, v, rng);          // note at the bottom
            const int high = mist::choose_candidate(9, 8, v, rng);         // note at the top
            ok &= (low >= 0 && low < 9) && (high >= 0 && high < 9);
            if (v == mist::kVoicingRising) ok &= (high == 8);              // nothing above it
        }
    }
    // Through pick_pitch: RISING never lands below the played note.
    mist::PitchContext c;
    c.chord_mask = mist::chord_mask(0, 0, kMajor, 7);
    for (int i = 0; i < 2000; i++) {
        ok &= (mist::pick_pitch(60.0f, mist::kScatterChord, 1.0f, mist::kVoicingRising, c, rng) >= 60.0f);
    }
    printf("Voicing edges: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

bool run_mist_tests() {
    int suite_pass = 0, suite_fail = 0;
    if (test_trigger_generator_modes()) suite_pass++; else suite_fail++;
    if (test_beads_tables_link()) suite_pass++; else suite_fail++;
    if (test_pitch_masks()) suite_pass++; else suite_fail++;
    if (test_pick_pitch_lands_on_the_mask()) suite_pass++; else suite_fail++;
    if (test_pick_pitch_edges()) suite_pass++; else suite_fail++;
    if (test_voicing_shapes()) suite_pass++; else suite_fail++;
    if (test_voicing_centered_off_centre()) suite_pass++; else suite_fail++;
    if (test_voicing_rising_anchor()) suite_pass++; else suite_fail++;
    if (test_voicing_edges()) suite_pass++; else suite_fail++;
    if (test_mist_renders_each_mode()) suite_pass++; else suite_fail++;
    if (test_mist_single_is_one_grain_burst_is_many()) suite_pass++; else suite_fail++;
    if (test_mist_level_and_dc()) suite_pass++; else suite_fail++;
    if (test_mist_dense_cloud_is_bounded()) suite_pass++; else suite_fail++;
    if (test_mist_burst_tail_outlives_the_gate()) suite_pass++; else suite_fail++;
    if (test_mist_continuous_follows_the_clock()) suite_pass++; else suite_fail++;
    if (test_mist_every_field_changes_the_audio()) suite_pass++; else suite_fail++;
    if (test_mist_pitch_is_sample_rate_independent()) suite_pass++; else suite_fail++;
    if (test_mist_side_needs_randomness()) suite_pass++; else suite_fail++;
    if (test_mist_sweep_is_finite()) suite_pass++; else suite_fail++;
    if (test_mist_wake_clears_stale_grains()) suite_pass++; else suite_fail++;
    if (test_mist_note_on_after_continuous_fires()) suite_pass++; else suite_fail++;
    if (test_mist_note_on_after_wake_fires()) suite_pass++; else suite_fail++;
    if (test_mist_grain_pitch()) suite_pass++; else suite_fail++;
    if (test_mist_bad_note_is_clamped()) suite_pass++; else suite_fail++;
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
