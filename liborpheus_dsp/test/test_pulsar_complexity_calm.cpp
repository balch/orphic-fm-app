// Complexity zones for lick variation: steady at 0.3 and below (0 = the lick as written),
// near the original behavior up to 0.9, past the old maximum above it. Dropping a zone
// rebuilds the licks on the next bar.
#include "test_harness.h"   // declares braids/plaits namespaces before orpheus_unit_pulsar.h
#include "test_pulsar_helpers.h"
#include "orpheus_engine.h"
#include "orpheus_unit_pulsar.h"
#include "../src/pulsar_lick_calm.h"
#include <cmath>
#include <cstring>

using lick_calm::Zone;

// ── CALM-1: the curve's anchors, zones, and the original math at variation 1 ──
static bool test_calm_helpers() {
    printf("\n=== Test: CALM-1 variation curve and helpers ===\n");
    auto near = [](float a, float b) { return std::fabs(a - b) < 1e-4f; };
    bool monotonic = true;
    float prev = lick_calm::variation_for(0.0f);
    for (int i = 1; i <= 100; i++) {
        const float v = lick_calm::variation_for(i / 100.0f);
        if (v < prev - 1e-6f) monotonic = false;
        prev = v;
    }
    const bool anchors = lick_calm::variation_for(0.0f) == 0.0f
        && near(lick_calm::variation_for(0.3f), lick_calm::kSteadyMax)
        && near(lick_calm::variation_for(0.9f), 1.0f)
        && near(lick_calm::variation_for(1.0f), lick_calm::kCrazyMax);
    const bool zones = lick_calm::zone_for(0.3f) == Zone::STEADY
        && lick_calm::zone_for(0.31f) == Zone::NORMAL
        && lick_calm::zone_for(0.89f) == Zone::NORMAL
        && lick_calm::zone_for(0.9f) == Zone::CRAZY;

    // Variation 1 in the normal zone reproduces the original spurt expression exactly.
    bool legacy = true;
    for (int i = 0; i <= 10; i++) {
        const float m = i / 10.0f;
        if (lick_calm::effective_mutation(m, false, 1.0f, Zone::NORMAL) != m) legacy = false;
        if (lick_calm::effective_mutation(m, true, 1.0f, Zone::NORMAL) != std::min(1.0f, m * 3.0f))
            legacy = false;
    }
    const bool mutation_math =
        lick_calm::effective_mutation(0.8f, true, 0.0f, Zone::STEADY) == 0.0f
        && near(lick_calm::effective_mutation(0.5f, true, 0.2f, Zone::STEADY), 0.1f)   // no spurt boost
        && near(lick_calm::effective_mutation(0.25f, false, 2.0f, Zone::CRAZY), 0.5f)
        && lick_calm::effective_mutation(0.7f, true, 2.0f, Zone::CRAZY) == 1.0f;       // capped
    const bool drops = lick_calm::dropped_zone(Zone::CRAZY, Zone::NORMAL)
        && lick_calm::dropped_zone(Zone::NORMAL, Zone::STEADY)
        && lick_calm::dropped_zone(Zone::CRAZY, Zone::STEADY)
        && !lick_calm::dropped_zone(Zone::NORMAL, Zone::NORMAL)
        && !lick_calm::dropped_zone(Zone::STEADY, Zone::CRAZY);

    const bool ok = monotonic && anchors && zones && legacy && mutation_math && drops;
    printf("  monotonic=%d anchors=%d zones=%d legacy=%d mutation_math=%d drops=%d -- %s\n",
           monotonic, anchors, zones, legacy, mutation_math, drops, ok ? "PASS" : "FAIL");
    return ok;
}

// Track 3 plays a two-note FILL lick with a wide variation budget, so above the threshold
// ghosts and drift grow fast.
static OrpheusEngine* make_calm_engine(float complexity, float lick_mutation) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    pin_pulsar_rngs(engine);
    engine->pulsar_step_count.store(16, std::memory_order_relaxed);
    engine->pulsar_complexity.store(complexity, std::memory_order_relaxed);
    engine->pulsar_lick_mutation.store(lick_mutation, std::memory_order_relaxed);
    engine->pulsar_track_lick_mode[3].store(static_cast<int>(LickMode::FILL), std::memory_order_relaxed);
    engine->pulsar_track_macros[3].complexity_var_min.store(0.0f, std::memory_order_relaxed);
    engine->pulsar_track_macros[3].complexity_var_max.store(1.0f, std::memory_order_relaxed);
    const int F = OrpheusEngine::kLickFieldsPerStep;
    auto putStep = [&](int step, float deg) {
        const int b = step * F;
        engine->pulsar_lick_pool_data[b + 0] = deg;
        engine->pulsar_lick_pool_data[b + 1] = 0.5f;
        engine->pulsar_lick_pool_data[b + 2] = 0.8f;
        engine->pulsar_lick_pool_data[b + 3] = -1.0f;
    };
    putStep(0, 0.f); putStep(1, 2.f); putStep(2, -1.f); putStep(3, 4.f);
    engine->pulsar_lick_pool_len[0] = 4; engine->pulsar_lick_pool_loop[0] = 4;
    engine->pulsar_lick_anomaly_index = -1;
    engine->pulsar_lick_anomaly_chance = 0.0f;
    engine->pulsar_lick_pool_count.store(1, std::memory_order_release);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(240.0f, std::memory_order_relaxed);
    return engine;
}

static GraphUnit make_unit() {
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    return unit;
}

// Render until `bars` more bar-wraps have run.
static PulsarState* render_bars(OrpheusEngine* engine, GraphUnit& unit, int bars) {
    PulsarState* ps = engine->pulsar_state;
    const int target = (ps ? ps->loop_count : 0) + bars;
    for (int block = 0; block < 200000; block++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        ps = engine->pulsar_state;
        if (ps && ps->loop_count >= target) break;
    }
    return ps;
}

struct LickSnapshot { bool gate[kMaxPulsarSteps]; uint8_t note[kMaxPulsarSteps]; int ghosts; int count; };

static LickSnapshot snapshot(const PulsarTrackState& ts) {
    LickSnapshot s{};
    s.count = ts.step_count;
    for (int i = 0; i < ts.step_count; i++) {
        s.gate[i] = ts.steps[i].gate;
        s.note[i] = ts.steps[i].gate ? ts.steps[i].note : 0;
        if (ts.steps[i].gate && ts.steps[i].ghost) s.ghosts++;
    }
    return s;
}

static int differing_steps(const LickSnapshot& a, const LickSnapshot& b) {
    if (a.count != b.count) return 99;
    int d = 0;
    for (int i = 0; i < a.count; i++)
        if (a.gate[i] != b.gate[i] || a.note[i] != b.note[i]) d++;
    return d;
}

// ── CALM-2: Complexity 0 plays the lick exactly as written, bar after bar ──
// lickMutation 1.0 vs 0.0 on the same seed: at Complexity 0 both must render the same steps,
// and 20 bars of a full variation budget must grow nothing.
static bool test_zero_complexity_plays_authored_lick() {
    printf("\n=== Test: CALM-2 Complexity 0 plays the authored lick ===\n");
    OrpheusEngine* wild = make_calm_engine(0.0f, 1.0f);
    OrpheusEngine* plain = make_calm_engine(0.0f, 0.0f);
    GraphUnit uw = make_unit(), up = make_unit();
    PulsarState* pw = render_bars(wild, uw, 20);
    PulsarState* pp = render_bars(plain, up, 20);
    bool ok = pw && pp;
    int diff = -1, ghosts = -1;
    if (ok) {
        const LickSnapshot w = snapshot(pw->tracks[3]), p = snapshot(pp->tracks[3]);
        diff = differing_steps(w, p);
        ghosts = w.ghosts;
        ok = diff == 0 && ghosts == 0;
    }
    printf("  differing_steps=%d ghosts=%d -- %s\n", diff, ghosts, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(wild);
    orpheus_engine_destroy(plain);
    return ok;
}

// ── CALM-3/4: dropping a zone rebuilds the lick on the next bar ──
// Grow at Complexity 1 for 5 bars (inside the first 8-bar reset window), then turn it down:
// one bar later the ghosts and drift are gone and the steps match the bar-0 render.
static bool snap_case(float drop_to) {
    OrpheusEngine* engine = make_calm_engine(1.0f, 0.0f);
    GraphUnit unit = make_unit();
    unit_process_pulsar(&unit, engine, 512, 48000.0f);   // vibe load
    PulsarState* ps = engine->pulsar_state;
    if (!ps) { orpheus_engine_destroy(engine); return false; }
    const LickSnapshot authored = snapshot(ps->tracks[3]);

    ps = render_bars(engine, unit, 5);
    const LickSnapshot grown = snapshot(ps->tracks[3]);
    const int grown_diff = differing_steps(grown, authored);

    engine->pulsar_complexity.store(drop_to, std::memory_order_relaxed);
    ps = render_bars(engine, unit, 1);
    const LickSnapshot snapped = snapshot(ps->tracks[3]);
    const int snapped_diff = differing_steps(snapped, authored);

    const bool ok = grown_diff > 0 && snapped_diff == 0 && snapped.ghosts == 0;
    printf("  drop_to=%.1f grown_diff=%d (needs > 0) snapped_diff=%d ghosts=%d -- %s\n",
           drop_to, grown_diff, snapped_diff, snapped.ghosts, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

static bool test_crazy_to_steady_snaps_back() {
    printf("\n=== Test: CALM-3 crazy -> steady snaps the lick back ===\n");
    return snap_case(0.0f);
}

static bool test_crazy_to_normal_snaps_back() {
    printf("\n=== Test: CALM-4 crazy -> normal snaps the lick back ===\n");
    return snap_case(0.6f);
}

// ── CALM-5: the crazy zone grows more than the old maximum did ──
// Variation is 1 at Complexity 0.9 (the old maximum) and 2 at 1.0. The ghost rolls hash the
// step and bar, so the doubled threshold ghosts a superset: strictly more on average.
static double avg_ghosts_at(float complexity) {
    OrpheusEngine* engine = make_calm_engine(complexity, 0.0f);
    GraphUnit unit = make_unit();
    long sum = 0;
    int bars = 0;
    for (; bars < 40; bars++) {
        PulsarState* ps = render_bars(engine, unit, 1);
        if (!ps) break;
        sum += snapshot(ps->tracks[3]).ghosts;
    }
    orpheus_engine_destroy(engine);
    return bars > 0 ? static_cast<double>(sum) / bars : 0.0;
}

static bool test_crazy_exceeds_old_max() {
    printf("\n=== Test: CALM-5 Complexity 1.0 grows more than the old maximum ===\n");
    const double old_max = avg_ghosts_at(0.9f);
    const double crazy = avg_ghosts_at(1.0f);
    const bool ok = old_max > 0.0 && crazy > old_max;
    printf("  avg ghosts: 0.9=%.3f 1.0=%.3f -- %s\n", old_max, crazy, ok ? "PASS" : "FAIL");
    return ok;
}

bool run_pulsar_complexity_calm_tests() {
    printf("\n=== Pulsar Complexity Calm Tests ===\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_calm_helpers());
    tally(test_zero_complexity_plays_authored_lick());
    tally(test_crazy_to_steady_snaps_back());
    tally(test_crazy_to_normal_snaps_back());
    tally(test_crazy_exceeds_old_max());
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
