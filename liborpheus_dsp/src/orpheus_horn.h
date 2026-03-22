#pragma once
// Orpheus Horn — dual-rotor Leslie speaker simulation
//
// Models a real Leslie 122/147 cabinet:
//   - Treble horn: single directional source rotating in a circle.
//     Two virtual microphones (L/R) at 0° and 180° pick up Doppler
//     pitch shift and amplitude panning as the horn sweeps past.
//   - Bass drum rotor: amplitude panning only (too large/slow for
//     audible Doppler). Opposite-phase AM between L/R mics.
//   - Crossover at ~800 Hz splits treble from bass.
//   - Rotor inertia: ~1s spin-up, ~3s coast-down (brake).

#include <cmath>
#include <cstring>

// ─── Crossover filter state (1st-order one-pole LP/HP pair) ───────────────────
struct OrpheusHornCrossover {
    float lp_state = 0.0f;

    inline void process(float in, float coeff, float& lp_out, float& hp_out) {
        lp_state += coeff * (in - lp_state);
        lp_out = lp_state;
        hp_out = in - lp_state;
    }

    void reset() { lp_state = 0.0f; }
};

// ─── OrpheusHorn ──────────────────────────────────────────────────────────────
struct OrpheusHorn {
    static constexpr int kBufSize  = 2048;
    static constexpr int kBufMask  = kBufSize - 1;
    static constexpr float k2Pi    = 6.28318530718f;

    // External buffer pointers (owned by OrpheusEngine)
    float* delay_l = nullptr;
    float* delay_r = nullptr;

    // Write position shared between L/R delay lines
    int write_pos = 0;

    // Rotor phases (0..1)
    float horn_phase  = 0.0f;
    float woofer_phase = 0.0f;

    // Current (inertia-smoothed) speeds, in Hz
    float horn_speed_hz   = 0.0f;
    float woofer_speed_hz = 0.0f;

    // Crossover filter state
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
    inline float read_interp(const float* buf, float offset) const {
        float rd = static_cast<float>(write_pos) - offset;
        while (rd < 0.0f) rd += static_cast<float>(kBufSize);
        int i0 = static_cast<int>(rd) & kBufMask;
        int i1 = (i0 + 1) & kBufMask;
        float frac = rd - static_cast<float>(static_cast<int>(rd));
        return buf[i0] + (buf[i1] - buf[i0]) * frac;
    }

    // ── Process one block ────────────────────────────────────────────────────
    void Process(
        const float* in_l, const float* in_r,
        float* out_l, float* out_r,
        int num_frames,
        float mix_target,
        float horn_hz_target,
        float woofer_hz_target,
        float depth_param,
        float crossover_coeff,
        float sample_rate,
        float& out_horn_phase,
        float& out_woofer_phase,
        float smooth_coeff_val)
    {
        // Inertia time constants
        const float inv_sr = 1.0f / sample_rate;
        const float ramp_up_coeff   = 1.0f - std::exp(-inv_sr / 1.0f);  // ~1s spin-up
        const float ramp_down_coeff = 1.0f - std::exp(-inv_sr / 3.0f);  // ~3s coast-down

        // ── Doppler delay depth ──
        // Single horn rotating at radius R from mic pair.
        // Delay modulation depth in samples, scaled by depth knob.
        // At depth=1.0: ±320 samples (~6.7ms at 48kHz) = strong pitch wobble.
        // Exponential ramp above 0.7 for dramatic high-end Doppler.
        float d = depth_param;
        if (d > 0.7f) {
            float excess = (d - 0.7f) / 0.3f;
            d = 0.7f + excess * excess * 1.1f;  // 0.7 → 1.8 at depth=1.0
        }
        const float center = 256.0f;            // center delay (samples)
        const float doppler_amp = d * 320.0f;   // ±320 samples at depth=1

        // ── AM depth ──
        // How strongly the horn/woofer amplitude pans between L and R.
        // At depth=1.0: horn swings 0..1 (full pan), woofer swings 0.2..1.
        float am_d = depth_param;
        if (am_d > 0.7f) {
            float excess = (am_d - 0.7f) / 0.3f;
            am_d = 0.7f + excess * excess * 0.3f + excess * 0.3f;
        }
        float horn_am_depth = 0.20f + 0.80f * am_d;  // 0.2..1.0
        float woof_am_depth = 0.15f + 0.55f * am_d;  // 0.15..0.7

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

            // ── Horn angle (single rotating source) ──
            float hp = horn_phase * k2Pi;
            float cos_horn = std::cos(hp);  // +1 when horn faces L mic, -1 when facing R
            float sin_horn = std::sin(hp);  // velocity component for Doppler

            // ── Treble path: single-source Doppler via delay line ──
            // Write mono treble into delay (sum L+R for a single source).
            float treble_mono = (treble_l + treble_r) * 0.5f;
            delay_l[write_pos] = treble_mono;

            // L mic delay: horn approaching L → shorter delay (pitch up)
            // R mic delay: horn approaching R → shorter delay (pitch down relative to L)
            // cos_horn: +1 = horn at L mic position, -1 = horn at R mic position
            float delay_l_offset = center - cos_horn * doppler_amp;
            float delay_r_offset = center + cos_horn * doppler_amp;

            // Clamp to valid range
            delay_l_offset = std::fmax(1.0f, std::fmin(delay_l_offset, static_cast<float>(kBufSize - 2)));
            delay_r_offset = std::fmax(1.0f, std::fmin(delay_r_offset, static_cast<float>(kBufSize - 2)));

            float wet_treble_l = read_interp(delay_l, delay_l_offset);
            float wet_treble_r = read_interp(delay_l, delay_r_offset);

            // ── Horn AM: amplitude panning as horn faces each mic ──
            // cos_horn=+1 → horn faces L → L loud, R quiet
            // cos_horn=-1 → horn faces R → R loud, L quiet
            float horn_am_l = 1.0f - horn_am_depth + horn_am_depth * (0.5f + 0.5f * cos_horn);
            float horn_am_r = 1.0f - horn_am_depth + horn_am_depth * (0.5f - 0.5f * cos_horn);
            wet_treble_l *= horn_am_l;
            wet_treble_r *= horn_am_r;

            // ── Bass path: woofer AM only (no Doppler) ──
            float cos_woof = std::cos(woofer_phase * k2Pi);
            float woof_am_l = 1.0f - woof_am_depth + woof_am_depth * (0.5f + 0.5f * cos_woof);
            float woof_am_r = 1.0f - woof_am_depth + woof_am_depth * (0.5f - 0.5f * cos_woof);
            float wet_bass_l = bass_l * woof_am_l;
            float wet_bass_r = bass_r * woof_am_r;

            // ── Recombine and crossfade ──
            float wet_l = wet_treble_l + wet_bass_l;
            float wet_r = wet_treble_r + wet_bass_r;

            // mix=0 → pure dry, mix=1 → full Leslie
            out_l[i] = in_l[i] + (wet_l - in_l[i]) * smooth_mix;
            out_r[i] = in_r[i] + (wet_r - in_r[i]) * smooth_mix;

            // ── Advance write pointer ──
            write_pos = (write_pos + 1) & kBufMask;
        }

        // Export final rotor phases for animation
        out_horn_phase   = horn_phase;
        out_woofer_phase = woofer_phase;
    }
};
