#include "orpheus_units.h"
#include "orpheus_units_common.h"
#include "orpheus_engine.h"
#include "orpheus_viz.h"
#include <cmath>
#include <cstring>
#include <algorithm>

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
        out[i] = std::tanh(in[i]);
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

    // Signal chain: pan → volume → peak measurement → limiter → output
    float pan_target = engine->master_pan.load(std::memory_order_relaxed);
    float vol_target = engine->master_volume.load(std::memory_order_relaxed);
    float coeff = smooth_coeff(sr);

    // Feed-forward limiter coefficients
    // Threshold: 0.9 (starts limiting before hard clip)
    // Attack: ~0.1ms (catches transients)
    // Release: ~100ms (smooth recovery, avoids pumping)
    constexpr float kLimiterThreshold = 0.9f;
    float attack_coeff  = 1.0f - std::exp(-1.0f / (0.0001f * sr));  // ~0.1ms
    float release_coeff = 1.0f - std::exp(-1.0f / (0.100f * sr));   // ~100ms

    float env = engine->master_limiter_env;
    float pk_l = 0.0f, pk_r = 0.0f;

    for (int i = 0; i < n; i++) {
        // Pan (constant-power, per-sample smoothed)
        engine->smooth_master_pan += coeff * (pan_target - engine->smooth_master_pan);
        float mp_angle = ((engine->smooth_master_pan + 1.0f) * 0.5f) * (3.14159265f * 0.5f);
        float l = in_l[i] * std::cos(mp_angle);
        float r = in_r[i] * std::sin(mp_angle);

        // Volume (smoothed)
        engine->smooth_master_volume += coeff * (vol_target - engine->smooth_master_volume);
        l *= engine->smooth_master_volume;
        r *= engine->smooth_master_volume;

        // Peak measurement (pre-limiter)
        float al = std::fabs(l);
        float ar = std::fabs(r);
        if (al > pk_l) pk_l = al;
        if (ar > pk_r) pk_r = ar;

        // Feed-forward limiter: track peak, compute gain reduction
        float peak = std::max(al, ar);
        float over = peak - kLimiterThreshold;

        // Envelope follower (fast attack, slow release)
        if (over > env) {
            env += attack_coeff * (over - env);
        } else {
            env += release_coeff * (0.0f - env);
        }

        // Compute gain: reduce by the amount we're over threshold
        // Soft knee: blend between unity and limited in the knee region
        float gain = 1.0f;
        if (env > 0.0f) {
            gain = kLimiterThreshold / (kLimiterThreshold + env);
        }

        l *= gain;
        r *= gain;

        // Safety net: soft clip anything still above ±1.0
        // (shouldn't happen often with the limiter, but prevents digital overs)
        if (l >  1.0f) l =  1.0f; else if (l < -1.0f) l = -1.0f;
        if (r >  1.0f) r =  1.0f; else if (r < -1.0f) r = -1.0f;

        output_buffer[i * 2]     = l;
        output_buffer[i * 2 + 1] = r;
    }

    engine->master_limiter_env = env;

    // Store pre-limiter peaks for monitoring
    engine->peak_left.store(pk_l, std::memory_order_relaxed);
    engine->peak_right.store(pk_r, std::memory_order_relaxed);

    // Write master output visualization (mono peak of L+R)
    float master_viz = std::max(pk_l, pk_r);
    engine->viz_rings[VIZ_MASTER_OUT].write(master_viz);
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
