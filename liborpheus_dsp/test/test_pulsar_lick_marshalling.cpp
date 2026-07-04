// Lick marshalling round-trip guard (P1-T2).
//
// This is the safety net for the "longer licks" refactor: it characterizes
// the CURRENT (cap = kMaxLickSteps = 32) Kotlin -> C++ lick pipeline so that
// a later task raising the cap has a trustworthy regression test to lean on.
//
// Path under test:
//   1. Kotlin pushes lick_data_<idx> (idx = step * kLickFieldsPerStep + field)
//      through orpheus_engine_set_port(engine, PULSAR_URI, "lick_data_N", value).
//   2. orpheus_engine_routing.cpp decodes idx -> (step, field) and writes into
//      engine->pulsar_lick[step].<field> (bounds-checked step < kMaxLickSteps).
//   3. Kotlin sets "lick_length" LAST (release-fence contract: the write uses
//      std::memory_order_release so every lick_data_* store above happens-before
//      it from the audio thread's point of view).
//   4. load_vibe() (orpheus_unit_pulsar.cpp) acquire-loads pulsar_lick_length,
//      then copies pulsar_lick[0..len) into PulsarState::lick[] field-by-field.
//
// Field order (kLickFieldsPerStep = 4): 0=degree, 1=duration, 2=velocity, 3=glide.
#include "test_harness.h"
#include "test_pulsar_helpers.h"
#include "../src/orpheus_unit_pulsar.h"
#include <cstdio>
#include <cmath>
#include <cstring>

static constexpr const char* PULSAR_URI = "org.balch.orpheus.plugins.pulsar";

static bool approx(float a, float b) { return std::fabs(a - b) < 1e-4f; }

// Push a full-length lick through the routing decode (orpheus_engine_set_port),
// using distinct, order-revealing values per field so a stride mixup is
// unmistakable: degree=i (0..31), duration=1.0+i*0.01, velocity=0.5+i*0.001,
// glide=0.1+i*0.0001. lick_length is written LAST per the release-fence contract.
static void push_lick_via_routing(OrpheusEngine* engine, int step_count) {
    for (int i = 0; i < step_count; i++) {
        int base = i * OrpheusEngine::kLickFieldsPerStep;
        char sym[32];
        snprintf(sym, sizeof(sym), "lick_data_%d", base + 0);
        orpheus_engine_set_port(engine, PULSAR_URI, sym, static_cast<float>(i));               // degree
        snprintf(sym, sizeof(sym), "lick_data_%d", base + 1);
        orpheus_engine_set_port(engine, PULSAR_URI, sym, 1.0f + i * 0.01f);                     // duration
        snprintf(sym, sizeof(sym), "lick_data_%d", base + 2);
        orpheus_engine_set_port(engine, PULSAR_URI, sym, 0.5f + i * 0.001f);                    // velocity
        snprintf(sym, sizeof(sym), "lick_data_%d", base + 3);
        orpheus_engine_set_port(engine, PULSAR_URI, sym, 0.1f + i * 0.0001f);                   // glide
    }
    // Release-fence write LAST, mirroring the real Kotlin -> C++ contract.
    orpheus_engine_set_port(engine, PULSAR_URI, "lick_length", static_cast<float>(step_count));
}

// ── Test 1: full 32-step lick round-trips with field order intact ──────────
static bool test_lick_marshalling_roundtrip() {
    printf("\n=== Test: 32-step lick round-trips Kotlin->C++ with field order intact ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_cosmic_techno(engine);
    engine->pulsar_seed.store(424242, std::memory_order_relaxed);  // pin RNG (avoid wall-clock re-stir)

    constexpr int kSteps = 32;  // current cap; this test is meant to characterize N=32 exactly
    push_lick_via_routing(engine, kSteps);

    trigger_vibe_load(engine);
    // One process call is enough to run load_vibe() and flush the lick copy
    // loop into PulsarState — we don't need audio to actually render.
    unit_process_pulsar(&unit, engine, 64, 48000.0f);

    PulsarState* ps = engine->pulsar_state;
    bool ok = (ps != nullptr);
    if (!ok) {
        printf("  FAIL: PulsarState was null after process call\n");
        orpheus_engine_destroy(engine);
        return false;
    }

    bool length_ok = (ps->lick_length == kSteps);
    printf("  lick_length=%d (expected %d) -- %s\n", ps->lick_length, kSteps,
           length_ok ? "OK" : "FAIL");

    bool fields_ok = true;
    for (int i = 0; i < kSteps; i++) {
        const PulsarLickStep& s = ps->lick[i];
        float expected_duration = 1.0f + i * 0.01f;
        float expected_velocity = 0.5f + i * 0.001f;
        float expected_glide    = 0.1f + i * 0.0001f;

        bool degree_ok   = (s.scale_degree == static_cast<int8_t>(i));
        bool duration_ok = approx(s.duration, expected_duration);
        bool velocity_ok = approx(s.velocity, expected_velocity);
        bool glide_ok     = approx(s.glide_rate, expected_glide);

        if (!(degree_ok && duration_ok && velocity_ok && glide_ok)) {
            fields_ok = false;
            printf("  MISMATCH at step %d: degree=%d(exp %d) duration=%.5f(exp %.5f) "
                   "velocity=%.5f(exp %.5f) glide=%.5f(exp %.5f)\n",
                   i, s.scale_degree, i, s.duration, expected_duration,
                   s.velocity, expected_velocity, s.glide_rate, expected_glide);
        }
    }
    printf("  all %d steps match by field order (degree,duration,velocity,glide) -- %s\n",
           kSteps, fields_ok ? "OK" : "FAIL");

    ok = length_ok && fields_ok;
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return ok;
}

// ── Test 2: no truncation — every one of the 32 steps is distinguishable ───
// (Reuses the same round-trip but explicitly checks the boundary steps 0 and
// 31, and that no step silently collapsed to a default/zeroed value — which
// is what truncation or a stride bug would look like.)
static bool test_lick_marshalling_no_truncation() {
    printf("\n=== Test: full 32-step lick is not truncated (boundary steps + monotonic degree) ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_cosmic_techno(engine);
    engine->pulsar_seed.store(424242, std::memory_order_relaxed);

    constexpr int kSteps = 32;
    push_lick_via_routing(engine, kSteps);

    trigger_vibe_load(engine);
    unit_process_pulsar(&unit, engine, 64, 48000.0f);

    PulsarState* ps = engine->pulsar_state;
    bool ok = (ps != nullptr) && (ps->lick_length == kSteps);

    // Boundary checks: step 0 and step 31 (kMaxLickSteps - 1) must both be
    // present and correct. A truncating bug (e.g. off-by-one in the copy loop
    // bound, or the routing decode's step < kMaxLickSteps check being wrong)
    // would most likely clip the tail (step 31) or the head (step 0).
    if (ok) {
        const PulsarLickStep& first = ps->lick[0];
        const PulsarLickStep& last  = ps->lick[kSteps - 1];
        bool first_ok = first.scale_degree == 0 && approx(first.duration, 1.0f)
                      && approx(first.velocity, 0.5f) && approx(first.glide_rate, 0.1f);
        bool last_ok  = last.scale_degree == (kSteps - 1)
                      && approx(last.duration, 1.0f + (kSteps - 1) * 0.01f)
                      && approx(last.velocity, 0.5f + (kSteps - 1) * 0.001f)
                      && approx(last.glide_rate, 0.1f + (kSteps - 1) * 0.0001f);
        printf("  step 0  degree=%d duration=%.4f -- %s\n",
               first.scale_degree, first.duration, first_ok ? "OK" : "FAIL");
        printf("  step 31 degree=%d duration=%.4f -- %s\n",
               last.scale_degree, last.duration, last_ok ? "OK" : "FAIL");

        // Monotonicity across all 32 degrees also rules out a truncated/short
        // copy silently leaving a prefix of zero-initialized steps.
        bool monotonic = true;
        for (int i = 0; i < kSteps; i++) {
            if (ps->lick[i].scale_degree != static_cast<int8_t>(i)) { monotonic = false; break; }
        }
        printf("  degree sequence 0..31 monotonic (no gaps/collapses) -- %s\n",
               monotonic ? "OK" : "FAIL");

        ok = first_ok && last_ok && monotonic;
    } else {
        printf("  FAIL: PulsarState missing or lick_length != %d\n", kSteps);
    }
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return ok;
}

// ── Negative check: a deliberately wrong stride must be caught ─────────────
//
// This proves the guard isn't vacuous: if we write lick fields as though the
// stride were 3 fields/step (instead of the real kLickFieldsPerStep = 4), the
// routing decode misinterprets which (step, field) each control index maps
// to, and the resulting state->lick[] must NOT match what a correct 4-field
// stride would have produced. We assert the mismatch is actually detected
// (i.e. this test itself must find a difference, not silently agree).
static bool test_lick_marshalling_wrong_stride_detected() {
    printf("\n=== Test: wrong stride (3 fields/step) produces a DETECTABLE mismatch ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_cosmic_techno(engine);
    engine->pulsar_seed.store(424242, std::memory_order_relaxed);

    constexpr int kSteps = 32;
    constexpr int kWrongStride = 3;  // real stride is kLickFieldsPerStep == 4

    // Write using the SAME per-step field values as the correct-stride test,
    // but pack them with a 3-wide stride so control index `i*3+field` lands
    // in the wrong (step, field) slot once decoded with the real stride of 4.
    for (int i = 0; i < kSteps; i++) {
        int base = i * kWrongStride;
        char sym[32];
        snprintf(sym, sizeof(sym), "lick_data_%d", base + 0);
        orpheus_engine_set_port(engine, PULSAR_URI, sym, static_cast<float>(i));               // "degree"
        snprintf(sym, sizeof(sym), "lick_data_%d", base + 1);
        orpheus_engine_set_port(engine, PULSAR_URI, sym, 1.0f + i * 0.01f);                     // "duration"
        snprintf(sym, sizeof(sym), "lick_data_%d", base + 2);
        orpheus_engine_set_port(engine, PULSAR_URI, sym, 0.5f + i * 0.001f);                    // "velocity"
        // Deliberately omit the 4th field per step (a 3-wide stride has none) —
        // this is exactly the kind of corruption a stride mismatch produces.
    }
    orpheus_engine_set_port(engine, PULSAR_URI, "lick_length", static_cast<float>(kSteps));

    trigger_vibe_load(engine);
    unit_process_pulsar(&unit, engine, 64, 48000.0f);

    PulsarState* ps = engine->pulsar_state;
    bool have_state = (ps != nullptr) && (ps->lick_length == kSteps);
    bool mismatch_detected = false;
    if (have_state) {
        for (int i = 0; i < kSteps; i++) {
            const PulsarLickStep& s = ps->lick[i];
            float expected_duration = 1.0f + i * 0.01f;
            float expected_velocity = 0.5f + i * 0.001f;
            bool degree_ok   = (s.scale_degree == static_cast<int8_t>(i));
            bool duration_ok = approx(s.duration, expected_duration);
            bool velocity_ok = approx(s.velocity, expected_velocity);
            if (!(degree_ok && duration_ok && velocity_ok)) {
                mismatch_detected = true;
                break;
            }
        }
    }

    // This test PASSES when the mismatch IS detected — i.e. the guard is not
    // vacuous. If wrong-stride data somehow matched correct-stride
    // expectations, that would mean our comparison is too weak to catch
    // corruption, which is exactly the failure mode this task must avoid.
    bool ok = have_state && mismatch_detected;
    printf("  wrong-stride data compared against correct-stride expectations: %s\n",
           mismatch_detected ? "MISMATCH FOUND (guard is discriminating)" : "NO MISMATCH (guard would be vacuous!)");
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return ok;
}

// ── Test 4: full 64-step lick round-trips at the RAISED cap (P1-T4) ─────────
//
// This is the red->green guard for raising the cap 32 -> 64. It pushes a
// stepCount=64 lick (every one of the 64 steps carrying distinct,
// order-revealing values) and asserts lick_length == 64 with all 64 steps
// surviving field-order intact. BEFORE the cap raise (kMaxLickSteps == 32),
// the routing decode's `step < kMaxLickSteps` bound and the load_vibe copy
// loop both stop at 32, so steps 32..63 truncate and this test FAILS (RED).
// AFTER the cap raise (kMaxLickSteps == 64), every step survives (GREEN).
static bool test_lick_marshalling_roundtrip_64() {
    printf("\n=== Test: 64-step lick round-trips Kotlin->C++ with no truncation (raised cap) ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit unit;
    std::memset(&unit, 0, sizeof(unit));
    unit.type = UNIT_PULSAR;
    unit.enabled = true;

    engine->pulsar_playing.store(1, std::memory_order_relaxed);
    engine->pulsar_mix.store(1.0f, std::memory_order_relaxed);
    setup_cosmic_techno(engine);
    engine->pulsar_seed.store(424242, std::memory_order_relaxed);  // pin RNG (avoid wall-clock re-stir)

    constexpr int kSteps = 64;  // the raised cap; must be kMaxLickSteps to not truncate
    push_lick_via_routing(engine, kSteps);

    trigger_vibe_load(engine);
    unit_process_pulsar(&unit, engine, 64, 48000.0f);

    PulsarState* ps = engine->pulsar_state;
    if (ps == nullptr) {
        printf("  FAIL: PulsarState was null after process call\n");
        orpheus_engine_destroy(engine);
        return false;
    }

    bool length_ok = (ps->lick_length == kSteps);
    printf("  lick_length=%d (expected %d) -- %s\n", ps->lick_length, kSteps,
           length_ok ? "OK" : "FAIL");

    // Every one of the 64 steps must survive with field order (degree, duration,
    // velocity, glide) intact. A truncating cap would leave steps 32..63 as
    // zero-initialized defaults; the per-step monotonic degree + distinct
    // duration/velocity/glide values make any dropped tail step unmistakable.
    bool fields_ok = true;
    for (int i = 0; i < kSteps; i++) {
        const PulsarLickStep& s = ps->lick[i];
        float expected_duration = 1.0f + i * 0.01f;
        float expected_velocity = 0.5f + i * 0.001f;
        float expected_glide    = 0.1f + i * 0.0001f;

        bool degree_ok   = (s.scale_degree == static_cast<int8_t>(i));
        bool duration_ok = approx(s.duration, expected_duration);
        bool velocity_ok = approx(s.velocity, expected_velocity);
        bool glide_ok    = approx(s.glide_rate, expected_glide);

        if (!(degree_ok && duration_ok && velocity_ok && glide_ok)) {
            fields_ok = false;
            printf("  MISMATCH at step %d: degree=%d(exp %d) duration=%.5f(exp %.5f) "
                   "velocity=%.5f(exp %.5f) glide=%.5f(exp %.5f)\n",
                   i, s.scale_degree, i, s.duration, expected_duration,
                   s.velocity, expected_velocity, s.glide_rate, expected_glide);
        }
    }
    printf("  all %d steps match by field order (degree,duration,velocity,glide) -- %s\n",
           kSteps, fields_ok ? "OK" : "FAIL");

    // Explicit tail-boundary check: step 63 (kMaxLickSteps-1) is exactly the step
    // a 32-cap would have dropped. Assert it survived with correct values.
    const PulsarLickStep& tail = ps->lick[kSteps - 1];
    bool tail_ok = tail.scale_degree == (kSteps - 1)
                 && approx(tail.duration, 1.0f + (kSteps - 1) * 0.01f)
                 && approx(tail.velocity, 0.5f + (kSteps - 1) * 0.001f)
                 && approx(tail.glide_rate, 0.1f + (kSteps - 1) * 0.0001f);
    printf("  step 63 (tail) degree=%d duration=%.4f -- %s\n",
           tail.scale_degree, tail.duration, tail_ok ? "OK" : "FAIL");

    bool ok = length_ok && fields_ok && tail_ok;
    printf("  Overall -- %s\n", ok ? "PASS" : "FAIL");

    orpheus_engine_destroy(engine);
    return ok;
}

// Regression guard for the final-review Critical: the viz PRODUCER must not write past
// the fixed-width consumer buffers. The JNI/iOS/monitor consumers are
// kNumPulsarTracks * kPulsarVizSteps = 256 wide; before the kPulsarVizSteps fix,
// raising kMaxPulsarSteps to 64 made orpheus_engine_get_pulsar_viz write 8*64 = 512 —
// a native buffer overflow at ~60fps in the running app on every vibe. This sentinels
// the tail past the export width and asserts the producer left it untouched.
static bool test_pulsar_viz_export_within_consumer_bounds() {
    printf("\n=== Test: pulsar viz export does not overrun the consumer buffers ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    constexpr int kExportSteps = 32;                            // == kPulsarVizSteps (orpheus_engine.h)
    constexpr int kConsumer = kNumPulsarTracks * kExportSteps;  // 256 — real bridge/monitor buffer width
    constexpr int kSlack = kNumPulsarTracks * kMaxPulsarSteps;  // 512 — big enough to catch a 64-stride overrun
    static_assert(kSlack >= kConsumer, "slack must cover the consumer width");

    constexpr int GATE_SENT = 0x0BADF00D;
    constexpr float VEL_SENT = -123456.0f;
    int gates[kSlack]; float vels[kSlack];
    int playheads[kNumPulsarTracks]; int step_counts[kNumPulsarTracks];
    for (int i = 0; i < kSlack; i++) { gates[i] = GATE_SENT; vels[i] = VEL_SENT; }

    orpheus_engine_get_pulsar_viz(engine, gates, vels, playheads, step_counts);

    // Every index at/after the consumer width must still be the sentinel — i.e. the
    // producer wrote at most kConsumer entries (stride == kExportSteps, not kMaxPulsarSteps).
    bool ok = true;
    for (int i = kConsumer; i < kSlack; i++) {
        if (gates[i] != GATE_SENT || vels[i] != VEL_SENT) { ok = false; break; }
    }
    printf("  producer stayed within %d-entry consumer width -- %s\n", kConsumer, ok ? "OK" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

bool run_pulsar_marshalling_tests() {
    printf("\n========== PULSAR LICK MARSHALLING TESTS ==========\n");
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_lick_marshalling_roundtrip());
    tally(test_lick_marshalling_no_truncation());
    tally(test_lick_marshalling_wrong_stride_detected());
    tally(test_lick_marshalling_roundtrip_64());
    tally(test_pulsar_viz_export_within_consumer_bounds());
    printf("\nPulsar lick marshalling tests: %s\n", suite_fail == 0 ? "ALL PASSED" : "SOME FAILED");
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
