// Lick growth on lick-driven tracks: ghosts surviving a figure re-render, and the pitch
// they land on. The pitch rule itself (ghost_pitch_source) is pinned by the ghost pitch suite.
#include "test_harness.h"   // declares braids/plaits namespaces before orpheus_unit_pulsar.h
#include "test_pulsar_helpers.h"
#include "orpheus_engine.h"
#include "orpheus_unit_pulsar.h"
#include "../src/pulsar_lick_growth.h"
#include <cmath>
#include <cstring>
#include <vector>

static bool test_carry_ghosts_fills_empty_steps_only() {
    printf("\n=== Test: carry puts old ghosts on empty steps only, pitched from the new figure ===\n");
    PulsarStep before[8] = {};
    before[3].gate = true; before[3].ghost = true; before[3].velocity = 0.25f; before[3].duration = 0.2f;
    before[6].gate = true; before[6].ghost = true; before[6].velocity = 0.20f; before[6].duration = 0.2f;
    before[1].gate = true; before[1].note = 40;                 // a figure note or a drift, never carried
    PulsarStep after[8] = {};
    after[0].gate = true; after[0].note = 64; after[0].raw_note = 64;
    after[6].gate = true; after[6].note = 67; after[6].raw_note = 67;   // sounding: must survive
    const int carried = lick_growth::carry_ghosts(before, 8, after, 8);
    const bool ok = carried == 1
        && after[3].gate && after[3].ghost && after[3].note == 64
        && std::fabs(after[3].velocity - 0.25f) < 1e-6f
        && !after[6].ghost && after[6].note == 67
        && !after[1].gate;
    printf("  carried=%d step3 note=%d ghost=%d step6 ghost=%d -- %s\n",
           carried, after[3].note, (int)after[3].ghost, (int)after[6].ghost, ok ? "PASS" : "FAIL");
    return ok;
}

// Two sections, one loop-cycle each, pinning pool slots 0 and 1: every wrap swaps track 3's figure.
static void push_two_pinned_sections(OrpheusEngine* engine) {
    engine->pulsar_arrangement_active.store(1, std::memory_order_relaxed);
    engine->pulsar_arrangement_section_count.store(2, std::memory_order_relaxed);
    engine->pulsar_arrangement_intro_index.store(0, std::memory_order_relaxed);
    engine->pulsar_arrangement_outro_index.store(-1, std::memory_order_relaxed);
    std::vector<float> sd(kMaxSections * kSectionDataFields, 0.0f);
    for (int s = 0; s < kMaxSections; s++) {
        const int b = s * kSectionDataFields;
        sd[b + 5] = sd[b + 6] = sd[b + 7] = sd[b + 8] = -1.0f;   // no macro overrides
        sd[b + 18] = sd[b + 19] = sd[b + 20] = -1.0f;            // no comping overrides
    }
    for (int s = 0; s < 2; s++) {
        const int b = s * kSectionDataFields;
        sd[b + 0] = 1.0f; sd[b + 1] = 1.0f;   // one loop-cycle
        sd[b + 2] = 1.0f;                     // bar_step
        sd[b + 3] = 1.0f;                     // recency_decay
        sd[b + 4] = 1.0f;                     // one outgoing edge
        sd[b + 26] = static_cast<float>(s + 1);   // pin pool slot s (wire is slot + 1)
    }
    for (int i = 0; i < kMaxSections * kSectionDataFields; i++)
        engine->pulsar_section_data[i].store(sd[i], std::memory_order_relaxed);
    std::vector<float> tr(kMaxSections * kMaxSectionTransitions * 3, 0.0f);
    tr[0] = 1.0f; tr[1] = 1.0f;                                   // 0 -> 1
    const int s1 = kMaxSectionTransitions * 3;
    tr[s1 + 0] = 0.0f; tr[s1 + 1] = 1.0f;                         // 1 -> 0
    for (int i = 0; i < kMaxSections * kMaxSectionTransitions * 3; i++)
        engine->pulsar_section_transitions[i].store(tr[i], std::memory_order_relaxed);
    engine->pulsar_arrangement_generation.store(1, std::memory_order_release);
}

static OrpheusEngine* make_lick_growth_engine(float carry) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    engine->pulsar_complexity.store(0.0f, std::memory_order_relaxed);   // no runtime ghosts or drift
    engine->pulsar_track_lick_mode[3].store(static_cast<int>(LickMode::FILL), std::memory_order_relaxed);
    const int F = OrpheusEngine::kLickFieldsPerStep, S = OrpheusEngine::kMaxLickSteps;
    auto putStep = [&](int slot, int step, float deg) {
        const int b = slot * (S * F) + step * F;
        engine->pulsar_lick_pool_data[b + 0] = deg;
        engine->pulsar_lick_pool_data[b + 1] = 0.5f;
        engine->pulsar_lick_pool_data[b + 2] = 0.8f;
        engine->pulsar_lick_pool_data[b + 3] = -1.0f;
    };
    putStep(0, 0, 0.f); putStep(0, 1, 1.f);
    putStep(1, 0, 4.f); putStep(1, 1, 5.f);
    engine->pulsar_lick_pool_len[0] = 2; engine->pulsar_lick_pool_loop[0] = 4;
    engine->pulsar_lick_pool_len[1] = 2; engine->pulsar_lick_pool_loop[1] = 4;
    engine->pulsar_lick_anomaly_index = -1;
    engine->pulsar_lick_anomaly_chance = 0.0f;
    engine->pulsar_lick_carry_growth = carry;
    engine->pulsar_lick_pool_count.store(2, std::memory_order_release);
    push_two_pinned_sections(engine);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);
    return engine;
}

// A hand-placed ghost in track 3's silent gap, then render until the figure swaps.
static bool run_carry_case(float carry, bool expect_carried) {
    OrpheusEngine* engine = make_lick_growth_engine(carry);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    unit_process_pulsar(&unit, engine, 512, 48000.0f);   // vibe load
    PulsarState* ps = engine->pulsar_state;
    if (!ps) { orpheus_engine_destroy(engine); return false; }
    const bool setup_ok = !ps->tracks[3].steps[10].gate;   // step 10 is past the 4-step figure
    PulsarStep& g = ps->tracks[3].steps[10];
    g.gate = true; g.ghost = true; g.velocity = 0.2f; g.duration = 0.2f; g.note = 0; g.raw_note = 0;
    const int from = ps->current_lick_index;
    bool swapped = false;
    for (int block = 0; block < 2000 && !swapped; block++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        ps = engine->pulsar_state;
        swapped = ps && ps->current_lick_index != from;
    }
    const PulsarTrackState& t3 = ps->tracks[3];
    const bool carried = t3.steps[10].gate && t3.steps[10].ghost;
    const int src = ghost_pitch_source(t3.steps, t3.step_count, 10);
    const bool pitch_ok = !carried || (src >= 0 && t3.steps[10].note == t3.steps[src].note);
    const bool ok = setup_ok && swapped && carried == expect_carried && pitch_ok;
    printf("  carry=%.0f setup=%d swapped=%d carried=%d pitch_ok=%d -- %s\n",
           carry, (int)setup_ok, (int)swapped, (int)carried, (int)pitch_ok, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_figure_swap_carries_ghosts_when_enabled() {
    printf("\n=== Test: carryGrowth on keeps a gap ghost through a pool slot swap ===\n");
    return run_carry_case(1.0f, true);
}

static bool test_figure_swap_drops_ghosts_by_default() {
    printf("\n=== Test: carryGrowth off (default) re-renders the gap empty, as before ===\n");
    return run_carry_case(0.0f, false);
}

// ── B1: mutate_patterns growing REAL ghosts (not hand-placed) must take the
// figure's pitch, per apply_figure_pitch. Track 3's authored complexity-
// variation budget defaults to (0, 0.3): widen it to (0, 1) so Complexity=1
// reliably rolls ghosts, instead of relying on the ~2.4%/step default rate.

// A safe render window: the déjà-vu reset fires every max(8, 32*(1-complexity))
// loops, which is 8 at Complexity=1 (see orpheus_unit_pulsar.cpp's reset_interval).
// 6 bar-wraps leaves margin and is well past the first ghost this fixture grows
// (observed at loop 1, deterministically -- step_hash has no RNG dependency).
static constexpr int kGrowthRenderBars = 6;

static OrpheusEngine* make_growth_pitch_engine(float carry, int pool_count) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    engine->pulsar_complexity.store(1.0f, std::memory_order_relaxed);   // ghosts actually grow
    engine->pulsar_track_lick_mode[3].store(static_cast<int>(LickMode::FILL), std::memory_order_relaxed);
    engine->pulsar_track_macros[3].complexity_var_min.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_track_macros[3].complexity_var_max.store(1.0f, std::memory_order_relaxed);
    const int F = OrpheusEngine::kLickFieldsPerStep, S = OrpheusEngine::kMaxLickSteps;
    auto putStep = [&](int slot, int step, float deg) {
        const int b = slot * (S * F) + step * F;
        engine->pulsar_lick_pool_data[b + 0] = deg;
        engine->pulsar_lick_pool_data[b + 1] = 0.5f;
        engine->pulsar_lick_pool_data[b + 2] = 0.8f;
        engine->pulsar_lick_pool_data[b + 3] = -1.0f;
    };
    putStep(0, 0, 0.f); putStep(0, 1, 1.f);
    engine->pulsar_lick_pool_len[0] = 2; engine->pulsar_lick_pool_loop[0] = 4;
    if (pool_count > 1) {
        // Second slot: distinct scale degrees, so a swap's new figure is audibly
        // a different pitch set from the first (matches run_carry_case's fixture).
        putStep(1, 0, 4.f); putStep(1, 1, 5.f);
        engine->pulsar_lick_pool_len[1] = 2; engine->pulsar_lick_pool_loop[1] = 4;
    }
    engine->pulsar_lick_anomaly_index = -1;
    engine->pulsar_lick_anomaly_chance = 0.0f;
    engine->pulsar_lick_carry_growth = carry;
    engine->pulsar_lick_pool_count.store(pool_count, std::memory_order_release);
    if (pool_count > 1) push_two_pinned_sections(engine);   // 1-bar sections: swaps every wrap
    trigger_vibe_load(engine);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);
    return engine;
}

// Render bar by bar until track 3 shows its first ghost, or `max_loops` bar-
// wraps pass with none. Checking the FIRST ghost (rather than accumulating
// several bars and checking all current ghosts at the end) matters: mutate_
// patterns' note-drift pass touches every gated step, ghosts included, on
// later bars, so an older ghost can drift away from its source after the bar
// it grew on. Only a freshly-grown ghost, checked immediately, isolates
// apply_figure_pitch from that unrelated drift.
static bool render_until_first_ghost(OrpheusEngine* engine, GraphUnit& unit, int max_loops) {
    for (int block = 0; block < 20000; block++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        PulsarState* ps = engine->pulsar_state;
        if (!ps || ps->loop_count > max_loops) return false;
        const PulsarTrackState& t3 = ps->tracks[3];
        for (int s = 0; s < t3.step_count; s++) {
            if (t3.steps[s].gate && t3.steps[s].ghost) return true;
        }
    }
    return false;
}

static bool test_growth_pitch_matches_figure_source_when_carry_on() {
    printf("\n=== Test: a grown ghost takes the figure's pitch when carryGrowth is on ===\n");
    OrpheusEngine* engine = make_growth_pitch_engine(1.0f, 1);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    unit_process_pulsar(&unit, engine, 512, 48000.0f);   // vibe load
    const bool grew = render_until_first_ghost(engine, unit, kGrowthRenderBars);
    PulsarState* ps = engine->pulsar_state;
    int ghost_count = 0;
    bool pitch_ok = true;
    if (grew && ps) {
        const PulsarTrackState& t3 = ps->tracks[3];
        for (int s = 0; s < t3.step_count; s++) {
            if (!t3.steps[s].gate || !t3.steps[s].ghost) continue;
            ghost_count++;
            const int src = ghost_pitch_source(t3.steps, t3.step_count, s);
            if (src < 0 || t3.steps[s].note != t3.steps[src].note) pitch_ok = false;
        }
    }
    const bool ok = grew && ghost_count > 0 && pitch_ok;
    printf("  grew=%d loop=%d ghost_count=%d pitch_ok=%d -- %s\n",
           (int)grew, ps ? ps->loop_count : -1, ghost_count, (int)pitch_ok, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// carryGrowth gates the CARRY across a figure swap, not the pitch: since the ghost pitch
// fix, every grown ghost borrows a written pitch whatever this flag says.
static bool test_growth_pitch_borrows_when_carry_off() {
    printf("\n=== Test: a grown ghost takes the figure's pitch with carryGrowth off too ===\n");
    OrpheusEngine* engine = make_growth_pitch_engine(0.0f, 1);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    unit_process_pulsar(&unit, engine, 512, 48000.0f);   // vibe load
    const bool grew = render_until_first_ghost(engine, unit, kGrowthRenderBars);
    PulsarState* ps = engine->pulsar_state;
    int ghost_count = 0;
    bool pitch_ok = true;
    if (grew && ps) {
        const PulsarTrackState& t3 = ps->tracks[3];
        for (int s = 0; s < t3.step_count; s++) {
            if (!t3.steps[s].gate || !t3.steps[s].ghost) continue;
            ghost_count++;
            const int src = ghost_pitch_source(t3.steps, t3.step_count, s);
            if (src < 0 || t3.steps[s].note != t3.steps[src].note) pitch_ok = false;
        }
    }
    const bool ok = grew && ghost_count > 0 && pitch_ok;
    printf("  grew=%d loop=%d ghost_count=%d pitch_ok=%d -- %s\n",
           (int)grew, ps ? ps->loop_count : -1, ghost_count, (int)pitch_ok, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Two pinned 1-bar sections swap track 3's lick on every wrap, so the same
// bar that grows a ghost (mutate_patterns) also swaps and carries it
// (regenerate_lick_tracks -> carry_ghosts), re-pitching it from the NEW
// figure. Confirms carry_ghosts' second pass, not just apply_figure_pitch.
static bool test_growth_pitch_survives_pool_swap_when_carry_on() {
    printf("\n=== Test: a grown ghost is carried onto the new figure and re-pitched from it ===\n");
    OrpheusEngine* engine = make_growth_pitch_engine(1.0f, 2);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    unit_process_pulsar(&unit, engine, 512, 48000.0f);   // vibe load
    PulsarState* ps = engine->pulsar_state;
    if (!ps) { orpheus_engine_destroy(engine); return false; }
    const int from = ps->current_lick_index;
    bool swapped = false;
    for (int block = 0; block < 2000 && !swapped && ps->loop_count <= kGrowthRenderBars; block++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        ps = engine->pulsar_state;
        swapped = ps && ps->current_lick_index != from;
    }
    bool carried_ok = false;
    bool pitch_ok = true;
    if (ps) {
        const PulsarTrackState& t3 = ps->tracks[3];
        for (int s = 0; s < t3.step_count; s++) {
            if (!t3.steps[s].gate || !t3.steps[s].ghost) continue;
            carried_ok = true;
            const int src = ghost_pitch_source(t3.steps, t3.step_count, s);
            if (src < 0 || t3.steps[s].note != t3.steps[src].note) pitch_ok = false;
        }
    }
    const bool ok = swapped && carried_ok && pitch_ok;
    printf("  swapped=%d loop=%d carried_ok=%d pitch_ok=%d -- %s\n",
           (int)swapped, ps ? ps->loop_count : -1, (int)carried_ok, (int)pitch_ok, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

bool run_pulsar_lick_growth_tests() {
    printf("\n=== Pulsar Lick Growth Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_carry_ghosts_fills_empty_steps_only());
    tally(test_figure_swap_carries_ghosts_when_enabled());
    tally(test_figure_swap_drops_ghosts_by_default());
    tally(test_growth_pitch_matches_figure_source_when_carry_on());
    tally(test_growth_pitch_borrows_when_carry_off());
    tally(test_growth_pitch_survives_pool_swap_when_carry_on());
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
