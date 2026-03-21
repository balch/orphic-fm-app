#include "orpheus_units.h"
#include "orpheus_engine.h"
#include "stmlib/dsp/dsp.h"
#include "stmlib/dsp/parameter_interpolator.h"
#include <cstring>
#include <algorithm>

// Ported from plaits/dsp/fx/overdrive.h (Emilie Gillet, MIT license)

struct OverdriveState {
    float pre_gain;
    float post_gain;
    bool initialized;
};

static OverdriveState& get_od_state(GraphUnit* u) {
    static_assert(sizeof(OverdriveState) <= sizeof(UnitState), "OverdriveState too large");
    return *reinterpret_cast<OverdriveState*>(&u->state);
}

void unit_process_overdrive(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate) {
    auto& s = get_od_state(u);
    if (!s.initialized) {
        s.pre_gain = 0.0f;
        s.post_gain = 0.0f;
        s.initialized = true;
    }

    float* in = u->inputs[IPORT_INPUT].buffer;
    float* out = u->output_buffers[OPORT_OUT];

    // Read drive from engine atomics (bass_overdrive + accent boost, clamped to 1.0)
    float drive = engine->bass_overdrive.load(std::memory_order_relaxed)
                + engine->bass_accent_drive_boost;
    drive = std::max(0.0f, std::min(1.0f, drive));

    // Bypass: MI algorithm mutes at drive=0 (pre_gain=0), so pass through clean signal
    if (drive < 0.001f) {
        std::memcpy(out, in, num_frames * sizeof(float));
        s.pre_gain = 0.0f;
        s.post_gain = 1.0f;
        return;
    }

    const float drive_2 = drive * drive;
    const float pre_gain_a = drive * 0.5f;
    const float pre_gain_b = drive_2 * drive_2 * drive * 24.0f;
    const float pre_gain = pre_gain_a + (pre_gain_b - pre_gain_a) * drive_2;
    const float drive_squashed = drive * (2.0f - drive);
    const float post_gain = 1.0f / stmlib::SoftClip(
        0.33f + drive_squashed * (pre_gain - 0.33f));

    stmlib::ParameterInterpolator pre_gain_mod(&s.pre_gain, pre_gain, static_cast<size_t>(num_frames));
    stmlib::ParameterInterpolator post_gain_mod(&s.post_gain, post_gain, static_cast<size_t>(num_frames));

    for (int i = 0; i < num_frames; i++) {
        float pre = pre_gain_mod.Next() * in[i];
        out[i] = stmlib::SoftClip(pre) * post_gain_mod.Next();
    }
}
