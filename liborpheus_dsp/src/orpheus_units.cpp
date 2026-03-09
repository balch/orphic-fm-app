#include "orpheus_units.h"
#include "orpheus_engine.h"
#include <cmath>
#include <cstring>
#include <algorithm>

// soft_limit() and kOutGain[] are provided by orpheus_voice.h (included via orpheus_engine.h)

// -- Smoothing coefficient (~5ms at any sample rate) --
static float smooth_coeff(float sample_rate) {
    return 1.0f - std::exp(-1.0f / (0.005f * sample_rate));
}

// -- Input port prepare: fill buffer from sources or constant --
void port_prepare(GraphPort* p, int num_frames, float sr) {
    if (p->num_sources == 0) {
        if (p->is_smoothed) {
            float coeff = smooth_coeff(sr);
            for (int i = 0; i < num_frames; i++) {
                p->smoothed += coeff * (p->constant - p->smoothed);
                p->buffer[i] = p->smoothed;
            }
        } else {
            for (int i = 0; i < num_frames; i++)
                p->buffer[i] = p->constant;
        }
    } else if (p->num_sources == 1) {
        std::memcpy(p->buffer, p->sources[0], num_frames * sizeof(float));
    } else {
        std::memcpy(p->buffer, p->sources[0], num_frames * sizeof(float));
        for (int s = 1; s < p->num_sources; s++) {
            for (int i = 0; i < num_frames; i++)
                p->buffer[i] += p->sources[s][i];
        }
    }
}

// -- Oscillators -------------------------------------------

void unit_process_triangle_osc(GraphUnit* u, int n, float sr) {
    float* freq = u->inputs[IPORT_FREQUENCY].buffer;
    float* amp  = u->inputs[IPORT_AMPLITUDE].buffer;
    float* out  = u->output_buffers[OPORT_OUT];
    float phase = u->state.osc.phase;
    for (int i = 0; i < n; i++) {
        out[i] = (4.0f * std::fabs(phase - 0.5f) - 1.0f) * amp[i];
        phase += freq[i] / sr;
        phase -= std::floor(phase);
    }
    u->state.osc.phase = phase;
}

void unit_process_square_osc(GraphUnit* u, int n, float sr) {
    float* freq = u->inputs[IPORT_FREQUENCY].buffer;
    float* amp  = u->inputs[IPORT_AMPLITUDE].buffer;
    float* out  = u->output_buffers[OPORT_OUT];
    float phase = u->state.osc.phase;
    for (int i = 0; i < n; i++) {
        out[i] = (phase < 0.5f ? 1.0f : -1.0f) * amp[i];
        phase += freq[i] / sr;
        phase -= std::floor(phase);
    }
    u->state.osc.phase = phase;
}

// -- Math --------------------------------------------------

void unit_process_multiply(GraphUnit* u, int n) {
    float* a   = u->inputs[IPORT_INPUT_A].buffer;
    float* b   = u->inputs[IPORT_INPUT_B].buffer;
    float* out = u->output_buffers[OPORT_OUT];
    for (int i = 0; i < n; i++)
        out[i] = a[i] * b[i];
}

void unit_process_add(GraphUnit* u, int n) {
    float* a   = u->inputs[IPORT_INPUT_A].buffer;
    float* b   = u->inputs[IPORT_INPUT_B].buffer;
    float* out = u->output_buffers[OPORT_OUT];
    for (int i = 0; i < n; i++)
        out[i] = a[i] + b[i];
}

void unit_process_multiply_add(GraphUnit* u, int n) {
    float* a   = u->inputs[IPORT_INPUT_A].buffer;
    float* b   = u->inputs[IPORT_INPUT_B].buffer;
    float* c   = u->inputs[IPORT_INPUT_C].buffer;
    float* out = u->output_buffers[OPORT_OUT];
    for (int i = 0; i < n; i++)
        out[i] = a[i] * b[i] + c[i];
}

void unit_process_pass_through(GraphUnit* u, int n) {
    std::memcpy(u->output_buffers[OPORT_OUT],
                u->inputs[IPORT_INPUT].buffer, n * sizeof(float));
}

// -- Dynamics ----------------------------------------------

void unit_process_envelope(GraphUnit* u, int n) {
    float* gate = u->inputs[IPORT_GATE].buffer;
    float* out  = u->output_buffers[OPORT_OUT];
    auto& e = u->state.env;

    for (int i = 0; i < n; i++) {
        bool gate_on = gate[i] > 0.0f;

        // Edge detection
        if (gate_on && !e.gate_was_on) e.stage = 1; // ATTACK
        if (!gate_on && e.gate_was_on) e.stage = 4; // RELEASE
        e.gate_was_on = gate_on;

        switch (e.stage) {
            case 1: // ATTACK (linear ramp to 1.0)
                e.level += e.attack_rate;
                if (e.level >= 1.0f) { e.level = 1.0f; e.stage = 2; }
                break;
            case 2: // DECAY (exponential toward sustain)
                e.level = e.sustain_level +
                          (e.level - e.sustain_level) * e.decay_coeff;
                if (e.level - e.sustain_level < 0.0001f) {
                    e.level = e.sustain_level; e.stage = 3;
                }
                break;
            case 3: // SUSTAIN
                e.level = e.sustain_level;
                break;
            case 4: // RELEASE (exponential toward 0)
                e.level *= e.release_coeff;
                if (e.level < 0.0001f) { e.level = 0.0f; e.stage = 0; }
                break;
            default: // IDLE
                e.level = 0.0f;
                break;
        }
        out[i] = e.level;
    }
}

void unit_process_linear_ramp(GraphUnit* u, int n, float sr) {
    float* target = u->inputs[IPORT_INPUT].buffer;
    float* time   = u->inputs[IPORT_TIME].buffer;
    float* out    = u->output_buffers[OPORT_OUT];
    float current = u->state.ramp.current;

    for (int i = 0; i < n; i++) {
        float t = std::max(time[i], 0.001f);
        float rate = 1.0f / (t * sr);
        float diff = target[i] - current;
        current += std::max(-rate, std::min(rate, diff));
        out[i] = current;
    }
    u->state.ramp.current = current;
}

void unit_process_peak_follower(GraphUnit* u, int n) {
    float* in  = u->inputs[IPORT_INPUT].buffer;
    float* out = u->output_buffers[OPORT_OUT];
    float peak = u->state.peak.peak;
    float coeff = u->state.peak.decay_coeff;

    for (int i = 0; i < n; i++) {
        float s = std::fabs(in[i]);
        peak = std::max(s, peak * coeff);
        out[i] = peak;
    }
    u->state.peak.peak = peak;
}

void unit_process_hard_clip(GraphUnit* u, int n) {
    float* in  = u->inputs[IPORT_INPUT].buffer;
    float* out = u->output_buffers[OPORT_OUT];
    for (int i = 0; i < n; i++)
        out[i] = std::max(-1.0f, std::min(1.0f, in[i]));
}

void unit_process_limiter(GraphUnit* u, OrpheusEngine* engine, int n, float sr) {
    float* in    = u->inputs[IPORT_INPUT].buffer;
    float* drive = u->inputs[IPORT_DRIVE].buffer;
    float* out   = u->output_buffers[OPORT_OUT];
    float mix_target = engine->drive_mix.load(std::memory_order_relaxed);
    float coeff = smooth_coeff(sr);
    for (int i = 0; i < n; i++) {
        engine->smooth_drive_mix += coeff * (mix_target - engine->smooth_drive_mix);
        float mix = engine->smooth_drive_mix;
        float wet = std::tanh(in[i] * drive[i]);
        out[i] = in[i] * (1.0f - mix) + wet * mix;
    }
}

void unit_process_delay_line(GraphUnit* u, int n, float sr) {
    float* in    = u->inputs[IPORT_INPUT].buffer;
    float* dtime = u->inputs[IPORT_TIME].buffer;
    float* out   = u->output_buffers[OPORT_OUT];
    auto& d = u->state.delay;

    for (int i = 0; i < n; i++) {
        d.buffer[d.write_pos] = in[i];
        int delay_samples = static_cast<int>(dtime[i] * sr + 0.5f);
        delay_samples = std::max(0, std::min(delay_samples, d.buffer_size - 1));
        int read_pos = d.write_pos - delay_samples;
        if (read_pos < 0) read_pos += d.buffer_size;
        out[i] = d.buffer[read_pos];
        d.write_pos = (d.write_pos + 1) % d.buffer_size;
    }
}

void unit_process_master_out(GraphUnit* u, OrpheusEngine* engine, float* output_buffer, int n, float sr) {
    float* in_l = u->inputs[IPORT_INPUT_A].buffer;
    float* in_r = u->inputs[IPORT_INPUT_B].buffer;

    // Master pan (constant-power) with per-sample smoothing
    float pan_target = engine->master_pan.load(std::memory_order_relaxed);
    float coeff = smooth_coeff(sr);

    for (int i = 0; i < n; i++) {
        engine->smooth_master_pan += coeff * (pan_target - engine->smooth_master_pan);
        float mp_angle = ((engine->smooth_master_pan + 1.0f) * 0.5f) * (3.14159265f * 0.5f);
        output_buffer[i * 2]     = in_l[i] * std::cos(mp_angle);
        output_buffer[i * 2 + 1] = in_r[i] * std::sin(mp_angle);
    }
}

// -- Unit initialization from descriptor params --
void unit_init(GraphUnit* u, float sr) {
    std::memset(&u->state, 0, sizeof(UnitState));

    for (int p = 0; p < kMaxInputPorts; p++) {
        u->inputs[p].constant = 0.0f;
        u->inputs[p].smoothed = 0.0f;
        u->inputs[p].num_sources = 0;
        u->inputs[p].is_smoothed = false;
    }
    for (int p = 0; p < kMaxOutputPorts; p++)
        std::memset(u->output_buffers[p], 0, sizeof(u->output_buffers[p]));

    u->enabled = true;

    switch (u->type) {
        case UNIT_TRIANGLE_OSC:
        case UNIT_SQUARE_OSC:
            u->inputs[IPORT_FREQUENCY].is_smoothed = true;
            u->inputs[IPORT_FREQUENCY].constant = 440.0f;
            u->inputs[IPORT_AMPLITUDE].is_smoothed = true;
            u->inputs[IPORT_AMPLITUDE].constant = 0.3f;
            break;
        case UNIT_MULTIPLY:
        case UNIT_ADD:
            u->inputs[IPORT_INPUT_A].is_smoothed = true;
            u->inputs[IPORT_INPUT_B].is_smoothed = true;
            break;
        case UNIT_MULTIPLY_ADD:
            u->inputs[IPORT_INPUT_A].is_smoothed = true;
            u->inputs[IPORT_INPUT_B].is_smoothed = true;
            u->inputs[IPORT_INPUT_C].is_smoothed = true;
            break;
        case UNIT_ENVELOPE:
            u->state.env.attack_rate = 1.0f / (0.01f * sr);
            u->state.env.decay_coeff = std::exp(-1.0f / (0.1f * sr));
            u->state.env.sustain_level = 0.7f;
            u->state.env.release_coeff = std::exp(-1.0f / (0.3f * sr));
            break;
        case UNIT_LINEAR_RAMP:
            u->inputs[IPORT_TIME].is_smoothed = true;
            u->inputs[IPORT_TIME].constant = 0.02f;
            break;
        case UNIT_PEAK_FOLLOWER:
            u->state.peak.decay_coeff =
                std::exp(std::log(0.5f) / (0.15f * sr));
            break;
        case UNIT_LIMITER:
            u->inputs[IPORT_INPUT].is_smoothed = false;
            u->inputs[IPORT_DRIVE].is_smoothed = true;
            u->inputs[IPORT_DRIVE].constant = 1.0f;
            break;
        case UNIT_DELAY_LINE:
            u->inputs[IPORT_TIME].is_smoothed = true;
            break;
        case UNIT_CLOCK:
            // Sentinel defaults: negative means "use engine atomics, don't override"
            u->inputs[IPORT_INPUT_A].constant = 0.0f;   // BPM: 0 = no override
            u->inputs[IPORT_INPUT_B].constant = -1.0f;   // Run: negative = no override
            break;
        default:
            break;
    }
}

// -- MI Module Wrappers ------------------------------------

// -- ADSR + Hold helpers for per-voice amplitude envelope --

// Compute ADSR parameters from envSpeed (0.0=fast, 1.0=slow/drone)
// Matches Kotlin: eased = speed², then interpolate ADSR ranges
static void compute_adsr_from_speed(float speed, float sr,
                                     float& attack_rate, float& decay_coeff,
                                     float& sustain_level, float& release_coeff) {
    float eased = speed * speed;
    float attack_s  = 0.005f + eased * 2.995f;   // 5ms – 3s
    float decay_s   = 0.05f  + eased * 2.95f;    // 50ms – 3s
    sustain_level    = 0.8f   + eased * 0.2f;     // 0.8 – 1.0
    float release_s = 0.1f   + eased * 3.9f;     // 100ms – 4s

    attack_rate   = 1.0f / (attack_s * sr);
    // Use -6.908 (≈ ln(0.001)) so the time parameters represent
    // the duration to reach -60 dB, matching JSyn's DAHDSR behavior.
    // The previous -1.0 treated the time as a time constant (1/e decay),
    // which is ~7× slower — notes would never fully release.
    decay_coeff   = std::exp(-6.908f / (decay_s * sr));
    release_coeff = std::exp(-6.908f / (release_s * sr));
}

// Compute scaled hold matching Kotlin: (hold^(4-speed*3)) × (0.5+speed*1.5)
static float compute_scaled_hold(float hold, float speed) {
    if (hold < 0.001f) return 0.0f;
    float exponent = 4.0f - speed * 3.0f;        // 4.0 at speed=0, 1.0 at speed=1
    float scale_factor = 0.5f + speed * 1.5f;    // 0.5 at speed=0, 2.0 at speed=1
    float eased_hold = std::pow(hold, exponent);
    return std::min(1.0f, eased_hold * scale_factor);
}

void unit_process_plaits(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr) {
    int idx = u->state.module.index;
    if (idx < 0 || idx >= kNumVoices) {
        std::memset(u->output_buffers[OPORT_OUT], 0, num_frames * sizeof(float));
        return;
    }

    auto& vp = engine->voice_params[idx];

    if (!vp.active.load(std::memory_order_relaxed)) {
        std::memset(u->output_buffers[OPORT_OUT], 0, num_frames * sizeof(float));
        engine->voice_levels[idx].store(0.0f, std::memory_order_relaxed);
        return;
    }

    // ── Graph gate input: detect rising/falling edges from IPORT_GATE ──
    // When connected (e.g. from Grids triggers), supplements voice_params gate.
    // Uses trigger_pending flag so a complete rise+fall within one buffer isn't lost.
    // The graph gate is OR'd with the API gate so that orpheus_engine_trigger_drum
    // can trigger drums even when the graph gate input (GRIDS) is silent.
    GraphPort* gate_port = &u->inputs[IPORT_GATE];
    if (gate_port->num_sources > 0) {
        int api_gate = vp.gate.load(std::memory_order_relaxed);
        for (int i = 0; i < num_frames; i++) {
            bool gate_on = gate_port->buffer[i] > 0.5f;
            if (gate_on && !vp.graph_gate_prev) {
                vp.graph_trigger_pending = true;
                vp.ever_triggered.store(1, std::memory_order_relaxed);
            }
            vp.graph_gate_prev = gate_on;
        }
        // Merge: graph trigger OR API gate (don't clobber API-set gate)
        if (vp.graph_trigger_pending) {
            vp.gate.store(1, std::memory_order_relaxed);
            vp.graph_trigger_pending = false;
        } else {
            bool graph_gate = vp.graph_gate_prev;
            vp.gate.store((api_gate || graph_gate) ? 1 : 0, std::memory_order_relaxed);
        }
    }

    if (!vp.ever_triggered.load(std::memory_order_relaxed)) {
        std::memset(u->output_buffers[OPORT_OUT], 0, num_frames * sizeof(float));
        return;
    }

    float* out = u->output_buffers[OPORT_OUT];
    int engine_index = vp.engine_index.load(std::memory_order_relaxed);
    int actual_gate = vp.gate.load(std::memory_order_relaxed);
    float env_speed = vp.decay.load(std::memory_order_relaxed); // envSpeed stored in decay
    float raw_hold = engine->voice_hold_level[idx].load(std::memory_order_relaxed);
    float scaled_hold = compute_scaled_hold(raw_hold, env_speed);

    auto& osc = engine->voice_osc_state[idx];

    // ── Idle voice early exit ────────────────────────────────
    // Skip all computation for silent voices to save CPU
    if (engine_index < 0) {
        // Engine 0: idle if envelope finished, no hold, no gate
        if (osc.env_stage == 0 && scaled_hold < 0.001f && actual_gate == 0) {
            std::memset(out, 0, num_frames * sizeof(float));
            engine->voice_levels[idx].store(0.0f, std::memory_order_relaxed);
            return;
        }
    } else {
        // Plaits: idle if gate off, no hold, ADSR finished, and previous peak was near zero
        float prev_peak = engine->voice_levels[idx].load(std::memory_order_relaxed);
        if (actual_gate == 0 && scaled_hold < 0.001f && osc.env_stage == 0 && prev_peak < 0.0001f) {
            // Reset trigger state so next gate-on produces a rising edge.
            // Without this, trigger_state_ stays true (stale from last gate)
            // and OrpheusVoice won't detect TRIGGER_RISING_EDGE on re-trigger.
            engine->voices_dsp[idx].trigger_state_ = false;
            std::memset(out, 0, num_frames * sizeof(float));
            engine->voice_levels[idx].store(0.0f, std::memory_order_relaxed);
            return;
        }
    }

    // ── Smoothing coefficient for this block ──────────────────
    float sc = smooth_coeff(sr);

    // ── LFO vibrato modulation ───────────────────────────────
    // Per-sample LFO buffer for Engine 0; block-midpoint for Plaits engines
    float vib_target = engine->vibrato_depth.load(std::memory_order_relaxed);
    engine->smooth_vibrato_depth += sc * (vib_target - engine->smooth_vibrato_depth);
    float vibrato_depth = engine->smooth_vibrato_depth;
    float lfo_mid = engine->lfo_output_buffer[num_frames / 2];
    float vibrato_semitones = lfo_mid * vibrato_depth * 2.0f;

    // ── Voice coupling: partner envelope → pitch modulation ──
    float coupling_offset = 0.0f;
    {
        float cp_target = engine->coupling_depth.load(std::memory_order_relaxed);
        engine->smooth_coupling_depth += sc * (cp_target - engine->smooth_coupling_depth);
        float coupling = engine->smooth_coupling_depth;
        if (coupling > 0.001f) {
            int partner = (idx % 2 == 0) ? idx + 1 : idx - 1;
            if (partner >= 0 && partner < kNumVoices) {
                coupling_offset = engine->voice_envelope[partner] * coupling * 24.0f;
            }
        }
    }

    // ── Mod source routing: FM + timbre modulation ──────
    float fm_mod_semitones = 0.0f;
    float timbre_mod_offset = 0.0f;
    {
        int duo = idx / 2;
        if (duo < OrpheusEngine::kNumDuos) {
            // Kotlin ModSource enum: VOICE_FM=0, OFF=1, LFO=2, FLUX=3
            int src = engine->mod_source[duo].load(std::memory_order_relaxed);
            float mod_signal = 0.0f;
            if (src == 0) { // VOICE_FM
                int fm_source;
                if (!engine->fm_cross_quad.load(std::memory_order_relaxed)) {
                    fm_source = (idx % 2 == 0) ? idx + 1 : idx - 1;
                } else {
                    fm_source = (idx - 2 + 8) % 8;
                }
                if (fm_source >= 0 && fm_source < kNumVoices) {
                    mod_signal = engine->voice_last_output[fm_source];
                }
            } else if (src == 2) { // LFO
                mod_signal = lfo_mid;
            } else if (src == 3) { // FLUX (Marbles CV)
                mod_signal = engine->marbles_cv_output[duo % 2];
            }
            // src == 1 (OFF) leaves mod_signal = 0.0f
            float md_target = engine->mod_depth[duo].load(std::memory_order_relaxed);
            float fd_target = engine->fm_depth[duo].load(std::memory_order_relaxed);
            engine->smooth_mod_depth[duo] += sc * (md_target - engine->smooth_mod_depth[duo]);
            engine->smooth_fm_depth[duo] += sc * (fd_target - engine->smooth_fm_depth[duo]);
            timbre_mod_offset = mod_signal * engine->smooth_mod_depth[duo];
            fm_mod_semitones = mod_signal * engine->smooth_fm_depth[duo] * 24.0f;
        }
    }

    // ── Bender pitch offset (Hz domain, matches JSyn benderDepth=100Hz) ──
    float bend_hz = 0.0f;
    float bend_vol_mult = 1.0f;
    if (idx < kNumMainVoices) {
        bend_hz = engine->voice_bend_cv[idx];
        bend_vol_mult = engine->voice_mix_cv[idx];
    }

    // Smooth hold ramp coefficient (~20ms) — constant for given sr
    // Using fast approximation: 1 - e^(-50/sr)
    float hold_coeff = 50.0f / sr;  // first-order Taylor approx, good enough for smoothing

    if (engine_index < 0) {
        // ═══ ENGINE 0 (OSC MODE): Triangle + Square with ADSR + Hold ═══
        // Harmonics = self-feedback amount (0-1), creates FM-like timbral richness
        // Morph = duo voice detune in cents (handled at voice pair level in Kotlin,
        //         here we use it as a subtle frequency spread: morph * 50 cents)
        float feedback_amount = vp.harmonics.load(std::memory_order_relaxed);
        float morph_val = vp.morph.load(std::memory_order_relaxed);
        float detune_semitones = morph_val * (50.0f / 100.0f);  // 0-0.5 semitones

        float base_note = vp.tune.load(std::memory_order_relaxed) + coupling_offset + fm_mod_semitones + detune_semitones;
        float sharpness = vp.timbre.load(std::memory_order_relaxed); // 0=triangle, 1=square

        // Compute ADSR parameters from envSpeed
        float attack_rate, decay_coeff, sustain_level, release_coeff;
        compute_adsr_from_speed(env_speed, sr, attack_rate, decay_coeff,
                                 sustain_level, release_coeff);

        float voice_peak = 0.0f;
        for (int i = 0; i < num_frames; i++) {
            // Per-sample LFO vibrato modulation
            float lfo_i = engine->lfo_output_buffer[i];
            float vib_semi = lfo_i * vibrato_depth * 2.0f;
            float note = base_note + vib_semi;
            float freq = 440.0f * std::pow(2.0f, (note - 69.0f) / 12.0f);
            freq += bend_hz;  // Bend applied as Hz offset (matches JSyn benderDepth architecture)

            // Self-feedback: previous output modulates phase for FM-like timbre
            float fb_offset = osc.prev_output * feedback_amount * 0.3f;

            // Triangle oscillator: 4×|phase-0.5| - 1
            float tri_phase_mod = osc.tri_phase + fb_offset;
            tri_phase_mod -= std::floor(tri_phase_mod);
            float tri = 4.0f * std::fabs(tri_phase_mod - 0.5f) - 1.0f;
            // Square oscillator
            float sq_phase_mod = osc.sq_phase + fb_offset;
            sq_phase_mod -= std::floor(sq_phase_mod);
            float sq = (sq_phase_mod < 0.5f) ? 1.0f : -1.0f;

            // Crossfade: (tri × (1-sharp)) + (sq × sharp)
            float audio = tri * (1.0f - sharpness) + sq * sharpness;

            // Advance phases (unmodulated — feedback only affects read position)
            float phase_inc = freq / sr;
            osc.tri_phase += phase_inc;
            osc.tri_phase -= std::floor(osc.tri_phase);
            osc.sq_phase += phase_inc;
            osc.sq_phase -= std::floor(osc.sq_phase);

            // ADSR envelope
            bool gate_on = actual_gate != 0;
            if (gate_on && !osc.env_gate_was_on) osc.env_stage = 1;
            if (!gate_on && osc.env_gate_was_on) osc.env_stage = 4;
            osc.env_gate_was_on = gate_on;

            switch (osc.env_stage) {
                case 1: // ATTACK
                    osc.env_level += attack_rate;
                    if (osc.env_level >= 1.0f) { osc.env_level = 1.0f; osc.env_stage = 2; }
                    break;
                case 2: // DECAY
                    osc.env_level = sustain_level +
                                    (osc.env_level - sustain_level) * decay_coeff;
                    if (osc.env_level - sustain_level < 0.0001f) {
                        osc.env_level = sustain_level; osc.env_stage = 3;
                    }
                    break;
                case 3: // SUSTAIN
                    osc.env_level = sustain_level;
                    break;
                case 4: // RELEASE
                    osc.env_level *= release_coeff;
                    if (osc.env_level < 0.0001f) { osc.env_level = 0.0f; osc.env_stage = 0; }
                    break;
                default: // IDLE
                    osc.env_level = 0.0f;
                    break;
            }

            // Hold ramp (smoothed to avoid clicks)
            osc.hold_smoothed += hold_coeff * (scaled_hold - osc.hold_smoothed);

            // VCA = audio × (envelope + hold)
            float vca = osc.env_level + osc.hold_smoothed;
            float sample = audio * vca;
            osc.prev_output = audio;  // store pre-VCA for feedback
            out[i] = sample;

            float abs_s = std::fabs(sample);
            if (abs_s > voice_peak) voice_peak = abs_s;
        }

        engine->voice_levels[idx].store(voice_peak, std::memory_order_relaxed);

        // Apply bender voice mix volume
        if (bend_vol_mult < 0.999f) {
            for (int i = 0; i < num_frames; i++) {
                out[i] *= bend_vol_mult;
            }
        }

        engine->voice_last_output[idx] = voice_peak;

        // Update peak follower for voice coupling
        engine->voice_envelope[idx] = engine->voice_envelope[idx] * 0.999f
                                     + 0.001f * voice_peak;

    } else {
        // ═══ PLAITS ENGINES (1+): Render via OrpheusVoice (direct Engine::Render) ═══
        auto& voice = engine->voices_dsp[idx];

        // If hold is active, force gate on so engine stays active
        int plaits_gate = actual_gate;
        if (scaled_hold > 0.01f) plaits_gate = 1;

        // Engine change retrigger: force gate off for one block so
        // OrpheusVoice sees a 0→1 rising edge on the next render.
        if (vp.engine_changed.load(std::memory_order_relaxed)) {
            plaits_gate = 0;
            vp.engine_changed.store(0, std::memory_order_relaxed);
        }

        // Compute note with bend applied as Hz offset (matches JSyn benderDepth architecture)
        float note_raw = vp.tune.load(std::memory_order_relaxed) + vibrato_semitones + coupling_offset + fm_mod_semitones;
        float note;
        if (std::fabs(bend_hz) > 0.01f) {
            float base_freq = 440.0f * std::pow(2.0f, (note_raw - 69.0f) / 12.0f);
            float bent_freq = base_freq + bend_hz;
            note = (bent_freq > 0.0f) ? 69.0f + 12.0f * std::log2f(bent_freq / 440.0f) : note_raw;
        } else {
            note = note_raw;
        }
        float harmonics = vp.harmonics.load(std::memory_order_relaxed);
        float timbre = std::max(0.0f, std::min(1.0f,
            vp.timbre.load(std::memory_order_relaxed) + timbre_mod_offset));
        float morph = vp.morph.load(std::memory_order_relaxed);

        // Render via OrpheusVoice (handles engine selection, outGain, soft_limit)
        voice.Render(engine_index, plaits_gate, note, harmonics, timbre, morph, 0.8f,
                     out, num_frames);

        // ADSR envelope — wraps main voices (matching JSyn's DspVoice VCA).
        // Drum voices (idx >= kNumMainVoices) bypass ADSR: their Plaits engines
        // (BassDrum, SnareDrum, HiHat) have built-in percussive envelopes,
        // and the JSyn path renders drums without DspVoice ADSR wrapping.
        float voice_peak = 0.0f;

        if (idx < kNumMainVoices) {
            // Main voices: full ADSR + hold VCA
            float attack_rate, decay_coeff, sustain_level, release_coeff;
            compute_adsr_from_speed(env_speed, sr, attack_rate, decay_coeff,
                                     sustain_level, release_coeff);

            for (int i = 0; i < num_frames; i++) {
                // ADSR state machine
                bool gate_on = actual_gate != 0;
                if (gate_on && !osc.env_gate_was_on) osc.env_stage = 1;
                if (!gate_on && osc.env_gate_was_on) osc.env_stage = 4;
                osc.env_gate_was_on = gate_on;

                switch (osc.env_stage) {
                    case 1: // ATTACK
                        osc.env_level += attack_rate;
                        if (osc.env_level >= 1.0f) { osc.env_level = 1.0f; osc.env_stage = 2; }
                        break;
                    case 2: // DECAY
                        osc.env_level = sustain_level +
                                        (osc.env_level - sustain_level) * decay_coeff;
                        if (osc.env_level - sustain_level < 0.0001f) {
                            osc.env_level = sustain_level; osc.env_stage = 3;
                        }
                        break;
                    case 3: // SUSTAIN
                        osc.env_level = sustain_level;
                        break;
                    case 4: // RELEASE
                        osc.env_level *= release_coeff;
                        if (osc.env_level < 0.0001f) { osc.env_level = 0.0f; osc.env_stage = 0; }
                        break;
                    default: // IDLE
                        osc.env_level = 0.0f;
                        break;
                }

                // Hold ramp (smoothed to avoid clicks)
                osc.hold_smoothed += hold_coeff * (scaled_hold - osc.hold_smoothed);

                // VCA = envelope + hold (same formula as Engine 0)
                float vca = osc.env_level + osc.hold_smoothed;
                out[i] *= vca;

                float abs_s = std::fabs(out[i]);
                if (abs_s > voice_peak) voice_peak = abs_s;
            }
        } else {
            // Drum voices: no ADSR — engine has its own percussive envelope
            for (int i = 0; i < num_frames; i++) {
                float abs_s = std::fabs(out[i]);
                if (abs_s > voice_peak) voice_peak = abs_s;
            }
        }

        engine->voice_levels[idx].store(voice_peak, std::memory_order_relaxed);

        // Apply bender voice mix volume
        if (bend_vol_mult < 0.999f) {
            for (int i = 0; i < num_frames; i++) {
                out[i] *= bend_vol_mult;
            }
        }

        engine->voice_last_output[idx] = voice_peak;

        // Update peak follower for voice coupling
        engine->voice_envelope[idx] = engine->voice_envelope[idx] * 0.999f
                                     + 0.001f * voice_peak;

        // Clear gate for drum voices (one-shot triggers)
        if (idx >= kDrumVoiceStart && actual_gate) {
            vp.gate.store(0, std::memory_order_relaxed);
        }
    }

    // Populate warps source buffers
    if (idx < kDrumVoiceStart) {
        // SYNTH (source 0): accumulate main voices (0-11)
        for (int i = 0; i < num_frames; i++) {
            engine->warps_source_buffers[0][i] += out[i] * (1.0f / kNumMainVoices);
        }
    }
    if (idx >= kDrumVoiceStart) {
        // DRUMS (source 2): accumulate drum voices (12-14)
        for (int i = 0; i < num_frames; i++) {
            engine->warps_source_buffers[2][i] += out[i] * (1.0f / kNumDrumVoices);
        }
    }
}

void unit_process_clouds(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr) {
    float* in_l = u->inputs[IPORT_INPUT_A].buffer;
    float* in_r = u->inputs[IPORT_INPUT_B].buffer;
    float* out_l = u->output_buffers[OPORT_OUT];
    float* out_r = u->output_buffers[OPORT_OUT_RIGHT];

    if (engine->clouds_bypass.load(std::memory_order_relaxed)) {
        std::memcpy(out_l, in_l, num_frames * sizeof(float));
        std::memcpy(out_r, in_r, num_frames * sizeof(float));
        return;
    }

    auto* p = engine->clouds_processor.mutable_parameters();
    p->position = engine->clouds_position.load(std::memory_order_relaxed);
    p->size = engine->clouds_size.load(std::memory_order_relaxed);
    p->pitch = engine->clouds_pitch.load(std::memory_order_relaxed);
    p->density = engine->clouds_density.load(std::memory_order_relaxed);
    p->texture = engine->clouds_texture.load(std::memory_order_relaxed);
    p->dry_wet = engine->clouds_dry_wet.load(std::memory_order_relaxed);
    p->feedback = engine->clouds_feedback.load(std::memory_order_relaxed);
    p->reverb = engine->clouds_reverb.load(std::memory_order_relaxed);
    p->freeze = engine->clouds_freeze.load(std::memory_order_relaxed) != 0;

    engine->clouds_processor.set_playback_mode(
        static_cast<clouds::PlaybackMode>(
            engine->clouds_mode.load(std::memory_order_relaxed)));
    engine->clouds_processor.Prepare();

    int frames_done = 0;
    while (frames_done < num_frames) {
        int block = std::min(static_cast<int>(clouds::kMaxBlockSize),
                             num_frames - frames_done);

        clouds::ShortFrame in_frames[clouds::kMaxBlockSize];
        clouds::ShortFrame out_frames[clouds::kMaxBlockSize];

        for (int i = 0; i < block; i++) {
            float l = std::max(-1.0f, std::min(1.0f, in_l[frames_done + i]));
            float r = std::max(-1.0f, std::min(1.0f, in_r[frames_done + i]));
            in_frames[i].l = static_cast<short>(l * 32767.0f);
            in_frames[i].r = static_cast<short>(r * 32767.0f);
        }

        engine->clouds_processor.Process(in_frames, out_frames, block);

        const float inv = 1.0f / 32768.0f;
        for (int i = 0; i < block; i++) {
            out_l[frames_done + i] = out_frames[i].l * inv;
            out_r[frames_done + i] = out_frames[i].r * inv;
        }

        frames_done += block;
    }

    // DRUMS source (1) for warps routing
    for (int i = 0; i < num_frames; i++) {
        engine->warps_source_buffers[1][i] = (out_l[i] + out_r[i]) * 0.5f;
    }
}

void unit_process_rings(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr) {
    float* in = u->inputs[IPORT_INPUT].buffer;
    float* out_l = u->output_buffers[OPORT_OUT];
    float* out_r = u->output_buffers[OPORT_OUT_RIGHT];

    // moduleIndex distinguishes main resonator (0) from drum resonator (1)
    bool is_drum = u->state.module.index == 1;

    if (is_drum) {
        if (engine->rings_drum_bypass.load(std::memory_order_relaxed)) {
            std::memcpy(out_l, in, num_frames * sizeof(float));
            std::memcpy(out_r, in, num_frames * sizeof(float));
            return;
        }
    } else {
        if (engine->rings_bypass.load(std::memory_order_relaxed)) {
            std::memcpy(out_l, in, num_frames * sizeof(float));
            std::memcpy(out_r, in, num_frames * sizeof(float));
            return;
        }
    }

    // Both resonators share the same parameter values (controlled from same UI)
    rings::Patch rings_patch;
    rings_patch.structure = engine->rings_structure.load(std::memory_order_relaxed);
    rings_patch.brightness = engine->rings_brightness.load(std::memory_order_relaxed);
    rings_patch.damping = engine->rings_damping.load(std::memory_order_relaxed);
    rings_patch.position = engine->rings_position.load(std::memory_order_relaxed);

    rings::PerformanceState perf;
    std::memset(&perf, 0, sizeof(perf));
    perf.tonic = engine->rings_frequency.load(std::memory_order_relaxed);
    perf.note = 0.0f;
    perf.internal_exciter = engine->rings_internal_exciter.load(std::memory_order_relaxed) != 0;
    perf.internal_strum = false;
    perf.internal_note = false;

    // Pick the right Part instance
    rings::Part& part = is_drum ? engine->rings_drum_part : engine->rings_part;

    if (!is_drum) {
        int strum = engine->rings_strum.load(std::memory_order_relaxed);
        perf.strum = strum != 0;
        if (strum) engine->rings_strum.store(0, std::memory_order_relaxed);
    }

    part.set_model(
        static_cast<rings::ResonatorModel>(
            engine->rings_model.load(std::memory_order_relaxed)));
    part.set_polyphony(
        engine->rings_polyphony.load(std::memory_order_relaxed));

    int frames_done = 0;
    while (frames_done < num_frames) {
        int block = std::min(static_cast<int>(rings::kMaxBlockSize),
                             num_frames - frames_done);

        float out_buf[rings::kMaxBlockSize];
        float aux_buf[rings::kMaxBlockSize];

        part.Process(perf, rings_patch,
                     in + frames_done, out_buf, aux_buf, block);

        perf.strum = false; // Only trigger on first block

        for (int i = 0; i < block; i++) {
            out_l[frames_done + i] = out_buf[i];
            out_r[frames_done + i] = aux_buf[i];
        }

        frames_done += block;
    }

    // RESONATOR aux source (4) for warps routing (main resonator only)
    if (!is_drum) {
        std::memcpy(engine->warps_source_buffers[4], out_r, num_frames * sizeof(float));
    }
}

void unit_process_warps(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr) {
    float* out_l = u->output_buffers[OPORT_OUT];
    float* out_r = u->output_buffers[OPORT_OUT_RIGHT];

    if (engine->warps_bypass.load(std::memory_order_relaxed)) {
        // When bypassed with source routing, pass through graph port inputs
        float* fallback_l = u->inputs[IPORT_INPUT_A].buffer;
        float* fallback_r = u->inputs[IPORT_INPUT_B].buffer;
        std::memcpy(out_l, fallback_l, num_frames * sizeof(float));
        std::memcpy(out_r, fallback_r, num_frames * sizeof(float));
        return;
    }

    // Select carrier and modulator from source buffers
    int c_src = engine->warps_carrier_source.load(std::memory_order_relaxed);
    int m_src = engine->warps_modulator_source.load(std::memory_order_relaxed);

    float carrier_buf[kMaxFrames];
    float mod_buf[kMaxFrames];

    auto select_source = [&](int src, float* dest) {
        if (src == 5) { // WARPS feedback
            std::memcpy(dest, engine->warps_feedback_l, num_frames * sizeof(float));
        } else if (src >= 0 && src < OrpheusEngine::kNumWarpsSources) {
            std::memcpy(dest, engine->warps_source_buffers[src], num_frames * sizeof(float));
        } else {
            std::memset(dest, 0, num_frames * sizeof(float));
        }
    };

    select_source(c_src, carrier_buf);
    select_source(m_src, mod_buf);

    float* in_l = carrier_buf;
    float* in_r = mod_buf;

    auto* wp = engine->warps_modulator.mutable_parameters();
    wp->modulation_algorithm = engine->warps_algorithm.load(std::memory_order_relaxed);
    wp->modulation_parameter = engine->warps_timbre.load(std::memory_order_relaxed);
    wp->channel_drive[0] = engine->warps_level1.load(std::memory_order_relaxed);
    wp->channel_drive[1] = engine->warps_level2.load(std::memory_order_relaxed);
    wp->carrier_shape = 0;

    int frames_done = 0;
    while (frames_done < num_frames) {
        int block = std::min(static_cast<int>(warps::kMaxBlockSize),
                             num_frames - frames_done);

        warps::ShortFrame in_frames[warps::kMaxBlockSize];
        warps::ShortFrame out_frames[warps::kMaxBlockSize];

        for (int i = 0; i < block; i++) {
            float l = std::max(-1.0f, std::min(1.0f, in_l[frames_done + i]));
            float r = std::max(-1.0f, std::min(1.0f, in_r[frames_done + i]));
            in_frames[i].l = static_cast<short>(l * 32767.0f);
            in_frames[i].r = static_cast<short>(r * 32767.0f);
        }

        engine->warps_modulator.Process(in_frames, out_frames, block);

        const float inv = 1.0f / 32768.0f;
        for (int i = 0; i < block; i++) {
            out_l[frames_done + i] = out_frames[i].l * inv;
            out_r[frames_done + i] = out_frames[i].r * inv;
        }

        frames_done += block;
    }

    // Store output as feedback source (source 5)
    std::memcpy(engine->warps_feedback_l, out_l, num_frames * sizeof(float));
    std::memcpy(engine->warps_feedback_r, out_r, num_frames * sizeof(float));
    for (int i = 0; i < num_frames; i++) {
        engine->warps_source_buffers[5][i] = (out_l[i] + out_r[i]) * 0.5f;
    }
}

// -- Dual Delay graph unit --------------------------------
// Stereo in/out, reads delay params from engine atomics,
// uses engine's delay buffers for state.
void unit_process_dual_delay(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr) {
    float* in_l = u->inputs[IPORT_INPUT_A].buffer;
    float* in_r = u->inputs[IPORT_INPUT_B].buffer;
    float* lfo_in = u->inputs[IPORT_INPUT_C].buffer;  // LFO modulation input
    float* out_l = u->output_buffers[OPORT_OUT];
    float* out_r = u->output_buffers[OPORT_OUT_RIGHT];

    if (engine->delay_bypass.load(std::memory_order_relaxed)) {
        std::memcpy(out_l, in_l, num_frames * sizeof(float));
        std::memcpy(out_r, in_r, num_frames * sizeof(float));
        return;
    }

    float mix_target = engine->delay_mix.load(std::memory_order_relaxed);
    float fb_target = std::min(engine->delay_feedback.load(std::memory_order_relaxed), 0.95f);
    const float mod_depth_1 = engine->delay_mod_depth_1.load(std::memory_order_relaxed);
    const float mod_depth_2 = engine->delay_mod_depth_2.load(std::memory_order_relaxed);

    // Base delay times in seconds (0..1 knob → 0.01..2.0s)
    float base_time_1 = 0.01f + engine->delay_time_1.load(std::memory_order_relaxed) * 1.99f;
    float base_time_2 = 0.01f + engine->delay_time_2.load(std::memory_order_relaxed) * 1.99f;

    const int max_d = OrpheusEngine::kMaxDelaySamples;

    // Smooth delay times (~20ms ramp)
    const float smooth = 1.0f - std::exp(-1.0f / (0.02f * sr));
    float coeff = smooth_coeff(sr);

    for (int i = 0; i < num_frames; i++) {
        // Per-sample smoothing for mix and feedback
        engine->smooth_delay_mix += coeff * (mix_target - engine->smooth_delay_mix);
        engine->smooth_delay_feedback += coeff * (fb_target - engine->smooth_delay_feedback);
        float mix = engine->smooth_delay_mix;
        float fb = engine->smooth_delay_feedback;
        float dry = 1.0f - mix;

        // LFO modulation: convert -1..1 to 0..1 unipolar, scale by mod depth
        float lfo_uni = (lfo_in[i] + 1.0f) * 0.5f;
        float mod_time_1 = (base_time_1 + lfo_uni * mod_depth_1) * sr;
        float mod_time_2 = (base_time_2 + lfo_uni * mod_depth_2) * sr;

        // Per-sample smoothing for modulated delay time
        engine->delay_time_1_smooth += (mod_time_1 - engine->delay_time_1_smooth) * smooth;
        engine->delay_time_2_smooth += (mod_time_2 - engine->delay_time_2_smooth) * smooth;

        int ds1 = std::max(1, std::min(static_cast<int>(engine->delay_time_1_smooth),
                                        max_d - 1));
        int ds2 = std::max(1, std::min(static_cast<int>(engine->delay_time_2_smooth),
                                        max_d - 1));

        int wp = engine->delay_write_pos;

        int rp1 = (wp - ds1 + max_d) % max_d;
        int rp2 = (wp - ds2 + max_d) % max_d;

        float d1l = engine->delay_buffer_1l[rp1];
        float d1r = engine->delay_buffer_1r[rp1];
        float d2l = engine->delay_buffer_2l[rp2];
        float d2r = engine->delay_buffer_2r[rp2];

        engine->delay_buffer_1l[wp] = in_l[i] + d1l * fb;
        engine->delay_buffer_1r[wp] = in_r[i] + d1r * fb;
        engine->delay_buffer_2l[wp] = in_l[i] + d2l * fb;
        engine->delay_buffer_2r[wp] = in_r[i] + d2r * fb;

        float wet_l = (d1l + d2l) * 0.5f;
        float wet_r = (d1r + d2r) * 0.5f;
        out_l[i] = in_l[i] * dry + wet_l * mix;
        out_r[i] = in_r[i] * dry + wet_r * mix;

        engine->delay_write_pos = (wp + 1) % max_d;
    }
}

// -- HyperLFO graph unit ----------------------------------
// No audio input. Computes dual LFO with logic combination.
// Per-sample computation for correct waveform at all rates.
// Writes to engine->lfo_output_value (monitoring) and output buffer.
void unit_process_hyper_lfo(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr) {
    float* out = u->output_buffers[OPORT_OUT];

    float freq_a = engine->lfo_freq_a.load(std::memory_order_relaxed);
    float freq_b = engine->lfo_freq_b.load(std::memory_order_relaxed);
    float shape = engine->lfo_shape.load(std::memory_order_relaxed);
    int mode = engine->lfo_mode.load(std::memory_order_relaxed);

    float inc_a = freq_a / sr;
    float inc_b = freq_b / sr;
    float phase_a = engine->lfo_phase_a;
    float phase_b = engine->lfo_phase_b;

    auto gen_wave = [](float phase, float shp) -> float {
        float sq = phase < 0.5f ? 1.0f : -1.0f;
        float tri = 4.0f * std::fabs(phase - 0.5f) - 1.0f;
        return sq + (tri - sq) * shp;
    };

    float output = 0.0f;
    float last_a = 0.0f, last_b = 0.0f;
    for (int i = 0; i < num_frames; i++) {
        float a = gen_wave(phase_a, shape);
        float b = gen_wave(phase_b, shape);
        last_a = a;
        last_b = b;

        if (mode == 0) { // AND
            float ua = a * 0.5f + 0.5f;
            float ub = b * 0.5f + 0.5f;
            output = (ua * ub) * 2.0f - 1.0f;
        } else if (mode == 2) { // OR
            float ua = a * 0.5f + 0.5f;
            float ub = b * 0.5f + 0.5f;
            output = (ua + ub - ua * ub) * 2.0f - 1.0f;
        } else { // OFF (mode=1) — silence
            output = 0.0f;
        }
        out[i] = output;

        phase_a += inc_a;
        if (phase_a >= 1.0f) phase_a -= 1.0f;
        phase_b += inc_b;
        if (phase_b >= 1.0f) phase_b -= 1.0f;
    }

    engine->lfo_phase_a = phase_a;
    engine->lfo_phase_b = phase_b;
    engine->lfo_output_value = output; // last sample for monitoring
    engine->lfo_output_value_a = last_a;
    engine->lfo_output_value_b = last_b;
    std::memcpy(engine->lfo_output_buffer, out, num_frames * sizeof(float));

    // LFO source (3) for warps routing
    std::memcpy(engine->warps_source_buffers[3], out, num_frames * sizeof(float));
}

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
}

// ═══════════════════════════════════════════════════════════════════════
// Dattorro Plate Reverb (ported from DattorroReverb.kt / MI Rings)
// ═══════════════════════════════════════════════════════════════════════

void unit_process_reverb(GraphUnit* u, OrpheusEngine* engine,
                         int num_frames, float sample_rate) {
    float* out_l = u->output_buffers[OPORT_OUT];
    float* out_r = u->output_buffers[OPORT_OUT_RIGHT];

    // Self-bypass
    if (engine->reverb_bypass.load(std::memory_order_relaxed)) {
        std::memset(out_l, 0, num_frames * sizeof(float));
        std::memset(out_r, 0, num_frames * sizeof(float));
        return;
    }

    float* in_l = u->inputs[IPORT_INPUT_A].buffer;
    float* in_r = u->inputs[IPORT_INPUT_B].buffer;

    // Load parameters
    float amount_target = engine->reverb_amount.load(std::memory_order_relaxed);
    const float krt    = engine->reverb_time.load(std::memory_order_relaxed);
    const float klp    = engine->reverb_damping.load(std::memory_order_relaxed);
    const float kap    = engine->reverb_diffusion.load(std::memory_order_relaxed);
    const float gain   = 0.5f;  // inputGain
    float rv_coeff = smooth_coeff(sample_rate);

    // Reference delay lengths at 48kHz, scaled to runtime sample rate
    const float rr = sample_rate / 48000.0f;

    // Input allpass lengths
    const int ap1_len = static_cast<int>(150 * rr);
    const int ap2_len = static_cast<int>(214 * rr);
    const int ap3_len = static_cast<int>(319 * rr);
    const int ap4_len = static_cast<int>(527 * rr);

    // Loop allpass + delay lengths
    const int dap1a_len = static_cast<int>(2182 * rr);
    const int dap1b_len = static_cast<int>(2690 * rr);
    const int del1_len  = static_cast<int>(4501 * rr);
    const int dap2a_len = static_cast<int>(2525 * rr);
    const int dap2b_len = static_cast<int>(2197 * rr);
    const int del2_len  = static_cast<int>(6312 * rr);

    // Bases (cumulative offsets in ring buffer)
    const int ap1_base   = 0;
    const int ap2_base   = ap1_base   + ap1_len + 1;
    const int ap3_base   = ap2_base   + ap2_len + 1;
    const int ap4_base   = ap3_base   + ap3_len + 1;
    const int dap1a_base = ap4_base   + ap4_len + 1;
    const int dap1b_base = dap1a_base + dap1a_len + 1;
    const int del1_base  = dap1b_base + dap1b_len + 1;
    const int dap2a_base = del1_base  + del1_len + 1;
    const int dap2b_base = dap2a_base + dap2a_len + 1;
    const int del2_base  = dap2b_base + dap2b_len + 1;

    // LFO modulation tap offsets (scaled)
    const float del1_tap     = 4460.0f * rr;
    const float del1_lfo_amp = 40.0f * rr;
    const float del2_tap     = 6261.0f * rr;
    const float del2_lfo_amp = 50.0f * rr;

    // LFO phase increments (updated every 32 samples)
    const float lfo1_inc = 0.5f / sample_rate * 32.0f;
    const float lfo2_inc = 0.3f / sample_rate * 32.0f;

    constexpr float TWO_PI = 6.2831853f;
    float* buf = engine->reverb_buffer;
    const int mask = OrpheusEngine::kReverbMask;
    int wp = engine->reverb_write_pos;
    float lp1 = engine->reverb_lp_decay1;
    float lp2 = engine->reverb_lp_decay2;
    float lfo1_phase = engine->reverb_lfo1_phase;
    float lfo2_phase = engine->reverb_lfo2_phase;
    float lfo_val0 = engine->reverb_lfo1_value;
    float lfo_val1 = engine->reverb_lfo2_value;

    for (int i = 0; i < num_frames; i++) {
        // Advance write pointer (decrement, wrapping)
        wp = (wp - 1 + OrpheusEngine::kReverbBufferSize) & mask;

        // Update LFOs every 32 samples
        if ((wp & 31) == 0) {
            lfo1_phase += lfo1_inc;
            if (lfo1_phase >= 1.0f) lfo1_phase -= 1.0f;
            lfo_val0 = std::cos(lfo1_phase * TWO_PI);

            lfo2_phase += lfo2_inc;
            if (lfo2_phase >= 1.0f) lfo2_phase -= 1.0f;
            lfo_val1 = std::cos(lfo2_phase * TWO_PI);
        }

        // --- Read/write helpers (inline via lambda) ---
        #define RB(offset) buf[(wp + (offset)) & mask]
        #define WB(offset, val) buf[(wp + (offset)) & mask] = (val)

        // Allpass: read tail, feedforward/feedback, write head
        #define ALLPASS(base, len, input, coeff, result) do { \
            float _tail = RB((base) + (len) - 1); \
            float _v = (input) + _tail * (coeff); \
            WB((base), _v); \
            (result) = _v * (-(coeff)) + _tail; \
        } while(0)

        // Mono sum input
        float acc = (in_l[i] + in_r[i]) * gain;

        // 4 input allpass diffusers
        ALLPASS(ap1_base, ap1_len, acc, kap, acc);
        ALLPASS(ap2_base, ap2_len, acc, kap, acc);
        ALLPASS(ap3_base, ap3_len, acc, kap, acc);
        ALLPASS(ap4_base, ap4_len, acc, kap, acc);

        float apout = acc;

        // Interpolated read with LFO modulation
        auto interpolate = [&](int base, float offset, float lfo_val, float amplitude) -> float {
            float mod_off = offset + amplitude * lfo_val;
            int int_part = static_cast<int>(mod_off);
            float frac = mod_off - int_part;
            float a = buf[(wp + int_part + base) & mask];
            float b = buf[(wp + int_part + base + 1) & mask];
            return a + (b - a) * frac;
        };

        // Path 1 (left output)
        acc = apout;
        acc += interpolate(del2_base, del2_tap, lfo_val1, del2_lfo_amp) * krt;
        lp1 += klp * (acc - lp1);
        acc = lp1;
        ALLPASS(dap1a_base, dap1a_len, acc, -kap, acc);
        ALLPASS(dap1b_base, dap1b_len, acc,  kap, acc);
        WB(del1_base, acc);
        engine->smooth_reverb_amount += rv_coeff * (amount_target - engine->smooth_reverb_amount);
        float amount = engine->smooth_reverb_amount;
        out_l[i] = acc * 2.0f * amount;

        // Path 2 (right output)
        acc = apout;
        acc += interpolate(del1_base, del1_tap, lfo_val0, del1_lfo_amp) * krt;
        lp2 += klp * (acc - lp2);
        acc = lp2;
        ALLPASS(dap2a_base, dap2a_len, acc,  kap, acc);
        ALLPASS(dap2b_base, dap2b_len, acc, -kap, acc);
        WB(del2_base, acc);
        out_r[i] = acc * 2.0f * amount;

        #undef RB
        #undef WB
        #undef ALLPASS
    }

    // Save state
    engine->reverb_write_pos = wp;
    engine->reverb_lp_decay1 = lp1;
    engine->reverb_lp_decay2 = lp2;
    engine->reverb_lfo1_phase = lfo1_phase;
    engine->reverb_lfo2_phase = lfo2_phase;
    engine->reverb_lfo1_value = lfo_val0;
    engine->reverb_lfo2_value = lfo_val1;

}

// ═══════════════════════════════════════════════════════════════════════
// Grids Drum Pattern Generator (MI Grids drum map, AVR-free implementation)
// ═══════════════════════════════════════════════════════════════════════
//
// The pattern ROM is a 5×5 grid of 25 nodes, each 96 bytes (3 instruments × 32 steps).
// x,y select a position in the grid; bilinear interpolation across 4 nearest nodes
// produces an accent level (0-255). If level > density threshold, trigger fires.

namespace grids_data {

static const uint8_t node_0[] = {
    255,0,0,0,0,0,145,0,0,0,0,0,218,0,0,0,72,0,36,0,182,0,0,0,109,0,0,0,72,0,0,0,
    36,0,109,0,0,0,8,0,255,0,0,0,0,0,72,0,0,0,182,0,0,0,36,0,218,0,0,0,145,0,0,0,
    170,0,113,0,255,0,56,0,170,0,141,0,198,0,56,0,170,0,113,0,226,0,28,0,170,0,113,0,198,0,85,0,
};
static const uint8_t node_1[] = {
    229,0,25,0,102,0,25,0,204,0,25,0,76,0,8,0,255,0,8,0,51,0,25,0,178,0,25,0,153,0,127,0,
    28,0,198,0,56,0,56,0,226,0,28,0,141,0,28,0,28,0,170,0,28,0,28,0,255,0,113,0,85,0,85,0,
    159,0,159,0,255,0,63,0,159,0,159,0,191,0,31,0,159,0,127,0,255,0,31,0,159,0,127,0,223,0,95,0,
};
static const uint8_t node_2[] = {
    255,0,0,0,127,0,0,0,0,0,102,0,0,0,229,0,0,0,178,0,204,0,0,0,76,0,51,0,153,0,25,0,
    0,0,127,0,0,0,0,0,255,0,191,0,31,0,63,0,0,0,95,0,0,0,0,0,223,0,0,0,31,0,159,0,
    255,0,85,0,148,0,85,0,127,0,85,0,106,0,63,0,212,0,170,0,191,0,170,0,85,0,42,0,233,0,21,0,
};
static const uint8_t node_3[] = {
    255,0,212,0,63,0,0,0,106,0,148,0,85,0,127,0,191,0,21,0,233,0,0,0,21,0,170,0,0,0,42,0,
    0,0,0,0,141,0,113,0,255,0,198,0,0,0,56,0,0,0,85,0,56,0,28,0,226,0,28,0,170,0,56,0,
    255,0,231,0,255,0,208,0,139,0,92,0,115,0,92,0,185,0,69,0,46,0,46,0,162,0,23,0,208,0,46,0,
};
static const uint8_t node_4[] = {
    255,0,31,0,63,0,63,0,127,0,95,0,191,0,63,0,223,0,31,0,159,0,63,0,31,0,63,0,95,0,31,0,
    8,0,0,0,95,0,63,0,255,0,0,0,127,0,0,0,8,0,0,0,159,0,63,0,255,0,223,0,191,0,31,0,
    76,0,25,0,255,0,127,0,153,0,51,0,204,0,102,0,76,0,51,0,229,0,127,0,153,0,51,0,178,0,102,0,
};
static const uint8_t node_5[] = {
    255,0,51,0,25,0,76,0,0,0,0,0,102,0,0,0,204,0,229,0,0,0,178,0,0,0,153,0,127,0,8,0,
    178,0,127,0,153,0,204,0,255,0,0,0,25,0,76,0,102,0,51,0,0,0,0,0,229,0,25,0,25,0,204,0,
    178,0,102,0,255,0,76,0,127,0,76,0,229,0,76,0,153,0,102,0,255,0,25,0,127,0,51,0,204,0,51,0,
};
static const uint8_t node_6[] = {
    255,0,0,0,223,0,0,0,31,0,8,0,127,0,0,0,95,0,0,0,159,0,0,0,95,0,63,0,191,0,0,0,
    51,0,204,0,0,0,102,0,255,0,127,0,8,0,178,0,25,0,229,0,0,0,76,0,204,0,153,0,51,0,25,0,
    255,0,226,0,255,0,255,0,198,0,28,0,141,0,56,0,170,0,56,0,85,0,28,0,170,0,28,0,113,0,56,0,
};
static const uint8_t node_7[] = {
    223,0,0,0,63,0,0,0,95,0,0,0,223,0,31,0,255,0,0,0,159,0,0,0,127,0,31,0,191,0,31,0,
    0,0,0,0,109,0,0,0,218,0,0,0,182,0,72,0,8,0,36,0,145,0,36,0,255,0,8,0,182,0,72,0,
    255,0,72,0,218,0,36,0,218,0,0,0,145,0,0,0,255,0,36,0,182,0,36,0,182,0,0,0,109,0,0,0,
};
static const uint8_t node_8[] = {
    255,0,0,0,218,0,0,0,36,0,0,0,218,0,0,0,182,0,109,0,255,0,0,0,0,0,0,0,145,0,72,0,
    159,0,0,0,31,0,127,0,255,0,31,0,0,0,95,0,8,0,0,0,191,0,31,0,255,0,31,0,223,0,63,0,
    255,0,31,0,63,0,31,0,95,0,31,0,63,0,127,0,159,0,31,0,63,0,31,0,223,0,223,0,191,0,191,0,
};
static const uint8_t node_9[] = {
    226,0,28,0,28,0,141,0,8,0,8,0,255,0,8,0,113,0,28,0,198,0,85,0,56,0,198,0,170,0,28,0,
    8,0,95,0,8,0,8,0,255,0,63,0,31,0,223,0,8,0,31,0,191,0,8,0,255,0,127,0,127,0,159,0,
    115,0,46,0,255,0,185,0,139,0,23,0,208,0,115,0,231,0,69,0,255,0,162,0,139,0,115,0,231,0,92,0,
};
static const uint8_t node_10[] = {
    145,0,0,0,0,0,109,0,0,0,0,0,255,0,109,0,72,0,218,0,0,0,0,0,36,0,0,0,182,0,0,0,
    0,0,127,0,159,0,127,0,159,0,191,0,223,0,63,0,255,0,95,0,31,0,95,0,31,0,8,0,63,0,8,0,
    255,0,0,0,145,0,0,0,182,0,109,0,109,0,109,0,218,0,0,0,72,0,0,0,182,0,72,0,182,0,36,0,
};
static const uint8_t node_11[] = {
    255,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,255,0,0,0,218,0,72,36,0,0,182,0,0,0,145,109,
    0,0,127,0,0,0,42,0,212,0,0,212,0,0,212,0,0,0,0,0,42,0,0,0,255,0,0,0,170,170,127,85,
    145,0,109,109,218,109,72,0,145,0,72,0,218,0,109,0,182,0,109,0,255,0,72,0,182,109,36,109,255,109,109,0,
};
static const uint8_t node_12[] = {
    255,0,0,0,255,0,191,0,0,0,0,0,95,0,63,0,31,0,0,0,223,0,223,0,0,0,8,0,159,0,127,0,
    0,0,85,0,56,0,28,0,255,0,28,0,0,0,226,0,0,0,170,0,56,0,113,0,198,0,0,0,113,0,141,0,
    255,0,42,0,233,0,63,0,212,0,85,0,191,0,106,0,191,0,21,0,170,0,8,0,170,0,127,0,148,0,148,0,
};
static const uint8_t node_13[] = {
    255,0,0,0,0,0,63,0,191,0,95,0,31,0,223,0,255,0,63,0,95,0,63,0,159,0,0,0,0,0,127,0,
    72,0,0,0,0,0,0,0,255,0,0,0,0,0,0,0,72,0,72,0,36,0,8,0,218,0,182,0,145,0,109,0,
    255,0,162,0,231,0,162,0,231,0,115,0,208,0,139,0,185,0,92,0,185,0,46,0,162,0,69,0,162,0,23,0,
};
static const uint8_t node_14[] = {
    255,0,0,0,51,0,0,0,0,0,0,0,102,0,0,0,204,0,0,0,153,0,0,0,0,0,0,0,51,0,0,0,
    0,0,0,0,8,0,36,0,255,0,0,0,182,0,8,0,0,0,0,0,72,0,109,0,145,0,0,0,255,0,218,0,
    212,0,8,0,170,0,0,0,127,0,0,0,85,0,8,0,255,0,8,0,170,0,0,0,127,0,0,0,42,0,8,0,
};
static const uint8_t node_15[] = {
    255,0,0,0,0,0,0,0,36,0,0,0,182,0,0,0,218,0,0,0,0,0,0,0,72,0,0,0,145,0,109,0,
    36,0,36,0,0,0,0,0,255,0,0,0,182,0,0,0,0,0,0,0,0,0,0,109,218,0,0,0,145,0,72,72,
    255,0,28,0,226,0,56,0,198,0,0,0,0,0,28,28,170,0,0,0,141,0,0,0,113,0,0,0,85,85,85,85,
};
static const uint8_t node_16[] = {
    255,0,0,0,0,0,95,0,0,0,127,0,0,0,0,0,223,0,95,0,63,0,31,0,191,0,0,0,159,0,0,0,
    0,0,31,0,255,0,0,0,0,0,95,0,223,0,0,0,0,0,63,0,191,0,0,0,0,0,0,0,159,0,127,0,
    141,0,28,0,28,0,28,0,113,0,8,0,8,0,8,0,255,0,0,0,226,0,0,0,198,0,56,0,170,0,85,0,
};
static const uint8_t node_17[] = {
    255,0,0,0,8,0,0,0,182,0,0,0,72,0,0,0,218,0,0,0,36,0,0,0,145,0,0,0,109,0,0,0,
    0,0,51,25,76,25,25,0,153,0,0,0,127,102,178,0,204,0,0,0,0,0,255,0,0,0,102,0,229,0,76,0,
    113,0,0,0,141,0,85,0,0,0,0,0,170,0,0,0,56,28,255,0,0,0,0,0,198,0,0,0,226,0,0,0,
};
static const uint8_t node_18[] = {
    255,0,8,0,28,0,28,0,198,0,56,0,56,0,85,0,255,0,85,0,113,0,113,0,226,0,141,0,170,0,141,0,
    0,0,0,0,0,0,0,0,255,0,0,0,127,0,0,0,0,0,0,0,0,0,0,0,63,0,0,0,191,0,0,0,
    255,0,0,0,255,0,127,0,0,0,85,0,0,0,212,0,0,0,212,0,42,0,170,0,0,0,127,0,0,0,0,0,
};
static const uint8_t node_19[] = {
    255,0,0,0,0,0,218,0,182,0,0,0,0,0,145,0,145,0,36,0,0,0,109,0,109,0,0,0,72,0,36,0,
    0,0,0,0,109,0,8,0,72,0,0,0,255,0,182,0,0,0,0,0,145,0,8,0,36,0,8,0,218,0,182,0,
    255,0,0,0,0,0,226,0,85,0,0,0,141,0,0,0,0,0,0,0,170,0,56,0,198,0,0,0,113,0,28,0,
};
static const uint8_t node_20[] = {
    255,0,0,0,113,0,0,0,198,0,56,0,85,0,28,0,255,0,0,0,226,0,0,0,170,0,0,0,141,0,0,0,
    0,0,0,0,0,0,0,0,255,0,145,0,109,0,218,0,36,0,182,0,72,0,72,0,255,0,0,0,0,0,109,0,
    36,0,36,0,145,0,0,0,72,0,72,0,182,0,0,0,72,0,72,0,218,0,0,0,109,0,109,0,255,0,0,0,
};
static const uint8_t node_21[] = {
    255,0,0,0,218,0,0,0,145,0,0,0,36,0,0,0,218,0,0,0,36,0,0,0,182,0,72,0,0,0,109,0,
    0,0,0,0,8,0,0,0,255,0,85,0,212,0,42,0,0,0,0,0,8,0,0,0,85,0,170,0,127,0,42,0,
    109,0,109,0,255,0,0,0,72,0,72,0,218,0,0,0,145,0,182,0,255,0,0,0,36,0,36,0,218,0,8,0,
};
static const uint8_t node_22[] = {
    255,0,0,0,42,0,0,0,212,0,0,0,8,0,212,0,170,0,0,0,85,0,0,0,212,0,8,0,127,0,8,0,
    255,0,85,0,0,0,0,0,226,0,85,0,0,0,198,0,0,0,141,0,56,0,0,0,170,0,28,0,0,0,113,0,
    113,0,56,0,255,0,0,0,85,0,56,0,226,0,0,0,0,0,170,0,0,0,141,0,28,0,28,0,198,0,28,0,
};
static const uint8_t node_23[] = {
    255,0,0,0,229,0,0,0,204,0,204,0,0,0,76,0,178,0,153,0,51,0,178,0,178,0,127,0,102,51,51,25,
    0,0,0,0,0,0,0,31,0,0,0,0,255,0,0,31,0,0,8,0,0,0,191,159,127,95,95,0,223,0,63,0,
    255,0,255,0,204,204,204,204,0,0,51,51,51,51,0,0,204,0,204,0,153,153,153,153,153,0,0,0,102,102,102,102,
};
static const uint8_t node_24[] = {
    170,0,0,0,0,255,0,0,198,0,0,0,0,28,0,0,141,0,0,0,0,226,0,0,56,0,0,113,0,85,0,0,
    255,0,0,0,0,113,0,0,85,0,0,0,0,226,0,0,141,0,0,8,0,170,56,56,198,0,0,56,0,141,28,0,
    255,0,0,0,0,191,0,0,159,0,0,0,0,223,0,0,95,0,0,0,0,63,0,0,127,0,0,0,0,31,0,0,
};

// Drum map: 5×5 grid mapping (row=x>>6, col=y>>6) to node arrays
// Layout matches MI Grids pattern_generator.cc
static const uint8_t* const drum_map[5][5] = {
    { node_10, node_8,  node_0,  node_9,  node_11 },
    { node_15, node_7,  node_13, node_12, node_6  },
    { node_18, node_14, node_4,  node_5,  node_3  },
    { node_23, node_16, node_21, node_1,  node_2  },
    { node_24, node_19, node_17, node_20, node_22 },
};

// U8Mix: unsigned 8-bit linear interpolation (matches avrlib/op.h)
// result = a + (b - a) * balance / 256
static inline uint8_t U8Mix(uint8_t a, uint8_t b, uint8_t balance) {
    return a + static_cast<uint8_t>((static_cast<int16_t>(b - a) * balance) >> 8);
}

// ReadDrumMap: bilinear interpolation across 4 nearest nodes
// step: 0..31, instrument: 0..2, x/y: 0..255
static uint8_t ReadDrumMap(uint8_t step, uint8_t instrument, uint8_t x, uint8_t y) {
    uint8_t i = x >> 6;  // 0..3
    uint8_t j = y >> 6;  // 0..3
    const uint8_t* a_map = drum_map[i][j];
    const uint8_t* b_map = drum_map[i + 1][j];
    const uint8_t* c_map = drum_map[i][j + 1];
    const uint8_t* d_map = drum_map[i + 1][j + 1];
    uint8_t offset = instrument * 32 + step;
    uint8_t a = a_map[offset];
    uint8_t b = b_map[offset];
    uint8_t c = c_map[offset];
    uint8_t d = d_map[offset];
    return U8Mix(U8Mix(a, b, x << 2), U8Mix(c, d, x << 2), y << 2);
}

} // namespace grids_data

void unit_process_grids(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate) {
    float* clock_in  = u->inputs[IPORT_INPUT_A].buffer;  // 24 PPQN tick pulses
    float* out_kick  = u->output_buffers[OPORT_OUT];
    float* out_snare = u->output_buffers[OPORT_OUT_RIGHT];
    float* out_hat   = u->output_buffers[OPORT_AUX];

    if (engine->grids_bypass.load(std::memory_order_relaxed)) {
        std::memset(out_kick,  0, num_frames * sizeof(float));
        std::memset(out_snare, 0, num_frames * sizeof(float));
        std::memset(out_hat,   0, num_frames * sizeof(float));
        return;
    }

    // Read parameters
    float fx = engine->grids_x.load(std::memory_order_relaxed);
    float fy = engine->grids_y.load(std::memory_order_relaxed);
    uint8_t gx = static_cast<uint8_t>(std::max(0.0f, std::min(1.0f, fx)) * 255.0f);
    uint8_t gy = static_cast<uint8_t>(std::max(0.0f, std::min(1.0f, fy)) * 255.0f);

    // Density: 0.0 = sparse (high threshold), 1.0 = dense (low threshold)
    // MI Grids uses ~density (bitwise NOT), so threshold = 255 - density*255
    float dk = engine->grids_density_kick.load(std::memory_order_relaxed);
    float ds = engine->grids_density_snare.load(std::memory_order_relaxed);
    float dh = engine->grids_density_hat.load(std::memory_order_relaxed);
    uint8_t threshold[3] = {
        static_cast<uint8_t>((1.0f - std::max(0.0f, std::min(1.0f, dk))) * 255.0f),
        static_cast<uint8_t>((1.0f - std::max(0.0f, std::min(1.0f, ds))) * 255.0f),
        static_cast<uint8_t>((1.0f - std::max(0.0f, std::min(1.0f, dh))) * 255.0f),
    };

    float randomness = engine->grids_randomness.load(std::memory_order_relaxed);
    int trigger_samples = static_cast<int>(engine->grids_trigger_duration * sample_rate);
    if (trigger_samples < 1) trigger_samples = 1;

    for (int i = 0; i < num_frames; i++) {
        bool tick = clock_in[i] > 0.5f;

        if (tick) {
            engine->grids_pulse_count++;
            // Every 6th clock tick = one 16th-note step (24 PPQN / 6 = 4 PPQN)
            if (engine->grids_pulse_count >= 6) {
                engine->grids_pulse_count = 0;

                // Evaluate pattern for current step
                int step = engine->grids_step;
                for (int ch = 0; ch < 3; ch++) {
                    uint8_t level = grids_data::ReadDrumMap(
                        static_cast<uint8_t>(step),
                        static_cast<uint8_t>(ch),
                        gx, gy);

                    // Add randomness perturbation (LCG PRNG, audio-safe)
                    if (randomness > 0.0f) {
                        engine->grids_rng_state = engine->grids_rng_state * 1664525u + 1013904223u;
                        uint8_t noise = static_cast<uint8_t>(engine->grids_rng_state >> 24);
                        uint8_t perturb = static_cast<uint8_t>(randomness * static_cast<float>(noise) * 0.5f);
                        if (level < 255 - perturb) {
                            level += perturb;
                        } else {
                            level = 255;
                        }
                    }

                    if (level > threshold[ch]) {
                        engine->grids_trigger_countdown[ch] = trigger_samples;
                    }
                }

                engine->grids_step = (step + 1) % 32;
            }
        }

        // Output trigger pulses with countdown
        out_kick[i]  = engine->grids_trigger_countdown[0] > 0 ? 1.0f : 0.0f;
        out_snare[i] = engine->grids_trigger_countdown[1] > 0 ? 1.0f : 0.0f;
        out_hat[i]   = engine->grids_trigger_countdown[2] > 0 ? 1.0f : 0.0f;

        for (int ch = 0; ch < 3; ch++) {
            if (engine->grids_trigger_countdown[ch] > 0)
                engine->grids_trigger_countdown[ch]--;
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Master Clock (sample-accurate 24 PPQN tempo generator)
// ═══════════════════════════════════════════════════════════════════════

void unit_process_clock(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate) {
    float* out_tick = u->output_buffers[OPORT_OUT];
    float* out_beat = u->output_buffers[OPORT_OUT_RIGHT];

    float bpm = engine->clock_bpm.load(std::memory_order_relaxed);
    int running = engine->clock_running.load(std::memory_order_relaxed);

    float port_bpm = u->inputs[IPORT_INPUT_A].constant;
    if (port_bpm > 0.0f) bpm = port_bpm;
    float port_run = u->inputs[IPORT_INPUT_B].constant;
    if (port_run >= 0.0f) running = port_run > 0.5f ? 1 : 0;

    if (!running || bpm <= 0.0f) {
        std::memset(out_tick, 0, num_frames * sizeof(float));
        std::memset(out_beat, 0, num_frames * sizeof(float));
        return;
    }

    double inc = (static_cast<double>(bpm) / 60.0) * 24.0 / static_cast<double>(sample_rate);

    for (int i = 0; i < num_frames; i++) {
        engine->clock_phase += inc;
        bool tick = false;
        bool beat = false;

        if (engine->clock_phase >= 1.0) {
            engine->clock_phase -= 1.0;
            tick = true;
            engine->clock_tick_count++;
            if (engine->clock_tick_count >= 24) {
                engine->clock_tick_count = 0;
                beat = true;
                engine->clock_beat_count = (engine->clock_beat_count + 1) % 4;
            }
        }

        out_tick[i] = tick ? 1.0f : 0.0f;
        out_beat[i] = beat ? 1.0f : 0.0f;
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Marbles Random Sequencer (MI Marbles TGenerator + XYGenerator)
// ═══════════════════════════════════════════════════════════════════════
//
// Input: IPORT_INPUT_A = 24 PPQN clock pulses (from UNIT_CLOCK)
// Output: OPORT_OUT       = t1 gate (rhythmic trigger from TGenerator)
//         OPORT_OUT_RIGHT = x1 CV (random pitch voltage from XYGenerator)
//         OPORT_AUX       = x2 CV (second random voltage)

void unit_process_marbles(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate) {
    float* out_gate = u->output_buffers[OPORT_OUT];
    float* out_cv1  = u->output_buffers[OPORT_OUT_RIGHT];
    float* out_cv2  = u->output_buffers[OPORT_AUX];

    // Self-bypass: output silence when disabled
    if (engine->marbles_bypass.load(std::memory_order_relaxed)) {
        std::memset(out_gate, 0, num_frames * sizeof(float));
        std::memset(out_cv1,  0, num_frames * sizeof(float));
        std::memset(out_cv2,  0, num_frames * sizeof(float));
        return;
    }

    float* clock_in = u->inputs[IPORT_INPUT_A].buffer;

    // ── Step 1: Convert float clock pulses (0/1) to stmlib::GateFlags ──
    stmlib::GateFlags* gate_flags = engine->marbles_gate_flags;
    stmlib::GateFlags prev_flag = engine->marbles_prev_gate_flag;
    for (int i = 0; i < num_frames; i++) {
        bool high = clock_in[i] > 0.5f;
        gate_flags[i] = stmlib::ExtractGateFlags(prev_flag, high);
        prev_flag = gate_flags[i];
    }
    engine->marbles_prev_gate_flag = prev_flag;

    // ── Step 2: Read TGenerator parameters from engine atomics ──
    float t_rate = engine->marbles_t_rate.load(std::memory_order_relaxed);
    float t_bias = engine->marbles_t_bias.load(std::memory_order_relaxed);
    float t_jitter = engine->marbles_t_jitter.load(std::memory_order_relaxed);
    int t_model_i = engine->marbles_t_model.load(std::memory_order_relaxed);
    int t_range_i = engine->marbles_t_range.load(std::memory_order_relaxed);
    float deja_vu = engine->marbles_deja_vu.load(std::memory_order_relaxed);
    int deja_vu_length = engine->marbles_deja_vu_length.load(std::memory_order_relaxed);

    // Clamp enums to valid ranges
    if (t_model_i < 0) t_model_i = 0;
    if (t_model_i > 6) t_model_i = 6;
    if (t_range_i < 0) t_range_i = 0;
    if (t_range_i > 2) t_range_i = 2;
    if (deja_vu_length < 1) deja_vu_length = 1;
    if (deja_vu_length > 16) deja_vu_length = 16;

    engine->marbles_t_generator.set_model(
        static_cast<marbles::TGeneratorModel>(t_model_i));
    engine->marbles_t_generator.set_range(
        static_cast<marbles::TGeneratorRange>(t_range_i));
    engine->marbles_t_generator.set_rate(t_rate);
    engine->marbles_t_generator.set_bias(t_bias);
    engine->marbles_t_generator.set_jitter(t_jitter);
    engine->marbles_t_generator.set_deja_vu(deja_vu);
    engine->marbles_t_generator.set_length(deja_vu_length);
    engine->marbles_t_generator.set_pulse_width_mean(0.5f);
    engine->marbles_t_generator.set_pulse_width_std(0.0f);

    // ── Step 3: Set up Ramps struct with working buffer pointers ──
    marbles::Ramps ramps;
    ramps.external = engine->marbles_ramp_external;
    ramps.master   = engine->marbles_ramp_master;
    ramps.slave[0] = engine->marbles_ramp_slave0;
    ramps.slave[1] = engine->marbles_ramp_slave1;

    // ── Step 4: Process TGenerator (rhythmic gate generation) ──
    // use_external_clock=true: we feed the 24 PPQN clock from UNIT_CLOCK
    bool* gate_out = engine->marbles_gate_out;
    engine->marbles_t_generator.Process(
        true,                   // use_external_clock
        gate_flags,             // external clock gate flags
        ramps,                  // ramp working buffers (filled by TGenerator)
        gate_out,               // output: bool gate[size * kNumTChannels]
        static_cast<size_t>(num_frames));

    // ── Step 5: Read XYGenerator parameters ──
    float x_spread = engine->marbles_x_spread.load(std::memory_order_relaxed);
    float x_bias = engine->marbles_x_bias.load(std::memory_order_relaxed);
    float x_steps = engine->marbles_x_steps.load(std::memory_order_relaxed);
    int x_control_mode_i = engine->marbles_x_control_mode.load(std::memory_order_relaxed);
    int x_range_i = engine->marbles_x_range.load(std::memory_order_relaxed);
    int x_scale_i = engine->marbles_x_scale.load(std::memory_order_relaxed);

    if (x_control_mode_i < 0) x_control_mode_i = 0;
    if (x_control_mode_i > 2) x_control_mode_i = 2;
    if (x_range_i < 0) x_range_i = 0;
    if (x_range_i > 2) x_range_i = 2;
    if (x_scale_i < 0) x_scale_i = 0;
    if (x_scale_i > 5) x_scale_i = 5;

    // Build GroupSettings for X and Y outputs
    marbles::GroupSettings x_settings;
    x_settings.control_mode = static_cast<marbles::ControlMode>(x_control_mode_i);
    x_settings.voltage_range = static_cast<marbles::VoltageRange>(x_range_i);
    x_settings.register_mode = false;
    x_settings.register_value = 0.0f;
    x_settings.spread = x_spread;
    x_settings.bias = x_bias;
    x_settings.steps = x_steps;
    x_settings.deja_vu = deja_vu;
    x_settings.scale_index = x_scale_i;
    x_settings.length = deja_vu_length;
    x_settings.ratio = { 1, 1 };

    marbles::GroupSettings y_settings = x_settings;
    y_settings.ratio = { 1, 1 };

    // ── Step 6: Process XYGenerator (random CV generation) ──
    // CLOCK_SOURCE_INTERNAL_T1_T2_T3: X channels use the T ramps as clock
    float* xy_output = engine->marbles_xy_output;
    engine->marbles_xy_generator.Process(
        marbles::CLOCK_SOURCE_INTERNAL_T1_T2_T3,
        x_settings,
        y_settings,
        gate_flags,
        ramps,
        xy_output,
        static_cast<size_t>(num_frames));

    // ── Step 7: Deinterleave outputs to graph buffers ──
    // TGenerator gate output: interleaved [t1_0, t2_0, t1_1, t2_1, ...]
    // We take t1 (channel 0) as the primary gate output
    for (int i = 0; i < num_frames; i++) {
        out_gate[i] = gate_out[i * 2] ? 1.0f : 0.0f;  // t1 gate
    }

    // XYGenerator output: interleaved [x1, x2, x3, y] per sample (stride = kNumChannels = 4)
    // We take x1 and x2 as the two CV outputs
    for (int i = 0; i < num_frames; i++) {
        out_cv1[i] = xy_output[i * 4 + 0];  // x1 CV
        out_cv2[i] = xy_output[i * 4 + 1];  // x2 CV
    }

    // Cache CV output for mod source routing
    engine->marbles_cv_output[0] = u->output_buffers[OPORT_OUT_RIGHT][num_frames - 1];
    engine->marbles_cv_output[1] = u->output_buffers[OPORT_AUX][num_frames - 1];

    // FLUX source (6) for warps routing
    std::memcpy(engine->warps_source_buffers[6],
                u->output_buffers[OPORT_OUT_RIGHT], num_frames * sizeof(float));
}

// ── UNIT_LOOPER: Beat-quantized audio looper ────────────────
// IPORT_INPUT_A = audio in L (from effect chain)
// IPORT_INPUT_B = audio in R
// IPORT_INPUT_C = beat pulse (from UNIT_CLOCK, for quantization)
// OPORT_OUT      = audio out L (loop playback + passthrough)
// OPORT_OUT_RIGHT = audio out R
void unit_process_looper(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate) {
    float* in_l = u->inputs[IPORT_INPUT_A].buffer;
    float* in_r = u->inputs[IPORT_INPUT_B].buffer;
    float* in_beat = u->inputs[IPORT_INPUT_C].buffer;
    float* out_l = u->output_buffers[OPORT_OUT];
    float* out_r = u->output_buffers[OPORT_OUT_RIGHT];

    float level = engine->looper_level.load(std::memory_order_relaxed);
    float feedback = engine->looper_feedback.load(std::memory_order_relaxed);
    bool quantize = engine->looper_quantize.load(std::memory_order_relaxed) != 0;
    int requested = engine->looper_requested_state.load(std::memory_order_relaxed);

    // Check for state change request
    if (requested != engine->looper_current_state && !engine->looper_pending_transition) {
        if (quantize) {
            engine->looper_pending_transition = true;
            engine->looper_pending_state = requested;
        } else {
            engine->looper_current_state = requested;
            if (requested == 1) { // start recording
                engine->looper_position = 0;
                engine->looper_length = 0;
            }
        }
    }

    int state = engine->looper_current_state;
    int max_samples = OrpheusEngine::kMaxLoopSamples;

    for (int i = 0; i < num_frames; i++) {
        // Check for beat boundary (quantized state transitions)
        if (engine->looper_pending_transition && in_beat[i] > 0.5f) {
            engine->looper_pending_transition = false;
            state = engine->looper_pending_state;
            engine->looper_current_state = state;
            if (state == 1) { // start recording
                engine->looper_position = 0;
                engine->looper_length = 0;
            }
        }

        float loop_l = 0.0f, loop_r = 0.0f;
        int pos = engine->looper_position;

        switch (state) {
            case 0: // Stop — passthrough only
                out_l[i] = in_l[i];
                out_r[i] = in_r[i];
                break;

            case 1: // Record
                if (pos < max_samples) {
                    engine->looper_buffer_l[pos] = in_l[i];
                    engine->looper_buffer_r[pos] = in_r[i];
                    engine->looper_length = pos + 1;
                    engine->looper_position = pos + 1;
                }
                out_l[i] = in_l[i]; // monitor input while recording
                out_r[i] = in_r[i];
                break;

            case 2: // Play
                if (engine->looper_length > 0) {
                    loop_l = engine->looper_buffer_l[pos] * level;
                    loop_r = engine->looper_buffer_r[pos] * level;
                    engine->looper_position = (pos + 1) % engine->looper_length;
                }
                out_l[i] = in_l[i] + loop_l;
                out_r[i] = in_r[i] + loop_r;
                break;

            case 3: // Overdub
                if (engine->looper_length > 0) {
                    loop_l = engine->looper_buffer_l[pos];
                    loop_r = engine->looper_buffer_r[pos];
                    engine->looper_buffer_l[pos] = in_l[i] + loop_l * feedback;
                    engine->looper_buffer_r[pos] = in_r[i] + loop_r * feedback;
                    engine->looper_position = (pos + 1) % engine->looper_length;
                }
                out_l[i] = in_l[i] + loop_l * level;
                out_r[i] = in_r[i] + loop_r * level;
                break;
        }
    }
}
