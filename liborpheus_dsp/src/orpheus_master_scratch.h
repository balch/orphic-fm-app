#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>

namespace orpheus {

/**
 * Vinyl needle-skip on the master bus, "punchy multi-scratch" gesture. A ring
 * buffer continuously captures the incoming audio (bit-exact passthrough when
 * disarmed). When armed, it snapshots the freshest slice and plays a segmented
 * gesture:
 *
 *   drag -> [ repeat , baby-scratch ] x N -> squeal arc -> drop on beat
 *
 *   - drag (~60ms): short pitch-down scrape, the needle bump.
 *   - repeat: replays the frozen slice from its start (stuck-record), click on
 *     the catch. N rides the handoff length (1 below 700ms, 2 to 1000ms, else 3).
 *   - baby-scratch: a fast forward-then-back wiggle of the slice (~70ms), the
 *     "wikka"; always follows each repeat so even N=1 keeps one.
 *   - squeal arc (~150-280ms): read speed ramps up to ~5x (a high squeal) and
 *     back down, click on the catch.
 *   - drop: on the first beat_phase wrap past the squeal (or forced near the
 *     end), crossfade back to live audio (the new song the Kotlin runner swapped
 *     in).
 *
 * A subtle crackle/hiss bed mixes under it. All randomness uses a per-instance
 * LCG seeded in arm() from seed_offset (L/R get different seeds for stereo
 * crackle width). Requires beat_phase (0..1) and bpm for the on-beat drop.
 * Thread-safe: arm() (control thread) publishes geometry, then stores
 * samples_left_ with release; process() (audio thread) loads it with acquire.
 * Mono - run once per channel for stereo.
 */
class MasterScratch {
public:
    static constexpr int kRingSize = 16384;  // ~340ms @ 48k — history for the snapshot
    static constexpr int kSliceCap = 8192;   // max slice samples (>= 160ms @ 48k)

    MasterScratch() {
        for (int i = 0; i < kRingSize; ++i) ring_[i] = 0.0f;
        for (int i = 0; i < kSliceCap; ++i) slice_[i] = 0.0f;
    }

    void arm(int samples, float sample_rate, unsigned int seed_offset = 0);

    void process(float* buf, size_t n, float beat_phase, float bpm);

    bool is_active() const { return samples_left_.load(std::memory_order_relaxed) > 0; }

private:
    inline uint32_t lcg() { rng_ = rng_ * 1664525u + 1013904223u; return rng_; }
    inline float next_uniform() { return (lcg() >> 8) * (1.0f / 16777216.0f); } // [0,1)
    inline float next_noise() { return 2.0f * next_uniform() - 1.0f; }          // [-1,1)
    inline float interp_slice(float pos) const {
        int i0 = (int)pos;
        float frac = pos - (float)i0;
        i0 %= slice_samples_; if (i0 < 0) i0 += slice_samples_;
        int i1 = i0 + 1; if (i1 >= slice_samples_) i1 = 0;
        return slice_[i0] * (1.0f - frac) + slice_[i1] * frac;
    }

    enum SegType : uint8_t { SEG_DRAG, SEG_REPEAT, SEG_MINI, SEG_SQUEAL, SEG_POST };
    static constexpr int kMaxSegs = 10;  // drag + (repeat+mini)*3 + squeal + post = 9

    float ring_[kRingSize];
    float slice_[kSliceCap];
    int   write_head_ = 0;
    float read_pos_   = 0.0f;

    float sample_rate_ = 48000.0f;
    std::atomic<int> samples_left_{0};
    std::atomic<int> samples_total_{0};

    // Segment timeline (computed in arm()).
    int     seg_start_[kMaxSegs] = {};
    int     seg_len_[kMaxSegs]   = {};
    uint8_t seg_type_[kMaxSegs]  = {};
    int     seg_count_      = 0;
    int     cur_seg_        = 0;
    int     slice_samples_  = 1;
    int     drop_threshold_ = 0;  // start of the post-hold (end of squeal)

    // Run state.
    float    click_env_    = 0.0f;
    float    crackle_env_  = 0.0f;
    bool     dropped_      = false;
    int      drop_elapsed_ = 0;
    uint32_t rng_          = 0x12345u;

    // Tuning.
    static constexpr float kDragFrac    = 0.12f;
    static constexpr int   kDragMinMs   = 50;
    static constexpr int   kDragMaxMs   = 120;
    static constexpr float kDragSweep   = 1.6f;    // drag speed 1.0 -> 1.0-1.6
    static constexpr int   kSliceMs     = 95;      // repeat/snapshot length, mechanical
    static constexpr int   kSliceMinMs  = 80;
    static constexpr int   kSliceMaxMs  = 160;
    static constexpr int   kMiniMs      = 70;      // baby-scratch length
    static constexpr float kMiniSpeed   = 2.0f;    // fwd/back peak speed
    static constexpr float kSquealFrac  = 0.40f;
    static constexpr int   kSquealMinMs = 160;
    static constexpr int   kSquealMaxMs = 280;
    static constexpr float kSquealSpeed = 5.0f;    // peak read speed -> ~+2.3 oct squeal
    static constexpr float kDropXfade   = 256.0f;  // ~5ms crossfade to live
    static constexpr int   kDropForce   = 2048;    // force the drop within this many of the end
    static constexpr float kClickDecay  = 0.995f;  // ~4ms click tail
    static constexpr float kClickGain   = 0.6f;
    static constexpr float kCrackleProb = 0.0008f;
    static constexpr float kCrackleGain = 0.15f;
    static constexpr float kCrackleDecay = 0.85f;
    static constexpr float kHissGain    = 0.004f;
};

} // namespace orpheus
