#pragma once
// Orpheus Horn — dual-rotor Leslie effect DSP
//
// Ported from MI Ensemble (plaits/dsp/fx/ensemble.h, Emilie Gillet).
// Removes all stmlib/FxEngine/SineRaw/plaits-resource dependencies.
// Extends with:
//   - 1st-order one-pole crossover (treble/bass split at ~800 Hz)
//   - Independent inertia per rotor (ramp-up ~1s, ramp-down ~3s)
//   - Phase export for Compose animation

#include <cmath>
#include <cstring>

// ─── Crossover filter state (1st-order one-pole LP/HP pair) ───────────────────
// A single one-pole LP + complementary HP (input - LP) gives a -6dB/oct slope.
// Sufficient for a Leslie crossover and avoids phase cancellation artifacts
// in the recombined sum.

struct OrpheusHornCrossover {
    float lp_state = 0.0f;  // one-pole lowpass state

    // Returns (lp_out, hp_out) via output parameters.
    // coeff = 1 - exp(-2*pi*fc/sr)   computed once per block in OrpheusHorn.
    inline void process(float in, float coeff, float& lp_out, float& hp_out) {
        lp_state += coeff * (in - lp_state);
        lp_out = lp_state;
        hp_out = in - lp_state;
    }

    void reset() { lp_state = 0.0f; }
};

// ─── OrpheusHorn ──────────────────────────────────────────────────────────────
// Owns all delay-line and LFO state.  Delay buffers live in OrpheusEngine to
// keep this header lightweight; call Init() with pointers to those arrays.

struct OrpheusHorn {
    static constexpr int kBufSize  = 2048;   // must match OrpheusEngine::kHornBufferSize
    static constexpr int kBufMask  = kBufSize - 1;
    static constexpr float k2Pi    = 6.28318530718f;
    static constexpr float k1Over3 = 1.0f / 3.0f;
    static constexpr float k2Over3 = 2.0f / 3.0f;

    // External buffer pointers (owned by OrpheusEngine)
    float* delay_l = nullptr;
    float* delay_r = nullptr;

    // Write position shared between L/R delay lines
    int write_pos = 0;

    // Rotor phases (0..1).  horn = treble rotor, woofer = bass rotor.
    float horn_phase  = 0.0f;
    float woofer_phase = 0.0f;

    // Current (inertia-smoothed) speeds, in Hz.
    float horn_speed_hz   = 0.0f;
    float woofer_speed_hz = 0.0f;

    // Parameter cache (written each block from atomics, processed sample-by-sample)
    float amount = 0.5f;   // wet blend  (0..1)
    float depth  = 0.5f;   // delay modulation depth (0..1)

    // Crossover filter state (L and R, separate for treble and bass paths)
    OrpheusHornCrossover xover_l;
    OrpheusHornCrossover xover_r;

    // Smoothed mix (for artifact-free bypass transitions)
    float smooth_mix = 0.0f;

    void Init(float* buf_l, float* buf_r) {
        delay_l  = buf_l;
        delay_r  = buf_r;
        write_pos = 0;
        horn_phase    = 0.0f;
        woofer_phase  = 0.0f;
        horn_speed_hz   = 0.0f;
        woofer_speed_hz = 0.0f;
        smooth_mix = 0.0f;
        xover_l.reset();
        xover_r.reset();
        std::memset(buf_l, 0, kBufSize * sizeof(float));
        std::memset(buf_r, 0, kBufSize * sizeof(float));
    }

    // ── Linear-interpolated delay-line tap ──
    // offset is in samples (fractional OK). write_pos is the current head.
    inline float read_interp(const float* buf, float offset) const {
        // offset > 0 means "offset samples into the past"
        float rd = static_cast<float>(write_pos) - offset;
        // wrap into [0, kBufSize)
        while (rd < 0.0f) rd += static_cast<float>(kBufSize);
        int i0 = static_cast<int>(rd) & kBufMask;
        int i1 = (i0 + 1) & kBufMask;
        float frac = rd - static_cast<float>(static_cast<int>(rd));
        return buf[i0] + (buf[i1] - buf[i0]) * frac;
    }

    // ── Process one block ────────────────────────────────────────────────────
    // in_l / in_r   : input audio (num_frames samples)
    // out_l / out_r : output audio (num_frames samples)
    // mix_target    : dry/wet 0..1 (smoothed internally)
    // horn_hz_target   : target treble-rotor speed Hz
    // woofer_hz_target : target bass-rotor speed Hz
    // amount_param  : modulation amount 0..1 (= MI amount_)
    // depth_param   : delay modulation depth 0..1 (= MI depth_)
    // crossover_coeff  : one-pole LP coefficient for crossover
    // sr            : sample rate
    // out_horn_phase   : exported horn phase 0..1 (for animation, written once)
    // out_woofer_phase : exported woofer phase 0..1
    // smooth_coeff_val : parameter smoothing coefficient
    void Process(
        const float* in_l, const float* in_r,
        float* out_l, float* out_r,
        int num_frames,
        float mix_target,
        float horn_hz_target,
        float woofer_hz_target,
        float amount_param,
        float depth_param,
        float crossover_coeff,
        float sample_rate,
        float& out_horn_phase,
        float& out_woofer_phase,
        float smooth_coeff_val)
    {
        // Inertia time constants (seconds)
        // ramp-up ~1s, ramp-down ~3s
        const float inv_sr = 1.0f / sample_rate;
        const float ramp_up_coeff   = 1.0f - std::exp(-inv_sr / 1.0f);
        const float ramp_down_coeff = 1.0f - std::exp(-inv_sr / 3.0f);

        // MI Ensemble delay parameters:
        //   center = 192 samples, large LFO amplitude = 160, small = 16
        //   scaled by depth (0..1)
        // Above 0.7, apply exponential curve for dramatic pitch wobble:
        //   0.0→0.0, 0.7→0.7, 1.0→1.8 (overshoots for exaggerated Doppler)
        const float center = 192.0f;
        float d = depth_param;
        if (d > 0.7f) {
            float excess = (d - 0.7f) / 0.3f;            // 0..1 in the 0.7..1.0 range
            d = 0.7f + excess * excess * 1.1f;            // quadratic ramp: 0.7 → 1.8
        }
        const float a = d * 160.0f;              // slow LFO: 0..288 samples
        const float b = d * 16.0f;               // fast LFO: 0..29 samples

        // amount_ controls wet/dry exactly as MI does:
        //   wet = amount_param, dry = 1 - amount_param * 0.5
        const float wet_scale = amount_param;
        const float dry_scale = 1.0f - amount_param * 0.5f;

        for (int i = 0; i < num_frames; ++i) {
            // ── Inertia: slew speeds toward targets ──
            float horn_coeff = (horn_hz_target > horn_speed_hz)
                               ? ramp_up_coeff : ramp_down_coeff;
            float woof_coeff = (woofer_hz_target > woofer_speed_hz)
                               ? ramp_up_coeff : ramp_down_coeff;
            horn_speed_hz   += horn_coeff   * (horn_hz_target   - horn_speed_hz);
            woofer_speed_hz += woof_coeff   * (woofer_hz_target - woofer_speed_hz);

            // ── Advance phases ──
            horn_phase   += horn_speed_hz   * inv_sr;
            woofer_phase += woofer_speed_hz * inv_sr;
            if (horn_phase   >= 1.0f) horn_phase   -= 1.0f;
            if (woofer_phase >= 1.0f) woofer_phase -= 1.0f;

            // ── Smooth mix ──
            smooth_mix += smooth_coeff_val * (mix_target - smooth_mix);

            // ── Crossover split ──
            float treble_l, bass_l, treble_r, bass_r;
            xover_l.process(in_l[i], crossover_coeff, bass_l, treble_l);
            xover_r.process(in_r[i], crossover_coeff, bass_r, treble_r);

            // ── Horn LFO (treble rotor) — 3 phases at 120° offsets ──
            float hp = horn_phase * k2Pi;
            float slow_0   = std::sin(hp);
            float slow_120 = std::sin(hp + k2Pi * k1Over3);
            float slow_240 = std::sin(hp + k2Pi * k2Over3);

            // ── Woofer LFO (bass rotor) ──
            float wp_angle = woofer_phase * k2Pi;
            float fast_0   = std::sin(wp_angle);

            // ── Treble path: chorus via delay line (Doppler from horn) ──
            // Only treble goes into the delay — the horn spins fast enough
            // for audible pitch modulation; the woofer rotor is too large.
            float mod_1 = slow_0   * a;
            float mod_2 = slow_120 * a;
            float mod_3 = slow_240 * a;

            delay_l[write_pos] = treble_l;
            delay_r[write_pos] = treble_r;

            // Three-tap chorus read (MI Ensemble pattern)
            // Left output: L-tap1 + L-tap2 + R-tap3
            float wet_treble_l =
                read_interp(delay_l, center + mod_1) * 0.33f +
                read_interp(delay_l, center + mod_2) * 0.33f +
                read_interp(delay_r, center + mod_3) * 0.33f;

            // Right output: R-tap1 + R-tap2 + L-tap3
            float wet_treble_r =
                read_interp(delay_r, center + mod_1) * 0.33f +
                read_interp(delay_r, center + mod_2) * 0.33f +
                read_interp(delay_l, center + mod_3) * 0.33f;

            // ── Leslie stereo AM ──
            // Real Leslie: each rotor acts as a directional sound source,
            // creating opposite-phase amplitude modulation between L/R mics.
            // Horn AM on treble, woofer AM on bass.
            // AM depth: above 0.7 ramp up aggressively toward real Leslie levels
            // horn: 0.15..0.85 (at depth=1, volume swings 15–100%)
            // woof: 0.10..0.60 (bass rotor is less directional)
            float am_d = depth_param;
            if (am_d > 0.7f) {
                float excess = (am_d - 0.7f) / 0.3f;
                am_d = 0.7f + excess * excess * 0.3f + excess * 0.3f;  // faster ramp
            }
            float horn_am_depth = 0.15f + 0.70f * am_d;  // 0.15..0.85
            float woof_am_depth = 0.10f + 0.50f * am_d;  // 0.10..0.60

            // Horn AM applied to treble chorus output
            float horn_am_l = 1.0f - horn_am_depth + horn_am_depth * (0.5f + 0.5f * slow_0);
            float horn_am_r = 1.0f - horn_am_depth + horn_am_depth * (0.5f - 0.5f * slow_0);
            wet_treble_l *= horn_am_l;
            wet_treble_r *= horn_am_r;

            // ── Bass path: woofer AM only (no chorus/Doppler) ──
            float woof_am_l = 1.0f - woof_am_depth + woof_am_depth * (0.5f + 0.5f * fast_0);
            float woof_am_r = 1.0f - woof_am_depth + woof_am_depth * (0.5f - 0.5f * fast_0);
            float wet_bass_l = bass_l * woof_am_l;
            float wet_bass_r = bass_r * woof_am_r;

            // ── Recombine treble + bass → MI amount blend ──
            // wet = treble chorus + bass AM (full effect signal)
            // amount controls wet/dry balance: wet*amount + dry*(1-amount*0.5)
            float wet_l = (wet_treble_l + wet_bass_l) * wet_scale;
            float wet_r = (wet_treble_r + wet_bass_r) * wet_scale;

            // ── Dry/wet crossfade ──
            // mix=0 → pure dry (passthrough), mix=1 → full effect
            float effect_l = wet_l + in_l[i] * dry_scale;
            float effect_r = wet_r + in_r[i] * dry_scale;
            float dry_mix = 1.0f - smooth_mix;

            out_l[i] = in_l[i] * dry_mix + effect_l * smooth_mix;
            out_r[i] = in_r[i] * dry_mix + effect_r * smooth_mix;

            // ── Advance write pointer ──
            write_pos = (write_pos + 1) & kBufMask;
        }

        // Export final rotor phases for animation (0..1)
        out_horn_phase   = horn_phase;
        out_woofer_phase = woofer_phase;
    }
};
