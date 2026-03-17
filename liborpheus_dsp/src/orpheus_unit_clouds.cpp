#include "orpheus_units.h"
#include "orpheus_engine.h"
#include <cmath>
#include <cstring>
#include <algorithm>

void unit_process_clouds(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr) {
    float* in_l = u->inputs[IPORT_INPUT_A].buffer;
    float* in_r = u->inputs[IPORT_INPUT_B].buffer;
    float* out_l = u->output_buffers[OPORT_OUT];
    float* out_r = u->output_buffers[OPORT_OUT_RIGHT];

    if (engine->clouds_bypass.load(std::memory_order_relaxed)) {
        std::memcpy(out_l, in_l, num_frames * sizeof(float));
        std::memcpy(out_r, in_r, num_frames * sizeof(float));
        return;
    }

    // Viz: grains input peak
    {
        float peak = 0.0f;
        for (int i = 0; i < num_frames; i++) {
            float a = std::fabs(in_l[i]);
            if (a > peak) peak = a;
        }
        engine->viz_rings[VIZ_GRAINS_IN].write(peak);
    }

    auto* p = engine->clouds_processor.mutable_parameters();
    p->position = engine->clouds_position.load(std::memory_order_relaxed);
    p->size = engine->clouds_size.load(std::memory_order_relaxed);
    // Kotlin sends pitch as -1..1 normalized; MI Clouds expects semitones
    p->pitch = engine->clouds_pitch.load(std::memory_order_relaxed) * 24.0f;
    // MI Clouds has a dead zone at density 0.47-0.53 where overlap=0 and
    // no grains spawn (silence). On hardware ADC noise avoids this; remap
    // the 0..1 range to skip it: 0..0.5 → 0..0.47, 0.5..1 → 0.53..1.0
    {
        float d = engine->clouds_density.load(std::memory_order_relaxed);
        if (d <= 0.5f) {
            p->density = d * 0.94f;
        } else {
            p->density = 0.53f + (d - 0.5f) * 0.94f;
        }
    }
    p->texture = engine->clouds_texture.load(std::memory_order_relaxed);
    p->dry_wet = engine->clouds_dry_wet.load(std::memory_order_relaxed);
    p->feedback = engine->clouds_feedback.load(std::memory_order_relaxed);
    p->reverb = engine->clouds_reverb.load(std::memory_order_relaxed);
    p->freeze = engine->clouds_freeze.load(std::memory_order_relaxed) != 0;
    p->trigger = engine->clouds_trigger.exchange(0, std::memory_order_relaxed) != 0;

    engine->clouds_processor.set_playback_mode(
        static_cast<clouds::PlaybackMode>(
            engine->clouds_mode.load(std::memory_order_relaxed)));
    engine->clouds_processor.Prepare();

    int frames_done = 0;
    while (frames_done < num_frames) {
        int block = std::min(static_cast<int>(clouds::kMaxBlockSize),
                             num_frames - frames_done);

        clouds::ShortFrame in_frames[clouds::kMaxBlockSize];
        clouds::ShortFrame out_frames[clouds::kMaxBlockSize];

        for (int i = 0; i < block; i++) {
            float l = std::max(-1.0f, std::min(1.0f, in_l[frames_done + i]));
            float r = std::max(-1.0f, std::min(1.0f, in_r[frames_done + i]));
            in_frames[i].l = static_cast<short>(l * 32767.0f);
            in_frames[i].r = static_cast<short>(r * 32767.0f);
        }

        engine->clouds_processor.Process(in_frames, out_frames, block);

        const float inv = 1.0f / 32768.0f;
        for (int i = 0; i < block; i++) {
            out_l[frames_done + i] = out_frames[i].l * inv;
            out_r[frames_done + i] = out_frames[i].r * inv;
        }

        frames_done += block;
    }

    // Viz: grains output peak
    {
        float peak = 0.0f;
        for (int i = 0; i < num_frames; i++) {
            float a = std::fabs(out_l[i]);
            if (a > peak) peak = a;
        }
        engine->viz_rings[VIZ_GRAINS_OUT].write(peak);
    }

    // Attenuate Clouds dry path only when DRUMS is the Warps CARRIER.
    // Clouds processes synth audio (not drums), but its output feeds into
    // the same master mix — attenuate so Warps wet can replace the carrier.
    if (!engine->warps_bypass.load(std::memory_order_relaxed)) {
        float mix = engine->warps_smooth_mix;
        if (mix > 0.001f) {
            int c = engine->warps_carrier_source.load(std::memory_order_relaxed);
            if (c == 1) {  // DRUMS is carrier
                float scale = 1.0f - mix;
                for (int i = 0; i < num_frames; i++) {
                    out_l[i] *= scale;
                    out_r[i] *= scale;
                }
            }
        }
    }
}
