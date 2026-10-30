#include "orpheus_units.h"
#include "orpheus_units_common.h"
#include "orpheus_engine.h"
#include "orpheus_unit_chaos.h"
#include <cmath>
#include <cstring>
#include <algorithm>

// soft_limit() and kOutGain[] are provided by orpheus_voice.h (included via orpheus_engine.h)

// ── Helper: process external gate buffer for a voice ──
// Scans ext_gate_buffer for rising edges, sets trigger_pending/ext_retrigger/ever_triggered,
// then merges with API gate. Returns true if an external gate was processed.
static inline void process_ext_gate(OrpheusEngine::VoiceParams& vp,
                                    const float* ext_gate_buffer, int num_frames) {
    int api_gate = vp.gate.load(std::memory_order_relaxed);
    for (int i = 0; i < num_frames; i++) {
        bool gate_on = ext_gate_buffer[i] > 0.5f;
        if (gate_on && !vp.graph_gate_prev) {
            vp.graph_trigger_pending = true;
            vp.ext_retrigger = true;
            vp.ever_triggered.store(1, std::memory_order_relaxed);
        }
        vp.graph_gate_prev = gate_on;
    }
    // Merge: external trigger OR API gate (don't clobber API-set gate)
    if (vp.graph_trigger_pending) {
        vp.gate.store(1, std::memory_order_relaxed);
        vp.graph_trigger_pending = false;
    } else {
        bool graph_gate = vp.graph_gate_prev;
        vp.gate.store((api_gate || graph_gate) ? 1 : 0, std::memory_order_relaxed);
    }
}

// ── Helper: release a voice from external gate ──
static inline void release_ext_gate(OrpheusEngine::VoiceParams& vp) {
    if (vp.graph_gate_prev) {
        vp.graph_gate_prev = false;
        vp.graph_trigger_pending = false;
        vp.gate.store(0, std::memory_order_relaxed);
    }
}

// ── Helper: Warps carrier dry-path attenuation ──
// Only the CARRIER source is attenuated — the modulator passes through
// at full level, matching hardware Warps behaviour. Warps wet output
// replaces the carrier in the mix; the modulator is merely used to
// shape the carrier inside the effect.
// At mix=1: carrier dry is silenced, wet fills the gap at matching level.
static inline float warps_dry_scale(OrpheusEngine* engine, int voice_idx) {
    if (engine->warps_bypass.load(std::memory_order_relaxed)) return 1.0f;
    float mix = engine->warps_smooth_mix;  // use smoothed value (no clicks)
    if (mix <= 0.001f) return 1.0f;
    int carrier = engine->warps_carrier_source.load(std::memory_order_relaxed);
    // SYNTH(0) = voices 0-7, DRUMS(1) = via drum voices, REPL(2) = voices 8-11
    bool is_carrier = false;
    if (carrier == 0 && voice_idx < 8) is_carrier = true;
    if (carrier == 1 && voice_idx >= kDrumVoiceStart) is_carrier = true;
    if (carrier == 2 && voice_idx >= 8 && voice_idx < kDrumVoiceStart) is_carrier = true;
    return is_carrier ? (1.0f - mix) : 1.0f;
}

// ── Helper: resolve Marbles T buffer from trigger source index ──
// Returns pointer to T1/T2/T3 buffer, or nullptr if trig_src is not 1-3.
static inline float* resolve_marbles_t(OrpheusEngine* engine, int trig_src) {
    if (trig_src < 1 || trig_src > 3) return nullptr;
    float* marbles_t[] = { engine->marbles_t1_buffer,
                           engine->marbles_t2_buffer,
                           engine->marbles_t3_buffer };
    return marbles_t[trig_src - 1];
}

// ── Helper: resolve Marbles X (CV) buffer from pitch source index ──
// Returns pointer to X1/X2/X3 buffer, or nullptr if pitch_src is not 1-3.
static inline float* resolve_marbles_x(OrpheusEngine* engine, int pitch_src) {
    if (pitch_src < 1 || pitch_src > 3) return nullptr;
    float* marbles_x[] = { engine->marbles_x1_buffer,
                           engine->marbles_x2_buffer,
                           engine->marbles_x3_buffer };
    return marbles_x[pitch_src - 1];
}

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

// Score voices only: orchestral envelope. Attack/release are tens to a few hundred ms
// (an eighth note at tempo), not compute_adsr_from_speed's second-long drone range.
static void compute_score_adsr(float speed, float sr,
                               float& attack_rate, float& decay_coeff,
                               float& sustain_level, float& release_coeff) {
    float eased = speed * speed;
    float attack_s  = 0.005f + eased * 0.145f;  // 5ms – 150ms
    float decay_s   = 0.05f  + eased * 0.35f;
    sustain_level    = 0.8f   + eased * 0.2f;
    float release_s = 0.15f  + eased * 0.45f;   // 150ms – 600ms

    attack_rate   = 1.0f / (attack_s * sr);
    decay_coeff   = std::exp(-6.908f / (decay_s * sr));
    release_coeff = std::exp(-6.908f / (release_s * sr));
}

// Score sustain taper: notes drift toward this fraction of sustain instead of holding
// flat. PROVISIONAL — calibrated by ear, 2026-08-20 Fifth-orchestra session.
constexpr float kScoreSustainFloorRatio = 0.6f;
constexpr float kScoreSustainTauSeconds = 3.5f;

// Compute scaled hold matching Kotlin: (hold^(4-speed*3)) × (0.5+speed*1.5)
static float compute_scaled_hold(float hold, float speed) {
    if (hold < 0.001f) return 0.0f;
    float exponent = 4.0f - speed * 3.0f;        // 4.0 at speed=0, 1.0 at speed=1
    float scale_factor = 0.5f + speed * 1.5f;    // 0.5 at speed=0, 2.0 at speed=1
    float eased_hold = std::pow(hold, exponent);
    return std::min(1.0f, eased_hold * scale_factor);
}

static inline float clamp01(float x) { return std::max(0.0f, std::min(1.0f, x)); }

// A destination's cables added onto base one term at a time as base + value * gain, the shape
// the release build fused for the old mod sources, so legacy patches render bit-identically.
static inline float add_terms(float base, const PatchVoiceTerms& t, int dst) {
    for (int j = 0; j < t.count[dst]; j++) base += t.term[dst][j].value * t.term[dst][j].gain;
    return base;
}

// add_terms clamped to [0, 1]. With nothing plugged in the base is left untouched.
static inline float add_terms01(float base, const PatchVoiceTerms& t, int dst) {
    return (t.count[dst] > 0) ? clamp01(add_terms(base, t, dst)) : base;
}

// ENV cables move envSpeed (stored in decay) before the envelope and hold read it.
static inline float env_speed_with(float decay, const PatchVoiceTerms& t, const PatchVoiceMods& m) {
    return (t.count[kPatchDstEnv] > 0) ? clamp01(decay + m.env) : decay;
}

// FM cables at sample i: buffer sources fuse as freq + v * gain, block constants add their Hz.
static inline float apply_fm(float freq, const PatchFmPlan& p, int i, float partner) {
    for (int j = 0; j < p.count; j++) freq += (p.buf[j] ? p.buf[j][i] : partner) * p.gain[j];
    if (p.has_block) freq += p.block_hz;
    return freq;
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

    // ── External gate routing ──
    // Drums: Grids (graph) or Marbles T1/T2/T3. Quads: Internal/MIDI or Marbles T1/T2/T3.
    // Uses trigger_pending flag so a complete rise+fall within one buffer isn't lost.
    // Gate is OR'd with API gate so manual triggers still work.
    {
        float* ext_gate_buffer = nullptr;
        if (idx >= kDrumVoiceStart) {
            int drum_slot = idx - kDrumVoiceStart;
            int trig_src = engine->drum_trigger_source[drum_slot].load(std::memory_order_relaxed);
            ext_gate_buffer = resolve_marbles_t(engine, trig_src);
            if (!ext_gate_buffer && u->inputs[IPORT_GATE].num_sources > 0)
                ext_gate_buffer = u->inputs[IPORT_GATE].buffer;
        } else {
            int quad = idx / 4;
            if (quad < 3) {
                int trig_src = engine->quad_trigger_source[quad].load(std::memory_order_relaxed);
                ext_gate_buffer = resolve_marbles_t(engine, trig_src);
            }
            if (!ext_gate_buffer && u->inputs[IPORT_GATE].num_sources > 0)
                ext_gate_buffer = u->inputs[IPORT_GATE].buffer;
        }
        if (ext_gate_buffer)
            process_ext_gate(vp, ext_gate_buffer, num_frames);
        else
            release_ext_gate(vp);
    }

    if (!vp.ever_triggered.load(std::memory_order_relaxed)) {
        std::memset(u->output_buffers[OPORT_OUT], 0, num_frames * sizeof(float));
        return;
    }

    // ── Patch matrix: smoothed master depth and this voice's block-rate offsets ──
    float sc = block_smooth_coeff(sr, num_frames, kDuoDepthSmoothSeconds);
    int duo = idx / 2;
    float depth = 0.0f;
    if (duo < OrpheusEngine::kNumDuos) {
        float md_target = engine->mod_depth[duo].load(std::memory_order_relaxed);
        engine->smooth_mod_depth[duo] += sc * (md_target - engine->smooth_mod_depth[duo]);
        depth = engine->smooth_mod_depth[duo];
    }
    PatchVoiceTerms terms;
    patch_voice_terms(*engine, duo, idx % 2, idx, num_frames, depth, &terms);
    PatchVoiceMods mods = patch_term_sums(terms);

    float* out = u->output_buffers[OPORT_OUT];
    int engine_index = vp.engine_index.load(std::memory_order_relaxed);
    int actual_gate = vp.gate.load(std::memory_order_relaxed);
    // envSpeed stored in decay; an ENV cable moves it before envelope and hold read it
    float env_speed = env_speed_with(vp.decay.load(std::memory_order_relaxed), terms, mods);
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
            engine->voice_envelope[idx] = coupling_follower_block(engine->voice_envelope[idx], 0.0f, sr, num_frames);
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
            engine->voice_envelope[idx] = coupling_follower_block(engine->voice_envelope[idx], 0.0f, sr, num_frames);
            return;
        }
    }

    // ── Vibrato modulation ───────────────────────────────────
    // Block-midpoint from dedicated vibrato buffer (Hz domain, matches JSyn)
    float vibrato_hz_mid = engine->vibrato_output_buffer[num_frames / 2];
    // For Plaits: convert Hz to semitones for tune offset
    float base_freq = 440.0f * std::pow(2.0f, (vp.tune.load(std::memory_order_relaxed) - 69.0f) / 12.0f);
    float vibrato_semitones = (base_freq > 1.0f) ? 12.0f * std::log2(1.0f + vibrato_hz_mid / base_freq) : 0.0f;

    // ── Voice coupling: partner envelope → pitch modulation ──
    float coupling_hz = 0.0f;
    {
        float coupling = engine->smooth_coupling_depth;  // smoothed in orpheus_graph_process
        if (coupling > 0.001f) {
            int partner = (idx % 2 == 0) ? idx + 1 : idx - 1;
            if (partner >= 0 && partner < kNumVoices) {
                coupling_hz = engine->voice_envelope[partner] * coupling * 30.0f;
            }
        }
    }

    // VOICE_FM partner for per-sample FM (Engine 0 only; Plaits engines take no FM)
    int fm_voice_idx = patch_fm_partner(*engine, idx);

    // ── Bender pitch offset (Hz domain, matches JSyn benderDepth=100Hz) ──
    float bend_hz = 0.0f;
    float bend_vol_mult = 1.0f;
    if (idx < kNumMainVoices) {
        bend_hz = engine->voice_bend_cv[idx];
        bend_vol_mult = engine->voice_mix_cv[idx];
    }

    // ── Flux pitch CV modulation (trigger router X1/X2/X3) ──
    // Flux outputs 2^(V*mix)-1 (frequency ratio). JSyn applies as: freq * (1 + cv).
    // Per-sample buffer pointer for smooth pitch modulation (avoids zipper artifacts).
    float* pitch_cv_buffer = nullptr;
    if (idx >= kDrumVoiceStart) {
        int drum_slot = idx - kDrumVoiceStart;
        int pitch_src = engine->drum_pitch_source[drum_slot].load(std::memory_order_relaxed);
        pitch_cv_buffer = resolve_marbles_x(engine, pitch_src);
    } else {
        int quad = idx / 4;
        if (quad < 3) {
            int pitch_src = engine->quad_pitch_source[quad].load(std::memory_order_relaxed);
            pitch_cv_buffer = resolve_marbles_x(engine, pitch_src);
        }
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

        // Patch cables: MORPH and HARMONICS (feedback); TIMBRE is unused here
        morph_val = add_terms01(morph_val, terms, kPatchDstMorph);
        feedback_amount = add_terms01(feedback_amount, terms, kPatchDstHarmonics);

        // Detune: ±morph × 25 cents (matching JSyn: MAX_DETUNE=50 cents, split ±half)
        // Voice A (even idx) gets positive detune, Voice B (odd) gets negative
        float detune_sign = (idx % 2 == 0) ? 1.0f : -1.0f;
        float detune_semitones = detune_sign * morph_val * (25.0f / 100.0f);

        float base_note = vp.tune.load(std::memory_order_relaxed) + detune_semitones;
        base_note = add_terms(base_note, terms, kPatchDstPitch);
        base_note = std::max(20.0f, std::min(127.0f, base_note));

        PatchFmTaps fm_taps;
        patch_fm_taps(*engine, duo, idx % 2, &fm_taps);
        PatchFmPlan fm_plan;
        patch_fm_plan(*engine, fm_taps, depth, &fm_plan);
        bool has_fm = fm_plan.count > 0 || fm_plan.has_block;

        float sharpness = vp.timbre.load(std::memory_order_relaxed); // 0=triangle, 1=square

        // Compute ADSR parameters from envSpeed
        float attack_rate, decay_coeff, sustain_level, release_coeff;
        compute_adsr_from_speed(env_speed, sr, attack_rate, decay_coeff,
                                 sustain_level, release_coeff);
        // Trigger mode: percussive envelope (sustain = 0)
        if (idx < kNumMainVoices) {
            int q = idx / 4;
            if (q < 3 && engine->quad_trigger_mode[q].load(std::memory_order_relaxed))
                sustain_level = 0.0f;
        }

        float voice_peak = 0.0f;
        for (int i = 0; i < num_frames; i++) {
            // Per-sample vibrato + LFO modulation
            float lfo_i = engine->lfo_output_buffer[i];
            float vib_hz_i = engine->vibrato_output_buffer[i];  // Hz from dedicated vibrato osc
            float freq = 440.0f * std::pow(2.0f, (base_note - 69.0f) / 12.0f);
            freq += vib_hz_i;     // Vibrato in Hz (matches JSyn VibratoPlugin)
            freq += bend_hz;      // Bend applied as Hz offset (matches JSyn benderDepth architecture)
            freq += coupling_hz;  // Coupling applied as Hz offset (matches JSyn couplingDepth × 30Hz)
            // Flux pitch CV: per-sample multiplicative ratio, matches JSyn baseFreq * (1 + cv)
            if (pitch_cv_buffer) {
                freq *= (1.0f + pitch_cv_buffer[i]);
            }

            // Linear FM in Hz from the FM cables (VOICE_FM reads the partner's previous block)
            if (has_fm) {
                float partner = (fm_voice_idx >= 0) ? engine->voice_fm_buffer[fm_voice_idx][i] : 0.0f;
                freq = apply_fm(freq, fm_plan, i, partner);
            }

            float audio = osc.core.Next(freq, sr, sharpness, feedback_amount);

            // ADSR envelope (with external re-trigger support)
            bool gate_on = actual_gate != 0;
            if (vp.ext_retrigger) {
                osc.env_stage = 1;
                vp.ext_retrigger = false;
            } else {
                if (gate_on && !osc.env_gate_was_on) osc.env_stage = 1;
                if (!gate_on && osc.env_gate_was_on) osc.env_stage = 4;
            }
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
            out[i] = sample * kEngine0OutGain;

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

        // Store full output buffer for audio-rate VOICE_FM cross-modulation
        std::memcpy(engine->voice_fm_buffer[idx], out, num_frames * sizeof(float));

        // Update peak follower for voice coupling (150ms half-life, matching JSyn PeakFollower)
        engine->voice_envelope[idx] = coupling_follower_block(engine->voice_envelope[idx], voice_peak, sr, num_frames);

    } else {
        // ═══ PLAITS ENGINES (1+): Render via OrpheusVoice (direct Engine::Render) ═══
        auto& voice = engine->voices_dsp[idx];

        // Gate and hold are independent signals (matching JSyn DspVoice):
        //   - gate goes to Plaits engine for trigger edge detection
        //   - hold is an additive VCA floor (envelope + hold), never touches the gate
        // This ensures PulsePad retriggering works during quad hold,
        // and engines behave identically to the JSyn path.
        int plaits_gate = actual_gate;

        // Engine change retrigger: a gate held across the change drops for one block so
        // OrpheusVoice sees a fresh 0→1 edge. With the detector already low the gate is
        // its own edge, and dropping it would eat a one-shot drum strike.
        if (vp.engine_changed.exchange(0, std::memory_order_relaxed) && voice.trigger_state_) {
            plaits_gate = 0;
        }

        // Plaits note: NO fm_mod_semitones — in JSyn, VOICE_FM only modulates
        // Plaits timbre (via plaitsTimbreModInput), not pitch. Frequency FM is
        // Engine 0 only, applied per-sample in the oscillator loop above.
        float note_raw = vp.tune.load(std::memory_order_relaxed) + vibrato_semitones;
        float note;
        // Plaits renders block-rate, so use last sample of CV buffer as block-rate pitch offset
        float pitch_cv_block = pitch_cv_buffer ? pitch_cv_buffer[num_frames - 1] : 0.0f;
        if (std::fabs(bend_hz) > 0.01f || std::fabs(coupling_hz) > 0.01f
            || std::fabs(pitch_cv_block) > 0.0001f) {
            float base_freq = 440.0f * std::pow(2.0f, (note_raw - 69.0f) / 12.0f);
            float bent_freq = base_freq + bend_hz + coupling_hz;
            // Flux pitch CV: multiplicative ratio, matches JSyn baseFreq * (1 + cv)
            if (std::fabs(pitch_cv_block) > 0.0001f) {
                bent_freq *= (1.0f + pitch_cv_block);
            }
            note = (bent_freq > 0.0f) ? 69.0f + 12.0f * std::log2f(bent_freq / 440.0f) : note_raw;
        } else {
            note = note_raw;
        }
        note = add_terms(note, terms, kPatchDstPitch);
        note = std::max(20.0f, std::min(127.0f, note));  // clamp to safe MIDI range
        // Patch cables. A parked DX HARMONICS cable arrives as amount 0, so DX patches hold.
        float harmonics = add_terms01(vp.harmonics.load(std::memory_order_relaxed), terms, kPatchDstHarmonics);
        float timbre = std::max(0.0f, std::min(1.0f,
            vp.timbre.load(std::memory_order_relaxed) + mods.timbre));
        float morph = add_terms01(vp.morph.load(std::memory_order_relaxed), terms, kPatchDstMorph);
        float accent = vp.accent.load(std::memory_order_relaxed);

        // Render: Braids range (100..199) → MacroOscillator wrapper.
        // Otherwise → OrpheusVoice (handles Plaits engine selection, outGain, soft_limit).
        // Pass the FM-modulated harmonics/morph locals so LFO/Tides/etc. modulation
        // reaches the Braids macro params (parameter_[0/1]).
        if (engine_index >= 100 && engine_index < 200) {
            unit_process_braids(engine->braids_voices[idx],
                                engine->braids_resampler[idx],
                                engine_index,
                                note, harmonics, timbre, morph,
                                sr, out, num_frames);
        } else if (engine_index >= kChaosEngineMin && engine_index <= kChaosEngineMax) {
            // Chaos voices write carrier+MORPH-blended chaos into `out` here,
            // then fall through to the shared ADSR/peak/voice_levels tail below
            // so they receive the same envelope handling as Plaits voices.
            // Pass FM/LFO-modulated harmonics/morph so LFO→HARMONICS/MORPH
            // routes through to the chaos kernel (matching Plaits/Braids paths).
            unit_process_chaos(u, engine, num_frames, sr, harmonics, morph);
        } else {
            voice.Render(engine_index, plaits_gate, note, harmonics, timbre, morph, accent,
                         out, num_frames);
        }

        // ADSR envelope — wraps main voices (matching JSyn's DspVoice VCA).
        // Drum voices (idx >= kNumMainVoices) bypass ADSR: their Plaits engines
        // (BassDrum, SnareDrum, HiHat) have built-in percussive envelopes,
        // and the JSyn path renders drums without DspVoice ADSR wrapping.
        float voice_peak = 0.0f;

        if (idx < kNumMainVoices) {
            bool score = vp.score_driven.load(std::memory_order_relaxed);
            // String and Modal engines have built-in decay (physical models) — skip
            // external ADSR to avoid double-enveloping. Score voices always get it.
            bool already_enveloped = !score && (engine_index == 19 || engine_index == 20);

            if (already_enveloped) {
                for (int i = 0; i < num_frames; i++) {
                    float abs_s = std::fabs(out[i]);
                    if (abs_s > voice_peak) voice_peak = abs_s;
                }
            } else {
            // Main voices: full ADSR + hold VCA. Score voices use the orchestral
            // mapping (short attack/release) instead of compute_adsr_from_speed's
            // second-long drone range.
            float attack_rate, decay_coeff, sustain_level, release_coeff;
            if (score) {
                compute_score_adsr(env_speed, sr, attack_rate, decay_coeff,
                                    sustain_level, release_coeff);
            } else {
                compute_adsr_from_speed(env_speed, sr, attack_rate, decay_coeff,
                                         sustain_level, release_coeff);
            }
            // Trigger mode: percussive envelope (sustain = 0)
            {
                int q = idx / 4;
                if (q < 3 && engine->quad_trigger_mode[q].load(std::memory_order_relaxed))
                    sustain_level = 0.0f;
            }
            // Score sustain taper: drift toward a floor instead of holding flat.
            float sustain_floor = 0.0f, taper_coeff = 0.0f;
            if (score) {
                sustain_floor = sustain_level * kScoreSustainFloorRatio;
                taper_coeff = std::exp(-1.0f / (kScoreSustainTauSeconds * sr));
            }

            for (int i = 0; i < num_frames; i++) {
                // ADSR state machine (with external re-trigger support)
                bool gate_on = actual_gate != 0;
                if (vp.ext_retrigger) {
                    osc.env_stage = 1;
                    vp.ext_retrigger = false;
                } else {
                    if (gate_on && !osc.env_gate_was_on) osc.env_stage = 1;
                    if (!gate_on && osc.env_gate_was_on) osc.env_stage = 4;
                }
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
                        if (score) {
                            osc.env_level = sustain_floor +
                                            (osc.env_level - sustain_floor) * taper_coeff;
                        } else {
                            osc.env_level = sustain_level;
                        }
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
            } // !already_enveloped
        } else {
            // Drum voices: external percussive envelope matching Kotlin's
            // setPercussiveMode(!engine.alreadyEnveloped).
            // Engines with built-in envelopes (indices 19-23: String, Modal, BD, SD, HH;
            // and indices 2-4: Six-Op FM hardware slots) already decay naturally —
            // skip external envelope for those.
            // Non-percussive engines (FM, Swarm, etc.) need this to avoid
            // sustaining indefinitely.
            bool already_enveloped = (engine_index >= 19 && engine_index <= 23) || (engine_index >= 2 && engine_index <= 4);
            int drum_slot = idx - kDrumVoiceStart;
            float& env_amp = engine->drum_env_amplitude[drum_slot];

            if (!already_enveloped) {
                // Reset envelope on trigger (gate is auto-cleared after each render)
                if (actual_gate) {
                    env_amp = 1.0f;
                }

                // Decay coefficient from morph (decay knob): 30ms..2000ms → -60dB
                // -ln(0.001) = 6.908 → envelope reaches -60dB after decay_samples
                float decay_ms = 30.0f + morph * 1970.0f;
                float decay_samples = decay_ms * sr / 1000.0f;
                float decay_coeff = std::exp(-6.908f / decay_samples);

                for (int i = 0; i < num_frames; i++) {
                    out[i] *= env_amp;
                    env_amp *= decay_coeff;
                    float abs_s = std::fabs(out[i]);
                    if (abs_s > voice_peak) voice_peak = abs_s;
                }
            } else {
                // Built-in envelope engines: just track peak, no external decay
                for (int i = 0; i < num_frames; i++) {
                    float abs_s = std::fabs(out[i]);
                    if (abs_s > voice_peak) voice_peak = abs_s;
                }
            }
        }

        engine->voice_levels[idx].store(voice_peak, std::memory_order_relaxed);

        // Apply bender voice mix volume
        if (bend_vol_mult < 0.999f) {
            for (int i = 0; i < num_frames; i++) {
                out[i] *= bend_vol_mult;
            }
        }

        // Store full output buffer for audio-rate VOICE_FM cross-modulation
        std::memcpy(engine->voice_fm_buffer[idx], out, num_frames * sizeof(float));

        // Update peak follower for voice coupling (150ms half-life, matching JSyn PeakFollower)
        engine->voice_envelope[idx] = coupling_follower_block(engine->voice_envelope[idx], voice_peak, sr, num_frames);

        // Clear gate for drum voices (one-shot triggers)
        if (idx >= kDrumVoiceStart && actual_gate) {
            vp.gate.store(0, std::memory_order_relaxed);
        }

        // Apply drum_mix gain to output buffer (matching Kotlin DrumPlugin: baseGain=1.6 * mix).
        // This scales the graph output so downstream units (pan, resonator, master) see the gain.
        if (idx >= kDrumVoiceStart) {
            // Each drum unit owns its smoother; a shared one was stepped by all three.
            float dm_target = 3.2f * engine->drum_mix.load(std::memory_order_relaxed);
            float dm_coeff = smooth_coeff(sr);
            float& dm = engine->smooth_drum_mix[idx - kDrumVoiceStart];
            for (int i = 0; i < num_frames; i++) {
                dm += dm_coeff * (dm_target - dm);
                out[i] *= dm;
            }
        }
    }

    // Populate warps source buffers BEFORE dry attenuation (Warps needs full signal)
    if (idx < 8) {
        constexpr float kSynthNorm = 1.0f / 8.0f;
        for (int i = 0; i < num_frames; i++)
            engine->warps_source_buffers[0][i] += out[i] * kSynthNorm;
    } else if (idx >= 8 && idx < kDrumVoiceStart) {
        constexpr float kReplNorm = 1.0f / 4.0f;
        for (int i = 0; i < num_frames; i++)
            engine->warps_source_buffers[2][i] += out[i] * kReplNorm;
    } else if (idx >= kDrumVoiceStart) {
        // DRUMS source (1): accumulate drum voices directly
        // (Clouds may not receive drums depending on graph routing)
        constexpr float kDrumNorm = 1.0f / 3.0f;  // 3 drum voices
        for (int i = 0; i < num_frames; i++)
            engine->warps_source_buffers[1][i] += out[i] * kDrumNorm;
    }

    // Attenuate dry path when this voice is the Warps carrier source.
    // Warps wet output replaces the carrier, so we fade out the dry carrier
    // proportional to mix: at mix=1 the carrier is fully replaced by Warps.
    float dry_scale = warps_dry_scale(engine, idx);
    if (dry_scale < 0.999f) {
        for (int i = 0; i < num_frames; i++)
            out[i] *= dry_scale;
    }
}

void unit_process_duo_voice(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr) {
    int duo = u->state.module.index;
    int idxA = duo * 2;
    int idxB = idxA + 1;

    float* outA = u->output_buffers[OPORT_OUT];
    float* outB = u->output_buffers[OPORT_AUX];

    if (duo < 0 || duo >= OrpheusEngine::kNumDuos ||
        idxA >= kNumMainVoices || idxB >= kNumMainVoices) {
        std::memset(outA, 0, num_frames * sizeof(float));
        std::memset(outB, 0, num_frames * sizeof(float));
        return;
    }

    auto& vpA = engine->voice_params[idxA];
    auto& vpB = engine->voice_params[idxB];

    // ── External gate routing must run before active check ──
    // External gates set ever_triggered, which is part of the active check below.
    int quad = duo / 2;
    if (quad < 3) {
        int trig_src = engine->quad_trigger_source[quad].load(std::memory_order_relaxed);
        float* ext_gate_buffer = resolve_marbles_t(engine, trig_src);
        if (ext_gate_buffer) {
            process_ext_gate(vpA, ext_gate_buffer, num_frames);
            process_ext_gate(vpB, ext_gate_buffer, num_frames);
        } else {
            release_ext_gate(vpA);
            release_ext_gate(vpB);
        }
    }

    bool activeA = vpA.active.load(std::memory_order_relaxed) &&
                   vpA.ever_triggered.load(std::memory_order_relaxed);
    bool activeB = vpB.active.load(std::memory_order_relaxed) &&
                   vpB.ever_triggered.load(std::memory_order_relaxed);

    if (!activeA && !activeB) {
        std::memset(outA, 0, num_frames * sizeof(float));
        std::memset(outB, 0, num_frames * sizeof(float));
        engine->voice_levels[idxA].store(0.0f, std::memory_order_relaxed);
        engine->voice_levels[idxB].store(0.0f, std::memory_order_relaxed);
        return;
    }

    // ── Plaits rendering: delegate to unit_process_plaits per voice ──
    // A VOICE_FM cable's block-rate value is the partner's RMS: A reads B's previous
    // block, B reads A's block just rendered.
    int engineA = vpA.engine_index.load(std::memory_order_relaxed);
    int engineB = vpB.engine_index.load(std::memory_order_relaxed);
    if (engineA >= 0 || engineB >= 0) {
        // Pre-smooth the depth once per block, then save+restore around
        // the two unit_process_plaits calls to prevent double-smoothing.
        float sc = block_smooth_coeff(sr, num_frames, kDuoDepthSmoothSeconds);
        float md_target = engine->mod_depth[duo].load(std::memory_order_relaxed);
        engine->smooth_mod_depth[duo] += sc * (md_target - engine->smooth_mod_depth[duo]);

        float saved_mod = engine->smooth_mod_depth[duo];

        auto& tmp = engine->plaits_fallback_unit;

        // Engine 0 renders first in a mixed duo, so the Plaits voice reads its current block.
        int first = idxA, second = idxB;
        float *first_out = outA, *second_out = outB;
        if (engineA >= 0 && engineB < 0) {
            first = idxB; first_out = outB;
            second = idxA; second_out = outA;
        }

        std::memset(&tmp, 0, sizeof(tmp));
        tmp.type = UNIT_PLAITS;
        tmp.enabled = true;
        tmp.state.module.index = first;
        unit_process_plaits(&tmp, engine, num_frames, sr);
        std::memcpy(first_out, tmp.output_buffers[OPORT_OUT], num_frames * sizeof(float));

        engine->smooth_mod_depth[duo] = saved_mod;

        tmp.state.module.index = second;
        std::memset(tmp.output_buffers, 0, sizeof(tmp.output_buffers));
        unit_process_plaits(&tmp, engine, num_frames, sr);
        std::memcpy(second_out, tmp.output_buffers[OPORT_OUT], num_frames * sizeof(float));

        engine->smooth_mod_depth[duo] = saved_mod;

        return;
    }

    // ── Block-rate setup ──────────────────────────────────────────
    float sc = block_smooth_coeff(sr, num_frames, kDuoDepthSmoothSeconds);

    // Vibrato: read from dedicated sine oscillator buffer (computed in HyperLFO unit)
    // vibrato_output_buffer already contains sine(rate) * depth * 20 Hz

    // Coupling depth (shared, smoothed in orpheus_graph_process)
    float coupling = engine->smooth_coupling_depth;

    // Master patch depth (per-duo, smoothed) and each voice's cables
    float md_target = engine->mod_depth[duo].load(std::memory_order_relaxed);
    engine->smooth_mod_depth[duo] += sc * (md_target - engine->smooth_mod_depth[duo]);
    float depth = engine->smooth_mod_depth[duo];
    PatchVoiceTerms termsA, termsB;
    patch_voice_terms(*engine, duo, 0, idxA, num_frames, depth, &termsA);
    patch_voice_terms(*engine, duo, 1, idxB, num_frames, depth, &termsB);
    PatchVoiceMods modsA = patch_term_sums(termsA);
    PatchVoiceMods modsB = patch_term_sums(termsB);
    PatchFmTaps tapsA, tapsB;
    patch_fm_taps(*engine, duo, 0, &tapsA);
    patch_fm_taps(*engine, duo, 1, &tapsB);
    PatchFmPlan planA, planB;
    patch_fm_plan(*engine, tapsA, depth, &planA);
    patch_fm_plan(*engine, tapsB, depth, &planB);
    bool has_fmA = planA.count > 0 || planA.has_block;
    bool has_fmB = planB.count > 0 || planB.has_block;

    // VOICE_FM partners: A reads B and B reads A, or each its cross-quad source
    int fm_source_for_A = patch_fm_partner(*engine, idxA);
    int fm_source_for_B = patch_fm_partner(*engine, idxB);

    // ── Per-voice parameters ──────────────────────────────────────
    // Voice A
    int gateA = vpA.gate.load(std::memory_order_relaxed);
    float env_speedA = env_speed_with(vpA.decay.load(std::memory_order_relaxed), termsA, modsA);
    float raw_holdA = engine->voice_hold_level[idxA].load(std::memory_order_relaxed);
    float scaled_holdA = compute_scaled_hold(raw_holdA, env_speedA);
    float feedbackA = vpA.harmonics.load(std::memory_order_relaxed);
    float morph_valA = add_terms01(vpA.morph.load(std::memory_order_relaxed), termsA, kPatchDstMorph);
    feedbackA = add_terms01(feedbackA, termsA, kPatchDstHarmonics);
    // Voice A: positive detune (+morph × 25 cents, matching JSyn ±split)
    float detune_semiA = morph_valA * (25.0f / 100.0f);
    float base_noteA = vpA.tune.load(std::memory_order_relaxed) + detune_semiA;
    base_noteA = add_terms(base_noteA, termsA, kPatchDstPitch);
    base_noteA = std::max(20.0f, std::min(127.0f, base_noteA));
    float sharpnessA = vpA.timbre.load(std::memory_order_relaxed);
    float bend_hzA = engine->voice_bend_cv[idxA];
    float bend_volA = engine->voice_mix_cv[idxA];

    float attack_rateA, decay_coeffA, sustain_levelA, release_coeffA;
    compute_adsr_from_speed(env_speedA, sr, attack_rateA, decay_coeffA,
                             sustain_levelA, release_coeffA);

    // Voice B
    int gateB = vpB.gate.load(std::memory_order_relaxed);
    float env_speedB = env_speed_with(vpB.decay.load(std::memory_order_relaxed), termsB, modsB);
    float raw_holdB = engine->voice_hold_level[idxB].load(std::memory_order_relaxed);
    float scaled_holdB = compute_scaled_hold(raw_holdB, env_speedB);
    float feedbackB = vpB.harmonics.load(std::memory_order_relaxed);
    float morph_valB = add_terms01(vpB.morph.load(std::memory_order_relaxed), termsB, kPatchDstMorph);
    feedbackB = add_terms01(feedbackB, termsB, kPatchDstHarmonics);
    // Voice B: negative detune (-morph × 25 cents, matching JSyn ±split)
    float detune_semiB = -morph_valB * (25.0f / 100.0f);
    float base_noteB = vpB.tune.load(std::memory_order_relaxed) + detune_semiB;
    base_noteB = add_terms(base_noteB, termsB, kPatchDstPitch);
    base_noteB = std::max(20.0f, std::min(127.0f, base_noteB));
    float sharpnessB = vpB.timbre.load(std::memory_order_relaxed);
    float bend_hzB = engine->voice_bend_cv[idxB];
    float bend_volB = engine->voice_mix_cv[idxB];

    float attack_rateB, decay_coeffB, sustain_levelB, release_coeffB;
    compute_adsr_from_speed(env_speedB, sr, attack_rateB, decay_coeffB,
                             sustain_levelB, release_coeffB);

    // Trigger mode: percussive envelope (sustain = 0)
    if (quad < 3 && engine->quad_trigger_mode[quad].load(std::memory_order_relaxed)) {
        sustain_levelA = 0.0f;
        sustain_levelB = 0.0f;
    }

    // ── Flux pitch CV modulation (quad_pitch_source → X1/X2/X3) ──
    // Per-sample buffer pointer for smooth pitch modulation (avoids zipper artifacts).
    float* pitch_cv_buffer = nullptr;
    if (quad < 3) {
        int pitch_src = engine->quad_pitch_source[quad].load(std::memory_order_relaxed);
        pitch_cv_buffer = resolve_marbles_x(engine, pitch_src);
    }

    auto& oscA = engine->voice_osc_state[idxA];
    auto& oscB = engine->voice_osc_state[idxB];

    // Hold smoothing coefficient (~20ms)
    float hold_coeff = 50.0f / sr;

    // ── Cross-mod init: load last sample from previous block ──────
    // For Voice A reading Voice B: use last sample of B's previous fm_buffer
    // For Voice B reading Voice A: use last sample of A's current output (computed this sample)
    float prev_outB = (num_frames > 0) ?
        engine->voice_fm_buffer[idxB][num_frames - 1] : 0.0f;

    // ── Idle voice checks ─────────────────────────────────────────
    bool idleA = !activeA ||
        (oscA.env_stage == 0 && scaled_holdA < 0.001f && gateA == 0);
    bool idleB = !activeB ||
        (oscB.env_stage == 0 && scaled_holdB < 0.001f && gateB == 0);

    if (idleA && idleB) {
        std::memset(outA, 0, num_frames * sizeof(float));
        std::memset(outB, 0, num_frames * sizeof(float));
        engine->voice_levels[idxA].store(0.0f, std::memory_order_relaxed);
        engine->voice_levels[idxB].store(0.0f, std::memory_order_relaxed);
        return;
    }

    // ── Per-sample peak follower coefficient for coupling ──────────
    // JSyn PeakFollower uses 150ms half-life. Per-sample:
    //   decay = e^(-ln(2) / (sr * 0.15)) ≈ 0.99990 at 48kHz
    // Uses fast attack / slow decay (matching unit_process_plaits).
    float env_decay = 1.0f - 0.693f / (sr * 0.15f);  // ~150ms half-life
    float envA = engine->voice_envelope[idxA];
    float envB = engine->voice_envelope[idxB];

    // ── Per-sample loop ───────────────────────────────────────────
    float peakA = 0.0f, peakB = 0.0f;

    for (int i = 0; i < num_frames; i++) {
        float vib_hz = engine->vibrato_output_buffer[i];  // Hz offset from dedicated vibrato osc

        // ── Voice A ──────────────────────────────────────────
        float sampleA = 0.0f;
        if (!idleA) {
            float freqA = 440.0f * std::pow(2.0f, (base_noteA - 69.0f) / 12.0f);
            freqA += vib_hz;   // vibrato in Hz (matches JSyn: VibratoPlugin sine * depth * 20)
            freqA += bend_hzA;
            // Flux pitch CV: per-sample multiplicative ratio, matches JSyn baseFreq * (1 + cv)
            if (pitch_cv_buffer) {
                freqA *= (1.0f + pitch_cv_buffer[i]);
            }

            // Coupling: B's per-sample envelope → A's pitch
            if (coupling > 0.001f) {
                freqA += envB * coupling * 30.0f;
            }

            // FM cables. VOICE_FM: B's previous sample (1-sample delay), or the cross-quad buffer
            if (has_fmA) {
                float partner = (fm_source_for_A == idxB) ? prev_outB
                    : (fm_source_for_A >= 0) ? engine->voice_fm_buffer[fm_source_for_A][i] : 0.0f;
                freqA = apply_fm(freqA, planA, i, partner);
            }

            float audioA = oscA.core.Next(freqA, sr, sharpnessA, feedbackA);

            // ADSR envelope (with external re-trigger support)
            bool gate_onA = gateA != 0;
            if (vpA.ext_retrigger) {
                // External gate rising edge: force re-attack even if gate was already on
                oscA.env_stage = 1;
                vpA.ext_retrigger = false;
            } else {
                if (gate_onA && !oscA.env_gate_was_on) oscA.env_stage = 1;
                if (!gate_onA && oscA.env_gate_was_on) oscA.env_stage = 4;
            }
            oscA.env_gate_was_on = gate_onA;

            switch (oscA.env_stage) {
                case 1:
                    oscA.env_level += attack_rateA;
                    if (oscA.env_level >= 1.0f) { oscA.env_level = 1.0f; oscA.env_stage = 2; }
                    break;
                case 2:
                    oscA.env_level = sustain_levelA +
                                     (oscA.env_level - sustain_levelA) * decay_coeffA;
                    if (oscA.env_level - sustain_levelA < 0.0001f) {
                        oscA.env_level = sustain_levelA; oscA.env_stage = 3;
                    }
                    break;
                case 3:
                    oscA.env_level = sustain_levelA;
                    break;
                case 4:
                    oscA.env_level *= release_coeffA;
                    if (oscA.env_level < 0.0001f) { oscA.env_level = 0.0f; oscA.env_stage = 0; }
                    break;
                default:
                    oscA.env_level = 0.0f;
                    break;
            }

            // Hold ramp
            oscA.hold_smoothed += hold_coeff * (scaled_holdA - oscA.hold_smoothed);

            // VCA
            float vcaA = oscA.env_level + oscA.hold_smoothed;
            sampleA = audioA * vcaA * kEngine0OutGain;

            float absA = std::fabs(sampleA);
            if (absA > peakA) peakA = absA;

            // Update A's envelope per-sample (150ms half-life peak follower)
            // Fast attack / slow decay — matches unit_process_plaits peak follower
            envA = (absA > envA) ? (1.0f - env_decay) * absA + env_decay * envA : envA * env_decay;
        }
        outA[i] = sampleA;

        // ── Voice B ──────────────────────────────────────────
        float sampleB = 0.0f;
        if (!idleB) {
            float freqB = 440.0f * std::pow(2.0f, (base_noteB - 69.0f) / 12.0f);
            freqB += vib_hz;   // vibrato in Hz (same oscillator for both voices)
            freqB += bend_hzB;
            // Flux pitch CV: per-sample multiplicative ratio, matches JSyn baseFreq * (1 + cv)
            if (pitch_cv_buffer) {
                freqB *= (1.0f + pitch_cv_buffer[i]);
            }

            // Coupling: A's per-sample envelope → B's pitch
            if (coupling > 0.001f) {
                freqB += envA * coupling * 30.0f;
            }

            // FM cables. VOICE_FM: A's current sample (zero delay), or the cross-quad buffer
            if (has_fmB) {
                float partner = (fm_source_for_B == idxA) ? sampleA
                    : (fm_source_for_B >= 0) ? engine->voice_fm_buffer[fm_source_for_B][i] : 0.0f;
                freqB = apply_fm(freqB, planB, i, partner);
            }

            float audioB = oscB.core.Next(freqB, sr, sharpnessB, feedbackB);

            // ADSR envelope (with external re-trigger support)
            bool gate_onB = gateB != 0;
            if (vpB.ext_retrigger) {
                oscB.env_stage = 1;
                vpB.ext_retrigger = false;
            } else {
                if (gate_onB && !oscB.env_gate_was_on) oscB.env_stage = 1;
                if (!gate_onB && oscB.env_gate_was_on) oscB.env_stage = 4;
            }
            oscB.env_gate_was_on = gate_onB;

            switch (oscB.env_stage) {
                case 1:
                    oscB.env_level += attack_rateB;
                    if (oscB.env_level >= 1.0f) { oscB.env_level = 1.0f; oscB.env_stage = 2; }
                    break;
                case 2:
                    oscB.env_level = sustain_levelB +
                                     (oscB.env_level - sustain_levelB) * decay_coeffB;
                    if (oscB.env_level - sustain_levelB < 0.0001f) {
                        oscB.env_level = sustain_levelB; oscB.env_stage = 3;
                    }
                    break;
                case 3:
                    oscB.env_level = sustain_levelB;
                    break;
                case 4:
                    oscB.env_level *= release_coeffB;
                    if (oscB.env_level < 0.0001f) { oscB.env_level = 0.0f; oscB.env_stage = 0; }
                    break;
                default:
                    oscB.env_level = 0.0f;
                    break;
            }

            // Hold ramp
            oscB.hold_smoothed += hold_coeff * (scaled_holdB - oscB.hold_smoothed);

            // VCA
            float vcaB = oscB.env_level + oscB.hold_smoothed;
            sampleB = audioB * vcaB * kEngine0OutGain;

            float absB = std::fabs(sampleB);
            if (absB > peakB) peakB = absB;

            // Update B's envelope per-sample (150ms half-life peak follower)
            // Fast attack / slow decay — matches unit_process_plaits peak follower
            envB = (absB > envB) ? (1.0f - env_decay) * absB + env_decay * envB : envB * env_decay;
        }
        outB[i] = sampleB;

        // Update prev_outB for next iteration (1-sample delay for A reading B)
        prev_outB = sampleB;
    }

    // ── Post-processing ───────────────────────────────────────────

    // Store voice levels
    engine->voice_levels[idxA].store(peakA, std::memory_order_relaxed);
    engine->voice_levels[idxB].store(peakB, std::memory_order_relaxed);

    // Apply bender voice mix volume
    if (bend_volA < 0.999f) {
        for (int i = 0; i < num_frames; i++) outA[i] *= bend_volA;
    }
    if (bend_volB < 0.999f) {
        for (int i = 0; i < num_frames; i++) outB[i] *= bend_volB;
    }

    // Store full output buffers for audio-rate VOICE_FM cross-modulation
    // (used by other units that may read these voices' fm_buffers)
    std::memcpy(engine->voice_fm_buffer[idxA], outA, num_frames * sizeof(float));
    std::memcpy(engine->voice_fm_buffer[idxB], outB, num_frames * sizeof(float));

    // Store per-sample envelope (already tracked in the loop with 150ms half-life)
    engine->voice_envelope[idxA] = envA;
    engine->voice_envelope[idxB] = envB;

    // Accumulate into Warps source buffers (matching unit_process_plaits)
    // Voices 0-7 → SYNTH (source 0), voices 8-11 → REPL (source 2)
    auto accumulate_warps = [&](int idx, float* buf) {
        if (idx < 8) {
            constexpr float kSynthNorm = 1.0f / 8.0f;
            for (int i = 0; i < num_frames; i++)
                engine->warps_source_buffers[0][i] += buf[i] * kSynthNorm;
        } else if (idx < kDrumVoiceStart) {
            constexpr float kReplNorm = 1.0f / 4.0f;
            for (int i = 0; i < num_frames; i++)
                engine->warps_source_buffers[2][i] += buf[i] * kReplNorm;
        }
    };
    accumulate_warps(idxA, outA);
    accumulate_warps(idxB, outB);

    // Attenuate dry path when voices are the Warps carrier source
    float dry_A = warps_dry_scale(engine, idxA);
    float dry_B = warps_dry_scale(engine, idxB);
    if (dry_A < 0.999f) {
        for (int i = 0; i < num_frames; i++) outA[i] *= dry_A;
    }
    if (dry_B < 0.999f) {
        for (int i = 0; i < num_frames; i++) outB[i] *= dry_B;
    }
}
