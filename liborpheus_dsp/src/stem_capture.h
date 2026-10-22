// Stem capture: an armable SPSC ring of 13 interleaved stereo stems per frame, fed from the mix
// bench's taps. It records only while the mix bench is enabled, since the bench owns the taps.
// Frame layout: t0L,t0R .. t7L,t7R, storm, street, speech, bus (pre_limit), out (L then R each).
#pragma once

#include "orpheus_engine.h"
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <memory>

constexpr int kStemChannels = 13;
constexpr int kStemFloatsPerFrame = kStemChannels * 2;
constexpr int kStemRingFrames = 1 << 16;  // ~6.8 MB, ~1.37 s at 48 kHz

class StemCapture {
public:
    // Rounds up to a power of two; allocates once.
    explicit StemCapture(int capacity_frames) {
        uint32_t cap = 1;
        while (cap < static_cast<uint32_t>(std::max(capacity_frames, 1))) cap <<= 1;
        capacity_ = cap;
        mask_ = cap - 1;
        buf_ = std::make_unique<float[]>(static_cast<size_t>(cap) * kStemFloatsPerFrame);
    }

    // Control thread. Starts a capture of `frames` only when none is running. A re-arm keeps any
    // frames left undrained from the last capture and dropped() counts from enable, so drain to empty first.
    bool arm(int frames) {
        if (frames <= 0) return false;
        int idle = 0;
        return remaining_.compare_exchange_strong(idle, frames, std::memory_order_acq_rel);
    }

    // Audio thread. Records min(frames, remaining); frames that do not fit are dropped and still
    // count against remaining, so a capture always spans the armed wall-clock length.
    void on_block(const OrpheusEngine::PulsarMeasureTaps& t, const float* out, int frames) {
        const int rem = remaining_.load(std::memory_order_acquire);
        if (rem <= 0 || frames <= 0) return;
        const uint32_t n = static_cast<uint32_t>(std::min({frames, rem, kMaxFrames}));
        const uint32_t w = write_.load(std::memory_order_relaxed);
        const uint32_t r = read_.load(std::memory_order_acquire);
        const uint32_t k = std::min(n, capacity_ - (w - r));
        for (uint32_t i = 0; i < k; i++) {
            float* f = &buf_[static_cast<size_t>((w + i) & mask_) * kStemFloatsPerFrame];
            for (int c = 0; c < 8; c++) { *f++ = t.track_l[c][i]; *f++ = t.track_r[c][i]; }
            for (int s = 0; s < OrpheusEngine::PulsarMeasureTaps::kMeasureSources; s++) {
                *f++ = t.source_l[s][i];
                *f++ = t.source_r[s][i];
            }
            *f++ = t.pre_limit_l[i];
            *f++ = t.pre_limit_r[i];
            *f++ = out[2 * i];
            *f = out[2 * i + 1];
        }
        write_.store(w + k, std::memory_order_release);
        if (k < n) dropped_.fetch_add(n - k, std::memory_order_relaxed);
        remaining_.fetch_sub(static_cast<int>(n), std::memory_order_release);
    }

    // Reader thread. Copies up to max_frames frames of kStemFloatsPerFrame floats, oldest first.
    int drain(float* out, int max_frames) {
        if (!out || max_frames <= 0) return 0;
        const uint32_t r = read_.load(std::memory_order_relaxed);
        const uint32_t w = write_.load(std::memory_order_acquire);
        const uint32_t n = std::min(static_cast<uint32_t>(max_frames), w - r);
        const uint32_t first = std::min(n, capacity_ - (r & mask_));
        std::copy_n(&buf_[static_cast<size_t>(r & mask_) * kStemFloatsPerFrame],
                    static_cast<size_t>(first) * kStemFloatsPerFrame, out);
        std::copy_n(&buf_[0], static_cast<size_t>(n - first) * kStemFloatsPerFrame,
                    out + static_cast<size_t>(first) * kStemFloatsPerFrame);
        read_.store(r + n, std::memory_order_release);
        return static_cast<int>(n);
    }

    // Frames the armed capture still has to record; 0 when idle or done.
    int remaining() const { return remaining_.load(std::memory_order_acquire); }
    // Monotonic for this ring's lifetime.
    uint64_t dropped() const { return dropped_.load(std::memory_order_relaxed); }

private:
    std::unique_ptr<float[]> buf_;
    uint32_t capacity_ = 0;
    uint32_t mask_ = 0;
    std::atomic<int> remaining_{0};
    std::atomic<uint32_t> write_{0};
    std::atomic<uint32_t> read_{0};
    std::atomic<uint64_t> dropped_{0};
};
