#include "orpheus_units.h"
#include "orpheus_engine.h"
#include <cmath>
#include <cstring>
#include <algorithm>

struct CompressorState {
    float envelope;
    bool initialized;
};

static CompressorState& get_comp_state(GraphUnit* u) {
    static_assert(sizeof(CompressorState) <= sizeof(UnitState), "CompressorState too large");
    return *reinterpret_cast<CompressorState*>(&u->state);
}

void unit_process_compressor(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate) {
    auto& s = get_comp_state(u);
    if (!s.initialized) {
        s.envelope = 0.0f;
        s.initialized = true;
    }

    float* in = u->inputs[IPORT_INPUT].buffer;
    float* out = u->output_buffers[OPORT_OUT];

    float amount = engine->bass_compressor.load(std::memory_order_relaxed);

    if (amount < 0.001f) {
        std::memcpy(out, in, num_frames * sizeof(float));
        return;
    }

    amount = std::min(amount, 1.0f);

    const float threshold = 1.0f - amount * 0.9f;
    const float ratio = 1.0f + amount * 9.0f;
    const float inv_ratio = 1.0f / ratio;

    const float attack_coeff = expf(-1.0f / (0.001f * sample_rate));
    const float release_coeff = expf(-1.0f / (0.050f * sample_rate));

    const float knee_db = 6.0f;

    for (int i = 0; i < num_frames; i++) {
        float x = in[i];
        float abs_x = fabsf(x);

        float coeff = (abs_x > s.envelope) ? attack_coeff : release_coeff;
        s.envelope = s.envelope * coeff + abs_x * (1.0f - coeff);

        float env_db = 20.0f * log10f(std::max(s.envelope, 1e-10f));
        float thresh_db = 20.0f * log10f(std::max(threshold, 1e-10f));

        float gain_db = 0.0f;
        float diff = env_db - thresh_db;
        if (diff <= -knee_db * 0.5f) {
            gain_db = 0.0f;
        } else if (diff >= knee_db * 0.5f) {
            gain_db = diff * (inv_ratio - 1.0f);
        } else {
            float knee_factor = (diff + knee_db * 0.5f) / knee_db;
            gain_db = knee_factor * knee_factor * diff * (inv_ratio - 1.0f);
        }

        float gain = powf(10.0f, gain_db / 20.0f);
        out[i] = x * gain;
    }
}
