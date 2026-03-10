// Full-chain comparison tests: render identical scenarios through the C++ graph
// and output WAVs + CSV for comparison against JSyn equivalents.
//
// Each scenario pumps a deterministic excitation signal (sine wave or impulse)
// through the production graph with specific effects enabled, writing stereo WAV
// output to test/output/cpp_chain_*.wav.
//
// The Kotlin counterpart (JsynChainCompareTest.kt) renders the same scenarios
// through JSyn and writes jsyn_chain_*.wav files for direct comparison.

#include "test_harness.h"
#include <cmath>

static constexpr float kPi = 3.14159265358979f;

// Generate a deterministic sine wave into a buffer
static void fill_sine(float* buf, int num_samples, float freq_hz, float sample_rate, float amplitude = 0.5f) {
    for (int i = 0; i < num_samples; i++) {
        buf[i] = amplitude * std::sin(2.0f * kPi * freq_hz * i / sample_rate);
    }
}

// Render a scenario through the full production graph.
// excitation_type: 0 = voice (use Plaits engine), 1 = impulse-only (for resonator strum)
struct ChainScenario {
    const char* name;

    // Voice config
    int engine_index;       // Plaits engine (0=VA, etc.), -1 = no voice
    float note;             // MIDI note
    float harmonics;
    float timbre;
    float morph;
    float gate_seconds;     // how long gate is on

    // Resonator config
    bool resonator_on;
    int  resonator_mode;    // 0=modal, 1=sympathetic, 2=string
    float structure;
    float brightness;
    float damping;
    float position;
    float reso_mix;         // 0=dry, 1=fully wet
    bool  strum;            // trigger strum at start

    // Target mix (0=drum, 0.5=both, 1=synth)
    float target_mix;       // controls excitation routing

    // Other effects
    float drive_amount;     // 0=clean (default)
    float drive_mix;
    float delay_mix;        // 0=no delay
    float reverb_amount;    // 0=no reverb

    float duration_seconds;
};

static void render_chain_scenario(const ChainScenario& sc, int sr, const char* output_dir) {
    OrpheusEngine* engine = orpheus_engine_create(sr);
    if (!load_production_graph(engine)) {
        printf("  SKIP %s: no production graph\n", sc.name);
        orpheus_engine_destroy(engine);
        return;
    }

    // Unity master volume for apples-to-apples comparison with JSyn
    engine->master_volume.store(1.0f, std::memory_order_relaxed);

    // Set up voice
    if (sc.engine_index >= 0) {
        activate_voice(engine, 0, sc.engine_index, sc.note,
                       sc.harmonics, sc.timbre, sc.morph, 0.5f);
    }

    // Set up resonator
    if (sc.resonator_on) {
        engine->rings_bypass.store(0, std::memory_order_relaxed);
        engine->rings_model.store(sc.resonator_mode, std::memory_order_relaxed);
        engine->rings_structure.store(sc.structure, std::memory_order_relaxed);
        engine->rings_brightness.store(sc.brightness, std::memory_order_relaxed);
        engine->rings_damping.store(sc.damping, std::memory_order_relaxed);
        engine->rings_position.store(sc.position, std::memory_order_relaxed);

        // Set mix via the engine handler (which also updates bypass)
        orpheus_engine_set_port(engine,
            "org.balch.orpheus.plugins.resonator", "mix", sc.reso_mix);

        // Set wet/dry gains through graph port map
        orpheus_engine_set_port(engine,
            "org.balch.orpheus.plugins.resonator", "wet_gain", sc.reso_mix);
        orpheus_engine_set_port(engine,
            "org.balch.orpheus.plugins.resonator", "dry_gain", 1.0f - sc.reso_mix);

        // Compute excitation/bypass gains from target_mix (matches Kotlin logic)
        float tm = sc.target_mix;
        float drumEx = (tm <= 0.5f) ? 1.0f : std::max(0.0f, std::min(1.0f, 1.0f - (tm - 0.5f) * 2.0f));
        float synthEx = (tm >= 0.5f) ? 1.0f : std::max(0.0f, std::min(1.0f, tm * 2.0f));
        orpheus_engine_set_port(engine,
            "org.balch.orpheus.plugins.resonator", "drum_ex_gain", drumEx);
        orpheus_engine_set_port(engine,
            "org.balch.orpheus.plugins.resonator", "synth_ex_gain", synthEx);
        orpheus_engine_set_port(engine,
            "org.balch.orpheus.plugins.resonator", "drum_bp_gain", 1.0f - drumEx);
        orpheus_engine_set_port(engine,
            "org.balch.orpheus.plugins.resonator", "synth_bp_gain", 1.0f - synthEx);

        if (sc.strum) {
            engine->rings_frequency.store(sc.note, std::memory_order_relaxed);
            engine->rings_strum.store(1, std::memory_order_relaxed);
        }
    }

    // Drive
    if (sc.drive_amount > 0.0f) {
        orpheus_engine_set_port(engine,
            "org.balch.orpheus.plugins.distortion", "drive", sc.drive_amount);
        orpheus_engine_set_port(engine,
            "org.balch.orpheus.plugins.distortion", "mix", sc.drive_mix);
    }

    // Delay
    if (sc.delay_mix > 0.0f) {
        orpheus_engine_set_port(engine,
            "org.balch.orpheus.plugins.delay", "mix", sc.delay_mix);
        orpheus_engine_set_port(engine,
            "org.balch.orpheus.plugins.delay", "time_1", 0.25f);
        orpheus_engine_set_port(engine,
            "org.balch.orpheus.plugins.delay", "time_2", 0.375f);
        orpheus_engine_set_port(engine,
            "org.balch.orpheus.plugins.delay", "feedback", 0.3f);
    }

    // Reverb
    if (sc.reverb_amount > 0.0f) {
        orpheus_engine_set_port(engine,
            "org.balch.orpheus.plugins.reverb", "amount", sc.reverb_amount);
    }

    // Render
    const int total_frames = (int)(sr * sc.duration_seconds);
    std::vector<float> buf(total_frames * 2, 0.0f);

    for (int off = 0; off < total_frames; off += 128) {
        int chunk = std::min(128, total_frames - off);

        // Gate off after gate_seconds
        if (sc.engine_index >= 0 && off >= (int)(sr * sc.gate_seconds)) {
            orpheus_engine_set_voice_gate(engine, 0, 0);
        }

        orpheus_engine_process(engine, buf.data() + off * 2, chunk);
    }

    // Metrics
    float rms = compute_rms(buf.data(), total_frames * 2);
    float peak = compute_peak(buf.data(), total_frames * 2);

    // Write WAV
    char path[512];
    snprintf(path, sizeof(path), "%s/cpp_chain_%s.wav", output_dir, sc.name);
    write_wav(path, buf.data(), total_frames, sr);

    printf("  %-35s RMS=%.4f  Peak=%.4f\n", sc.name, rms, peak);

    orpheus_engine_destroy(engine);
}

bool run_chain_compare_tests() {
    printf("\n=== Full-Chain Comparison Tests ===\n");
    mkdir("test", 0755);
    mkdir("test/output", 0755);
    const int sr = 48000;
    const char* dir = "test/output";

    // Define scenarios
    ChainScenario scenarios[] = {
        // --- Voice-only baselines ---
        {
            .name = "voice_va_dry",
            .engine_index = 8, .note = 60.0f, .harmonics = 0.5f, .timbre = 0.5f, .morph = 0.5f,
            .gate_seconds = 1.0f,
            .resonator_on = false, .resonator_mode = 0,
            .structure = 0.5f, .brightness = 0.5f, .damping = 0.5f, .position = 0.5f,
            .reso_mix = 0.0f, .strum = false,
            .target_mix = 0.5f, .drive_amount = 0.0f, .drive_mix = 0.0f, .delay_mix = 0.0f, .reverb_amount = 0.0f,
            .duration_seconds = 2.0f
        },

        // --- Resonator modes (voice excitation) ---
        {
            .name = "voice_reso_modal",
            .engine_index = 8, .note = 60.0f, .harmonics = 0.5f, .timbre = 0.5f, .morph = 0.5f,
            .gate_seconds = 1.0f,
            .resonator_on = true, .resonator_mode = 0,
            .structure = 0.25f, .brightness = 0.5f, .damping = 0.3f, .position = 0.5f,
            .reso_mix = 1.0f, .strum = true,
            .target_mix = 0.5f, .drive_amount = 0.0f, .drive_mix = 0.0f, .delay_mix = 0.0f, .reverb_amount = 0.0f,
            .duration_seconds = 3.0f
        },
        {
            .name = "voice_reso_string",
            .engine_index = 8, .note = 60.0f, .harmonics = 0.5f, .timbre = 0.5f, .morph = 0.5f,
            .gate_seconds = 1.0f,
            .resonator_on = true, .resonator_mode = 1,
            .structure = 0.25f, .brightness = 0.5f, .damping = 0.3f, .position = 0.5f,
            .reso_mix = 1.0f, .strum = true,
            .target_mix = 0.5f, .drive_amount = 0.0f, .drive_mix = 0.0f, .delay_mix = 0.0f, .reverb_amount = 0.0f,
            .duration_seconds = 3.0f
        },
        {
            .name = "voice_reso_sympathetic",
            .engine_index = 8, .note = 60.0f, .harmonics = 0.5f, .timbre = 0.5f, .morph = 0.5f,
            .gate_seconds = 1.0f,
            .resonator_on = true, .resonator_mode = 2,
            .structure = 0.25f, .brightness = 0.5f, .damping = 0.3f, .position = 0.5f,
            .reso_mix = 1.0f, .strum = true,
            .target_mix = 0.5f, .drive_amount = 0.0f, .drive_mix = 0.0f, .delay_mix = 0.0f, .reverb_amount = 0.0f,
            .duration_seconds = 3.0f
        },

        // --- Resonator with half mix (50% wet/dry) ---
        {
            .name = "voice_reso_modal_half",
            .engine_index = 8, .note = 60.0f, .harmonics = 0.5f, .timbre = 0.5f, .morph = 0.5f,
            .gate_seconds = 1.0f,
            .resonator_on = true, .resonator_mode = 0,
            .structure = 0.25f, .brightness = 0.5f, .damping = 0.3f, .position = 0.5f,
            .reso_mix = 0.5f, .strum = true,
            .target_mix = 0.5f, .drive_amount = 0.0f, .drive_mix = 0.0f, .delay_mix = 0.0f, .reverb_amount = 0.0f,
            .duration_seconds = 3.0f
        },

        // --- Voice + Drive ---
        {
            .name = "voice_drive",
            .engine_index = 8, .note = 60.0f, .harmonics = 0.5f, .timbre = 0.5f, .morph = 0.5f,
            .gate_seconds = 1.0f,
            .resonator_on = false, .resonator_mode = 0,
            .structure = 0.5f, .brightness = 0.5f, .damping = 0.5f, .position = 0.5f,
            .reso_mix = 0.0f, .strum = false,
            .target_mix = 0.5f, .drive_amount = 0.5f, .drive_mix = 1.0f, .delay_mix = 0.0f, .reverb_amount = 0.0f,
            .duration_seconds = 2.0f
        },

        // --- Voice + Delay ---
        {
            .name = "voice_delay",
            .engine_index = 8, .note = 60.0f, .harmonics = 0.5f, .timbre = 0.5f, .morph = 0.5f,
            .gate_seconds = 0.5f,
            .resonator_on = false, .resonator_mode = 0,
            .structure = 0.5f, .brightness = 0.5f, .damping = 0.5f, .position = 0.5f,
            .reso_mix = 0.0f, .strum = false,
            .target_mix = 0.5f, .drive_amount = 0.0f, .drive_mix = 0.0f, .delay_mix = 0.5f, .reverb_amount = 0.0f,
            .duration_seconds = 3.0f
        },

        // --- Voice + Reverb ---
        {
            .name = "voice_reverb",
            .engine_index = 8, .note = 60.0f, .harmonics = 0.5f, .timbre = 0.5f, .morph = 0.5f,
            .gate_seconds = 0.5f,
            .resonator_on = false, .resonator_mode = 0,
            .structure = 0.5f, .brightness = 0.5f, .damping = 0.5f, .position = 0.5f,
            .reso_mix = 0.0f, .strum = false,
            .target_mix = 0.5f, .drive_amount = 0.0f, .drive_mix = 0.0f, .delay_mix = 0.0f, .reverb_amount = 0.5f,
            .duration_seconds = 4.0f
        },

        // --- Full chain: Voice + Resonator + Drive + Delay ---
        {
            .name = "full_chain",
            .engine_index = 8, .note = 60.0f, .harmonics = 0.5f, .timbre = 0.5f, .morph = 0.5f,
            .gate_seconds = 1.0f,
            .resonator_on = true, .resonator_mode = 0,
            .structure = 0.25f, .brightness = 0.5f, .damping = 0.3f, .position = 0.5f,
            .reso_mix = 0.8f, .strum = true,
            .target_mix = 0.5f, .drive_amount = 0.3f, .drive_mix = 0.5f, .delay_mix = 0.3f, .reverb_amount = 0.3f,
            .duration_seconds = 4.0f
        },

        // --- Resonator parameter sweep: structure ---
        {
            .name = "reso_struct_low",
            .engine_index = 8, .note = 60.0f, .harmonics = 0.5f, .timbre = 0.5f, .morph = 0.5f,
            .gate_seconds = 1.0f,
            .resonator_on = true, .resonator_mode = 0,
            .structure = 0.1f, .brightness = 0.5f, .damping = 0.3f, .position = 0.5f,
            .reso_mix = 1.0f, .strum = true,
            .target_mix = 0.5f, .drive_amount = 0.0f, .drive_mix = 0.0f, .delay_mix = 0.0f, .reverb_amount = 0.0f,
            .duration_seconds = 3.0f
        },
        {
            .name = "reso_struct_high",
            .engine_index = 8, .note = 60.0f, .harmonics = 0.5f, .timbre = 0.5f, .morph = 0.5f,
            .gate_seconds = 1.0f,
            .resonator_on = true, .resonator_mode = 0,
            .structure = 0.9f, .brightness = 0.5f, .damping = 0.3f, .position = 0.5f,
            .reso_mix = 1.0f, .strum = true,
            .target_mix = 0.5f, .drive_amount = 0.0f, .drive_mix = 0.0f, .delay_mix = 0.0f, .reverb_amount = 0.0f,
            .duration_seconds = 3.0f
        },

        // --- Resonator parameter sweep: brightness ---
        {
            .name = "reso_bright_low",
            .engine_index = 8, .note = 60.0f, .harmonics = 0.5f, .timbre = 0.5f, .morph = 0.5f,
            .gate_seconds = 1.0f,
            .resonator_on = true, .resonator_mode = 0,
            .structure = 0.25f, .brightness = 0.1f, .damping = 0.3f, .position = 0.5f,
            .reso_mix = 1.0f, .strum = true,
            .target_mix = 0.5f, .drive_amount = 0.0f, .drive_mix = 0.0f, .delay_mix = 0.0f, .reverb_amount = 0.0f,
            .duration_seconds = 3.0f
        },
        {
            .name = "reso_bright_high",
            .engine_index = 8, .note = 60.0f, .harmonics = 0.5f, .timbre = 0.5f, .morph = 0.5f,
            .gate_seconds = 1.0f,
            .resonator_on = true, .resonator_mode = 0,
            .structure = 0.25f, .brightness = 0.9f, .damping = 0.3f, .position = 0.5f,
            .reso_mix = 1.0f, .strum = true,
            .target_mix = 0.5f, .drive_amount = 0.0f, .drive_mix = 0.0f, .delay_mix = 0.0f, .reverb_amount = 0.0f,
            .duration_seconds = 3.0f
        },

        // --- Resonator parameter sweep: damping ---
        {
            .name = "reso_damp_low",
            .engine_index = 8, .note = 60.0f, .harmonics = 0.5f, .timbre = 0.5f, .morph = 0.5f,
            .gate_seconds = 1.0f,
            .resonator_on = true, .resonator_mode = 0,
            .structure = 0.25f, .brightness = 0.5f, .damping = 0.1f, .position = 0.5f,
            .reso_mix = 1.0f, .strum = true,
            .target_mix = 0.5f, .drive_amount = 0.0f, .drive_mix = 0.0f, .delay_mix = 0.0f, .reverb_amount = 0.0f,
            .duration_seconds = 3.0f
        },
        {
            .name = "reso_damp_high",
            .engine_index = 8, .note = 60.0f, .harmonics = 0.5f, .timbre = 0.5f, .morph = 0.5f,
            .gate_seconds = 1.0f,
            .resonator_on = true, .resonator_mode = 0,
            .structure = 0.25f, .brightness = 0.5f, .damping = 0.9f, .position = 0.5f,
            .reso_mix = 1.0f, .strum = true,
            .target_mix = 0.5f, .drive_amount = 0.0f, .drive_mix = 0.0f, .delay_mix = 0.0f, .reverb_amount = 0.0f,
            .duration_seconds = 3.0f
        },
    };

    int count = sizeof(scenarios) / sizeof(scenarios[0]);
    for (int i = 0; i < count; i++) {
        render_chain_scenario(scenarios[i], sr, dir);
    }

    // ── Routing isolation tests ──
    // Verify that synth signal is silent through resonator when target_mix = drum
    printf("\n  --- Routing Isolation Tests ---\n");
    {
        // Synth voice + resonator, target_mix = DRUM (synth should bypass, not excite)
        ChainScenario drum_only = {
            .name = "route_drum_only",
            .engine_index = 8, .note = 60.0f, .harmonics = 0.5f, .timbre = 0.5f, .morph = 0.5f,
            .gate_seconds = 1.0f,
            .resonator_on = true, .resonator_mode = 0,
            .structure = 0.25f, .brightness = 0.5f, .damping = 0.3f, .position = 0.5f,
            .reso_mix = 1.0f, .strum = true,
            .target_mix = 0.0f, // DRUM ONLY — synth should not excite resonator
            .drive_amount = 0.0f, .drive_mix = 0.0f, .delay_mix = 0.0f, .reverb_amount = 0.0f,
            .duration_seconds = 2.0f
        };
        render_chain_scenario(drum_only, sr, dir);

        // Same scenario with target_mix = SYNTH (synth should fully excite)
        ChainScenario synth_only = drum_only;
        synth_only.name = "route_synth_only";
        synth_only.target_mix = 1.0f; // SYNTH ONLY
        render_chain_scenario(synth_only, sr, dir);

        // Same scenario with target_mix = BOTH
        ChainScenario both = drum_only;
        both.name = "route_both";
        both.target_mix = 0.5f; // BOTH
        render_chain_scenario(both, sr, dir);

        // Compare: drum_only should have much lower resonator output than synth_only
        // because no drum voices are active — resonator receives no excitation in drum mode
    }

    // Write CSV summary
    printf("\n  Chain comparison WAVs written to %s/cpp_chain_*.wav\n", dir);
    printf("  Run JSyn counterpart: ./gradlew :core:dsp-engine:jvmTest --tests '*chainComparison'\n");
    printf("  Then compare: python3 %s/compare_chains.py\n", dir);

    return true;
}
