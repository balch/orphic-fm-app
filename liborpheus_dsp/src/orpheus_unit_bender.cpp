#include "orpheus_units.h"
#include "orpheus_engine.h"
#include <cmath>
#include <cstring>

// ═══════════════════════════════════════════════════════════════════════
// Global Bender (pitch CV + timbre CV + tension/spring audio synthesis)
// ═══════════════════════════════════════════════════════════════════════

static float bender_advance_env(float& env, int& stage, float sr,
                                 float attack_s, float decay_s, float sustain, float release_s) {
    switch (stage) {
        case 1: // attack
            env += 1.0f / (attack_s * sr);
            if (env >= 1.0f) { env = 1.0f; stage = 2; }
            break;
        case 2: // decay
            env -= (env - sustain) / (decay_s * sr);
            if (std::fabs(env - sustain) < 0.001f) { env = sustain; stage = 3; }
            break;
        case 3: // sustain
            break;
        case 4: // release
            env *= 1.0f - 1.0f / (release_s * sr);
            if (env < 0.001f) { env = 0.0f; stage = 0; }
            break;
        default:
            env = 0.0f;
            break;
    }
    return env;
}

void unit_process_bender(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr) {
    float* out_pitch  = u->output_buffers[OPORT_OUT];
    float* out_timbre = u->output_buffers[OPORT_OUT_RIGHT];
    float* out_audio  = u->output_buffers[OPORT_AUX];

    float amount = engine->bend_amount.load(std::memory_order_relaxed);
    float max_bend = engine->bend_max_semitones.load(std::memory_order_relaxed);
    float random_depth = engine->bend_random_depth.load(std::memory_order_relaxed);
    float timbre_mod = engine->bend_timbre_mod.load(std::memory_order_relaxed);
    float tension_vol = engine->bend_tension_vol.load(std::memory_order_relaxed);
    float spring_vol = engine->bend_spring_vol.load(std::memory_order_relaxed);

    bool active = std::fabs(amount) > 0.05f;

    // State transitions
    if (active && !engine->bend_was_active) {
        engine->bend_tension_env_stage = 1; // trigger tension attack
    }
    if (!active && engine->bend_was_active) {
        engine->bend_tension_env_stage = 4; // tension release
        engine->bend_spring_env_stage = 1;  // trigger spring attack
    }
    engine->bend_was_active = active;

    constexpr float TWO_PI = 6.2831853f;

    // Random LFO modulation (matches JSyn BenderPlugin randomLfo + randomDepthGain)
    // JSyn: lfoRate = 1.5 + |bend| * 3.0, randomIntensity = randomDepth * |bend| * 0.1
    float lfo_rate = 1.5f + std::fabs(amount) * 3.0f;
    engine->bend_random_lfo_phase += lfo_rate / sr;
    engine->bend_random_lfo_phase -= std::floor(engine->bend_random_lfo_phase);
    float random_lfo = std::sin(engine->bend_random_lfo_phase * TWO_PI);
    float random_intensity = random_depth * std::fabs(amount) * 0.1f;

    // Apply global bend to all main voice pitch CVs (Hz domain, matches JSyn BenderPlugin)
    // JSyn tension curve: normalizedBend * (1 + |normalizedBend| * 0.5)
    float tension_curve = amount * (1.0f + std::fabs(amount) * 0.5f);
    float semitones = tension_curve * max_bend;
    float freq_mult = std::pow(2.0f, semitones / 12.0f) - 1.0f;
    // JSyn signal path: nonlinearMixer(bend * 1.5) + randomComponent → × frequencyMultiplier × benderDepth(100Hz)
    float nonlinear_bend = amount * 1.5f + random_lfo * random_intensity;
    float bend_hz = nonlinear_bend * freq_mult * 100.0f;
    for (int v = 0; v < kNumMainVoices; v++) {
        engine->voice_bend_cv[v] = bend_hz;
    }

    for (int i = 0; i < num_frames; i++) {
        out_pitch[i] = bend_hz;

        // Timbre CV
        out_timbre[i] = amount * timbre_mod;

        // Tension oscillator (300-500 Hz, driven by bend amount)
        float tension_freq = 300.0f + std::fabs(amount) * 200.0f;
        float tension_env = bender_advance_env(
            engine->bend_tension_env, engine->bend_tension_env_stage,
            sr, 0.1f, 0.1f, 0.6f, 0.2f);
        engine->bend_tension_phase += tension_freq / sr;
        engine->bend_tension_phase -= std::floor(engine->bend_tension_phase);
        float tension = std::sin(engine->bend_tension_phase * TWO_PI)
                       * tension_env * tension_vol;

        // Spring oscillator (wobble frequency, triggered on release)
        float spring_env = bender_advance_env(
            engine->bend_spring_env, engine->bend_spring_env_stage,
            sr, 0.003f, 0.4f, 0.0f, 0.3f);
        engine->bend_wobble_phase += 8.0f / sr;
        engine->bend_wobble_phase -= std::floor(engine->bend_wobble_phase);
        float wobble = std::sin(engine->bend_wobble_phase * TWO_PI) * 80.0f;
        float spring_freq = 350.0f + wobble + spring_env * 200.0f;
        engine->bend_spring_phase += spring_freq / sr;
        engine->bend_spring_phase -= std::floor(engine->bend_spring_phase);
        float spring = std::sin(engine->bend_spring_phase * TWO_PI)
                      * spring_env * spring_vol;

        out_audio[i] = tension + spring;
    }

    // Populate Warps source buffer 7 (BENDER audio)
    std::memcpy(engine->warps_source_buffers[7], out_audio, num_frames * sizeof(float));
}

// ═══════════════════════════════════════════════════════════════════════
// Per-String Bender (4 strings × 2 voices, with audio synthesis)
// ═══════════════════════════════════════════════════════════════════════

void unit_process_per_string_bender(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr) {
    float* out_l = u->output_buffers[OPORT_OUT];
    float* out_r = u->output_buffers[OPORT_OUT_RIGHT];

    std::memset(out_l, 0, num_frames * sizeof(float));
    std::memset(out_r, 0, num_frames * sizeof(float));

    constexpr float TWO_PI = 6.2831853f;

    for (int s = 0; s < 4; s++) {
        auto& st = engine->string_state[s];
        float bend = engine->string_bend[s].load(std::memory_order_relaxed);
        float mix = engine->string_mix[s].load(std::memory_order_relaxed);
        bool active = engine->string_active[s].load(std::memory_order_relaxed) != 0;

        // Direction: strings 2,3 are inverted (right hand)
        float direction = (s < 2) ? 1.0f : -1.0f;
        float directed_bend = bend * direction;

        // State transitions
        if (active && !st.was_active) {
            st.tension_env_stage = 1;
        }
        if (!active && st.was_active) {
            st.tension_env_stage = 4;
            // No spring or pluck on normal string release — matches JSyn PerStringBenderPlugin.releaseString()
            // JSyn only triggers pluck on fast release with sufficient pull (velocity-gated)
        }
        st.was_active = active;
        st.is_active = active;

        // Compute voice CVs (Hz domain matching JSyn PerStringBenderPlugin)
        // Only write when string is active — otherwise let global bender's value stand
        // Uses += to accumulate with global bender (matching JSyn where both connect to same benderInput)
        int v0 = s * 2, v1 = s * 2 + 1;
        if (active) {
            float cubic = directed_bend * directed_bend * directed_bend;
            float semi = cubic * 12.0f;
            float freq_mult = std::pow(2.0f, semi / 12.0f) - 1.0f;
            float bend_hz = freq_mult * 100.0f;  // benderDepth = 100 Hz
            engine->voice_bend_cv[v0] += bend_hz;
            engine->voice_bend_cv[v1] += bend_hz;
        }

        // Voice mix: non-linear crossfade (only override when string active)
        if (active) {
            float volA, volB;
            if (mix <= 0.25f) {
                volA = 1.0f; volB = mix / 0.25f;
            } else if (mix >= 0.75f) {
                volA = (1.0f - mix) / 0.25f; volB = 1.0f;
            } else {
                volA = 1.0f; volB = 1.0f;
            }
            engine->voice_mix_cv[v0] = volA;
            engine->voice_mix_cv[v1] = volB;
        }

        // Per-string audio synthesis
        float base_freq = engine->string_base_freq[s].load(std::memory_order_relaxed);

        for (int i = 0; i < num_frames; i++) {
            float sample = 0.0f;

            // Tension (300+s*20 Hz)
            float tension_freq = 300.0f + s * 20.0f + std::fabs(directed_bend) * 200.0f;
            float tension_env = bender_advance_env(
                st.tension_env, st.tension_env_stage,
                sr, 0.1f, 0.1f, 0.6f, 0.2f);
            st.tension_phase += tension_freq / sr;
            st.tension_phase -= std::floor(st.tension_phase);
            sample += std::sin(st.tension_phase * TWO_PI)
                     * tension_env * 0.015f;

            // Spring (wobble + envelope-modulated freq)
            float spring_env = bender_advance_env(
                st.spring_env, st.spring_env_stage,
                sr, 0.002f, 0.5f, 0.0f, 0.3f);
            st.wobble_phase += 8.0f / sr;
            st.wobble_phase -= std::floor(st.wobble_phase);
            float wobble = std::sin(st.wobble_phase * TWO_PI) * 80.0f;
            float spring_freq = 350.0f + wobble + spring_env * 200.0f;
            st.spring_phase += spring_freq / sr;
            st.spring_phase -= std::floor(st.spring_phase);
            sample += std::sin(st.spring_phase * TWO_PI)
                     * spring_env * 0.5f * 0.4f;

            // Pluck (short burst at base_freq)
            float pluck_env = bender_advance_env(
                st.pluck_env, st.pluck_env_stage,
                sr, 0.001f, 0.08f, 0.0f, 0.05f);
            float pluck_freq = base_freq + s * 150.0f;
            st.pluck_phase += pluck_freq / sr;
            st.pluck_phase -= std::floor(st.pluck_phase);
            sample += std::sin(st.pluck_phase * TWO_PI)
                     * pluck_env * 0.6f;

            // Slide (square wave + LFO modulation when slide bar active)
            float slide_bar_y = engine->slide_bar_y.load(std::memory_order_relaxed);
            if (slide_bar_y > 0.01f) {
                float slide_lfo_freq = 40.0f + s * 10.0f;
                st.slide_lfo_phase += slide_lfo_freq / sr;
                st.slide_lfo_phase -= std::floor(st.slide_lfo_phase);
                float slide_mod = std::sin(st.slide_lfo_phase * TWO_PI) * 50.0f;
                float slide_freq = 200.0f + s * 100.0f + slide_mod;
                st.slide_phase += slide_freq / sr;
                st.slide_phase -= std::floor(st.slide_phase);
                float sq = (st.slide_phase < 0.5f) ? 1.0f : -1.0f;

                float target_ramp = slide_bar_y;
                st.slide_ramp += (target_ramp - st.slide_ramp) / (0.03f * sr);
                sample += sq * st.slide_ramp * 0.1f;
            }

            // Pan: strings 0,1 left; strings 2,3 right
            float pan = (s < 2) ? -0.3f : 0.3f;
            out_l[i] += sample * (0.5f - pan * 0.5f);
            out_r[i] += sample * (0.5f + pan * 0.5f);
        }
    }

    // Apply slide bar pitch bend to all voice bend CVs (Hz domain, matches JSyn PerStringBenderPlugin.setSlideBar)
    float slide_y = engine->slide_bar_y.load(std::memory_order_relaxed);
    if (slide_y > 0.01f) {
        // JSyn: tensionCurve = totalBend * (1 + |totalBend| * 0.5), semitones = tensionCurve * MAX_BEND * 0.5
        float slide_tension = slide_y * (1.0f + std::fabs(slide_y) * 0.5f);
        float slide_semi = slide_tension * 12.0f * 0.5f;  // MAX_BEND_SEMITONES * 0.5
        float slide_freq_mult = std::pow(2.0f, slide_semi / 12.0f) - 1.0f;
        float slide_hz = slide_freq_mult * 100.0f;  // benderDepth = 100 Hz
        for (int v = 0; v < kNumMainVoices; v++) {
            engine->voice_bend_cv[v] += slide_hz;
        }
    }

    // Populate Warps source buffer 8 (STRINGS audio, mono mix)
    for (int i = 0; i < num_frames; i++) {
        engine->warps_source_buffers[8][i] = (out_l[i] + out_r[i]) * 0.5f;
    }
}
