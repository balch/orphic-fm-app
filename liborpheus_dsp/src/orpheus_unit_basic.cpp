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

    // Pulsar output is now routed through the graph (delay/reverb sends + master),
    // not summed here. See DefaultWiringGraph.kt for wiring.

    // Signal chain: global_mute → pan → tape_stop → fader (volume) → peak → limiter → output
    // MasterFader replaces the legacy smooth_master_volume one-pole; MasterTapeStop is
    // inert (pass-through) unless armed via orpheus_engine_master_tape_stop().
    float pan_target = engine->master_pan.load(std::memory_order_relaxed);
    float coeff = smooth_coeff(sr);

    // Global mute: smooth toward 0 (muted) or 1 (unmuted) for click-free transitions
    float mute_target = engine->global_muted.load(std::memory_order_relaxed) != 0 ? 0.0f : 1.0f;

    // Fast early-out: if fully muted and target is still muted, zero output.
    // Still advance tape_stop write heads so an arm immediately after unmute
    // doesn't read uninitialised silence-then-noise from the ring.
    if (engine->smooth_global_mute < 0.0001f && mute_target < 0.5f) {
        engine->smooth_global_mute = 0.0f;
        std::memset(output_buffer, 0, n * 2 * sizeof(float));
        engine->peak_left.store(0.0f, std::memory_order_relaxed);
        engine->peak_right.store(0.0f, std::memory_order_relaxed);
        engine->viz_rings[VIZ_MASTER_OUT].write(0.0f);
        return;
    }

    // Scratch L/R buffers for the tape_stop → fader chain.
    // Sized to kMaxFrames (=512) which bounds n; ~4 KB total on stack.
    float scratch_l[kMaxFrames];
    float scratch_r[kMaxFrames];

    // Pass 1: pan + global mute → scratch L/R (pre-tape_stop/fader).
    // Volume is no longer applied here — the fader handles it next.
    for (int i = 0; i < n; i++) {
        engine->smooth_global_mute += coeff * (mute_target - engine->smooth_global_mute);
        float mute_gain = engine->smooth_global_mute;

        engine->smooth_master_pan += coeff * (pan_target - engine->smooth_master_pan);
        float mp_angle = ((engine->smooth_master_pan + 1.0f) * 0.5f) * (3.14159265f * 0.5f);
        scratch_l[i] = in_l[i] * std::cos(mp_angle) * mute_gain;
        scratch_r[i] = in_r[i] * std::sin(mp_angle) * mute_gain;
    }

    // Master-bus chain: tape_stop → fader → filter → leslie → stutter.
    // All inert (passthrough / unity / silent) unless armed.
    engine->master_tape_stop_l.process(scratch_l, static_cast<size_t>(n));
    engine->master_tape_stop_r.process(scratch_r, static_cast<size_t>(n));
    engine->master_fader_l.process(scratch_l, static_cast<size_t>(n));
    engine->master_fader_r.process(scratch_r, static_cast<size_t>(n));
    engine->master_filter_l.process(scratch_l, static_cast<size_t>(n));
    engine->master_filter_r.process(scratch_r, static_cast<size_t>(n));
    engine->master_leslie_l.process(scratch_l, static_cast<size_t>(n));
    engine->master_leslie_r.process(scratch_r, static_cast<size_t>(n));
    float bp = engine->beat_phase.load(std::memory_order_relaxed);
    float stutter_bpm = engine->clock_bpm.load(std::memory_order_relaxed);
    if (stutter_bpm < 20.0f) stutter_bpm = 120.0f;
    engine->master_scratch_l.process(scratch_l, static_cast<size_t>(n), bp, stutter_bpm);
    engine->master_scratch_r.process(scratch_r, static_cast<size_t>(n), bp, stutter_bpm);

    // Keep the legacy smooth_master_volume mirror in sync with the fader's
    // instantaneous gain. Other readers (diagnostics, future code) may still
    // sample this field; cheap to update once per block.
    engine->smooth_master_volume = engine->master_fader_l.current();

    // Pass 2: peak measurement → feed-forward limiter → soft-sat → interleaved out.
    constexpr float kLimiterThreshold = 0.9f;
    float attack_coeff  = 1.0f - std::exp(-1.0f / (0.0001f * sr));
    float release_coeff = 1.0f - std::exp(-1.0f / (0.100f * sr));

    float env = engine->master_limiter_env;
    float pk_l = 0.0f, pk_r = 0.0f;

    for (int i = 0; i < n; i++) {
        float l = scratch_l[i];
        float r = scratch_r[i];

        // Peak measurement (pre-limiter, post-fader)
        float al = std::fabs(l);
        float ar = std::fabs(r);
        if (al > pk_l) pk_l = al;
        if (ar > pk_r) pk_r = ar;

        // Feed-forward limiter
        float peak = std::max(al, ar);
        float over = peak - kLimiterThreshold;
        if (over > env) {
            env += attack_coeff * (over - env);
        } else {
            env += release_coeff * (0.0f - env);
        }
        float gain = 1.0f;
        if (env > 0.0f) {
            gain = kLimiterThreshold / (kLimiterThreshold + env);
        }
        l *= gain;
        r *= gain;

        // Soft-saturate safety net
        auto soft_sat = [](float x) -> float {
            float ax = std::fabs(x);
            if (ax <= 0.9f) return x;
            float s = (x >= 0.0f) ? 1.0f : -1.0f;
            return s * (0.9f + 0.1f * std::tanh((ax - 0.9f) * 10.0f));
        };
        l = soft_sat(l);
        r = soft_sat(r);

        output_buffer[i * 2]     = l;
        output_buffer[i * 2 + 1] = r;
    }

    engine->master_limiter_env = env;
    engine->peak_left.store(pk_l, std::memory_order_relaxed);
    engine->peak_right.store(pk_r, std::memory_order_relaxed);
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
    u->duck_source = DUCK_NONE;

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
