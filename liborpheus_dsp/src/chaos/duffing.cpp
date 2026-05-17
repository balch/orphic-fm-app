#include "chaos/duffing.h"
#include <cmath>

namespace chaos {

namespace {
constexpr float kDelta = 0.3f;
constexpr float kAlpha = -1.0f;
constexpr float kBetaCoeff = 1.0f;
constexpr float kTwoPi = 6.28318530717958647692f;
}

float process_duffing(ChaosVoiceState& s, float harmonics, float timbre,
                      float note_freq, float sr) {
    const float gamma = harmonics * 0.5f;            // [0, 0.5]
    const float dt    = 0.005f + timbre * 0.05f;
    const float h     = dt * 0.25f;
    const float phase_inc = (note_freq / sr) * 0.25f;
    for (int i = 0; i < 4; i++) {
        const float drive = gamma * std::cos(kTwoPi * s.drive_phase);
        const float dx = s.y;
        const float dy = -kDelta * s.y - kAlpha * s.x
                         - kBetaCoeff * s.x * s.x * s.x + drive;
        s.x += h * dx;
        s.y += h * dy;
        s.drive_phase += phase_inc;
        if (s.drive_phase >= 1.0f) s.drive_phase -= 1.0f;
    }
    return std::tanh(s.x * 0.7f);
}

} // namespace chaos
