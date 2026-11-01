// Control routing integration tests
// Verify orpheus_engine_set_port accepts all URIs/symbols that Kotlin sends
// and that the values actually affect audio output or engine state.
//
// These tests catch gaps where Kotlin sends port values that C++ ignores
// (wrong URI, wrong symbol name, or missing handler).
#include "test_harness.h"
#include "patch_test_helpers.h"

// ═══════════════════════════════════════════════════════════════════
// Helper: render with a specific port set, return RMS
// ═══════════════════════════════════════════════════════════════════
static float render_with_port(
    const char* uri, const char* symbol, float value,
    int engine_idx = 8, float note = 60.0f, float duration_s = 0.5f)
{
    float sr = 48000.0f;
    int total = (int)(sr * duration_s);

    OrpheusEngine* engine = orpheus_engine_create(sr);
    if (!load_production_graph(engine)) {
        fprintf(stderr, "render_with_port: could not load production graph\n");
        orpheus_engine_destroy(engine);
        return 0.0f;
    }
    activate_voice(engine, 0, engine_idx, note);

    // Apply the port under test
    orpheus_engine_set_port(engine, uri, symbol, value);

    auto r = render_engine(engine, total, 5);
    orpheus_engine_destroy(engine);
    return (r.rms_l + r.rms_r) / 2.0f;
}

// ═══════════════════════════════════════════════════════════════════
// Test 1: Patch cable + depth routing via set_port (Kotlin URIs + symbols)
// ═══════════════════════════════════════════════════════════════════
static bool test_patch_port_routing() {
    printf("\n=== Test: Patch cable and depth port routing via set_port ===\n");
    bool pass = true;

    struct TestCase {
        const char* label;
        const char* depth_uri;
        const char* depth_symbol;
    } cases[] = {
        {"voice URI depth", "org.balch.orpheus.plugins.voice", "duo_mod_source_level_0"},
        {"modulation URI depth", "org.balch.orpheus.plugins.modulation", "mod_depth_0"},
    };

    for (auto& tc : cases) {
        OrpheusEngine* engine = orpheus_engine_create(48000.0f);
        // LFO_AB -> TIMBRE on voice A of duo 0
        orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.patch", "p0_0_0_1", 1.0f);
        orpheus_engine_set_port(engine, tc.depth_uri, tc.depth_symbol, 0.8f);

        float amount = engine->patch_amount[0][0][kPatchSrcLfoAB][kPatchDstTimbre].load();
        float actual_depth = engine->mod_depth[0].load();
        printf("  %s: cable=%.2f (expect 1.00) mod_depth=%.2f (expect 0.80)\n",
               tc.label, amount, actual_depth);

        if (amount != 1.0f) {
            printf("    FAIL: patch cable not set (URI/symbol mismatch)\n");
            pass = false;
        }
        if (actual_depth < 0.79f) {
            printf("    FAIL: mod_depth not set\n");
            pass = false;
        }

        orpheus_engine_destroy(engine);
    }

    printf("Patch port routing test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 2: Distortion drive_mix affects audio output
// ═══════════════════════════════════════════════════════════════════
static bool test_distortion_mix_port() {
    printf("\n=== Test: Distortion mix via set_port ===\n");
    bool pass = true;

    const char* uri = "org.balch.orpheus.plugins.distortion";

    // With drive at max, compare mix=0 (dry) vs mix=1 (wet/distorted)
    OrpheusEngine* eng0 = orpheus_engine_create(48000.0f);
    if (!load_production_graph(eng0)) {
        printf("FAIL: could not load production graph\n");
        orpheus_engine_destroy(eng0);
        return false;
    }
    activate_voice(eng0, 0, 8, 60.0f);
    orpheus_engine_set_port(eng0, uri, "drive", 0.8f);
    orpheus_engine_set_port(eng0, uri, "mix", 0.0f);  // dry

    OrpheusEngine* eng1 = orpheus_engine_create(48000.0f);
    if (!load_production_graph(eng1)) {
        printf("FAIL: could not load production graph\n");
        orpheus_engine_destroy(eng0);
        orpheus_engine_destroy(eng1);
        return false;
    }
    activate_voice(eng1, 0, 8, 60.0f);
    orpheus_engine_set_port(eng1, uri, "drive", 0.8f);
    orpheus_engine_set_port(eng1, uri, "mix", 1.0f);  // wet

    // Verify the atomics were set
    float mix0 = eng0->drive_mix.load();
    float mix1 = eng1->drive_mix.load();
    printf("  Engine0 drive_mix=%.2f, Engine1 drive_mix=%.2f\n", mix0, mix1);
    if (mix0 > 0.01f || mix1 < 0.99f) {
        printf("  FAIL: drive_mix not stored correctly\n");
        pass = false;
    }

    // Render both — drive_mix changes output via production graph limiter
    auto r0 = render_engine(eng0, 24000, 5);
    auto r1 = render_engine(eng1, 24000, 5);
    float drms = diff_rms(r0, r1);

    printf("  mix=0 RMS=%.4f, mix=1 RMS=%.4f, diff_rms=%.4f\n",
           (r0.rms_l + r0.rms_r) / 2.0f, (r1.rms_l + r1.rms_r) / 2.0f, drms);
    if (drms < 0.001f) {
        printf("  FAIL: distortion mix has no effect on output\n");
        pass = false;
    }

    orpheus_engine_destroy(eng0);
    orpheus_engine_destroy(eng1);

    printf("Distortion mix port test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 3: Master pan via set_port affects L/R balance
// ═══════════════════════════════════════════════════════════════════
static bool test_master_pan_port() {
    printf("\n=== Test: Master pan via set_port ===\n");
    bool pass = true;

    const char* uri = "org.balch.orpheus.plugins.stereo";
    float sr = 48000.0f;
    int total = 24000;

    auto render_with_pan = [&](float pan) -> std::pair<float, float> {
        OrpheusEngine* engine = orpheus_engine_create(sr);
        if (!load_production_graph(engine)) {
            orpheus_engine_destroy(engine);
            return {0.0f, 0.0f};
        }
        activate_voice(engine, 0, 8, 60.0f);
        orpheus_engine_set_port(engine, uri, "master_pan", pan);

        auto r = render_engine(engine, total, 5);
        orpheus_engine_destroy(engine);
        return {r.rms_l, r.rms_r};
    };

    auto [l_center, r_center] = render_with_pan(0.0f);
    auto [l_left, r_left] = render_with_pan(-0.8f);
    auto [l_right, r_right] = render_with_pan(0.8f);

    printf("  Center: L=%.4f R=%.4f\n", l_center, r_center);
    printf("  Left:   L=%.4f R=%.4f\n", l_left, r_left);
    printf("  Right:  L=%.4f R=%.4f\n", l_right, r_right);

    // Center should be roughly equal L/R
    if (std::fabs(l_center - r_center) > (l_center + r_center) * 0.15f) {
        printf("  FAIL: center pan should have L≈R\n");
        pass = false;
    }
    // Left pan should have L > R
    if (l_left <= r_left) {
        printf("  FAIL: left pan should have L > R\n");
        pass = false;
    }
    // Right pan should have R > L
    if (r_right <= l_right) {
        printf("  FAIL: right pan should have R > L\n");
        pass = false;
    }

    printf("Master pan port test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 4: Voice timbre/morph via set_voice API affects output
// ═══════════════════════════════════════════════════════════════════
static bool test_voice_timbre_morph() {
    printf("\n=== Test: Voice timbre/morph API ===\n");
    bool pass = true;

    float sr = 48000.0f;
    int total = 24000;

    auto render_with_params = [&](float timbre, float morph) {
        OrpheusEngine* engine = orpheus_engine_create(sr);
        if (!load_production_graph(engine)) {
            orpheus_engine_destroy(engine);
            return RenderResult{};
        }
        activate_voice(engine, 0, 8, 60.0f, 0.5f, timbre, morph);

        auto r = render_engine(engine, total, 5);
        orpheus_engine_destroy(engine);
        return r;
    };

    auto r_low = render_with_params(0.1f, 0.1f);
    auto r_mid = render_with_params(0.5f, 0.5f);
    auto r_high = render_with_params(0.9f, 0.9f);

    // Compare outputs — different timbre/morph should produce different waveforms
    float d_low_mid = diff_rms(r_low, r_mid);
    float d_mid_high = diff_rms(r_mid, r_high);

    printf("  low vs mid diff: %.6f\n", d_low_mid);
    printf("  mid vs high diff: %.6f\n", d_mid_high);

    if (d_low_mid < 0.001f) {
        printf("  FAIL: timbre/morph change from low to mid has no effect\n");
        pass = false;
    }
    if (d_mid_high < 0.001f) {
        printf("  FAIL: timbre/morph change from mid to high has no effect\n");
        pass = false;
    }

    printf("Voice timbre/morph test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 5: Patch cable + depth integration: an LFO cable affects voice output
// Tests the full pipeline: LFO_AB -> TIMBRE cable + mod_depth -> voice timbre changes
// ═══════════════════════════════════════════════════════════════════
static bool test_patch_depth_integration() {
    printf("\n=== Test: Patch cable + depth integration ===\n");
    bool pass = true;

    float sr = 48000.0f;
    int total = 24000;

    // Baseline: no modulation
    OrpheusEngine* eng_dry = orpheus_engine_create(sr);
    eng_dry->voice_params[0].active.store(1);
    eng_dry->voice_params[0].ever_triggered.store(1);
    eng_dry->voice_params[0].engine_index.store(8);
    eng_dry->voice_params[0].tune.store(60.0f);
    eng_dry->voice_params[0].gate.store(1);
    eng_dry->voice_params[0].harmonics.store(0.5f);
    eng_dry->voice_params[0].timbre.store(0.5f);
    eng_dry->voice_params[0].morph.store(0.5f);
    eng_dry->voice_params[0].decay.store(0.0f);  // no cables: unmodulated

    GraphUnit v_dry;
    setup_voice_unit(&v_dry, 0);
    std::vector<float> buf_dry(total, 0.0f);
    for (int off = 0; off < total; off += 128) {
        int chunk = std::min(128, total - off);
        unit_process_plaits(&v_dry, eng_dry, chunk, sr);
        std::memcpy(buf_dry.data() + off, v_dry.output_buffers[OPORT_OUT], chunk * sizeof(float));
    }
    orpheus_engine_destroy(eng_dry);

    // With mod: set via set_port API
    OrpheusEngine* eng_mod = orpheus_engine_create(sr);
    eng_mod->voice_params[0].active.store(1);
    eng_mod->voice_params[0].ever_triggered.store(1);
    eng_mod->voice_params[0].engine_index.store(8);
    eng_mod->voice_params[0].tune.store(60.0f);
    eng_mod->voice_params[0].gate.store(1);
    eng_mod->voice_params[0].harmonics.store(0.5f);
    eng_mod->voice_params[0].timbre.store(0.5f);
    eng_mod->voice_params[0].morph.store(0.5f);
    eng_mod->voice_params[0].decay.store(0.0f);

    // LFO_AB -> TIMBRE cable on both voices of duo 0, depth = 0.8
    orpheus_engine_set_port(eng_mod, "org.balch.orpheus.plugins.patch", "p0_0_0_1", 1.0f);
    orpheus_engine_set_port(eng_mod, "org.balch.orpheus.plugins.patch", "p0_1_0_1", 1.0f);
    orpheus_engine_set_port(eng_mod, "org.balch.orpheus.plugins.modulation",
                            "mod_depth_0", 0.8f);
    eng_mod->lfo_output_value = 0.7f;
    for (int i = 0; i < kMaxFrames; i++) eng_mod->lfo_output_buffer[i] = 0.7f;

    // Verify values were stored
    float cable = eng_mod->patch_amount[0][0][kPatchSrcLfoAB][kPatchDstTimbre].load();
    float depth = eng_mod->mod_depth[0].load();
    printf("  After set_port: cable=%.2f mod_depth=%.2f\n", cable, depth);
    if (cable != 1.0f || depth < 0.79f) {
        printf("  FAIL: set_port didn't store values correctly\n");
        pass = false;
    }

    GraphUnit v_mod;
    setup_voice_unit(&v_mod, 0);
    std::vector<float> buf_mod(total, 0.0f);
    for (int off = 0; off < total; off += 128) {
        int chunk = std::min(128, total - off);
        unit_process_plaits(&v_mod, eng_mod, chunk, sr);
        std::memcpy(buf_mod.data() + off, v_mod.output_buffers[OPORT_OUT], chunk * sizeof(float));
    }
    orpheus_engine_destroy(eng_mod);

    // Diff should be significant
    float diff_sum = 0.0f;
    for (int i = 0; i < total; i++) {
        float d = buf_dry[i] - buf_mod[i];
        diff_sum += d * d;
    }
    float diff_rms = std::sqrt(diff_sum / total);
    printf("  Dry vs LFO-modulated diff_rms: %.6f\n", diff_rms);
    if (diff_rms < 0.001f) {
        printf("  FAIL: patch cable + depth should change voice output\n");
        pass = false;
    }

    printf("Patch cable + depth integration test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 6: Kotlin URI compatibility — test that ALL Kotlin port
// URIs and symbols are handled by C++ set_port
// ═══════════════════════════════════════════════════════════════════
static bool test_kotlin_port_compatibility() {
    printf("\n=== Test: Kotlin port URI/symbol compatibility ===\n");
    bool pass = true;

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    // Each test: set port via set_port, then read the atomic directly to verify
    struct PortTest {
        const char* uri;
        const char* symbol;
        float value;
        const char* description;
        bool should_work;
    };

    // Helper: read atomic value after set_port
    auto check = [&](const char* uri, const char* sym, float val, const char* desc,
                     float actual, float expected, bool should_work) {
        bool stored = std::fabs(actual - expected) < 0.01f;
        printf("  %s/%s = %.1f → actual=%.2f %s %s\n",
               uri, sym, val, actual,
               stored ? "STORED" : "IGNORED",
               should_work ? (stored ? "OK" : "FAIL") :
                            (stored ? "UNEXPECTED" : "(expected - known gap)"));
        if (should_work && !stored) {
            printf("    FAIL: port should be handled but value was not stored\n");
            pass = false;
        }
    };

    // Voice coupling
    engine->coupling_depth.store(0.0f);
    orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.voice", "coupling_depth", 0.5f);
    check("voice", "coupling_depth", 0.5f, "coupling_depth",
          engine->coupling_depth.load(), 0.5f, true);

    // Distortion drive (stored as 1 + value * 14)
    engine->drive_amount.store(1.0f);
    orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.distortion", "drive", 0.5f);
    check("distortion", "drive", 0.5f, "drive_amount (scaled)",
          engine->drive_amount.load(), 1.0f + 0.5f * 14.0f, true);

    // Distortion mix
    engine->drive_mix.store(0.0f);
    orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.distortion", "mix", 0.7f);
    check("distortion", "mix", 0.7f, "drive_mix",
          engine->drive_mix.load(), 0.7f, true);

    // Master pan
    engine->master_pan.store(0.0f);
    orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.stereo", "master_pan", -0.5f);
    check("stereo", "master_pan", -0.5f, "master_pan",
          engine->master_pan.load(), -0.5f, true);

    // Voice pan
    engine->voice_pan[0].store(0.0f);
    orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.stereo", "voice_pan_0", 0.3f);
    check("stereo", "voice_pan_0", 0.3f, "voice_pan[0]",
          engine->voice_pan[0].load(), 0.3f, true);

    // LFO params
    engine->lfo_freq_a.store(1.0f);
    orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.duolfo", "freq_a", 2.5f);
    check("duolfo", "freq_a", 2.5f, "lfo_freq_a",
          engine->lfo_freq_a.load(), 2.5f, true);

    engine->lfo_freq_b.store(1.0f);
    orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.duolfo", "freq_b", 3.0f);
    check("duolfo", "freq_b", 3.0f, "lfo_freq_b",
          engine->lfo_freq_b.load(), 3.0f, true);

    engine->lfo_shape.store(0.5f);
    orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.duolfo", "shape", 0.8f);
    check("duolfo", "shape", 0.8f, "lfo_shape",
          engine->lfo_shape.load(), 0.8f, true);

    engine->lfo_mode.store(1);
    orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.duolfo", "mode", 2.0f);
    check("duolfo", "mode", 2.0f, "lfo_mode",
          (float)engine->lfo_mode.load(), 2.0f, true);

    // Reverb
    engine->reverb_amount.store(0.0f);
    orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.reverb", "amount", 0.5f);
    check("reverb", "amount", 0.5f, "reverb_amount",
          engine->reverb_amount.load(), 0.5f, true);

    engine->reverb_time.store(0.5f);
    orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.reverb", "time", 0.6f);
    check("reverb", "time", 0.6f, "reverb_time",
          engine->reverb_time.load(), 0.6f, true);

    // Delay
    engine->delay_time_1.store(0.0f);
    orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.delay", "time_1", 0.4f);
    check("delay", "time_1", 0.4f, "delay_time_1",
          engine->delay_time_1.load(), 0.4f, true);

    engine->delay_mix.store(0.0f);
    orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.delay", "mix", 0.5f);
    check("delay", "mix", 0.5f, "delay_mix",
          engine->delay_mix.load(), 0.5f, true);

    // Patch cable via the patch URI: p<duo>_<voice>_<src>_<dst>
    orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.patch", "p0_1_15_3", 0.5f);
    check("patch", "p0_1_15_3", 0.5f, "patch_amount[0][1][15][3]",
          engine->patch_amount[0][1][15][3].load(), 0.5f, true);

    // Mod depth via Kotlin URI (voice plugin)
    engine->mod_depth[0].store(0.0f);
    orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.voice", "duo_mod_source_level_0", 0.8f);
    check("voice", "duo_mod_source_level_0", 0.8f, "mod_depth[0] (Kotlin URI)",
          engine->mod_depth[0].load(), 0.8f, true);

    // Mod depth via C++ URI (modulation plugin)
    engine->mod_depth[0].store(0.0f);
    orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.modulation", "mod_depth_0", 0.8f);
    check("modulation", "mod_depth_0", 0.8f, "mod_depth[0] (C++ URI)",
          engine->mod_depth[0].load(), 0.8f, true);

    // Vibrato — via voice URI
    engine->vibrato_depth.store(0.0f);
    orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.voice", "vibrato", 0.8f);
    check("voice", "vibrato", 0.8f, "vibrato_depth",
          engine->vibrato_depth.load(), 0.8f, true);

    orpheus_engine_destroy(engine);
    printf("Kotlin port compatibility test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 7: Patch symbol layout matches Kotlin patchCellSymbol
// cell = (voice * 16 + src) * 6 + dst, symbol p<duo>_<cell/96>_<cell%96/6>_<cell%6>
// ═══════════════════════════════════════════════════════════════════
static bool test_patch_symbol_layout() {
    printf("\n=== Test: Patch symbol layout ===\n");
    int wrong = 0;
    for (int duo = 0; duo < kNumPanelDuos; duo++) {
        for (int cell = 0; cell < 2 * kPatchSources * kPatchDests; cell++) {
            OrpheusEngine* engine = orpheus_engine_create(48000.0f);
            char sym[32];
            snprintf(sym, sizeof(sym), "p%d_%d_%d_%d", duo, cell / 96, cell % 96 / 6, cell % 6);
            orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.patch", sym, 0.5f);
            int v = cell / 96, src = cell % 96 / 6, dst = cell % 6;
            if (engine->patch_amount[duo][v][src][dst].load() != 0.5f) {
                if (wrong < 5) printf("  %s did not reach [%d][%d][%d][%d]\n", sym, duo, v, src, dst);
                wrong++;
            }
            orpheus_engine_destroy(engine);
        }
    }
    printf("Patch symbol layout test: %s (%d wrong)\n", wrong == 0 ? "PASS" : "FAIL", wrong);
    return wrong == 0;
}

// ═══════════════════════════════════════════════════════════════════
// Test 8: Vibrato depth via set_port
// ═══════════════════════════════════════════════════════════════════
static bool test_vibrato_port() {
    printf("\n=== Test: Vibrato depth ===\n");
    bool pass = true;

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);

    // Test dedicated API (what Kotlin actually uses via JNI)
    orpheus_engine_set_vibrato(engine, 0.8f);
    float stored = engine->vibrato_depth.load();
    printf("  vibrato_depth via dedicated API: %.2f (expect 0.80)\n", stored);
    if (std::fabs(stored - 0.8f) > 0.01f) {
        printf("  FAIL: vibrato not stored via dedicated API\n");
        pass = false;
    }

    // Document: set_port doesn't handle vibrato (would need voice URI handler)
    engine->vibrato_depth.store(0.0f);
    orpheus_engine_set_port(engine, "org.balch.orpheus.plugins.voice", "vibrato", 0.8f);
    float via_port = engine->vibrato_depth.load();
    printf("  vibrato_depth via set_port: %.2f (known gap — uses dedicated API instead)\n", via_port);

    orpheus_engine_destroy(engine);
    printf("Vibrato port test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test 9: Delay parameters via set_port
// ═══════════════════════════════════════════════════════════════════
static bool test_delay_ports() {
    printf("\n=== Test: Delay parameters via set_port ===\n");
    bool pass = true;

    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    const char* uri = "org.balch.orpheus.plugins.delay";

    orpheus_engine_set_port(engine, uri, "time_1", 0.4f);
    orpheus_engine_set_port(engine, uri, "time_2", 0.6f);
    orpheus_engine_set_port(engine, uri, "feedback", 0.3f);
    orpheus_engine_set_port(engine, uri, "mix", 0.5f);

    // Read atomics directly (get_port is a stub)
    float t1 = engine->delay_time_1.load();
    float t2 = engine->delay_time_2.load();
    float fb = engine->delay_feedback.load();
    float mx = engine->delay_mix.load();

    printf("  time_1=%.2f time_2=%.2f feedback=%.2f mix=%.2f\n", t1, t2, fb, mx);

    if (std::fabs(t1 - 0.4f) > 0.01f || std::fabs(t2 - 0.6f) > 0.01f ||
        std::fabs(fb - 0.3f) > 0.01f || std::fabs(mx - 0.5f) > 0.01f) {
        printf("  FAIL: delay parameters not stored correctly\n");
        pass = false;
    }

    orpheus_engine_destroy(engine);
    printf("Delay ports test: %s\n", pass ? "PASS" : "FAIL");
    return pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test runner
// ═══════════════════════════════════════════════════════════════════
bool run_control_routing_tests() {
    int suite_pass = 0, suite_fail = 0;
    auto tally = [&](bool ok) { if (ok) ++suite_pass; else ++suite_fail; };
    tally(test_patch_port_routing());
    tally(test_distortion_mix_port());
    tally(test_master_pan_port());
    tally(test_voice_timbre_morph());
    tally(test_patch_depth_integration());
    tally(test_kotlin_port_compatibility());
    tally(test_patch_symbol_layout());
    tally(test_vibrato_port());
    tally(test_delay_ports());
    TEST_SUITE_RETURN(suite_pass, suite_fail);
}
