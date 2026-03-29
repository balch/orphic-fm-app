// Preset voice capture tests — loads a full preset via the production graph,
// gates specific voice pairs, renders audio, and uses snapshot comparison
// to detect regressions in preset behavior.
//
// Each test captures per-duo-pair output through the full signal chain
// (voices → effects → master_out) so we hear exactly what the user hears.
#include "test_harness.h"
#include "data/preset_six_seven.h"
#include "data/preset_pink.h"
#include "data/preset_clean.h"
#include "data/preset_orpheus.h"
#include "data/preset_funk491.h"
#include "data/preset_swirly_dreams.h"

static constexpr int SR = 48000;
static constexpr float DURATION_S = 2.0f;
static constexpr float GATE_S = 1.0f;

// Apply a preset's port values to the engine via set_port
static void apply_preset(OrpheusEngine* engine, const std::vector<PresetPort>& ports) {
    for (auto& p : ports) {
        orpheus_engine_set_port(engine, p.uri, p.symbol, p.value);
    }
}

// Render a preset with specific voice pair gated, through the production graph.
// duo_index: which duo pair (0-5), each pair = 2 voices (duo*2, duo*2+1)
// note_a/note_b: MIDI notes for the two voices in the pair
// Returns: true if snapshot matches or new baseline created
static bool test_preset_duo_pair(const char* preset_name,
                                  const std::vector<PresetPort>& ports,
                                  int duo_index,
                                  float note_a, float note_b,
                                  float tolerance_rms = 0.05f) {
    printf("\n--- Preset '%s' duo pair %d (voices %d, %d) ---\n",
           preset_name, duo_index, duo_index * 2, duo_index * 2 + 1);

    OrpheusEngine* engine = orpheus_engine_create(SR);
    if (!load_production_graph(engine)) {
        printf("  FAIL: could not load production graph\n");
        orpheus_engine_destroy(engine);
        return false;
    }

    // Apply all preset ports (sets engines, tune, mod sources, effects, etc.)
    apply_preset(engine, ports);

    // Gate the two voices in this duo pair
    int v_a = duo_index * 2;
    int v_b = duo_index * 2 + 1;
    for (int v : {v_a, v_b}) {
        engine->voice_params[v].active.store(1, std::memory_order_relaxed);
        engine->voice_params[v].ever_triggered.store(1, std::memory_order_relaxed);
        engine->voice_params[v].gate.store(1, std::memory_order_relaxed);
    }
    // Override tune to deterministic notes (preset tune is a normalized knob,
    // but we want consistent MIDI notes for reproducible snapshots)
    engine->voice_params[v_a].tune.store(note_a, std::memory_order_relaxed);
    engine->voice_params[v_b].tune.store(note_b, std::memory_order_relaxed);

    // Render
    const int total = static_cast<int>(SR * DURATION_S);
    const int gate_frames = static_cast<int>(SR * GATE_S);
    std::vector<float> buf(total * 2, 0.0f);

    for (int off = 0; off < total; off += 128) {
        int chunk = std::min(128, total - off);
        if (off >= gate_frames) {
            engine->voice_params[v_a].gate.store(0, std::memory_order_relaxed);
            engine->voice_params[v_b].gate.store(0, std::memory_order_relaxed);
        }
        orpheus_engine_process(engine, buf.data() + off * 2, chunk);
    }

    // Analyze per-channel
    float rms_l = 0.0f, rms_r = 0.0f, peak = 0.0f;
    for (int i = 0; i < total; i++) {
        float l = buf[i * 2], r = buf[i * 2 + 1];
        rms_l += l * l;
        rms_r += r * r;
        float al = std::fabs(l), ar = std::fabs(r);
        if (al > peak) peak = al;
        if (ar > peak) peak = ar;
    }
    rms_l = std::sqrt(rms_l / total);
    rms_r = std::sqrt(rms_r / total);

    // Report voice-level atomics for diagnostics
    float vl_a = engine->voice_levels[v_a].load(std::memory_order_relaxed);
    float vl_b = engine->voice_levels[v_b].load(std::memory_order_relaxed);
    int eng_a = engine->voice_params[v_a].engine_index.load(std::memory_order_relaxed);
    int eng_b = engine->voice_params[v_b].engine_index.load(std::memory_order_relaxed);
    int mod_src = engine->mod_source[duo_index].load(std::memory_order_relaxed);
    float mod_d = engine->mod_depth[duo_index].load(std::memory_order_relaxed);

    printf("  Engine: v%d=%d v%d=%d  ModSrc=%d ModDepth=%.3f\n",
           v_a, eng_a, v_b, eng_b, mod_src, mod_d);
    printf("  VoiceLevels: v%d=%.4f v%d=%.4f\n", v_a, vl_a, v_b, vl_b);
    printf("  Mix: RMS_L=%.4f RMS_R=%.4f Peak=%.4f\n", rms_l, rms_r, peak);

    // Fail if both voices are silent
    bool has_audio = (rms_l > 0.001f || rms_r > 0.001f);
    if (!has_audio) {
        printf("  FAIL: no audio output from duo pair %d\n", duo_index);
        orpheus_engine_destroy(engine);
        return false;
    }

    // Peak clipping check
    if (peak >= 0.999f) {
        printf("  WARN: clipping detected (peak=%.4f)\n", peak);
    }

    // Snapshot comparison
    char label[128];
    snprintf(label, sizeof(label), "preset_%s_duo%d", preset_name, duo_index);
    bool snap_pass = snapshot_check(label, buf.data(), total, SR, "test/output",
                                     tolerance_rms);

    // Write WAV for listening
    char wav_path[256];
    snprintf(wav_path, sizeof(wav_path), "test/output/preset_%s_duo%d.wav",
             preset_name, duo_index);
    write_wav(wav_path, buf.data(), total, SR);

    orpheus_engine_destroy(engine);
    return snap_pass;
}

// Full preset render: gate ALL voices with preset parameters,
// render through the production graph, snapshot the mix output.
static bool test_preset_full_mix(const char* preset_name,
                                  const std::vector<PresetPort>& ports,
                                  float bpm) {
    printf("\n--- Preset '%s' full mix (all voices) ---\n", preset_name);

    OrpheusEngine* engine = orpheus_engine_create(SR);
    if (!load_production_graph(engine)) {
        printf("  FAIL: could not load production graph\n");
        orpheus_engine_destroy(engine);
        return false;
    }

    apply_preset(engine, ports);

    // Gate all 12 main voices at C major scale spread across the duos
    float notes[] = {48, 52, 55, 60, 64, 67, 72, 76, 79, 84, 88, 91};
    for (int v = 0; v < 12; v++) {
        engine->voice_params[v].active.store(1, std::memory_order_relaxed);
        engine->voice_params[v].ever_triggered.store(1, std::memory_order_relaxed);
        engine->voice_params[v].gate.store(1, std::memory_order_relaxed);
        engine->voice_params[v].tune.store(notes[v], std::memory_order_relaxed);
    }

    const int total = static_cast<int>(SR * DURATION_S);
    const int gate_frames = static_cast<int>(SR * GATE_S);
    std::vector<float> buf(total * 2, 0.0f);

    for (int off = 0; off < total; off += 128) {
        int chunk = std::min(128, total - off);
        if (off >= gate_frames) {
            for (int v = 0; v < 12; v++)
                engine->voice_params[v].gate.store(0, std::memory_order_relaxed);
        }
        orpheus_engine_process(engine, buf.data() + off * 2, chunk);
    }

    float rms = compute_rms(buf.data(), total * 2);
    float peak = compute_peak(buf.data(), total * 2);
    printf("  RMS=%.4f Peak=%.4f\n", rms, peak);

    bool has_audio = rms > 0.001f;
    if (!has_audio) {
        printf("  FAIL: no audio output\n");
        orpheus_engine_destroy(engine);
        return false;
    }

    char label[128];
    snprintf(label, sizeof(label), "preset_%s_full", preset_name);
    bool snap_pass = snapshot_check(label, buf.data(), total, SR, "test/output");

    orpheus_engine_destroy(engine);
    return snap_pass;
}

// ═══════════════════════════════════════════════════════════════════
// Test suite entry point
// ═══════════════════════════════════════════════════════════════════

bool run_preset_voice_tests() {
    printf("\n══════════════════════════════════════\n");
    printf("  PRESET VOICE CAPTURE TESTS\n");
    printf("══════════════════════════════════════\n");

    mkdir("test", 0755);
    mkdir("test/output", 0755);
    bool all_pass = true;

    // ── 6-7 preset: the primary regression target ──
    // Duo pair 1 (voices 2, 3) uses engine 17 (noise) with VOICE_FM mod source
    // This is the "speech" pair the user reported as broken.
    all_pass &= test_preset_duo_pair("six_seven", preset_six_seven_ports,
                                      1, 60.0f, 67.0f);  // duo 1: C4, G4

    // Also test duo pair 0 (engine 0 with FLUX mod) and pair 2 (engine 0 with LFO mod)
    all_pass &= test_preset_duo_pair("six_seven", preset_six_seven_ports,
                                      0, 55.0f, 62.0f);  // duo 0: G3, D4
    all_pass &= test_preset_duo_pair("six_seven", preset_six_seven_ports,
                                      2, 48.0f, 52.0f);  // duo 2: C3, E3

    // Full mix with all voices
    all_pass &= test_preset_full_mix("six_seven", preset_six_seven_ports,
                                      preset_six_seven_bpm);

    // ── Other presets: baseline coverage ──
    // Pink and Funk491 use non-deterministic engines (speech, particle, etc.)
    // that drift between runs — use a wider tolerance for snapshot comparison.
    all_pass &= test_preset_duo_pair("pink", preset_pink_ports,
                                      0, 60.0f, 67.0f, 0.10f);
    all_pass &= test_preset_duo_pair("clean", preset_clean_ports,
                                      0, 60.0f, 64.0f);
    all_pass &= test_preset_duo_pair("orpheus", preset_orpheus_ports,
                                      0, 60.0f, 67.0f);
    all_pass &= test_preset_duo_pair("funk491", preset_funk491_ports,
                                      0, 60.0f, 64.0f, 0.10f);
    all_pass &= test_preset_duo_pair("swirly_dreams", preset_swirly_dreams_ports,
                                      0, 60.0f, 67.0f);

    printf("\nPreset voice tests: %s\n", all_pass ? "ALL PASS" : "SOME FAILED");
    return all_pass;
}
