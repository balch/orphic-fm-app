#include "chaos/rossler.h"
#include <cmath>

namespace chaos {

namespace {
constexpr float kA = 0.2f;
constexpr float kB = 0.2f;
}

float process_rossler(ChaosVoiceState& s, float harmonics, float timbre,
                      float note_freq, float sr) {
    (void)note_freq;
    (void)sr;
    const float c  = 3.0f + harmonics * 9.0f;     // [3, 12]
    const float dt = 0.005f + timbre * 0.05f;
    const float h  = dt * 0.25f;
    for (int i = 0; i < 4; i++) {
        const float dx = -s.y - s.z;
        const float dy = s.x + kA * s.y;
        const float dz = kB + s.z * (s.x - c);
        s.x += h * dx;
        s.y += h * dy;
        s.z += h * dz;
    }
    return std::tanh(s.x * 0.15f);
}

} // namespace chaos
