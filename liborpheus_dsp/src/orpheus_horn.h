#pragma once
// Orpheus Horn — dual-rotor Leslie speaker simulation
//
// Models a real Leslie 122/147 cabinet:
//   - Treble horn: single directional source rotating in a circle.
//     Two virtual microphones (L/R) at 0° and 180° pick up Doppler
//     pitch shift and amplitude panning as the horn sweeps past.
//   - Bass drum rotor: amplitude panning only (too large/slow for
//     audible Doppler). Opposite-phase AM between L/R mics.
//   - Crossover at ~800 Hz splits treble from bass. The bass path is delayed
//     by the treble's centre delay so the two bands recombine in phase.
//   - Rotor inertia: the horn spins up in ~1s and coasts in ~3s; the heavier
//     drum is slower both ways, so it audibly lags the horn on a speed change.

#include <cmath>
#include <cstring>

// ─── Depth curves ─────────────────────────────────────────────────────────────

// Doppler swing (seconds of delay modulation). A real horn (r ≈ 0.15 m) swings
// ±0.44 ms, about ±32 cents at fast speed; that sits at depth 0.5. The top of the
// knob reaches 4 ms for an exaggerated warble. Seconds, so it is sample-rate independent.
constexpr float kHornDopplerMaxSeconds = 0.004f;
constexpr float kHornDopplerCurve      = 3.2f;
inline float horn_doppler_seconds(float depth) {
    return kHornDopplerMaxSeconds * std::pow(std::fmax(depth, 0.0f), kHornDopplerCurve);
}

// Fixed treble centre delay (and bass alignment delay), in whole samples. It covers
// the largest swing plus a margin, so the modulated read never reaches the write head.
inline int horn_center_samples(float sample_rate) {
    return static_cast<int>(std::ceil(kHornDopplerMaxSeconds * sample_rate)) + 2;
}

// Amplitude-panning depth for each rotor. 1.0 = full pan (far side silent); capped
// there so the boosted top of the knob can never invert the far side's polarity.
struct HornAmDepths { float horn; float woofer; };
inline HornAmDepths horn_am_depths(float depth) {
    float am_d = depth;
    if (am_d > 0.7f) {
        float excess = (am_d - 0.7f) / 0.3f;
        am_d = 0.7f + excess * excess * 0.3f + excess * 0.3f;
    }
    return { std::fmin(0.20f + 0.80f * am_d, 1.0f), std::fmin(0.15f + 0.55f * am_d, 1.0f) };
}

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

    // Rotor inertia time constants (seconds)
    static constexpr float kHornSpinUp  = 1.0f;
    static constexpr float kHornCoast   = 3.0f;
    static constexpr float kDrumSpinUp  = 3.5f;
    static constexpr float kDrumCoast   = 4.5f;

    // Mono treble (single rotating source) and stereo bass, sharing one write head
    float treble_buf[kBufSize] = {};
    float bass_buf_l[kBufSize] = {};
    float bass_buf_r[kBufSize] = {};
    int write_pos = 0;

    // Rotor phases (0..1) and inertia-smoothed speeds (Hz). These survive bypass.
    float horn_phase   = 0.0f;
    float woofer_phase = 0.0f;
    float horn_speed_hz   = 0.0f;
    float woofer_speed_hz = 0.0f;

    OrpheusHornCrossover xover_l;
    OrpheusHornCrossover xover_r;

    // Smoothed mix (for artifact-free bypass transitions)
    float smooth_mix = 0.0f;
    bool  bypassed = true;

    // Drop everything audible so a re-enable starts from silence, not stale audio.
    void ClearAudio() {
        std::memset(treble_buf, 0, sizeof(treble_buf));
        std::memset(bass_buf_l, 0, sizeof(bass_buf_l));
        std::memset(bass_buf_r, 0, sizeof(bass_buf_r));
        xover_l.reset();
        xover_r.reset();
        smooth_mix = 0.0f;
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
        float smooth_coeff_val)
    {
        const float inv_sr = 1.0f / sample_rate;
        const float horn_up    = 1.0f - std::exp(-inv_sr / kHornSpinUp);
        const float horn_down  = 1.0f - std::exp(-inv_sr / kHornCoast);
        const float drum_up    = 1.0f - std::exp(-inv_sr / kDrumSpinUp);
        const float drum_down  = 1.0f - std::exp(-inv_sr / kDrumCoast);

        const int   center_i    = horn_center_samples(sample_rate);
        const float center      = static_cast<float>(center_i);
        const float doppler_amp = horn_doppler_seconds(depth_param) * sample_rate;
        const HornAmDepths am   = horn_am_depths(depth_param);

        for (int i = 0; i < num_frames; ++i) {
            // ── Inertia: slew speeds toward targets ──
            horn_speed_hz   += ((horn_hz_target > horn_speed_hz) ? horn_up : horn_down)
                               * (horn_hz_target - horn_speed_hz);
            woofer_speed_hz += ((woofer_hz_target > woofer_speed_hz) ? drum_up : drum_down)
                               * (woofer_hz_target - woofer_speed_hz);

            // ── Advance phases ──
            horn_phase   += horn_speed_hz   * inv_sr;
            woofer_phase += woofer_speed_hz * inv_sr;
            if (horn_phase   >= 1.0f) horn_phase   -= 1.0f;
            if (woofer_phase >= 1.0f) woofer_phase -= 1.0f;

            smooth_mix += smooth_coeff_val * (mix_target - smooth_mix);

            // ── Crossover split ──
            float treble_l, bass_l, treble_r, bass_r;
            xover_l.process(in_l[i], crossover_coeff, bass_l, treble_l);
            xover_r.process(in_r[i], crossover_coeff, bass_r, treble_r);

            treble_buf[write_pos] = (treble_l + treble_r) * 0.5f;  // single source
            bass_buf_l[write_pos] = bass_l;
            bass_buf_r[write_pos] = bass_r;

            // ── Treble: single-source Doppler ──
            // cos_horn: +1 = horn faces the L mic (L delay shortest), -1 = faces R
            float cos_horn = std::cos(horn_phase * k2Pi);
            float wet_treble_l = read_interp(treble_buf, center - cos_horn * doppler_amp);
            float wet_treble_r = read_interp(treble_buf, center + cos_horn * doppler_amp);
            wet_treble_l *= 1.0f - am.horn + am.horn * (0.5f + 0.5f * cos_horn);
            wet_treble_r *= 1.0f - am.horn + am.horn * (0.5f - 0.5f * cos_horn);

            // ── Bass: woofer AM only, delayed to line up with the treble centre ──
            int bass_idx = (write_pos - center_i) & kBufMask;
            float cos_woof = std::cos(woofer_phase * k2Pi);
            float wet_bass_l = bass_buf_l[bass_idx] * (1.0f - am.woofer + am.woofer * (0.5f + 0.5f * cos_woof));
            float wet_bass_r = bass_buf_r[bass_idx] * (1.0f - am.woofer + am.woofer * (0.5f - 0.5f * cos_woof));

            // mix=0 → pure dry, mix=1 → full Leslie
            float wet_l = wet_treble_l + wet_bass_l;
            float wet_r = wet_treble_r + wet_bass_r;
            out_l[i] = in_l[i] + (wet_l - in_l[i]) * smooth_mix;
            out_r[i] = in_r[i] + (wet_r - in_r[i]) * smooth_mix;

            write_pos = (write_pos + 1) & kBufMask;
        }
    }
};
