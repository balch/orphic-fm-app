// Section ramps: MacroOverrides.volume scales the Pulsar mix for a section, Section.rampTo
// glides a section's macros (and volume) from its overrides to the ramp over its own bars,
// and TensionProfile.arc RISE/FALL runs tension across the section instead of the global
// sawtooth. The intro swell and outro fade of Orphic Lore are built from these.
//
// Two 4-bar sections with hard cuts, section 0 the intro, so every bar is arithmetic:
// 240 BPM with 16 steps puts one bar at 48000 samples. Energy 1.0 pins tempo drift.
#include "test_harness.h"
#include "test_pulsar_helpers.h"
#include "orpheus_engine.h"
#include "orpheus_unit_pulsar.h"
#include "stmlib/utils/random.h"
#include <cmath>
#include <cstdio>
#include <cstring>
#include <utility>
#include <vector>

static constexpr float kSR = 48000.0f;
static constexpr int kBlock = 512;
static constexpr float kBPM = 240.0f;
static constexpr int kBars = 4;
static constexpr int kBlocks = 800;      // a little over two sections
static constexpr int kSlotTrack = 3;     // fixture engines differ by slot (9 / 19)

struct RampSpec {
    float volume = -1.0f, ramp_volume = -1.0f;
    float energy = -1.0f, ramp_energy = -1.0f;
    int arc = -1;   // -1 = no tension override, else kTensionArc*
    std::vector<std::pair<float, float>> envelope;   // (bar, level) volume points
};

struct RampTrace {
    std::vector<int> section, bar;        // bar = bars elapsed in the current section
    std::vector<float> gain, tension, rms;
    std::vector<int> space_slot;
    std::vector<int> spurt;
};

// Slots 31-36 carry value + 1, so 0 (never written) means unset.
static float wire(float v) { return v >= 0.0f ? v + 1.0f : 0.0f; }

// s1_edge: where section 1 goes (0 = back to the intro, 1 = loops to itself); ignored
// when s1_terminal.
static void push_ramp_arrangement(OrpheusEngine* engine, const RampSpec& s0,
                                  const RampSpec& s1, bool s1_terminal, int s1_edge) {
    engine->pulsar_arrangement_active.store(1, std::memory_order_relaxed);
    engine->pulsar_arrangement_section_count.store(2, std::memory_order_relaxed);
    engine->pulsar_arrangement_intro_index.store(0, std::memory_order_relaxed);
    engine->pulsar_arrangement_outro_index.store(-1, std::memory_order_relaxed);

    std::vector<float> sd(kMaxSections * kSectionDataFields, 0.0f);
    for (int s = 0; s < kMaxSections; s++) {
        const int b = s * kSectionDataFields;
        sd[b + 5] = sd[b + 6] = sd[b + 7] = sd[b + 8] = -1.0f;
        sd[b + 18] = sd[b + 19] = sd[b + 20] = -1.0f;
    }
    for (int s = 0; s < 2; s++) {
        const int b = s * kSectionDataFields;
        sd[b + 0] = sd[b + 1] = static_cast<float>(kBars);
        sd[b + 2] = 1.0f;
        sd[b + 3] = 0.8f;
        sd[b + 4] = 1.0f;
    }
    if (s1_terminal) sd[kSectionDataFields + 4] = 0.0f;   // no outgoing edge
    const RampSpec* spec[2] = { &s0, &s1 };
    for (int s = 0; s < 2; s++) {
        const int b = s * kSectionDataFields;
        sd[b + 5] = spec[s]->energy;
        sd[b + 31] = wire(spec[s]->volume);
        sd[b + 32] = wire(spec[s]->ramp_energy);
        sd[b + 36] = wire(spec[s]->ramp_volume);
        sd[b + 37] = static_cast<float>(spec[s]->envelope.size());
        for (size_t i = 0; i < spec[s]->envelope.size(); i++) {
            sd[b + 38 + static_cast<int>(i) * 2] = spec[s]->envelope[i].first;
            sd[b + 39 + static_cast<int>(i) * 2] = spec[s]->envelope[i].second;
        }
    }
    for (int i = 0; i < kMaxSections * kSectionDataFields; i++)
        engine->pulsar_section_data[i].store(sd[i], std::memory_order_relaxed);

    std::vector<float> tr(kMaxSections * kMaxSectionTransitions * 3, 0.0f);
    tr[0] = 1.0f; tr[1] = 1.0f;                                   // 0 -> 1, hard cut
    tr[kMaxSectionTransitions * 3 + 0] = static_cast<float>(s1_edge);
    tr[kMaxSectionTransitions * 3 + 1] = 1.0f;                    // 1 -> s1_edge, hard cut
    for (int i = 0; i < kMaxSections * kMaxSectionTransitions * 3; i++)
        engine->pulsar_section_transitions[i].store(tr[i], std::memory_order_relaxed);

    for (int i = 0; i < kMaxSections * kNumPulsarTracks; i++) {
        engine->pulsar_section_track_comping_style[i].store(-1, std::memory_order_relaxed);
        engine->pulsar_section_track_inversion[i].store(-1, std::memory_order_relaxed);
        engine->pulsar_section_track_arp_mode[i].store(-1, std::memory_order_relaxed);
        engine->pulsar_section_track_chord_follow[i].store(-1, std::memory_order_relaxed);
        engine->pulsar_section_track_density[i].store(-1.0f, std::memory_order_relaxed);
    }
    for (int s = 0; s < kMaxSections; s++) {
        engine->pulsar_section_progression_length[s].store(0, std::memory_order_relaxed);
        engine->pulsar_section_chords_per_bar[s].store(0, std::memory_order_relaxed);
        engine->pulsar_section_tension_active[s].store(0, std::memory_order_relaxed);
    }
    for (int s = 0; s < 2; s++) {
        if (spec[s]->arc < 0) continue;
        engine->pulsar_section_tension_active[s].store(1, std::memory_order_relaxed);
        const float t[kSectionTensionFields] = {
            4, 0, 0.5f, 0.3f, 0.0f,  0, 0, 0, 0.0f,
            -1, -1, 0.7f,  -1, -1, 0.5f,  -1, -1, 0.3f,
            0.5f, 0.3f, 0.0f, static_cast<float>(spec[s]->arc),
        };
        for (int f = 0; f < kSectionTensionFields; f++)
            engine->pulsar_section_tension_data[s * kSectionTensionFields + f]
                .store(t[f], std::memory_order_relaxed);
    }
    engine->pulsar_arrangement_generation.store(1, std::memory_order_release);
}

static RampTrace run_ramp(const RampSpec& s0, const RampSpec& s1 = RampSpec{},
                          bool s1_terminal = false, int blocks = kBlocks,
                          int s1_edge = 0, bool arm_ending = false) {
    RampTrace tr;
    OrpheusEngine* engine = orpheus_engine_create(kSR);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(0.1f, std::memory_order_relaxed);   // stays under the limiter knee
    setup_fixture_baseline(engine);
    push_ramp_arrangement(engine, s0, s1, s1_terminal, s1_edge);
    engine->pulsar_energy.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_complexity.store(0.3f, std::memory_order_relaxed);
    engine->pulsar_space.store(0.4f, std::memory_order_relaxed);
    engine->pulsar_mood.store(0.5f, std::memory_order_relaxed);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    engine->clock_bpm.store(kBPM, std::memory_order_relaxed);
    engine->pulsar_bpm_override.store(0.0f, std::memory_order_relaxed);
    pin_pulsar_rngs(engine);
    trigger_vibe_load(engine);

    for (int b = 0; b < blocks; b++) {
        // The song-ending arm, sent once the vibe is under way (the load clears the latch).
        if (arm_ending && b == 50)
            engine->pulsar_arrangement_outro_request.store(1, std::memory_order_relaxed);
        unit_process_pulsar(&unit, engine, kBlock, kSR);
        const PulsarState* ps = engine->pulsar_state;
        const SectionState& ss = ps->section_state;
        tr.section.push_back(ss.current_section);
        tr.bar.push_back(ss.bars_total - ss.bars_remaining);
        tr.gain.push_back(ps->section_volume_gain);
        tr.tension.push_back(ps->tension_intensity);
        tr.rms.push_back(compute_rms(engine->pulsar_out_l, kBlock));
        tr.space_slot.push_back(
            engine->pulsar_track_active_space_slot[kSlotTrack].load(std::memory_order_relaxed));
        tr.spurt.push_back(ps->in_spurt ? 1 : 0);
    }
    orpheus_engine_destroy(engine);
    return tr;
}

// The last block of section 0's first visit that sits in [bar], or -1.
static int last_block_in(const RampTrace& tr, int bar) {
    int found = -1;
    for (size_t b = 0; b < tr.section.size(); b++) {
        if (tr.section[b] != 0) { if (found >= 0 || b > 10) break; continue; }
        if (tr.bar[b] == bar) found = static_cast<int>(b);
    }
    return found;
}

static int first_block_of_section1(const RampTrace& tr) {
    for (size_t b = 0; b < tr.section.size(); b++)
        if (tr.section[b] == 1) return static_cast<int>(b);
    return -1;
}

static bool test_unwritten_slots_leave_the_mix_alone() {
    printf("\n=== Test: unwritten ramp/volume slots keep the section volume at exactly 1 ===\n");
    const RampTrace tr = run_ramp(RampSpec{});
    bool ok = first_block_of_section1(tr) > 0;
    for (float g : tr.gain) ok &= (g == 1.0f);
    printf("  section 1 at block %d, all gains 1.0: %s\n", first_block_of_section1(tr), ok ? "yes" : "no");
    printf("Unwritten slots: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_section_volume_scales_the_mix() {
    printf("\n=== Test: volume 0.5 halves the mix in its section only ===\n");
    RampSpec half;
    half.volume = 0.5f;
    const RampTrace a = run_ramp(half);
    const RampTrace ref = run_ramp(RampSpec{});
    const int s1 = first_block_of_section1(a);
    double num = 0.0, den = 0.0, after_num = 0.0, after_den = 0.0;
    float peak = 0.0f;
    for (int b = 4; b < s1 - 1; b++) { num += a.rms[b]; den += ref.rms[b]; peak = std::fmax(peak, ref.rms[b]); }
    for (int b = s1 + 2; b < s1 + 200; b++) { after_num += a.rms[b]; after_den += ref.rms[b]; }
    const double ratio = den > 0.0 ? num / den : 0.0;
    const double after = after_den > 0.0 ? after_num / after_den : 0.0;
    printf("  section 0 ratio=%.4f (expect 0.5)  section 1 ratio=%.4f (expect 1)  ref rms peak=%.3f\n",
           ratio, after, peak);
    const bool ok = den > 0.0 && std::fabs(ratio - 0.5) < 1e-3 && std::fabs(after - 1.0) < 1e-3;
    printf("Section volume: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_volume_ramp_climbs_across_the_section() {
    printf("\n=== Test: volume 0.2 -> 1.0 climbs bar by bar and lands on 1 at the seam ===\n");
    RampSpec up;
    up.volume = 0.2f;
    up.ramp_volume = 1.0f;
    const RampTrace tr = run_ramp(up);
    bool ok = true;
    float prev = 0.0f;
    for (int bar = 0; bar < kBars; bar++) {
        const int b = last_block_in(tr, bar);
        const float expect = 0.2f + 0.8f * static_cast<float>(bar + 1) / kBars;
        const float g = (b >= 0) ? tr.gain[b] : -1.0f;
        printf("  bar %d end: gain=%.3f (expect ~%.3f)\n", bar, g, expect);
        ok &= b >= 0 && g > prev && std::fabs(g - expect) < 0.03f;
        prev = g;
    }
    // The flip lands inside a block whose gain was taken before it, so read the next one.
    const int s1 = first_block_of_section1(tr);
    ok &= s1 > 0 && tr.gain[s1 + 1] == 1.0f;
    printf("Volume ramp up: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_volume_ramp_falls_to_its_end() {
    printf("\n=== Test: volume 1.0 -> 0.25 fades over the section ===\n");
    RampSpec down;
    down.volume = 1.0f;
    down.ramp_volume = 0.25f;
    const RampTrace tr = run_ramp(down);
    const int first = last_block_in(tr, 0), last = last_block_in(tr, kBars - 1);
    const float g0 = first >= 0 ? tr.gain[first] : -1.0f, g3 = last >= 0 ? tr.gain[last] : -1.0f;
    printf("  bar 0 end gain=%.3f (expect ~0.81)  last bar end gain=%.3f (expect ~0.25)\n", g0, g3);
    const bool ok = std::fabs(g0 - 0.8125f) < 0.03f && std::fabs(g3 - 0.25f) < 0.03f;
    printf("Volume ramp down: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_energy_ramp_moves_the_slot() {
    printf("\n=== Test: energy 0.2 -> 1.0 starts on the SPACE slot and ends on EDM ===\n");
    RampSpec rise;
    rise.energy = 0.2f;
    rise.ramp_energy = 1.0f;
    const RampTrace tr = run_ramp(rise);
    const int early = last_block_in(tr, 0) / 2, late = last_block_in(tr, kBars - 1);
    const int flat_late = late >= 0 ? run_ramp([] { RampSpec f; f.energy = 0.2f; return f; }()).space_slot[late] : -1;
    printf("  bar 0: space_slot=%d  last bar: space_slot=%d  (no ramp, last bar: %d)\n",
           early >= 0 ? tr.space_slot[early] : -1, late >= 0 ? tr.space_slot[late] : -1, flat_late);
    const bool ok = early >= 0 && late >= 0 && tr.space_slot[early] == 1
                 && tr.space_slot[late] == 0 && flat_late == 1;
    printf("Energy ramp: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool check_arc(int arc, const float expect[kBars], const char* name) {
    RampSpec spec;
    spec.arc = arc;
    const RampTrace tr = run_ramp(spec);
    bool ok = true;
    for (int bar = 0; bar < kBars; bar++) {
        const int b = last_block_in(tr, bar);
        const float t = b >= 0 ? tr.tension[b] : -1.0f;
        printf("  %s bar %d: tension=%.3f (expect %.3f)\n", name, bar, t, expect[bar]);
        ok &= std::fabs(t - expect[bar]) < 1e-4f;
    }
    // An arc at 1 is the section's full level, not a climax: no forced spurt in it or
    // the bars that follow (spurt_chance is 0 here).
    const int s1 = first_block_of_section1(tr);
    int spurts = 0;
    for (int b = 0; b < s1 + 200 && b < static_cast<int>(tr.spurt.size()); b++) spurts += tr.spurt[b];
    printf("  %s spurt blocks through section 1's start: %d (expect 0)\n", name, spurts);
    ok &= s1 > 0 && spurts == 0;
    return ok;
}

static bool test_tension_arc_rise_and_fall() {
    printf("\n=== Test: arc RISE climbs 0 -> 1 over the section's bars, FALL the reverse ===\n");
    const float rise[kBars] = {0.0f, 1.0f / 3.0f, 2.0f / 3.0f, 1.0f};
    const float fall[kBars] = {1.0f, 2.0f / 3.0f, 1.0f / 3.0f, 0.0f};
    const bool ok = check_arc(kTensionArcRise, rise, "RISE") && check_arc(kTensionArcFall, fall, "FALL");
    printf("Tension arc: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_terminal_outro_loop_holds_its_end() {
    printf("\n=== Test: a terminal outro looping into itself holds its ramp and arc ends ===\n");
    RampSpec outro;
    outro.volume = 1.0f;
    outro.ramp_volume = 0.25f;
    outro.arc = kTensionArcFall;
    const RampTrace tr = run_ramp(RampSpec{}, outro, true, 1300);
    // Section 1's second pass starts where its bar count drops back to 0.
    int second = -1;
    for (size_t b = 1; b < tr.section.size(); b++)
        if (tr.section[b] == 1 && tr.section[b - 1] == 1 && tr.bar[b] < tr.bar[b - 1]) { second = static_cast<int>(b); break; }
    bool ok = second > 0;
    float gmin = 2.0f, gmax = 0.0f, tmax = 0.0f;
    for (int b = second + 2; ok && b < second + 300 && b < static_cast<int>(tr.gain.size()); b++) {
        gmin = std::fmin(gmin, tr.gain[b]); gmax = std::fmax(gmax, tr.gain[b]);
        tmax = std::fmax(tmax, tr.tension[b]);
    }
    printf("  second pass at block %d: gain %.3f..%.3f (expect 0.25) tension max %.3f (expect 0)\n",
           second, gmin, gmax, tmax);
    ok &= std::fabs(gmin - 0.25f) < 1e-4f && std::fabs(gmax - 0.25f) < 1e-4f && tmax == 0.0f;
    printf("Outro loop hold: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_armed_ending_without_outro_keeps_restarting_a_loop() {
    printf("\n=== Test: an armed ending on a vibe with no outro does not pin a self-looping ramp ===\n");
    RampSpec groove;
    groove.volume = 1.0f;
    groove.ramp_volume = 0.25f;
    const RampTrace tr = run_ramp(RampSpec{}, groove, false, 1300, /*s1_edge=*/1, /*arm_ending=*/true);
    int second = -1;
    for (size_t b = 1; b < tr.section.size(); b++)
        if (tr.section[b] == 1 && tr.section[b - 1] == 1 && tr.bar[b] < tr.bar[b - 1]) { second = static_cast<int>(b); break; }
    const float restart = second > 0 ? tr.gain[second + 2] : -1.0f;
    printf("  second pass at block %d: gain just after the restart %.3f (expect ~1)\n", second, restart);
    const bool ok = second > 0 && restart > 0.95f;
    printf("Armed loop restarts: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_volume_envelope_follows_its_points() {
    printf("\n=== Test: a volume envelope runs straight lines between its points, then holds ===\n");
    RampSpec fade;
    fade.envelope = {{0.0f, 1.0f}, {2.0f, 0.4f}, {3.0f, 0.15f}, {3.5f, 0.0f}};
    const RampTrace tr = run_ramp(fade);
    // Last block of each bar sits ~0.01 bar before its end.
    const float expect[kBars] = {0.7f, 0.4f, 0.15f, 0.0f};
    bool ok = true;
    for (int bar = 0; bar < kBars; bar++) {
        const int b = last_block_in(tr, bar);
        const float g = b >= 0 ? tr.gain[b] : -1.0f;
        printf("  bar %d end: gain=%.3f (expect ~%.3f)\n", bar, g, expect[bar]);
        ok &= b >= 0 && std::fabs(g - expect[bar]) < 0.02f;
    }
    // From the middle of the last bar to the seam it is silent.
    int silent = 0, total = 0;
    const int last = last_block_in(tr, kBars - 1);
    for (int b = last - 40; b <= last && b >= 0; b++) { total++; silent += (tr.gain[b] == 0.0f && tr.rms[b] == 0.0f); }
    printf("  silent blocks in the last bar's second half: %d/%d\n", silent, total);
    ok &= total > 0 && silent == total;
    printf("Volume envelope: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_terminal_outro_envelope_holds_its_last_point() {
    printf("\n=== Test: an envelope holds its first level before its first point and its last through the loop ===\n");
    RampSpec outro;
    outro.envelope = {{1.0f, 1.0f}, {3.0f, 0.0f}};
    const RampTrace tr = run_ramp(RampSpec{}, outro, true, 1300);
    int first = -1, second = -1;
    for (size_t b = 1; b < tr.section.size(); b++) {
        if (first < 0 && tr.section[b] == 1 && tr.section[b - 1] == 0) first = static_cast<int>(b);
        if (tr.section[b] == 1 && tr.section[b - 1] == 1 && tr.bar[b] < tr.bar[b - 1]) { second = static_cast<int>(b); break; }
    }
    // Section 1's first bar sits before the first point (bar 1): full level.
    float early = 0.0f;
    for (int b = first + 2; first > 0 && b < first + 80; b++) early = std::fmax(early, std::fabs(tr.gain[b] - 1.0f));
    float held = 0.0f;
    for (int b = second + 1; second > 0 && b < second + 300 && b < static_cast<int>(tr.gain.size()); b++)
        held = std::fmax(held, tr.gain[b]);
    printf("  first bar: max |gain-1|=%.4f (expect 0)  second pass: max gain %.4f (expect 0)\n", early, held);
    const bool ok = first > 0 && second > 0 && early < 1e-6f && held == 0.0f;
    printf("Envelope ends: %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

bool run_pulsar_section_ramp_tests() {
    printf("\n=== Pulsar Section Ramp Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    if (test_unwritten_slots_leave_the_mix_alone()) suite_pass++; else suite_fail++;
    if (test_section_volume_scales_the_mix()) suite_pass++; else suite_fail++;
    if (test_volume_ramp_climbs_across_the_section()) suite_pass++; else suite_fail++;
    if (test_volume_ramp_falls_to_its_end()) suite_pass++; else suite_fail++;
    if (test_energy_ramp_moves_the_slot()) suite_pass++; else suite_fail++;
    if (test_tension_arc_rise_and_fall()) suite_pass++; else suite_fail++;
    if (test_terminal_outro_loop_holds_its_end()) suite_pass++; else suite_fail++;
    if (test_armed_ending_without_outro_keeps_restarting_a_loop()) suite_pass++; else suite_fail++;
    if (test_volume_envelope_follows_its_points()) suite_pass++; else suite_fail++;
    if (test_terminal_outro_envelope_holds_its_last_point()) suite_pass++; else suite_fail++;
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
