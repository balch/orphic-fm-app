#include "orpheus_master_scratch.h"

#include <cmath>

namespace orpheus {

void MasterScratch::arm(int samples, float sample_rate, unsigned int seed_offset) {
    if (samples <= 0) return;
    sample_rate_ = sample_rate;
    const int total = samples;
    const float sr = sample_rate;
    auto ms = [sr](int m) { return (int)(m * 0.001f * sr); };

    // Repeat count rides the handoff length: punchy single repeat at the
    // default, up to 3 at the long end.
    int N;
    if (total < ms(700)) N = 1;
    else if (total < ms(1000)) N = 2;
    else N = 3;

    // Base segment durations (clamped mechanically).
    int drag = (int)(total * kDragFrac);
    if (drag < ms(kDragMinMs)) drag = ms(kDragMinMs);
    if (drag > ms(kDragMaxMs)) drag = ms(kDragMaxMs);

    int slice = ms(kSliceMs);
    if (slice < ms(kSliceMinMs)) slice = ms(kSliceMinMs);
    if (slice > ms(kSliceMaxMs)) slice = ms(kSliceMaxMs);

    int mini = ms(kMiniMs);

    int squeal = (int)(total * kSquealFrac);
    if (squeal < ms(kSquealMinMs)) squeal = ms(kSquealMinMs);
    if (squeal > ms(kSquealMaxMs)) squeal = ms(kSquealMaxMs);

    // Scale all segments by one factor if the natural gesture exceeds the
    // handoff (short 250-350ms handoffs). Keeps proportions; the kDropForce
    // near-end fallback still guarantees a clean hand-off.
    int natural = drag + N * (slice + mini) + squeal;
    int budget = total - (int)kDropXfade;
    if (budget < 1) budget = (total > 1) ? total - 1 : 1;
    if (natural > budget) {
        float f = (float)budget / (float)natural;
        drag   = (int)(drag * f);   if (drag < 1) drag = 1;
        slice  = (int)(slice * f);  if (slice < 1) slice = 1;
        mini   = (int)(mini * f);   if (mini < 1) mini = 1;
        squeal = (int)(squeal * f); if (squeal < 1) squeal = 1;
    }
    if (slice > kSliceCap) slice = kSliceCap;
    slice_samples_ = slice;

    // Lay out the segment timeline: drag, (repeat, mini) * N, squeal, post.
    int cursor = 0;
    int s = 0;
    seg_type_[s] = SEG_DRAG; seg_start_[s] = cursor; seg_len_[s] = drag; cursor += drag; ++s;
    for (int k = 0; k < N; ++k) {
        seg_type_[s] = SEG_REPEAT; seg_start_[s] = cursor; seg_len_[s] = slice; cursor += slice; ++s;
        seg_type_[s] = SEG_MINI;   seg_start_[s] = cursor; seg_len_[s] = mini;  cursor += mini;  ++s;
    }
    seg_type_[s] = SEG_SQUEAL; seg_start_[s] = cursor; seg_len_[s] = squeal; cursor += squeal; ++s;
    drop_threshold_ = cursor;
    int post = total - cursor; if (post < 1) post = 1;
    seg_type_[s] = SEG_POST; seg_start_[s] = cursor; seg_len_[s] = post; ++s;
    seg_count_ = s;
    cur_seg_ = 0;

    // Snapshot the freshest slice from the ring (the audio playing at the skip).
    for (int k = 0; k < slice_samples_; ++k) {
        int idx = ((write_head_ - slice_samples_ + k) % kRingSize + kRingSize) % kRingSize;
        slice_[k] = ring_[idx];
    }

    read_pos_     = 0.0f;  // drag entry
    click_env_    = 0.0f;
    crackle_env_  = 0.0f;
    dropped_      = false;
    drop_elapsed_ = 0;
    rng_ = 0x9E3779B9u + seed_offset * 2654435761u + (uint32_t)total;

    samples_total_.store(total, std::memory_order_relaxed);
    samples_left_.store(total, std::memory_order_release);
}

void MasterScratch::process(float* buf, size_t n, float beat_phase, float bpm) {
    int left = samples_left_.load(std::memory_order_acquire);

    if (left <= 0) {
        // Disarmed: keep capturing so the ring is primed at the next arm().
        for (size_t i = 0; i < n; ++i) {
            ring_[write_head_] = buf[i];
            write_head_ = (write_head_ + 1) % kRingSize;
        }
        return;
    }

    const int total = samples_total_.load(std::memory_order_relaxed);
    const float phase_inc = bpm / (60.0f * sample_rate_);
    const float kPi = 3.14159265f;
    float bphase = beat_phase;

    for (size_t i = 0; i < n; ++i) {
        if (left <= 0) break;

        const float dry = buf[i];
        ring_[write_head_] = dry;
        write_head_ = (write_head_ + 1) % kRingSize;

        const int elapsed = total - left;

        // Advance to the current segment, running entry actions on each crossing.
        while (cur_seg_ + 1 < seg_count_ && elapsed >= seg_start_[cur_seg_ + 1]) {
            ++cur_seg_;
            const uint8_t t = seg_type_[cur_seg_];
            if (t == SEG_REPEAT || t == SEG_SQUEAL) {
                read_pos_ = 0.0f;                  // replay the slice from its start
                if (!dropped_) click_env_ = 1.0f;  // groove catch
            }
        }

        const uint8_t type = seg_type_[cur_seg_];
        const int seg_len = seg_len_[cur_seg_] > 0 ? seg_len_[cur_seg_] : 1;
        const float p = (float)(elapsed - seg_start_[cur_seg_]) / (float)seg_len; // 0..1

        float speed;
        switch (type) {
            case SEG_DRAG:   speed = 1.0f - kDragSweep * p; break;                       // pitch-down scrape
            case SEG_MINI:   speed = kMiniSpeed * std::cos(kPi * p); break;              // wikka: fwd then back
            case SEG_SQUEAL: speed = 1.0f + (kSquealSpeed - 1.0f) * std::sin(kPi * p); break; // up then down
            case SEG_REPEAT:
            case SEG_POST:
            default:         speed = 1.0f; break;
        }
        read_pos_ += speed;
        while (read_pos_ >= (float)slice_samples_) read_pos_ -= (float)slice_samples_;
        while (read_pos_ < 0.0f) read_pos_ += (float)slice_samples_;
        float wet = interp_slice(read_pos_);

        // Drop back to live: first beat wrap past the squeal, or forced near end.
        if (!dropped_ && elapsed >= drop_threshold_) {
            const bool beat_wrap = (bphase + phase_inc >= 1.0f);
            const bool near_end  = (left <= kDropForce);
            if (beat_wrap || near_end) {
                dropped_      = true;
                drop_elapsed_ = 0;
                click_env_    = 1.0f;  // final catch as the needle drops
            }
        }

        // Groove / drop click transient.
        if (click_env_ > 1e-4f) {
            wet += kClickGain * click_env_ * next_noise();
            click_env_ *= kClickDecay;
        }

        // Subtle surface crackle: sparse pops + faint hiss.
        if (next_uniform() < kCrackleProb)
            crackle_env_ = kCrackleGain * (0.5f + 0.5f * next_uniform());
        wet += crackle_env_ * next_noise();
        crackle_env_ *= kCrackleDecay;
        wet += kHissGain * next_noise();

        // Output: wet skip, crossfading to live once dropped.
        float out;
        if (dropped_) {
            float x = (float)drop_elapsed_ / kDropXfade;
            if (x > 1.0f) x = 1.0f;
            out = wet * (1.0f - x) + dry * x;
            ++drop_elapsed_;
        } else {
            out = wet;
        }
        buf[i] = out;

        bphase += phase_inc;
        if (bphase >= 1.0f) bphase -= 1.0f;
        --left;
    }

    samples_left_.store(left, std::memory_order_relaxed);
}

} // namespace orpheus
