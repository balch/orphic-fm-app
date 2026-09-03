// Per-track breathe cycle (gain + timbre).
//
// A breathe is a bar-clocked swell authored as a per-section TRACK override
// (breathe_bars / breathe_floor / breathe_timbre_span). The phase restarts at the
// TOP on section entry and descends, so the track sinks toward its floor and rises
// back instead of muting out. bars == 0 is off, and off must be EXACTLY unity.
//
// MEASUREMENT DESIGN — what makes these assertions exact rather than flaky:
//  * Drive unit_process_pulsar directly and read engine->pulsar_out_l: no master bus,
//    no returns, no limiter between the breathe and the number being asserted.
//  * Every level claim is a RATIO against an identically-seeded no-breathe render of
//    the same case. Pulsar's melodic tracks rest whole bars, so an absolute per-bar
//    RMS threshold would measure the pattern, not the swell. With timbre_span 0 the
//    two runs generate and render identical notes, so the ratio IS the gain envelope.
//  * 240 BPM at 48 kHz puts one 16-step bar at exactly 48000 samples = 1.0 s, and
//    pulsar_energy 1.0 pins tempo drift to 0, so bar boundaries are arithmetic.
//  * Bars are bucketed by state->loop_count, not by block arithmetic: one bar is
//    93.75 blocks, so an index-derived bucket would smear by a block every 4 bars.
//  * Both RNGs are pinned (pulsar_seed + the global stmlib::Random); the suite hands
//    the global one back exactly as it found it.
#include "test_harness.h"   // declares braids/plaits namespaces before orpheus_unit_pulsar.h
#include "test_pulsar_helpers.h"
#include "orpheus_engine.h"
#include "orpheus_unit_pulsar.h"
#include "../src/pulsar_breathe.h"
#include "stmlib/utils/random.h"
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <cmath>
#include <vector>

static constexpr float    kSR          = 48000.0f;
static constexpr int      kBlock       = 512;
static constexpr float    kBPM         = 240.0f;   // one bar = 48000 samples = 1.0 s
static constexpr int      kPatternSeed = 4242;
static constexpr uint32_t kNoiseSeed   = 0x5A4F0BADu;
// Track 3 is the hard-wired bass slot and MELODIC in the baseline fixture — the same
// role RustBelt's breathing hook occupies.
static constexpr int      kTrack       = 3;
// WTB (wavetable): timbre_floor 0 and the full 0..1 mod range, so a timbre bias is
// observable at the debug peek without an engine playability floor clipping it.
static constexpr int      kEngine      = 13;

struct BreatheSpec {
    int   bars        = 0;
    float floor_gain  = 0.0f;
    float timbre_span = 0.0f;
};

struct BreatheCase {
    BreatheSpec s0;                    // section 0's breathe on kTrack
    BreatheSpec s1;                    // section 1's breathe on kTrack (default: none)
    int   bars_per_section = 8;
    float density          = 1.0f;     // pulsar_track_density_override on kTrack
    bool  pin_timbre       = false;
    float timbre           = 0.6f;
    int   blocks           = 800;
};

struct BreatheTrace {
    std::vector<float> out;        // pulsar_out_l, concatenated
    std::vector<float> rms;        // per block
    std::vector<int>   bar;        // loop_count after each block
    std::vector<int>   section;    // current_section after each block
    std::vector<float> timbre;     // pulsar_track_mod_timbre_debug[kTrack] after each block
};

// Two-section arrangement, hard cuts, no solos, fixed bar counts. Section 0 is the
// intro so the opening section is deterministic. Each section carries its own breathe
// on kTrack, so a flip can be a handoff between two live cycles or an exit into a
// breathe-less section — those are different code paths and both are tested below.
static void push_breathe_arrangement(OrpheusEngine* engine, int bars_per_section,
                                     const BreatheSpec& s0, const BreatheSpec& s1) {
    engine->pulsar_arrangement_active.store(1, std::memory_order_relaxed);
    engine->pulsar_arrangement_section_count.store(2, std::memory_order_relaxed);
    engine->pulsar_arrangement_intro_index.store(0, std::memory_order_relaxed);
    engine->pulsar_arrangement_outro_index.store(-1, std::memory_order_relaxed);

    constexpr int kStride = kSectionDataFields;
    std::vector<float> sd(kMaxSections * kStride, 0.0f);
    for (int s = 0; s < kMaxSections; s++) {
        const int b = s * kStride;
        sd[b + 5] = sd[b + 6] = sd[b + 7] = sd[b + 8] = -1.0f;   // no macro overrides
        sd[b + 9] = 0.0f;                                        // no solo
        sd[b + 18] = sd[b + 19] = sd[b + 20] = -1.0f;            // no comping overrides
    }
    for (int s = 0; s < 2; s++) {
        const int b = s * kStride;
        sd[b + 0] = static_cast<float>(bars_per_section);
        sd[b + 1] = static_cast<float>(bars_per_section);
        sd[b + 2] = 1.0f;      // bar_step
        sd[b + 3] = 0.8f;      // recency_decay
        sd[b + 4] = 1.0f;      // one outgoing edge
    }
    for (int i = 0; i < kMaxSections * kStride; i++)
        engine->pulsar_section_data[i].store(sd[i], std::memory_order_relaxed);

    std::vector<float> tr(kMaxSections * kMaxSectionTransitions * 3, 0.0f);
    tr[0] = 1.0f; tr[1] = 1.0f; tr[2] = 0.0f;                    // 0 -> 1, hard cut
    const int edge1 = kMaxSectionTransitions * 3;                // section 1's edge base
    tr[edge1 + 0] = 0.0f; tr[edge1 + 1] = 1.0f; tr[edge1 + 2] = 0.0f;   // 1 -> 0, hard cut
    for (int i = 0; i < kMaxSections * kMaxSectionTransitions * 3; i++)
        engine->pulsar_section_transitions[i].store(tr[i], std::memory_order_relaxed);

    // Per-track section-override bank. The four int families default to 0, which is a
    // REAL override value (style 0), so they get the -1 sentinel explicitly the way the
    // Kotlin push does. The three breathe slots default to "off" at 0.
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
    const BreatheSpec* spec[2] = { &s0, &s1 };
    for (int s = 0; s < 2; s++) {
        const int slot = s * kNumPulsarTracks + kTrack;
        engine->pulsar_section_track_breathe_bars[slot]
            .store(static_cast<float>(spec[s]->bars), std::memory_order_relaxed);
        engine->pulsar_section_track_breathe_floor[slot]
            .store(spec[s]->floor_gain, std::memory_order_relaxed);
        engine->pulsar_section_track_breathe_timbre_span[slot]
            .store(spec[s]->timbre_span, std::memory_order_relaxed);
    }

    for (int s = 0; s < kMaxSections; s++) {
        engine->pulsar_section_progression_length[s].store(0, std::memory_order_relaxed);
        engine->pulsar_section_chords_per_bar[s].store(0, std::memory_order_relaxed);
        engine->pulsar_section_tension_active[s].store(0, std::memory_order_relaxed);
    }
    for (int i = 0; i < 8 * 15; i++)
        engine->pulsar_track_solo_behavior[i].store(0.0f, std::memory_order_relaxed);
    for (int i = 0; i < kNumPulsarTracks * kTrackDuckingFields; i++)
        engine->pulsar_track_ducking[i].store(0.0f, std::memory_order_relaxed);
    for (int i = 0; i < 8 * 15; i++)
        engine->pulsar_track_solo_markov[i].store(0.0f, std::memory_order_relaxed);

    engine->pulsar_arrangement_generation.store(1, std::memory_order_release);
}

static BreatheTrace run_case(const BreatheCase& c) {
    BreatheTrace tr;
    OrpheusEngine* engine = orpheus_engine_create(kSR);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);

    setup_fixture_baseline(engine);
    for (int t = 0; t < kNumPulsarTracks; t++) {
        engine->pulsar_track_mute[t].store(0, std::memory_order_relaxed);
        engine->pulsar_track_density_override[t]
            .store(t == kTrack ? c.density : 0.0f, std::memory_order_relaxed);
        engine->pulsar_track_engine_edm[t].store(kEngine, std::memory_order_relaxed);
        engine->pulsar_track_engine_space[t].store(kEngine, std::memory_order_relaxed);
        engine->pulsar_track_timbre[t].store(c.timbre, std::memory_order_relaxed);
        engine->pulsar_track_timbre_space[t].store(c.timbre, std::memory_order_relaxed);
        const int pin = c.pin_timbre ? 1 : 0;
        engine->pulsar_track_pin_timbre[t].store(pin, std::memory_order_relaxed);
        engine->pulsar_track_pin_timbre_space[t].store(pin, std::memory_order_relaxed);
    }
    push_breathe_arrangement(engine, c.bars_per_section, c.s0, c.s1);

    // Solo AFTER the fixture writes the volumes and BEFORE the vibe load: ts.volume
    // latches inside load_vibe, it is not re-read per block.
    solo_track(engine, kTrack);
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

    tr.out.reserve(static_cast<size_t>(c.blocks) * kBlock);
    for (int b = 0; b < c.blocks; b++) {
        unit_process_pulsar(&unit, engine, kBlock, kSR);
        tr.out.insert(tr.out.end(), engine->pulsar_out_l, engine->pulsar_out_l + kBlock);
        tr.rms.push_back(compute_rms(engine->pulsar_out_l, kBlock));
        const PulsarState* ps = engine->pulsar_state;
        tr.bar.push_back(ps ? ps->loop_count : 0);
        tr.section.push_back(ps ? ps->section_state.current_section : 0);
        tr.timbre.push_back(
            engine->pulsar_track_mod_timbre_debug[kTrack].load(std::memory_order_relaxed));
    }
    orpheus_engine_destroy(engine);
    return tr;
}

// Root-mean-square of the per-block RMS values in [lo, hi) — the energy of that
// window, weighted the way the blocks actually carry it.
static float window_rms(const BreatheTrace& tr, int lo, int hi) {
    double sq = 0.0; long n = 0;
    const int cap = static_cast<int>(tr.rms.size());
    for (int b = (lo < 0 ? 0 : lo); b < (hi > cap ? cap : hi); b++) {
        sq += static_cast<double>(tr.rms[b]) * tr.rms[b]; n++;
    }
    return n > 0 ? static_cast<float>(std::sqrt(sq / n)) : 0.0f;
}

static float bar_rms(const BreatheTrace& tr, int bar) {
    double sq = 0.0; long n = 0;
    for (size_t b = 0; b < tr.rms.size(); b++) {
        if (tr.bar[b] != bar) continue;
        sq += static_cast<double>(tr.rms[b]) * tr.rms[b]; n++;
    }
    return n > 0 ? static_cast<float>(std::sqrt(sq / n)) : -1.0f;
}

// (a) The regression guard. bars == 0 skips the breathe path entirely; bars > 0 with
// floor 1.0 RUNS it at a gain of exactly 1.0. Both must be bit-identical to each
// other, which is the only way to pin "off costs the mix nothing" from inside a
// build that always has the feature compiled in.
static bool test_breathe_off_is_bit_identical() {
    printf("\n=== Test: no breathe, and a unity-floor breathe, are bit-identical ===\n");
    BreatheCase off;
    BreatheCase unity;
    unity.s0 = {2, 1.0f, 0.0f};      // path armed, envelope pinned at the top
    const BreatheTrace a = run_case(off);
    const BreatheTrace b = run_case(unity);

    float loudest = 0.0f;
    for (float s : a.out) loudest = std::fmax(loudest, std::fabs(s));
    size_t first_diff = a.out.size();
    for (size_t i = 0; i < a.out.size(); i++)
        if (a.out[i] != b.out[i]) { first_diff = i; break; }

    bool ok = true;
    if (loudest < 1e-3f) {
        printf("  FAIL: the baseline render is silent (%.4g) -- nothing to compare\n", loudest);
        ok = false;
    }
    if (first_diff != a.out.size()) {
        printf("  FAIL: diverge at sample %zu (%.9g vs %.9g)\n",
               first_diff, a.out[first_diff], b.out[first_diff]);
        ok = false;
    }
    if (ok) printf("  PASS: %zu samples bit-identical, peak %.3f\n", a.out.size(), loudest);
    return ok;
}

// (b) The swell itself. bars = 2 puts the top on even bars and the bottom on odd
// ones. Ratios against a no-breathe render of the same case, so the pattern's own
// per-bar rests cancel out.
static bool test_breathe_gain_cycles_per_bar() {
    printf("\n=== Test: a 2-bar breathe sinks and rises on alternating bars ===\n");
    BreatheCase dry;
    dry.bars_per_section = 8;
    BreatheCase wet = dry;
    wet.s0 = {2, 0.1f, 0.0f};        // timbre_span 0: identical notes, identical timbre
    const BreatheTrace a = run_case(dry);
    const BreatheTrace b = run_case(wet);

    double top_sum = 0.0, bot_sum = 0.0;
    int top_n = 0, bot_n = 0;
    float worst_top = 1e9f, worst_bot = 0.0f;
    printf("  bar   dry_rms   wet_rms   ratio\n");
    for (int bar = 0; bar < 8; bar++) {
        const float ra = bar_rms(a, bar);
        const float rb = bar_rms(b, bar);
        if (ra < 1e-4f) { printf("  %3d   (silent, skipped)\n", bar); continue; }
        const float ratio = rb / ra;
        printf("  %3d   %.5f   %.5f   %.3f%s\n", bar, ra, rb, ratio,
               (bar % 2 == 0) ? "   <- top" : "   <- bottom");
        if (bar % 2 == 0) { top_sum += ratio; top_n++; worst_top = std::fmin(worst_top, ratio); }
        else              { bot_sum += ratio; bot_n++; worst_bot = std::fmax(worst_bot, ratio); }
    }

    bool ok = true;
    if (top_n < 3 || bot_n < 3) {
        printf("  FAIL: only %d top / %d bottom bars carried notes\n", top_n, bot_n); ok = false;
    } else {
        const float top = static_cast<float>(top_sum / top_n);
        const float bot = static_cast<float>(bot_sum / bot_n);
        if (top < 0.70f) { printf("  FAIL: top-of-cycle bars lost level (mean ratio %.3f)\n", top); ok = false; }
        if (bot > 0.60f) { printf("  FAIL: bottom-of-cycle bars did not sink (mean ratio %.3f)\n", bot); ok = false; }
        if (worst_bot >= worst_top) {
            printf("  FAIL: cycle does not separate (worst top %.3f <= worst bottom %.3f)\n",
                   worst_top, worst_bot); ok = false;
        }
        if (ok) printf("  PASS: top mean %.3f, bottom mean %.3f\n", top, bot);
    }
    return ok;
}

// (c) The timbre half. Pinned timbre keeps every other writer off mod_timbre, so the
// debug peek is the base value plus the breathe bias and nothing else.
static bool test_breathe_biases_timbre_by_span() {
    printf("\n=== Test: the breathe closes timbre by its span across a cycle ===\n");
    const float span = 0.35f;
    BreatheCase c;
    c.s0 = {4, 0.5f, span};          // 4-bar cycle: the phase visits top, mid, bottom, mid
    c.bars_per_section = 8;
    c.pin_timbre = true;
    c.timbre = 0.6f;
    const BreatheTrace tr = run_case(c);

    // Bars 4..7 = the second full cycle, past the opening bar's settle.
    float lo = 1e9f, hi = -1e9f;
    int n = 0;
    for (size_t b = 0; b < tr.timbre.size(); b++) {
        if (tr.bar[b] < 4 || tr.bar[b] > 7) continue;
        lo = std::fmin(lo, tr.timbre[b]);
        hi = std::fmax(hi, tr.timbre[b]);
        n++;
    }

    bool ok = true;
    if (n < 100) { printf("  FAIL: only %d blocks landed in the measured cycle\n", n); ok = false; }
    else {
        const float observed = hi - lo;
        if (std::fabs(observed - span) > 0.05f) {
            printf("  FAIL: timbre swing %.3f (expected %.3f +/- 0.05)\n", observed, span); ok = false;
        }
        if (std::fabs(hi - c.timbre) > 0.05f) {
            printf("  FAIL: top of the cycle biased timbre to %.3f (expected %.3f)\n",
                   hi, c.timbre); ok = false;
        }
        if (ok) printf("  PASS: timbre %.3f -> %.3f (swing %.3f) over %d blocks\n",
                       hi, lo, observed, n);
    }
    return ok;
}

// Shared measurement for the two flip tests below. Renders `wet` and an
// otherwise-identical no-breathe `dry`, finds the first 0 -> 1 section flip, and
// reports the gain ratio over the 20 blocks (~0.21 s) either side of it. 20 blocks is
// chosen against the smoothing constant: a snap holds unity across that window, while
// a one-pole glide up from a sunk floor still averages well under half.
struct FlipMeasurement {
    int   flip = -1;
    float pre_ratio = -1.0f;
    float post_ratio = -1.0f;
    bool  measurable = false;
    bool  same_flip_block = false;
};

static FlipMeasurement measure_flip(const BreatheCase& dry, const BreatheCase& wet) {
    FlipMeasurement m;
    const BreatheTrace a = run_case(dry);
    const BreatheTrace b = run_case(wet);
    for (size_t i = 1; i < b.section.size(); i++)
        if (b.section[i] != 0 && b.section[i - 1] == 0) { m.flip = static_cast<int>(i); break; }
    if (m.flip < 1) return m;

    m.same_flip_block = (a.section[m.flip] == b.section[m.flip] && a.section[m.flip - 1] == 0);
    const float pre_dry  = window_rms(a, m.flip - 20, m.flip);
    const float post_dry = window_rms(a, m.flip, m.flip + 20);
    m.measurable = pre_dry > 1e-4f && post_dry > 1e-4f;
    if (!m.measurable) return m;
    m.pre_ratio  = window_rms(b, m.flip - 20, m.flip)  / pre_dry;
    m.post_ratio = window_rms(b, m.flip, m.flip + 20) / post_dry;
    return m;
}

static bool check_flip(const char* what, const FlipMeasurement& m) {
    bool ok = true;
    if (m.flip < 1) { printf("  FAIL: the arrangement never flipped section\n"); return false; }
    if (!m.same_flip_block) { printf("  FAIL: the two runs did not flip on the same block\n"); ok = false; }
    if (!m.measurable) { printf("  FAIL: no signal to measure around the flip\n"); return false; }
    if (m.pre_ratio > 0.5f) {
        printf("  FAIL: the pre-flip bar was not sunk (ratio %.3f) -- the test is vacuous\n",
               m.pre_ratio);
        ok = false;
    }
    if (m.post_ratio < 0.90f || m.post_ratio > 1.10f) {
        printf("  FAIL: post-flip ratio %.3f (expected ~1.0; a glide up from the floor reads ~0.4)\n",
               m.post_ratio);
        ok = false;
    }
    if (ok) printf("  PASS: %s -- flip at block %d, %.3f before -> %.3f after\n",
                   what, m.flip, m.pre_ratio, m.post_ratio);
    return ok;
}

// (d) Leaving a breathe behind. Two-bar sections put the flip on a sunk bar and the
// incoming section declares no breathe, so the mask bit clears and the render path is
// skipped outright. This pins that the exit is clean — it does NOT pin the envelope
// reset, because with the path skipped the ratio is 1.0 whatever breathe_env holds.
// The handoff test below is what pins the reset.
static bool test_flip_into_a_breathe_less_section_is_clean() {
    printf("\n=== Test: flipping into a section with no breathe returns to unity ===\n");
    BreatheCase dry;
    dry.bars_per_section = 2;
    BreatheCase wet = dry;
    wet.s0 = {2, 0.1f, 0.0f};        // bar 0 top, bar 1 bottom, flip out of the bottom
    return check_flip("mask cleared on exit", measure_flip(dry, wet));
}

// (d') The handoff, and the one case that actually pins the section-entry envelope
// reset. Both sections breathe, so the mask bit STAYS SET across the flip and the
// per-sample gain keeps running; the incoming section's own cycle starts at bar 0 =
// the top, so the only thing that can put the first post-flip block at unity is the
// reset. Without it the envelope carries the outgoing section's sunk value and glides
// up over the next bar, which reads ~0.45 here. The incoming period is deliberately
// different (3 vs 2) so nothing about this can pass by the two cycles coinciding.
static bool test_breathe_handoff_snaps_to_the_incoming_top() {
    printf("\n=== Test: a section-to-section breathe handoff snaps to the incoming top ===\n");
    BreatheCase dry;
    dry.bars_per_section = 2;
    BreatheCase wet = dry;
    wet.s0 = {2, 0.1f, 0.0f};        // flip out of the bottom of a 2-bar cycle
    wet.s1 = {3, 0.1f, 0.0f};        // into a live 3-bar cycle, which starts at its top
    return check_flip("mask still set through the handoff", measure_flip(dry, wet));
}

// The wire itself: three raw-control families indexed s * kNumPulsarTracks + t.
static bool test_breathe_bank_routing() {
    printf("\n=== Test: section_track_breathe_* routes and bounds-checks ===\n");
    OrpheusEngine* engine = orpheus_engine_create(kSR);
    const char* uri = "org.balch.orpheus.plugins.pulsar";
    constexpr int kBank = kMaxSections * kNumPulsarTracks;
    orpheus_engine_set_port(engine, uri, "section_track_breathe_bars_11", 2.0f);
    orpheus_engine_set_port(engine, uri, "section_track_breathe_floor_11", 0.25f);
    orpheus_engine_set_port(engine, uri, "section_track_breathe_timbre_span_11", 0.4f);
    orpheus_engine_set_port(engine, uri, "section_track_breathe_bars_9999", 7.0f);   // dropped

    bool ok = true;
    if (engine->pulsar_section_track_breathe_bars[11].load(std::memory_order_relaxed) != 2.0f) {
        printf("  FAIL: bars slot did not route\n"); ok = false;
    }
    if (engine->pulsar_section_track_breathe_floor[11].load(std::memory_order_relaxed) != 0.25f) {
        printf("  FAIL: floor slot did not route\n"); ok = false;
    }
    if (engine->pulsar_section_track_breathe_timbre_span[11].load(std::memory_order_relaxed) != 0.4f) {
        printf("  FAIL: timbre_span slot did not route\n"); ok = false;
    }
    // The three families share the "section_track_breathe_" prefix; a too-short
    // strncmp would land floor/timbre_span writes in the bars bank.
    for (int i = 0; i < kBank; i++) {
        if (i == 11) continue;
        if (engine->pulsar_section_track_breathe_bars[i].load(std::memory_order_relaxed) != 0.0f) {
            printf("  FAIL: bars slot %d was written by a sibling family\n", i); ok = false; break;
        }
    }
    if (ok) printf("  PASS: 3 x %d-slot bank routed, out-of-range write dropped\n", kBank);
    orpheus_engine_destroy(engine);
    return ok;
}

// Pure-math guards on the curve, independent of the render path.
static bool test_breathe_curve_shape() {
    printf("\n=== Test: the breathe curve starts at the top and sinks to the floor ===\n");
    bool ok = true;
    const float floor_gain = 0.2f, span = 0.5f;
    struct { int bar; float k; } expect[] = {
        {0, 1.0f}, {1, 0.5f}, {2, 0.0f}, {3, 0.5f}, {4, 1.0f},
    };
    for (const auto& e : expect) {
        const float k = orpheus::breathe_envelope(orpheus::breathe_phase(e.bar, 4));
        if (std::fabs(k - e.k) > 1e-5f) {
            printf("  FAIL: bar %d envelope %.5f (expected %.5f)\n", e.bar, k, e.k); ok = false;
        }
    }
    // Entry is the top: full gain and an unbiased tone. The bottom is the floor and
    // a tone closed by the full span.
    const float top = orpheus::breathe_envelope(0.0f);
    const float bot = orpheus::breathe_envelope(0.5f);
    if (orpheus::breathe_gain(top, floor_gain) != 1.0f) {
        printf("  FAIL: top gain %.6f (expected 1)\n", orpheus::breathe_gain(top, floor_gain));
        ok = false;
    }
    if (std::fabs(orpheus::breathe_gain(bot, floor_gain) - floor_gain) > 1e-6f) {
        printf("  FAIL: bottom gain %.6f (expected the floor %.3f)\n",
               orpheus::breathe_gain(bot, floor_gain), floor_gain); ok = false;
    }
    if (orpheus::breathe_timbre_bias(top, span) != 0.0f) {
        printf("  FAIL: top bias %.6f (expected 0 -- the tone is open at the top)\n",
               orpheus::breathe_timbre_bias(top, span)); ok = false;
    }
    if (std::fabs(orpheus::breathe_timbre_bias(bot, span) + span) > 1e-6f) {
        printf("  FAIL: bottom bias %.6f (expected -%.3f)\n",
               orpheus::breathe_timbre_bias(bot, span), span); ok = false;
    }
    // bars 0 is off, and off has to be unity at every phase the caller could ask for.
    if (orpheus::breathe_phase(7, 0) != 0.0f) {
        printf("  FAIL: bars 0 must read as phase 0\n"); ok = false;
    }
    if (ok) printf("  PASS: raised cosine, top at entry, floor and -span at the bottom\n");
    return ok;
}

bool run_pulsar_breathe_tests() {
    printf("\n=== Pulsar Breathe Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_breathe_curve_shape());
    tally(test_breathe_bank_routing());
    // The render tests pin the global noise RNG; later suites read whatever state they
    // inherit, so hand it back exactly as it was found.
    {
        uint32_t saved = stmlib::Random::state();
        tally(test_breathe_off_is_bit_identical());
        tally(test_breathe_gain_cycles_per_bar());
        tally(test_breathe_biases_timbre_by_span());
        tally(test_flip_into_a_breathe_less_section_is_clean());
        tally(test_breathe_handoff_snaps_to_the_incoming_top());
        stmlib::Random::Seed(saved);
    }
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
