#pragma once

// Street voice: beat-locked footsteps and a steam train pulling away, section-driven like the
// storm bed (pulsar_storm.h). Engine-free: the host feeds step boundaries and section progress.

#include "pulsar_rng.h"
#include "stmlib/dsp/filter.h"
#include <algorithm>
#include <cmath>
#include <cstdint>

namespace street {

static constexpr float kTwoPi = 6.28318530718f;
static constexpr float kSilence = 1e-4f;
static constexpr int kMaxQueuedSteps = 32;

// EAR-TUNE: footsteps. Envelope seconds are the time to fall to kSilence.
static constexpr float kStepThudStartHz = 140.0f;
static constexpr float kStepThudEndHz = 85.0f;
static constexpr float kStepThudSeconds = 0.045f;
static constexpr float kStepThudGain = 0.55f;
static constexpr float kStepScuffHz = 1200.0f;
static constexpr float kStepScuffQ = 0.8f;
static constexpr float kStepScuffSeconds = 0.025f;
static constexpr float kStepScuffGain = 0.35f;
static constexpr float kStepPan = 0.20f;
static constexpr float kStepLevelJitter = 0.15f;
static constexpr float kStepToneJitter = 0.05f;
static constexpr float kRunBrightness = 1.6f;
static constexpr float kRunLength = 0.7f;
static constexpr float kRunGain = 1.25f;

// EAR-TUNE: steam train.
static constexpr float kChuffRateStart = 1.5f;    // chuffs per second at progress 0
static constexpr float kChuffRateEnd = 6.0f;
static constexpr float kChuffSeconds = 0.12f;
static constexpr float kChuffHzStart = 900.0f;
static constexpr float kChuffHzEnd = 500.0f;
static constexpr float kChuffQ = 1.2f;
static constexpr float kChuffAccent = 1.0f;
static constexpr float kChuffPlain = 0.6f;
static constexpr float kChuffGain = 0.5f;
static constexpr float kTrainLpStartHz = 6000.0f;
static constexpr float kTrainLpEndHz = 1200.0f;
static constexpr float kWhistleHzA = 550.0f;
static constexpr float kWhistleHzB = 660.0f;
static constexpr float kWhistleSeconds = 1.2f;
static constexpr float kWhistleAttackSeconds = 0.06f;
static constexpr float kWhistleReleaseSeconds = 0.30f;
static constexpr float kWhistleGain = 0.18f;

inline uint32_t street_seed(uint32_t seed, uint32_t salt) {
    uint32_t x = seed ^ (salt * 0x9E3779B9u);
    x ^= x >> 16; x *= 0x7FEB352Du; x ^= x >> 15; x *= 0x846CA68Bu; x ^= x >> 16;
    return x ? x : 0x1234567u;
}

// Per-sample multiplier that falls from 1 to kSilence in `seconds`.
inline float decay_to_silence(float seconds, float sample_rate) {
    return std::exp(std::log(kSilence) / std::max(1.0f, seconds * sample_rate));
}

inline float noise(uint32_t& rng) { return pattern_rand01(rng) * 2.0f - 1.0f; }

// One step at a time: a falling low thud plus a band-passed scuff, alternating sides.
struct FootstepGen {
    void Init(uint32_t seed, float sample_rate) {
        sr_ = sample_rate; rng_ = seed;
        thud_env_ = 0.0f; scuff_env_ = 0.0f; phase_ = 0.0f; left_ = true;
        scuff_.Init();
    }

    void Trigger(float level, bool run) {
        const float jitter = 1.0f + noise(rng_) * kStepLevelJitter;
        const float gain = level * jitter * (run ? kRunGain : 1.0f);
        const float length = run ? kRunLength : 1.0f;
        thud_env_ = gain;
        scuff_env_ = gain;
        thud_decay_ = decay_to_silence(kStepThudSeconds * length, sr_);
        scuff_decay_ = decay_to_silence(kStepScuffSeconds * length, sr_);
        thud_glide_ = decay_to_silence(kStepThudSeconds * length * 0.5f, sr_);
        thud_hz_ = kStepThudStartHz;
        phase_ = 0.0f;
        const float tone = 1.0f + noise(rng_) * kStepToneJitter;
        const float hz = kStepScuffHz * tone * (run ? kRunBrightness : 1.0f);
        scuff_.set_f_q<stmlib::FREQUENCY_FAST>(std::min(0.2f, hz / sr_), kStepScuffQ);
        left_ = !left_;
    }

    bool active() const { return thud_env_ > kSilence || scuff_env_ > kSilence; }

    // ADDS into l/r.
    void Process(float* l, float* r, int n) {
        if (!active()) { thud_env_ = 0.0f; scuff_env_ = 0.0f; return; }   // keeps state out of denormals
        const float pan_l = left_ ? 0.5f + kStepPan : 0.5f - kStepPan;
        const float pan_r = 1.0f - pan_l;
        for (int i = 0; i < n; i++) {
            thud_hz_ = kStepThudEndHz + (thud_hz_ - kStepThudEndHz) * thud_glide_;
            phase_ += kTwoPi * thud_hz_ / sr_;
            if (phase_ > kTwoPi) phase_ -= kTwoPi;
            const float thud = std::sin(phase_) * thud_env_ * kStepThudGain;
            const float scuff = scuff_.Process<stmlib::FILTER_MODE_BAND_PASS>(noise(rng_))
                                * scuff_env_ * kStepScuffGain;
            const float s = thud + scuff;
            l[i] += s * pan_l;
            r[i] += s * pan_r;
            thud_env_ *= thud_decay_;
            scuff_env_ *= scuff_decay_;
        }
    }

 private:
    float sr_ = 48000.0f;
    uint32_t rng_ = 1;
    float thud_env_ = 0.0f, scuff_env_ = 0.0f;
    float thud_decay_ = 0.0f, scuff_decay_ = 0.0f, thud_glide_ = 0.0f;
    float thud_hz_ = kStepThudStartHz, phase_ = 0.0f;
    stmlib::Svf scuff_;
    bool left_ = true;
};

// A steam train pulling away: chuffs speed up, darken and fade across its section, and one
// whistle blows as it leaves.
struct TrainGen {
    void Init(uint32_t seed, float sample_rate) {
        sr_ = sample_rate; rng_ = seed;
        chuff_env_ = 0.0f; chuff_clock_ = 1.0f; chuff_count_ = 0; lp_ = 0.0f;
        whistle_left_ = 0; whistle_t_ = 0.0f; phase_a_ = 0.0f; phase_b_ = 0.0f;
        band_.Init();
        breath_.Init();
        breath_.set_f_q<stmlib::FREQUENCY_FAST>(std::min(0.2f, 2500.0f / sample_rate), 0.7f);
    }

    void Whistle() { whistle_left_ = static_cast<int>(kWhistleSeconds * sr_); whistle_t_ = 0.0f; }

    bool active() const { return chuff_env_ > kSilence || whistle_left_ > 0; }

    // ADDS into l/r. `level` 0-1, `progress` 0-1 through the train's section.
    void Process(float* l, float* r, int n, float level, float progress) {
        const bool running = level > kSilence;
        if (!running && !active()) { band_.Init(); lp_ = 0.0f; chuff_env_ = 0.0f; return; }
        progress = std::min(1.0f, std::max(0.0f, progress));
        const float rate = kChuffRateStart + (kChuffRateEnd - kChuffRateStart) * progress;
        const float recede = std::pow(1.0f - progress, 1.5f);
        band_.set_f_q<stmlib::FREQUENCY_FAST>((kChuffHzStart + (kChuffHzEnd - kChuffHzStart) * progress) / sr_, kChuffQ);
        const float lp_hz = kTrainLpStartHz + (kTrainLpEndHz - kTrainLpStartHz) * progress;
        const float lp_coef = 1.0f - std::exp(-kTwoPi * lp_hz / sr_);
        const float chuff_decay = decay_to_silence(kChuffSeconds, sr_);
        const float attack = kWhistleAttackSeconds * sr_;
        const float release = kWhistleReleaseSeconds * sr_;
        for (int i = 0; i < n; i++) {
            if (running) {
                chuff_clock_ += rate / sr_;
                if (chuff_clock_ >= 1.0f) {
                    chuff_clock_ -= 1.0f;
                    chuff_env_ = (chuff_count_ % 4 == 0) ? kChuffAccent : kChuffPlain;
                    chuff_count_++;
                }
            }
            const float chuff = band_.Process<stmlib::FILTER_MODE_BAND_PASS>(noise(rng_)) * chuff_env_;
            chuff_env_ *= chuff_decay;
            lp_ += (chuff - lp_) * lp_coef;
            float s = lp_ * kChuffGain * level * recede;
            if (whistle_left_ > 0) {
                float env = whistle_t_ < attack ? whistle_t_ / attack : 1.0f;
                const float left = static_cast<float>(whistle_left_);
                if (left < release) env = std::min(env, left / release);
                phase_a_ += kTwoPi * kWhistleHzA / sr_;
                if (phase_a_ > kTwoPi) phase_a_ -= kTwoPi;
                phase_b_ += kTwoPi * kWhistleHzB / sr_;
                if (phase_b_ > kTwoPi) phase_b_ -= kTwoPi;
                const float breath = breath_.Process<stmlib::FILTER_MODE_BAND_PASS>(noise(rng_)) * 0.3f;
                s += (std::sin(phase_a_) + std::sin(phase_b_) + breath) * env * kWhistleGain * level;
                whistle_t_ += 1.0f;
                whistle_left_--;
            }
            l[i] += s;
            r[i] += s;
        }
    }

 private:
    float sr_ = 48000.0f;
    uint32_t rng_ = 1;
    float chuff_env_ = 0.0f, chuff_clock_ = 1.0f, lp_ = 0.0f;
    int chuff_count_ = 0;
    stmlib::Svf band_, breath_;
    int whistle_left_ = 0;
    float whistle_t_ = 0.0f, phase_a_ = 0.0f, phase_b_ = 0.0f;
};

struct StreetVoice {
    void Init(uint32_t seed, float sample_rate) {
        steps_.Init(street_seed(seed, 201u), sample_rate);
        train_.Init(street_seed(seed, 202u), sample_rate);
        queued_ = 0;
        footsteps_ = 0.0f; run_ = false; train_level_ = 0.0f; progress_ = 0.0f;
    }

    void set_bed(float footsteps, bool run, float train, float train_progress) {
        footsteps_ = clamp01(footsteps);
        run_ = run;
        train_level_ = clamp01(train);
        progress_ = clamp01(train_progress);
    }

    // Queue a footstep for a boundary at `frame` in the coming block. WALK steps on every beat
    // (step % 4), RUN on every 8th (step % 2); both are even steps, which swing never moves.
    void OnStepBoundary(int frame, int step_index) {
        if (footsteps_ <= kSilence || queued_ >= kMaxQueuedSteps) return;
        if (step_index % (run_ ? 2 : 4) != 0) return;
        queue_[queued_++] = frame;
    }

    void OnSectionEntry(float incoming_train) {
        if (incoming_train > kSilence) train_.Whistle();
    }

    bool idle() const {
        return footsteps_ <= kSilence && train_level_ <= kSilence && queued_ == 0
               && !steps_.active() && !train_.active();
    }

    // ADDS footsteps into foot_l/r and the train into train_l/r.
    void Process(float* foot_l, float* foot_r, float* train_l, float* train_r, int n) {
        int cursor = 0;
        for (int q = 0; q < queued_; q++) {
            const int at = std::min(std::max(queue_[q], cursor), n);
            if (at > cursor) steps_.Process(foot_l + cursor, foot_r + cursor, at - cursor);
            cursor = at;
            if (at < n) steps_.Trigger(footsteps_, run_);
        }
        if (cursor < n) steps_.Process(foot_l + cursor, foot_r + cursor, n - cursor);
        queued_ = 0;
        train_.Process(train_l, train_r, n, train_level_, progress_);
    }

 private:
    static float clamp01(float v) { return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v); }

    FootstepGen steps_;
    TrainGen train_;
    int queue_[kMaxQueuedSteps] = {};
    int queued_ = 0;
    float footsteps_ = 0.0f, train_level_ = 0.0f, progress_ = 0.0f;
    bool run_ = false;
};

}  // namespace street
