#pragma once

// Fog horn voice: a low ship's horn that swells in, holds and sags away, as one blast or a
// "dum, duuum, dumm" phrase. Engine-free: the host picks the pitch (from the vibe's key) and
// says when to sound it, from a section's fog bed or from the FogHornAnomaly.

#include "pulsar_rng.h"
#include "stmlib/dsp/filter.h"
#include <algorithm>
#include <cmath>
#include <cstdint>

namespace fog {

static constexpr float kTwoPi = 6.28318530718f;
static constexpr float kSilence = 1e-4f;
static constexpr int kMaxBlasts = 3;

// Phrase shapes. Values are the wire encoding (section slot / anomaly bank).
enum Pattern : int { kSingle = 0, kDumDuum = 1, kPatternCount = 2 };

// EAR-TUNE: the horn. Times are seconds, ratios multiply the root pitch.
static constexpr float kAttackSeconds = 0.20f;        // capped at 40% of a short blast
static constexpr float kReleaseSeconds = 0.90f;
static constexpr float kScoopDepth = 0.035f;          // starts this far flat, rises into pitch
static constexpr float kScoopSeconds = 0.35f;
static constexpr float kBeatRatio = 1.004f;           // the second saw beats against the first
static constexpr float kFifthRatio = 1.5f;
static constexpr float kSawGain = 0.60f;
static constexpr float kBeatGain = 0.50f;
static constexpr float kFifthGain = 0.30f;
static constexpr float kSubGain = 0.35f;
static constexpr float kBellMultiple = 3.5f;          // the bell's resonance, in root multiples
static constexpr float kBellQ = 3.0f;
static constexpr float kBellGain = 0.25f;
static constexpr float kBreathHz = 420.0f;
static constexpr float kBreathGain = 0.12f;
static constexpr float kTremoloHz = 4.6f;
static constexpr float kTremoloDepth = 0.05f;
static constexpr float kCutoffClosedHz = 500.0f;      // mouth shut: low and dull
static constexpr float kCutoffOpenHz = 1200.0f;       // blown hard: opens up
static constexpr float kCutoffQ = 1.2f;
static constexpr float kDistanceDarken = 0.65f;       // distance 1 closes the cutoff by this much
static constexpr float kDistanceQuiet = 0.45f;
static constexpr float kHornGain = 0.30f;

// {start delay s, length s, pitch ratio} for each blast of a phrase.
struct BlastSpec { float delay; float length; float ratio; };
static constexpr BlastSpec kSingleBlasts[] = { {0.0f, 3.0f, 1.0f} };
static constexpr BlastSpec kDumDuumBlasts[] = {
    {0.0f, 0.45f, 1.0f}, {1.10f, 2.60f, 1.0f}, {4.40f, 0.60f, 0.891f}   // the last sags a tone
};

inline float clamp01(float v) { return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v); }

inline float noise(uint32_t& rng) { return pattern_rand01(rng) * 2.0f - 1.0f; }

// Band-limited saw: naive ramp minus the PolyBLEP correction at the wrap.
inline float poly_blep(float t, float dt) {
    if (t < dt) { t /= dt; return t + t - t * t - 1.0f; }
    if (t > 1.0f - dt) { t = (t - 1.0f) / dt; return t * t + t + t + 1.0f; }
    return 0.0f;
}

inline float saw(float phase, float dt) { return (2.0f * phase - 1.0f) - poly_blep(phase, dt); }

// One horn note: attack, hold for the blast's length, then release. A new blast takes over
// from wherever the envelope is, so back-to-back blasts never click.
struct HornGen {
    void Init(uint32_t seed, float sample_rate) {
        sr_ = sample_rate; rng_ = seed ? seed : 1u;
        env_ = 0.0f; gate_left_ = 0; held_ = 0; since_start_ = 0.0f;
        phase_a_ = phase_b_ = phase_c_ = phase_sub_ = phase_trem_ = 0.0f;
        low_.Init(); bell_.Init(); breath_.Init();
        breath_.set_f_q<stmlib::FREQUENCY_FAST>(std::min(0.2f, kBreathHz / sample_rate), 0.8f);
    }

    void Blast(float hz, float length_seconds, float intensity, float distance) {
        hz_ = std::max(20.0f, hz);
        intensity_ = clamp01(intensity);
        distance_ = clamp01(distance);
        held_ = std::max(1, static_cast<int>(length_seconds * sr_));
        gate_left_ = held_;
        since_start_ = 0.0f;
        attack_step_ = 1.0f / std::max(1.0f, std::min(kAttackSeconds, 0.4f * length_seconds) * sr_);
        release_coef_ = std::exp(std::log(kSilence) / (kReleaseSeconds * sr_));
        bell_.set_f_q<stmlib::FREQUENCY_FAST>(std::min(0.2f, hz_ * kBellMultiple / sr_), kBellQ);
    }

    bool active() const { return gate_left_ > 0 || env_ > kSilence; }

    // ADDS into l/r.
    void Process(float* l, float* r, int n) {
        if (!active()) return;
        const float scoop_samples = kScoopSeconds * sr_;
        for (int i = 0; i < n; i++) {
            if (gate_left_ > 0) { env_ = std::min(1.0f, env_ + attack_step_); gate_left_--; }
            else env_ *= release_coef_;

            if ((i & 7) == 0) {
                const float open = kCutoffClosedHz + (kCutoffOpenHz - kCutoffClosedHz) * env_;
                const float cutoff = open * (1.0f - kDistanceDarken * distance_);
                low_.set_f_q<stmlib::FREQUENCY_FAST>(std::min(0.2f, cutoff / sr_), kCutoffQ);
            }

            const float scoop = 1.0f - kScoopDepth * std::max(0.0f, 1.0f - since_start_ / scoop_samples);
            const float f = hz_ * scoop;
            const float dt = f / sr_;
            phase_a_ = wrap(phase_a_ + dt);
            phase_b_ = wrap(phase_b_ + dt * kBeatRatio);
            phase_c_ = wrap(phase_c_ + dt * kFifthRatio);
            phase_sub_ = wrap(phase_sub_ + dt * 0.5f);
            const float raw = kSawGain * saw(phase_a_, dt)
                            + kBeatGain * saw(phase_b_, dt * kBeatRatio)
                            + kFifthGain * saw(phase_c_, dt * kFifthRatio)
                            + kSubGain * std::sin(kTwoPi * phase_sub_);
            float s = low_.Process<stmlib::FILTER_MODE_LOW_PASS>(raw);
            s += kBellGain * bell_.Process<stmlib::FILTER_MODE_BAND_PASS>(raw);
            // Breath is loudest while the horn is still opening.
            s += kBreathGain * (1.0f - env_) * env_ * 4.0f
                 * breath_.Process<stmlib::FILTER_MODE_BAND_PASS>(noise(rng_));

            phase_trem_ = wrap(phase_trem_ + kTremoloHz / sr_);
            const float trem = 1.0f + kTremoloDepth * std::sin(kTwoPi * phase_trem_);
            const float out = s * env_ * trem * kHornGain * intensity_
                              * (1.0f - kDistanceQuiet * distance_);
            l[i] += out;
            r[i] += out;
            since_start_ += 1.0f;
        }
    }

 private:
    static float wrap(float p) { return p - std::floor(p); }

    float sr_ = 48000.0f;
    uint32_t rng_ = 1;
    float hz_ = 69.0f, intensity_ = 0.0f, distance_ = 0.0f;
    float env_ = 0.0f, attack_step_ = 0.01f, release_coef_ = 0.999f, since_start_ = 0.0f;
    int gate_left_ = 0, held_ = 0;
    float phase_a_ = 0.0f, phase_b_ = 0.0f, phase_c_ = 0.0f, phase_sub_ = 0.0f, phase_trem_ = 0.0f;
    stmlib::Svf low_, bell_, breath_;
};

struct FogHornVoice {
    void Init(uint32_t seed, float sample_rate) {
        sr_ = sample_rate;
        horn_.Init(seed, sample_rate);
        pending_ = 0;
    }

    // Start a phrase. `root_hz` is the horn's pitch (the host derives it from the vibe's key).
    // A phrase in flight is replaced, so a re-trigger never stacks a second horn on the first.
    void Trigger(float root_hz, int pattern, float intensity, float distance) {
        const BlastSpec* specs = pattern == kDumDuum ? kDumDuumBlasts : kSingleBlasts;
        const int count = pattern == kDumDuum ? 3 : 1;
        root_hz_ = root_hz; intensity_ = intensity; distance_ = distance;
        pending_ = 0;
        for (int i = 0; i < count && i < kMaxBlasts; i++) {
            pending_blast_[pending_] = specs[i];
            pending_left_[pending_] = static_cast<int>(specs[i].delay * sr_);
            pending_++;
        }
    }

    // True while a phrase is still being played or has blasts waiting.
    bool active() const { return pending_ > 0 || horn_.active(); }

    // ADDS the horn into l/r. Blasts start sample-accurately inside the block.
    void Process(float* l, float* r, int n) {
        if (!active()) return;
        int cursor = 0;
        while (cursor < n) {
            int next = n;
            for (int i = 0; i < pending_; i++) next = std::min(next, cursor + pending_left_[i]);
            if (next > cursor) {
                horn_.Process(l + cursor, r + cursor, next - cursor);
                for (int i = 0; i < pending_; i++) pending_left_[i] -= next - cursor;
                cursor = next;
            }
            bool fired = false;
            for (int i = 0; i < pending_; i++) {
                if (pending_left_[i] > 0) continue;
                horn_.Blast(root_hz_ * pending_blast_[i].ratio, pending_blast_[i].length,
                            intensity_, distance_);
                for (int j = i + 1; j < pending_; j++) {
                    pending_blast_[j - 1] = pending_blast_[j];
                    pending_left_[j - 1] = pending_left_[j];
                }
                pending_--;
                fired = true;
                break;
            }
            if (!fired && cursor >= n) break;
        }
    }

 private:
    float sr_ = 48000.0f;
    HornGen horn_;
    BlastSpec pending_blast_[kMaxBlasts] = {};
    int pending_left_[kMaxBlasts] = {};
    int pending_ = 0;
    float root_hz_ = 69.0f, intensity_ = 0.0f, distance_ = 0.0f;
};

}  // namespace fog
