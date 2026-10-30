// Legacy LFO/Tides patches park the HARMONICS cable on DX, so ch2 must not move DX patch selection.
// Only ch2 carries signal here, so depth 0 vs 1 isolates the harmonics route.
#include "test_harness.h"
#include "patch_test_helpers.h"
#include "dx_engine_range.h"

static constexpr int kFrames = 12000;

static std::vector<float> render_ch2_only(int engine_idx, int mod_source, float depth) {
    OrpheusEngine* engine = orpheus_engine_create(48000.0f);
    activate_voice(engine, 0, engine_idx, 60.0f, 0.5f, 0.5f, 0.5f, 0.5f);
    set_legacy_patch(engine, 0, mod_source, is_dx_engine_index(engine_idx));
    engine->mod_depth[0].store(depth);
    for (int i = 0; i < kMaxFrames; i++) {
        engine->lfo_output_buffer[i] = 0.0f;
        engine->lfo_morph_buffer[i] = 0.0f;
        engine->lfo_pitch_buffer[i] = 0.0f;
        engine->lfo_harmonics_buffer[i] = (mod_source == 2) ? 0.8f : 0.0f;
        for (int c = 0; c < 4; c++) engine->tides_output_buffer[c][i] = 0.0f;
        if (mod_source == 4) engine->tides_output_buffer[2][i] = 0.8f;
    }
    GraphUnit v0;
    setup_voice_unit(&v0, 0);
    std::vector<float> out;
    out.reserve(kFrames);
    for (int off = 0; off < kFrames; off += 128) {
        int chunk = std::min(128, kFrames - off);
        unit_process_plaits(&v0, engine, chunk, 48000.0f);
        for (int i = 0; i < chunk; i++) out.push_back(v0.output_buffers[OPORT_OUT][i]);
    }
    orpheus_engine_destroy(engine);
    return out;
}

static bool identical(const std::vector<float>& a, const std::vector<float>& b) {
    return a.size() == b.size() && std::memcmp(a.data(), b.data(), a.size() * sizeof(float)) == 0;
}

static float peak(const std::vector<float>& a) {
    float p = 0.0f;
    for (float s : a) p = std::max(p, std::fabs(s));
    return p;
}

static bool test_dx_parked_harmonics_holds() {
    printf("\n=== Test: a parked (amount 0) HARMONICS cell leaves DX unchanged ===\n");
    bool pass = true;
    for (int eng : {2, 3, 4}) {
        for (int src : {2, 4}) {
            auto off = render_ch2_only(eng, src, 0.0f);
            auto on = render_ch2_only(eng, src, 1.0f);
            bool ok = peak(off) > 0.001f && identical(off, on);
            printf("  engine %d src %d: peak=%.4f %s\n", eng, src, peak(off), ok ? "identical" : "DIFFERS");
            pass &= ok;
        }
    }
    printf(pass ? "  PASS\n" : "  FAIL\n");
    return pass;
}

static bool test_va_ch2_still_moves() {
    printf("\n=== Test: VA still follows LFO/Tides ch2 ===\n");
    bool pass = true;
    for (int src : {2, 4}) {
        auto off = render_ch2_only(8, src, 0.0f);
        auto on = render_ch2_only(8, src, 1.0f);
        bool ok = !identical(off, on);
        printf("  engine 8 src %d: %s\n", src, ok ? "differs" : "IDENTICAL");
        pass &= ok;
    }
    printf(pass ? "  PASS\n" : "  FAIL\n");
    return pass;
}

bool run_plaits_dx_lfo_tests() {
    int p = 0, f = 0;
    auto tally = [&](bool ok) { if (ok) ++p; else ++f; };
    tally(test_dx_parked_harmonics_holds());
    tally(test_va_ch2_still_moves());
    TEST_SUITE_RETURN(p, f);
}
