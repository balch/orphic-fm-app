#include "test_harness.h"
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/orpheus_graph.h"
#include "../src/pulsar_section.h"
#include "../src/pulsar_band_solo.h"
#include "../src/pulsar_rng.h"
#include "../src/pulsar_handoff.h"
#include "stmlib/utils/random.h"  // pin the global noise RNG for reproducible integration tests
#include <cstdio>
#include <cmath>
#include <cstring>

static bool approx(float a, float b) { return std::fabs(a - b) < 1e-4f; }

// ── Test helper: create a 3-section arrangement ──

static ArrangementParams make_test_arrangement() {
    ArrangementParams arr = {};
    arr.active = true;
    arr.section_count = 3;
    arr.intro_index = -1;
    arr.outro_index = -1;

    // Section 0: bars 4-8, transitions to 1(0.6) and 2(0.4), recencyDecay=0.5,
    // both edges hard-cut (transition_bars=0).
    arr.sections[0].bars_min = 4;
    arr.sections[0].bars_max = 8;
    arr.sections[0].recency_decay = 0.5f;
    arr.sections[0].transitions[0].target_index = 1;
    arr.sections[0].transitions[0].weight = 0.6f;
    arr.sections[0].transitions[0].transition_bars = 0;
    arr.sections[0].transitions[1].target_index = 2;
    arr.sections[0].transitions[1].weight = 0.4f;
    arr.sections[0].transitions[1].transition_bars = 0;
    arr.sections[0].transition_count = 2;

    // Section 1: bars 4-4, transitions to 0(0.8) and 2(0.2), recencyDecay=0.5.
    arr.sections[1].bars_min = 4;
    arr.sections[1].bars_max = 4;
    arr.sections[1].recency_decay = 0.5f;
    arr.sections[1].transitions[0].target_index = 0;
    arr.sections[1].transitions[0].weight = 0.8f;
    arr.sections[1].transitions[0].transition_bars = 0;
    arr.sections[1].transitions[1].target_index = 2;
    arr.sections[1].transitions[1].weight = 0.2f;
    arr.sections[1].transitions[1].transition_bars = 0;
    arr.sections[1].transition_count = 2;

    // Section 2: bars 4-4, transitions to 0(1.0), recencyDecay=0.5.
    arr.sections[2].bars_min = 4;
    arr.sections[2].bars_max = 4;
    arr.sections[2].recency_decay = 0.5f;
    arr.sections[2].transitions[0].target_index = 0;
    arr.sections[2].transitions[0].weight = 1.0f;
    arr.sections[2].transitions[0].transition_bars = 0;
    arr.sections[2].transition_count = 1;

    return arr;
}

// ── Unit tests ──

static bool test_section_init() {
    printf("\n=== Test: Section init without intro starts in valid section ===\n");
    ArrangementParams arr = make_test_arrangement();
    SectionState state;
    uint32_t seed = 12345;
    init_section_state(state, arr, seed);

    bool ok = state.current_section >= 0
           && state.current_section < arr.section_count
           && state.bars_remaining > 0
           && state.intro_done == true;
    printf("  current_section=%d, bars_remaining=%d, intro_done=%s -- %s\n",
           state.current_section, state.bars_remaining,
           state.intro_done ? "true" : "false",
           ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_section_init_with_intro() {
    printf("\n=== Test: Section init with intro_index=0 starts at section 0, intro_done=false ===\n");
    ArrangementParams arr = make_test_arrangement();
    arr.intro_index = 0;  // Set intro to section 0
    SectionState state;
    uint32_t seed = 12345;
    init_section_state(state, arr, seed);

    bool ok = state.current_section == 0
           && state.intro_done == false;
    printf("  current_section=%d, intro_done=%s -- %s\n",
           state.current_section, state.intro_done ? "true" : "false",
           ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_section_advance_countdown() {
    printf("\n=== Test: Section advance decrements bars_remaining without changing section ===\n");
    ArrangementParams arr = make_test_arrangement();
    SectionState state;
    uint32_t seed = 12345;
    init_section_state(state, arr, seed);

    int initial_section = state.current_section;
    int initial_bars = state.bars_remaining;

    // Advance once
    bool changed = advance_section(state, arr, seed);

    bool ok = state.current_section == initial_section
           && state.bars_remaining == initial_bars - 1
           && changed == false;
    printf("  section unchanged: %s, bars decremented: %d -> %d, changed=%s -- %s\n",
           state.current_section == initial_section ? "true" : "false",
           initial_bars, state.bars_remaining,
           changed ? "true" : "false",
           ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_section_transitions_eventually() {
    printf("\n=== Test: After enough advances, section changes ===\n");
    ArrangementParams arr = make_test_arrangement();
    SectionState state;
    uint32_t seed = 12345;
    init_section_state(state, arr, seed);

    int initial_section = state.current_section;

    // Advance enough times to trigger a transition
    bool transitioned = false;
    for (int i = 0; i < 100; i++) {
        if (advance_section(state, arr, seed)) {
            transitioned = true;
            break;
        }
    }

    // After transition, section should be different
    bool ok = transitioned && state.current_section != initial_section;
    printf("  transitioned=%s, initial=%d, final=%d -- %s\n",
           transitioned ? "true" : "false",
           initial_section, state.current_section,
           ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_section_recency_prevents_immediate_repeat() {
    printf("\n=== Test: Low recencyDecay prevents immediate repeat (300 transitions) ===\n");
    ArrangementParams arr = make_test_arrangement();
    // Set very low recency decay to strongly penalize recent visits
    arr.sections[0].recency_decay = 0.1f;
    arr.sections[1].recency_decay = 0.1f;
    arr.sections[2].recency_decay = 0.1f;

    SectionState state;
    uint32_t seed = 54321;
    init_section_state(state, arr, seed);

    // Count visits to each section
    int visits[3] = {0, 0, 0};
    visits[state.current_section]++;

    // Perform 300 transitions by advancing many times
    // Each section is 4-8 bars, so with ~6 bar average, 300 transitions = ~1800 advances
    for (int i = 0; i < 2000; i++) {
        bool changed = advance_section(state, arr, seed);
        if (changed && state.current_section >= 0 && state.current_section < 3) {
            visits[state.current_section]++;
        }
    }

    // With 3 sections and many transitions, each should get reasonable visits.
    // Check that each section gets at least some visits (> 10%)
    float visit_pct[3];
    int total_visits = 0;
    for (int i = 0; i < 3; i++) {
        total_visits += visits[i];
    }
    for (int i = 0; i < 3; i++) {
        visit_pct[i] = (total_visits > 0) ? visits[i] / (float)total_visits : 0.0f;
    }
    bool ok = visit_pct[0] > 0.1f && visit_pct[1] > 0.1f && visit_pct[2] > 0.1f;
    printf("  total transitions: %d, visits: sec0=%.1f%%, sec1=%.1f%%, sec2=%.1f%% -- %s\n",
           total_visits, visit_pct[0] * 100.0f, visit_pct[1] * 100.0f, visit_pct[2] * 100.0f,
           ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_section_transition_ramp() {
    printf("\n=== Test: pre-roll ramp occupies last N bars of source section ===\n");

    // 2-section A->B arrangement. Section 0 is 4 bars; the edge to section 1
    // has transition_bars = 2, so the LAST 2 of those 4 bars are the ramp zone.
    ArrangementParams arr = {};
    arr.active = true;
    arr.section_count = 2;
    arr.intro_index = -1;
    arr.outro_index = -1;

    arr.sections[0].bars_min = 4;
    arr.sections[0].bars_max = 4;
    arr.sections[0].recency_decay = 0.5f;
    arr.sections[0].transitions[0].target_index = 1;
    arr.sections[0].transitions[0].weight = 1.0f;
    arr.sections[0].transitions[0].transition_bars = 2;
    arr.sections[0].transition_count = 1;

    arr.sections[1].bars_min = 4;
    arr.sections[1].bars_max = 4;
    arr.sections[1].recency_decay = 0.5f;
    arr.sections[1].transitions[0].target_index = 0;
    arr.sections[1].transitions[0].weight = 1.0f;
    arr.sections[1].transitions[0].transition_bars = 0;  // hard cut back
    arr.sections[1].transition_count = 1;

    SectionState state;
    uint32_t seed = 11111;
    init_section_state(state, arr, seed);

    // Force section 0 active deterministically.
    state.current_section = 0;
    state.bars_remaining = 4;
    plan_next_section(state, arr, seed);

    // Bar 1: bars_remaining 4 -> 3. Outside ramp zone (3 >= 2).
    advance_section(state, arr, seed);
    bool ok_bar1 = state.transition_target < 0 && state.bars_remaining == 3;

    // Bar 2: bars_remaining 3 -> 2. Still outside (2 < 2 is false).
    advance_section(state, arr, seed);
    bool ok_bar2 = state.transition_target < 0 && state.bars_remaining == 2;

    // Bar 3: bars_remaining 2 -> 1. Ramp starts. progress = (2-1)/2 = 0.5.
    bool changed3 = advance_section(state, arr, seed);
    float progress_after_bar3 = state.transition_progress;
    bool ok_bar3 = state.transition_target == 1
                && std::fabs(progress_after_bar3 - 0.5f) < 0.001f
                && !changed3;

    // Bar 4: bars_remaining 1 -> 0. Ramp completes mid-update (would be 1.0),
    // then hard-flip to section 1. After flip: progress reset, transition cleared.
    bool changed4 = advance_section(state, arr, seed);
    bool ok_bar4 = changed4
                && state.current_section == 1
                && state.transition_target == -1
                && state.transition_progress == 0.0f;

    printf("  bar 1 (rem=3, no ramp): %s\n", ok_bar1 ? "PASS" : "FAIL");
    printf("  bar 2 (rem=2, no ramp): %s\n", ok_bar2 ? "PASS" : "FAIL");
    printf("  bar 3 (rem=1, ramp progress=%.2f): %s\n",
           progress_after_bar3, ok_bar3 ? "PASS" : "FAIL");
    printf("  bar 4 (rem=0, flip to section 1): %s\n", ok_bar4 ? "PASS" : "FAIL");

    return ok_bar1 && ok_bar2 && ok_bar3 && ok_bar4;
}

static bool test_section_macro_interpolation() {
    printf("\n=== Test: section_macro_value interpolates correctly ===\n");

    // Test at progress=0.0 (no override, should return base)
    float v1 = section_macro_value(0.5f, 0.3f, 0.8f, 0.0f);
    bool ok1 = std::fabs(v1 - 0.3f) < 0.001f;
    printf("  at progress=0.0: %.2f (expected 0.30) -- %s\n", v1, ok1 ? "PASS" : "FAIL");

    // Test at progress=0.5 (midway)
    float v2 = section_macro_value(0.5f, 0.3f, 0.8f, 0.5f);
    float expected2 = 0.3f + (0.8f - 0.3f) * 0.5f;  // 0.55
    bool ok2 = std::fabs(v2 - expected2) < 0.001f;
    printf("  at progress=0.5: %.2f (expected %.2f) -- %s\n", v2, expected2, ok2 ? "PASS" : "FAIL");

    // Test at progress=1.0 (complete)
    float v3 = section_macro_value(0.5f, 0.3f, 0.8f, 1.0f);
    bool ok3 = std::fabs(v3 - 0.8f) < 0.001f;
    printf("  at progress=1.0: %.2f (expected 0.80) -- %s\n", v3, ok3 ? "PASS" : "FAIL");

    // Test with no override (both -1)
    float v4 = section_macro_value(0.5f, -1.0f, -1.0f, 0.5f);
    bool ok4 = std::fabs(v4 - 0.5f) < 0.001f;
    printf("  no override at progress=0.5: %.2f (expected 0.50) -- %s\n", v4, ok4 ? "PASS" : "FAIL");

    return ok1 && ok2 && ok3 && ok4;
}

// ── Integration tests that drive the full unit_process_pulsar path ──
//
// The existing tests above are pure state-machine unit tests that call
// init_section_state / advance_section directly.  The two tests below verify
// the runtime hook that re-inits the chord progression on every section_changed
// event — logic that only fires inside unit_process_pulsar.

// Push a two-section arrangement where section 0 deterministically transitions
// to section 1 after `bars_per_section` bars.
static void push_two_section_ab_arrangement(OrpheusEngine* engine,
                                            int bars_per_section) {
    engine->pulsar_arrangement_active.store(1, std::memory_order_relaxed);
    engine->pulsar_arrangement_section_count.store(2, std::memory_order_relaxed);
    engine->pulsar_arrangement_intro_index.store(-1, std::memory_order_relaxed);
    engine->pulsar_arrangement_outro_index.store(-1, std::memory_order_relaxed);

    constexpr int kSectionStride = kSectionDataFields;
    float section_data[8 * kSectionStride] = {};
    for (int s = 0; s < 8; s++) {
        // Default comping overrides to -1
        section_data[s * kSectionStride + 18] = -1;
        section_data[s * kSectionStride + 19] = -1;
        section_data[s * kSectionStride + 20] = -1;
        // Disable solo for all sections (slot 9)
        section_data[s * kSectionStride + 9]  = 0;
        // No macro overrides
        section_data[s * kSectionStride + 5]  = -1;
        section_data[s * kSectionStride + 6]  = -1;
        section_data[s * kSectionStride + 7]  = -1;
        section_data[s * kSectionStride + 8]  = -1;
    }
    // Section 0: bars_min = bars_max = bars_per_section, one transition to section 1.
    section_data[0] = (float)bars_per_section; // bars_min
    section_data[1] = (float)bars_per_section; // bars_max
    section_data[2] = 1;                       // bar_step
    section_data[3] = 0.8f;                    // recency_decay
    section_data[4] = 1;                       // transition_count
    // Section 1: bars_min = bars_max = bars_per_section, one transition back to section 0.
    int s1 = kSectionStride;
    section_data[s1 + 0] = (float)bars_per_section;
    section_data[s1 + 1] = (float)bars_per_section;
    section_data[s1 + 2] = 1;                  // bar_step
    section_data[s1 + 3] = 0.8f;
    section_data[s1 + 4] = 1;

    for (int i = 0; i < 8 * kSectionStride; i++)
        engine->pulsar_section_data[i].store(section_data[i], std::memory_order_relaxed);

    // Transitions: s0 -> {1:1.0}, s1 -> {0:1.0}.
    // Per-edge stride: 3 floats (target, weight, transition_bars). All hard-cut.
    float trans[8 * 8 * 3] = {};
    trans[0]  = 1; trans[1]  = 1.0f; trans[2]  = 0;  // s0 edge 0 -> section 1
    trans[24] = 0; trans[25] = 1.0f; trans[26] = 0;  // s1 edge 0 -> section 0 (s1 base = 8*3 = 24)
    for (int i = 0; i < 8 * 8 * 3; i++)
        engine->pulsar_section_transitions[i].store(trans[i], std::memory_order_relaxed);

    // Clear all per-section overrides by default; tests set them explicitly after.
    for (int s = 0; s < 8; s++) {
        engine->pulsar_section_progression_length[s].store(0, std::memory_order_relaxed);
        engine->pulsar_section_chords_per_bar[s].store(0, std::memory_order_relaxed);
        engine->pulsar_section_tension_active[s].store(0, std::memory_order_relaxed);
        for (int i = 0; i < 12; i++) {  // kMaxProgressionLength
            engine->pulsar_section_progression_degrees[s * 12 + i].store(0, std::memory_order_relaxed);
        }
    }

    // Clear solo/ducking/markov to defaults
    for (int i = 0; i < 8 * 15; i++)
        engine->pulsar_track_solo_behavior[i].store(0.0f, std::memory_order_relaxed);
    for (int i = 0; i < kNumPulsarTracks * kTrackDuckingFields; i++)
        engine->pulsar_track_ducking[i].store(0.0f, std::memory_order_relaxed);
    for (int i = 0; i < 8 * 15; i++)
        engine->pulsar_track_solo_markov[i].store(0.0f, std::memory_order_relaxed);

    engine->pulsar_arrangement_generation.store(1, std::memory_order_release);
}

// ── The OPENING section's overrides apply at load ──────────────────────────
// The first section is entered via init_section_state, which fires no
// advance_section — so the section-change handler never ran for it and a vibe
// whose intro declared macroOverrides played its whole intro at vibe-base
// macros. section_total_steps likewise still held the PREVIOUS vibe's value,
// which is what the void anomaly's end-aligned arc is measured against.
static bool test_opening_section_overrides_apply_at_load() {
    printf("\n=== Test: the opening section's overrides apply at load ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    push_two_section_ab_arrangement(engine, 4);

    // Pin section 0 as the intro. The fixture leaves intro_index at -1, and
    // init_section_state then DRAWS the opening section (pattern_rand01), so
    // without this the assertion below is a coin flip on which section opens.
    engine->pulsar_arrangement_intro_index.store(0, std::memory_order_relaxed);

    // Slot 5 of section 0 is the energy macro override (a MULTIPLIER; -1 means
    // inactive). Written after the fixture, which defaults it to -1.
    constexpr float kEnergyOverride = 0.5f;
    engine->pulsar_section_data[5].store(kEnergyOverride, std::memory_order_relaxed);

    trigger_vibe_load(engine);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);
    unit_process_pulsar(&unit, engine, 64, 48000.0f);

    PulsarState* ps = engine->pulsar_state;
    bool ok = true;
    if (!ps) {
        printf("  FAIL: pulsar_state was not allocated\n");
        orpheus_engine_destroy(engine);
        return false;
    }

    const float got = ps->section_state.target_energy;
    if (got != kEnergyOverride) {
        printf("  FAIL: target_energy = %.3f (expected %.3f) [current_section=%d, "
               "sec0.energy=%.3f] — the intro is playing at vibe-base macros\n",
               got, kEnergyOverride, ps->section_state.current_section,
               ps->arrangement.sections[0].macro_overrides.energy);
        ok = false;
    } else {
        printf("  target_energy = %.3f at load -- PASS\n", got);
    }

    // section_total_steps is what the void arc is end-aligned against; before
    // this it held whatever the PREVIOUS vibe's last section left behind.
    const float want_steps =
        static_cast<float>(ps->section_state.bars_remaining) *
        static_cast<float>(ps->tracks[0].step_count);
    if (ps->section_total_steps != want_steps || want_steps <= 0.0f) {
        printf("  FAIL: section_total_steps = %.1f (expected %.1f)\n",
               ps->section_total_steps, want_steps);
        ok = false;
    } else {
        printf("  section_total_steps = %.1f (bars_remaining %d x step_count %d) -- PASS\n",
               ps->section_total_steps, ps->section_state.bars_remaining,
               ps->tracks[0].step_count);
    }

    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_section_progression_override_applied() {
    printf("\n=== Test: Section progression override activates on section entry ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);

    // Two-section arrangement, 1 bar each so transitions happen quickly.
    push_two_section_ab_arrangement(engine, 1);

    // Section 0: no override.  Section 1: customProgression = [3, 4, 0].
    engine->pulsar_section_progression_length[1].store(3, std::memory_order_relaxed);
    engine->pulsar_section_progression_degrees[1 * 12 + 0].store(3, std::memory_order_relaxed);
    engine->pulsar_section_progression_degrees[1 * 12 + 1].store(4, std::memory_order_relaxed);
    engine->pulsar_section_progression_degrees[1 * 12 + 2].store(0, std::memory_order_relaxed);

    trigger_vibe_load(engine);
    // At 240 BPM with 1-bar sections and 512-sample blocks at 48 kHz, one bar ≈ 47 blocks.
    // 800-iteration cap gives ~17 bars of slack before we consider the test stuck.
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);

    // Drive audio until we see section 1 (or bail out).
    bool reached_section_1 = false;
    for (int i = 0; i < 800 && !reached_section_1; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        PulsarState* ps = engine->pulsar_state;
        if (ps && ps->section_state.current_section == 1) {
            reached_section_1 = true;
        }
    }

    bool ok = reached_section_1;
    if (!ok) {
        printf("  FAIL: never reached section 1\n");
    } else {
        PulsarState* ps = engine->pulsar_state;
        int plen = ps->chord_state.progression_length;
        int p0 = (plen > 0) ? ps->chord_state.progression[0] : -1;
        int p1 = (plen > 1) ? ps->chord_state.progression[1] : -1;
        int p2 = (plen > 2) ? ps->chord_state.progression[2] : -1;
        int cidx = ps->chord_state.chord_index;
        bool plen_ok = (plen == 3);
        bool seq_ok  = (p0 == 3 && p1 == 4 && p2 == 0);
        bool idx_ok  = (cidx == 0);
        ok = plen_ok && seq_ok && idx_ok;
        printf("  progression_length=%d (expected 3) -- %s\n", plen, plen_ok ? "OK" : "FAIL");
        printf("  progression[0..3]={%d, %d, %d} (expected {3, 4, 0}) -- %s\n",
               p0, p1, p2, seq_ok ? "OK" : "FAIL");
        printf("  chord_index=%d (expected 0) -- %s\n", cidx, idx_ok ? "OK" : "FAIL");
    }
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_section_progression_inheritance_resets_index() {
    printf("\n=== Test: Section without override inherits vibe progression after section with override ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);

    // Vibe-level progression: [0, 3, 4, 5] (I - IV - V - VI).
    engine->pulsar_custom_progression_active.store(1, std::memory_order_relaxed);
    engine->pulsar_custom_progression_length.store(4, std::memory_order_relaxed);
    engine->pulsar_custom_progression[0].store(0, std::memory_order_relaxed);
    engine->pulsar_custom_progression[1].store(3, std::memory_order_relaxed);
    engine->pulsar_custom_progression[2].store(4, std::memory_order_relaxed);
    engine->pulsar_custom_progression[3].store(5, std::memory_order_relaxed);

    // Two-section ping-pong arrangement, 1 bar each.
    push_two_section_ab_arrangement(engine, 1);

    // Section 0: no override (should use vibe progression).
    // Section 1: override to [2, 1] (distinct length and contents from vibe progression).
    engine->pulsar_section_progression_length[1].store(2, std::memory_order_relaxed);
    engine->pulsar_section_progression_degrees[1 * 12 + 0].store(2, std::memory_order_relaxed);
    engine->pulsar_section_progression_degrees[1 * 12 + 1].store(1, std::memory_order_relaxed);

    trigger_vibe_load(engine);
    // At 240 BPM with 1-bar sections and 512-sample blocks at 48 kHz, one bar ≈ 47 blocks.
    // 3000-iteration cap gives ~63 bars of slack to observe the 0 -> 1 -> 0 ping-pong.
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);

    // Track the full section history: we need to see 0 -> 1 -> 0 and verify that on
    // the second entry to section 0, the vibe progression is RE-INSTALLED (length=4,
    // sequence={0,3,4,5}) and chord_index is reset to 0.  Without the fix, once
    // section 1's override shrinks the progression to length=2 with contents {2,1},
    // returning to section 0 would leave it there (no re-init), so this test fails.
    int last_section = -1;
    bool entered_section_1 = false;
    bool section_1_override_observed = false;
    bool returned_to_section_0 = false;
    int snap_len = -1, snap_cidx = -1;
    int snap_prog[4] = {-1, -1, -1, -1};

    for (int i = 0; i < 3000; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        PulsarState* ps = engine->pulsar_state;
        if (!ps) continue;
        int cur = ps->section_state.current_section;

        // On the tick we observe entry to section 1, confirm the override took hold
        if (last_section == 0 && cur == 1) {
            entered_section_1 = true;
            if (ps->chord_state.progression_length == 2
                && ps->chord_state.progression[0] == 2
                && ps->chord_state.progression[1] == 1) {
                section_1_override_observed = true;
            }
        }

        // On the tick we observe return to section 0, snapshot state
        if (last_section == 1 && cur == 0 && entered_section_1) {
            snap_len = ps->chord_state.progression_length;
            snap_cidx = ps->chord_state.chord_index;
            for (int k = 0; k < snap_len && k < 4; k++)
                snap_prog[k] = ps->chord_state.progression[k];
            returned_to_section_0 = true;
            break;
        }
        last_section = cur;
    }

    bool entered_ok = entered_section_1 && section_1_override_observed;
    bool return_ok  = returned_to_section_0;
    bool plen_ok    = (snap_len == 4);
    bool seq_ok     = (snap_prog[0] == 0
                    && snap_prog[1] == 3
                    && snap_prog[2] == 4
                    && snap_prog[3] == 5);
    bool idx_ok     = (snap_cidx == 0);

    bool ok = entered_ok && return_ok && plen_ok && seq_ok && idx_ok;

    if (!entered_ok) {
        printf("  FAIL: did not observe section 1 override taking effect\n");
    } else {
        printf("  section 1 override observed (length=2, {2,1}) -- OK\n");
    }
    if (!return_ok) {
        printf("  FAIL: never observed return to section 0\n");
    } else {
        printf("  on return to section 0:\n");
        printf("    progression_length=%d (expected 4) -- %s\n", snap_len, plen_ok ? "OK" : "FAIL");
        printf("    progression[0..4]={%d, %d, %d, %d} (expected {0, 3, 4, 5}) -- %s\n",
               snap_prog[0], snap_prog[1], snap_prog[2], snap_prog[3],
               seq_ok ? "OK" : "FAIL");
        printf("    chord_index=%d (expected 0) -- %s\n", snap_cidx, idx_ok ? "OK" : "FAIL");
    }
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return ok;
}

// ── Tension phase reset tests (Task 7) ──
//
// Verify that on EVERY section boundary:
//  - tension_intensity and tension_evo_smooth are unconditionally zeroed
//  - when a section has no override, the vibe-level tension profile is
//    re-loaded so a prior section's override does not leak forward.

static bool test_tension_phase_resets_without_override() {
    printf("\n=== Test: Tension phase (intensity + evo_smooth) resets on section change without override ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);

    // Vibe-level tension: inner_bars=4, outer_bars=0, volume=0.3
    // so tension_intensity rises from 0 -> 0.75 over 4 bars then wraps.
    engine->pulsar_tension_inner_bars.store(4, std::memory_order_relaxed);
    engine->pulsar_tension_outer_bars.store(0, std::memory_order_relaxed);
    engine->pulsar_tension_volume.store(0.3f, std::memory_order_relaxed);
    // Give evo a non-trivial attack point so evo_smooth can rise.
    engine->pulsar_tension_evo_attack_point.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_tension_evo_release_speed.store(0.3f, std::memory_order_relaxed);

    // 2-section ping-pong, 3 bars each so tension_intensity has time to rise.
    push_two_section_ab_arrangement(engine, 3);

    trigger_vibe_load(engine);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);

    // Track section history: we want to observe 0 -> 1 and sample state at the
    // moment we first see section 1 (end of the block where section_changed fired).
    int last_section = -1;
    bool entered_section_1 = false;
    float saw_intensity_rise = 0.0f;  // max observed while still in section 0
    float intensity_at_change = -1.0f;
    float evo_smooth_at_change = -1.0f;

    for (int i = 0; i < 2400; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        PulsarState* ps = engine->pulsar_state;
        if (!ps) continue;
        int cur = ps->section_state.current_section;

        if (cur == 0) {
            if (ps->tension_intensity > saw_intensity_rise) {
                saw_intensity_rise = ps->tension_intensity;
            }
        }

        if (last_section == 0 && cur == 1 && !entered_section_1) {
            entered_section_1 = true;
            intensity_at_change = ps->tension_intensity;
            evo_smooth_at_change = ps->tension_evo_smooth;
            break;
        }
        last_section = cur;
    }

    bool entered_ok = entered_section_1;
    bool rose_ok    = saw_intensity_rise > 0.1f;
    bool intensity_ok = entered_ok && intensity_at_change <= 1e-6f;
    bool evo_ok       = entered_ok && evo_smooth_at_change <= 1e-6f;

    if (!entered_ok) {
        printf("  FAIL: never observed transition to section 1\n");
    } else {
        printf("  rose to %.4f while in section 0 -- %s\n",
               saw_intensity_rise, rose_ok ? "OK" : "FAIL");
        printf("  tension_intensity at section change = %.6g (expected 0) -- %s\n",
               intensity_at_change, intensity_ok ? "OK" : "FAIL");
        printf("  tension_evo_smooth at section change = %.6g (expected 0) -- %s\n",
               evo_smooth_at_change, evo_ok ? "OK" : "FAIL");
    }

    bool ok = entered_ok && rose_ok && intensity_ok && evo_ok;
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_tension_override_then_inherit() {
    printf("\n=== Test: Section tension override applied, then vibe tension reverts on exit ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);

    // Vibe-level tension: distinctive volume = 0.3
    engine->pulsar_tension_inner_bars.store(4, std::memory_order_relaxed);
    engine->pulsar_tension_outer_bars.store(0, std::memory_order_relaxed);
    engine->pulsar_tension_volume.store(0.3f, std::memory_order_relaxed);

    // 2-section ping-pong, 1 bar each so transitions happen quickly.
    push_two_section_ab_arrangement(engine, 1);

    // Section 1: override with distinctive volume = 0.9
    // Field layout (matches engine.h): 0=inner_bars, 1=outer_bars, 2=outer_depth,
    //   3=volume, 4=timing, 5=octave_shift, 6=key_shift, 7=half_lick,
    //   8=chromatic_passing, 9..11=evo_timbre_low/high/prob, 12..14=evo_morph_*,
    //   15..17=evo_harm_*, 18=evo_attack_point, 19=evo_release_speed, 20=spurt_chance
    engine->pulsar_section_tension_active[1].store(1, std::memory_order_relaxed);
    // Tension's own fixed stride, NOT pulsar_section_data's kSectionDataFields, which
    // has been widened repeatedly (weather, then the pinned lick) -- see pulsar_limits.h.
    constexpr int kSectionStride = 21;
    const int tb = 1 * kSectionStride;
    // Valid, non-silly defaults so the override struct isn't garbage.
    engine->pulsar_section_tension_data[tb + 0].store(4.0f, std::memory_order_relaxed);  // inner_bars
    engine->pulsar_section_tension_data[tb + 1].store(0.0f, std::memory_order_relaxed);  // outer_bars
    engine->pulsar_section_tension_data[tb + 2].store(0.5f, std::memory_order_relaxed);  // outer_depth
    engine->pulsar_section_tension_data[tb + 3].store(0.9f, std::memory_order_relaxed);  // volume <-- distinctive
    engine->pulsar_section_tension_data[tb + 4].store(0.2f, std::memory_order_relaxed);  // timing
    engine->pulsar_section_tension_data[tb + 5].store(0.0f, std::memory_order_relaxed);  // octave_shift
    engine->pulsar_section_tension_data[tb + 6].store(0.0f, std::memory_order_relaxed);  // key_shift
    engine->pulsar_section_tension_data[tb + 7].store(0.0f, std::memory_order_relaxed);  // half_lick
    engine->pulsar_section_tension_data[tb + 8].store(0.0f, std::memory_order_relaxed);  // chromatic_passing
    engine->pulsar_section_tension_data[tb + 9].store(0.25f, std::memory_order_relaxed); // evo_timbre_low
    engine->pulsar_section_tension_data[tb + 10].store(0.55f, std::memory_order_relaxed);// evo_timbre_high
    engine->pulsar_section_tension_data[tb + 11].store(0.5f, std::memory_order_relaxed); // evo_timbre_prob
    engine->pulsar_section_tension_data[tb + 12].store(-1.0f, std::memory_order_relaxed);// evo_morph_low
    engine->pulsar_section_tension_data[tb + 13].store(-1.0f, std::memory_order_relaxed);// evo_morph_high
    engine->pulsar_section_tension_data[tb + 14].store(0.5f, std::memory_order_relaxed); // evo_morph_prob
    engine->pulsar_section_tension_data[tb + 15].store(-1.0f, std::memory_order_relaxed);// evo_harm_low
    engine->pulsar_section_tension_data[tb + 16].store(-1.0f, std::memory_order_relaxed);// evo_harm_high
    engine->pulsar_section_tension_data[tb + 17].store(0.3f, std::memory_order_relaxed); // evo_harm_prob
    engine->pulsar_section_tension_data[tb + 18].store(0.5f, std::memory_order_relaxed); // evo_attack_point
    engine->pulsar_section_tension_data[tb + 19].store(0.3f, std::memory_order_relaxed); // evo_release_speed
    engine->pulsar_section_tension_data[tb + 20].store(0.0f, std::memory_order_relaxed); // spurt_chance

    trigger_vibe_load(engine);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);

    // Observe ping-pong 0 -> 1 -> 0.
    int last_section = -1;
    bool entered_section_1 = false;
    bool section_1_override_observed = false;
    bool returned_to_section_0 = false;
    float vol_on_return = -1.0f;

    for (int i = 0; i < 3000; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        PulsarState* ps = engine->pulsar_state;
        if (!ps) continue;
        int cur = ps->section_state.current_section;

        // On entry to section 1, confirm override took hold
        if (last_section == 0 && cur == 1) {
            entered_section_1 = true;
            if (std::fabs(ps->tension.volume - 0.9f) < 1e-5f) {
                section_1_override_observed = true;
            }
        }

        // On return to section 0, snapshot tension.volume
        if (last_section == 1 && cur == 0 && entered_section_1) {
            returned_to_section_0 = true;
            vol_on_return = ps->tension.volume;
            break;
        }
        last_section = cur;
    }

    bool entered_ok = entered_section_1 && section_1_override_observed;
    bool return_ok  = returned_to_section_0;
    bool revert_ok  = return_ok && std::fabs(vol_on_return - 0.3f) < 1e-5f;
    bool ok = entered_ok && return_ok && revert_ok;

    if (!entered_ok) {
        printf("  FAIL: did not observe section 1 override (volume=0.9) take effect\n");
    } else {
        printf("  section 1 override observed (tension.volume=0.9) -- OK\n");
    }
    if (!return_ok) {
        printf("  FAIL: never observed return to section 0\n");
    } else {
        printf("  on return to section 0: tension.volume=%.4f (expected 0.30, vibe default) -- %s\n",
               vol_on_return, revert_ok ? "OK" : "FAIL");
    }
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_initial_section_tension_override_applied_on_load() {
    printf("\n=== Test: Initial section's tension override applies on load (before any transition) ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);

    // Vibe-level tension: distinctive base volume = 0.3
    engine->pulsar_tension_inner_bars.store(4, std::memory_order_relaxed);
    engine->pulsar_tension_outer_bars.store(0, std::memory_order_relaxed);
    engine->pulsar_tension_volume.store(0.3f, std::memory_order_relaxed);

    push_two_section_ab_arrangement(engine, 1);
    // The AB helper defaults intro_index to -1 (random weighted start); pin it to 0
    // so the section carrying the override below is deterministically the initial one.
    engine->pulsar_arrangement_intro_index.store(0, std::memory_order_relaxed);

    // Override on the INITIAL section (0) with distinctive volume = 0.9. Before the
    // fix, load_vibe left the intro on the vibe base (0.3) until the first transition.
    engine->pulsar_section_tension_active[0].store(1, std::memory_order_relaxed);
    // Tension's own fixed stride, NOT pulsar_section_data's kSectionDataFields, which
    // has been widened repeatedly (weather, then the pinned lick) -- see pulsar_limits.h.
    constexpr int kSectionStride = 21;
    const int tb = 0 * kSectionStride;
    engine->pulsar_section_tension_data[tb + 0].store(4.0f, std::memory_order_relaxed);  // inner_bars
    engine->pulsar_section_tension_data[tb + 1].store(0.0f, std::memory_order_relaxed);  // outer_bars
    engine->pulsar_section_tension_data[tb + 2].store(0.5f, std::memory_order_relaxed);  // outer_depth
    engine->pulsar_section_tension_data[tb + 3].store(0.9f, std::memory_order_relaxed);  // volume <-- distinctive
    engine->pulsar_section_tension_data[tb + 4].store(0.2f, std::memory_order_relaxed);  // timing
    engine->pulsar_section_tension_data[tb + 5].store(0.0f, std::memory_order_relaxed);  // octave_shift
    engine->pulsar_section_tension_data[tb + 6].store(0.0f, std::memory_order_relaxed);  // key_shift
    engine->pulsar_section_tension_data[tb + 7].store(0.0f, std::memory_order_relaxed);  // half_lick
    engine->pulsar_section_tension_data[tb + 8].store(0.0f, std::memory_order_relaxed);  // chromatic_passing
    engine->pulsar_section_tension_data[tb + 9].store(0.25f, std::memory_order_relaxed); // evo_timbre_low
    engine->pulsar_section_tension_data[tb + 10].store(0.55f, std::memory_order_relaxed);// evo_timbre_high
    engine->pulsar_section_tension_data[tb + 11].store(0.5f, std::memory_order_relaxed); // evo_timbre_prob
    engine->pulsar_section_tension_data[tb + 12].store(-1.0f, std::memory_order_relaxed);// evo_morph_low
    engine->pulsar_section_tension_data[tb + 13].store(-1.0f, std::memory_order_relaxed);// evo_morph_high
    engine->pulsar_section_tension_data[tb + 14].store(0.5f, std::memory_order_relaxed); // evo_morph_prob
    engine->pulsar_section_tension_data[tb + 15].store(-1.0f, std::memory_order_relaxed);// evo_harm_low
    engine->pulsar_section_tension_data[tb + 16].store(-1.0f, std::memory_order_relaxed);// evo_harm_high
    engine->pulsar_section_tension_data[tb + 17].store(0.3f, std::memory_order_relaxed); // evo_harm_prob
    engine->pulsar_section_tension_data[tb + 18].store(0.5f, std::memory_order_relaxed); // evo_attack_point
    engine->pulsar_section_tension_data[tb + 19].store(0.3f, std::memory_order_relaxed); // evo_release_speed
    engine->pulsar_section_tension_data[tb + 20].store(0.0f, std::memory_order_relaxed); // spurt_chance

    trigger_vibe_load(engine);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);

    // The first render runs load_vibe (initial-section entry). Assert the intro's
    // override is live immediately — BEFORE any advance_section transition fires
    // (one 512-frame block at 240 BPM is far shorter than a bar, so section stays 0).
    unit_process_pulsar(&unit, engine, 512, 48000.0f);
    PulsarState* ps = engine->pulsar_state;
    bool ok = ps != nullptr
           && ps->section_state.current_section == 0
           && std::fabs(ps->tension.volume - 0.9f) < 1e-5f;

    if (!ps) {
        printf("  FAIL: no pulsar state\n");
    } else {
        printf("  initial section=%d, tension.volume=%.4f (expected 0.90 override, NOT 0.30 base) -- %s\n",
               ps->section_state.current_section, ps->tension.volume, ok ? "OK" : "FAIL");
    }
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_section_comping_humanization_override_loads() {
    printf("\n=== Test: Section comping humanization override loads from atomics ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);

    // Two-section arrangement so we have indices 0 and 1.
    push_two_section_ab_arrangement(engine, 1);

    // Section 0: no override.
    engine->pulsar_section_comping_humanization_active[0].store(0, std::memory_order_relaxed);

    // Section 1: full override with distinctive values.
    engine->pulsar_section_comping_humanization_active[1].store(1, std::memory_order_relaxed);
    engine->pulsar_section_comping_humanization_data[1 * 4 + 0].store(0.05f, std::memory_order_relaxed); // drop
    engine->pulsar_section_comping_humanization_data[1 * 4 + 1].store(0.10f, std::memory_order_relaxed); // ghost
    engine->pulsar_section_comping_humanization_data[1 * 4 + 2].store(0.00f, std::memory_order_relaxed); // octave (the SunkenPlace use case)
    engine->pulsar_section_comping_humanization_data[1 * 4 + 3].store(0.15f, std::memory_order_relaxed); // extension

    trigger_vibe_load(engine);
    // One process call to flush load_vibe into PulsarState.
    unit_process_pulsar(&unit, engine, 64, 48000.0f);

    PulsarState* ps = engine->pulsar_state;
    bool ok = (ps != nullptr);
    if (ok) {
        const SectionParam& s0 = ps->arrangement.sections[0];
        const SectionParam& s1 = ps->arrangement.sections[1];
        bool s0_ok = !s0.has_comping_humanization_override;
        bool s1_active_ok = s1.has_comping_humanization_override;
        bool s1_drop_ok   = std::fabs(s1.comping_humanization_drop      - 0.05f) < 1e-5f;
        bool s1_ghost_ok  = std::fabs(s1.comping_humanization_ghost     - 0.10f) < 1e-5f;
        bool s1_octave_ok = std::fabs(s1.comping_humanization_octave    - 0.00f) < 1e-5f;
        bool s1_ext_ok    = std::fabs(s1.comping_humanization_extension - 0.15f) < 1e-5f;
        printf("  section 0 inactive override: %s\n", s0_ok ? "OK" : "FAIL");
        printf("  section 1 active flag: %s\n", s1_active_ok ? "OK" : "FAIL");
        printf("  section 1 drop=%.4f ghost=%.4f octave=%.4f ext=%.4f -- %s\n",
               s1.comping_humanization_drop, s1.comping_humanization_ghost,
               s1.comping_humanization_octave, s1.comping_humanization_extension,
               (s1_drop_ok && s1_ghost_ok && s1_octave_ok && s1_ext_ok) ? "OK" : "FAIL");
        ok = s0_ok && s1_active_ok && s1_drop_ok && s1_ghost_ok && s1_octave_ok && s1_ext_ok;
    } else {
        printf("  FAIL: PulsarState was null after process call\n");
    }
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return ok;
}

// Regression (Task 3 review, Finding 2): the field-21 "+1" decode had no C++ coverage.
// Reverting orpheus_unit_pulsar.cpp's `lick_slot - 1` back to a raw pass-through failed
// nothing in the suite before this test existed -- section 0's untouched (zero) field
// would silently decode as "pinned to slot 0" instead of -1 ("no override"), which is
// exactly the failure mode the brief called the single most important correctness
// property in the task. The sections[0].lick_index == -1 assertion is the one that
// actually catches a raw decode; sections[1] alone would pass either way.
static bool test_section_lick_index_decodes_plus_one() {
    printf("\n=== Test: section_data field 26 decodes as lick_index+1 (0 = no override) ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);

    // Two-section arrangement so we have indices 0 and 1.
    push_two_section_ab_arrangement(engine, 1);

    // Section 0: left untouched -- field 26 stays at its zero-init default.
    // Section 1: lick_index pinned to slot 3, encoded as 3+1=4.
    engine->pulsar_section_data[1 * kSectionDataFields + 26].store(4.0f, std::memory_order_relaxed);

    trigger_vibe_load(engine);
    // One process call to flush load_vibe into PulsarState.
    unit_process_pulsar(&unit, engine, 64, 48000.0f);

    PulsarState* ps = engine->pulsar_state;
    bool ok = (ps != nullptr);
    if (ok) {
        const SectionParam& s0 = ps->arrangement.sections[0];
        const SectionParam& s1 = ps->arrangement.sections[1];
        bool s0_ok = (s0.lick_index == -1);
        bool s1_ok = (s1.lick_index == 3);
        printf("  section 0 lick_index=%d (expected -1, no override) -- %s\n",
               s0.lick_index, s0_ok ? "OK" : "FAIL");
        printf("  section 1 lick_index=%d (expected 3, from encoded 4) -- %s\n",
               s1.lick_index, s1_ok ? "OK" : "FAIL");
        ok = s0_ok && s1_ok;
    } else {
        printf("  FAIL: PulsarState was null after process call\n");
    }
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_section_macro_crossfade() {
    printf("\n=== Test: pre-roll macro crossfade across per-edge transition_bars ===\n");

    // Build a 2-section arrangement where section 0 IS the ramp:
    //   Section 0: 4 bars long, no macro overrides.
    //   Edge 0->1: transition_bars = 4 (the whole section is the ramp zone).
    //   Section 1: energy override = 2.0 (the ramp target).
    ArrangementParams arr = {};
    arr.active = true;
    arr.section_count = 2;
    arr.intro_index = -1;
    arr.outro_index = -1;

    arr.sections[0].bars_min = 4;
    arr.sections[0].bars_max = 4;
    arr.sections[0].recency_decay = 0.5f;
    arr.sections[0].transitions[0].target_index = 1;
    arr.sections[0].transitions[0].weight = 1.0f;
    arr.sections[0].transitions[0].transition_bars = 4;
    arr.sections[0].transition_count = 1;
    // macro_overrides default-initialized to -1 (no override)

    arr.sections[1].bars_min = 1;
    arr.sections[1].bars_max = 1;
    arr.sections[1].recency_decay = 0.5f;
    arr.sections[1].transitions[0].target_index = 0;
    arr.sections[1].transitions[0].weight = 1.0f;
    arr.sections[1].transitions[0].transition_bars = 0;  // hard cut back
    arr.sections[1].transition_count = 1;
    arr.sections[1].macro_overrides.energy = 2.0f;

    SectionState state;
    uint32_t seed = 7777;
    init_section_state(state, arr, seed);

    // Force section 0 active deterministically.
    state.current_section = 0;
    state.target_energy = -1.0f;
    state.target_complexity = -1.0f;
    state.target_space = -1.0f;
    state.target_mood = -1.0f;
    state.bars_remaining = 4;
    plan_next_section(state, arr, seed);
    bool ok_plan = state.next_section_planned == 1
                && state.next_section_trans_bars == 4;
    printf("  plan: next=%d, trans_bars=%d -- %s\n",
           state.next_section_planned, state.next_section_trans_bars,
           ok_plan ? "PASS" : "FAIL");

    // Bar 1: rem 4->3. into_ramp = 4-3 = 1. progress = 0.25. Stage destination.
    advance_section(state, arr, seed);
    bool ok_stage = state.transition_target == 1
                 && std::fabs(state.transition_progress - 0.25f) < 0.001f
                 && std::fabs(state.next_energy - 2.0f) < 0.001f
                 && std::fabs(state.target_energy - (-1.0f)) < 0.001f;
    // Blend at progress=0.25: 1.0 + (2.0 - 1.0) * 0.25 = 1.25
    float mult_q = section_macro_value(1.0f, state.target_energy, state.next_energy,
                                        state.transition_progress);
    bool ok_mult_q = std::fabs(mult_q - 1.25f) < 0.001f;
    printf("  bar 1 (rem=3, progress=%.2f, blend=%.3f): %s/%s\n",
           state.transition_progress, mult_q,
           ok_stage ? "PASS" : "FAIL", ok_mult_q ? "PASS" : "FAIL");

    // Bars 2, 3: progress 0.5, 0.75
    advance_section(state, arr, seed);
    bool ok_half = std::fabs(state.transition_progress - 0.5f) < 0.001f;
    advance_section(state, arr, seed);
    bool ok_three_q = std::fabs(state.transition_progress - 0.75f) < 0.001f;
    printf("  bar 2 (progress=%.2f): %s\n", state.transition_progress, ok_half ? "PASS" : "FAIL");
    // (note: bar 2's progress was already overwritten by bar 3's; ok_half captured at the right time)
    printf("  bar 3 (progress=%.2f): %s\n", state.transition_progress, ok_three_q ? "PASS" : "FAIL");

    // Bar 4: rem 1->0. Ramp would hit 1.0 mid-bar, then hard-flip to section 1.
    bool changed = advance_section(state, arr, seed);
    bool ok_flip = changed
                && state.current_section == 1
                && state.transition_target == -1
                && state.transition_progress == 0.0f
                && state.next_energy < -0.5f;  // staging slots cleared back to sentinel
    printf("  bar 4 (flip to section 1): %s\n", ok_flip ? "PASS" : "FAIL");

    return ok_plan && ok_stage && ok_mult_q && ok_half && ok_three_q && ok_flip;
}

// Verify the audio path computes a continuous sub-bar transition_progress
// from track 0's playhead, so the macro lerp updates per-step instead of
// stepping in coarse per-bar jumps. The arrangement-level transition_progress
// stays bar-quantized; the smoothing happens inside unit_process_pulsar.
//
// Assertion: across an N-bar ramp, observe more than N distinct progress
// values. A bar-quantized implementation gives exactly N values; the
// continuous formula gives ~N * step_count.
static bool test_section_macro_subbar_lerp() {
    printf("\n=== Test: macro multiplier lerps continuously across each bar ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);

    // 2-section arrangement, 4 bars per section so we can observe a 4-bar ramp.
    push_two_section_ab_arrangement(engine, 4);

    // Edge 0->1 has transition_bars = 4 so the entire section 0 IS the ramp.
    constexpr int kSectionStride = kSectionDataFields;
    engine->pulsar_section_data[1 * kSectionStride + 5].store(2.0f, std::memory_order_relaxed);  // section 1 energy = 2.0
    engine->pulsar_section_transitions[0 * 3 + 2].store(4.0f, std::memory_order_relaxed);        // edge 0 -> 1 trans_bars
    // Force section 0 as the initial section so the ramp is deterministic
    // (intro_index = -1 makes init_section_state pick randomly).
    engine->pulsar_arrangement_intro_index.store(0, std::memory_order_relaxed);

    trigger_vibe_load(engine);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);

    // Drive long enough to traverse the full 4-bar ramp with room to spare.
    // 1024-sample blocks at 48 kHz, 240 BPM, 4 bars = ~94 blocks; budget 400.
    int distinct_progress = 0;
    float last_progress = -2.0f;
    bool monotonic = true;
    bool reached_section_1 = false;
    int N = 4;

    for (int i = 0; i < 400; i++) {
        unit_process_pulsar(&unit, engine, 1024, 48000.0f);
        PulsarState* ps = engine->pulsar_state;
        if (!ps) continue;
        if (ps->section_state.current_section == 1) {
            reached_section_1 = true;
            break;
        }
        if (ps->section_state.transition_target < 0) continue;

        const PulsarTrackState& t0 = ps->tracks[0];
        float bar_phase = (t0.step_count > 0)
            ? static_cast<float>(t0.playhead) / static_cast<float>(t0.step_count)
            : 0.0f;
        float bars_remaining_continuous =
            static_cast<float>(ps->section_state.bars_remaining) - bar_phase;
        if (bars_remaining_continuous < 0.0f) bars_remaining_continuous = 0.0f;
        float progress = (static_cast<float>(N) - bars_remaining_continuous)
                         / static_cast<float>(N);
        if (progress < 0.0f) progress = 0.0f;
        if (progress > 1.0f) progress = 1.0f;

        if (std::fabs(progress - last_progress) > 1e-5f) {
            distinct_progress++;
            if (progress + 1e-5f < last_progress) monotonic = false;
            last_progress = progress;
        }
    }

    bool ok_reached = reached_section_1;
    bool ok_smooth = distinct_progress > N;       // bar-quantized would give exactly N
    bool ok_mono = monotonic;

    printf("  reached section 1: %s\n", ok_reached ? "OK" : "FAIL");
    printf("  distinct progress values during ramp: %d (expected > %d): %s\n",
           distinct_progress, N, ok_smooth ? "OK" : "FAIL");
    printf("  progress monotonic non-decreasing: %s\n", ok_mono ? "OK" : "FAIL");

    bool ok = ok_reached && ok_smooth && ok_mono;
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return ok;
}

// randomize_section_bars must keep the chosen length within [bars_min, bars_max]
// and must keep the minimum reachable (idx == 0 maps to bars_min). The top edge
// needs a guard because pattern_rand01 returns [0, 1] *inclusive* of 1.0, which
// would otherwise push idx to stop_count and overshoot bars_max by one step.
static bool test_randomize_section_bars_bounds() {
    printf("\n=== Test: randomize_section_bars includes min, never exceeds max ===\n");
    bool ok = true;

    // Contract sweep: range 6..8 step 1 — both ends reachable, never out of bounds.
    {
        SectionParam sec;
        sec.bars_min = 6; sec.bars_max = 8; sec.bar_step = 1;
        uint32_t seed = 0xC0FFEE;
        bool saw_min = false, saw_max = false, in_bounds = true;
        for (int i = 0; i < 200000; i++) {
            int b = randomize_section_bars(sec, seed);
            if (b < 6 || b > 8) in_bounds = false;
            if (b == 6) saw_min = true;
            if (b == 8) saw_max = true;
        }
        bool case_ok = saw_min && saw_max && in_bounds;
        printf("  6..8 step1: saw_min=%s saw_max=%s in_bounds=%s -- %s\n",
               saw_min ? "yes" : "no", saw_max ? "yes" : "no",
               in_bounds ? "yes" : "no", case_ok ? "PASS" : "FAIL");
        ok = ok && case_ok;
    }

    // Equal min==max (e.g. the 4-bar drift/awol section): always returns the min.
    {
        SectionParam sec;
        sec.bars_min = 4; sec.bars_max = 4; sec.bar_step = 1;
        uint32_t seed = 0x1234;
        bool always_min = true;
        for (int i = 0; i < 1000; i++)
            if (randomize_section_bars(sec, seed) != 4) always_min = false;
        printf("  4..4: always returns 4 = %s -- %s\n",
               always_min ? "yes" : "no", always_min ? "PASS" : "FAIL");
        ok = ok && always_min;
    }

    // Deterministic top-edge clamp: find a seed whose first xorshift fills the low
    // 23 bits (so pattern_rand01 returns exactly 1.0), then confirm the result is
    // clamped to bars_max (8) rather than overshooting to bars_max + step (9).
    {
        uint32_t hot_seed = 0;
        bool found = false;
        for (uint32_t cand = 1; cand != 0; cand++) {
            uint32_t t = cand;
            if ((pattern_rand(t) & 0x7FFFFF) == 0x7FFFFF) { hot_seed = cand; found = true; break; }
            if (cand > 50000000u) break;  // safety; a hit exists far sooner
        }
        SectionParam sec;
        sec.bars_min = 6; sec.bars_max = 8; sec.bar_step = 1;  // stop_count = 3
        uint32_t seed = hot_seed;
        int b = randomize_section_bars(sec, seed);
        bool case_ok = found && (b == 8);
        printf("  forced rand01=1.0 (seed=%u): bars=%d (expect 8, not 9) found=%s -- %s\n",
               hot_seed, b, found ? "yes" : "no", case_ok ? "PASS" : "FAIL");
        ok = ok && case_ok;
    }

    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_select_next_lead_excludes_self_and_drums() {
    // 3 members: 0 = drums (always_active), 1 = bass, 2 = keys.
    // handoff_matrix rows give the CURRENT lead (member 2) nonzero weight to
    // itself and to the drummer — the primary loop must refuse both.
    BandSoloConfigParam cfg = {};
    cfg.member_count = 3;
    cfg.members[0].always_active = true;
    cfg.members[1].always_active = false;
    cfg.members[2].always_active = false;
    // stride-kMaxBandMembers layout (post-pack), row = current lead 2
    cfg.handoff_matrix[2 * kMaxBandMembers + 0] = 0.5f; // -> drums (must skip)
    cfg.handoff_matrix[2 * kMaxBandMembers + 1] = 0.5f; // -> bass  (allowed)
    cfg.handoff_matrix[2 * kMaxBandMembers + 2] = 0.5f; // -> self  (must skip)

    BandSoloState st = {};
    st.lead_member = 2;
    for (int m = 0; m < 3; m++) st.bars_since_lead[m] = 4;

    bool ok = true;
    uint32_t seed = 1;
    for (int i = 0; i < 500; i++) {
        int next = select_next_lead(cfg, st, seed);
        if (next == 2) { ok = false; printf("  FAIL: re-picked self (member 2)\n"); break; }
        if (cfg.members[next].always_active) {
            ok = false; printf("  FAIL: picked always_active drummer (member %d)\n", next); break;
        }
    }
    printf("  select_next_lead excludes self + drums over 500 rolls -- %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// Fix #4: in JAM mode a member that owns NO melodic track (a chordal-only "Keys"
// member, as in DogHouse) must never be chosen as the solo lead — generate_jam_solo_line
// would no-op, producing a dead solo (just boosted comping). Eligible jam leads own >=1
// MELODIC track. Band here: drums(always), bass(mel), keys(chordal-only), fx(mel).
static bool test_jam_lead_excludes_chordal_only_member() {
    BandSoloConfigParam cfg = {};
    cfg.member_count = 4;
    cfg.members[0].always_active = true;                                  // drums
    cfg.members[0].track_count = 3; cfg.members[0].tracks[0]=0; cfg.members[0].tracks[1]=1; cfg.members[0].tracks[2]=2;
    cfg.members[1].track_count = 1; cfg.members[1].tracks[0]=3;           // bass (melodic)
    cfg.members[2].track_count = 1; cfg.members[2].tracks[0]=4;           // keys (chordal-only)
    cfg.members[3].track_count = 1; cfg.members[3].tracks[0]=5;           // fx (melodic)
    // Every member pushes hard toward keys (member 2) — the filter must still refuse it.
    for (int f = 0; f < 4; f++) for (int t = 0; t < 4; t++)
        cfg.handoff_matrix[f * kMaxBandMembers + t] = (f == t) ? 0.0f : 1.0f;
    cfg.bars_per_lead_min = 1; cfg.bars_per_lead_max = 1;

    PulsarTrackState tracks[kNumPulsarTracks] = {};
    tracks[0].role = TrackRole::PERCUSSIVE; tracks[1].role = TrackRole::PERCUSSIVE; tracks[2].role = TrackRole::PERCUSSIVE;
    tracks[3].role = TrackRole::MELODIC; tracks[3].step_count = 16;
    tracks[4].role = TrackRole::CHORDAL; tracks[4].step_count = 16;
    tracks[5].role = TrackRole::MELODIC; tracks[5].step_count = 16;

    SectionParam sec = {}; sec.solo_mode = SoloModeId::JAM;

    BandSoloState st = {};
    st.active = true; st.lead_member = 1;             // start on bass (eligible)
    st.member_role[1] = MemberSoloRole::LEADING;
    st.member_bars_remaining[1] = 1;
    for (int m = 0; m < 4; m++) st.bars_since_lead[m] = 4;

    uint32_t seed = 3;
    bool keys_ever_led = false;
    for (int i = 0; i < 400; i++) {
        advance_band_solo(st, cfg, sec, tracks, seed, kNumPulsarTracks);
        if (st.lead_member == 2) keys_ever_led = true;
        if (st.member_bars_remaining[st.lead_member] <= 0) st.member_bars_remaining[st.lead_member] = 1;
    }
    bool ok = !keys_ever_led;
    printf("  jam excludes chordal-only lead over 400 handoffs: keys_led=%d -- %s\n",
           keys_ever_led, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_duck_gate_is_deterministic() {
    // Same (playhead, track, loop, mod) must give the same survive/drop decision,
    // and ~ (1+mod) fraction must survive over many distinct steps.
    bool deterministic = true;
    for (int s = 0; s < 16; s++) {
        bool a = duck_passes(s, 3, 7, -0.4f);
        bool b = duck_passes(s, 3, 7, -0.4f);
        if (a != b) { deterministic = false; break; }
    }
    int survived = 0, total = 0;
    for (int loop = 0; loop < 50; loop++)
        for (int s = 0; s < 16; s++) { total++; if (duck_passes(s, 3, loop, -0.4f)) survived++; }
    float frac = static_cast<float>(survived) / total;     // expect ~0.6
    bool frac_ok = frac > 0.45f && frac < 0.75f;
    bool ok = deterministic && frac_ok;
    printf("  duck gate deterministic=%s survive_frac=%.2f (exp ~0.60) -- %s\n",
           deterministic ? "yes" : "no", frac, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_support_duck_is_softened() {
    // 2 members: 0 keys (LEADING), 1 bass (SUPPORT, not always_active).
    BandSoloConfigParam cfg = {};
    cfg.member_count = 2;
    cfg.members[0].track_count = 1; cfg.members[0].tracks[0] = 1; // keys on track 1
    cfg.members[1].track_count = 1; cfg.members[1].tracks[0] = 2; // bass on track 2
    BandSoloState st = {};
    st.active = true; st.lead_member = 0;
    st.member_role[0] = MemberSoloRole::LEADING;
    st.member_role[1] = MemberSoloRole::SUPPORT;

    PulsarTrackState tracks[kNumPulsarTracks] = {};
    apply_band_solo_modifiers(tracks, cfg, st, kNumPulsarTracks);

    bool ok = approx(tracks[2].solo_density_mod, -0.2f) &&
              approx(tracks[2].solo_fill_mod,    -0.35f);
    printf("  SUPPORT duck density=%.2f fill=%.2f (exp -0.20 / -0.35) -- %s\n",
           tracks[2].solo_density_mod, tracks[2].solo_fill_mod, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_overlap_baton_pass() {
    printf("\n=== Test: Overlap baton-pass (look-ahead + bridge bar) ===\n");
    BandSoloConfigParam cfg = {};
    cfg.member_count = 3;
    cfg.members[0].always_active = true;                    // drums
    cfg.members[1].always_active = false;                   // bass
    cfg.members[2].always_active = false;                   // keys
    cfg.handoff_matrix[1 * kMaxBandMembers + 2] = 1.0f;     // bass -> keys
    cfg.bars_per_lead_min = 1; cfg.bars_per_lead_max = 1;
    SectionParam sec = {}; sec.solo_mode = SoloModeId::JAM;

    BandSoloState st = {};
    st.active = true; st.lead_member = 1;
    st.member_role[1] = MemberSoloRole::LEADING;
    st.member_role[2] = MemberSoloRole::SUPPORT;
    st.member_bars_remaining[1] = 2;   // expires in 2 bars -> hits ==1 after one advance
    for (int m = 0; m < 3; m++) st.bars_since_lead[m] = 4;

    PulsarTrackState tracks[kNumPulsarTracks] = {};
    uint32_t seed = 5;

    advance_band_solo(st, cfg, sec, tracks, seed, kNumPulsarTracks); // bars_remaining 2->1: pre-select
    bool preselected = (st.pending_lead == 2) && (st.member_role[2] == MemberSoloRole::ACTIVE);

    advance_band_solo(st, cfg, sec, tracks, seed, kNumPulsarTracks); // expiry: promote pending, demote outgoing to ACTIVE
    bool promoted = (st.lead_member == 2) && (st.member_role[2] == MemberSoloRole::LEADING)
                 && (st.member_role[1] == MemberSoloRole::ACTIVE) && (st.pending_lead == -1);

    bool ok = preselected && promoted;
    printf("  baton pass: preselect=%d promote=%d -- %s\n", preselected, promoted, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_slew_toward_monotonic_no_overshoot() {
    float v = -0.3f; const float target = 0.2f, step = 0.25f;
    float prev = v; bool ok = true; int bars = 0;
    while (std::fabs(v - target) > 1e-4f && bars < 20) {
        v = slew_toward(v, target, step);
        if (v < prev - 1e-6f) { ok = false; break; }   // monotonic up
        if (v > target + 1e-4f) { ok = false; break; } // no overshoot
        prev = v; bars++;
    }
    bool reached = std::fabs(v - target) < 1e-4f;
    // |−0.3 → 0.2| = 0.5 at 0.25/bar => 2 bars
    bool timing_ok = bars == 2;
    ok = ok && reached && timing_ok;
    printf("  slew reached target in %d bars, monotonic/no-overshoot -- %s\n",
           bars, ok ? "PASS" : "FAIL");
    return ok;
}

// ── Task 7: drum-lead gate + style selection ─────────────────────────────────

static bool test_drum_lead_gate_and_style() {
    uint32_t seed = 12345;
    // Rarity: over 2000 LICK_BUILDER rolls with last_was_drum=false, ~12% fire.
    int fired = 0;
    for (int i = 0; i < 2000; i++) if (should_drum_lead(SoloModeId::LICK_BUILDER, false, seed)) fired++;
    float frac = fired / 2000.0f;
    bool rarity_ok = frac > 0.08f && frac < 0.16f;
    // Never fires in JAM, never fires consecutively.
    bool jam_blocked = !should_drum_lead(SoloModeId::JAM, false, seed);
    bool consec_blocked = !should_drum_lead(SoloModeId::LICK_BUILDER, true, seed);
    // Contour falls back to LOCK_IN when <3 tracks; allowed with 3.
    bool fallback_ok = pick_drum_lead_style(1, seed) != DrumLeadStyle::CONTOUR;
    bool contour_possible = false;
    for (int i = 0; i < 200; i++) if (pick_drum_lead_style(3, seed) == DrumLeadStyle::CONTOUR) { contour_possible = true; break; }
    bool ok = rarity_ok && jam_blocked && consec_blocked && fallback_ok && contour_possible;
    printf("  drum-lead frac=%.3f jam_blocked=%d consec_blocked=%d fallback=%d contour=%d -- %s\n",
           frac, jam_blocked, consec_blocked, fallback_ok, contour_possible, ok ? "PASS" : "FAIL");
    return ok;
}

// ── Task 8: drum-lead render via generate_lick_rhythm_pattern ───────────────

static bool test_render_drum_lead_mirrors_lick() {
    // Build a 3-track drum member and a short lick; render each style.
    // Uses the preferred testable signature (no PulsarState* needed):
    //   render_drum_lead(config, tracks, num_tracks, lead_member, style, lick, lick_len, complexity, seed)

    // Config: one band member owning tracks 0/1/2 (kick/snare/hat)
    BandSoloConfigParam cfg = {};
    cfg.member_count = 1;
    cfg.members[0].track_count = 3;
    cfg.members[0].tracks[0] = 0;  // kick
    cfg.members[0].tracks[1] = 1;  // snare
    cfg.members[0].tracks[2] = 2;  // hat
    cfg.members[0].always_active = true;

    // Tracks: 0/1/2 PERCUSSIVE, 3 MELODIC pre-filled with gated steps
    PulsarTrackState tracks[kNumPulsarTracks] = {};
    tracks[0].role = TrackRole::PERCUSSIVE; tracks[0].step_count = 16;
    tracks[1].role = TrackRole::PERCUSSIVE; tracks[1].step_count = 16;
    tracks[2].role = TrackRole::PERCUSSIVE; tracks[2].step_count = 16;
    tracks[3].role = TrackRole::MELODIC;    tracks[3].step_count = 16;
    for (int i = 0; i < 16; i++) tracks[3].steps[i] = make_step(60, 0.8f, true, 0.5f);

    // Short lick to mirror
    PulsarLickStep lick[4] = {
        {0, 1.0f, 0.9f, -1.0f}, {2, 1.0f, 0.5f, -1.0f},
        {4, 1.0f, 0.3f, -1.0f}, {5, 1.0f, 0.8f, -1.0f}
    };
    uint32_t seed = 7;

    // LOCK_IN: drum tracks should get hits; melody should be untouched
    render_drum_lead(cfg, tracks, kNumPulsarTracks, 0, DrumLeadStyle::LOCK_IN, lick, 4, 0.5f, seed);
    int drum_hits = 0;
    for (int i = 0; i < 16; i++) {
        if (tracks[0].steps[i].gate || tracks[1].steps[i].gate || tracks[2].steps[i].gate)
            drum_hits++;
    }
    bool melody_kept = tracks[3].steps[0].gate;  // LOCK_IN leaves melody alone

    // BREAK: the render itself no longer touches melody -- the duck does that work now.
    // Re-fill melody before the BREAK render
    for (int i = 0; i < 16; i++) tracks[3].steps[i] = make_step(60, 0.8f, true, 0.5f);
    render_drum_lead(cfg, tracks, kNumPulsarTracks, 0, DrumLeadStyle::BREAK, lick, 4, 0.5f, seed);
    bool melody_kept_under_break = tracks[3].steps[0].gate;

    bool ok = (drum_hits > 0) && melody_kept && melody_kept_under_break;
    printf("  drum render: hits=%d lockin_keeps_melody=%d break_leaves_melody=%d -- %s\n",
           drum_hits, melody_kept, melody_kept_under_break, ok ? "PASS" : "FAIL");
    return ok;
}

// ── Task 6: the drum arc ─────────────────────────────────────────────────────

// Shared scaffold for the arc tests: a 3-track drum member with a snapshot groove.
// PulsarTrackState is not copy-assignable (it embeds an OrpheusVoice), so the fixture
// clears the three kit tracks' steps by hand instead of reassigning the array.
static void arc_fixture(BandSoloConfigParam& cfg, PulsarTrackState* tracks, BandSoloState& st) {
    cfg = BandSoloConfigParam{}; cfg.member_count = 1;
    cfg.members[0].track_count = 3; cfg.members[0].tracks[0] = 0; cfg.members[0].tracks[1] = 1; cfg.members[0].tracks[2] = 2;
    cfg.members[0].always_active = true;
    for (int t = 0; t < 3; t++) {
        tracks[t].role = TrackRole::PERCUSSIVE; tracks[t].step_count = 16;
        for (int i = 0; i < kMaxPulsarSteps; i++) tracks[t].steps[i] = make_step(0, 0.0f, false, 0.0f);
    }
    for (int i = 0; i < 16; i += 4) tracks[0].steps[i] = make_step(36, 0.9f, true, 0.5f);
    for (int i = 4; i < 16; i += 8) tracks[1].steps[i] = make_step(40, 0.8f, true, 0.4f);
    st = BandSoloState{}; st.active = true; st.lead_member = 0; st.drum_lead_style = 1;
    st.member_role[0] = MemberSoloRole::LEADING; st.member_bars_remaining[0] = 4;
    begin_drum_lead(st, cfg, tracks, kNumPulsarTracks);
}

static int count_gates(const PulsarTrackState& t, int from, int to) {
    int n = 0; for (int i = from; i < to; i++) if (t.steps[i].gate) n++; return n;
}

static bool test_drum_arc_hats_climb_and_climax_fills_the_last_beat() {
    printf("\n=== Test: drum arc: hats climb with progress, the last bar ends in a fill ===\n");
    BandSoloConfigParam cfg; PulsarTrackState tracks[kNumPulsarTracks]; BandSoloState st;
    PulsarLickStep lick[4] = {{0, 1.0f, 0.9f, -1.0f}, {2, 1.0f, 0.5f, -1.0f}, {4, 1.0f, 0.3f, -1.0f}, {5, 1.0f, 0.8f, -1.0f}};
    int hats_early = 0, hats_late = 0, climax_q = 0, other_q = 0;
    for (uint32_t s = 1; s <= 8; s++) {
        arc_fixture(cfg, tracks, st); uint32_t seed = s;
        DrumArc a0 = drum_arc(0.0f, false, s, 0);
        render_drum_lead(cfg, tracks, kNumPulsarTracks, 0, DrumLeadStyle::LOCK_IN, lick, 4, 0.5f, seed, &st, &a0);
        hats_early += count_gates(tracks[2], 0, 16);
        DrumArc a1 = drum_arc(1.0f, false, s, 3);
        render_drum_lead(cfg, tracks, kNumPulsarTracks, 0, DrumLeadStyle::LOCK_IN, lick, 4, 0.5f, seed, &st, &a1);
        hats_late += count_gates(tracks[2], 0, 16);
        DrumArc last = drum_arc(1.0f, true, s, 3);
        render_drum_lead(cfg, tracks, kNumPulsarTracks, 0, DrumLeadStyle::LOCK_IN, lick, 4, 0.5f, seed, &st, &last);
        climax_q += count_gates(tracks[1], 12, 16);
        other_q  += count_gates(tracks[1], 0, 4) + count_gates(tracks[1], 4, 8) + count_gates(tracks[1], 8, 12);
    }
    bool climb = hats_late * 2 > hats_early * 3;   // the observed margin is roughly 2x
    bool climax = climax_q >= 8 * 4 && climax_q * 3 > other_q;   // every climax quarter full
    bool pass = climb && climax;
    printf("  hats early=%d late=%d  snare climax-quarter=%d other-quarters=%d -- %s\n",
           hats_early, hats_late, climax_q, other_q, pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_drum_arc_runs_without_a_lick() {
    printf("\n=== Test: a drum lead with no live lick still restores and arcs ===\n");
    BandSoloConfigParam cfg; PulsarTrackState tracks[kNumPulsarTracks]; BandSoloState st;
    arc_fixture(cfg, tracks, st); uint32_t seed = 3;
    // Render once with a loud lick note so the kick row is actually overlaid (0.9 in the
    // groove, 1.0 in the lick beats it), then again with no lick, so "restored" below is
    // proving something rather than checking gates the render never touched.
    PulsarLickStep lick[4] = {{0, 1.0f, 1.0f, -1.0f}, {2, 1.0f, 0.5f, -1.0f}, {4, 1.0f, 0.3f, -1.0f}, {5, 1.0f, 0.8f, -1.0f}};
    DrumArc mid = drum_arc(1.0f, false, 3, 3);
    render_drum_lead(cfg, tracks, kNumPulsarTracks, 0, DrumLeadStyle::LOCK_IN, lick, 4, 0.5f, seed, &st, &mid);
    DrumArc a = drum_arc(1.0f, true, 3, 3);
    render_drum_lead(cfg, tracks, kNumPulsarTracks, 0, DrumLeadStyle::LOCK_IN, nullptr, 0, 0.5f, seed, &st, &a);
    // Kick should be back to exactly the snapshot (gated at 0/4/8/12, nowhere else) --
    // except the last step, which the climax's own unconditional downbeat kick owns
    // regardless of any lick; that override is covered by the climax test above.
    bool groove_kept = true;
    for (int i = 0; i < 16 && groove_kept; i++) {
        if (i == 15) continue;
        const PulsarStep& k = tracks[0].steps[i];
        const PulsarStep& g = st.drum_groove[0][i];
        if (k.gate != g.gate || k.note != g.note || k.velocity != g.velocity || k.duration != g.duration)
            groove_kept = false;
    }
    bool hats = count_gates(tracks[2], 0, 16) > 0;
    bool fill = count_gates(tracks[1], 12, 16) == 4;
    bool pass = groove_kept && hats && fill;
    printf("  groove=%d hats=%d fill=%d -- %s\n", groove_kept, hats, fill, pass ? "PASS" : "FAIL");
    return pass;
}

// The hats and ghosts draw from the seed the caller HANDS the render, which is how the
// engine keeps them off the shared mutation stream: it passes a hashed local seed. Pin
// both halves -- the draws land on the caller's variable, and the same seed renders the
// same bar.
static bool test_drum_lead_render_leaves_the_caller_seed_alone() {
    printf("\n=== Test: a drum lead render draws only from the seed it was handed ===\n");
    BandSoloConfigParam cfg; PulsarTrackState tracks[kNumPulsarTracks]; BandSoloState st;
    arc_fixture(cfg, tracks, st);
    PulsarLickStep lick[4] = {{0, 1.0f, 0.9f, -1.0f}, {2, 1.0f, 0.5f, -1.0f},
                              {4, 1.0f, 0.3f, -1.0f}, {5, 1.0f, 0.8f, -1.0f}};
    DrumArc arc = drum_arc(0.5f, false, 9u, 1);

    const uint32_t start = 0xC0FFEEu;
    uint32_t seed = start;
    render_drum_lead(cfg, tracks, kNumPulsarTracks, 0, DrumLeadStyle::LOCK_IN, lick, 4, 0.5f, seed, &st, &arc);
    bool advanced = seed != start;
    PulsarStep hat[kMaxPulsarSteps], snare[kMaxPulsarSteps];
    std::memcpy(hat, tracks[2].steps, sizeof(hat));
    std::memcpy(snare, tracks[1].steps, sizeof(snare));

    // The snapshot is the base every bar, so an identical seed must reproduce the rows.
    uint32_t again = start;
    render_drum_lead(cfg, tracks, kNumPulsarTracks, 0, DrumLeadStyle::LOCK_IN, lick, 4, 0.5f, again, &st, &arc);
    bool deterministic = (again == seed);
    for (int i = 0; i < 16 && deterministic; i++) {
        const PulsarStep& h = tracks[2].steps[i];
        const PulsarStep& s = tracks[1].steps[i];
        deterministic = h.gate == hat[i].gate && h.note == hat[i].note && h.velocity == hat[i].velocity &&
                        s.gate == snare[i].gate && s.note == snare[i].note && s.velocity == snare[i].velocity;
    }
    bool pass = advanced && deterministic;
    printf("  seed 0x%X -> 0x%X advanced=%d identical_rows=%d -- %s\n",
           start, seed, advanced, deterministic, pass ? "PASS" : "FAIL");
    return pass;
}

static bool approx6(float a, float b) { return std::fabs(a - b) < 1e-6f; }

static bool test_drum_span_progress_lands_the_climax_on_the_last_bar() {
    printf("\n=== Test: drum_span_progress lands the climax on exactly the last bar ===\n");
    bool ok = true;

    // span=4: remaining 4,3,2,1 -> dp 0, 1/3, 2/3, 1; climax true only on remaining=1.
    const float expect4[4] = {0.0f, 1.0f / 3.0f, 2.0f / 3.0f, 1.0f};
    for (int i = 0; i < 4; i++) {
        int remaining = 4 - i;
        float dp = drum_span_progress(4, remaining);
        BandSoloState st{};
        st.drum_span_bars = 4; st.member_bars_remaining[0] = remaining;
        st.solo_seed = 7; st.bars_elapsed = 2;
        DrumArc arc = drum_arc_for_bar(st, 0);
        bool dp_ok = approx6(dp, expect4[i]);
        bool climax_ok = arc.climax == (remaining <= 1);
        printf("  span=4 remaining=%d dp=%.6f (expect %.6f) climax=%d -- %s\n",
               remaining, dp, expect4[i], arc.climax, (dp_ok && climax_ok) ? "OK" : "FAIL");
        ok = ok && dp_ok && climax_ok;
    }

    // A one-bar span is entirely climax.
    BandSoloState st1{}; st1.drum_span_bars = 1; st1.member_bars_remaining[0] = 1;
    float dp1 = drum_span_progress(1, 1);
    bool span1_ok = approx6(dp1, 1.0f) && drum_arc_for_bar(st1, 0).climax;
    printf("  span=1 remaining=1 dp=%.6f (expect 1.0) climax=%d -- %s\n",
           dp1, drum_arc_for_bar(st1, 0).climax, span1_ok ? "OK" : "FAIL");
    ok = ok && span1_ok;

    // span=2: first bar dp=0 (no climax), second (last) bar dp=1 (climax).
    BandSoloState st2a{}; st2a.drum_span_bars = 2; st2a.member_bars_remaining[0] = 2;
    BandSoloState st2b{}; st2b.drum_span_bars = 2; st2b.member_bars_remaining[0] = 1;
    float dp2a = drum_span_progress(2, 2);
    float dp2b = drum_span_progress(2, 1);
    bool arc2a_climax = drum_arc_for_bar(st2a, 0).climax;
    bool arc2b_climax = drum_arc_for_bar(st2b, 0).climax;
    bool span2_ok = approx6(dp2a, 0.0f) && approx6(dp2b, 1.0f) && !arc2a_climax && arc2b_climax;
    printf("  span=2 dp=%.6f,%.6f (expect 0,1) climax=%d,%d (expect 0,1) -- %s\n",
           dp2a, dp2b, arc2a_climax, arc2b_climax, span2_ok ? "OK" : "FAIL");
    ok = ok && span2_ok;

    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// ── Task 6: register reconciliation ─────────────────────────────────────────

static bool test_choose_lick_octave_minimizes_leap() {
    // Outgoing soloist ended near MIDI 60. The lick's first degree, rendered at
    // the chosen octave, should land within a perfect 5th (7 semitones) of 60.
    PulsarScale scale = kPulsarScales[0];   // major scale (index 0)
    int root = 0;        // C
    int first_degree = 0;
    int outgoing = 60;
    int oct = choose_lick_octave(first_degree, outgoing, root, scale, 36, 84);
    // Recompute the first note the way render_lick_into_track will, given oct:
    int note = lick_degree_to_midi(first_degree, root, scale, oct);
    bool ok = std::abs(note - outgoing) <= 7;
    printf("  chosen octave places first note %d within a 5th of %d -- %s\n",
           note, outgoing, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_choose_lick_octave_no_prior_soloist() {
    // When outgoing_note < 0, choose_lick_octave returns -1 (auto).
    PulsarScale scale = kPulsarScales[0];
    int oct = choose_lick_octave(0, -1, 0, scale, 36, 84);
    bool ok = (oct == -1);
    printf("  no prior soloist returns -1 (auto) -- %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_choose_lick_octave_clamps_to_range() {
    // Outgoing note is 120 (very high). Chosen octave must keep note in [36,84].
    PulsarScale scale = kPulsarScales[0];
    int root = 0; int first_degree = 0; int outgoing = 120;
    int oct = choose_lick_octave(first_degree, outgoing, root, scale, 36, 84);
    bool ok = false;
    if (oct >= 0) {
        int note = lick_degree_to_midi(first_degree, root, scale, oct);
        ok = (note >= 36 && note <= 84);
    }
    printf("  high outgoing note: chosen octave keeps first note in range [36,84] -- %s\n",
           ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_articulate_bass_solo() {
    PulsarStep steps[16];
    for (int i = 0; i < 16; i++) steps[i] = make_step(0, 0.0f, false, 0.0f);
    // Gated bass roots on the quarter-note downbeats (i = 0,4,8,12).
    for (int i = 0; i < 16; i += 4) steps[i] = make_step(36, 0.6f, true, 0.5f);

    uint32_t seed = 9;
    articulate_bass_solo(steps, 16, 0.8f /*dense for the test*/, seed);

    // 1. Downbeat gated steps are accented (velocity raised above the original 0.6).
    bool accented = steps[0].velocity > 0.6f && steps[4].velocity > 0.6f;
    // 2. Some empty ODD (off-beat) steps became slap ghosts: gate=true, short dur, high vel, bass note.
    int slaps = 0; bool slap_shape_ok = true;
    for (int i = 1; i < 16; i += 2) {            // odd indices only
        if (steps[i].gate) {
            slaps++;
            if (!(steps[i].velocity >= 0.85f && steps[i].duration <= 0.2f && steps[i].note == 36))
                slap_shape_ok = false;
        }
    }
    // 3. EVEN empty steps (downbeat subdivisions) are NOT slapped — slaps are off-beat only.
    bool even_clean = true;
    for (int i = 2; i < 16; i += 4) if (steps[i].gate) even_clean = false;  // i=2,6,10,14 were empty
    // 4. Original sustained downbeats keep their long duration.
    bool sustained_ok = steps[0].duration >= 0.5f;
    // 5. Determinism.
    PulsarStep steps2[16];
    for (int i = 0; i < 16; i++) steps2[i] = make_step(0,0.0f,false,0.0f);
    for (int i = 0; i < 16; i += 4) steps2[i] = make_step(36, 0.6f, true, 0.5f);
    uint32_t seed2 = 9; articulate_bass_solo(steps2, 16, 0.8f, seed2);
    bool deterministic = true;
    for (int i = 0; i < 16; i++) if (steps2[i].gate != steps[i].gate) deterministic = false;

    bool ok = accented && slaps > 0 && slap_shape_ok && even_clean && sustained_ok && deterministic;
    printf("  articulate: accented=%d slaps=%d shape=%d even_clean=%d sustained=%d det=%d -- %s\n",
           accented, slaps, slap_shape_ok, even_clean, sustained_ok, deterministic, ok ? "PASS":"FAIL");
    return ok;
}

// Fix #2: articulate_bass_solo runs every bar; in JAM the bass substrate may not be
// regenerated, so it is applied repeatedly to the SAME buffer. It must be idempotent:
// repeated passes must NOT pile slaps onto every off-beat nor creep velocities to
// full scale (which turns a sparse bass solo into a loud constant 16th-note slap line).
static bool test_articulate_bass_solo_idempotent_under_repeats() {
    PulsarStep steps[16];
    for (int i = 0; i < 16; i++) steps[i] = make_step(0, 0.0f, false, 0.0f);
    for (int i = 0; i < 16; i += 4) steps[i] = make_step(36, 0.6f, true, 0.5f);  // quarter roots
    uint32_t seed = 9;
    // 20 bars of articulation on the same (never-regenerated) buffer.
    for (int bar = 0; bar < 20; bar++) articulate_bass_solo(steps, 16, 0.35f, seed);

    int odd_slaps = 0;
    for (int i = 1; i < 16; i += 2) if (steps[i].gate) odd_slaps++;
    // Quarter-note roots must not all be pinned at full scale (additive accents would).
    bool quarter_not_saturated = false;
    for (int i = 0; i < 16; i += 4) if (steps[i].gate && steps[i].velocity < 0.999f) quarter_not_saturated = true;
    // Sparse density (0.35) must stay sparse — NOT fill all 8 off-beats over 20 bars.
    bool slaps_bounded = odd_slaps <= 5;
    bool ok = slaps_bounded && quarter_not_saturated;
    printf("  articulate idempotent: odd_slaps=%d(<=5) quarter_not_saturated=%d -- %s\n",
           odd_slaps, quarter_not_saturated, ok ? "PASS":"FAIL");
    return ok;
}

// A hook-driven JAM lead runs render_lick_into_track (authored velocities) then
// ornament_jam_solo_line (deliberately quieter fills) and only THEN this pass. The
// beat-position floors would lift every quiet step to 0.85/0.70 — inverting a written
// diminuendo and pulling the ornaments up level with the hook. preserve_authored_velocity
// must leave every already-gated step exactly as authored while still inserting slaps.
static bool test_articulate_bass_solo_preserves_authored_velocity() {
    // A written contour with steps on both sides of the 0.85/0.70 floors, plus
    // ornament-level fills at 0.45/0.60 — durations 1.0 as generate_lick_pattern writes.
    const float authored[16] = {
        0.98f, 0.0f, 0.45f, 0.0f, 0.74f, 0.86f, 0.78f, 0.0f,
        0.70f, 0.0f, 0.60f, 0.0f, 1.00f, 0.0f, 0.88f, 0.0f,
    };
    PulsarStep kept[16], floored[16];
    for (int i = 0; i < 16; i++) {
        kept[i] = (authored[i] > 0.0f) ? make_step(38, authored[i], true, 1.0f)
                                       : make_step(0, 0.0f, false, 0.0f);
        floored[i] = kept[i];
    }

    // Dense slaps so assertion 3 does not hang on one RNG draw.
    uint32_t seed_a = 9, seed_b = 9;
    articulate_bass_solo(kept, 16, 0.8f, seed_a, /*preserve_authored_velocity=*/true);
    articulate_bass_solo(floored, 16, 0.8f, seed_b, /*preserve_authored_velocity=*/false);

    // 1. Every authored note survives at its written velocity.
    bool velocities_kept = true;
    for (int i = 0; i < 16; i++) {
        if (authored[i] <= 0.0f) continue;
        if (!kept[i].gate || kept[i].velocity != authored[i]) velocities_kept = false;
    }
    // 2. Mutation check: without the flag those same steps ARE rewritten, so a
    //    regression that drops the flag fails assertion 1 rather than passing quietly.
    int raised = 0;
    for (int i = 0; i < 16; i++)
        if (authored[i] > 0.0f && floored[i].velocity != authored[i]) raised++;
    // 3. Articulation still happens: slaps land on off-beats the hook left open.
    int slaps = 0;
    for (int i = 1; i < 16; i += 2)
        if (authored[i] <= 0.0f && kept[i].gate) slaps++;

    bool ok = velocities_kept && raised >= 4 && slaps > 0;
    printf("  authored bass solo: velocities_kept=%d floored_would_raise=%d slaps=%d -- %s\n",
           velocities_kept, raised, slaps, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_articulate_bass_solo_reports_and_strips_slaps() {
    printf("\n=== Test: articulate_bass_solo reports slap count and strips at density 0 ===\n");
    PulsarStep steps[16] = {};
    for (int i = 0; i < 16; i += 4) steps[i] = make_step(40, 0.8f, true, 0.5f);
    uint32_t seed = 31;
    int with = articulate_bass_solo(steps, 16, 1.0f, seed, true);
    int after = articulate_bass_solo(steps, 16, 0.0f, seed, true);
    int leftover = 0;
    for (int i = 1; i < 16; i += 2)
        if (steps[i].gate && steps[i].velocity == kBassSlapVelocity && steps[i].duration == kBassSlapDuration) leftover++;
    bool pass = with > 0 && after == 0 && leftover == 0;
    printf("  slaps with=%d after=%d leftover=%d -- %s\n", with, after, leftover, pass ? "PASS" : "FAIL");
    return pass;
}

static bool test_solo_fire_boost_never_saturates() {
    // High base + big mods must NOT clamp to 1.0 — headroom-relative lift leaves rests.
    float hi = solo_fire_boost(0.94f, 0.39f, 0.70f);   // the bass runaway case
    bool keeps_rests = hi < 1.0f && hi > 0.94f;
    // Low base: same mods lift more (more headroom to fill).
    float lo = solo_fire_boost(0.50f, 0.39f, 0.70f);
    bool lifts_more_when_low = (lo - 0.50f) > (hi - 0.94f);
    // Zero/negative density contributes nothing.
    bool noop = solo_fire_boost(0.7f, 0.0f, 0.5f) == 0.7f;
    // Never exceeds 1.0 even at extremes.
    bool bounded = solo_fire_boost(0.99f, 1.0f, 1.0f) <= 1.0f;
    bool ok = keeps_rests && lifts_more_when_low && noop && bounded;
    printf("  fire_boost hi=%.3f(<1,>0.94) lo=%.3f lifts_more=%d noop=%d bounded=%d -- %s\n",
           hi, lo, lifts_more_when_low, noop, bounded, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_midi_lick_degree_roundtrip_and_synth() {
    const PulsarScale& scale = kPulsarScales[0];
    int root = 48;  // C3: keeps oct-1..+1 notes in valid MIDI range (36-82)
    // 1. Round-trip for in-scale notes (octave 0 render).
    bool roundtrip = true;
    for (int oct = -1; oct <= 1; oct++) {
        for (int i = 0; i < scale.count; i++) {
            int note = root + oct*12 + scale.degrees[i];
            int d = midi_to_lick_degree(note, root, scale);
            int back = lick_degree_to_midi(d, root, scale, 0);
            if (back != note) { roundtrip = false; }
        }
    }
    // 2. Out-of-scale note snaps to a scale tone (back note's pitch-class is in scale).
    int weird = root + 1;  // a semitone above root (likely not in a diatonic scale)
    int wd = midi_to_lick_degree(weird, root, scale);
    int wback = lick_degree_to_midi(wd, root, scale, 0);
    int wpc = ((wback - root) % 12 + 12) % 12;
    bool snapped = false; for (int i = 0; i < scale.count; i++) if (scale.degrees[i] == wpc) snapped = true;
    // 3. synthesize captures only gated steps in order with their dur/vel.
    PulsarStep steps[8];
    for (int i = 0; i < 8; i++) steps[i] = make_step(0, 0.0f, false, 0.0f);
    steps[0] = make_step((uint8_t)(root + scale.degrees[0]), 0.7f, true, 0.5f);
    steps[2] = make_step((uint8_t)(root + 12 + scale.degrees[1]), 0.9f, true, 0.25f);
    int8_t dg[32]; float du[32], ve[32];
    int n = synthesize_lick_from_steps(steps, 8, root, scale, dg, du, ve, 32);
    bool synth_ok = (n == 2)
        && approx(du[0], 0.5f) && approx(ve[0], 0.7f)
        && approx(du[1], 0.25f) && approx(ve[1], 0.9f)
        && lick_degree_to_midi(dg[0], root, scale, 0) == root + scale.degrees[0]
        && lick_degree_to_midi(dg[1], root, scale, 0) == root + 12 + scale.degrees[1];
    bool ok = roundtrip && snapped && synth_ok;
    printf("  midi<->degree roundtrip=%d snapped=%d synth(n=%d)=%d -- %s\n",
           roundtrip, snapped, n, synth_ok, ok ? "PASS":"FAIL");
    return ok;
}

// Fix #1: a source note BELOW the root maps to a negative scale degree, and the
// lick renderer (bar_strategy / generate_lick_pattern) treats scale_degree<0 as a
// REST. So synthesize_lick_from_steps must octave-shift the contour non-negative,
// or the DogHouse lick-less LickBuilder fallback renders those notes as silence.
static bool test_synthesize_lick_below_root_renders_notes_not_rests() {
    const PulsarScale& scale = kPulsarScales[0];  // major
    int root = 60;  // C4 — a source note below this maps to a negative degree
    // Ascending line that STARTS below the root: G3, C4, E4.
    PulsarStep steps[8];
    for (int i = 0; i < 8; i++) steps[i] = make_step(0, 0.0f, false, 0.0f);
    steps[0] = make_step((uint8_t)(root - 5), 0.7f, true, 0.5f);  // G3, below root
    steps[1] = make_step((uint8_t)(root),     0.8f, true, 0.5f);  // C4
    steps[2] = make_step((uint8_t)(root + 4), 0.9f, true, 0.5f);  // E4
    int8_t dg[32]; float du[32], ve[32];
    int n = synthesize_lick_from_steps(steps, 8, root, scale, dg, du, ve, 32);
    // 1. All synthesized degrees must be NON-NEGATIVE (else they render as rests).
    bool no_negatives = (n == 3);
    for (int i = 0; i < n; i++) if (dg[i] < 0) no_negatives = false;
    // 2. Contour preserved: ascending source -> non-descending degrees.
    bool ascending = (n == 3) && dg[0] <= dg[1] && dg[1] <= dg[2];
    bool ok = no_negatives && ascending;
    printf("  synth below-root: n=%d no_neg=%d ascending=%d (dg=[%d,%d,%d]) -- %s\n",
           n, no_negatives, ascending, dg[0], dg[1], dg[2], ok ? "PASS":"FAIL");
    return ok;
}

static bool test_generate_jam_solo_line() {
    printf("\n=== Test: generate_jam_solo_line produces in-scale chord-anchored notes ===\n");
    const PulsarScale& scale = kPulsarScales[0];
    int root = 0, chord_degree = 0, octave = 5, current = 0;
    SoloBehaviorParam behavior = {};                       // default weights
    for (int i = 0; i < kMarkovIntervals; i++) behavior.interval_weights[i] = 1.0f;
    behavior.rest_probability = 0.0f; behavior.hold_probability = 0.0f;
    behavior.density_curve_min = 1.0f; behavior.density_curve_max = 1.0f;  // always fire
    BandSoloState st = {}; st.phrase_cursor = 0;
    PulsarStep steps[16]; for (int i=0;i<16;i++) steps[i]=make_step(0,0.0f,false,0.0f);
    uint32_t seed = 11;
    // 0,0 = no authored range, so notes keep the historical 24..96 clamp and this
    // test's in-scale / chord-anchor intent is unaffected by the note-range fit.
    generate_jam_solo_line(behavior, st, steps, 16, root, scale, chord_degree, octave, current,
                           0.5f, 0, 0, seed);

    // 1. Every gated note is in scale (pitch class is a scale degree).
    bool in_scale = true; int gated = 0;
    for (int s = 0; s < 16; s++) if (steps[s].gate) {
        gated++;
        int pc = ((steps[s].note - root) % 12 + 12) % 12;
        bool ok=false; for (int d=0; d<scale.count; d++) if (scale.degrees[d]==pc) ok=true;
        if (!ok) in_scale = false;
    }
    // 2. Downbeats (s%4==0) that fired are CHORD TONES (pc in {root,3rd,5th} of chord_degree=0).
    int chord_pcs[3] = { scale.degrees[0],
                         scale.degrees[2 % scale.count],
                         scale.degrees[4 % scale.count] };
    bool downbeats_chord = true;
    for (int s = 0; s < 16; s += 4) if (steps[s].gate) {
        int pc = ((steps[s].note - root) % 12 + 12) % 12;
        bool ct=false; for (int k=0;k<3;k++) if (chord_pcs[k]==pc) ct=true;
        if (!ct) downbeats_chord = false;
    }
    // 3. record_solo_note ran (phrase captured).
    bool recorded = st.phrase_cursor > 0;
    // 4. Determinism.
    SoloBehaviorParam b2 = behavior; BandSoloState s2 = {}; int c2 = 0;
    PulsarStep st2[16]; for (int i=0;i<16;i++) st2[i]=make_step(0,0.0f,false,0.0f);
    uint32_t seed2 = 11;
    generate_jam_solo_line(b2, s2, st2, 16, root, scale, chord_degree, octave, c2,
                           0.5f, 0, 0, seed2);
    bool det = true; for (int i=0;i<16;i++) if (st2[i].gate!=steps[i].gate || st2[i].note!=steps[i].note) det=false;

    bool ok = in_scale && downbeats_chord && recorded && det && gated > 0;
    printf("  jam gen: gated=%d in_scale=%d downbeats_chord=%d recorded=%d det=%d -- %s\n",
           gated, in_scale, downbeats_chord, recorded, det, ok ? "PASS":"FAIL");
    return ok;
}

// ── P1-T5: >32-step FILL lick renders through the sequencer ────────────────
//
// The lick marshalling tests (test_pulsar_lick_marshalling.cpp) only prove
// the Kotlin -> C++ round-trip of engine->pulsar_lick[]/lick_length. This
// test proves the RENDER path: a 48-step FILL lick (cap now 64, so 48 is
// valid) becomes the track's ts.step_count, every one of its 48 steps is
// rendered into ts.steps[] as authored, and the sequencer's playhead wraps
// at 48 (not 32, and not 64 — the kMaxPulsarSteps buffer bound).
static bool test_fill_lick_48_steps_renders_and_wraps() {
    printf("\n=== Test: 48-step FILL lick renders through the sequencer and wraps at 48 ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    engine->pulsar_seed.store(424242, std::memory_order_relaxed);  // pin RNG (avoid wall-clock re-stir)

    // setup_fixture_baseline's role[] marks track 4 (keys) MELODIC — make it the
    // FILL lead. Zero mutation so the authored lick renders byte-for-byte
    // (no random degree/velocity/duration perturbation from generate_lick_pattern).
    constexpr int kLeadTrack = 4;
    engine->pulsar_track_lick_mode[kLeadTrack].store(2 /* FILL */, std::memory_order_relaxed);
    engine->pulsar_lick_mutation.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_lick_octave.store(-1, std::memory_order_relaxed);   // auto
    engine->pulsar_lick_loop_length.store(0, std::memory_order_relaxed); // no rest padding

    // 48-step lick: one scale-degree note per sequencer step (duration=0.25 beat
    // == 1 slot at 4 steps/beat), each step carrying a distinct velocity so
    // truncation/collapse at any index is unmistakable. Degrees cycle 0..6 so
    // the octave-quantized MIDI note stays in a sane range.
    constexpr int kSteps = 48;
    for (int i = 0; i < kSteps; i++) {
        engine->pulsar_lick[i].scale_degree = static_cast<int8_t>(i % 7);
        engine->pulsar_lick[i].duration = 0.25f;
        engine->pulsar_lick[i].velocity = 0.5f + i * 0.01f;   // distinct per step, stays <= 0.97
        engine->pulsar_lick[i].glide_rate = -1.0f;
    }
    engine->pulsar_lick_length.store(kSteps, std::memory_order_release);  // release-fence LAST

    engine->pulsar_step_count.store(kSteps, std::memory_order_relaxed);

    trigger_vibe_load(engine);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);

    // One process call is enough to run load_vibe() and render the FILL pattern
    // into ts.steps[] — we don't need audio playback for the static checks.
    unit_process_pulsar(&unit, engine, 64, 48000.0f);

    PulsarState* ps = engine->pulsar_state;
    bool ok = (ps != nullptr);
    if (!ok) {
        printf("  FAIL: PulsarState was null after process call\n");
        orpheus_engine_destroy(engine);
        return false;
    }

    const PulsarTrackState& ts = ps->tracks[kLeadTrack];

    bool step_count_ok = (ts.step_count == kSteps);
    printf("  lead track step_count=%d (expected %d) -- %s\n",
           ts.step_count, kSteps, step_count_ok ? "OK" : "FAIL");

    // Spot-check steps 0, 32 (past the OLD 32-step cap), and 47 (the tail,
    // exactly what a 32-cap or an off-by-one would have dropped/clipped).
    auto check_step = [&](int i) -> bool {
        const PulsarStep& s = ts.steps[i];
        float expected_vel = 0.5f + i * 0.01f;
        bool gate_ok = s.gate;
        bool vel_ok = approx(s.velocity, expected_vel);
        printf("  step %2d: gate=%d velocity=%.4f (expected gate=1, velocity=%.4f) -- %s\n",
               i, s.gate, s.velocity, expected_vel,
               (gate_ok && vel_ok) ? "OK" : "FAIL");
        return gate_ok && vel_ok;
    };
    bool step0_ok  = check_step(0);
    bool step32_ok = check_step(32);
    bool step47_ok = check_step(47);

    // No read past kMaxPulsarSteps (64): the fixed-size steps[] buffer bound
    // itself guards this at compile time, but the functional contract is that
    // the RENDERED pattern never claims more than the buffer holds.
    bool bound_ok = (ts.step_count <= kMaxPulsarSteps);
    printf("  step_count within kMaxPulsarSteps buffer bound (%d <= %d) -- %s\n",
           ts.step_count, kMaxPulsarSteps, bound_ok ? "OK" : "FAIL");

    // ── Playhead wrap: advance the sequencer and confirm it wraps at 48, ──
    // ── not 32 (old cap) and not 64 (buffer bound).                      ──
    // Drive audio at 240 BPM (16 steps/sec at 48kHz => 3000 samples/step)
    // in small blocks so every step boundary is observable, and watch for
    // the playhead to return to 0 after having reached 47 (never touching
    // 48+, which the % loop_len wrap makes impossible by construction, but
    // an assertion here still proves it empirically from the render path).
    int max_seen = -1;
    bool saw_47 = false;
    bool wrapped_after_47 = false;
    bool ever_hit_48_or_more = false;
    int prev_playhead = ts.playhead;

    for (int i = 0; i < 4000 && !wrapped_after_47; i++) {
        unit_process_pulsar(&unit, engine, 64, 48000.0f);
        int ph = ps->tracks[kLeadTrack].playhead;
        if (ph > max_seen) max_seen = ph;
        if (ph >= kSteps) ever_hit_48_or_more = true;
        if (ph == kSteps - 1) saw_47 = true;
        if (saw_47 && prev_playhead == kSteps - 1 && ph == 0) wrapped_after_47 = true;
        prev_playhead = ph;
    }

    bool wrap_ok = saw_47 && wrapped_after_47 && !ever_hit_48_or_more;
    printf("  playhead: max_seen=%d saw_47=%d wrapped_47->0=%d ever>=48=%d -- %s\n",
           max_seen, saw_47, wrapped_after_47, ever_hit_48_or_more, wrap_ok ? "OK" : "FAIL");

    ok = step_count_ok && step0_ok && step32_ok && step47_ok && bound_ok && wrap_ok;
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_master_scratch_freezes_pulsar_clock() {
    printf("\n=== Test: an active master scratch freezes the pulsar clock (holds the incoming section) ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);

    // First render allocates pulsar_state, so read ps only after warming up.
    for (int i = 0; i < 10; i++) unit_process_pulsar(&unit, engine, 512, 48000.0f);
    PulsarState* ps = engine->pulsar_state;
    if (!ps) { printf("  FAIL: no pulsar state\n"); orpheus_engine_destroy(engine); return false; }

    // Control: with NO scratch active, the clock advances (track 0 playhead moves).
    // ~30 blocks * 512 = 15360 samples ≈ 2.5 steps at 120 BPM (6000 samples/step).
    int t0_start = ps->tracks[0].playhead;
    for (int i = 0; i < 30; i++) unit_process_pulsar(&unit, engine, 512, 48000.0f);
    bool advanced_normally = ps->tracks[0].playhead != t0_start;

    // Snapshot every playhead, arm a long (1s) scratch, render many blocks. Because
    // unit_process_pulsar does not process the master unit, samples_left_ never drains,
    // so is_active() stays true and the freeze holds for all 60 blocks (~5 steps' worth).
    int ph_before[kNumPulsarTracks];
    for (int t = 0; t < kNumPulsarTracks; t++) ph_before[t] = ps->tracks[t].playhead;
    engine->master_scratch_l.arm(48000, 48000, 0);
    engine->master_scratch_r.arm(48000, 48000, 0x55555555u);
    for (int i = 0; i < 60; i++) unit_process_pulsar(&unit, engine, 512, 48000.0f);
    bool frozen = true;
    for (int t = 0; t < kNumPulsarTracks; t++)
        if (ps->tracks[t].playhead != ph_before[t]) frozen = false;

    bool ok = advanced_normally && frozen;
    printf("  advances w/o scratch=%d, all playheads frozen during scratch=%d -- %s\n",
           advanced_normally, frozen, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// ── Task 3: Section.jamCarry — solo survives the section seam ───────────────
//
// Push a 2-section (2 bars each), A<->B arrangement wired for jamCarry testing:
// a 2-member melodic band (member 0 = track 3, member 1 = track 4; neither
// always_active, both eligible to lead) with non-zero handoff/pull-in rows in
// BOTH directions and bars_per_lead 2..2 — matching the section length, so
// advance_band_solo's OWN pull-in/expiry machinery (unrelated to jamCarry)
// lands a normal mid-solo handoff on the section's own 2nd bar, before any
// section seam is reached. This lets the tests below track "whoever is
// currently soloing" rather than assuming a single lead never rotates.
//
// Section 0 always solos LICK_BUILDER at `sec0_solo_probability`, with a fast
// (1.0) mutation rate so a real mutation lands within its 2 bars. Section 1
// solos at `sec1_solo_mode` / probability 1.0, but its mutation rate is
// FROZEN at 0.0 — so a carried live lick is byte-stable across section 1's
// own bars. That isolates the jamCarry gate as the only possible source of
// change right at the seam, making an exact memcmp meaningful instead of
// racing an unrelated per-bar mutate pass. `sec1_jam_carry` writes slot 16.
//
// The arrangement starts in section 1 (no solo — load_vibe's initial-section
// entry never starts a band solo; only the per-bar section_changed handler
// does), so the FIRST section_changed event is the transition INTO section 0,
// and section 0 gets its own (uncarried, since section 0 never sets jamCarry)
// solo start before the section-0 -> section-1 seam under test.
static void push_jam_carry_band_arrangement(OrpheusEngine* engine,
                                            float sec0_solo_probability,
                                            int sec1_solo_mode,
                                            float sec1_jam_carry) {
    push_two_section_ab_arrangement(engine, 2);

    constexpr int kSectionStride = kSectionDataFields;
    // Section 0: LICK_BUILDER solo, fast mutation, never carries in.
    engine->pulsar_section_data[0 * kSectionStride + 9].store(
        static_cast<float>(static_cast<int>(SoloModeId::LICK_BUILDER)), std::memory_order_relaxed);
    engine->pulsar_section_data[0 * kSectionStride + 10].store(sec0_solo_probability, std::memory_order_relaxed);
    engine->pulsar_section_data[0 * kSectionStride + 11].store(1.0f, std::memory_order_relaxed);  // mutation_rate (fast)
    engine->pulsar_section_data[0 * kSectionStride + 12].store(0.5f, std::memory_order_relaxed);  // lick_influence
    engine->pulsar_section_data[0 * kSectionStride + 13].store(2.0f, std::memory_order_relaxed);  // solo_bars_min
    engine->pulsar_section_data[0 * kSectionStride + 14].store(4.0f, std::memory_order_relaxed);  // solo_bars_max
    engine->pulsar_section_data[0 * kSectionStride + 16].store(0.0f, std::memory_order_relaxed);  // jamCarry = false

    // Section 1: mode/probability under test, mutation frozen, jamCarry per param.
    int s1 = 1 * kSectionStride;
    engine->pulsar_section_data[s1 + 9].store(static_cast<float>(sec1_solo_mode), std::memory_order_relaxed);
    engine->pulsar_section_data[s1 + 10].store(1.0f, std::memory_order_relaxed);   // solo_probability
    engine->pulsar_section_data[s1 + 11].store(0.0f, std::memory_order_relaxed);   // mutation_rate (frozen)
    engine->pulsar_section_data[s1 + 12].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_section_data[s1 + 13].store(2.0f, std::memory_order_relaxed);
    engine->pulsar_section_data[s1 + 14].store(4.0f, std::memory_order_relaxed);
    engine->pulsar_section_data[s1 + 16].store(sec1_jam_carry, std::memory_order_relaxed);  // jamCarry (slot 16)

    // Band config: member 0 = track 3, member 1 = track 4 (both MELODIC per
    // setup_fixture_baseline's role table), neither always_active.
    engine->pulsar_band_active.store(1, std::memory_order_relaxed);
    engine->pulsar_band_member_count.store(2, std::memory_order_relaxed);
    for (int i = 0; i < 96; i++)
        engine->pulsar_band_member_data[i].store(0.0f, std::memory_order_relaxed);
    engine->pulsar_band_member_data[0 + 0].store(1.0f, std::memory_order_relaxed);    // track_count
    engine->pulsar_band_member_data[0 + 1].store(3.0f, std::memory_order_relaxed);    // tracks[0] = 3
    engine->pulsar_band_member_data[0 + 9].store(0.0f, std::memory_order_relaxed);    // always_active = false
    engine->pulsar_band_member_data[0 + 10].store(0.7f, std::memory_order_relaxed);   // loudness
    engine->pulsar_band_member_data[0 + 11].store(0.9f, std::memory_order_relaxed);   // creativity (high: mutation lands fast)
    engine->pulsar_band_member_data[12 + 0].store(1.0f, std::memory_order_relaxed);   // track_count
    engine->pulsar_band_member_data[12 + 1].store(4.0f, std::memory_order_relaxed);   // tracks[0] = 4
    engine->pulsar_band_member_data[12 + 9].store(0.0f, std::memory_order_relaxed);   // always_active = false
    engine->pulsar_band_member_data[12 + 10].store(0.8f, std::memory_order_relaxed);  // loudness
    engine->pulsar_band_member_data[12 + 11].store(0.9f, std::memory_order_relaxed);  // creativity

    // Non-zero handoff/pull-in rows in BOTH directions. Engine-level storage is
    // Kotlin-packed stride-member_count (N=2 here); the C++ unpack re-packs it
    // into the consumers' stride-kMaxBandMembers layout (see BAND-01).
    for (int i = 0; i < 64; i++) {
        engine->pulsar_band_handoff_matrix[i].store(0.0f, std::memory_order_relaxed);
        engine->pulsar_band_pull_in_matrix[i].store(0.0f, std::memory_order_relaxed);
    }
    engine->pulsar_band_handoff_matrix[0 * 2 + 1].store(1.0f, std::memory_order_relaxed);  // 0 -> 1
    engine->pulsar_band_handoff_matrix[1 * 2 + 0].store(1.0f, std::memory_order_relaxed);  // 1 -> 0
    engine->pulsar_band_pull_in_matrix[0 * 2 + 1].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_band_pull_in_matrix[1 * 2 + 0].store(0.5f, std::memory_order_relaxed);
    engine->pulsar_band_bars_per_lead_min.store(2, std::memory_order_relaxed);
    engine->pulsar_band_bars_per_lead_max.store(2, std::memory_order_relaxed);
    engine->pulsar_band_pull_in_bars_min.store(1, std::memory_order_relaxed);
    engine->pulsar_band_pull_in_bars_max.store(1, std::memory_order_relaxed);
    engine->pulsar_band_probability.store(1.0f, std::memory_order_relaxed);

    for (int i = 0; i < 8 * 15; i++)
        engine->pulsar_track_solo_behavior[i].store(0.0f, std::memory_order_relaxed);
    for (int i = 0; i < kNumPulsarTracks * kTrackDuckingFields; i++)
        engine->pulsar_track_ducking[i].store(0.0f, std::memory_order_relaxed);
    for (int i = 0; i < 8 * 15; i++)
        engine->pulsar_track_solo_markov[i].store(0.0f, std::memory_order_relaxed);

    // Start in section 1 (see file comment above for why).
    engine->pulsar_arrangement_intro_index.store(1, std::memory_order_relaxed);

    engine->pulsar_arrangement_generation.store(1, std::memory_order_release);
}

// Shared setup for the three jamCarry tests: baseline fixture (tracks 3
// and 4 are MELODIC), a pinned 4-step authored lick, and both RNGs pinned per
// the project's anti-flake convention.
static void setup_jam_carry_engine_base(OrpheusEngine* engine, const int8_t authored_degrees[4]) {
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_complexity.store(0.0f, std::memory_order_relaxed);  // isolate solo-driven changes
    setup_fixture_baseline(engine);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);

    engine->pulsar_seed.store(0x5EED, std::memory_order_relaxed);
    stmlib::Random::Seed(0x5EED);

    engine->pulsar_lick[0] = {authored_degrees[0], 0.5f, 0.8f};
    engine->pulsar_lick[1] = {authored_degrees[1], 0.5f, 0.8f};
    engine->pulsar_lick[2] = {authored_degrees[2], 0.5f, 0.8f};
    engine->pulsar_lick[3] = {authored_degrees[3], 0.5f, 0.8f};
    engine->pulsar_lick_length.store(4, std::memory_order_release);
    engine->pulsar_lick_mutation.store(0.0f, std::memory_order_relaxed);
}

static bool test_jam_carry_preserves_solo_across_section_seam() {
    printf("\n=== Test: jamCarry preserves lead + live lick across seam ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    static const int8_t kAuthoredDegrees[4] = {0, 2, 4, 1};
    setup_jam_carry_engine_base(engine, kAuthoredDegrees);
    push_jam_carry_band_arrangement(engine, /*sec0_solo_probability=*/1.0f,
                                    /*sec1_solo_mode=*/static_cast<int>(SoloModeId::LICK_BUILDER),
                                    /*sec1_jam_carry=*/1.0f);

    trigger_vibe_load(engine);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);

    int last_loop = -1;
    int prev_section = -1;
    bool saw_active_in_section0 = false;
    int lead_before = -1;
    int8_t lick_before[kMaxLickSteps] = {};
    bool reached_seam = false;

    for (int i = 0; i < 2000 && !reached_seam; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        PulsarState* ps = engine->pulsar_state;
        if (!ps) continue;
        if (ps->loop_count == last_loop) continue;
        last_loop = ps->loop_count;

        int cur = ps->section_state.current_section;
        // Track the LATEST section-0 bar (not just the first): bars_per_lead
        // == section length means a normal mid-solo handoff can land on
        // section 0's own 2nd bar, unrelated to jamCarry. What must survive
        // the seam is whatever is CURRENTLY soloing right before it.
        if (cur == 0 && ps->band_solo_state.active) {
            saw_active_in_section0 = true;
            lead_before = ps->band_solo_state.lead_member;
            std::memcpy(lick_before, ps->live_lick_degrees, sizeof(lick_before));
        }
        if (prev_section == 0 && cur == 1) {
            reached_seam = true;
        }
        prev_section = cur;
    }

    PulsarState* ps = engine->pulsar_state;
    bool mutated_before_seam = saw_active_in_section0 &&
        std::memcmp(lick_before, kAuthoredDegrees, sizeof(kAuthoredDegrees)) != 0;
    bool still_active = ps && ps->band_solo_state.active;
    bool same_lead = ps && ps->band_solo_state.lead_member == lead_before;
    bool same_lick = ps && std::memcmp(ps->live_lick_degrees, lick_before, sizeof(lick_before)) == 0;

    printf("  reached_seam=%s saw_active_in_section0=%s mutated_before_seam=%s\n",
           reached_seam ? "yes" : "no", saw_active_in_section0 ? "yes" : "no",
           mutated_before_seam ? "yes" : "no");
    printf("  lead_before=%d lead_after=%d -- %s\n",
           lead_before, ps ? ps->band_solo_state.lead_member : -999, same_lead ? "OK" : "FAIL");
    printf("  still_active=%s -- %s\n", still_active ? "yes" : "no", still_active ? "OK" : "FAIL");
    printf("  same_lick=%s -- %s\n", same_lick ? "yes" : "no", same_lick ? "OK" : "FAIL");

    bool ok = reached_seam && saw_active_in_section0 && mutated_before_seam &&
              still_active && same_lead && same_lick;
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_no_jam_carry_resets_solo_at_section_seam() {
    printf("\n=== Test: default (no jamCarry) still fully resets ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    static const int8_t kAuthoredDegrees[4] = {0, 2, 4, 1};
    setup_jam_carry_engine_base(engine, kAuthoredDegrees);
    // Identical fixture to the carry test above, except section 1's jamCarry
    // (slot 16) is left at its default false.
    push_jam_carry_band_arrangement(engine, /*sec0_solo_probability=*/1.0f,
                                    /*sec1_solo_mode=*/static_cast<int>(SoloModeId::LICK_BUILDER),
                                    /*sec1_jam_carry=*/0.0f);

    trigger_vibe_load(engine);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);

    int last_loop = -1;
    int prev_section = -1;
    bool saw_active_in_section0 = false;
    int8_t lick_before[kMaxLickSteps] = {};
    bool reached_seam = false;

    for (int i = 0; i < 2000 && !reached_seam; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        PulsarState* ps = engine->pulsar_state;
        if (!ps) continue;
        if (ps->loop_count == last_loop) continue;
        last_loop = ps->loop_count;

        int cur = ps->section_state.current_section;
        if (cur == 0 && ps->band_solo_state.active) {
            saw_active_in_section0 = true;
            std::memcpy(lick_before, ps->live_lick_degrees, sizeof(lick_before));
        }
        if (prev_section == 0 && cur == 1) {
            reached_seam = true;
        }
        prev_section = cur;
    }

    PulsarState* ps = engine->pulsar_state;
    // Confirm mutation actually landed in section 0 before the seam — otherwise
    // "matches authored after the seam" would be true trivially, not because a
    // reset/re-seed happened.
    bool mutated_before_seam = saw_active_in_section0 &&
        std::memcmp(lick_before, kAuthoredDegrees, sizeof(kAuthoredDegrees)) != 0;
    bool reseeded = ps &&
        std::memcmp(ps->live_lick_degrees, kAuthoredDegrees, sizeof(kAuthoredDegrees)) == 0;

    printf("  reached_seam=%s saw_active_in_section0=%s mutated_before_seam=%s\n",
           reached_seam ? "yes" : "no", saw_active_in_section0 ? "yes" : "no",
           mutated_before_seam ? "yes" : "no");
    printf("  reseeded_to_authored_after_seam=%s -- %s\n",
           reseeded ? "yes" : "no", reseeded ? "OK" : "FAIL");

    bool ok = reached_seam && saw_active_in_section0 && mutated_before_seam && reseeded;
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_jam_carry_fallbacks() {
    printf("\n=== Test: jamCarry fallbacks (no solo in flight / into NONE) ===\n");

    static const int8_t kAuthoredDegrees[4] = {0, 2, 4, 1};
    bool pass_a;
    {
        // (a) Section 0's solo probability is 0 so it never actually starts a
        // solo — nothing is in flight. Section 1 sets jamCarry with probability
        // 1.0: with nothing to carry, it must fall back to a normal fresh
        // start (active becomes true), not stay permanently inactive.
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);
        GraphUnit unit;
        std::memset(&unit, 0, sizeof(unit));
        unit.type = UNIT_PULSAR;
        unit.enabled = true;

        setup_jam_carry_engine_base(engine, kAuthoredDegrees);
        push_jam_carry_band_arrangement(engine, /*sec0_solo_probability=*/0.0f,
                                        /*sec1_solo_mode=*/static_cast<int>(SoloModeId::LICK_BUILDER),
                                        /*sec1_jam_carry=*/1.0f);

        trigger_vibe_load(engine);
        engine->clock_bpm.store(240.0f, std::memory_order_relaxed);

        int last_loop = -1;
        int prev_section = -1;
        bool saw_active_in_section0 = false;
        bool reached_seam = false;
        for (int i = 0; i < 2000 && !reached_seam; i++) {
            unit_process_pulsar(&unit, engine, 512, 48000.0f);
            PulsarState* ps = engine->pulsar_state;
            if (!ps) continue;
            if (ps->loop_count == last_loop) continue;
            last_loop = ps->loop_count;
            int cur = ps->section_state.current_section;
            if (cur == 0 && ps->band_solo_state.active) saw_active_in_section0 = true;
            if (prev_section == 0 && cur == 1) reached_seam = true;
            prev_section = cur;
        }

        PulsarState* ps = engine->pulsar_state;
        bool active_after = ps && ps->band_solo_state.active;
        // Precondition check: section 0's solo_probability is 0 in this
        // sub-case, so nothing should ever have been in flight there. Without
        // this, a hypothetical bug that carried an ACTUALLY-active section-0
        // solo into section 1 would also read active_after == true and this
        // test couldn't tell the difference from the fresh-start fallback.
        pass_a = reached_seam && !saw_active_in_section0 && active_after;
        printf("  (a) no solo in flight, jamCarry into section 1: saw_active_in_section0=%s (expected no) "
               "active=%s (expected yes) -- %s\n",
               saw_active_in_section0 ? "yes" : "no",
               active_after ? "yes" : "no", pass_a ? "PASS" : "FAIL");

        orpheus_engine_destroy(engine);
    }

    bool pass_b;
    {
        // (b) Section 0 solos for real (probability 1.0). Section 1 sets
        // jamCarry but is solo-less (mode NONE): carry must never keep a solo
        // alive in a solo-less section — the else-branch clear still runs.
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);
        GraphUnit unit;
        std::memset(&unit, 0, sizeof(unit));
        unit.type = UNIT_PULSAR;
        unit.enabled = true;

        setup_jam_carry_engine_base(engine, kAuthoredDegrees);
        push_jam_carry_band_arrangement(engine, /*sec0_solo_probability=*/1.0f,
                                        /*sec1_solo_mode=*/static_cast<int>(SoloModeId::NONE),
                                        /*sec1_jam_carry=*/1.0f);

        trigger_vibe_load(engine);
        engine->clock_bpm.store(240.0f, std::memory_order_relaxed);

        int last_loop = -1;
        int prev_section = -1;
        bool saw_active_in_section0 = false;
        bool reached_seam = false;
        for (int i = 0; i < 2000 && !reached_seam; i++) {
            unit_process_pulsar(&unit, engine, 512, 48000.0f);
            PulsarState* ps = engine->pulsar_state;
            if (!ps) continue;
            if (ps->loop_count == last_loop) continue;
            last_loop = ps->loop_count;
            int cur = ps->section_state.current_section;
            if (cur == 0 && ps->band_solo_state.active) saw_active_in_section0 = true;
            if (prev_section == 0 && cur == 1) reached_seam = true;
            prev_section = cur;
        }

        PulsarState* ps = engine->pulsar_state;
        bool active_after = ps && ps->band_solo_state.active;
        pass_b = reached_seam && saw_active_in_section0 && !active_after;
        printf("  (b) soloing into a NONE section, jamCarry set: active=%s (expected no) -- %s\n",
               active_after ? "yes" : "no", pass_b ? "PASS" : "FAIL");

        orpheus_engine_destroy(engine);
    }

    bool ok = pass_a && pass_b;
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// A drum-led span carried into a JAM section must not survive: should_drum_lead
// (LICK_BUILDER-only) can leave the always-active Drummer as lead_member when a
// vamp's bars expire. If the carry gate blindly honors jamCarry, that kit-only
// lead crosses into JAM's render block, which needs the lead's first MELODIC
// track -- gets -1 for a drummer, and generates nothing while the band stays
// SUPPORT-ducked. The gate must fall back to a fresh (eligibility-filtered)
// start instead. Reuses the jamCarry fixture, but section 1 is JAM (not
// LICK_BUILDER) and a third, always-active, kit-only band member (the
// Drummer, mapped to track 0 -- PERC per setup_fixture_baseline) is added.
// always_active members never win normal lead selection (select_next_lead /
// select_initial_lead both zero their weight -- see pulsar_band_solo.h), so
// the only way the drummer becomes lead is should_drum_lead's handoff
// override; rather than rely on its ~12%-per-bar roll landing, this test
// pokes band_solo_state directly to force that state deterministically.
static bool test_jam_carry_requires_eligible_lead_into_jam_section() {
    printf("\n=== Test: jamCarry into JAM requires an eligible (non-drum) lead ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    static const int8_t kAuthoredDegrees[4] = {0, 2, 4, 1};
    setup_jam_carry_engine_base(engine, kAuthoredDegrees);
    // Section 0 = LICK_BUILDER (the only mode should_drum_lead ever fires in),
    // section 1 = JAM with jamCarry set -- the peak under test.
    push_jam_carry_band_arrangement(engine, /*sec0_solo_probability=*/1.0f,
                                    /*sec1_solo_mode=*/static_cast<int>(SoloModeId::JAM),
                                    /*sec1_jam_carry=*/1.0f);

    // Third band member: always-active, kit-only Drummer on track 0 (PERC).
    constexpr int kDrummerMember = 2;
    engine->pulsar_band_member_count.store(3, std::memory_order_relaxed);
    engine->pulsar_band_member_data[kDrummerMember * 12 + 0].store(1.0f, std::memory_order_relaxed);   // track_count
    engine->pulsar_band_member_data[kDrummerMember * 12 + 1].store(0.0f, std::memory_order_relaxed);   // tracks[0] = 0 (PERC)
    engine->pulsar_band_member_data[kDrummerMember * 12 + 9].store(1.0f, std::memory_order_relaxed);   // always_active = true
    engine->pulsar_band_member_data[kDrummerMember * 12 + 10].store(0.9f, std::memory_order_relaxed);  // loudness
    engine->pulsar_band_member_data[kDrummerMember * 12 + 11].store(0.5f, std::memory_order_relaxed);  // creativity

    trigger_vibe_load(engine);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);

    int last_loop = -1;
    int prev_section = -1;
    bool saw_active_in_section0 = false;
    bool reached_seam = false;

    for (int i = 0; i < 2000 && !reached_seam; i++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        PulsarState* ps = engine->pulsar_state;
        if (!ps) continue;
        if (ps->loop_count == last_loop) continue;
        last_loop = ps->loop_count;

        int cur = ps->section_state.current_section;
        if (cur == 0 && ps->band_solo_state.active) {
            saw_active_in_section0 = true;
            // Simulate a mid-drum-span expiry: install the always-active
            // drummer as lead, exactly as should_drum_lead's handoff override
            // would. Re-applied every bar in section 0 so whichever bar turns
            // out to be section 0's last, the drummer is still lead the
            // instant the seam is crossed on the next bar boundary.
            int prev_lead = ps->band_solo_state.lead_member;
            if (prev_lead != kDrummerMember) {
                if (prev_lead >= 0 && prev_lead < kMaxBandMembers) {
                    ps->band_solo_state.member_role[prev_lead] = MemberSoloRole::SUPPORT;
                }
                ps->band_solo_state.lead_member = kDrummerMember;
                ps->band_solo_state.member_role[kDrummerMember] = MemberSoloRole::LEADING;
            }
        }
        if (prev_section == 0 && cur == 1) {
            reached_seam = true;
        }
        prev_section = cur;
    }

    PulsarState* ps = engine->pulsar_state;
    bool still_active = ps && ps->band_solo_state.active;
    bool lead_is_drummer = ps && ps->band_solo_state.lead_member == kDrummerMember;
    bool lead_owns_melodic = ps && member_can_lead_solo(
        ps->band_solo_config, ps->band_solo_state.lead_member, ps->tracks, kNumPulsarTracks);

    printf("  reached_seam=%s saw_active_in_section0=%s\n",
           reached_seam ? "yes" : "no", saw_active_in_section0 ? "yes" : "no");
    printf("  lead_after=%d (drummer=%d) still_active=%s lead_owns_melodic=%s -- %s\n",
           ps ? ps->band_solo_state.lead_member : -999, kDrummerMember,
           still_active ? "yes" : "no", lead_owns_melodic ? "yes" : "no",
           (still_active && !lead_is_drummer && lead_owns_melodic) ? "OK" : "FAIL");

    // The drum-led span must NOT survive the seam into JAM: solo stays active,
    // but handed to a member that actually owns a melodic track -- never the
    // kit-only drummer, which would render nothing while the band stays ducked.
    bool ok = reached_seam && saw_active_in_section0 && still_active &&
              !lead_is_drummer && lead_owns_melodic;
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return ok;
}

// An out-of-range target_index must never be RETURNED, not merely skipped while weighting.
// A zeroed slot was reachable by both return sites: `roll <= cumulative` succeeds at a
// zero-weight slot when roll is exactly 0, and the fall-through returned the last transition
// unconditionally. Either one put an out-of-range index into state.current_section.
//
// pattern_rand01 returns exactly 0 only when the draw's low 23 bits are clear (~1 in 8.4M), so
// search for a seed that lands on it rather than sweeping and hoping.
static bool test_select_next_section_never_returns_out_of_range_target() {
    ArrangementParams arr = make_test_arrangement();
    // Section 0's FIRST edge points outside the arrangement; the second is valid.
    arr.sections[0].transitions[0].target_index = 99;
    arr.sections[0].transitions[0].weight = 1.0f;
    arr.sections[0].transitions[1].target_index = 1;
    arr.sections[0].transitions[1].weight = 1.0f;
    arr.sections[0].transition_count = 2;

    // Smallest seed whose first draw is exactly 0. Hardcoded rather than re-derived: the
    // search is ~8.4M draws and the guard below catches an RNG change immediately.
    constexpr uint32_t kZeroRollSeed = 8912960u;
    uint32_t probe = kZeroRollSeed;
    if (pattern_rand01(probe) != 0.0f) {
        printf("  FAIL: kZeroRollSeed no longer yields roll 0 — re-derive it\n");
        return false;
    }

    SectionState state = {};
    state.current_section = 0;
    uint32_t seed = kZeroRollSeed;
    int next = select_next_section(arr, state, 0, seed);

    bool ok = true;
    if (next < 0 || next >= arr.section_count) {
        printf("  FAIL: returned out-of-range section %d (section_count=%d)\n",
               next, arr.section_count);
        ok = false;
    }
    if (next != 1) {
        printf("  FAIL: expected the only valid target (1), got %d\n", next);
        ok = false;
    }

    // Sweep with the bad edge in LAST position and two valid edges either side, so the roll
    // actually distributes and both valid targets get selected across the seed range.
    arr.sections[0].transitions[0].target_index = 1;
    arr.sections[0].transitions[1].target_index = 2;
    arr.sections[0].transitions[2].target_index = -7;
    arr.sections[0].transitions[2].weight = 1.0f;
    arr.sections[0].transition_count = 3;
    bool saw_1 = false, saw_2 = false;
    for (uint32_t s = 1; s < 2000u; s++) {
        uint32_t sweep_seed = s;
        int r = select_next_section(arr, state, 0, sweep_seed);
        if (r < 0 || r >= arr.section_count) {
            printf("  FAIL: seed %u returned out-of-range section %d\n", s, r);
            ok = false;
            break;
        }
        if (r == 1) saw_1 = true;
        if (r == 2) saw_2 = true;
    }
    if (!saw_1 || !saw_2) {
        printf("  FAIL: sweep did not distribute (saw_1=%d saw_2=%d)\n", saw_1, saw_2);
        ok = false;
    }

    if (ok) printf("  PASS: select_next_section never returns an out-of-range target\n");
    return ok;
}

// The outro path is the other writer of current_section, and outro_index is unpacked
// unclamped. An out-of-range one used to index arr.sections[] and write past the end of
// bars_since_visit[kMaxSections].
static bool test_advance_section_rejects_out_of_range_outro() {
    bool ok = true;
    const int bad_indices[] = { kMaxSections, kMaxSections + 5, 99 };
    for (int bad : bad_indices) {
        ArrangementParams arr = make_test_arrangement();
        arr.outro_index = bad;

        SectionState state = {};
        state.current_section = 0;
        state.outro_triggered = true;
        state.bars_remaining = 1;  // decremented to 0 -> boundary flip this call
        uint32_t seed = 12345u;
        advance_section(state, arr, seed);

        if (state.current_section < 0 || state.current_section >= arr.section_count) {
            printf("  FAIL: outro_index %d left current_section at %d (section_count=%d)\n",
                   bad, state.current_section, arr.section_count);
            ok = false;
        }
    }
    if (ok) printf("  PASS: advance_section rejects an out-of-range outro_index\n");
    return ok;
}

// ═══════════════════════════════════════════════════════════════════════
// Per-section track density.
//
// density is a pattern-GENERATION input: the generators consume it while BUILDING a
// track's step array and nothing re-reads it while that array plays. So unlike volume
// it cannot ride a per-block atomic — the section boundary is the only place it can
// land. For years these overrides were authored across the vibe catalog and reached
// nothing at all. These two pin the contract that replaced that silence.
// ═══════════════════════════════════════════════════════════════════════

// Two 1-bar sections, A -> B, opening on A, at a tempo where one bar is a round
// number of render blocks.
static void setup_density_rig(OrpheusEngine* engine, GraphUnit* unit) {
    std::memset(unit, 0, sizeof(*unit));
    unit->type = UNIT_PULSAR;
    unit->enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    engine->pulsar_complexity.store(0.0f, std::memory_order_relaxed);  // freeze mutation
    push_two_section_ab_arrangement(engine, 1);
    engine->pulsar_arrangement_intro_index.store(0, std::memory_order_relaxed);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);
}

static int count_gates(const PulsarTrackState& ts) {
    int n = 0;
    const int limit = ts.step_count < kMaxPulsarSteps ? ts.step_count : kMaxPulsarSteps;
    for (int i = 0; i < limit; i++) if (ts.steps[i].gate) n++;
    return n;
}

// density = 0 is what vibe authors write to drop a track for one section. It has to
// silence the track OUTRIGHT: the level generators lay down unconditional primary hits
// (the backbeat on 2 and 4, driving 8ths) that no density value can gate, so thinning
// the pattern is not enough. Leaving the section has to bring the track back.
static bool test_section_density_zero_mutes_track_and_restores_on_exit() {
    printf("\n=== Test: section density 0 takes a track out, exit restores it ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    setup_density_rig(engine, &unit);
    solo_track(engine, 2);   // hat: fixture density 0.80, the busiest track
    // Section 0 drops track 2. Section 1 stays at the -1 no-override sentinel.
    engine->pulsar_section_track_density[0 * kNumPulsarTracks + 2]
        .store(0.0f, std::memory_order_relaxed);
    trigger_vibe_load(engine);
    unit_process_pulsar(&unit, engine, 512, 48000.0f);   // first render runs load_vibe

    PulsarState* ps = engine->pulsar_state;
    if (!ps) {
        printf("  FAIL: pulsar_state was not allocated\n");
        orpheus_engine_destroy(engine);
        return false;
    }

    // Measure per section rather than on block arithmetic: the A/B arrangement loops,
    // so a fixed block count can straddle a boundary and average the two states.
    float rms_muted = 0.0f, rms_restored = 0.0f;
    bool flag_in_a = ps->tracks[2].section_density_out;
    bool flag_in_b = true;
    bool reached_b = false;
    int b_blocks = 0;
    for (int i = 0; i < 3000 && b_blocks < 200; i++) {   // 200 blocks ≈ one bar
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        const float rms = compute_rms(engine->pulsar_out_l, 512);
        if (!reached_b && ps->section_state.current_section == 0) {
            rms_muted = std::fmax(rms_muted, rms);
            flag_in_a = flag_in_a || ps->tracks[2].section_density_out;
        } else if (ps->section_state.current_section == 1) {
            reached_b = true;
            b_blocks++;
            rms_restored = std::fmax(rms_restored, rms);
            flag_in_b = ps->tracks[2].section_density_out;
        }
    }
    if (!reached_b) {
        printf("  FAIL: never advanced into section 1, so the restore is untested\n");
        orpheus_engine_destroy(engine);
        return false;
    }

    // Threshold is calibrated for ONE solo'd hat, not a full mix: the muted section
    // reads exactly 0, so anything clearly above the noise floor proves the restore.
    const bool ok = flag_in_a && !flag_in_b && rms_muted < 1e-6f && rms_restored > 1e-3f;
    printf("  section A: rms=%.6f out_flag=%d (expect 0, 1)\n", rms_muted, flag_in_a);
    printf("  section B: rms=%.6f out_flag=%d (expect >0.001, 0) -- %s\n",
           rms_restored, flag_in_b, ok ? "PASS" : "FAIL");
    if (rms_muted >= 1e-6f)
        printf("  FAIL: track still sounds under a density = 0 override\n");
    if (rms_restored <= 1e-3f)
        printf("  FAIL: track did not come back on section exit\n");
    orpheus_engine_destroy(engine);
    return ok;
}

// A POSITIVE density override has to regenerate the pattern, not just scale it: the
// override is only meaningful if the generator re-runs at the new value. Compares two
// identically-seeded engines that differ only in the override.
static bool test_section_density_regenerates_generative_pattern() {
    printf("\n=== Test: positive section density rebuilds the pattern ===\n");
    int gates[2] = {0, 0};
    for (int run = 0; run < 2; run++) {
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);
        GraphUnit unit;
        setup_density_rig(engine, &unit);
        if (run == 1) {
            // Track 2's fixture density is 0.80; thin it hard for section 0 only.
            engine->pulsar_section_track_density[0 * kNumPulsarTracks + 2]
                .store(0.06f, std::memory_order_relaxed);
        }
        trigger_vibe_load(engine);
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        PulsarState* ps = engine->pulsar_state;
        if (!ps) {
            printf("  FAIL: pulsar_state was not allocated\n");
            orpheus_engine_destroy(engine);
            return false;
        }
        gates[run] = count_gates(ps->tracks[2]);
        orpheus_engine_destroy(engine);
    }
    // The opening section is entered without an advance_section, so this also proves
    // load_vibe applies the override rather than baking the vibe's base density.
    const bool ok = gates[1] < gates[0] && gates[1] >= 0;
    printf("  gates: base density=%d, section override 0.06=%d (expect fewer) -- %s\n",
           gates[0], gates[1], ok ? "PASS" : "FAIL");
    if (!ok)
        printf("  FAIL: the section's density never reached pattern generation\n");
    return ok;
}

// ── Authored progression anchor / drift must survive a section flip ──────────
//
// init_chord_progression clears anchor_bars and drift_range, and
// restart_progression_for_section — called unconditionally at every section
// entry — does not put them back from the atomics afterwards. The unit-level
// chord tests all set cs.anchor_bars / cs.drift_range by hand after init, so
// none of them can see whether a vibe's authored values ever reach the engine.
static bool test_progression_anchor_drift_survive_section_flip() {
    printf("\n=== Test: authored progression anchor/drift survive a section flip ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    setup_jam_arrangement(engine);
    // Two 2-bar sections (bars_min == bars_max draws no RNG) so the flip is prompt
    // and deterministic, with section 0 pinned as the opener.
    constexpr int kSectionStride = kSectionDataFields;
    for (int s = 0; s < 2; s++) {
        const int b = s * kSectionStride;
        engine->pulsar_section_data[b + 0].store(2.0f, std::memory_order_relaxed);
        engine->pulsar_section_data[b + 1].store(2.0f, std::memory_order_relaxed);
        engine->pulsar_section_data[b + 2].store(1.0f, std::memory_order_relaxed);
    }
    engine->pulsar_arrangement_intro_index.store(0, std::memory_order_relaxed);
    engine->pulsar_progression_anchor.store(4, std::memory_order_relaxed);
    engine->pulsar_progression_drift_range.store(0.5f, std::memory_order_relaxed);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);
    engine->pulsar_arrangement_generation.store(2, std::memory_order_release);
    trigger_vibe_load(engine);

    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;

    unit_process_pulsar(&unit, engine, 256, 48000.0f);
    const int   anchor_at_load = engine->pulsar_state->chord_state.anchor_bars;
    const float drift_at_load  = engine->pulsar_state->chord_state.drift_range;

    bool flipped = false;
    for (int i = 0; i < 3000 && !flipped; i++) {
        unit_process_pulsar(&unit, engine, 256, 48000.0f);
        flipped = engine->pulsar_state->section_state.current_section != 0;
    }
    const int   anchor_after = engine->pulsar_state->chord_state.anchor_bars;
    const float drift_after  = engine->pulsar_state->chord_state.drift_range;

    bool ok = true;
    if (!flipped) { printf("  FAIL: no section flip within the run\n"); ok = false; }
    if (anchor_at_load != 4) {
        printf("  FAIL: anchor_bars=%d at load, authored 4\n", anchor_at_load); ok = false; }
    if (std::fabs(drift_at_load - 0.5f) > 1e-4f) {
        printf("  FAIL: drift_range=%.3f at load, authored 0.5\n", drift_at_load); ok = false; }
    if (anchor_after != 4) {
        printf("  FAIL: anchor_bars=%d after the flip, authored 4\n", anchor_after); ok = false; }
    if (std::fabs(drift_after - 0.5f) > 1e-4f) {
        printf("  FAIL: drift_range=%.3f after the flip, authored 0.5\n", drift_after); ok = false; }
    if (ok) printf("  PASS: anchor=%d drift=%.2f survived the flip\n", anchor_after, drift_after);

    orpheus_engine_destroy(engine);
    return ok;
}

// ── Task 7: octave idiom in scale units ─────────────────────────────────────

static bool test_live_lick_octave_jump_is_one_scale() {
    printf("\n=== Test: the octave-jump idiom moves one scale's worth of degrees ===\n");
    int max_abs = 0, big = 0;
    for (uint32_t s = 1; s <= 400; s++) {
        int8_t deg[1] = {0}; float dur[1] = {1.0f}; float vel[1] = {0.8f}; int8_t base[1] = {0};
        uint32_t seed = s;
        mutate_live_lick(deg, dur, vel, 1, 1.0f, seed, base, /*max_degree_drift=*/10, true, /*octave_degrees=*/5);
        int d = std::abs(static_cast<int>(deg[0]));
        if (d > max_abs) max_abs = d;
        if (d >= 3) big++;
    }
    // substitution is +/-2, an octave is +/-5: the sum never exceeds 7, and never 9 (old +/-7 idiom).
    bool pass = max_abs <= 7 && big > 0;
    printf("  max |delta|=%d (want <= 7) jumps seen=%d -- %s\n", max_abs, big, pass ? "PASS" : "FAIL");
    return pass;
}

bool run_pulsar_sections_tests() {
    printf("\n========== PULSAR SECTIONS TESTS ==========\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_section_init());
    tally(test_section_init_with_intro());
    tally(test_section_advance_countdown());
    tally(test_section_transitions_eventually());
    tally(test_section_recency_prevents_immediate_repeat());
    tally(test_section_transition_ramp());
    tally(test_section_macro_interpolation());
    tally(test_progression_anchor_drift_survive_section_flip());
    tally(test_section_progression_override_applied());
    tally(test_section_progression_inheritance_resets_index());
    tally(test_tension_phase_resets_without_override());
    tally(test_tension_override_then_inherit());
    tally(test_initial_section_tension_override_applied_on_load());
    tally(test_master_scratch_freezes_pulsar_clock());
    tally(test_fill_lick_48_steps_renders_and_wraps());
    tally(test_section_macro_crossfade());
    tally(test_section_comping_humanization_override_loads());
    tally(test_section_lick_index_decodes_plus_one());
    tally(test_section_macro_subbar_lerp());
    tally(test_randomize_section_bars_bounds());
    tally(test_select_next_section_never_returns_out_of_range_target());
    tally(test_advance_section_rejects_out_of_range_outro());
    tally(test_select_next_lead_excludes_self_and_drums());
    tally(test_jam_lead_excludes_chordal_only_member());
    tally(test_duck_gate_is_deterministic());
    tally(test_support_duck_is_softened());
    tally(test_slew_toward_monotonic_no_overshoot());
    tally(test_overlap_baton_pass());
    tally(test_drum_lead_gate_and_style());
    tally(test_render_drum_lead_mirrors_lick());
    tally(test_drum_arc_hats_climb_and_climax_fills_the_last_beat());
    tally(test_drum_arc_runs_without_a_lick());
    tally(test_drum_lead_render_leaves_the_caller_seed_alone());
    tally(test_drum_span_progress_lands_the_climax_on_the_last_bar());
    tally(test_live_lick_octave_jump_is_one_scale());
    tally(test_choose_lick_octave_minimizes_leap());
    tally(test_choose_lick_octave_no_prior_soloist());
    tally(test_choose_lick_octave_clamps_to_range());
    tally(test_solo_fire_boost_never_saturates());
    tally(test_articulate_bass_solo());
    tally(test_articulate_bass_solo_idempotent_under_repeats());
    tally(test_articulate_bass_solo_preserves_authored_velocity());
    tally(test_articulate_bass_solo_reports_and_strips_slaps());
    tally(test_midi_lick_degree_roundtrip_and_synth());
    tally(test_synthesize_lick_below_root_renders_notes_not_rests());
    tally(test_generate_jam_solo_line());
    tally(test_jam_carry_preserves_solo_across_section_seam());
    tally(test_no_jam_carry_resets_solo_at_section_seam());
    tally(test_jam_carry_fallbacks());
    tally(test_jam_carry_requires_eligible_lead_into_jam_section());
    tally(test_opening_section_overrides_apply_at_load());
    tally(test_section_density_zero_mutes_track_and_restores_on_exit());
    tally(test_section_density_regenerates_generative_pattern());
    printf("\nPulsar sections tests: %s\n", suite_fail == 0 ? "ALL PASSED" : "SOME FAILED");
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
