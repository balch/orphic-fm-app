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
                //         21=StringMachine, 22=Chiptune
                // C++: see kOrpheusOutGain[] in orpheus_voice.h for index meanings
                static const int kKotlinToEngine[] = {
                    21, 22, 23, 10, // BD, SD, HH, FMDrum→FM(10) (no C++ FmDrum; Kotlin has custom impl)
                    10, 17,  9,  8, // FM, Noise, Waveshaping, VA
                    12, 11, 19, 20, // Additive, Grain, String, Modal
                    18, 16, 14, 13, // Particle, Swarm, Chord, Wavetable
                    15,             // Speech
                    // V1.2 engines (ordinals 17-22)
                     0,  1,  2,  5, // VA_VCF, PhaseDistortion, SixOpFM, WaveTerrain
                     6,  7           // StringMachine, Chiptune
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
        else if (std::strcmp(symbol, "grains_send") == 0)
            engine->bass_grains_send.store(value, std::memory_order_relaxed);
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.dj") == 0) {
        if (std::strcmp(symbol, "mix") == 0) {
            engine->turntable_mix.store(value, std::memory_order_relaxed);
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
        }
    }
    else if (std::strcmp(plugin_uri, "org.balch.orpheus.plugins.stereo") == 0) {
        if (std::strcmp(symbol, "master_pan") == 0)
            engine->master_pan.store(value, std::memory_order_relaxed);
        else if (std::strcmp(symbol, "master_vol") == 0)
            engine->master_volume.store(value, std::memory_order_relaxed);
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
