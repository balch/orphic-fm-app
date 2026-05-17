// Lorenz attractor: divergence test, output bounded
#include "test_harness.h"
#include "orpheus_unit_chaos.h"

static bool test_chaos_lorenz_diverges() {
    printf("\n=== Test: Lorenz engine 200 diverges ===\n");
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    engine->voice_params[0].active.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].engine_index.store(kChaosEngineLorenz);
    engine->voice_params[0].tune.store(60.0f);
    engine->voice_params[0].gate.store(1);
    engine->voice_params[0].harmonics.store(0.5f);   // ρ ≈ 31
    engine->voice_params[0].timbre.store(0.5f);
    engine->voice_params[0].morph.store(1.0f);
    engine->voice_params[0].decay.store(0.5f);

    GraphUnit v0; setup_voice_unit(&v0, 0);
    float amp = render_voice(&v0, engine, 12000);  // 250ms
    bool diverged = amp > 0.01f;
    bool bounded  = amp < 1.5f;
    printf("  Lorenz amp after 250ms: %.4f (diverged=%d, bounded=%d)\n",
           amp, diverged, bounded);
    bool ok = diverged && bounded;
    orpheus_engine_destroy(engine);
    return ok;
}

bool run_chaos_lorenz_tests() {
    int suite_pass = 0, suite_fail = 0;
    if (test_chaos_lorenz_diverges()) suite_pass++; else suite_fail++;
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
