#include "orpheus_units.h"
#include "orpheus_engine.h"
#include <cmath>
#include <cstring>
#include <algorithm>

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

void unit_process_limiter(GraphUnit* u, int n) {
    float* in    = u->inputs[IPORT_INPUT].buffer;
    float* drive = u->inputs[IPORT_DRIVE].buffer;
    float* out   = u->output_buffers[OPORT_OUT];
    for (int i = 0; i < n; i++)
        out[i] = std::tanh(in[i] * drive[i]);
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

void unit_process_master_out(GraphUnit* u, float* output_buffer, int n) {
    float* in_l = u->inputs[IPORT_INPUT_A].buffer;
    float* in_r = u->inputs[IPORT_INPUT_B].buffer;
    for (int i = 0; i < n; i++) {
        output_buffer[i * 2]     = in_l[i];
        output_buffer[i * 2 + 1] = in_r[i];
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
    decay_coeff   = std::exp(-1.0f / (decay_s * sr));
    release_coeff = std::exp(-1.0f / (release_s * sr));
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
    if (idx < 0 || idx >= kNumVoices) return;

    auto& vp = engine->voice_params[idx];
    if (!vp.active.load(std::memory_order_relaxed)) return;
    if (!vp.ever_triggered.load(std::memory_order_relaxed)) return;

    float* out = u->output_buffers[OPORT_OUT];
    int engine_index = vp.engine_index.load(std::memory_order_relaxed);
    int actual_gate = vp.gate.load(std::memory_order_relaxed);
    float env_speed = vp.decay.load(std::memory_order_relaxed); // envSpeed stored in decay
    float raw_hold = engine->voice_hold_level[idx].load(std::memory_order_relaxed);
    float scaled_hold = compute_scaled_hold(raw_hold, env_speed);

    // Smooth hold ramp (~20ms) to avoid clicks
    auto& osc = engine->voice_osc_state[idx];
    float hold_coeff = 1.0f - std::exp(-1.0f / (0.02f * sr));

    // Compute ADSR parameters from envSpeed
    float attack_rate, decay_coeff, sustain_level, release_coeff;
    compute_adsr_from_speed(env_speed, sr, attack_rate, decay_coeff,
                             sustain_level, release_coeff);

    if (engine_index < 0) {
        // ═══ ENGINE 0 (OSC MODE): Triangle + Square with ADSR + Hold ═══
        float note = vp.tune.load(std::memory_order_relaxed);
        float freq = 440.0f * std::pow(2.0f, (note - 69.0f) / 12.0f);
        float sharpness = vp.timbre.load(std::memory_order_relaxed); // 0=triangle, 1=square

        float voice_peak = 0.0f;
        for (int i = 0; i < num_frames; i++) {
            // Triangle oscillator: 4×|phase-0.5| - 1
            float tri = 4.0f * std::fabs(osc.tri_phase - 0.5f) - 1.0f;
            // Square oscillator
            float sq = (osc.sq_phase < 0.5f) ? 1.0f : -1.0f;

            // Crossfade: (tri × (1-sharp)) + (sq × sharp)
            float audio = tri * (1.0f - sharpness) + sq * sharpness;

            // Advance phases
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
            out[i] = sample;

            float abs_s = std::fabs(sample);
            if (abs_s > voice_peak) voice_peak = abs_s;
        }

        engine->voice_levels[idx].store(voice_peak, std::memory_order_relaxed);

    } else {
        // ═══ PLAITS ENGINES (1+): Render Plaits then apply Hold VCA ═══
        auto& voice = engine->voices_dsp[idx];

        // If hold is active, force gate on for Plaits so its internal LPG stays open
        int plaits_gate = actual_gate;
        if (scaled_hold > 0.01f) plaits_gate = 1;

        plaits::Patch patch;
        patch.engine = engine_index;
        patch.note = vp.tune.load(std::memory_order_relaxed);
        patch.harmonics = vp.harmonics.load(std::memory_order_relaxed);
        patch.timbre = vp.timbre.load(std::memory_order_relaxed);
        patch.morph = vp.morph.load(std::memory_order_relaxed);
        patch.frequency_modulation_amount = 0.0f;
        patch.timbre_modulation_amount = 0.0f;
        patch.morph_modulation_amount = 0.0f;
        patch.decay = env_speed;
        patch.lpg_colour = vp.lpg_colour.load(std::memory_order_relaxed);

        plaits::Modulations mod;
        std::memset(&mod, 0, sizeof(mod));
        mod.trigger = plaits_gate ? 1.0f : 0.0f;
        mod.trigger_patched = true;

        bool is_speech = (engine_index == 15);
        if (is_speech) {
            mod.level_patched = true;
            mod.level = plaits_gate ? 1.0f : 0.0f;
        } else {
            mod.level_patched = false;
        }

        const float inv_32768 = 1.0f / 32768.0f;
        int frames_done = 0;
        float voice_peak = 0.0f;

        while (frames_done < num_frames) {
            int block = std::min(static_cast<int>(plaits::kBlockSize),
                                 num_frames - frames_done);

            plaits::Voice::Frame frames[plaits::kMaxBlockSize];
            voice.Render(patch, mod, frames, block);

            for (int i = 0; i < block; i++) {
                float sample = (frames[i].out + frames[i].aux) * 0.5f * inv_32768;

                // Apply hold VCA for Plaits: when hold active, scale by hold level
                // Hold ramp smoothed to avoid clicks
                osc.hold_smoothed += hold_coeff * (scaled_hold - osc.hold_smoothed);

                // For Plaits: if no hold, pass through at unity.
                // If hold active but gate was off, the forced gate keeps Plaits
                // producing; scale by hold level for volume control.
                if (raw_hold > 0.001f && actual_gate == 0) {
                    sample *= osc.hold_smoothed;
                }

                out[frames_done + i] = sample;
                float abs_s = std::fabs(sample);
                if (abs_s > voice_peak) voice_peak = abs_s;
            }

            frames_done += block;
        }

        engine->voice_levels[idx].store(voice_peak, std::memory_order_relaxed);

        // Clear gate for drum voices (one-shot triggers)
        if (idx >= kNumMainVoices && actual_gate) {
            vp.gate.store(0, std::memory_order_relaxed);
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
}

void unit_process_rings(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr) {
    float* in = u->inputs[IPORT_INPUT].buffer;
    float* out_l = u->output_buffers[OPORT_OUT];
    float* out_r = u->output_buffers[OPORT_OUT_RIGHT];

    if (engine->rings_bypass.load(std::memory_order_relaxed)) {
        std::memcpy(out_l, in, num_frames * sizeof(float));
        std::memcpy(out_r, in, num_frames * sizeof(float));
        return;
    }

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

    int strum = engine->rings_strum.load(std::memory_order_relaxed);
    perf.strum = strum != 0;
    if (strum) engine->rings_strum.store(0, std::memory_order_relaxed);

    engine->rings_part.set_model(
        static_cast<rings::ResonatorModel>(
            engine->rings_model.load(std::memory_order_relaxed)));
    engine->rings_part.set_polyphony(
        engine->rings_polyphony.load(std::memory_order_relaxed));

    int frames_done = 0;
    while (frames_done < num_frames) {
        int block = std::min(static_cast<int>(rings::kMaxBlockSize),
                             num_frames - frames_done);

        float out_buf[rings::kMaxBlockSize];
        float aux_buf[rings::kMaxBlockSize];

        engine->rings_part.Process(perf, rings_patch,
                                    in + frames_done, out_buf, aux_buf, block);

        perf.strum = false; // Only trigger on first block

        for (int i = 0; i < block; i++) {
            out_l[frames_done + i] = out_buf[i];
            out_r[frames_done + i] = aux_buf[i];
        }

        frames_done += block;
    }
}

void unit_process_warps(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr) {
    float* in_l = u->inputs[IPORT_INPUT_A].buffer;
    float* in_r = u->inputs[IPORT_INPUT_B].buffer;
    float* out_l = u->output_buffers[OPORT_OUT];
    float* out_r = u->output_buffers[OPORT_OUT_RIGHT];

    if (engine->warps_bypass.load(std::memory_order_relaxed)) {
        std::memcpy(out_l, in_l, num_frames * sizeof(float));
        std::memcpy(out_r, in_r, num_frames * sizeof(float));
        return;
    }

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
}

// -- Dual Delay graph unit --------------------------------
// Stereo in/out, reads delay params from engine atomics,
// uses engine's delay buffers for state.
void unit_process_dual_delay(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr) {
    float* in_l = u->inputs[IPORT_INPUT_A].buffer;
    float* in_r = u->inputs[IPORT_INPUT_B].buffer;
    float* out_l = u->output_buffers[OPORT_OUT];
    float* out_r = u->output_buffers[OPORT_OUT_RIGHT];

    if (engine->delay_bypass.load(std::memory_order_relaxed)) {
        std::memcpy(out_l, in_l, num_frames * sizeof(float));
        std::memcpy(out_r, in_r, num_frames * sizeof(float));
        return;
    }

    const float mix = engine->delay_mix.load(std::memory_order_relaxed);
    const float fb = std::min(engine->delay_feedback.load(std::memory_order_relaxed), 0.95f);

    // Target delay times in samples (0..1 knob → 0.01..2.0s)
    float target_1 = (0.01f + engine->delay_time_1.load(std::memory_order_relaxed) * 1.99f) * sr;
    float target_2 = (0.01f + engine->delay_time_2.load(std::memory_order_relaxed) * 1.99f) * sr;

    // Smooth delay times (~20ms ramp)
    const float smooth = 1.0f - std::exp(-1.0f / (0.02f * sr));
    engine->delay_time_1_smooth += (target_1 - engine->delay_time_1_smooth) * smooth;
    engine->delay_time_2_smooth += (target_2 - engine->delay_time_2_smooth) * smooth;

    int ds1 = std::max(1, std::min(static_cast<int>(engine->delay_time_1_smooth),
                                    OrpheusEngine::kMaxDelaySamples - 1));
    int ds2 = std::max(1, std::min(static_cast<int>(engine->delay_time_2_smooth),
                                    OrpheusEngine::kMaxDelaySamples - 1));

    const float dry = 1.0f - mix;
    const int max_d = OrpheusEngine::kMaxDelaySamples;

    for (int i = 0; i < num_frames; i++) {
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
// Writes to engine->lfo_output_value and output buffer.
void unit_process_hyper_lfo(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr) {
    float* out = u->output_buffers[OPORT_OUT];

    float freq_a = engine->lfo_freq_a.load(std::memory_order_relaxed);
    float freq_b = engine->lfo_freq_b.load(std::memory_order_relaxed);
    float shape = engine->lfo_shape.load(std::memory_order_relaxed);
    int mode = engine->lfo_mode.load(std::memory_order_relaxed);

    float inc_a = freq_a / sr * static_cast<float>(num_frames);
    float inc_b = freq_b / sr * static_cast<float>(num_frames);
    engine->lfo_phase_a += inc_a;
    engine->lfo_phase_b += inc_b;
    if (engine->lfo_phase_a >= 1.0f) engine->lfo_phase_a -= std::floor(engine->lfo_phase_a);
    if (engine->lfo_phase_b >= 1.0f) engine->lfo_phase_b -= std::floor(engine->lfo_phase_b);

    auto gen_wave = [&](float phase) -> float {
        float sq = phase < 0.5f ? 1.0f : -1.0f;
        float tri = 4.0f * std::fabs(phase - 0.5f) - 1.0f;
        return sq + (tri - sq) * shape;
    };

    float a = gen_wave(engine->lfo_phase_a);
    float b = gen_wave(engine->lfo_phase_b);

    float output;
    if (mode == 0) { // AND
        float ua = a * 0.5f + 0.5f;
        float ub = b * 0.5f + 0.5f;
        output = (ua * ub) * 2.0f - 1.0f;
    } else if (mode == 2) { // OR
        float ua = a * 0.5f + 0.5f;
        float ub = b * 0.5f + 0.5f;
        output = (ua + ub - ua * ub) * 2.0f - 1.0f;
    } else { // OFF (mode=1) — use A only
        output = a;
    }

    engine->lfo_output_value = output;

    // Fill output buffer with constant value (for potential future modulation routing)
    for (int i = 0; i < num_frames; i++)
        out[i] = output;
}
