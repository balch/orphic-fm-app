// Tides2 unit processor tests
#include "test_harness.h"
#include <cmath>
#include <cstring>

// ── Test 1: Init — create engine, verify it doesn't crash, destroy ──────────
static bool test_tides_init() {
    printf("\n=== Test: Tides2 init ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    GraphUnit u = {};
    u.type = UNIT_TIDES;
    u.enabled = true;
    unit_init(&u, 48000.0f);

    orpheus_engine_destroy(engine);
    printf("  Create/init/destroy: PASS\n");
    return true;
}

// ── Test 2: Bypass — mix=0 produces all-zero outputs ───────────────────────
static bool test_tides_bypass() {
    printf("\n=== Test: Tides2 bypass (mix=0) ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->tides_mix.store(0.0f);
    engine->tides_ramp_mode.store(1); // LOOPING
    engine->tides_frequency.store(0.5f);
    engine->tides_gate_source.store(4); // free-run

    GraphUnit u = {};
    u.type = UNIT_TIDES;
    u.enabled = true;
    unit_init(&u, 48000.0f);

    // Pre-fill output buffers with non-zero to confirm they get zeroed
    for (int i = 0; i < 64; i++) {
        u.output_buffers[OPORT_OUT][i]       = 1.0f;
        u.output_buffers[OPORT_OUT_RIGHT][i] = 1.0f;
        u.output_buffers[OPORT_AUX][i]       = 1.0f;
    }

    unit_process_tides(&u, engine, 64, 48000.0f);

    bool all_zero = true;
    for (int i = 0; i < 64; i++) {
        if (u.output_buffers[OPORT_OUT][i]       != 0.0f) { all_zero = false; break; }
        if (u.output_buffers[OPORT_OUT_RIGHT][i] != 0.0f) { all_zero = false; break; }
        if (u.output_buffers[OPORT_AUX][i]       != 0.0f) { all_zero = false; break; }
    }

    orpheus_engine_destroy(engine);
    printf("  All outputs zero: %s\n", all_zero ? "PASS" : "FAIL");
    return all_zero;
}

// ── Test 3: Looping produces output ────────────────────────────────────────
static bool test_tides_looping_produces_output() {
    printf("\n=== Test: Tides2 looping produces non-zero output ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->tides_mix.store(1.0f);
    engine->tides_ramp_mode.store(1);  // LOOPING
    engine->tides_frequency.store(0.5f);
    engine->tides_gate_source.store(4); // free-run
    engine->tides_range.store(0);       // RANGE_CONTROL

    GraphUnit u = {};
    u.type = UNIT_TIDES;
    u.enabled = true;
    unit_init(&u, 48000.0f);

    float max_out = 0.0f;
    for (int b = 0; b < 10; b++) {
        unit_process_tides(&u, engine, 64, 48000.0f);
        for (int i = 0; i < 64; i++) {
            float a = std::fabs(u.output_buffers[OPORT_OUT][i]);
            if (a > max_out) max_out = a;
        }
    }

    bool has_output = max_out > 0.0f;
    orpheus_engine_destroy(engine);
    printf("  Channel 0 max amplitude: %.6f  %s\n", max_out, has_output ? "PASS" : "FAIL");
    return has_output;
}

// ── Test 4: Output within valid range [-5, +5] ─────────────────────────────
static bool test_tides_output_in_range() {
    printf("\n=== Test: Tides2 output in range [-5, +5] ===\n");

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->tides_mix.store(1.0f);
    engine->tides_ramp_mode.store(1);  // LOOPING
    engine->tides_frequency.store(0.7f);
    engine->tides_gate_source.store(4); // free-run
    engine->tides_range.store(0);

    GraphUnit u = {};
    u.type = UNIT_TIDES;
    u.enabled = true;
    unit_init(&u, 48000.0f);

    bool all_finite = true;
    bool all_in_range = true;
    const float kLimit = 5.0f;

    for (int b = 0; b < 20; b++) {
        unit_process_tides(&u, engine, 64, 48000.0f);
        for (int i = 0; i < 64; i++) {
            float ch[3] = {
                u.output_buffers[OPORT_OUT][i],
                u.output_buffers[OPORT_OUT_RIGHT][i],
                u.output_buffers[OPORT_AUX][i]
            };
            for (int c = 0; c < 3; c++) {
                if (!std::isfinite(ch[c]))               { all_finite = false; }
                if (ch[c] < -kLimit || ch[c] > kLimit)  { all_in_range = false; }
            }
        }
    }

    // Also check tides_output_buffer[3] (ch3 goes to warps source only)
    for (int i = 0; i < 64; i++) {
        float v = engine->tides_output_buffer[3][i];
        if (!std::isfinite(v))             { all_finite = false; }
        if (v < -kLimit || v > kLimit)     { all_in_range = false; }
    }

    bool pass = all_finite && all_in_range;
    orpheus_engine_destroy(engine);
    printf("  All finite: %s  All in [-5,+5]: %s  %s\n",
           all_finite ? "yes" : "NO",
           all_in_range ? "yes" : "NO",
           pass ? "PASS" : "FAIL");
    return pass;
}

// ── Test 5: All ramp modes produce finite output ────────────────────────────
static bool test_tides_all_ramp_modes() {
    printf("\n=== Test: Tides2 all ramp modes ===\n");

    // AD=0, LOOPING=1, AR=2
    const char* names[] = { "AD", "LOOPING", "AR" };
    bool all_pass = true;

    for (int mode = 0; mode <= 2; mode++) {
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);
        engine->tides_mix.store(1.0f);
        engine->tides_ramp_mode.store(mode);
        engine->tides_frequency.store(0.5f);
        engine->tides_gate_source.store(4); // free-run
        engine->tides_range.store(0);

        GraphUnit u = {};
        u.type = UNIT_TIDES;
        u.enabled = true;
        unit_init(&u, 48000.0f);

        bool finite_ok = true;
        for (int b = 0; b < 5; b++) {
            unit_process_tides(&u, engine, 64, 48000.0f);
            for (int i = 0; i < 64; i++) {
                if (!std::isfinite(u.output_buffers[OPORT_OUT][i]))       { finite_ok = false; break; }
                if (!std::isfinite(u.output_buffers[OPORT_OUT_RIGHT][i])) { finite_ok = false; break; }
                if (!std::isfinite(u.output_buffers[OPORT_AUX][i]))       { finite_ok = false; break; }
            }
            if (!finite_ok) break;
        }

        orpheus_engine_destroy(engine);
        printf("  ramp_mode=%s (%d): %s\n", names[mode], mode, finite_ok ? "PASS" : "FAIL");
        all_pass &= finite_ok;
    }

    return all_pass;
}

// ── Test 6: All output modes produce finite output ─────────────────────────
static bool test_tides_all_output_modes() {
    printf("\n=== Test: Tides2 all output modes ===\n");

    // GATES=0, AMPLITUDE=1, SLOPE_PHASE=2, FREQUENCY=3
    const char* names[] = { "GATES", "AMPLITUDE", "SLOPE_PHASE", "FREQUENCY" };
    bool all_pass = true;

    for (int mode = 0; mode <= 3; mode++) {
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);
        engine->tides_mix.store(1.0f);
        engine->tides_ramp_mode.store(1);   // LOOPING
        engine->tides_output_mode.store(mode);
        engine->tides_frequency.store(0.5f);
        engine->tides_gate_source.store(4); // free-run
        engine->tides_range.store(0);

        GraphUnit u = {};
        u.type = UNIT_TIDES;
        u.enabled = true;
        unit_init(&u, 48000.0f);

        bool finite_ok = true;
        for (int b = 0; b < 5; b++) {
            unit_process_tides(&u, engine, 64, 48000.0f);
            for (int i = 0; i < 64; i++) {
                if (!std::isfinite(u.output_buffers[OPORT_OUT][i]))       { finite_ok = false; break; }
                if (!std::isfinite(u.output_buffers[OPORT_OUT_RIGHT][i])) { finite_ok = false; break; }
                if (!std::isfinite(u.output_buffers[OPORT_AUX][i]))       { finite_ok = false; break; }
                if (!std::isfinite(engine->tides_output_buffer[3][i]))    { finite_ok = false; break; }
            }
            if (!finite_ok) break;
        }

        orpheus_engine_destroy(engine);
        printf("  output_mode=%s (%d): %s\n", names[mode], mode, finite_ok ? "PASS" : "FAIL");
        all_pass &= finite_ok;
    }

    return all_pass;
}

// ── Test runner ─────────────────────────────────────────────────────────────
bool run_tides_tests() {
    printf("\n========== TIDES2 TESTS ==========\n");
    bool all_pass = true;
    all_pass &= test_tides_init();
    all_pass &= test_tides_bypass();
    all_pass &= test_tides_looping_produces_output();
    all_pass &= test_tides_output_in_range();
    all_pass &= test_tides_all_ramp_modes();
    all_pass &= test_tides_all_output_modes();
    printf("\nTides2 tests: %s\n", all_pass ? "ALL PASSED" : "SOME FAILED");
    return all_pass;
}
