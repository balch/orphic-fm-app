#include "orpheus_engine.h"
#include "orpheus_graph.h"
#include <cmath>
#include <cstring>

// Hash function matching Kotlin's hash16() and orpheus_graph.cpp's hash16()
static uint16_t engine_hash16(const char* str) {
    uint16_t h = 0;
    while (*str) {
        h = h * 31 + static_cast<uint16_t>(*str);
        str++;
    }
    return h;
}

extern "C" {

void orpheus_engine_set_port(OrpheusEngine* engine,
                             const char* plugin_uri,
                             const char* symbol,
                             float value) {
    // Route through graph if available (raw value — scaled overrides below)
    OrpheusGraph* g = engine->graph.load(std::memory_order_relaxed);
    if (g) {
        uint16_t uh = engine_hash16(plugin_uri);
        uint16_t sh = engine_hash16(symbol);
        orpheus_graph_set_port(g, uh, sh, value);
    }

    // Voice plugin parameters (coupling, mod source/depth, vibrato)
    {
        static uint16_t h_voice = engine_hash16("org.balch.orpheus.plugins.voice");
        static uint16_t h_coupling = engine_hash16("coupling_depth");
        static uint16_t h_vibrato = engine_hash16("vibrato");
        uint16_t uri_hash = engine_hash16(plugin_uri);
        uint16_t symbol_hash = engine_hash16(symbol);
        if (uri_hash == h_voice) {
            if (symbol_hash == h_coupling) {
                engine->coupling_depth.store(value, std::memory_order_relaxed);
                return;
            }
            static uint16_t h_total_fb = engine_hash16("total_feedback");
            if (symbol_hash == h_total_fb) {
                engine->total_feedback.store(value, std::memory_order_relaxed);
                return;
            }
            if (symbol_hash == h_vibrato) {
                engine->vibrato_depth.store(value, std::memory_order_relaxed);
                return;
            }
            // Per-duo mod source/depth: duo_mod_source_N, duo_mod_source_level_N
            static uint16_t src_hashes[] = {
                engine_hash16("duo_mod_source_0"), engine_hash16("duo_mod_source_1"),
                engine_hash16("duo_mod_source_2"), engine_hash16("duo_mod_source_3"),
                engine_hash16("duo_mod_source_4"), engine_hash16("duo_mod_source_5")};
            static uint16_t lvl_hashes[] = {
                engine_hash16("duo_mod_source_level_0"), engine_hash16("duo_mod_source_level_1"),
                engine_hash16("duo_mod_source_level_2"), engine_hash16("duo_mod_source_level_3"),
                engine_hash16("duo_mod_source_level_4"), engine_hash16("duo_mod_source_level_5")};
            for (int i = 0; i < 6; i++) {
                if (symbol_hash == src_hashes[i]) { engine->mod_source[i].store(static_cast<int>(value), std::memory_order_relaxed); return; }
                if (symbol_hash == lvl_hashes[i]) { engine->mod_depth[i].store(value, std::memory_order_relaxed); engine->fm_depth[i].store(value, std::memory_order_relaxed); return; }
            }

            // Per-duo voice parameters: engine, sharpness→timbre, harmonics, morph.
            // duo_engine value is OrpheusEngineId.id (C++ engine index directly — no mapping).
            static uint16_t eng_hashes[] = {
                engine_hash16("duo_engine_0"), engine_hash16("duo_engine_1"),
                engine_hash16("duo_engine_2"), engine_hash16("duo_engine_3"),
                engine_hash16("duo_engine_4"), engine_hash16("duo_engine_5")};
            static uint16_t sharp_hashes[] = {
                engine_hash16("duo_sharpness_0"), engine_hash16("duo_sharpness_1"),
                engine_hash16("duo_sharpness_2"), engine_hash16("duo_sharpness_3"),
                engine_hash16("duo_sharpness_4"), engine_hash16("duo_sharpness_5")};
            static uint16_t harm_hashes[] = {
                engine_hash16("duo_harmonics_0"), engine_hash16("duo_harmonics_1"),
                engine_hash16("duo_harmonics_2"), engine_hash16("duo_harmonics_3"),
                engine_hash16("duo_harmonics_4"), engine_hash16("duo_harmonics_5")};
            static uint16_t morph_hashes[] = {
                engine_hash16("duo_morph_0"), engine_hash16("duo_morph_1"),
                engine_hash16("duo_morph_2"), engine_hash16("duo_morph_3"),
                engine_hash16("duo_morph_4"), engine_hash16("duo_morph_5")};
            for (int i = 0; i < 6; i++) {
                if (symbol_hash == eng_hashes[i]) {
                    int cpp_idx = static_cast<int>(value);  // canonical OrpheusEngineId.id
                    int voiceA = i * 2;
                    engine->voice_params[voiceA].engine_index.store(cpp_idx, std::memory_order_relaxed);
                    engine->voice_params[voiceA + 1].engine_index.store(cpp_idx, std::memory_order_relaxed);
                    engine->voice_params[voiceA].active.store(1, std::memory_order_relaxed);
                    engine->voice_params[voiceA + 1].active.store(1, std::memory_order_relaxed);
                    return;
                }
                if (symbol_hash == sharp_hashes[i]) {
                    int voiceA = i * 2;
                    engine->voice_params[voiceA].timbre.store(value, std::memory_order_relaxed);
                    engine->voice_params[voiceA + 1].timbre.store(value, std::memory_order_relaxed);
                    return;
                }
                if (symbol_hash == harm_hashes[i]) {
                    int voiceA = i * 2;
                    engine->voice_params[voiceA].harmonics.store(value, std::memory_order_relaxed);
                    engine->voice_params[voiceA + 1].harmonics.store(value, std::memory_order_relaxed);
                    return;
                }
                if (symbol_hash == morph_hashes[i]) {
                    int voiceA = i * 2;
                    engine->voice_params[voiceA].morph.store(value, std::memory_order_relaxed);
                    engine->voice_params[voiceA + 1].morph.store(value, std::memory_order_relaxed);
                    return;
                }
            }

            // Per-voice parameters: tune_N, env_speed_N
            static uint16_t tune_hashes[12], envspd_hashes[12];
            static bool per_voice_init = false;
            if (!per_voice_init) {
                const char* tune_names[] = {
                    "tune_0","tune_1","tune_2","tune_3","tune_4","tune_5",
                    "tune_6","tune_7","tune_8","tune_9","tune_10","tune_11"};
                const char* envspd_names[] = {
                    "env_speed_0","env_speed_1","env_speed_2","env_speed_3",
                    "env_speed_4","env_speed_5","env_speed_6","env_speed_7",
                    "env_speed_8","env_speed_9","env_speed_10","env_speed_11"};
                for (int j = 0; j < 12; j++) {
                    tune_hashes[j] = engine_hash16(tune_names[j]);
                    envspd_hashes[j] = engine_hash16(envspd_names[j]);
                }
                per_voice_init = true;
            }
            // Per-voice pitch multiplier in semitones (matches DspSynthEngine.VOICE_PITCH_MULT_SEMITONES)
            static const float kVoicePitchSemi[12] = {
                -12, -12, 0, 0, 0, 0, 12, 12, 0, 0, 0, 0
            };
            for (int j = 0; j < 12; j++) {
                if (symbol_hash == tune_hashes[j]) {
                    // Convert normalized knob (0-1) to MIDI note
                    // Formula: 33 + tune*48 + voicePitchOffset
                    // (quadPitch defaults to 0.5 → offset 0)
                    float note = 33.0f + value * 48.0f + kVoicePitchSemi[j];
                    engine->voice_params[j].tune.store(note, std::memory_order_relaxed);
                    return;
                }
                if (symbol_hash == envspd_hashes[j]) {
                    engine->voice_params[j].decay.store(value, std::memory_order_relaxed);
                    return;
                }
            }
        }
    }

    // Mod source routing
    {
        static uint16_t h_mod = engine_hash16("org.balch.orpheus.plugins.modulation");
        uint16_t uri_hash = engine_hash16(plugin_uri);
        uint16_t symbol_hash = engine_hash16(symbol);
        if (uri_hash == h_mod) {
            static uint16_t h_fm_xquad = engine_hash16("fm_cross_quad");
            if (symbol_hash == h_fm_xquad) {
                engine->fm_cross_quad.store(static_cast<int>(value), std::memory_order_relaxed);
                return;
            }
            // Per-duo parameters: mod_source_N, mod_depth_N, fm_depth_N
            static uint16_t mod_src_hashes[] = {
                engine_hash16("mod_source_0"), engine_hash16("mod_source_1"),
                engine_hash16("mod_source_2"), engine_hash16("mod_source_3"),
                engine_hash16("mod_source_4"), engine_hash16("mod_source_5")};
            static uint16_t mod_depth_hashes[] = {
                engine_hash16("mod_depth_0"), engine_hash16("mod_depth_1"),
                engine_hash16("mod_depth_2"), engine_hash16("mod_depth_3"),
                engine_hash16("mod_depth_4"), engine_hash16("mod_depth_5")};
            static uint16_t fm_depth_hashes[] = {
                engine_hash16("fm_depth_0"), engine_hash16("fm_depth_1"),
                engine_hash16("fm_depth_2"), engine_hash16("fm_depth_3"),
                engine_hash16("fm_depth_4"), engine_hash16("fm_depth_5")};
            for (int i = 0; i < 6; i++) {
                if (symbol_hash == mod_src_hashes[i]) { engine->mod_source[i].store(static_cast<int>(value), std::memory_order_relaxed); return; }
                if (symbol_hash == mod_depth_hashes[i])  { engine->mod_depth[i].store(value, std::memory_order_relaxed); return; }
                if (symbol_hash == fm_depth_hashes[i])  { engine->fm_depth[i].store(value, std::memory_order_relaxed); return; }
            }
        }
    }

    // Resonator routing
    {
        static uint16_t h_reso = engine_hash16("org.balch.orpheus.plugins.resonator");
        static uint16_t h_target_mix = engine_hash16("target_mix");
        static uint16_t h_reso_mix = engine_hash16("reso_mix");
        uint16_t uri_hash = engine_hash16(plugin_uri);
        uint16_t symbol_hash = engine_hash16(symbol);
        if (uri_hash == h_reso) {
            if (symbol_hash == h_target_mix) { engine->resonator_target_mix.store(value, std::memory_order_relaxed); return; }
            if (symbol_hash == h_reso_mix) { engine->resonator_mix.store(value, std::memory_order_relaxed); return; }
        }
    }

    // Warps source routing
    {
        static uint16_t h_warps_uri = engine_hash16("org.balch.orpheus.plugins.warps");
        static uint16_t h_carrier_src = engine_hash16("carrier_source");
        static uint16_t h_mod_src_w = engine_hash16("modulator_source");
        uint16_t uri_hash = engine_hash16(plugin_uri);
        uint16_t symbol_hash = engine_hash16(symbol);
        if (uri_hash == h_warps_uri) {
            if (symbol_hash == h_carrier_src) { engine->warps_carrier_source.store(static_cast<int>(value), std::memory_order_relaxed); return; }
            if (symbol_hash == h_mod_src_w) { engine->warps_modulator_source.store(static_cast<int>(value), std::memory_order_relaxed); return; }
        }
    }

    // Bender parameters
    {
        static uint16_t h_bender = engine_hash16("org.balch.orpheus.plugins.bender");
        uint16_t uri_hash = engine_hash16(plugin_uri);
        uint16_t symbol_hash = engine_hash16(symbol);
        if (uri_hash == h_bender) {
            static uint16_t h_bend = engine_hash16("bend");
            static uint16_t h_max_semi = engine_hash16("max_bend");
            static uint16_t h_random = engine_hash16("random_depth");
            static uint16_t h_timbre = engine_hash16("timbre_mod");
            static uint16_t h_tension = engine_hash16("tension_vol");
            if (symbol_hash == h_bend) { engine->bend_amount.store(value, std::memory_order_relaxed); return; }
            if (symbol_hash == h_max_semi) { engine->bend_max_semitones.store(value, std::memory_order_relaxed); return; }
            if (symbol_hash == h_random) { engine->bend_random_depth.store(value, std::memory_order_relaxed); return; }
            if (symbol_hash == h_timbre) { engine->bend_timbre_mod.store(value, std::memory_order_relaxed); return; }
            if (symbol_hash == h_tension) { engine->bend_tension_vol.store(value, std::memory_order_relaxed); return; }
            // Per-string parameters
            static uint16_t s_bend_hashes[] = {
                engine_hash16("string_bend_0"), engine_hash16("string_bend_1"),
                engine_hash16("string_bend_2"), engine_hash16("string_bend_3")};
            static uint16_t s_mix_hashes[] = {
                engine_hash16("string_mix_0"), engine_hash16("string_mix_1"),
                engine_hash16("string_mix_2"), engine_hash16("string_mix_3")};
            static uint16_t s_active_hashes[] = {
                engine_hash16("string_active_0"), engine_hash16("string_active_1"),
                engine_hash16("string_active_2"), engine_hash16("string_active_3")};
            for (int i = 0; i < 4; i++) {
                if (symbol_hash == s_bend_hashes[i]) { engine->string_bend[i].store(value, std::memory_order_relaxed); return; }
                if (symbol_hash == s_mix_hashes[i]) { engine->string_mix[i].store(value, std::memory_order_relaxed); return; }
                if (symbol_hash == s_active_hashes[i]) { engine->string_active[i].store(static_cast<int>(value), std::memory_order_relaxed); return; }
            }
            static uint16_t h_slide_y = engine_hash16("slide_bar_y");
            static uint16_t h_slide_x = engine_hash16("slide_bar_x");
            if (symbol_hash == h_slide_y) { engine->slide_bar_y.store(value, std::memory_order_relaxed); return; }
            if (symbol_hash == h_slide_x) { engine->slide_bar_x.store(value, std::memory_order_relaxed); return; }
        }
    }

    // Also set engine atomics (for MI wrappers that read from atomics)
    // Keep ALL existing strcmp chains below
    if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.grains") == 0) {
        if (std::strcmp(symbol, "position") == 0)
            engine->clouds_position.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "size") == 0)
            engine->clouds_size.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "pitch") == 0)
            engine->clouds_pitch.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "density") == 0)
            engine->clouds_density.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "texture") == 0)
            engine->clouds_texture.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "dry_wet") == 0)
            engine->clouds_dry_wet.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "feedback") == 0)
            engine->clouds_feedback.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "reverb") == 0)
            engine->clouds_reverb.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "freeze") == 0)
            engine->clouds_freeze.store(value > 0.5f ? 1 : 0, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "trigger") == 0) {
            if (value > 0.5f)
                engine->clouds_trigger.store(1, std::memory_order_relaxed);
        }
        else if (std::strcmp(symbol, "mode") == 0)
            engine->clouds_mode.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "bypass") == 0)
            engine->clouds_bypass.store(value > 0.5f ? 1 : 0, std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.resonator") == 0) {
        if (std::strcmp(symbol, "structure") == 0)
            engine->rings_structure.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "brightness") == 0)
            engine->rings_brightness.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "damping") == 0)
            engine->rings_damping.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "position") == 0)
            engine->rings_position.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "frequency") == 0)
            engine->rings_frequency.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "mode") == 0)
            engine->rings_model.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "strum") == 0)
            engine->rings_strum.store(1, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "mix") == 0) {
            engine->resonator_mix.store(value, std::memory_order_relaxed);
            engine->rings_bypass.store(value < 0.001f ? 1 : 0, std::memory_order_relaxed);
        }
        else if (std::strcmp(symbol, "bypass") == 0)
            engine->rings_bypass.store(value > 0.5f ? 1 : 0, std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.warps") == 0) {
        if (std::strcmp(symbol, "algorithm") == 0)
            engine->warps_algorithm.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "timbre") == 0)
            engine->warps_timbre.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "level1") == 0)
            engine->warps_level1.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "level2") == 0)
            engine->warps_level2.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "mix") == 0) {
            engine->warps_mix.store(value, std::memory_order_relaxed);
            engine->warps_bypass.store(value <= 0.001f ? 1 : 0, std::memory_order_relaxed);
        }
        else if (std::strcmp(symbol, "carrier_source") == 0)
            engine->warps_carrier_source.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "modulator_source") == 0)
            engine->warps_modulator_source.store(static_cast<int>(value), std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.delay") == 0) {
        if (std::strcmp(symbol, "time_1") == 0)
            engine->delay_time_1.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "time_2") == 0)
            engine->delay_time_2.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "feedback") == 0)
            engine->delay_feedback.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "mix") == 0) {
            engine->delay_mix.store(value, std::memory_order_relaxed);
            engine->delay_bypass.store(value < 0.001f ? 1 : 0, std::memory_order_relaxed);
        }
        else if (std::strcmp(symbol, "mod_depth_1") == 0)
            engine->delay_mod_depth_1.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "mod_depth_2") == 0)
            engine->delay_mod_depth_2.store(value, std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.duolfo") == 0) {
        if (std::strcmp(symbol, "freq_a") == 0)
            engine->lfo_freq_a.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "freq_b") == 0)
            engine->lfo_freq_b.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "shape") == 0)
            engine->lfo_shape.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "mode") == 0)
            engine->lfo_mode.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "range_min") == 0)
            engine->lfo_range_min.store(value * 2.0f - 1.0f, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "range_max") == 0)
            engine->lfo_range_max.store(value * 2.0f - 1.0f, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "source") == 0)
            engine->lfo_source.store(static_cast<int>(value), std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.flux") == 0
          || std::strcmp(plugin_uri, "marbles") == 0) {
        if (std::strcmp(symbol, "rate") == 0)
            // Map [0,1] UI knob → [-48,+48] semitone rate offset (matches Kotlin FluxProcessor)
            engine->marbles_t_rate.store((value - 0.5f) * 96.0f, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "spread") == 0)
            engine->marbles_x_spread.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "x_bias") == 0)
            engine->marbles_x_bias.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "probability") == 0)
            engine->marbles_t_bias.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "steps") == 0)
            engine->marbles_x_steps.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "jitter") == 0)
            engine->marbles_t_jitter.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "deja_vu") == 0)
            engine->marbles_deja_vu.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "deja_vu_length") == 0)
            engine->marbles_deja_vu_length.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "deja_vu_mode") == 0)
            engine->marbles_deja_vu_mode.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "t_model") == 0)
            engine->marbles_t_model.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "t_range") == 0)
            engine->marbles_t_range.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "x_control_mode") == 0)
            engine->marbles_x_control_mode.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "x_range") == 0)
            engine->marbles_x_range.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "x_scale") == 0)
            engine->marbles_x_scale.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "mix") == 0)
            engine->marbles_mix.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "pulse_width") == 0)
            engine->marbles_pulse_width.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "range_min") == 0)
            engine->marbles_range_min.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "range_max") == 0)
            engine->marbles_range_max.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "pulse_width_std") == 0)
            engine->marbles_pulse_width_std.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "clock_source") == 0)
            engine->marbles_clock_source.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "seed") == 0)
            engine->marbles_seed.store(static_cast<int64_t>(value), std::memory_order_relaxed);
        // Trigger router: drum source selectors
        else if (std::strcmp(symbol, "drum_trigger_source_0") == 0)
            engine->drum_trigger_source[0].store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "drum_trigger_source_1") == 0)
            engine->drum_trigger_source[1].store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "drum_trigger_source_2") == 0)
            engine->drum_trigger_source[2].store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "drum_pitch_source_0") == 0)
            engine->drum_pitch_source[0].store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "drum_pitch_source_1") == 0)
            engine->drum_pitch_source[1].store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "drum_pitch_source_2") == 0)
            engine->drum_pitch_source[2].store(static_cast<int>(value), std::memory_order_relaxed);
        // Trigger router: quad source selectors
        else if (std::strcmp(symbol, "quad_trigger_source_0") == 0) {
            engine->quad_trigger_source[0].store(static_cast<int>(value), std::memory_order_relaxed);
        }
        else if (std::strcmp(symbol, "quad_trigger_source_1") == 0) {
            engine->quad_trigger_source[1].store(static_cast<int>(value), std::memory_order_relaxed);
        }
        else if (std::strcmp(symbol, "quad_trigger_source_2") == 0) {
            engine->quad_trigger_source[2].store(static_cast<int>(value), std::memory_order_relaxed);
        }
        else if (std::strcmp(symbol, "quad_pitch_source_0") == 0)
            engine->quad_pitch_source[0].store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "quad_pitch_source_1") == 0)
            engine->quad_pitch_source[1].store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "quad_pitch_source_2") == 0)
            engine->quad_pitch_source[2].store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "quad_trigger_mode_0") == 0)
            engine->quad_trigger_mode[0].store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "quad_trigger_mode_1") == 0)
            engine->quad_trigger_mode[1].store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "quad_trigger_mode_2") == 0)
            engine->quad_trigger_mode[2].store(static_cast<int>(value), std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.drum") == 0) {
        // Drum synthesis parameters: map 0-1 knob values to voice_params for drum voices 12-14
        // Frequency is converted from 0-1 to MIDI note range (matching Kotlin DrumPlugin.frequencyToNote)
        auto set_drum_param = [&](int drum_index, const char* sym) {
            auto& vp = engine->voice_params[kDrumVoiceStart + drum_index];
            if (std::strcmp(sym, "freq") == 0) {
                // BD: MIDI 28-52, SD: MIDI 48-72, HH: MIDI 60-84
                static const float kBaseNote[] = {28.0f, 48.0f, 60.0f};
                float note = kBaseNote[drum_index] + value * 24.0f;
                vp.tune.store(note, std::memory_order_relaxed);
            } else if (std::strcmp(sym, "tone") == 0) {
                vp.timbre.store(value, std::memory_order_relaxed);
            } else if (std::strcmp(sym, "decay") == 0) {
                vp.morph.store(value, std::memory_order_relaxed);
            } else if (std::strcmp(sym, "p4") == 0) {
                vp.harmonics.store(value, std::memory_order_relaxed);
            } else if (std::strcmp(sym, "p5") == 0) {
                vp.lpg_colour.store(value, std::memory_order_relaxed);
            } else if (std::strcmp(sym, "engine") == 0) {
                // Map Kotlin PlaitsEngineId ordinal to C++ Plaits engine index
                // Kotlin: 0=BD, 1=SD, 2=HH, 3=FMDrum, 4=FM, 5=Noise, 6=Waveshaping,
                //         7=VA, 8=Additive, 9=Grain, 10=String, 11=Modal, 12=Particle,
                //         13=Swarm, 14=Chord, 15=Wavetable, 16=Speech,
                //         17=VA_VCF, 18=PhaseDistortion, 19=SixOpFM, 20=WaveTerrain,
                //         21=StringMachine, 22=Chiptune,
                //         23=SixOpFM_2 (bank 2), 24=SixOpFM_3 (bank 3)
                // C++: see kOrpheusOutGain[] in orpheus_voice.h for index meanings
                static const int kKotlinToEngine[] = {
                    21, 22, 23, 10, // BD, SD, HH, FMDrum→FM(10) (no C++ FmDrum; Kotlin has custom impl)
                    10, 17,  9,  8, // FM, Noise, Waveshaping, VA
                    12, 11, 19, 20, // Additive, Grain, String, Modal
                    18, 16, 14, 13, // Particle, Swarm, Chord, Wavetable
                    15,             // Speech
                    // V1.2 engines (ordinals 17-22)
                     0,  1,  2,  5, // VA_VCF, PhaseDistortion, SixOpFM, WaveTerrain
                     6,  7,         // StringMachine, Chiptune
                    // SixOp additional banks (ordinals 23-24)
                     3,  4          // SixOpFM_2 (bank 2), SixOpFM_3 (bank 3)
                };
                int ordinal = static_cast<int>(value);
                if (ordinal >= 0 && ordinal < static_cast<int>(sizeof(kKotlinToEngine)/sizeof(kKotlinToEngine[0]))) {
                    vp.engine_index.store(kKotlinToEngine[ordinal], std::memory_order_relaxed);
                    vp.engine_changed.store(1, std::memory_order_relaxed);
                }
            }
        };
        if (std::strncmp(symbol, "bd_", 3) == 0)
            set_drum_param(0, symbol + 3);
        else if (std::strncmp(symbol, "sd_", 3) == 0)
            set_drum_param(1, symbol + 3);
        else if (std::strncmp(symbol, "hh_", 3) == 0)
            set_drum_param(2, symbol + 3);
        else if (std::strcmp(symbol, "mix") == 0) {
            engine->drum_mix.store(value, std::memory_order_relaxed);
        }
        else if (std::strcmp(symbol, "bypass") == 0)
            engine->rings_drum_bypass.store(value > 0.5f ? 1 : 0, std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.drums") == 0) {
        if (std::strcmp(symbol, "x") == 0)
            engine->grids_x.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "y") == 0)
            engine->grids_y.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "density_kick") == 0)
            engine->grids_density_kick.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "density_snare") == 0)
            engine->grids_density_snare.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "density_hat") == 0)
            engine->grids_density_hat.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "randomness") == 0)
            engine->grids_randomness.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "bypass") == 0)
            engine->grids_bypass.store(value > 0.5f ? 1 : 0, std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.bass") == 0) {
        if (std::strcmp(symbol, "engine") == 0)
            engine->bass_engine.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "root_note") == 0)
            engine->bass_root_note.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "scale") == 0)
            engine->bass_scale.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "clock_div") == 0)
            engine->bass_clock_div.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "step_count") == 0)
            engine->bass_step_count.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "mutation") == 0)
            engine->bass_mutation.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "cutoff") == 0)
            engine->bass_params.timbre.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "resonance") == 0)
            engine->bass_params.harmonics.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "envelope") == 0)
            engine->bass_envelope.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "overdrive") == 0)
            engine->bass_overdrive.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "compressor") == 0)
            engine->bass_compressor.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "mix") == 0) {
            engine->bass_mix.store(value, std::memory_order_relaxed);
        }
        else if (std::strcmp(symbol, "lfo_mix") == 0)
            engine->bass_lfo_mix.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "trigger_source") == 0)
            engine->bass_trigger_source.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "pitch_source") == 0)
            engine->bass_pitch_source.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "timbre_source") == 0)
            engine->bass_timbre_source.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "accent_amount") == 0)
            engine->bass_accent_amount.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "jitter") == 0)
            engine->bass_jitter.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "fx_send") == 0)
            engine->bass_fx_send.store(value, std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.dj") == 0) {
        if (std::strcmp(symbol, "wet_a") == 0) {
            engine->turntable_wet_a.store(value, std::memory_order_relaxed);
        } else if (std::strcmp(symbol, "wet_b") == 0) {
            engine->turntable_wet_b.store(value, std::memory_order_relaxed);
        } else if (std::strcmp(symbol, "velocity_a") == 0) {
            engine->turntable_velocity_a.store(value, std::memory_order_relaxed);
        } else if (std::strcmp(symbol, "velocity_b") == 0) {
            engine->turntable_velocity_b.store(value, std::memory_order_relaxed);
        } else if (std::strcmp(symbol, "frozen_a") == 0) {
            engine->turntable_frozen_a.store(static_cast<int>(value), std::memory_order_relaxed);
        } else if (std::strcmp(symbol, "frozen_b") == 0) {
            engine->turntable_frozen_b.store(static_cast<int>(value), std::memory_order_relaxed);
        } else if (std::strcmp(symbol, "source_a") == 0) {
            engine->turntable_source_a.store(static_cast<int>(value), std::memory_order_relaxed);
        } else if (std::strcmp(symbol, "source_b") == 0) {
            engine->turntable_source_b.store(static_cast<int>(value), std::memory_order_relaxed);
        } else if (std::strcmp(symbol, "crossfader") == 0) {
            engine->turntable_crossfader.store(value, std::memory_order_relaxed);
        } else if (std::strcmp(symbol, "delay_send") == 0) {
            engine->turntable_delay_send.store(value, std::memory_order_relaxed);
        } else if (std::strcmp(symbol, "reverb_send") == 0) {
            engine->turntable_reverb_send.store(value, std::memory_order_relaxed);
        } else if (std::strcmp(symbol, "drop_a") == 0) {
            engine->turntable_drop_a.store(static_cast<int>(value), std::memory_order_relaxed);
        } else if (std::strcmp(symbol, "drop_b") == 0) {
            engine->turntable_drop_b.store(static_cast<int>(value), std::memory_order_relaxed);
        }
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.app") == 0) {
        if (std::strcmp(symbol, "muted") == 0)
            engine->global_muted.store(static_cast<int>(value), std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.stereo") == 0) {
        if (std::strcmp(symbol, "master_pan") == 0)
            engine->master_pan.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "master_vol") == 0) {
            engine->master_volume.store(value, std::memory_order_relaxed);
            // Snap the fader so non-ramped sets take effect immediately
            // (cancels any in-flight master_fade).
            engine->master_fader_l.reset(value);
            engine->master_fader_r.reset(value);
        }
        else if (std::strncmp(symbol, "voice_pan_", 10) == 0) {
            int idx = std::atoi(symbol + 10);
            if (idx >= 0 && idx < kNumVoices) {
                engine->voice_pan[idx].store(value, std::memory_order_relaxed);
                // Compute constant-power gains and update graph pan multiply units
                if (g) {
                    float angle = ((value + 1.0f) * 0.5f) * (3.14159265f * 0.5f);
                    float gl = std::cos(angle);
                    float gr = std::sin(angle);
                    static uint16_t uh = engine_hash16("org.balch.orpheus.plugins.stereo");
                    char sym_l[32], sym_r[32];
                    snprintf(sym_l, sizeof(sym_l), "voice_pan_L_%d", idx);
                    snprintf(sym_r, sizeof(sym_r), "voice_pan_R_%d", idx);
                    orpheus_graph_set_port(g, uh, engine_hash16(sym_l), gl);
                    orpheus_graph_set_port(g, uh, engine_hash16(sym_r), gr);
                }
            }
        }
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.reverb") == 0) {
        if (std::strcmp(symbol, "amount") == 0) {
            engine->reverb_amount.store(value, std::memory_order_relaxed);
            engine->reverb_bypass.store(value <= 0.001f ? 1 : 0, std::memory_order_relaxed);
        }
        else if (std::strcmp(symbol, "time") == 0)
            engine->reverb_time.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "damping") == 0)
            engine->reverb_damping.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "diffusion") == 0)
            engine->reverb_diffusion.store(value, std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.looper") == 0) {
        if (std::strcmp(symbol, "state") == 0)
            engine->looper_requested_state.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "level") == 0)
            engine->looper_level.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "feedback") == 0)
            engine->looper_feedback.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "quantize") == 0)
            engine->looper_quantize.store(value > 0.5f ? 1 : 0, std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.tts") == 0) {
        if (std::strcmp(symbol, "rate") == 0)
            engine->tts_rate.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "volume") == 0)
            engine->tts_volume.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "phaser") == 0)
            engine->tts_phaser.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "feedback") == 0)
            engine->tts_feedback.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "reverb") == 0)
            engine->tts_reverb.store(value, std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.lorenz") == 0) {
        if (std::strcmp(symbol, "rate") == 0)
            engine->lorenz_rate.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "balance") == 0)
            engine->lorenz_balance.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "bypass") == 0)
            engine->lorenz_bypass.store(value > 0.5f ? 1 : 0, std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.polylfo") == 0) {
        if (std::strcmp(symbol, "shape") == 0)
            engine->poly_lfo_shape.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "shape_spread") == 0)
            engine->poly_lfo_shape_spread.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "spread") == 0)
            engine->poly_lfo_spread.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "coupling") == 0)
            engine->poly_lfo_coupling.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "rate") == 0)
            engine->poly_lfo_rate.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "bypass") == 0)
            engine->poly_lfo_bypass.store(value > 0.5f ? 1 : 0, std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.tempo") == 0) {
        if (std::strcmp(symbol, "bpm") == 0)
            engine->clock_bpm.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "run") == 0)
            engine->clock_running.store(value > 0.5f ? 1 : 0, std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.distortion") == 0) {
        if (std::strcmp(symbol, "drive") == 0) {
            float scaled = 1.0f + value * 14.0f;
            engine->drive_amount.store(scaled, std::memory_order_relaxed);
            // Override graph port with scaled drive (raw value was set above)
            if (g) {
                orpheus_graph_set_port(g,
                    engine_hash16(plugin_uri), engine_hash16(symbol), scaled);
            }
        }
        else if (std::strcmp(symbol, "mix") == 0)
            engine->drive_mix.store(value, std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.horn") == 0) {
        if (std::strcmp(symbol, "speed") == 0)
            engine->horn_speed.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "ratio") == 0)
            engine->horn_ratio.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "depth") == 0)
            engine->horn_depth.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "mix") == 0) {
            engine->horn_mix.store(value, std::memory_order_relaxed);
            engine->horn_bypass.store(value <= 0.001f ? 1 : 0, std::memory_order_relaxed);
        }
        else if (std::strcmp(symbol, "brake") == 0)
            engine->horn_brake.store(value > 0.5f ? 1 : 0, std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.pulsar") == 0) {
        if (std::strcmp(symbol, "playing") == 0)
            engine->pulsar_playing.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "vibe_generation") == 0)
            engine->pulsar_vibe_generation.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "energy") == 0)
            engine->pulsar_energy.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "complexity") == 0)
            engine->pulsar_complexity.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "space") == 0)
            engine->pulsar_space.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "mood") == 0)
            engine->pulsar_mood.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "bpm") == 0)
            engine->pulsar_bpm_override.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "root_note") == 0)
            engine->pulsar_root_note.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "scale") == 0)
            engine->pulsar_scale_index.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "mix") == 0)
            engine->pulsar_mix.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "perc_mix") == 0)
            engine->pulsar_perc_mix.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "bass_gain") == 0)
            engine->pulsar_bass_gain.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "keys_gain") == 0)
            engine->pulsar_keys_gain.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "fx_gain") == 0)
            engine->pulsar_fx_gain.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "envelope_mode") == 0)
            engine->pulsar_envelope_mode.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "seed") == 0)
            engine->pulsar_seed.store(static_cast<int64_t>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "lick_mutation") == 0)
            engine->pulsar_lick_mutation.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "lick_octave") == 0)
            engine->pulsar_lick_octave.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "step_count") == 0)
            engine->pulsar_step_count.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "lick_loop_length") == 0)
            engine->pulsar_lick_loop_length.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "lick_length") == 0)
            engine->pulsar_lick_length.store(static_cast<int>(value), std::memory_order_release);
        else if (std::strncmp(symbol, "track_", 6) == 0 && symbol[6] >= '0' && symbol[6] <= '7') {
            int t = symbol[6] - '0';
            const char* param = symbol + 8; // skip "track_N_"
            if (std::strcmp(param, "engine_edm") == 0) {
                int v = static_cast<int>(value);
                engine->pulsar_track_engine_edm[t].store(v, std::memory_order_relaxed);
                // Seed the active-engine mirror so the very first audio block
                // after a vibe load doesn't read a stale 0 (which would route
                // pulsar_delay's per-track feedback/send through the wrong slot
                // for one block). unit_process_pulsar republishes this every
                // block thereafter.
                engine->pulsar_track_active_engine[t].store(v, std::memory_order_relaxed);
            }
            else if (std::strcmp(param, "engine_space") == 0)
                engine->pulsar_track_engine_space[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "volume") == 0)
                engine->pulsar_track_volume[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "volume_space") == 0)
                engine->pulsar_track_volume_space[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "pan") == 0)
                engine->pulsar_track_pan[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "harmonics") == 0)
                engine->pulsar_track_harmonics[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "harmonics_space") == 0)
                engine->pulsar_track_harmonics_space[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "timbre") == 0)
                engine->pulsar_track_timbre[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "timbre_space") == 0)
                engine->pulsar_track_timbre_space[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "morph") == 0)
                engine->pulsar_track_morph[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "morph_space") == 0)
                engine->pulsar_track_morph_space[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "pin_harmonics") == 0)
                engine->pulsar_track_pin_harmonics[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "pin_harmonics_space") == 0)
                engine->pulsar_track_pin_harmonics_space[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "pin_timbre") == 0)
                engine->pulsar_track_pin_timbre[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "pin_timbre_space") == 0)
                engine->pulsar_track_pin_timbre_space[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "pin_morph") == 0)
                engine->pulsar_track_pin_morph[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "pin_morph_space") == 0)
                engine->pulsar_track_pin_morph_space[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "harmonics_modulation") == 0)
                engine->pulsar_track_harmonics_modulation[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "harmonics_modulation_space") == 0)
                engine->pulsar_track_harmonics_modulation_space[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "harmonics_macro_source") == 0)
                engine->pulsar_track_harmonics_macro_source[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "harmonics_macro_source_space") == 0)
                engine->pulsar_track_harmonics_macro_source_space[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "harmonics_macro_range") == 0)
                engine->pulsar_track_harmonics_macro_range[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "harmonics_macro_range_space") == 0)
                engine->pulsar_track_harmonics_macro_range_space[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "envelope") == 0)
                engine->pulsar_track_envelope[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "role") == 0)
                engine->pulsar_track_role[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "bar_strategy") == 0)
                engine->pulsar_track_bar_strategy[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "evo_rhythmic") == 0)
                engine->pulsar_track_evo_rhythmic[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "evo_weight") == 0)
                engine->pulsar_track_evo_weight[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "mute") == 0)
                engine->pulsar_track_mute[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "mod_lfo_rate") == 0)
                engine->pulsar_track_mod_lfo_rate[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "mod_lfo_rate_space") == 0)
                engine->pulsar_track_mod_lfo_rate_space[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "mod_lfo_depth") == 0)
                engine->pulsar_track_mod_lfo_depth[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "mod_lfo_depth_space") == 0)
                engine->pulsar_track_mod_lfo_depth_space[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "mod_lfo_shape") == 0)
                engine->pulsar_track_mod_lfo_shape[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "mod_lfo_shape_space") == 0)
                engine->pulsar_track_mod_lfo_shape_space[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "mod_lfo_coupling") == 0)
                engine->pulsar_track_mod_lfo_coupling[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "mod_lfo_coupling_space") == 0)
                engine->pulsar_track_mod_lfo_coupling_space[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "hold_probability") == 0)
                engine->pulsar_track_hold_probability[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "hold_probability_space") == 0)
                engine->pulsar_track_hold_probability_space[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "hold_length_min") == 0)
                engine->pulsar_track_hold_length_min[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "hold_length_min_space") == 0)
                engine->pulsar_track_hold_length_min_space[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "hold_length_max") == 0)
                engine->pulsar_track_hold_length_max[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "hold_length_max_space") == 0)
                engine->pulsar_track_hold_length_max_space[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "delay_send") == 0)
                engine->pulsar_track_delay_send[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "delay_send_space") == 0)
                engine->pulsar_track_delay_send_space[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "reverb_send") == 0)
                engine->pulsar_track_reverb_send[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "reverb_send_space") == 0)
                engine->pulsar_track_reverb_send_space[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "note_range_low") == 0)
                engine->pulsar_track_note_range_low[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "note_range_low_space") == 0)
                engine->pulsar_track_note_range_low_space[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "note_range_high") == 0)
                engine->pulsar_track_note_range_high[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "note_range_high_space") == 0)
                engine->pulsar_track_note_range_high_space[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "reverb_brightness") == 0)
                engine->pulsar_track_reverb_brightness[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "reverb_brightness_space") == 0)
                engine->pulsar_track_reverb_brightness_space[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "density_override") == 0)
                engine->pulsar_track_density_override[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "delay_feedback") == 0)
                engine->pulsar_track_delay_feedback[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "delay_feedback_space") == 0)
                engine->pulsar_track_delay_feedback_space[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "glide_rate") == 0)
                engine->pulsar_track_glide_rate[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "glide_rate_space") == 0)
                engine->pulsar_track_glide_rate_space[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "lick_mode") == 0)
                engine->pulsar_track_lick_mode[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "comping_style") == 0)
                engine->pulsar_track_comping_style[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "arp_mode") == 0)
                engine->pulsar_track_arp_mode[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "arp_speed") == 0)
                engine->pulsar_track_arp_speed[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "arp_direction") == 0)
                engine->pulsar_track_arp_direction[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "inversion") == 0)
                engine->pulsar_track_inversion[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "human_drop_prob") == 0)
                engine->pulsar_track_human_drop_prob[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "human_ghost_prob") == 0)
                engine->pulsar_track_human_ghost_prob[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "human_octave_prob") == 0)
                engine->pulsar_track_human_octave_prob[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "human_ext_prob") == 0)
                engine->pulsar_track_human_ext_prob[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "fill_every_n") == 0)
                engine->pulsar_track_fill_every_n[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "fill_type") == 0)
                engine->pulsar_track_fill_type[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "fill_skip_prob") == 0)
                engine->pulsar_track_fill_skip_prob[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "evo_tension_resp") == 0)
                engine->pulsar_track_evo_tension_resp[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "chord_follow") == 0)
                engine->pulsar_track_chord_follow[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "lpg_mode") == 0)
                engine->pulsar_track_lpg_mode[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "lpg_mode_space") == 0)
                engine->pulsar_track_lpg_mode_space[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "lpg_decay") == 0)
                engine->pulsar_track_lpg_decay[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "lpg_decay_space") == 0)
                engine->pulsar_track_lpg_decay_space[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "lpg_colour") == 0)
                engine->pulsar_track_lpg_colour[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "lpg_colour_space") == 0)
                engine->pulsar_track_lpg_colour_space[t].store(value, std::memory_order_relaxed);
            else if (std::strcmp(param, "evo_note_follow") == 0)
                engine->pulsar_track_evo_note_follow[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "evo_pitch_mode") == 0)
                engine->pulsar_track_evo_pitch_mode[t].store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(param, "evo_voicing_tension") == 0)
                engine->pulsar_track_evo_voicing_tension[t].store(value, std::memory_order_relaxed);
            else if (std::strncmp(param, "macro_", 6) == 0) {
                const char* macro = param + 6;
                auto& m = engine->pulsar_track_macros[t];
                if (std::strcmp(macro, "energy_vol_min") == 0) m.energy_vol_min.store(value, std::memory_order_relaxed);
                else if (std::strcmp(macro, "energy_vol_max") == 0) m.energy_vol_max.store(value, std::memory_order_relaxed);
                else if (std::strcmp(macro, "energy_density_min") == 0) m.energy_density_min.store(value, std::memory_order_relaxed);
                else if (std::strcmp(macro, "energy_density_max") == 0) m.energy_density_max.store(value, std::memory_order_relaxed);
                else if (std::strcmp(macro, "complexity_swing_min") == 0) m.complexity_swing_min.store(value, std::memory_order_relaxed);
                else if (std::strcmp(macro, "complexity_swing_max") == 0) m.complexity_swing_max.store(value, std::memory_order_relaxed);
                else if (std::strcmp(macro, "complexity_var_min") == 0) m.complexity_var_min.store(value, std::memory_order_relaxed);
                else if (std::strcmp(macro, "complexity_var_max") == 0) m.complexity_var_max.store(value, std::memory_order_relaxed);
                else if (std::strcmp(macro, "space_decay_min") == 0) m.space_decay_min.store(value, std::memory_order_relaxed);
                else if (std::strcmp(macro, "space_decay_max") == 0) m.space_decay_max.store(value, std::memory_order_relaxed);
                else if (std::strcmp(macro, "mood_harm_min") == 0) m.mood_harm_min.store(value, std::memory_order_relaxed);
                else if (std::strcmp(macro, "mood_harm_max") == 0) m.mood_harm_max.store(value, std::memory_order_relaxed);
                else if (std::strcmp(macro, "mood_timbre_min") == 0) m.mood_timbre_min.store(value, std::memory_order_relaxed);
                else if (std::strcmp(macro, "mood_timbre_max") == 0) m.mood_timbre_max.store(value, std::memory_order_relaxed);
            }
        }
        else if (std::strncmp(symbol, "genre_density_", 14) == 0 && symbol[14] >= '0' && symbol[14] <= '7')
            engine->pulsar_genre_density[symbol[14] - '0'].store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "genre_swing") == 0)
            engine->pulsar_genre_swing.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "genre_ghost_prob") == 0)
            engine->pulsar_genre_ghost_prob.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "genre_note_range_low") == 0)
            engine->pulsar_genre_note_range_low.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "genre_note_range_high") == 0)
            engine->pulsar_genre_note_range_high.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "genre_rhythm_density") == 0)
            engine->pulsar_genre_rhythm_density.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "genre_progression_style") == 0)
            engine->pulsar_genre_progression_style.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "genre_chords_per_bar") == 0)
            engine->pulsar_genre_chords_per_bar.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "chord_matrix_active") == 0)
            engine->pulsar_chord_matrix_active.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strncmp(symbol, "chord_matrix_", 13) == 0) {
            int idx = std::atoi(symbol + 13);
            if (idx >= 0 && idx < 49)
                engine->pulsar_chord_matrix[idx].store(value, std::memory_order_relaxed);
        }
        else if (std::strcmp(symbol, "custom_progression_active") == 0)
            engine->pulsar_custom_progression_active.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "custom_progression_length") == 0)
            engine->pulsar_custom_progression_length.store(static_cast<int>(value), std::memory_order_relaxed);
        // Match the more-specific "custom_progression_glide_" prefix BEFORE the
        // broader "custom_progression_" so atoi("glide_0") does not collide.
        else if (std::strncmp(symbol, "custom_progression_glide_", 25) == 0) {
            int idx = std::atoi(symbol + 25);
            if (idx >= 0 && idx < 8)
                engine->pulsar_custom_progression_glide[idx].store(value, std::memory_order_relaxed);
        }
        else if (std::strncmp(symbol, "custom_progression_", 19) == 0) {
            int idx = std::atoi(symbol + 19);
            if (idx >= 0 && idx < 8)
                engine->pulsar_custom_progression[idx].store(static_cast<int>(value), std::memory_order_relaxed);
        }
        else if (std::strcmp(symbol, "progression_anchor") == 0)
            engine->pulsar_progression_anchor.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "progression_drift_range") == 0)
            engine->pulsar_progression_drift_range.store(value, std::memory_order_relaxed);
        else if (std::strncmp(symbol, "tension_", 8) == 0) {
            const char* t = symbol + 8;
            if (std::strcmp(t, "inner_bars") == 0)
                engine->pulsar_tension_inner_bars.store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(t, "outer_bars") == 0)
                engine->pulsar_tension_outer_bars.store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(t, "outer_depth") == 0)
                engine->pulsar_tension_outer_depth.store(value, std::memory_order_relaxed);
            else if (std::strcmp(t, "volume") == 0)
                engine->pulsar_tension_volume.store(value, std::memory_order_relaxed);
            else if (std::strcmp(t, "timing") == 0)
                engine->pulsar_tension_timing.store(value, std::memory_order_relaxed);
            else if (std::strcmp(t, "octave_shift") == 0)
                engine->pulsar_tension_octave_shift.store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(t, "key_shift") == 0)
                engine->pulsar_tension_key_shift.store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(t, "half_lick") == 0)
                engine->pulsar_tension_half_lick.store(static_cast<int>(value), std::memory_order_relaxed);
            else if (std::strcmp(t, "chromatic_passing") == 0)
                engine->pulsar_tension_chromatic_passing.store(value, std::memory_order_relaxed);
            else if (std::strcmp(t, "evo_timbre_low") == 0)
                engine->pulsar_tension_evo_timbre_low.store(value, std::memory_order_relaxed);
            else if (std::strcmp(t, "evo_timbre_high") == 0)
                engine->pulsar_tension_evo_timbre_high.store(value, std::memory_order_relaxed);
            else if (std::strcmp(t, "evo_timbre_prob") == 0)
                engine->pulsar_tension_evo_timbre_prob.store(value, std::memory_order_relaxed);
            else if (std::strcmp(t, "evo_morph_low") == 0)
                engine->pulsar_tension_evo_morph_low.store(value, std::memory_order_relaxed);
            else if (std::strcmp(t, "evo_morph_high") == 0)
                engine->pulsar_tension_evo_morph_high.store(value, std::memory_order_relaxed);
            else if (std::strcmp(t, "evo_morph_prob") == 0)
                engine->pulsar_tension_evo_morph_prob.store(value, std::memory_order_relaxed);
            else if (std::strcmp(t, "evo_harm_low") == 0)
                engine->pulsar_tension_evo_harm_low.store(value, std::memory_order_relaxed);
            else if (std::strcmp(t, "evo_harm_high") == 0)
                engine->pulsar_tension_evo_harm_high.store(value, std::memory_order_relaxed);
            else if (std::strcmp(t, "evo_harm_prob") == 0)
                engine->pulsar_tension_evo_harm_prob.store(value, std::memory_order_relaxed);
            else if (std::strcmp(t, "evo_attack_point") == 0)
                engine->pulsar_tension_evo_attack_point.store(value, std::memory_order_relaxed);
            else if (std::strcmp(t, "evo_release_speed") == 0)
                engine->pulsar_tension_evo_release_speed.store(value, std::memory_order_relaxed);
            else if (std::strcmp(t, "spurt_chance") == 0)
                engine->pulsar_tension_spurt_chance.store(value, std::memory_order_relaxed);
        }
        else if (std::strncmp(symbol, "lick_data_", 10) == 0) {
            int idx = std::atoi(symbol + 10);
            int step = idx / 4;
            int field = idx % 4;
            if (step >= 0 && step < OrpheusEngine::kMaxLickSteps) {
                switch (field) {
                    case 0: engine->pulsar_lick[step].scale_degree = static_cast<int8_t>(value); break;
                    case 1: engine->pulsar_lick[step].duration = value; break;
                    case 2: engine->pulsar_lick[step].velocity = value; break;
                    case 3: engine->pulsar_lick[step].glide_rate = value; break;  // -1 = use track default
                }
            }
        }
        // ── Arrangement / Sections ──
        else if (std::strcmp(symbol, "arrangement_active") == 0)
            engine->pulsar_arrangement_active.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "arrangement_section_count") == 0)
            engine->pulsar_arrangement_section_count.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "arrangement_intro_index") == 0)
            engine->pulsar_arrangement_intro_index.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "arrangement_outro_index") == 0)
            engine->pulsar_arrangement_outro_index.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "arrangement_outro_request") == 0)
            engine->pulsar_arrangement_outro_request.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strncmp(symbol, "section_data_", 13) == 0) {
            int idx = std::atoi(symbol + 13);
            if (idx >= 0 && idx < 8 * 21)
                engine->pulsar_section_data[idx].store(value, std::memory_order_relaxed);
        }
        else if (std::strncmp(symbol, "section_track_comping_", 22) == 0) {
            int idx = std::atoi(symbol + 22);
            if (idx >= 0 && idx < 8 * 8)
                engine->pulsar_section_track_comping_style[idx].store(static_cast<int>(value), std::memory_order_relaxed);
        }
        else if (std::strncmp(symbol, "section_track_inversion_", 24) == 0) {
            int idx = std::atoi(symbol + 24);
            if (idx >= 0 && idx < 8 * 8)
                engine->pulsar_section_track_inversion[idx].store(static_cast<int>(value), std::memory_order_relaxed);
        }
        else if (std::strncmp(symbol, "section_track_arp_mode_", 23) == 0) {
            int idx = std::atoi(symbol + 23);
            if (idx >= 0 && idx < 8 * 8)
                engine->pulsar_section_track_arp_mode[idx].store(static_cast<int>(value), std::memory_order_relaxed);
        }
        else if (std::strncmp(symbol, "section_track_chord_follow_", 27) == 0) {
            int idx = std::atoi(symbol + 27);
            if (idx >= 0 && idx < 8 * 8)
                engine->pulsar_section_track_chord_follow[idx].store(static_cast<int>(value), std::memory_order_relaxed);
        }
        else if (std::strncmp(symbol, "section_transitions_", 20) == 0) {
            int idx = std::atoi(symbol + 20);
            if (idx >= 0 && idx < 8 * 8 * 3)
                engine->pulsar_section_transitions[idx].store(value, std::memory_order_relaxed);
        }
        else if (std::strncmp(symbol, "section_progression_active_", 27) == 0) {
            int idx = std::atoi(symbol + 27);
            if (idx >= 0 && idx < 8)
                engine->pulsar_section_progression_length[idx].store(
                    static_cast<int>(value), std::memory_order_relaxed);
        }
        else if (std::strncmp(symbol, "section_progression_degree_", 27) == 0) {
            int idx = std::atoi(symbol + 27);
            if (idx >= 0 && idx < 8 * 8)
                engine->pulsar_section_progression_degrees[idx].store(
                    static_cast<int>(value), std::memory_order_relaxed);
        }
        else if (std::strncmp(symbol, "section_progression_glide_", 26) == 0) {
            int idx = std::atoi(symbol + 26);
            if (idx >= 0 && idx < 8 * 8)
                engine->pulsar_section_progression_glides[idx].store(
                    value, std::memory_order_relaxed);
        }
        else if (std::strncmp(symbol, "section_chords_per_bar_", 23) == 0) {
            int idx = std::atoi(symbol + 23);
            if (idx >= 0 && idx < 8)
                engine->pulsar_section_chords_per_bar[idx].store(
                    static_cast<int>(value), std::memory_order_relaxed);
        }
        else if (std::strncmp(symbol, "section_tension_active_", 23) == 0) {
            int idx = std::atoi(symbol + 23);
            if (idx >= 0 && idx < 8)
                engine->pulsar_section_tension_active[idx].store(
                    static_cast<int>(value), std::memory_order_relaxed);
        }
        else if (std::strncmp(symbol, "section_tension_data_", 21) == 0) {
            int idx = std::atoi(symbol + 21);
            if (idx >= 0 && idx < 8 * 21)
                engine->pulsar_section_tension_data[idx].store(
                    value, std::memory_order_relaxed);
        }
        else if (std::strncmp(symbol, "section_comping_humanization_active_", 36) == 0) {
            int idx = std::atoi(symbol + 36);
            if (idx >= 0 && idx < 8)
                engine->pulsar_section_comping_humanization_active[idx].store(
                    static_cast<int>(value), std::memory_order_relaxed);
        }
        else if (std::strncmp(symbol, "section_comping_humanization_data_", 34) == 0) {
            int idx = std::atoi(symbol + 34);
            if (idx >= 0 && idx < 8 * 4)
                engine->pulsar_section_comping_humanization_data[idx].store(
                    value, std::memory_order_relaxed);
        }
        else if (std::strncmp(symbol, "track_solo_behavior_", 20) == 0) {
            int idx = std::atoi(symbol + 20);
            if (idx >= 0 && idx < 8 * 15)
                engine->pulsar_track_solo_behavior[idx].store(value, std::memory_order_relaxed);
        }
        else if (std::strncmp(symbol, "track_ducking_", 14) == 0) {
            int idx = std::atoi(symbol + 14);
            if (idx >= 0 && idx < 8 * 6)
                engine->pulsar_track_ducking[idx].store(value, std::memory_order_relaxed);
        }
        else if (std::strncmp(symbol, "track_solo_markov_", 18) == 0) {
            int idx = std::atoi(symbol + 18);
            if (idx >= 0 && idx < 8 * 15)
                engine->pulsar_track_solo_markov[idx].store(value, std::memory_order_relaxed);
        }
        else if (std::strcmp(symbol, "soloist_matrix_active") == 0)
            engine->pulsar_soloist_matrix_active.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strncmp(symbol, "soloist_matrix_", 15) == 0) {
            int idx = std::atoi(symbol + 15);
            if (idx >= 0 && idx < 64)
                engine->pulsar_soloist_matrix[idx].store(value, std::memory_order_relaxed);
        }
        else if (std::strcmp(symbol, "band_active") == 0)
            engine->pulsar_band_active.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "band_member_count") == 0)
            engine->pulsar_band_member_count.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strncmp(symbol, "band_member_data_", 17) == 0) {
            int idx = std::atoi(symbol + 17);
            if (idx >= 0 && idx < 96)
                engine->pulsar_band_member_data[idx].store(value, std::memory_order_relaxed);
        }
        else if (std::strncmp(symbol, "band_handoff_", 13) == 0) {
            int idx = std::atoi(symbol + 13);
            if (idx >= 0 && idx < 64)
                engine->pulsar_band_handoff_matrix[idx].store(value, std::memory_order_relaxed);
        }
        else if (std::strcmp(symbol, "band_pull_in_bars_min") == 0)
            engine->pulsar_band_pull_in_bars_min.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "band_pull_in_bars_max") == 0)
            engine->pulsar_band_pull_in_bars_max.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strncmp(symbol, "band_pull_in_", 13) == 0) {
            int idx = std::atoi(symbol + 13);
            if (idx >= 0 && idx < 64)
                engine->pulsar_band_pull_in_matrix[idx].store(value, std::memory_order_relaxed);
        }
        else if (std::strcmp(symbol, "band_improv_carryover") == 0)
            engine->pulsar_band_improv_carryover.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "band_probability") == 0)
            engine->pulsar_band_probability.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "band_bars_per_lead_min") == 0)
            engine->pulsar_band_bars_per_lead_min.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "band_bars_per_lead_max") == 0)
            engine->pulsar_band_bars_per_lead_max.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "arrangement_generation") == 0)
            engine->pulsar_arrangement_generation.store(static_cast<int>(value), std::memory_order_release);
        else if (std::strcmp(symbol, "pulsar_delay_time_a") == 0)
            engine->pulsar_delay_time_a.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "pulsar_delay_time_b") == 0)
            engine->pulsar_delay_time_b.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "pulsar_delay_feedback") == 0)
            engine->pulsar_delay_feedback.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "pulsar_delay_damping") == 0)
            engine->pulsar_delay_damping.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "pulsar_reverb_size") == 0)
            engine->pulsar_reverb_time.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "pulsar_reverb_damping") == 0)
            engine->pulsar_reverb_damping.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "pulsar_reverb_brightness") == 0) {
            engine->pulsar_reverb_diffusion.store(0.3f + value * 0.5f, std::memory_order_relaxed);
        }
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.tides") == 0) {
        if (std::strcmp(symbol, "frequency") == 0)
            engine->tides_frequency.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "slope") == 0)
            engine->tides_slope.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "shape") == 0)
            engine->tides_shape.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "smoothness") == 0)
            engine->tides_smoothness.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "shift") == 0)
            engine->tides_shift.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "mix") == 0)
            engine->tides_mix.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "clock_offset") == 0)
            engine->tides_clock_offset.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "ramp_mode") == 0)
            engine->tides_ramp_mode.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "output_mode") == 0)
            engine->tides_output_mode.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "range") == 0)
            engine->tides_range.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "gate_source") == 0)
            engine->tides_gate_source.store(static_cast<int>(value), std::memory_order_relaxed);
        else if (std::strcmp(symbol, "clock_source") == 0)
            engine->tides_clock_source.store(static_cast<int>(value), std::memory_order_relaxed);
    }
}

float orpheus_engine_get_port(OrpheusEngine* engine,
                              const char* plugin_uri,
                              const char* symbol) {
    if (!engine) return 0.0f;

    if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.looper") == 0) {
        if (std::strcmp(symbol, "position") == 0) {
            // Normalized position: write position / max during record, read / length during play
            int state = engine->looper_current_state;
            int pos = engine->looper_position;
            if (state == 1) { // recording
                return static_cast<float>(pos) / OrpheusEngine::kMaxLoopSamples;
            } else if ((state == 2 || state == 3) && engine->looper_length > 0) {
                return static_cast<float>(pos) / engine->looper_length;
            }
            return 0.0f;
        }
        if (std::strcmp(symbol, "duration") == 0) {
            return static_cast<float>(engine->looper_length) / engine->sample_rate;
        }
        if (std::strcmp(symbol, "state") == 0) {
            return static_cast<float>(engine->looper_current_state);
        }
    }
    if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.tts") == 0) {
        if (std::strcmp(symbol, "playing") == 0) {
            return static_cast<float>(engine->tts_playing.load(std::memory_order_relaxed));
        }
    }
    return 0.0f;
}

}  // extern "C"
