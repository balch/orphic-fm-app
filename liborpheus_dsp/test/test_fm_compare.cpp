#include "test_harness.h"
#include "orpheus_units.h"
#include "orpheus_engine.h"
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>
#include <algorithm>

/**
 * FM / modulation comparison tests: C++ duo voice rendering.
 *
 * Produces WAV files under test/output/ with prefix `cpp_fm_`.
 * Counterpart: JsynFmCompareTest.kt produces `jsyn_fm_` files.
 * Run `compare_fm.py` to tabulate RMS / peak / ZCR differences.
 *
 * Scenarios match JSyn test exactly:
 *  1. dry          – duo pair, no modulation (baseline)
 *  2. voice_fm_lo  – VOICE_FM depth=0.2
 *  3. voice_fm_hi  – VOICE_FM depth=0.8
 *  4. lfo_fm       – LFO at 5 Hz → FM depth=0.5
 *  5. vibrato      – LFO vibrato depth matching JSyn (~88 Hz at C4 = ~2 semitones)
 *  6. coupling     – voice coupling depth=0.5 (depth * 30Hz = 15 Hz)
 *  7. feedback     – self-feedback (harmonics) 0.5
 */

static constexpr float SR = 48000.0f;
static constexpr float NOTE_A = 60.0f;  // C4
static constexpr float NOTE_B = 67.0f;  // G4
static constexpr float DURATION = 2.0f;
static constexpr float GATE_DUR = 1.0f;
static constexpr float ENV_SPEED = 0.5f;

struct FmScenario {
    const char* name;
    int   mod_source;    // 0=VOICE_FM, 1=OFF, 2=LFO, 3=FLUX
    float fm_depth;
    float coupling;      // coupling_depth atomic value (0-1)
    float vibrato_depth; // vibrato_depth atomic value (0-1)
    float lfo_freq_a;    // LFO frequency Hz
    float harmonics;     // self-feedback amount (voice harmonics)
    int   engine_a;      // -1=Engine 0, 0+=Plaits engine index
    int   engine_b;      // -1=Engine 0, 0+=Plaits engine index
};

// Create a minimal graph: LFO → DuoVoice → MasterOut
// LFO must come before DuoVoice in execution order (provides lfo_output_buffer)
static OrpheusGraph* create_duo_graph(OrpheusEngine* engine) {
    auto* g = new OrpheusGraph();
    std::memset(g, 0, sizeof(OrpheusGraph));
    g->sample_rate = SR;

    // Unit 0: HyperLFO (fills engine->lfo_output_buffer)
    g->units[0].type = UNIT_HYPER_LFO;
    g->units[0].id = 0;
    g->units[0].enabled = true;
    unit_init(&g->units[0], SR);

    // Unit 1: DuoVoice (duo index 0 → voices 0,1)
    g->units[1].type = UNIT_DUO_VOICE;
    g->units[1].id = 1;
    g->units[1].enabled = true;
    unit_init(&g->units[1], SR);
    g->units[1].state.module.index = 0;  // duo 0

    // Unit 2: MasterOut (voice A → left, voice B → right)
    g->units[2].type = UNIT_MASTER_OUT;
    g->units[2].id = 2;
    g->units[2].enabled = true;
    unit_init(&g->units[2], SR);
    // Left = voice A (OPORT_OUT), Right = voice B (OPORT_AUX)
    g->units[2].inputs[IPORT_INPUT_A].sources[0] = g->units[1].output_buffers[OPORT_OUT];
    g->units[2].inputs[IPORT_INPUT_A].num_sources = 1;
    g->units[2].inputs[IPORT_INPUT_B].sources[0] = g->units[1].output_buffers[OPORT_AUX];
    g->units[2].inputs[IPORT_INPUT_B].num_sources = 1;

    g->unit_count = 3;
    g->exec_count = 3;
    g->exec_order[0] = 0;  // LFO first
    g->exec_order[1] = 1;  // DuoVoice
    g->exec_order[2] = 2;  // MasterOut
    g->master_out_index = 2;

    engine->graph.store(g, std::memory_order_release);
    return g;
}

static std::vector<float> render_fm_scenario(const FmScenario& s) {
    OrpheusEngine* engine = orpheus_engine_create(SR);

    // Voice A
    engine->voice_params[0].active.store(1);
    engine->voice_params[0].ever_triggered.store(1);
    engine->voice_params[0].engine_index.store(s.engine_a);
    engine->voice_params[0].tune.store(NOTE_A);
    engine->voice_params[0].timbre.store(0.5f);
    engine->voice_params[0].morph.store(0.5f);
    engine->voice_params[0].harmonics.store(s.harmonics);
    engine->voice_params[0].decay.store(ENV_SPEED);

    // Voice B
    engine->voice_params[1].active.store(1);
    engine->voice_params[1].ever_triggered.store(1);
    engine->voice_params[1].engine_index.store(s.engine_b);
    engine->voice_params[1].tune.store(NOTE_B);
    engine->voice_params[1].timbre.store(0.5f);
    engine->voice_params[1].morph.store(0.5f);
    engine->voice_params[1].harmonics.store(s.harmonics);
    engine->voice_params[1].decay.store(ENV_SPEED);

    // Mod source and FM depth (per-duo, duo 0)
    engine->mod_source[0].store(s.mod_source);
    engine->fm_depth[0].store(s.fm_depth);
    engine->mod_depth[0].store(s.fm_depth);  // Plaits cross-mod uses mod_depth for RMS-based timbre modulation
    engine->fm_cross_quad.store(0);  // standard duo pairs

    // Coupling
    engine->coupling_depth.store(s.coupling);

    // Vibrato (dedicated sine oscillator, depth * 20 Hz matching JSyn VibratoPlugin)
    engine->vibrato_depth.store(s.vibrato_depth);
    engine->vibrato_rate.store(5.0f);   // 5 Hz (matches JSyn VibratoPlugin default)

    // HyperLFO (used for LFO FM modulation, NOT for vibrato)
    engine->lfo_freq_a.store(s.lfo_freq_a);
    engine->lfo_freq_b.store(s.lfo_freq_a);
    engine->lfo_shape.store(1.0f);  // triangle (matches JSyn sine roughly)
    engine->lfo_mode.store(0);      // AND mode (produces output; OFF=1 is silence)

    // Master volume
    engine->master_volume.store(1.0f);
    engine->master_pan.store(0.0f);

    auto* graph = create_duo_graph(engine);

    int total = (int)(SR * DURATION);
    int gate_frames = (int)(SR * GATE_DUR);
    std::vector<float> buf(total * 2, 0.0f);

    for (int off = 0; off < total; off += 128) {
        int chunk = std::min(128, total - off);
        if (off == 0) {
            engine->voice_params[0].gate.store(1);
            engine->voice_params[1].gate.store(1);
        }
        if (off >= gate_frames) {
            engine->voice_params[0].gate.store(0);
            engine->voice_params[1].gate.store(0);
        }
        orpheus_graph_process(graph, engine, buf.data() + off * 2, chunk);
    }

    // Clear engine's graph pointer to avoid double-free in orpheus_engine_destroy
    engine->graph.store(nullptr, std::memory_order_relaxed);
    delete graph;
    orpheus_engine_destroy(engine);
    return buf;
}

bool run_fm_compare_tests() {
    printf("\n=== FM Comparison Tests (C++) ===\n");

    // Vibrato depth mapping (now aligned with JSyn VibratoPlugin):
    // C++ dedicated vibrato oscillator: sine(rate) * depth * 20.0 Hz
    // JSyn VibratoPlugin: sine(rate) * depth * 20.0 Hz
    // Both use depth=1.0 → ±20 Hz vibrato at 5 Hz rate.
    // JSyn test previously used 88 Hz (vibratoDepthHz=88), updated to match C++ range.

    FmScenario scenarios[] = {
        {"dry",          1, 0.0f, 0.0f, 0.0f,  5.0f, 0.0f, -1, -1},
        {"voice_fm_lo",  0, 0.2f, 0.0f, 0.0f,  5.0f, 0.0f, -1, -1},
        {"voice_fm_hi",  0, 0.8f, 0.0f, 0.0f,  5.0f, 0.0f, -1, -1},
        {"lfo_fm",       2, 0.5f, 0.0f, 0.0f,  5.0f, 0.0f, -1, -1},
        {"vibrato",      1, 0.0f, 0.0f, 1.0f,  5.0f, 0.0f, -1, -1},
        {"coupling",     1, 0.0f, 0.5f, 0.0f,  5.0f, 0.0f, -1, -1},
        {"feedback",     1, 0.0f, 0.0f, 0.0f,  5.0f, 0.5f, -1, -1},
        // ── Plaits duo FM scenarios (engine 8 = VA, reliable output) ──
        {"plaits_dry",      1, 0.0f, 0.0f, 0.0f, 5.0f, 0.0f,  8,  8},
        {"plaits_fm_lo",    0, 0.2f, 0.0f, 0.0f, 5.0f, 0.0f,  8,  8},
        {"plaits_fm_hi",    0, 0.8f, 0.0f, 0.0f, 5.0f, 0.0f,  8,  8},
        // ── Mixed duo FM scenarios (Engine 0 + Plaits VA) ──
        {"mixed_dry",       1, 0.0f, 0.0f, 0.0f, 5.0f, 0.0f, -1,  8},
        {"mixed_fm_lo",     0, 0.2f, 0.0f, 0.0f, 5.0f, 0.0f, -1,  8},
        {"mixed_fm_hi",     0, 0.8f, 0.0f, 0.0f, 5.0f, 0.0f, -1,  8},
    };

    printf("%-20s | %8s %8s %8s %8s\n", "Scenario", "RMS_L", "Peak_L", "RMS_R", "Peak_R");
    printf("----------------------------------------------------------------------\n");

    mkdir("test", 0755);
    mkdir("test/output", 0755);
    const char* output_dir = "test/output";

    bool all_pass = true;
    int num_scenarios = sizeof(scenarios) / sizeof(scenarios[0]);
    for (int si = 0; si < num_scenarios; si++) {
        const auto& s = scenarios[si];
        auto buf = render_fm_scenario(s);
        int total = (int)(SR * DURATION);

        // Deinterleave
        std::vector<float> left(total), right(total);
        for (int i = 0; i < total; i++) {
            left[i] = buf[i * 2];
            right[i] = buf[i * 2 + 1];
        }

        float rms_l = compute_rms(left.data(), total);
        float rms_r = compute_rms(right.data(), total);
        float pk_l = compute_peak(left.data(), total);
        float pk_r = compute_peak(right.data(), total);

        printf("%-20s | %8.4f %8.4f %8.4f %8.4f\n", s.name, rms_l, pk_l, rms_r, pk_r);

        // Write WAV
        char path[256];
        snprintf(path, sizeof(path), "%s/cpp_fm_%s.wav", output_dir, s.name);
        write_wav(path, buf.data(), total, (int)SR);

        // Dry scenario must produce sound
        if (strcmp(s.name, "dry") == 0) {
            if (rms_l < 0.001f || rms_r < 0.001f) {
                printf("  FAIL: dry scenario produced silence\n");
                all_pass = false;
            }
        }
    }

    printf("\nFM comparison WAVs written. Run JSyn counterpart:\n");
    printf("  ./gradlew :core:dsp-engine:jvmTest --tests '*JsynFmCompareTest'\n");
    printf("Then compare:\n");
    printf("  python3 %s/compare_fm.py\n", output_dir);

    // ── Plaits duo FM verification ──
    // Plaits VOICE_FM should produce measurably different output from dry baseline
    {
        auto plaits_dry = render_fm_scenario({"plaits_dry", 1, 0.0f, 0.0f, 0.0f, 5.0f, 0.0f, 8, 8});
        auto plaits_fm  = render_fm_scenario({"plaits_fm_hi", 0, 0.8f, 0.0f, 0.0f, 5.0f, 0.0f, 8, 8});
        int n = (int)(SR * DURATION);
        float dry_rms = compute_rms(plaits_dry.data(), n * 2);
        float fm_rms  = compute_rms(plaits_fm.data(), n * 2);
        float diff = std::fabs(fm_rms - dry_rms);
        printf("\nPlaits duo FM check: dry_rms=%.4f fm_rms=%.4f diff=%.4f\n", dry_rms, fm_rms, diff);
        if (diff < 0.001f) {
            printf("  FAIL: Plaits VOICE_FM produced no timbral change vs dry\n");
            all_pass = false;
        } else {
            printf("  PASS: Plaits VOICE_FM produces measurable timbral change\n");
        }
    }

    // ── Mixed duo FM verification ──
    // Only the Plaits voice (right channel, odd samples) receives cross-mod.
    // Engine 0 (left channel) is largely unchanged, so compare right channel only.
    {
        auto mixed_dry = render_fm_scenario({"mixed_dry", 1, 0.0f, 0.0f, 0.0f, 5.0f, 0.0f, -1, 8});
        auto mixed_fm  = render_fm_scenario({"mixed_fm_hi", 0, 0.8f, 0.0f, 0.0f, 5.0f, 0.0f, -1, 8});
        int n = (int)(SR * DURATION);
        // Extract right channel (Plaits voice B) from interleaved stereo
        float dry_sum_sq = 0.0f, fm_sum_sq = 0.0f;
        for (int i = 0; i < n; i++) {
            float d = mixed_dry[i * 2 + 1];
            float f = mixed_fm[i * 2 + 1];
            dry_sum_sq += d * d;
            fm_sum_sq += f * f;
        }
        float dry_rms = std::sqrt(dry_sum_sq / n);
        float fm_rms  = std::sqrt(fm_sum_sq / n);
        float diff = std::fabs(fm_rms - dry_rms);
        printf("\nMixed duo FM check (R chan): dry_rms=%.4f fm_rms=%.4f diff=%.4f\n", dry_rms, fm_rms, diff);
        if (diff < 0.001f) {
            printf("  FAIL: Mixed duo VOICE_FM produced no timbral change vs dry\n");
            all_pass = false;
        } else {
            printf("  PASS: Mixed duo VOICE_FM produces measurable timbral change\n");
        }
    }

    // ── Engine 0 duo regression: output must be stable across runs ──
    {
        auto run1 = render_fm_scenario({"voice_fm_hi", 0, 0.8f, 0.0f, 0.0f, 5.0f, 0.0f, -1, -1});
        auto run2 = render_fm_scenario({"voice_fm_hi", 0, 0.8f, 0.0f, 0.0f, 5.0f, 0.0f, -1, -1});
        int n = (int)(SR * DURATION);
        // Use RMS difference threshold rather than bitwise comparison,
        // since floating-point non-determinism (memory alignment, etc.) can
        // cause tiny differences unrelated to the code change.
        float rms1 = compute_rms(run1.data(), n * 2);
        float rms2 = compute_rms(run2.data(), n * 2);
        float diff = std::fabs(rms1 - rms2);
        printf("\nEngine 0 duo regression: rms1=%.6f rms2=%.6f diff=%.8f %s\n",
               rms1, rms2, diff, diff < 0.0001f ? "PASS" : "FAIL");
        if (diff >= 0.0001f) all_pass = false;
    }

    return all_pass;
}
