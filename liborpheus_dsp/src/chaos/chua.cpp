#include "chaos/chua.h"
#include <cmath>

namespace chaos {

namespace {
constexpr float kBeta = 15.0f;
constexpr float kM0   = -1.0f / 7.0f;
constexpr float kM1   =  2.0f / 7.0f;

inline float chua_diode(float x) {
    return kM1 * x + 0.5f * (kM0 - kM1) * (std::fabs(x + 1.0f) - std::fabs(x - 1.0f));
}
}

float process_chua(ChaosVoiceState& s, float harmonics, float timbre,
                   float note_freq, float sr) {
    (void)note_freq;
    (void)sr;
    const float alpha = 8.0f + harmonics * 8.0f;   // [8, 16]
    const float dt    = 0.005f + timbre * 0.05f;
    const float h     = dt * 0.25f;
    for (int i = 0; i < 4; i++) {
        const float dx = alpha * (s.y - s.x - chua_diode(s.x));
        const float dy = s.x - s.y + s.z;
        const float dz = -kBeta * s.y;
        s.x += h * dx;
        s.y += h * dy;
        s.z += h * dz;
    }
    return std::tanh(s.x * 0.6f);
}

} // namespace chaos
