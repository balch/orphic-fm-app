#include "chaos/henon.h"
#include <cmath>

namespace chaos {

namespace {
constexpr float kB = 0.3f;
}

float process_henon(ChaosVoiceState& s, float harmonics, float timbre,
                    float note_freq, float sr) {
    (void)note_freq;
    (void)sr;
    const float a = 1.0f + harmonics * 0.4f;             // [1.0, 1.4]
    const int updates = 1 + static_cast<int>(timbre * 7.0f);  // 1..8
    for (int i = 0; i < updates; i++) {
        const float x_new = 1.0f - a * s.x * s.x + s.y;
        const float y_new = kB * s.x;
        s.x = x_new;
        s.y = y_new;
    }
    return std::tanh(s.x * 0.8f);
}

} // namespace chaos
