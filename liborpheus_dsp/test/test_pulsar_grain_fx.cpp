// Grain FX: a track's own voice recorded into a Beads delay line and replayed as grains.
// The kernel cases drive it with a synthetic sine; the engine cases run the Pulsar unit.
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/orpheus_graph.h"
#include "stmlib/utils/random.h"
#include "../src/pulsar_grain_fx.h"
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <vector>

static double midi_hz(int note) { return 440.0 * std::pow(2.0, (note - 69) / 12.0); }

// Goertzel power at one frequency over a Hann window, so a tone's skirt does not
// leak into a neighbouring bin.
static double tone_energy(const float* buf, int n, double hz, double sr) {
    const double w = 2.0 * M_PI * hz / sr;
    const double coeff = 2.0 * std::cos(w);
    double s1 = 0.0, s2 = 0.0;
    for (int i = 0; i < n; i++) {
        const double win = 0.5 - 0.5 * std::cos(2.0 * M_PI * i / (n - 1));
        const double s0 = buf[i] * win + coeff * s1 - s2;
        s2 = s1; s1 = s0;
    }
    return (s1 * s1 + s2 * s2 - coeff * s1 * s2) / (static_cast<double>(n) * n);
}

static double rms(const float* buf, int n) {
    double acc = 0.0;
    for (int i = 0; i < n; i++) acc += static_cast<double>(buf[i]) * buf[i];
    return n > 0 ? std::sqrt(acc / n) : 0.0;
}

static grainfx::Params base_params() {
    grainfx::Params p;
    p.note = 69.0f;
    p.mode = mist::kModeBurst;
    p.scatter_mode = mist::kScatterChord;
    p.voicing = mist::kVoicingEven;
    p.scatter = 0.0f;
    p.rate = 0.6f;
    p.random_timing = true;
    p.shape = 0.5f;
    p.blend = 1.0f;
    p.size = 0.5f;
    return p;
}

// A sine source and everything the kernel wrote, sample-aligned. Seeds Beads' shared
// RNG so grain timing and pan do not depend on which suites ran first.
struct Rig {
    Rig() { stmlib::Random::Seed(0x5EED); }
    std::unique_ptr<PulsarGrainFxState> st{new PulsarGrainFxState()};
    grainfx::Params p = base_params();
    mist::PitchContext pc;
    float sr = 48000.0f;
    double phase = 0.0;
    std::vector<float> in, io, mid, side;

    // `frames` of a sine at `hz` in blocks of at most `block`, gate high throughout.
    // `note_on` starts a note on the first sample.
    void feed(double hz, int frames, bool note_on, float amp = 0.5f, int block = mist::kMaxBlock) {
        float buf[mist::kMaxBlock], m[mist::kMaxBlock], s[mist::kMaxBlock];
        int ticks[mist::kMaxBlock / 64];
        bool first = true;
        while (frames > 0) {
            const int n = std::min(frames, block);
            for (int i = 0; i < n; i++) {
                buf[i] = amp * static_cast<float>(std::sin(phase));
                phase += 2.0 * M_PI * hz / sr;
            }
            in.insert(in.end(), buf, buf + n);
            int tick_count = 0;
            for (int at = 0; at < n; at += 64) ticks[tick_count++] = at;
            mist::Gate g;
            g.gate_pre = g.gate_post = true;
            g.note_on = note_on && first;
            g.ticks = ticks;
            g.tick_count = tick_count;
            grainfx::process_block(*st, p, g, pc, sr, buf, m, s, n);
            io.insert(io.end(), buf, buf + n);
            mid.insert(mid.end(), m, m + n);
            side.insert(side.end(), s, s + n);
            frames -= n;
            first = false;
        }
    }
    int samples(float seconds) const { return static_cast<int>(seconds * sr); }
};

static bool test_hold_samples_values() {
    printf("\n=== Test: note-on hold lengths and the 2 s size cap ===\n");
    const int h0 = grainfx::hold_samples(0.0f, 14400.0f, 48000.0f);
    const int h1 = grainfx::hold_samples(1.0f, 96000.0f, 48000.0f);
    const int h50 = grainfx::hold_samples(0.5f, 14400.0f, 48000.0f);
    const int h85 = grainfx::hold_samples(0.85f, 14400.0f, 48000.0f);
    Rig r;
    r.st->Init(48000.0f);
    const float cap = r.st->grains.ComputeGrainDuration(grainfx::kGrainFxSizeMax);
    printf("  shape0=%d shape1=%d shape0.5=%d shape0.85=%d cap=%.0f (size %.4f)\n",
           h0, h1, h50, h85, cap, grainfx::kGrainFxSizeMax);
    bool ok = (h0 == 3840) && (h1 == 0);
    ok &= std::abs(h50 - 3615) <= 2 && std::abs(h85 - 1050) <= 2;
    ok &= std::fabs(cap - 96000.0f) < 960.0f;
    printf("Hold values: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// Mid power at the note against a fifth above, over the second half of 0.5 s.
static bool replays_at(float sr) {
    Rig r;
    r.sr = sr;
    r.feed(440.0, r.samples(0.5f), true);
    const int half = r.samples(0.25f);
    const float* tail = r.mid.data() + r.mid.size() - half;
    const double e440 = tone_energy(tail, half, 440.0, sr);
    const double e660 = tone_energy(tail, half, 660.0, sr);
    const double side = rms(r.side.data(), static_cast<int>(r.side.size()));
    printf("  sr=%.0f e440=%.3g e660=%.3g ratio=%.1f side_rms=%.4f\n",
           sr, e440, e660, e440 / std::max(e660, 1e-30), side);
    return e440 > 0.0 && e440 >= 20.0 * e660 && side > 0.0;
}

static bool test_replays_its_input_pitch() {
    printf("\n=== Test: scatter 0 replays the voice at its own pitch ===\n");
    const bool ok = replays_at(48000.0f);
    printf("Replays input pitch: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_chord_scatter_lands_on_chord_tones() {
    printf("\n=== Test: CHORD scatter pitches grains onto chord tones ===\n");
    Rig r;
    r.p.note = 60.0f;
    r.p.scatter = 0.3f;
    r.p.size = 0.7f;
    r.pc.chord_mask = (1u << 0) | (1u << 4) | (1u << 7);
    r.feed(midi_hz(60), r.samples(2.0f), true);
    const int win = r.samples(1.0f);
    const float* tail = r.mid.data() + r.mid.size() - win;
    double chord = 0.0, off = 0.0;
    for (int n = 48; n <= 72; n++) {
        const int pc = n % 12;
        const double e = tone_energy(tail, win, midi_hz(n), r.sr);
        if (pc == 0 || pc == 4 || pc == 7) chord += e;
        if (pc == 1 || pc == 6 || pc == 10) off += e;
    }
    printf("  chord=%.3g off=%.3g ratio=%.1f\n", chord, off, chord / std::max(off, 1e-30));
    const bool ok = chord > 0.0 && chord >= 5.0 * off;
    printf("Chord scatter: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_blend_zero_leaves_io_untouched() {
    printf("\n=== Test: blend 0 leaves the voice bit-identical and the grains silent ===\n");
    Rig r;
    r.p.blend = 0.0f;
    r.feed(440.0, r.samples(1.0f), true);
    const bool same = std::memcmp(r.in.data(), r.io.data(), r.in.size() * sizeof(float)) == 0;
    bool silent = true;
    for (size_t i = 0; i < r.mid.size(); i++) silent &= (r.mid[i] == 0.0f) && (r.side[i] == 0.0f);
    printf("  io identical=%d grains silent=%d grains_started=%u\n",
           same ? 1 : 0, silent ? 1 : 0, r.st->grains_started);
    const bool ok = same && silent;
    printf("Blend zero: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// Mid for the first 100 ms after the input switches to 660 Hz, following 1 s at `before_hz`.
static std::vector<float> after_switch(double before_hz, bool wake) {
    Rig r;
    r.p.random_timing = false;   // even 14 Hz grains, so one is always sounding
    r.feed(before_hz, r.samples(1.0f), true);
    if (wake) grainfx::wake(*r.st);
    const size_t at = r.mid.size();
    r.feed(660.0, r.samples(0.1f), false);
    return std::vector<float>(r.mid.begin() + at, r.mid.end());
}

static bool test_wake_drops_stale_audio() {
    printf("\n=== Test: wake clears the recording so no old note replays ===\n");
    const std::vector<float> woken = after_switch(440.0, true);
    const std::vector<float> clean = after_switch(660.0, true);   // never heard 440
    const std::vector<float> stale = after_switch(440.0, false);
    const int n = static_cast<int>(woken.size());
    const double w440 = tone_energy(woken.data(), n, 440.0, 48000.0);
    const double w660 = tone_energy(woken.data(), n, 660.0, 48000.0);
    const double s440 = tone_energy(stale.data(), n, 440.0, 48000.0);
    const double s660 = tone_energy(stale.data(), n, 660.0, 48000.0);
    // After a wake the output cannot depend on what was recorded before it. The 440 bin is
    // not compared with 660 here: the new note's 20 ms onset leaks about 2% into it.
    const bool history_free = std::memcmp(woken.data(), clean.data(), n * sizeof(float)) == 0;
    printf("  woken: e440=%.3g e660=%.3g  not woken: e440=%.3g e660=%.3g  history-free=%d\n",
           w440, w660, s440, s660, history_free ? 1 : 0);
    // Without the wake the old note is there, so the check can see it.
    const bool ok = history_free && w660 > 0.0 && w440 < 0.01 * s440 && s440 > s660;
    printf("Wake: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_note_on_hold_defers_first_grain() {
    printf("\n=== Test: a clicky grain waits out the lookback after a note-on ===\n");
    Rig r;
    r.p.mode = mist::kModeSingle;
    r.p.shape = 0.0f;
    r.feed(440.0, 3840, true);
    const uint32_t before = r.st->grains_started;
    r.feed(440.0, 1, false);
    const uint32_t at = r.st->grains_started;
    r.feed(440.0, r.samples(1.0f) - 3841, false);
    const uint32_t after = r.st->grains_started;

    // A full swell on a 2 s grain hides the lookback itself: no hold.
    Rig s;
    s.p.mode = mist::kModeSingle;
    s.p.shape = 1.0f;
    s.p.size = 1.0f;
    s.feed(440.0, mist::kMaxBlock, true);
    const uint32_t swell = s.st->grains_started;
    printf("  shape 0: before=%u at 3840=%u after 1 s=%u   shape 1: first block=%u\n",
           before, at, after, swell);
    const bool ok = before == 0 && at == 1 && after == 1 && swell == 1;
    printf("Note-on hold: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_hold_on_legato_note_change() {
    printf("\n=== Test: a legato note change holds its grain and plays only the new note ===\n");
    Rig r;
    r.p.mode = mist::kModeSingle;
    r.p.shape = 0.0f;
    r.feed(440.0, r.samples(0.5f), true);
    const uint32_t first = r.st->grains_started;
    r.p.note = 76.0f;
    r.feed(660.0, 3840, true);
    const uint32_t held = r.st->grains_started;
    const size_t at = r.mid.size();
    r.feed(660.0, 1, false);
    const uint32_t released = r.st->grains_started;
    r.feed(660.0, r.samples(0.5f), false);
    const uint32_t after = r.st->grains_started;
    const int win = r.samples(0.1f);
    const double e440 = tone_energy(r.mid.data() + at, win, 440.0, r.sr);
    const double e660 = tone_energy(r.mid.data() + at, win, 660.0, r.sr);
    printf("  grains first=%u held=%u released=%u after=%u  e440=%.3g e660=%.3g\n",
           first, held, released, after, e440, e660);
    const bool ok = first == 1 && held == 1 && released == 2 && after == 2
                 && e660 > 0.0 && e440 < 0.05 * e660;
    printf("Legato hold: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// 40 note-ons every `period` samples; grains started and the mid level.
static bool notes_get_grains(int period, float shape) {
    Rig r;
    r.p.shape = shape;
    const int hold = grainfx::hold_samples(shape, 0.0f, r.sr);
    for (int k = 0; k < 40; k++) r.feed(440.0, period, true);
    const double level = rms(r.mid.data(), static_cast<int>(r.mid.size()));
    printf("  shape %.1f hold up to %d samples, note-on every %d: grains_started=%u mid_rms=%.4f\n",
           shape, hold, period, r.st->grains_started, level);
    return r.st->grains_started > 0 && level > 0.0;
}

// Note-ons every 50 ms, under the ~72 ms hold, must not restart it. Periods equal to the
// 3840-sample hold or a divisor land just as it ends and must still get its grain.
static bool test_fast_notes_do_not_starve_the_cloud() {
    printf("\n=== Test: note-ons faster than the hold still get grains ===\n");
    bool ok = notes_get_grains(2400, 0.5f);
    ok &= notes_get_grains(3840, 0.0f);
    ok &= notes_get_grains(1920, 0.0f);
    printf("Fast notes: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// Dense in-phase grains at the makeup gain peak near 8 unlimited; the limiter brings them
// under full scale. Beads' limiter is soft: a faster swell (rate 1) can overshoot at onset.
static bool test_limiter_holds_full_scale() {
    printf("\n=== Test: the limiter pulls a dense cloud under full scale ===\n");
    Rig r;
    r.p.mode = mist::kModeContinuous;
    r.p.rate = 0.6f;
    r.p.random_timing = false;
    r.p.size = 1.0f;
    r.feed(440.0, r.samples(2.0f), true, 0.5f);
    float peak = 0.0f;
    for (size_t i = 0; i < r.mid.size(); i++) {
        peak = std::max(peak, std::fabs(r.mid[i] + r.side[i]));
        peak = std::max(peak, std::fabs(r.mid[i] - r.side[i]));
    }
    printf("  peak=%.4f grains_started=%u\n", peak, r.st->grains_started);
    const bool ok = peak > 0.5f && peak <= 1.0f && r.st->grains_started > 100;
    printf("Limiter: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_wide_raw_scatter_stays_finite() {
    printf("\n=== Test: RAW scatter 1 at full rate stays finite ===\n");
    Rig r;
    r.p.scatter_mode = mist::kScatterRaw;
    r.p.scatter = 1.0f;
    r.p.rate = 1.0f;
    r.feed(440.0, r.samples(2.0f), true);
    bool finite = true;
    for (size_t i = 0; i < r.mid.size(); i++) finite &= std::isfinite(r.mid[i]) && std::isfinite(r.side[i]);
    const double level = rms(r.mid.data(), static_cast<int>(r.mid.size()));
    printf("  finite=%d mid_rms=%.4f grains_started=%u\n", finite ? 1 : 0, level, r.st->grains_started);
    const bool ok = finite && level > 0.0;
    printf("RAW scatter finite: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_lookback_fits_at_96k() {
    printf("\n=== Test: the 80 ms lookback fits the 1 s line at 96 kHz ===\n");
    const bool ok = replays_at(96000.0f);
    printf("96 kHz replay: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// ── Engine cases: the kernel inside the Pulsar render path ──

static constexpr int kBlockFrames = 512;
static constexpr int kKeys = 4;   // the one track index with no ducking
static constexpr int kVa = 8;
static constexpr const char* kPulsarUri = "org.balch.orpheus.plugins.pulsar";

#define FX_SET(field, track, value) do { \
    engine->pulsar_track_##field[track].store(value, std::memory_order_relaxed); \
    engine->pulsar_track_##field##_space[track].store(value, std::memory_order_relaxed); \
} while (0)

// Both slots of a kKeys grain field, through the routing table Kotlin pushes into.
static void route_grain(OrpheusEngine* engine, const char* field, float value) {
    char sym[64];
    std::snprintf(sym, sizeof(sym), "track_%d_%s", kKeys, field);
    orpheus_engine_set_port(engine, kPulsarUri, sym, value);
    std::snprintf(sym, sizeof(sym), "track_%d_%s_space", kKeys, field);
    orpheus_engine_set_port(engine, kPulsarUri, sym, value);
}

struct FxSetup {
    int engine_id = kVa;
    int grain_fx = 1;
    float blend = 1.0f;
    int random_timing = 1;
    float width = 1.0f;
    float size = 0.5f;
    float rate = 0.8f;
    int note = 0;                  // 0 leaves the generator's notes
    float breathe_floor = -1.0f;   // under 0: no arrangement, no breathe
    int blocks = 188;              // about 2 s
};

// One long section whose kKeys breathe cycles every 2 bars: top on even bars, the floor on odd.
static void push_breathe_section(OrpheusEngine* engine, float floor_gain) {
    engine->pulsar_arrangement_active.store(1, std::memory_order_relaxed);
    engine->pulsar_arrangement_section_count.store(1, std::memory_order_relaxed);
    engine->pulsar_arrangement_intro_index.store(0, std::memory_order_relaxed);
    engine->pulsar_arrangement_outro_index.store(-1, std::memory_order_relaxed);
    constexpr int kStride = kSectionDataFields;
    std::vector<float> sd(kMaxSections * kStride, 0.0f);
    for (int s = 0; s < kMaxSections; s++) {
        const int b = s * kStride;
        sd[b + 5] = sd[b + 6] = sd[b + 7] = sd[b + 8] = -1.0f;   // no macro overrides
        sd[b + 18] = sd[b + 19] = sd[b + 20] = -1.0f;            // no comping overrides
    }
    sd[0] = sd[1] = 64.0f;   // bars: the run never leaves the section
    sd[2] = 1.0f;            // bar_step
    sd[3] = 0.8f;            // recency_decay
    sd[4] = 1.0f;            // one outgoing edge
    for (int i = 0; i < kMaxSections * kStride; i++)
        engine->pulsar_section_data[i].store(sd[i], std::memory_order_relaxed);
    std::vector<float> tr(kMaxSections * kMaxSectionTransitions * 3, 0.0f);
    tr[1] = 1.0f;            // 0 -> 0, hard cut
    for (int i = 0; i < kMaxSections * kMaxSectionTransitions * 3; i++)
        engine->pulsar_section_transitions[i].store(tr[i], std::memory_order_relaxed);
    // The int override families read 0 as a real value, so they get the -1 sentinel.
    for (int i = 0; i < kMaxSections * kNumPulsarTracks; i++) {
        engine->pulsar_section_track_comping_style[i].store(-1, std::memory_order_relaxed);
        engine->pulsar_section_track_inversion[i].store(-1, std::memory_order_relaxed);
        engine->pulsar_section_track_arp_mode[i].store(-1, std::memory_order_relaxed);
        engine->pulsar_section_track_chord_follow[i].store(-1, std::memory_order_relaxed);
        engine->pulsar_section_track_density[i].store(-1.0f, std::memory_order_relaxed);
        engine->pulsar_section_track_breathe_bars[i].store(0.0f, std::memory_order_relaxed);
        engine->pulsar_section_track_breathe_floor[i].store(0.0f, std::memory_order_relaxed);
        engine->pulsar_section_track_breathe_timbre_span[i].store(0.0f, std::memory_order_relaxed);
    }
    engine->pulsar_section_track_breathe_bars[kKeys].store(2.0f, std::memory_order_relaxed);
    engine->pulsar_section_track_breathe_floor[kKeys].store(floor_gain, std::memory_order_relaxed);
    for (int s = 0; s < kMaxSections; s++) {
        engine->pulsar_section_progression_length[s].store(0, std::memory_order_relaxed);
        engine->pulsar_section_chords_per_bar[s].store(0, std::memory_order_relaxed);
        engine->pulsar_section_tension_active[s].store(0, std::memory_order_relaxed);
    }
    for (int i = 0; i < 8 * 15; i++) {
        engine->pulsar_track_solo_behavior[i].store(0.0f, std::memory_order_relaxed);
        engine->pulsar_track_solo_markov[i].store(0.0f, std::memory_order_relaxed);
    }
    for (int i = 0; i < kNumPulsarTracks * kTrackDuckingFields; i++)
        engine->pulsar_track_ducking[i].store(0.0f, std::memory_order_relaxed);
    engine->pulsar_arrangement_generation.store(1, std::memory_order_release);
}

// kKeys soloed and centred, RNGs pinned. Centred, left minus right is the grains' side alone.
static void setup_fx_track(OrpheusEngine* engine, const FxSetup& s) {
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    solo_track(engine, kKeys);
    engine->pulsar_track_engine_edm[kKeys].store(s.engine_id, std::memory_order_relaxed);
    engine->pulsar_track_engine_space[kKeys].store(s.engine_id, std::memory_order_relaxed);
    engine->pulsar_track_pan[kKeys].store(0.0f, std::memory_order_relaxed);
    FX_SET(volume, kKeys, 0.85f);
    engine->pulsar_track_density_override[kKeys].store(0.5f, std::memory_order_relaxed);
    FX_SET(grain_mode, kKeys, 0);
    FX_SET(grain_scatter_mode, kKeys, 0);
    FX_SET(grain_voicing, kKeys, 0);
    FX_SET(grain_scatter, kKeys, 0.0f);
    FX_SET(grain_rate, kKeys, s.rate);
    FX_SET(grain_random_timing, kKeys, s.random_timing);
    FX_SET(grain_shape, kKeys, 0.5f);
    FX_SET(grain_blend, kKeys, s.blend);
    route_grain(engine, "grain_fx", static_cast<float>(s.grain_fx));
    route_grain(engine, "grain_size", s.size);
    engine->pulsar_mist_width_debug.store(s.width, std::memory_order_relaxed);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);
    if (s.breathe_floor >= 0.0f) {
        // One bar is exactly 1 s; energy 1 pins tempo drift so bars stay arithmetic.
        push_breathe_section(engine, s.breathe_floor);
        engine->pulsar_energy.store(1.0f, std::memory_order_relaxed);
        engine->clock_bpm.store(240.0f, std::memory_order_relaxed);
    }
}

// Every kKeys step a gated full-length note at `note`, played as written. The state
// only exists after the first block, so that one plays the generator's notes.
static void pin_note(OrpheusEngine* engine, int note) {
    PulsarState* state = engine->pulsar_state;
    if (!state) return;
    PulsarTrackState& ts = state->tracks[kKeys];
    for (int i = 0; i < kMaxPulsarSteps; i++) {
        PulsarStep& st = ts.steps[i];
        st.note = st.raw_note = static_cast<uint8_t>(note);
        st.gate = true;
        st.velocity = 0.8f;
        st.duration = 1.0f;
        st.hold = false;
        st.ghost = false;
        st.glide_rate = -1.0f;
        st.hit_probability = 1.0f;
        st.from_lick = true;   // fires past the density roll
    }
    ts.chord_follow = ChordFollowMode::FIXED;
    ts.target_pitch = ts.current_pitch = static_cast<float>(note);
}

static void make_fx_unit(GraphUnit& unit) {
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
}

// The keys bus stem and both fx sends, taken before the master limiter so left and
// right stay linear in the side signal, plus the bar and rendered note per block.
struct FxRun {
    std::vector<float> l, r, rev_l, rev_r, dly_l, dly_r;
    std::vector<int> bar;
    std::vector<float> note;
};

static void append_block(FxRun& run, OrpheusEngine* engine) {
    auto add = [](std::vector<float>& v, const float* p) { v.insert(v.end(), p, p + kBlockFrames); };
    add(run.l, engine->pulsar_bus_keys_l);
    add(run.r, engine->pulsar_bus_keys_r);
    add(run.rev_l, engine->pulsar_reverb_send_l);
    add(run.rev_r, engine->pulsar_reverb_send_r);
    add(run.dly_l, engine->pulsar_delay_send_l);
    add(run.dly_r, engine->pulsar_delay_send_r);
    run.bar.push_back(engine->pulsar_state->loop_count);
    run.note.push_back(engine->pulsar_track_note_debug[kKeys].load(std::memory_order_relaxed));
}

static FxRun render_fx(const FxSetup& s) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    make_fx_unit(unit);
    setup_fx_track(engine, s);
    FX_SET(reverb_send, kKeys, 0.3f);
    FX_SET(delay_send, kKeys, 0.3f);
    trigger_vibe_load(engine);
    FxRun run;
    for (int i = 0; i < s.blocks; i++) {
        if (s.note > 0) pin_note(engine, s.note);
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        append_block(run, engine);
    }
    orpheus_engine_destroy(engine);
    return run;
}

static std::vector<float> fx_sum(const FxRun& run) {
    std::vector<float> v(run.l.size());
    for (size_t i = 0; i < v.size(); i++) v[i] = run.l[i] + run.r[i];
    return v;
}

static std::vector<float> fx_diff(const FxRun& run) {
    std::vector<float> v(run.l.size());
    for (size_t i = 0; i < v.size(); i++) v[i] = run.l[i] - run.r[i];
    return v;
}

static float fx_max_abs_diff(const std::vector<float>& a, const std::vector<float>& b) {
    float m = 0.0f;
    for (size_t i = 0; i < a.size() && i < b.size(); i++) m = std::max(m, std::fabs(a[i] - b[i]));
    return m;
}

static bool same_samples(const std::vector<float>& a, const std::vector<float>& b) {
    return a.size() == b.size() && std::memcmp(a.data(), b.data(), a.size() * sizeof(float)) == 0;
}

static bool same_stems(const FxRun& a, const FxRun& b) {
    return same_samples(a.l, b.l) && same_samples(a.r, b.r)
        && same_samples(a.rev_l, b.rev_l) && same_samples(a.rev_r, b.rev_r)
        && same_samples(a.dly_l, b.dly_l) && same_samples(a.dly_r, b.dly_r);
}

static float fx_peak(const std::vector<float>& v) {
    float p = 0.0f;
    for (float x : v) p = std::max(p, std::fabs(x));
    return p;
}

static bool test_engine_routing_and_defaults() {
    printf("\n=== Test: grain_fx and grain_size route to both slots; unpushed defaults ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool ok = true;
    for (int t = 0; t < 8; t++) {
        ok &= (engine->pulsar_track_grain_fx[t].load() == 0);
        ok &= (engine->pulsar_track_grain_fx_space[t].load() == 0);
        ok &= (engine->pulsar_track_grain_size[t].load() == 0.5f);
        ok &= (engine->pulsar_track_grain_size_space[t].load() == 0.5f);
    }
    orpheus_engine_set_port(engine, kPulsarUri, "track_2_grain_fx", 1.0f);
    orpheus_engine_set_port(engine, kPulsarUri, "track_3_grain_fx_space", 1.0f);
    orpheus_engine_set_port(engine, kPulsarUri, "track_2_grain_size", 0.25f);
    orpheus_engine_set_port(engine, kPulsarUri, "track_3_grain_size_space", 0.75f);
    ok &= (engine->pulsar_track_grain_fx[2].load() == 1) && (engine->pulsar_track_grain_fx_space[2].load() == 0);
    ok &= (engine->pulsar_track_grain_fx_space[3].load() == 1) && (engine->pulsar_track_grain_fx[3].load() == 0);
    ok &= (engine->pulsar_track_grain_size[2].load() == 0.25f);
    ok &= (engine->pulsar_track_grain_size_space[3].load() == 0.75f);
    printf("Routing and defaults: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Blend 0 keeps the grain layer running but out of the mix.
static bool test_blend_zero_matches_flag_off() {
    printf("\n=== Test: grain FX at blend 0 sounds like grain FX off ===\n");
    FxSetup on, off;
    on.blend = off.blend = 0.0f;
    off.grain_fx = 0;
    const FxRun a = render_fx(on);
    const FxRun b = render_fx(off);
    const float gap = fx_max_abs_diff(fx_sum(a), fx_sum(b));
    const float side_gap = fx_max_abs_diff(fx_diff(a), fx_diff(b));   // blend 0 adds no side
    const float peak = fx_peak(a.l);
    printf("  L+R max abs diff %.9f, L-R %.9f, peak %.4f\n", gap, side_gap, peak);
    const bool ok = (gap < 1e-6f) && (side_gap < 1e-6f) && (peak > 0.001f);
    printf("Blend zero: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// The grains' side signal is everything the width changes. With the flag off the width
// must change nothing, the check test_pulsar_other_engines_ignore_the_side_channel makes.
static bool test_grain_fx_adds_a_side_channel() {
    printf("\n=== Test: grain FX on a VA track adds a side channel, off adds none ===\n");
    FxSetup wide, mono;
    mono.width = 0.0f;
    const FxRun w = render_fx(wide);
    const FxRun m = render_fx(mono);
    std::vector<float> side = fx_diff(w);
    const std::vector<float> dm = fx_diff(m);
    for (size_t i = 0; i < side.size(); i++) side[i] -= dm[i];
    const double side_rms = rms(side.data(), static_cast<int>(side.size()));
    const float sum_gap = fx_max_abs_diff(fx_sum(w), fx_sum(m));

    FxSetup wide_off = wide, mono_off = mono;
    wide_off.grain_fx = mono_off.grain_fx = 0;
    const FxRun wo = render_fx(wide_off);
    const FxRun mo = render_fx(mono_off);
    const bool off_same = same_stems(wo, mo);
    printf("  on: side rms %.5f, L+R gap %.7f   off: width-independent=%d peak %.4f\n",
           side_rms, sum_gap, off_same ? 1 : 0, fx_peak(wo.l));
    const bool ok = (side_rms > 1e-4) && (sum_gap < 1e-4f) && off_same && (fx_peak(wo.l) > 0.001f);
    printf("Side channel: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static double sum_rms(const FxRun& run) {
    const std::vector<float> v = fx_sum(run);
    return rms(v.data(), static_cast<int>(v.size()));
}

// At the Kotlin defaults (BURST, rate 0.5, size and shape 0.5, scatter 0) a blend-1 cloud
// sits within 3 dB of the dry voice it replaces.
static bool test_default_cloud_matches_dry_level() {
    printf("\n=== Test: a default blend-1 cloud sits near the dry voice's level ===\n");
    bool ok = true;
    for (int note : {0, 69}) {
        FxSetup wet, dry;
        wet.rate = dry.rate = 0.5f;
        wet.random_timing = dry.random_timing = 0;
        wet.note = dry.note = note;
        wet.blocks = dry.blocks = 375;   // about 4 s
        dry.grain_fx = 0;
        const double w = sum_rms(render_fx(wet));
        const double d = sum_rms(render_fx(dry));
        const double db = 20.0 * std::log10(std::max(w, 1e-30) / std::max(d, 1e-30));
        printf("  %s: L+R rms wet %.5f dry %.5f (%+.2f dB)\n",
               note ? "pinned note 69" : "generator notes", w, d, db);
        ok &= d > 1e-3 && std::fabs(db) <= 3.0;
    }
    printf("Default level: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_mst_ignores_grain_fx() {
    printf("\n=== Test: grain FX on an MST track changes nothing ===\n");
    FxSetup on, off;
    on.engine_id = off.engine_id = kMistEngineId;
    off.grain_fx = 0;
    const FxRun a = render_fx(on);
    const FxRun b = render_fx(off);
    const bool same = same_stems(a, b);
    printf("  identical=%d peak=%.4f\n", same ? 1 : 0, fx_peak(a.l));
    const bool ok = same && (fx_peak(a.l) > 0.001f);
    printf("MST ignores grain FX: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// 1 s of grain FX over note 69, one block without it, then note 76 with it back. The
// block drops grain FX by its flag, or by swapping the engine to MST and back.
static bool reactivation_is_clean(bool engine_swap) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    make_fx_unit(unit);
    setup_fx_track(engine, FxSetup{});
    trigger_vibe_load(engine);
    FxRun run;
    auto block = [&](int note) {
        pin_note(engine, note);
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
        append_block(run, engine);
    };
    for (int i = 0; i < 94; i++) block(69);
    const float note_before = run.note.back();
    if (engine_swap) {
        engine->pulsar_track_engine_edm[kKeys].store(kMistEngineId, std::memory_order_relaxed);
        engine->pulsar_track_engine_space[kKeys].store(kMistEngineId, std::memory_order_relaxed);
    } else {
        route_grain(engine, "grain_fx", 0.0f);
    }
    block(76);
    engine->pulsar_track_engine_edm[kKeys].store(kVa, std::memory_order_relaxed);
    engine->pulsar_track_engine_space[kKeys].store(kVa, std::memory_order_relaxed);
    route_grain(engine, "grain_fx", 1.0f);
    const size_t at = run.l.size();
    const size_t first_block = run.note.size();
    const int n = 4800;   // 100 ms
    while (run.l.size() < at + n) block(76);
    float lo = 1e9f, hi = -1e9f;
    for (size_t b = first_block; b < run.note.size(); b++) {
        lo = std::min(lo, run.note[b]);
        hi = std::max(hi, run.note[b]);
    }
    std::vector<float> side(n);
    for (int i = 0; i < n; i++) side[i] = run.l[at + i] - run.r[at + i];
    const double e440 = tone_energy(side.data(), n, 440.0, 48000.0);
    const double e659 = tone_energy(side.data(), n, 659.255, 48000.0);
    printf("  note %.1f before, %.1f..%.1f after; L-R e440=%.3g e659=%.3g ratio=%.4f\n",
           note_before, lo, hi, e440, e659, e440 / std::max(e659, 1e-30));
    orpheus_engine_destroy(engine);
    // The floor is far under the new note's grains and far over the centred pan's residue.
    return note_before == 69.0f && lo == 76.0f && hi == 76.0f
        && e659 > 1e-12 && e440 < 0.01 * e659;
}

static bool test_reactivation_wakes_the_buffer() {
    printf("\n=== Test: turning grain FX back on replays none of the earlier voice ===\n");
    const bool ok = reactivation_is_clean(false);
    printf("Reactivation: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_engine_swap_wakes_the_buffer() {
    printf("\n=== Test: a block on MST and back replays none of the earlier voice ===\n");
    const bool ok = reactivation_is_clean(true);
    printf("Engine swap: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// A vibe load changes key and bank under the track; its grains must not carry over.
static bool test_vibe_load_wakes_the_buffer() {
    printf("\n=== Test: a vibe load drops the previous vibe's grains ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    make_fx_unit(unit);
    FxSetup s;
    s.size = 1.0f;   // 2 s grains, so the cloud piles up
    setup_fx_track(engine, s);
    trigger_vibe_load(engine);
    for (int i = 0; i < 200; i++) {
        pin_note(engine, 69);
        unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
    }
    const int before = engine->pulsar_state->grainfx[kKeys].grains.CountActiveGrains();
    trigger_vibe_load(engine);
    unit_process_pulsar(&unit, engine, kBlockFrames, 48000.0f);
    const int after = engine->pulsar_state->grainfx[kKeys].grains.CountActiveGrains();
    printf("  active grains: %d before the load, %d one block after\n", before, after);
    // The new vibe's downbeat lands in that block and can start at most two grains.
    const bool ok = (before >= 6) && (after <= 2);
    printf("Vibe load: %s\n", ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// RMS of left plus right and left minus right over the floor bar, skipping the swell into it.
static void floor_bar_rms(const FxRun& run, double* sum_rms, double* diff_rms) {
    double s = 0.0, d = 0.0;
    long n = 0;
    int seen = 0;
    for (size_t b = 0; b < run.bar.size(); b++) {
        if (run.bar[b] != 1 || seen++ < 25) continue;
        for (int i = 0; i < kBlockFrames; i++) {
            const double l = run.l[b * kBlockFrames + i], r = run.r[b * kBlockFrames + i];
            s += (l + r) * (l + r);
            d += (l - r) * (l - r);
            n++;
        }
    }
    *sum_rms = n > 0 ? std::sqrt(s / n) : 0.0;
    *diff_rms = n > 0 ? std::sqrt(d / n) : 0.0;
}

// Upstream of the breathe the two runs are identical, so the floor bar's level ratio is
// the breathe gain. The side has to sink by it too or the cloud widens as the voice sinks.
static bool test_breathe_sinks_the_grain_side() {
    printf("\n=== Test: a breathe at its floor sinks the grain side with the centre ===\n");
    FxSetup dry, wet;
    dry.note = wet.note = 69;
    dry.blocks = wet.blocks = 300;   // into bar 2
    dry.breathe_floor = 1.0f;   // the path runs at a gain of exactly 1
    wet.breathe_floor = 0.25f;
    const FxRun a = render_fx(dry);
    const FxRun b = render_fx(wet);
    double sum_a, diff_a, sum_b, diff_b;
    floor_bar_rms(a, &sum_a, &diff_a);
    floor_bar_rms(b, &sum_b, &diff_b);
    const double f_sum = sum_b / std::max(sum_a, 1e-30);
    const double f_diff = diff_b / std::max(diff_a, 1e-30);
    printf("  L+R %.5f -> %.5f (x%.3f), L-R %.5f -> %.5f (x%.3f)\n",
           sum_a, sum_b, f_sum, diff_a, diff_b, f_diff);
    const bool ok = (diff_a > 1e-4) && (f_sum < 0.5) && std::fabs(f_diff / f_sum - 1.0) < 0.05;
    printf("Breathe sinks the side: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

bool run_pulsar_grain_fx_tests() {
    printf("\n=== Pulsar Grain FX Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    if (test_hold_samples_values()) suite_pass++; else suite_fail++;
    if (test_replays_its_input_pitch()) suite_pass++; else suite_fail++;
    if (test_chord_scatter_lands_on_chord_tones()) suite_pass++; else suite_fail++;
    if (test_blend_zero_leaves_io_untouched()) suite_pass++; else suite_fail++;
    if (test_wake_drops_stale_audio()) suite_pass++; else suite_fail++;
    if (test_note_on_hold_defers_first_grain()) suite_pass++; else suite_fail++;
    if (test_hold_on_legato_note_change()) suite_pass++; else suite_fail++;
    if (test_fast_notes_do_not_starve_the_cloud()) suite_pass++; else suite_fail++;
    if (test_limiter_holds_full_scale()) suite_pass++; else suite_fail++;
    if (test_wide_raw_scatter_stays_finite()) suite_pass++; else suite_fail++;
    if (test_lookback_fits_at_96k()) suite_pass++; else suite_fail++;
    if (test_engine_routing_and_defaults()) suite_pass++; else suite_fail++;
    if (test_blend_zero_matches_flag_off()) suite_pass++; else suite_fail++;
    if (test_grain_fx_adds_a_side_channel()) suite_pass++; else suite_fail++;
    if (test_default_cloud_matches_dry_level()) suite_pass++; else suite_fail++;
    if (test_mst_ignores_grain_fx()) suite_pass++; else suite_fail++;
    if (test_reactivation_wakes_the_buffer()) suite_pass++; else suite_fail++;
    if (test_engine_swap_wakes_the_buffer()) suite_pass++; else suite_fail++;
    if (test_vibe_load_wakes_the_buffer()) suite_pass++; else suite_fail++;
    if (test_breathe_sinks_the_grain_side()) suite_pass++; else suite_fail++;
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
