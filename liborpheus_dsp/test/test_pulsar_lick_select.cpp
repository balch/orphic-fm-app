#include "test_harness.h"
#include "pulsar_lick_select.h"
#include <cstdio>

static bool test_rotation_always_in_range() {
    printf("\n=== Test: lick_pick_rotation stays in [0, pool_count) ===\n");
    bool ok = true;
    uint32_t rng = 0x1234567u;
    for (int i = 0; i < 100000; i++) {
        int idx = lick_pick_rotation(rng, 3);
        if (idx < 0 || idx >= 3) { ok = false; break; }
    }
    printf("  %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_rotation_single_member_is_zero() {
    printf("\n=== Test: pool_count==1 always returns 0 ===\n");
    uint32_t rng = 42u;
    uint32_t before = rng;
    bool ok = (lick_pick_rotation(rng, 1) == 0) && (rng == before);  // no RNG consumed
    printf("  %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_rotation_covers_both_members() {
    printf("\n=== Test: 2-member pool eventually picks both ===\n");
    uint32_t rng = 99u;
    bool saw0 = false, saw1 = false;
    for (int i = 0; i < 1000 && !(saw0 && saw1); i++) {
        int idx = lick_pick_rotation(rng, 2);
        if (idx == 0) saw0 = true; else if (idx == 1) saw1 = true;
    }
    bool ok = saw0 && saw1;
    printf("  saw0=%d saw1=%d -- %s\n", saw0, saw1, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_anomaly_threshold() {
    printf("\n=== Test: lick_roll_anomaly threshold at 0 / 1 / 0.5 ===\n");
    uint32_t rng = 7u;
    bool ok = true;
    for (int i = 0; i < 1000; i++) if (lick_roll_anomaly(rng, 0.0f)) { ok = false; break; }  // never
    rng = 7u;
    for (int i = 0; i < 1000; i++) if (!lick_roll_anomaly(rng, 1.0f)) { ok = false; break; } // always
    rng = 7u; int hits = 0;
    for (int i = 0; i < 100000; i++) if (lick_roll_anomaly(rng, 0.5f)) hits++;
    float frac = hits / 100000.0f;
    if (frac < 0.45f || frac > 0.55f) ok = false;  // ~half
    printf("  half-fraction=%.3f -- %s\n", frac, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_determinism_same_seed() {
    printf("\n=== Test: same seed reproduces the same picks ===\n");
    uint32_t a = 555u, b = 555u;
    bool ok = true;
    for (int i = 0; i < 100; i++) {
        if (lick_pick_rotation(a, 3) != lick_pick_rotation(b, 3)) { ok = false; break; }
    }
    printf("  %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

// lick_resolve_desired: re-rolls rotation only on section change; anomaly overrides.
static bool test_resolve_reroll_only_on_section_change() {
    printf("\n=== Test: resolve re-rolls rotation only on section change (anomaly off) ===\n");
    uint32_t rng = 0xBEEFu;
    int active = 0;
    // section_changed=false, anomaly_index=-1 -> returns active unchanged, no RNG used.
    uint32_t before = rng;
    int d0 = lick_resolve_desired(rng, /*section_changed=*/false, 2, -1, 0.0f, active);
    bool ok = (d0 == active) && (rng == before);
    // Across many section changes, both rotation members should appear.
    bool saw0 = false, saw1 = false;
    for (int i = 0; i < 1000 && !(saw0 && saw1); i++) {
        int d = lick_resolve_desired(rng, true, 2, -1, 0.0f, active);
        if (d == 0) saw0 = true; else if (d == 1) saw1 = true;
    }
    ok = ok && saw0 && saw1;
    printf("  saw0=%d saw1=%d -- %s\n", saw0, saw1, ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_resolve_anomaly_overrides_rotation() {
    printf("\n=== Test: anomaly (chance=1) overrides the rotation pick ===\n");
    uint32_t rng = 0x1111u;
    int active = 0;
    bool ok = true;
    for (int i = 0; i < 1000; i++) {  // anomaly slot 2, chance 1 -> always 2
        int d = lick_resolve_desired(rng, /*section_changed=*/(i % 3 == 0), 2, 2, 1.0f, active);
        if (d != 2) { ok = false; break; }
    }
    printf("  %s\n", ok ? "PASS" : "FAIL");
    return ok;
}

static bool test_resolve_no_anomaly_when_index_negative() {
    printf("\n=== Test: anomaly_index<0 never overrides + consumes no anomaly RNG ===\n");
    uint32_t rng = 0x2222u;
    int active = 1;
    bool ok = true;
    for (int i = 0; i < 500; i++) {  // section_changed=false, index<0 -> active, rng untouched
        int d = lick_resolve_desired(rng, false, 2, -1, 0.9f, active);
        if (d != active) { ok = false; break; }
    }
    if (rng != 0x2222u) ok = false;  // no rotation re-roll + short-circuited anomaly = no RNG
    printf("  rng_untouched=%d -- %s\n", (rng == 0x2222u), ok ? "PASS" : "FAIL");
    return ok;
}

// Anomaly Engine force path: force_anomaly=true must return anomaly_index even when
// chance=0 (the vibe's own per-statement roll would never fire it on its own) — this
// is the manual one-shot override. Also pins the "force consumes no RNG" contract:
// force is checked FIRST in the short-circuit, so lick_roll_anomaly's rand01() draw
// never runs on a forced call.
static bool test_force_anomaly_overrides_zero_chance() {
    printf("\n=== Test: force_anomaly=true fires the anomaly even at chance=0 (no RNG) ===\n");
    uint32_t rng = 0x3333u;
    uint32_t before = rng;
    int active = 0;
    bool ok = true;
    for (int i = 0; i < 500; i++) {  // section_changed=false -> no rotation re-roll either
        int d = lick_resolve_desired(rng, /*section_changed=*/false, 2, /*anomaly_index=*/2,
                                     /*anomaly_chance=*/0.0f, active, /*force_anomaly=*/true);
        if (d != 2) { ok = false; break; }
    }
    if (rng != before) ok = false;   // force short-circuits lick_roll_anomaly's rand01 draw
    printf("  rng_untouched=%d -- %s\n", (rng == before), ok ? "PASS" : "FAIL");
    return ok;
}

// Force path with no anomaly slot declared (anomaly_index<0): there is nothing to
// force, so the call must degrade to the ordinary rotation pick (no phantom slot,
// no crash), and consume no RNG (same short-circuit as the negative-index test above).
static bool test_force_anomaly_with_no_anomaly_slot_returns_rotation() {
    printf("\n=== Test: force_anomaly=true with anomaly_index<0 returns rotation ===\n");
    uint32_t rng = 0x4444u;
    uint32_t before = rng;
    int active = 1;
    bool ok = true;
    for (int i = 0; i < 500; i++) {  // section_changed=false, index<0 -> active, rng untouched
        int d = lick_resolve_desired(rng, /*section_changed=*/false, 2, /*anomaly_index=*/-1,
                                     /*anomaly_chance=*/0.9f, active, /*force_anomaly=*/true);
        if (d != active) { ok = false; break; }
    }
    if (rng != before) ok = false;
    printf("  rng_untouched=%d -- %s\n", (rng == before), ok ? "PASS" : "FAIL");
    return ok;
}

#include "test_pulsar_helpers.h"
#include "orpheus_engine.h"
#include "orpheus_unit_pulsar.h"
#include "stmlib/utils/random.h"  // pin the global noise RNG for reproducible integration tests
#include <cstring>

// Push a lick-pool bank via the atomics and assert load_vibe unpacks it and applies
// an initial rotation pick to state->lick. Mirrors test_void_config_unpacks_from_atomics.
static bool test_lick_pool_round_trip() {
    printf("\n=== Test: lick_pool atomics unpack + initial pick ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);

    // Two 2-step rotation licks in slots 0 and 1; distinct degrees so the pick is checkable.
    const int F = OrpheusEngine::kLickFieldsPerStep;      // 4
    const int S = OrpheusEngine::kMaxLickSteps;           // 64
    auto putStep = [&](int slot, int step, float deg, float dur, float vel, float glide) {
        int b = slot * (S * F) + step * F;
        engine->pulsar_lick_pool_data[b + 0] = deg;
        engine->pulsar_lick_pool_data[b + 1] = dur;
        engine->pulsar_lick_pool_data[b + 2] = vel;
        engine->pulsar_lick_pool_data[b + 3] = glide;
    };
    putStep(0, 0, 0.f, 0.5f, 0.9f, -1.f); putStep(0, 1, 1.f, 0.5f, 0.8f, -1.f);
    putStep(1, 0, 4.f, 0.5f, 0.9f, -1.f); putStep(1, 1, 5.f, 0.5f, 0.8f, -1.f);
    engine->pulsar_lick_pool_len[0] = 2;  engine->pulsar_lick_pool_loop[0] = 8;
    engine->pulsar_lick_pool_len[1] = 2;  engine->pulsar_lick_pool_loop[1] = 8;
    engine->pulsar_lick_anomaly_index = -1;
    engine->pulsar_lick_anomaly_chance = 0.0f;
    engine->pulsar_lick_pool_count.store(2, std::memory_order_release);

    engine->pulsar_seed.store(4242, std::memory_order_relaxed);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(120.0f, std::memory_order_relaxed);
    unit_process_pulsar(&unit, engine, 512, 48000.0f);

    PulsarState* ps = engine->pulsar_state;
    bool ok = ps
        && ps->lick_pool_count == 2
        && ps->lick_pool_len[0] == 2 && ps->lick_pool_len[1] == 2
        && ps->current_lick_index >= 0 && ps->current_lick_index < 2
        // state->lick was overridden with the picked pool member:
        && ps->lick_length == 2
        && ps->lick[0].scale_degree == ps->lick_pool[ps->current_lick_index][0].scale_degree
        && ps->lick[1].scale_degree == ps->lick_pool[ps->current_lick_index][1].scale_degree;
    printf("  pool_count=%d picked=%d lick[0].deg=%d -- %s\n",
           ps ? ps->lick_pool_count : -1, ps ? ps->current_lick_index : -1,
           ps ? ps->lick[0].scale_degree : -1, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Run many bars through an arrangement vibe and assert the active lick stays a valid,
// consistent pool member (rotation swaps keep state->lick == pool[current_lick_index]).
static bool test_section_rotation_consistency() {
    printf("\n=== Test: rotation keeps active lick consistent with its pool slot ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);

    const int F = OrpheusEngine::kLickFieldsPerStep, S = OrpheusEngine::kMaxLickSteps;
    auto putStep = [&](int slot, int step, float deg) {
        int b = slot * (S * F) + step * F;
        engine->pulsar_lick_pool_data[b + 0] = deg;
        engine->pulsar_lick_pool_data[b + 1] = 0.5f;
        engine->pulsar_lick_pool_data[b + 2] = 0.8f;
        engine->pulsar_lick_pool_data[b + 3] = -1.f;
    };
    putStep(0, 0, 0.f); putStep(0, 1, 1.f);
    putStep(1, 0, 4.f); putStep(1, 1, 5.f);
    engine->pulsar_lick_pool_len[0] = 2; engine->pulsar_lick_pool_loop[0] = 8;
    engine->pulsar_lick_pool_len[1] = 2; engine->pulsar_lick_pool_loop[1] = 8;
    engine->pulsar_lick_anomaly_index = -1;
    engine->pulsar_lick_anomaly_chance = 0.0f;
    engine->pulsar_lick_pool_count.store(2, std::memory_order_release);
    engine->pulsar_seed.store(4242, std::memory_order_relaxed);
    // Activate an arrangement so the wired per-section rotation actually runs
    // (the resolution lives in the section-advance path). Without this the
    // invariant below holds vacuously — current_lick_index never changes.
    setup_jam_arrangement(engine);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(300.0f, std::memory_order_relaxed);  // fast clock = many bars quickly

    // ~1500 blocks at 300 BPM guarantees at least one section transition, so a
    // rotation re-roll is exercised. We assert only the structural invariant here;
    // the deterministic "rotation varies" proof lives in the pure-header tests.
    bool ok = true;
    for (int block = 0; block < 1500 && ok; block++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        PulsarState* ps = engine->pulsar_state;
        if (!ps) { ok = false; break; }
        int ci = ps->current_lick_index;
        if (ci < 0 || ci >= ps->lick_pool_count) { ok = false; break; }
        if (ps->lick_length != ps->lick_pool_len[ci]) { ok = false; break; }
        if (ps->lick[0].scale_degree != ps->lick_pool[ci][0].scale_degree) { ok = false; break; }
    }
    printf("  final picked=%d -- %s\n",
           engine->pulsar_state ? engine->pulsar_state->current_lick_index : -1,
           ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// anomaly_chance = 1.0 forces the anomaly every statement: current_lick_index must
// settle on the anomaly slot (index == pool_count) within a few bars.
static bool test_anomaly_forced_when_chance_one() {
    printf("\n=== Test: anomaly_chance=1 forces the anomaly lick ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);

    const int F = OrpheusEngine::kLickFieldsPerStep, S = OrpheusEngine::kMaxLickSteps;
    auto putStep = [&](int slot, int step, float deg) {
        int b = slot * (S * F) + step * F;
        engine->pulsar_lick_pool_data[b + 0] = deg;
        engine->pulsar_lick_pool_data[b + 1] = 0.5f;
        engine->pulsar_lick_pool_data[b + 2] = 0.8f;
        engine->pulsar_lick_pool_data[b + 3] = -1.f;
    };
    // slot 0,1 = rotation; slot 2 = anomaly (distinct degree 7)
    putStep(0, 0, 0.f); putStep(1, 0, 4.f); putStep(2, 0, 7.f);
    engine->pulsar_lick_pool_len[0] = 1; engine->pulsar_lick_pool_loop[0] = 8;
    engine->pulsar_lick_pool_len[1] = 1; engine->pulsar_lick_pool_loop[1] = 8;
    engine->pulsar_lick_pool_len[2] = 1; engine->pulsar_lick_pool_loop[2] = 8;
    engine->pulsar_lick_anomaly_index = 2;
    engine->pulsar_lick_anomaly_chance = 1.0f;
    engine->pulsar_lick_pool_count.store(2, std::memory_order_release);
    engine->pulsar_seed.store(4242, std::memory_order_relaxed);
    // The wired per-statement resolution only runs inside an active arrangement
    // (it lives in the section-advance path), so activate one — like the real
    // FireSky05 vibe does at runtime — before the vibe load picks it up.
    setup_jam_arrangement(engine);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(200.0f, std::memory_order_relaxed);

    bool saw_anomaly = false;
    for (int block = 0; block < 400 && !saw_anomaly; block++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        PulsarState* ps = engine->pulsar_state;
        if (ps && ps->current_lick_index == 2) saw_anomaly = true;
    }
    printf("  saw_anomaly=%d -- %s\n", saw_anomaly, saw_anomaly ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return saw_anomaly;
}

// Anomaly Engine: the manual trigger's one-shot lick-force path. lick_anomaly_chance
// is pinned at 0 — the anomaly could NEVER fire from the vibe's own per-statement
// roll — so any appearance of the anomaly slot after the counter bump is unambiguously
// the forced path, not luck. It must also be a ONE-SHOT: force_lick_anomaly is
// consumed at the very next resolve regardless of outcome (orpheus_unit_pulsar.cpp),
// so a few loop-wraps later — chance still 0 — the active lick must fall back to a
// rotation member on its own. Mirrors test_anomaly_forced_when_chance_one's fixture,
// with an active arrangement (setup_jam_arrangement) since the resolve dispatcher only
// runs inside the section-advance path. void_data[7] is left at 0 (undeclared) so the
// SAME manual-trigger bump proves it fires the lick alone, not a stray void arm.
static bool test_manual_trigger_forces_lick_anomaly_once() {
    printf("\n=== Test: manual anomaly trigger forces the lick anomaly once, then releases ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);

    const int F = OrpheusEngine::kLickFieldsPerStep, S = OrpheusEngine::kMaxLickSteps;
    auto putStep = [&](int slot, int step, float deg) {
        int b = slot * (S * F) + step * F;
        engine->pulsar_lick_pool_data[b + 0] = deg;
        engine->pulsar_lick_pool_data[b + 1] = 0.5f;
        engine->pulsar_lick_pool_data[b + 2] = 0.8f;
        engine->pulsar_lick_pool_data[b + 3] = -1.f;
    };
    // slot 0,1 = rotation; slot 2 = anomaly (distinct degree 7)
    putStep(0, 0, 0.f); putStep(1, 0, 4.f); putStep(2, 0, 7.f);
    engine->pulsar_lick_pool_len[0] = 1; engine->pulsar_lick_pool_loop[0] = 8;
    engine->pulsar_lick_pool_len[1] = 1; engine->pulsar_lick_pool_loop[1] = 8;
    engine->pulsar_lick_pool_len[2] = 1; engine->pulsar_lick_pool_loop[2] = 8;
    engine->pulsar_lick_anomaly_index = 2;
    engine->pulsar_lick_anomaly_chance = 0.0f;   // never fires on its own — force is the only path
    engine->pulsar_lick_pool_count.store(2, std::memory_order_release);
    engine->pulsar_void_data[7].store(0.0f, std::memory_order_relaxed);  // undeclared: no void arm

    // The wired per-statement resolution only runs inside an active arrangement (it
    // lives in the section-advance path), so activate one, as the real FireSky05 vibe
    // does at runtime.
    setup_jam_arrangement(engine);
    engine->pulsar_seed.store(4242, std::memory_order_relaxed);
    stmlib::Random::Seed(0xBEA70000u);   // project anti-flake convention: pin both RNGs
    trigger_vibe_load(engine);
    engine->clock_bpm.store(200.0f, std::memory_order_relaxed);

    // Run until stable: past the initial vibe-load pick. With chance=0 this can only
    // ever be a rotation slot (0 or 1), never the anomaly (2).
    PulsarState* ps = nullptr;
    for (int block = 0; block < 150; block++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        ps = engine->pulsar_state;
    }
    bool started_on_rotation = ps && ps->current_lick_index != 2;

    // Fire the manual trigger (bump the counter, as the ViewModel would).
    engine->pulsar_anomaly_request.store(1, std::memory_order_release);

    bool saw_anomaly = false;
    for (int block = 0; block < 400 && !saw_anomaly; block++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        if (ps->current_lick_index == 2) saw_anomaly = true;
    }

    // One-shot: keep running well past the forced statement. force_lick_anomaly was
    // already consumed at that resolve, and chance is still 0, so the anomaly cannot
    // re-fire — the very next loop-wrap must land back on a rotation slot.
    bool released_to_rotation = false;
    for (int block = 0; block < 800 && !released_to_rotation; block++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        if (ps->current_lick_index != 2) released_to_rotation = true;
    }

    bool ok = started_on_rotation && saw_anomaly && released_to_rotation;
    printf("  started_on_rotation=%d saw_anomaly=%d released_to_rotation=%d final=%d -- %s\n",
           started_on_rotation, saw_anomaly, released_to_rotation,
           ps ? ps->current_lick_index : -1, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Regression: a rotation vibe with step_count > kMaxPulsarSteps must NOT overflow the
// fixed ts.steps[kMaxPulsarSteps] buffer when a swap re-renders the Fill tracks.
// regenerate_lick_tracks must clamp step_count exactly like the load / deja-vu paths.
static bool test_regenerate_clamps_step_count() {
    printf("\n=== Test: regenerate clamps oversized step_count (no ts.steps overflow) ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit; std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR; unit.enabled = true;
    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_fixture_baseline(engine);
    // Force track 4 to be a FILL melodic lick track so regenerate_lick_tracks renders it.
    engine->pulsar_track_role[4].store(1, std::memory_order_relaxed);       // MELODIC
    engine->pulsar_track_lick_mode[4].store(2, std::memory_order_relaxed);  // FILL

    const int F = OrpheusEngine::kLickFieldsPerStep, S = OrpheusEngine::kMaxLickSteps;
    auto putStep = [&](int slot, int step, float deg) {
        int b = slot * (S * F) + step * F;
        engine->pulsar_lick_pool_data[b + 0] = deg;
        engine->pulsar_lick_pool_data[b + 1] = 0.5f;
        engine->pulsar_lick_pool_data[b + 2] = 0.8f;
        engine->pulsar_lick_pool_data[b + 3] = -1.f;
    };
    putStep(0, 0, 0.f); putStep(1, 0, 4.f); putStep(2, 0, 7.f);
    engine->pulsar_lick_pool_len[0] = 1; engine->pulsar_lick_pool_loop[0] = 8;
    engine->pulsar_lick_pool_len[1] = 1; engine->pulsar_lick_pool_loop[1] = 8;
    engine->pulsar_lick_pool_len[2] = 1; engine->pulsar_lick_pool_loop[2] = 8;
    engine->pulsar_lick_anomaly_index = 2;
    engine->pulsar_lick_anomaly_chance = 1.0f;  // force a swap -> regenerate every statement
    engine->pulsar_lick_pool_count.store(2, std::memory_order_release);
    setup_jam_arrangement(engine);
    // Oversized step count LAST so it wins over any setup default — would write
    // ts.steps[0..127] into a [64] buffer without the clamp.
    engine->pulsar_step_count.store(128, std::memory_order_relaxed);
    engine->pulsar_seed.store(4242, std::memory_order_relaxed);
    trigger_vibe_load(engine);
    engine->clock_bpm.store(200.0f, std::memory_order_relaxed);

    bool ok = true;
    for (int block = 0; block < 400 && ok; block++) {
        unit_process_pulsar(&unit, engine, 512, 48000.0f);
        PulsarState* ps = engine->pulsar_state;
        if (!ps) { ok = false; break; }
        for (int t = 0; t < kNumPulsarTracks; t++)
            if (ps->tracks[t].step_count > kMaxPulsarSteps) { ok = false; break; }
    }
    printf("  track4 step_count=%d (expected <= %d) -- %s\n",
           engine->pulsar_state ? engine->pulsar_state->tracks[4].step_count : -1,
           kMaxPulsarSteps, ok ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

bool run_pulsar_lick_select_tests() {
    printf("\n=== Pulsar Lick Select ===\n");
    int passed = 0, failed = 0;
    auto run = [&](bool (*fn)()) { if (fn()) passed++; else failed++; };
    run(test_rotation_always_in_range);
    run(test_rotation_single_member_is_zero);
    run(test_rotation_covers_both_members);
    run(test_anomaly_threshold);
    run(test_determinism_same_seed);
    run(test_resolve_reroll_only_on_section_change);
    run(test_resolve_anomaly_overrides_rotation);
    run(test_resolve_no_anomaly_when_index_negative);
    run(test_force_anomaly_overrides_zero_chance);
    run(test_force_anomaly_with_no_anomaly_slot_returns_rotation);
    run(test_lick_pool_round_trip);
    run(test_section_rotation_consistency);
    run(test_anomaly_forced_when_chance_one);
    run(test_manual_trigger_forces_lick_anomaly_once);
    run(test_regenerate_clamps_step_count);
    printf("\n  Pulsar Lick Select: %d passed, %d failed\n", passed, failed);
    TEST_SUITE_RETURN(passed, failed);
}
