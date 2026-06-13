// Pulsar per-track complexity cap (Task 1) — behavioral contracts.
//
// Root cause being guarded against: the single global `complexity` macro
// fanned out to several variation consumers in mutate_patterns() that used
// RAW complexity with hardcoded coefficients and IGNORED the per-track
// variation budget (PulsarTrackState.macro_map.complexity_variation). The fix
// routes ghost/drift/markov through lerp_macro(complexity, complexity_variation),
// raises the step-count-mutation gate, skips percussive tracks entirely for
// step-count mutation, removes the +/-2 step jump, and raises the deja-vu reset
// floor from 4 to 8.
//
// These tests verify the OBSERVABLE contracts through the public engine API.

#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include "../src/orpheus_graph.h"
#include <cstdio>
#include <cmath>
#include <cstring>
#include <vector>

namespace {

// Configure complexity_variation budgets matching the audit's role presets:
// RHYTHM 0.15, MELODIC 0.30, EFFECT 0.25, WILD 0.60 (from MacroTarget.kt).
// NOTE: two independent axes here. The TrackRole enum (0=PERC, 1=MELODIC) only
// gates step-count mutation (CAP-1/2). The complexity_variation *budget* is what
// ghost/drift/markov consult (CAP-4). Tracks 3-7 are all MELODIC role (non-perc,
// so step-count is free to mutate) but carry different variation budgets so CAP-4
// can compare a RHYTHM-budget track (3) against a WILD-budget track (7).
void setup_role_aware_macros(OrpheusEngine* engine) {
    //                  t: 0     1     2  | 3      4      5      6      7
    int role[8] = {0, 0, 0, 1, 1, 1, 1, 1};      // 0-2 PERC, 3-7 MELODIC
    float var_max[8] = {0.15f, 0.15f, 0.15f,     // RHYTHM budget
                        0.30f, 0.30f,            // MELODIC budget
                        0.25f, 0.25f,            // EFFECT budget
                        0.60f};                  // WILD budget
    for (int t = 0; t < 8; t++) {
        engine->pulsar_track_role[t].store(role[t], std::memory_order_relaxed);
        engine->pulsar_track_macros[t].complexity_var_min.store(0.0f, std::memory_order_relaxed);
        engine->pulsar_track_macros[t].complexity_var_max.store(var_max[t], std::memory_order_relaxed);
    }
}

// Advance the Pulsar unit until it has completed at least `target_loops` bars,
// invoking `on_bar` once per new loop (after the per-bar mutate). Returns the
// number of bars observed.
template <typename Fn>
int advance_bars(OrpheusEngine* engine, GraphUnit& unit, int target_loops, Fn on_bar) {
    int last_loop = -1;
    int bars_seen = 0;
    for (int i = 0; i < 200000 && bars_seen < target_loops; i++) {
        unit_process_pulsar(&unit, engine, 256, 48000.0f);
        PulsarState* ps = engine->pulsar_state;
        if (!ps) continue;
        if (ps->loop_count != last_loop) {
            last_loop = ps->loop_count;
            bars_seen++;
            on_bar(ps);
        }
    }
    return bars_seen;
}

GraphUnit make_pulsar_unit() {
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;
    return unit;
}

}  // namespace

// ── CAP-1: percussive track step_count is immutable at max complexity ──
//
// Step-count mutation must skip percussive tracks (phrase-lock) AND the gate is
// raised to >0.85, so even at complexity=1.0 the drum tracks' step_count must
// never change over a long run.
static bool test_percussive_step_count_locked_at_max_complexity() {
    printf("\n=== Test: CAP-1 percussive step_count locked at complexity=1.0 ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit = make_pulsar_unit();

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_energy.store(0.8f, std::memory_order_relaxed);
    engine->pulsar_complexity.store(1.0f, std::memory_order_relaxed);  // MAX
    engine->pulsar_space.store(0.5f, std::memory_order_relaxed);
    engine->pulsar_mood.store(0.5f, std::memory_order_relaxed);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);
    setup_cosmic_techno(engine);
    setup_role_aware_macros(engine);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    trigger_vibe_load(engine);

    // Prime one bar to load the vibe, then snapshot percussive step counts.
    int initial_perc[8];
    bool primed = false;
    int min_perc_step = 999, max_perc_step = 0;
    bool any_perc_changed = false;

    advance_bars(engine, unit, 80, [&](PulsarState* ps) {
        if (!primed) {
            for (int t = 0; t < 8; t++) {
                if (ps->tracks[t].role == TrackRole::PERCUSSIVE)
                    initial_perc[t] = ps->tracks[t].step_count;
                else
                    initial_perc[t] = -1;  // not tracked
            }
            primed = true;
            return;
        }
        for (int t = 0; t < 8; t++) {
            if (ps->tracks[t].role != TrackRole::PERCUSSIVE) continue;
            int sc = ps->tracks[t].step_count;
            if (sc != initial_perc[t]) any_perc_changed = true;
            if (sc < min_perc_step) min_perc_step = sc;
            if (sc > max_perc_step) max_perc_step = sc;
        }
    });

    printf("  percussive step_count over run: min=%d max=%d changed=%s\n",
           min_perc_step, max_perc_step, any_perc_changed ? "YES" : "NO");

    bool pass = primed && !any_perc_changed;
    printf("  CAP-1: %s\n", pass ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass;
}

// ── CAP-2: no step_count mutation in the (0.7, 0.85] complexity band ──
//
// The gate was raised from >0.7 to >0.85, so at complexity=0.8 the step-count
// mutation must be fully disabled — NO track's step_count may change.
static bool test_no_step_count_mutation_at_0_8() {
    printf("\n=== Test: CAP-2 no step_count mutation at complexity=0.8 ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit = make_pulsar_unit();

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_energy.store(0.7f, std::memory_order_relaxed);
    engine->pulsar_complexity.store(0.8f, std::memory_order_relaxed);  // in (0.7, 0.85]
    engine->pulsar_space.store(0.5f, std::memory_order_relaxed);
    engine->pulsar_mood.store(0.5f, std::memory_order_relaxed);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);
    setup_cosmic_techno(engine);
    setup_role_aware_macros(engine);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    trigger_vibe_load(engine);

    int initial[8];
    bool primed = false;
    bool any_changed = false;
    int changed_track = -1;

    advance_bars(engine, unit, 120, [&](PulsarState* ps) {
        if (!primed) {
            for (int t = 0; t < 8; t++) initial[t] = ps->tracks[t].step_count;
            primed = true;
            return;
        }
        for (int t = 0; t < 8; t++) {
            if (ps->tracks[t].step_count != initial[t]) {
                any_changed = true;
                if (changed_track < 0) changed_track = t;
            }
        }
    });

    if (any_changed)
        printf("  FAIL: track %d step_count changed at complexity=0.8\n", changed_track);
    else
        printf("  no track step_count changed over 120 bars at complexity=0.8\n");

    bool pass = primed && !any_changed;
    printf("  CAP-2: %s\n", pass ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass;
}

// ── CAP-3: deja-vu reset interval is EXACTLY 8 at max complexity ──
//
// reset_interval = max(8, 32*(1-complexity)). At complexity=1.0 the floor wins
// and the interval is exactly 8. loops_since_reset counts up 0..interval-1 then
// resets to 0, so its peak is interval-1 == 7. These tests install no
// arrangement (setup_jam_arrangement is never called), so the section
// macro-override path is skipped and complexity stays a constant 1.0 for the
// whole run — there is no ramp transient that could push the interval above 8.
// We therefore assert the peak is EXACTLY 7, which is two-sided: the OLD floor
// of 4 caps the peak at 3 (fails low), and any regression that inflates the
// floor (e.g. max(80, ...)) pushes the peak well above 7 (fails high).
static bool test_dejavu_reset_floor_at_least_8() {
    printf("\n=== Test: CAP-3 deja-vu reset interval == 8 at complexity=1.0 ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit = make_pulsar_unit();

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_energy.store(0.8f, std::memory_order_relaxed);
    engine->pulsar_complexity.store(1.0f, std::memory_order_relaxed);  // floor case
    engine->pulsar_space.store(0.5f, std::memory_order_relaxed);
    engine->pulsar_mood.store(0.5f, std::memory_order_relaxed);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);
    setup_cosmic_techno(engine);
    setup_role_aware_macros(engine);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    trigger_vibe_load(engine);

    int max_loops_since_reset = 0;
    advance_bars(engine, unit, 60, [&](PulsarState* ps) {
        if (ps->loops_since_reset > max_loops_since_reset)
            max_loops_since_reset = ps->loops_since_reset;
    });

    // With interval == 8, loops_since_reset reaches exactly 7 before resetting.
    // Old floor (4) would have capped it at 3; an inflated floor exceeds 7.
    printf("  max loops_since_reset observed = %d (expect exactly 7 for interval=8)\n",
           max_loops_since_reset);

    bool pass = (max_loops_since_reset == 7);
    printf("  CAP-3: %s\n", pass ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return pass;
}

// ── CAP-4: a wider per-track variation budget yields strictly more ghost
//          activity than a tight one, all else equal ──
//
// Ghost-note activation scales as ghost_prob = track_var * 0.08, where
// track_var = lerp_macro(complexity, {0, var_max}). At complexity=1.0,
// track_var == var_max, so a WILD budget (0.60) gives 4x the ghost rate of a
// RHYTHM budget (0.15).
//
// We make the budget the SOLE variable by running the SAME probe track (index 5,
// MELODIC so role gating never skips it) twice, with a FIXED non-zero seed so
// the base pattern, loop progression, and deja-vu regen are byte-identical
// between the two runs. The probe's genre density is forced sparse so there are
// always inactive steps for ghosts to activate.
//
// The ghost roll is step_hash(s, t, loop_count) — independent of the budget — so
// a wider budget only RAISES the activation threshold. Every step the tight
// budget activates, the wide budget activates no later (superset), and the wide
// budget activates strictly more whenever a roll lands in the gap between the
// two thresholds. So the wide run's average active-gate count must be strictly
// greater. Crucially, if the fix regresses (ghost_prob reverts to raw
// complexity, ignoring the budget) the two runs become byte-identical and the
// averages are EQUAL — the strict inequality below then fails. No tautological
// "inconclusive => pass" escape hatch, and nothing depends on wall-clock state.
static double cap4_avg_active_gates_for_budget(float probe_var_max) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit = make_pulsar_unit();
    constexpr int kProbe = 5;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_energy.store(0.6f, std::memory_order_relaxed);
    engine->pulsar_complexity.store(1.0f, std::memory_order_relaxed);
    engine->pulsar_space.store(0.5f, std::memory_order_relaxed);
    engine->pulsar_mood.store(0.5f, std::memory_order_relaxed);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);
    setup_cosmic_techno(engine);
    setup_role_aware_macros(engine);

    // Probe: MELODIC role, sparse base pattern (plenty of inactive steps for
    // ghosts), and the variation budget under test. Everything else is held
    // identical across the two calls; only complexity_var_max differs.
    engine->pulsar_track_role[kProbe].store(1, std::memory_order_relaxed);  // MELODIC
    engine->pulsar_genre_density[kProbe].store(0.15f, std::memory_order_relaxed);
    engine->pulsar_track_macros[kProbe].complexity_var_min.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_track_macros[kProbe].complexity_var_max.store(probe_var_max, std::memory_order_relaxed);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    engine->pulsar_seed.store(0x00C0FFEE, std::memory_order_relaxed);  // FIXED => deterministic
    trigger_vibe_load(engine);

    long sum_gates = 0;
    int n = 0;
    advance_bars(engine, unit, 200, [&](PulsarState* ps) {
        int gates = 0;
        for (int s = 0; s < ps->tracks[kProbe].step_count; s++)
            if (ps->tracks[kProbe].steps[s].gate) gates++;
        sum_gates += gates;
        n++;
    });

    orpheus_engine_destroy(engine);
    return n > 0 ? static_cast<double>(sum_gates) / n : 0.0;
}

static bool test_rhythm_budget_lower_variation_than_wild() {
    printf("\n=== Test: CAP-4 wider variation budget => strictly more ghost activity ===\n");

    double rhythm = cap4_avg_active_gates_for_budget(0.15f);  // tight RHYTHM budget
    double wild   = cap4_avg_active_gates_for_budget(0.60f);  // wide WILD budget (4x)

    printf("  RHYTHM-budget(0.15) avg active gates = %.4f\n", rhythm);
    printf("  WILD-budget  (0.60) avg active gates = %.4f\n", wild);
    printf("  delta (wild - rhythm) = %.4f (must be > 0; a budget-ignoring "
           "regression makes it exactly 0)\n", wild - rhythm);

    bool pass = (wild > rhythm);
    printf("  CAP-4: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

bool run_pulsar_complexity_cap_tests() {
    printf("\n========== PULSAR COMPLEXITY CAP TESTS ==========\n");
    int pass = 0, fail = 0;

    if (test_percussive_step_count_locked_at_max_complexity()) pass++; else fail++;
    if (test_no_step_count_mutation_at_0_8())                  pass++; else fail++;
    if (test_dejavu_reset_floor_at_least_8())                  pass++; else fail++;
    if (test_rhythm_budget_lower_variation_than_wild())        pass++; else fail++;

    printf("\nComplexity-cap tests: %d passed, %d failed\n", pass, fail);
    TEST_SUITE_RETURN(pass, fail);
}
