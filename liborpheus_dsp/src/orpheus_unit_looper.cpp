#include "orpheus_units.h"
#include "orpheus_units_common.h"
#include "orpheus_engine.h"
#include <cmath>
#include <cstring>

// ── UNIT_LOOPER: Beat-quantized audio looper ────────────────
// IPORT_INPUT_A = audio in L (from effect chain)
// IPORT_INPUT_B = audio in R
// IPORT_INPUT_C = beat pulse (from UNIT_CLOCK, for quantization)
// OPORT_OUT      = audio out L (loop playback + passthrough)
// OPORT_OUT_RIGHT = audio out R
void unit_process_looper(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sample_rate) {
    float* in_l = u->inputs[IPORT_INPUT_A].buffer;
    float* in_r = u->inputs[IPORT_INPUT_B].buffer;
    float* in_beat = u->inputs[IPORT_INPUT_C].buffer;
    float* out_l = u->output_buffers[OPORT_OUT];
    float* out_r = u->output_buffers[OPORT_OUT_RIGHT];

    float level_target = engine->looper_level.load(std::memory_order_relaxed);
    float feedback_target = engine->looper_feedback.load(std::memory_order_relaxed);
    bool quantize = engine->looper_quantize.load(std::memory_order_relaxed) != 0;
    int requested = engine->looper_requested_state.load(std::memory_order_relaxed);

    // Check for state change request
    if (requested != engine->looper_current_state && !engine->looper_pending_transition) {
        if (quantize) {
            engine->looper_pending_transition = true;
            engine->looper_pending_state = requested;
        } else {
            engine->looper_current_state = requested;
            if (requested == 1) { // start recording
                engine->looper_position = 0;
                engine->looper_length = 0;
            }
        }
    }

    int state = engine->looper_current_state;
    int max_samples = OrpheusEngine::kMaxLoopSamples;
    float lp_coeff = smooth_coeff(sample_rate);

    for (int i = 0; i < num_frames; i++) {
        // Smooth level and feedback to prevent clicks
        engine->smooth_looper_level += lp_coeff * (level_target - engine->smooth_looper_level);
        engine->smooth_looper_feedback += lp_coeff * (feedback_target - engine->smooth_looper_feedback);
        float level = engine->smooth_looper_level;
        float feedback = engine->smooth_looper_feedback;
        // Check for beat boundary (quantized state transitions)
        if (engine->looper_pending_transition && in_beat[i] > 0.5f) {
            engine->looper_pending_transition = false;
            state = engine->looper_pending_state;
            engine->looper_current_state = state;
            if (state == 1) { // start recording
                engine->looper_position = 0;
                engine->looper_length = 0;
            }
        }

        float loop_l = 0.0f, loop_r = 0.0f;
        int pos = engine->looper_position;

        switch (state) {
            case 0: // Stop — passthrough only
                out_l[i] = in_l[i];
                out_r[i] = in_r[i];
                break;

            case 1: // Record
                if (pos < max_samples) {
                    engine->looper_buffer_l[pos] = in_l[i];
                    engine->looper_buffer_r[pos] = in_r[i];
                    engine->looper_length = pos + 1;
                    engine->looper_position = pos + 1;
                }
                out_l[i] = in_l[i]; // monitor input while recording
                out_r[i] = in_r[i];
                break;

            case 2: // Play
                if (engine->looper_length > 0) {
                    loop_l = engine->looper_buffer_l[pos] * level;
                    loop_r = engine->looper_buffer_r[pos] * level;
                    engine->looper_position = (pos + 1) % engine->looper_length;
                }
                out_l[i] = in_l[i] + loop_l;
                out_r[i] = in_r[i] + loop_r;
                break;

            case 3: // Overdub
                if (engine->looper_length > 0) {
                    loop_l = engine->looper_buffer_l[pos];
                    loop_r = engine->looper_buffer_r[pos];
                    engine->looper_buffer_l[pos] = in_l[i] + loop_l * feedback;
                    engine->looper_buffer_r[pos] = in_r[i] + loop_r * feedback;
                    engine->looper_position = (pos + 1) % engine->looper_length;
                }
                out_l[i] = in_l[i] + loop_l * level;
                out_r[i] = in_r[i] + loop_r * level;
                break;
        }
    }
}
