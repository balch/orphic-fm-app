#pragma once

#include "orpheus_graph.h"  // kMaxFrames

#include <cstdint>
#include <cstring>

// Hands a bus written during one graph block to readers in the next block, as one
// seamless stream. Each block pops what it reads before the graph runs and pushes
// what it wrote after. Copying "the previous block's buffer" instead drops or pads
// audio whenever block sizes differ, as on Bluetooth (882 frames → 512 + 370).
//
// Latency adapts: it starts at zero and grows to the largest block seen (one block
// of 96 on the speaker, 512 on Bluetooth). The only padding is the one-off silence
// when a block needs more than is buffered.
struct SourceHandoff {
    static constexpr uint32_t kSize = 2048;  // power of two, >= 2 * kMaxFrames
    static_assert(kSize >= 2 * kMaxFrames && (kSize & (kSize - 1)) == 0);

    float ring[kSize] = {};
    uint32_t write = 0;
    uint32_t read = 0;

    void push(const float* src, int n) {
        for (int i = 0; i < n; i++) ring[(write + i) & (kSize - 1)] = src[i];
        write += n;
        if (write - read > kSize) read = write - kSize;
    }

    void pop(float* dst, int n) {
        const int avail = static_cast<int>(write - read);
        const int pad = n > avail ? n - avail : 0;
        std::memset(dst, 0, pad * sizeof(float));
        for (int i = pad; i < n; i++) dst[i] = ring[(read + i - pad) & (kSize - 1)];
        read += n - pad;
    }
};
