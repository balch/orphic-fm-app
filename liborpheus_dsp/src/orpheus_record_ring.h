// Lock-free SPSC stereo ring for desktop and Android recording. The audio thread writes, one
// consumer thread reads. Indices are free-running uint32 (capacity is a power of two, so wrap is
// a mask).
#pragma once

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <memory>

namespace orpheus {

class RecordRing {
public:
    // Rounds up to a power of two; allocates once.
    explicit RecordRing(int capacity_frames) {
        uint32_t cap = 1;
        while (cap < static_cast<uint32_t>(std::max(capacity_frames, 1))) cap <<= 1;
        capacity_ = cap;
        mask_ = cap - 1;
        buf_ = std::make_unique<float[]>(static_cast<size_t>(cap) * 2);
    }

    void arm() { armed_.store(true, std::memory_order_release); }
    void disarm() { armed_.store(false, std::memory_order_release); }
    bool armed() const { return armed_.load(std::memory_order_acquire); }

    // Audio thread. Never overwrites unread data: frames that do not fit are counted as dropped.
    void write(const float* interleaved, int frames) {
        if (frames <= 0 || !armed_.load(std::memory_order_acquire)) return;
        const uint32_t w = write_.load(std::memory_order_relaxed);
        const uint32_t r = read_.load(std::memory_order_acquire);
        const uint32_t free_frames = capacity_ - (w - r);
        const uint32_t n = std::min(static_cast<uint32_t>(frames), free_frames);
        copy_in(w, interleaved, n);
        write_.store(w + n, std::memory_order_release);
        if (n < static_cast<uint32_t>(frames))
            dropped_.fetch_add(frames - n, std::memory_order_relaxed);
    }

    // Consumer thread. Returns the number of frames copied.
    int read(float* out_interleaved, int max_frames) {
        if (max_frames <= 0) return 0;
        const uint32_t r = read_.load(std::memory_order_relaxed);
        const uint32_t w = write_.load(std::memory_order_acquire);
        const uint32_t n = std::min(static_cast<uint32_t>(max_frames), w - r);
        copy_out(r, out_interleaved, n);
        read_.store(r + n, std::memory_order_release);
        return static_cast<int>(n);
    }

    // Consumer thread. Touches only the read index, so it is safe while the writer runs.
    void discard() { read_.store(write_.load(std::memory_order_acquire), std::memory_order_release); }

    // Monotonic and never reset; callers keep their own baseline.
    uint64_t dropped_frames() const { return dropped_.load(std::memory_order_relaxed); }
    int capacity_frames() const { return static_cast<int>(capacity_); }

private:
    void copy_in(uint32_t start, const float* src, uint32_t frames) {
        const uint32_t first = std::min(frames, capacity_ - (start & mask_));
        std::memcpy(&buf_[static_cast<size_t>(start & mask_) * 2], src, first * 2 * sizeof(float));
        std::memcpy(&buf_[0], src + static_cast<size_t>(first) * 2, (frames - first) * 2 * sizeof(float));
    }

    void copy_out(uint32_t start, float* dst, uint32_t frames) const {
        const uint32_t first = std::min(frames, capacity_ - (start & mask_));
        std::memcpy(dst, &buf_[static_cast<size_t>(start & mask_) * 2], first * 2 * sizeof(float));
        std::memcpy(dst + static_cast<size_t>(first) * 2, &buf_[0], (frames - first) * 2 * sizeof(float));
    }

    std::unique_ptr<float[]> buf_;
    uint32_t capacity_ = 0;
    uint32_t mask_ = 0;
    std::atomic<bool> armed_{false};
    std::atomic<uint32_t> write_{0};
    std::atomic<uint32_t> read_{0};
    std::atomic<uint64_t> dropped_{0};
};

}  // namespace orpheus
