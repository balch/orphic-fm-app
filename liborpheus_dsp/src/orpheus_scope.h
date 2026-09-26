#pragma once
#include <atomic>
#include <cstdint>
#include <cstring>

// Tests the bits where the optimizer can't see them: under -ffast-math clang turns a plain bit
// test back into an FP-class test and folds it to "finite".
inline bool scope_is_finite(float x) {
    uint32_t bits;
    std::memcpy(&bits, &x, sizeof bits);
#if defined(__GNUC__) && !defined(__EMSCRIPTEN__)
    __asm__ volatile("" : "+r"(bits));
#else
    volatile uint32_t opaque = bits;
    bits = opaque;
#endif
    return (bits & 0x7f800000u) != 0x7f800000u;
}

// Mono tap of the master mix for orpheus_engine_get_scope. The audio thread, its only writer,
// claims the slots it will overwrite before writing; a reader checks the claim afterwards.
struct ScopeRing {
    static constexpr uint32_t kSize = 32768;  // power of two: 683 ms at 48 kHz, 341 ms at 96 kHz
    static constexpr uint32_t kMask = kSize - 1;

    std::atomic<float>    buf[kSize] = {};
    std::atomic<uint32_t> published{0};  // samples written and visible to readers
    std::atomic<uint32_t> claimed{0};    // samples the writer may be overwriting, >= published

    // Audio thread only: appends (L+R)/2 of n interleaved stereo frames. A non-finite sample (a
    // blown-up voice) is stored as 0, so no reader can pass it on to a drawing.
    inline void write_stereo(const float* lr, int n) {
        if (n <= 0) return;
        uint32_t w = published.load(std::memory_order_relaxed);
        if (n > static_cast<int>(kSize)) {  // longer than the ring: only the newest kSize survive
            const int skip = n - static_cast<int>(kSize);
            lr += 2 * skip;
            w += static_cast<uint32_t>(skip);
            n = static_cast<int>(kSize);
        }
        const uint32_t end = w + static_cast<uint32_t>(n);
        claimed.store(end, std::memory_order_relaxed);
        // Orders the claim before the overwrites: a reader that sees a new sample sees the claim.
        std::atomic_thread_fence(std::memory_order_release);
        for (int i = 0; i < n; ++i) {
            const float mono = (lr[2 * i] + lr[2 * i + 1]) * 0.5f;
            buf[(w + static_cast<uint32_t>(i)) & kMask].store(
                scope_is_finite(mono) ? mono : 0.0f, std::memory_order_relaxed);
        }
        published.store(end, std::memory_order_release);
    }
};

// Copies the newest len samples (oldest first) into dst. False when the writer lapped them
// mid-copy; dst then holds garbage.
bool scope_ring_copy_latest(const ScopeRing& ring, float* dst, int len);

// The read behind orpheus_engine_get_scope; see orpheus_dsp.h for the contract.
int scope_read(const ScopeRing& ring, float sample_rate, float window_ms, float* out, int num_points);
