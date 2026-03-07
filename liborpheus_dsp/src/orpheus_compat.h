#pragma once

// ── STM32 → Standard C++ compatibility shim ─────────────────────
// Allows original eurorack source to compile unmodified on desktop/mobile/WASM.

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>

// stmlib.h macros
#ifndef DISALLOW_COPY_AND_ASSIGN
#define DISALLOW_COPY_AND_ASSIGN(TypeName) \
    TypeName(const TypeName&) = delete;    \
    TypeName& operator=(const TypeName&) = delete;
#endif

// CONSTRAIN: in-place clamp (used heavily in MI code)
// The original is a macro that modifies `var` in place.
#ifndef CONSTRAIN
#define CONSTRAIN(var, min, max) \
    if ((var) < (min)) (var) = (min); \
    else if ((var) > (max)) (var) = (max);
#endif

// CLIP: 16-bit signed clip
#ifndef CLIP
#define CLIP(x) if ((x) < -32767) (x) = -32767; if ((x) > 32767) (x) = 32767;
#endif

// ARM SSAT intrinsics → standard clamp for non-ARM
#ifndef __arm__
inline int16_t Clip16(int32_t x) {
    return static_cast<int16_t>(std::clamp(x, -32768, 32767));
}
inline uint16_t ClipU16(int32_t x) {
    return static_cast<uint16_t>(std::clamp(x, 0, 65535));
}
#endif

// stmlib BufferAllocator — heap-based replacement for STM32 SRAM allocator.
// Only called during Init(), never on audio thread.
namespace stmlib {
class BufferAllocator {
public:
    BufferAllocator() : buffer_(nullptr), size_(0), offset_(0) {}
    BufferAllocator(uint8_t* buffer, size_t size)
        : buffer_(buffer), size_(size), offset_(0) {}

    void Init(uint8_t* buffer, size_t size) {
        buffer_ = buffer;
        size_ = size;
        offset_ = 0;
    }

    template<typename T>
    T* Allocate(size_t count) {
        size_t bytes = count * sizeof(T);
        // Align to sizeof(T) or 4, whichever is larger
        size_t align = std::max(sizeof(T), size_t(4));
        offset_ = (offset_ + align - 1) & ~(align - 1);
        T* ptr = reinterpret_cast<T*>(buffer_ + offset_);
        offset_ += bytes;
        std::memset(ptr, 0, bytes);
        return ptr;
    }

    size_t used() const { return offset_; }

private:
    uint8_t* buffer_;
    size_t size_;
    size_t offset_;
};
}  // namespace stmlib
