// Chaos engines: per-voice trajectory, pitched carrier, MORPH blend
#include "test_harness.h"
#include "orpheus_unit_chaos.h"
#include <cmath>
#include <cstring>
#include <vector>

static bool test_chaos_engine_produces_output() {
    printf("\n=== Test: Chaos engine 200 (Lorenz) produces output ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    bool all_pass = true;

    engine->voice_params[0].active.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].engine_index.store(kChaosEngineLorenz);  // 200
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].gate.store(1);
    engine->voice_params[0].harmonics.store(0.5f);
    engine->voice_params[0].timbre.store(0.5f);
    engine->voice_params[0].morph.store(1.0f);  // pure chaos
    engine->voice_params[0].decay.store(0.5f);

    GraphUnit v0;
    setup_voice_unit(&v0, 0);

    float amp = render_voice(&v0, engine, 24000);
    printf("  Chaos/Lorenz: peak=%.4f\n", amp);
    all_pass &= (amp > 0.001f);
    all_pass &= (amp < 1.5f);  // soft_limit should bound

    printf("Chaos smoke test: %s\n", all_pass ? "PASS" : "FAIL");
    orpheus_engine_destroy(engine);
    return all_pass;
}

// At MORPH=0 the chaos voice should emit a pure sine carrier locked to the
// note frequency. Count zero crossings over 100ms at A4 (440 Hz) and expect
// roughly 2 * f * t = 88 crossings.
static bool test_chaos_pitch_tracks_at_morph_zero() {
    printf("\n=== Test: At MORPH=0, output frequency tracks note pitch ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->voice_params[0].active.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].engine_index.store(kChaosEngineLorenz);
    engine->voice_params[0].tune.store(69.0f);   // A4 = 440 Hz
    engine->voice_params[0].gate.store(1);
    engine->voice_params[0].harmonics.store(0.5f);
    engine->voice_params[0].timbre.store(0.5f);
    engine->voice_params[0].morph.store(0.0f);   // pure carrier
    engine->voice_params[0].decay.store(0.5f);

    GraphUnit v0; setup_voice_unit(&v0, 0);

    constexpr int N = 4800;  // 100ms at 48 kHz
    std::vector<float> buffer(N);
    int filled = 0;
    while (filled < N) {
        int n = std::min(128, N - filled);
        unit_process_plaits(&v0, engine, n, 48000.0f);
        for (int i = 0; i < n; i++) buffer[filled + i] = v0.output_buffers[OPORT_OUT][i];
        filled += n;
    }
    int zero_crossings = 0;
    for (int i = 1; i < N; i++) {
        if ((buffer[i-1] >= 0.0f) != (buffer[i] >= 0.0f)) zero_crossings++;
    }
    bool ok = zero_crossings > 70 && zero_crossings < 110;
    printf("  Zero crossings: %d (expected ~88) %s\n",
           zero_crossings, ok ? "OK" : "FAIL");
    orpheus_engine_destroy(engine);
    return ok;
}

// Blow-up safety: inject extreme state, render long enough for the reset to
// fire, and verify the voice recovers (bounded state + audible output).
static bool test_chaos_blow_up_recovers() {
    printf("\n=== Test: Chaos voice recovers from manually-injected runaway state ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->voice_params[0].active.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].engine_index.store(kChaosEngineLorenz);
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].gate.store(1);
    engine->voice_params[0].harmonics.store(0.5f);
    engine->voice_params[0].timbre.store(0.5f);
    engine->voice_params[0].morph.store(1.0f);
    engine->voice_params[0].decay.store(0.5f);

    engine->chaos_state[0].x = 1e6f;
    engine->chaos_state[0].y = 1e6f;
    engine->chaos_state[0].z = 1e6f;
    // Pre-set prev_gate=1 so the rising-edge re-seed doesn't clobber the
    // injected runaway state before the kernel runs (we want the runtime
    // blow-up guard to handle it, not the gate-edge reset).
    engine->chaos_state[0].prev_gate = 1;

    GraphUnit v0; setup_voice_unit(&v0, 0);
    float amp = render_voice(&v0, engine, 12000);
    bool reset_fired   = engine->chaos_state[0].blow_up_count > 0;
    bool now_bounded   = std::fabs(engine->chaos_state[0].x) < 100.0f;
    bool audible_again = amp > 0.001f && amp < 1.5f;

    printf("  Reset count: %d, state.x=%.4f, amp=%.4f\n",
           engine->chaos_state[0].blow_up_count,
           engine->chaos_state[0].x, amp);

    orpheus_engine_destroy(engine);
    return reset_fired && now_bounded && audible_again;
}

static bool test_chaos_per_voice_independence() {
    printf("\n=== Test: 4 simultaneous Lorenz voices produce decorrelated output ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    GraphUnit units[4];
    for (int v = 0; v < 4; v++) {
        engine->voice_params[v].active.store(1);
        engine->voice_params[v].ever_triggered.store(1);
        engine->voice_params[v].engine_index.store(kChaosEngineLorenz);
        engine->voice_params[v].tune.store(60.0f + v);
        engine->voice_params[v].gate.store(1);
        engine->voice_params[v].harmonics.store(0.5f);
        engine->voice_params[v].timbre.store(0.5f);
        engine->voice_params[v].morph.store(1.0f);  // pure chaos for the corr test
        engine->voice_params[v].decay.store(0.5f);
        // Slightly different seeds so trajectories actually diverge in finite time.
        engine->chaos_state[v].x = 0.1f + 0.01f * v;
        // Pre-set prev_gate=1 so the rising-edge re-seed doesn't reset all
        // voices to the same x=0.1 starting point and destroy the differentiation.
        engine->chaos_state[v].prev_gate = 1;
        setup_voice_unit(&units[v], v);
    }

    constexpr int N = 4800;  // 100 ms at 48 kHz
    std::vector<std::vector<float>> bufs(4, std::vector<float>(N));
    for (int v = 0; v < 4; v++) {
        for (int offset = 0; offset < N; offset += 128) {
            int n = std::min(128, N - offset);
            unit_process_plaits(&units[v], engine, n, 48000.0f);
            std::memcpy(&bufs[v][offset], units[v].output_buffers[OPORT_OUT], n * sizeof(float));
        }
    }

    bool all_diverged = true;
    for (int v = 1; v < 4; v++) {
        double num = 0, da = 0, db = 0;
        for (int i = 0; i < N; i++) {
            num += bufs[0][i] * bufs[v][i];
            da  += bufs[0][i] * bufs[0][i];
            db  += bufs[v][i] * bufs[v][i];
        }
        double corr = (da > 0 && db > 0) ? num / std::sqrt(da * db) : 0.0;
        bool ok = std::fabs(corr) < 0.5;
        printf("  Voice 0 vs %d correlation: %.3f %s\n", v, corr, ok ? "OK" : "FAIL");
        all_diverged &= ok;
    }

    orpheus_engine_destroy(engine);
    return all_diverged;
}

static bool test_chaos_all_settings_sweep() {
    printf("\n=== Test: All-settings sweep across 5 engines × HARMONICS/TIMBRE/MORPH ===\n");
    bool all_pass = true;
    const int engines[] = {kChaosEngineLorenz, kChaosEngineRossler, kChaosEngineDuffing,
                           kChaosEngineHenon, kChaosEngineChua};
    const float steps[] = {0.0f, 0.25f, 0.5f, 0.75f, 1.0f};
    int total_combos = 0, failed_combos = 0;

    for (int e : engines) {
        for (float h : steps) for (float t : steps) for (float m : steps) {
            total_combos++;
            OrpheusEngine* engine = orpheus_engine_create(48000.0f);
            engine->voice_params[0].active.store(1);
            engine->voice_params[0].ever_triggered.store(1);
            engine->voice_params[0].engine_index.store(e);
            engine->voice_params[0].tune.store(60.0f);
            engine->voice_params[0].gate.store(1);
            engine->voice_params[0].harmonics.store(h);
            engine->voice_params[0].timbre.store(t);
            engine->voice_params[0].morph.store(m);
            engine->voice_params[0].decay.store(0.5f);

            GraphUnit v0; setup_voice_unit(&v0, 0);
            float peak = 0.0f;
            bool finite = true;
            for (int offset = 0; offset < 4800; offset += 128) {
                int n = std::min(128, 4800 - offset);
                unit_process_plaits(&v0, engine, n, 48000.0f);
                for (int i = 0; i < n; i++) {
                    float s = v0.output_buffers[OPORT_OUT][i];
                    if (!std::isfinite(s)) finite = false;
                    peak = std::max(peak, std::fabs(s));
                }
            }
            bool ok = finite && peak < 1.5f;
            if (!ok) {
                printf("  Engine %d h=%.2f t=%.2f m=%.2f peak=%.4f finite=%d FAIL\n",
                       e, h, t, m, peak, finite);
                failed_combos++;
                all_pass = false;
            }
            orpheus_engine_destroy(engine);
        }
    }
    printf("Sweep: %d/%d combinations PASS\n", total_combos - failed_combos, total_combos);
    return all_pass;
}

// Variant of the all-settings sweep that seeds each iteration with an extreme
// initial state to force the isfinite/magnitude reset path. Henon is the most
// likely to escalate to NaN/Inf within a handful of iterations when a=1.4
// meets a high-magnitude starting state, so we run the sweep against it.
static bool test_chaos_blow_up_safety_henon() {
    printf("\n=== Test: Henon recovers from extreme seed across all settings ===\n");
    bool all_pass = true;
    const float steps[] = {0.0f, 0.25f, 0.5f, 0.75f, 1.0f};
    int total_combos = 0, failed_combos = 0;

    for (float h : steps) for (float t : steps) for (float m : steps) {
        total_combos++;
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);
        engine->voice_params[0].active.store(1);
        engine->voice_params[0].ever_triggered.store(1);
        engine->voice_params[0].engine_index.store(kChaosEngineHenon);
        engine->voice_params[0].tune.store(60.0f);
        engine->voice_params[0].gate.store(1);
        engine->voice_params[0].harmonics.store(h);
        engine->voice_params[0].timbre.store(t);
        engine->voice_params[0].morph.store(m);
        engine->voice_params[0].decay.store(0.5f);

        // Seed extreme initial state; pre-arm prev_gate so the rising-edge
        // re-seed doesn't immediately wipe it — we want the runtime guard
        // (isfinite + magnitude bound) to be the thing that handles this.
        engine->chaos_state[0].x = 999.0f;
        engine->chaos_state[0].y = 999.0f;
        engine->chaos_state[0].z = 999.0f;
        engine->chaos_state[0].prev_gate = 1;

        GraphUnit v0; setup_voice_unit(&v0, 0);
        float peak = 0.0f;
        bool finite = true;
        for (int offset = 0; offset < 4800; offset += 128) {
            int n = std::min(128, 4800 - offset);
            unit_process_plaits(&v0, engine, n, 48000.0f);
            for (int i = 0; i < n; i++) {
                float s = v0.output_buffers[OPORT_OUT][i];
                if (!std::isfinite(s)) finite = false;
                peak = std::max(peak, std::fabs(s));
            }
        }
        bool reset_fired = engine->chaos_state[0].blow_up_count > 0;
        bool ok = finite && peak < 1.5f && reset_fired;
        if (!ok) {
            printf("  Henon h=%.2f t=%.2f m=%.2f peak=%.4f finite=%d reset_count=%d FAIL\n",
                   h, t, m, peak, finite, engine->chaos_state[0].blow_up_count);
            failed_combos++;
            all_pass = false;
        }
        orpheus_engine_destroy(engine);
    }
    printf("Henon blow-up sweep: %d/%d combinations PASS\n",
           total_combos - failed_combos, total_combos);
    return all_pass;
}

bool run_chaos_tests() {
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_chaos_engine_produces_output());
    tally(test_chaos_pitch_tracks_at_morph_zero());
    tally(test_chaos_blow_up_recovers());
    tally(test_chaos_per_voice_independence());
    tally(test_chaos_all_settings_sweep());
    tally(test_chaos_blow_up_safety_henon());
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
