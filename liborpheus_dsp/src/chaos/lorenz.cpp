#include "chaos/lorenz.h"
#include <cmath>

namespace chaos {

namespace {
constexpr float kSigma = 10.0f;
constexpr float kBeta  = 8.0f / 3.0f;
}

float process_lorenz(ChaosVoiceState& s, float harmonics, float timbre,
                     float note_freq, float sr) {
    (void)note_freq;
    (void)sr;
    const float rho = 18.0f + harmonics * 27.0f;   // [18, 45]
    const float dt  = 0.005f + timbre * 0.05f;     // [0.005, 0.055]
    const float h   = dt * 0.25f;                  // 4× oversample
    for (int i = 0; i < 4; i++) {
        const float dx = kSigma * (s.y - s.x);
        const float dy = s.x * (rho - s.z) - s.y;
        const float dz = s.x * s.y - kBeta * s.z;
        s.x += h * dx;
        s.y += h * dy;
        s.z += h * dz;
    }
    return std::tanh(s.x * 0.05f);
}

} // namespace chaos
