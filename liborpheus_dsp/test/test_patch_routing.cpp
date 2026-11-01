// Duo patch matrix routing: legacy exactness, port parsing, DX harmonics, ENV, pooled duos.
#include "test_harness.h"
#include "patch_test_helpers.h"
#include "dx_engine_range.h"
#include <random>

namespace {

constexpr int kFrames = 256;
constexpr float kDepth = 0.37f;

// ── Legacy formulas, copied from orpheus_unit_plaits.cpp before the patch matrix ──

static inline bool lfo_ch2_targets_harmonics(int engine_index) {
    return !is_dx_engine_index(engine_index);
}

struct LegacyMods {
    float timbre = 0.0f, morph = 0.0f, harmonics = 0.0f, note = 0.0f;
    bool harmonics_applied = false;
    int fm_mod_source = -1;
    int fm_voice_idx = -1;
    float fm_flux_signal = 0.0f;
};

// The per-voice mod block plus the multi-channel blocks, as offsets. md was smooth_mod_depth,
// fd smooth_fm_depth; both followed the same port, so the test passes the same depth.
LegacyMods legacy_mods(OrpheusEngine* engine, int idx, int src, int engine_index, int num_frames,
                       float duo_mod_signal, float md, float fd) {
    LegacyMods o;
    int duo = idx / 2;
    float mod_signal = 0.0f;
    if (src == 0) { // VOICE_FM
        int fm_source;
        if (!engine->fm_cross_quad.load(std::memory_order_relaxed)) {
            fm_source = (idx % 2 == 0) ? idx + 1 : idx - 1;
        } else {
            fm_source = (idx - 2 + 8) % 8;
        }
        if (fm_source >= 0 && fm_source < kNumVoices) {
            o.fm_mod_source = 0;
            o.fm_voice_idx = fm_source;
            mod_signal = (duo_mod_signal >= 0.0f)
                ? duo_mod_signal
                : engine->voice_fm_buffer[fm_source][num_frames / 2];
        }
    } else if (src == 2) { // LFO
        o.fm_mod_source = 2;
        mod_signal = engine->lfo_output_buffer[num_frames / 2];
    } else if (src == 3) { // FLUX (Marbles CV)
        o.fm_mod_source = 3;
        o.fm_flux_signal = engine->marbles_cv_output[duo % 2];
        mod_signal = o.fm_flux_signal;
    } else if (src == 4) { // TIDES
        o.fm_mod_source = 4;
        mod_signal = engine->tides_output_buffer[0][num_frames / 2];
    }
    o.timbre = mod_signal * md;

    if (o.fm_mod_source == 2 || o.fm_mod_source == 4) {
        float morph_src = (o.fm_mod_source == 4)
            ? engine->tides_output_buffer[1][num_frames / 2]
            : engine->lfo_morph_buffer[num_frames / 2];
        float harm_src = (o.fm_mod_source == 4)
            ? engine->tides_output_buffer[2][num_frames / 2]
            : engine->lfo_harmonics_buffer[num_frames / 2];
        o.morph = morph_src * fd * 0.5f;
        // The OSC branch always applied ch2; the Plaits branch skipped it on DX.
        if (engine_index < 0 || lfo_ch2_targets_harmonics(engine_index)) {
            o.harmonics = harm_src * fd * 0.5f;
            o.harmonics_applied = true;
        }
        float ps = (o.fm_mod_source == 4)
            ? engine->tides_output_buffer[3][num_frames / 2]
            : engine->lfo_pitch_buffer[num_frames / 2];
        o.note = ps * fd * 0.5f;
    }
    return o;
}

// The OSC per-sample FM term, as an offset. The source read `s * fd * 200.0f`; the shipped
// -O3 -ffast-math build hoisted fd * 200 out of the sample loop and fused s * (fd * 200) into
// freq (checked in the asm), so the per-sample terms are spelled in that order. FLUX, a block
// constant, kept (flux * fd) * 200.
float legacy_fm_hz(OrpheusEngine* engine, const LegacyMods& o, int i, float fd) {
    if (o.fm_mod_source == 0 && o.fm_voice_idx >= 0) {
        float fm_sample = engine->voice_fm_buffer[o.fm_voice_idx][i];
        return fm_sample * (fd * 200.0f);
    } else if (o.fm_mod_source == 2) {
        float lfo_fm = engine->lfo_output_buffer[i];
        return lfo_fm * (fd * 200.0f);
    } else if (o.fm_mod_source == 3) {
        return o.fm_flux_signal * fd * 200.0f;
    } else if (o.fm_mod_source == 4) {
        return engine->tides_output_buffer[0][i] * (fd * 200.0f);
    }
    return 0.0f;
}

bool same_bits(float a, float b) { return std::memcmp(&a, &b, sizeof(float)) == 0; }

void fill_random(OrpheusEngine* e, std::mt19937& rng) {
    std::uniform_real_distribution<float> bi(-1.0f, 1.0f);
    for (int i = 0; i < kMaxFrames; i++) {
        e->lfo_output_buffer[i] = bi(rng);
        e->lfo_morph_buffer[i] = bi(rng);
        e->lfo_harmonics_buffer[i] = bi(rng);
        e->lfo_pitch_buffer[i] = bi(rng);
        for (int c = 0; c < 4; c++) e->tides_output_buffer[c][i] = bi(rng);
        e->marbles_x3_buffer[i] = bi(rng);
        e->marbles_y_buffer[i] = bi(rng);
        e->marbles_t1_buffer[i] = bi(rng);
        e->marbles_t2_buffer[i] = bi(rng);
        e->marbles_t3_buffer[i] = bi(rng);
        for (int v = 0; v < kNumMainVoices; v++) e->voice_fm_buffer[v][i] = bi(rng);
    }
    e->marbles_cv_output[0] = bi(rng);
    e->marbles_cv_output[1] = bi(rng);
}

// Offsets only. The unit folds them into its bases inside its own -ffast-math loops, where FMA
// contraction is per call site, so the guard for that step is the preset render cmp
// (ORPHEUS_PRESET_RENDER_DUMP before/after), not this test.
bool test_patch_matches_legacy_formulas() {
    printf("\n=== Test: legacy patches reproduce the legacy formulas bit for bit ===\n");
    const int engines[] = {-1, 8, 2, 100, 200};  // OSC, VA, DX, Braids, Chaos
    const char* names[] = {"VOICE_FM", "OFF", "LFO", "FLUX", "TIDES"};
    OrpheusEngine* e = orpheus_engine_create(48000.0f);
    std::mt19937 rng(0x5eed);
    int checks = 0, failures = 0;
    for (int eng : engines) {
        bool on_dx = is_dx_engine_index(eng);
        for (int duo = 0; duo < kNumPanelDuos; duo++) {
            for (int xq = 0; xq < 2; xq++) {
                e->fm_cross_quad.store(xq);
                for (int src = 0; src < 5; src++) {
                    fill_random(e, rng);
                    set_legacy_patch(e, duo, src, on_dx);
                    for (int slot = 0; slot < 2; slot++) {
                        int idx = duo * 2 + slot;
                        // unit_process_duo_voice passed the duo partner's block RMS.
                        float rms = patch_block_rms(e->voice_fm_buffer[idx ^ 1], kFrames);
                        LegacyMods L = legacy_mods(e, idx, src, eng, kFrames, rms, kDepth, kDepth);
                        PatchVoiceMods P = patch_block_mods(*e, duo, slot, idx, kFrames, kDepth);
                        bool ok = same_bits(L.timbre, P.timbre) && same_bits(L.morph, P.morph) &&
                                  same_bits(L.note, P.note) && same_bits(0.0f, P.env);
                        // Where legacy skipped harmonics (DX), the cable is parked: exactly +0.
                        ok &= same_bits(L.harmonics_applied ? L.harmonics : 0.0f, P.harmonics);
                        // The patch side picks its own VOICE_FM partner; it must be the legacy one.
                        int partner_idx = patch_fm_partner(*e, idx);
                        if (src == kLegacyVoiceFm) ok &= partner_idx == L.fm_voice_idx;
                        PatchFmTaps taps;
                        patch_fm_taps(*e, duo, slot, &taps);
                        int bad_sample = -1;
                        for (int i = 0; i < kFrames && bad_sample < 0; i++) {
                            float partner = (partner_idx >= 0) ? e->voice_fm_buffer[partner_idx][i] : 0.0f;
                            if (!same_bits(legacy_fm_hz(e, L, i, kDepth),
                                           patch_fm_hz(taps, *e, i, partner, kDepth)))
                                bad_sample = i;
                        }
                        ok &= bad_sample < 0;
                        checks++;
                        if (!ok) {
                            failures++;
                            if (failures <= 10)
                                printf("  MISMATCH engine %d duo %d xq %d %s slot %d: timbre %.9g/%.9g morph %.9g/%.9g "
                                       "harm %.9g/%.9g note %.9g/%.9g env %.9g fm sample %d\n",
                                       eng, duo, xq, names[src], slot, L.timbre, P.timbre, L.morph, P.morph,
                                       L.harmonics, P.harmonics, L.note, P.note, P.env, bad_sample);
                        }
                    }
                }
            }
        }
    }
    orpheus_engine_destroy(e);
    printf("  %d checks, %d mismatches %s\n", checks, failures, failures == 0 ? "PASS" : "FAIL");
    return failures == 0;
}

bool test_patch_port_parse() {
    printf("\n=== Test: patch port symbol parsing ===\n");
    OrpheusEngine* e = orpheus_engine_create(48000.0f);
    const char* uri = "org.balch.orpheus.plugins.patch";
    orpheus_engine_set_port(e, uri, "p3_1_8_2", 0.25f);
    bool pass = e->patch_amount[3][1][8][2].load() == 0.25f;
    printf("  p3_1_8_2 -> %.3f %s\n", e->patch_amount[3][1][8][2].load(), pass ? "ok" : "WRONG");
    for (const char* bad : {"p6_0_0_0", "p0_2_0_0", "p0_0_16_0", "p0_0_0_6", "px", "p", "p0_0_0", "p0_0_0_0_0", "p-1_0_0_0"}) {
        orpheus_engine_set_port(e, uri, bad, 0.75f);
    }
    int changed = 0;
    for (int d = 0; d < kNumPanelDuos; d++)
        for (int v = 0; v < 2; v++)
            for (int s = 0; s < kPatchSources; s++)
                for (int t = 0; t < kPatchDests; t++) {
                    float want = (d == 3 && v == 1 && s == 8 && t == 2) ? 0.25f : 0.0f;
                    if (e->patch_amount[d][v][s][t].load() != want) changed++;
                }
    printf("  bad symbols changed %d cells %s\n", changed, changed == 0 ? "ok" : "WRONG");
    pass &= changed == 0;
    bool finite_ok = true;
    for (float bad : {std::nanf(""), INFINITY, -INFINITY}) {
        orpheus_engine_set_port(e, uri, "p3_1_8_2", bad);
        finite_ok &= same_bits(e->patch_amount[3][1][8][2].load(), 0.0f);
    }
    printf("  non-finite amounts stored as 0 %s\n", finite_ok ? "ok" : "WRONG");
    pass &= finite_ok;
    orpheus_engine_destroy(e);
    printf(pass ? "  PASS\n" : "  FAIL\n");
    return pass;
}

struct DuoRender { std::vector<float> a, b; };

DuoRender render_duo(OrpheusEngine* e, int duo, int blocks, int gate_blocks = -1) {
    GraphUnit unit = {};
    unit.type = UNIT_DUO_VOICE;
    unit.enabled = true;
    unit_init(&unit, 48000.0f);  // resets the module index, so set it after
    unit.state.module.index = duo;
    DuoRender r;
    for (int blk = 0; blk < blocks; blk++) {
        if (blk == gate_blocks) {
            e->voice_params[duo * 2].gate.store(0);
            e->voice_params[duo * 2 + 1].gate.store(0);
        }
        unit_process_duo_voice(&unit, e, 128, 48000.0f);
        r.a.insert(r.a.end(), unit.output_buffers[OPORT_OUT], unit.output_buffers[OPORT_OUT] + 128);
        r.b.insert(r.b.end(), unit.output_buffers[OPORT_AUX], unit.output_buffers[OPORT_AUX] + 128);
    }
    return r;
}

bool identical(const std::vector<float>& x, const std::vector<float>& y) {
    return x.size() == y.size() && std::memcmp(x.data(), y.data(), x.size() * sizeof(float)) == 0;
}

float peak_of(const std::vector<float>& x) {
    float p = 0.0f;
    for (float s : x) p = std::max(p, std::fabs(s));
    return p;
}

float window_rms(const std::vector<float>& x, int from, int to) {
    double s = 0.0;
    for (int i = from; i < to; i++) s += (double)x[i] * x[i];
    return (float)std::sqrt(s / (to - from));
}

DuoRender render_dx_harmonics(float amount) {
    OrpheusEngine* e = orpheus_engine_create(48000.0f);
    activate_voice(e, 0, 2, 60.0f, 0.1f, 0.5f, 0.5f, 0.0f);
    activate_voice(e, 1, 2, 67.0f, 0.1f, 0.5f, 0.5f, 0.0f);
    e->mod_depth[0].store(1.0f);
    for (int i = 0; i < kMaxFrames; i++) e->lfo_harmonics_buffer[i] = 0.8f;
    set_patch_cell(e, 0, -1, kPatchSrcLfoB, kPatchDstHarmonics, amount);
    DuoRender r = render_duo(e, 0, 80);
    orpheus_engine_destroy(e);
    return r;
}

bool test_dx_harmonics_cable_reaches_dx() {
    printf("\n=== Test: a HARMONICS cable reaches a DX duo ===\n");
    DuoRender off = render_dx_harmonics(0.0f);
    DuoRender on = render_dx_harmonics(1.0f);
    bool pass = peak_of(off.a) > 0.001f && !identical(off.a, on.a) && !identical(off.b, on.b);
    printf("  peak %.4f, A %s, B %s\n", peak_of(off.a), identical(off.a, on.a) ? "IDENTICAL" : "differs",
           identical(off.b, on.b) ? "IDENTICAL" : "differs");
    printf(pass ? "  PASS\n" : "  FAIL\n");
    return pass;
}

DuoRender render_env(int engine_index, float amount) {
    OrpheusEngine* e = orpheus_engine_create(48000.0f);
    activate_voice(e, 0, engine_index, 60.0f, 0.3f, 0.5f, 0.5f, 0.1f);
    activate_voice(e, 1, engine_index, 67.0f, 0.3f, 0.5f, 0.5f, 0.1f);
    e->mod_depth[0].store(1.0f);
    for (int i = 0; i < kMaxFrames; i++) e->lfo_output_buffer[i] = 0.5f;
    set_patch_cell(e, 0, 0, kPatchSrcLfoAB, kPatchDstEnv, amount);  // voice A only
    DuoRender r = render_duo(e, 0, 400, 188);  // ~0.5 s gate, ~0.57 s after release
    orpheus_engine_destroy(e);
    return r;
}

bool test_env_cable_moves_decay() {
    printf("\n=== Test: an ENV cable lengthens voice A's release only ===\n");
    bool pass = true;
    for (int eng : {-1, 8}) {
        DuoRender base = render_env(eng, 0.0f);
        DuoRender env = render_env(eng, 1.0f);
        int from = (188 + 48) * 128, to = 400 * 128;  // 0.25 s after release onward
        float tail_base = window_rms(base.a, from, to);
        float tail_env = window_rms(env.a, from, to);
        bool b_same = identical(base.b, env.b);
        bool ok = tail_env > 1e-4f && tail_env > 2.0f * tail_base && b_same;
        printf("  engine %d: A tail %.6f -> %.6f, B %s %s\n", eng, tail_base, tail_env,
               b_same ? "unchanged" : "CHANGED", ok ? "ok" : "FAIL");
        pass &= ok;
    }
    printf(pass ? "  PASS\n" : "  FAIL\n");
    return pass;
}

DuoRender render_pooled(int engine_index, float panel_amount) {
    OrpheusEngine* e = orpheus_engine_create(48000.0f);
    constexpr int kDuo = 7;
    activate_voice(e, kDuo * 2, engine_index, 60.0f);
    activate_voice(e, kDuo * 2 + 1, engine_index, 67.0f);
    e->mod_depth[kDuo].store(1.0f);
    for (int i = 0; i < kMaxFrames; i++) {
        e->lfo_output_buffer[i] = 0.6f;
        e->lfo_morph_buffer[i] = 0.6f;
        e->lfo_harmonics_buffer[i] = 0.6f;
        e->lfo_pitch_buffer[i] = 0.6f;
        for (int c = 0; c < 4; c++) e->tides_output_buffer[c][i] = 0.6f;
    }
    for (int d = 0; d < kNumPanelDuos; d++)
        for (int s = 0; s < kPatchSources; s++)
            for (int t = 0; t < kPatchDests; t++)
                set_patch_cell(e, d, -1, s, t, panel_amount);
    DuoRender r = render_duo(e, kDuo, 40);
    orpheus_engine_destroy(e);
    return r;
}

bool test_pooled_duos_ignore_patch() {
    printf("\n=== Test: pooled duo 7 never reads the patch ===\n");
    bool pass = true;
    for (int eng : {-1, 8}) {
        DuoRender zero = render_pooled(eng, 0.0f);
        DuoRender full = render_pooled(eng, 1.0f);
        bool ok = peak_of(zero.a) > 0.001f && identical(zero.a, full.a) && identical(zero.b, full.b);
        printf("  engine %d: peak %.4f %s\n", eng, peak_of(zero.a), ok ? "unchanged" : "CHANGED");
        pass &= ok;
    }
    printf(pass ? "  PASS\n" : "  FAIL\n");
    return pass;
}

}  // namespace

bool run_patch_routing_tests() {
    int p = 0, f = 0;
    auto tally = [&](bool ok) { if (ok) ++p; else ++f; };
    tally(test_patch_matches_legacy_formulas());
    tally(test_patch_port_parse());
    tally(test_dx_harmonics_cable_reaches_dx());
    tally(test_env_cable_moves_decay());
    tally(test_pooled_duos_ignore_patch());
    TEST_SUITE_RETURN(p, f);
}
