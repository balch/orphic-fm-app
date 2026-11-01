#pragma once

// OrpheusCowbellResonator: the six-model MI resonator (24-sample blocks) wrapped for
// variable engine blocks, with velocity strikes at a frame offset and gliding controls.
// Rings headers stay in the .cpp: they collide with Plaits' resource macros.

#include <cstdint>
#include <memory>

namespace rings {
class Part;
}

class OrpheusCowbellResonator {
public:
    static constexpr int kNumModels = 6;  // modal, sympathetic, string, fm, quantized sympathetic, string+reverb
    static constexpr int kMaxStrikes = 8;
    static constexpr int kSubBlock = 24;  // the resonator's own block size
    static constexpr float kGlideSeconds = 0.015f;  // control glide, stepped once per sub-block

    OrpheusCowbellResonator();
    ~OrpheusCowbellResonator();

    void init(float sample_rate);  // allocates Part + reverb buffer and primes it; not audio-thread safe
    void set_model(int model);     // clamped 0..5; applied at the next strike
    void set_polyphony(int voices);  // 1, 2 or 4; applied at the next strike
    void set_note(float midi_note);
    void set_structure(float v);  // all four: 0..1 targets, slewed
    void set_brightness(float v);
    void set_damping(float v);
    void set_position(float v);
    void set_bend_semitones(float st);  // every voice, ringing ones too; slewed like the four above
    // velocity 0..1; offset = frame index into the next process(). brightness_offset (-1..1) rides
    // on BRIGHTNESS from this strike and glides back to it; 0 leaves the sound as it was.
    void strike(float velocity, int offset, float brightness_offset = 0.0f);
    void process(const float* in, float* out, float* aux, int num_frames);  // in and aux may be null
    int model() const;  // the model currently sounding
    int polyphony() const;  // the voice count currently sounding
    float bend_semitones() const { return current_[kBend]; }  // the glided bend now sounding
    // DC blocker + soft knee on both outputs (on by default). Tests turn it off to check the
    // resonator's raw level and DC, which the stage would otherwise hide.
    void set_output_conditioning(bool on) { conditioning_ = on; }

private:
    struct Strike {
        float velocity;
        int offset;
        float brightness_offset;
    };
    enum Param { kStructure, kBrightness, kDamping, kPosition, kBend, kNumParams };

    void set_param(Param p, float v);
    void prime();
    void fill_note_filter();  // silent blocks at note_, so outgoing voices are handed note_
    void finish_output(float* x, int size, int channel);  // DC blocker + soft knee
    void render_piece(const float* in, float* out, float* aux, int size, float impulse, bool strum,
                      bool from_silence);

    std::unique_ptr<rings::Part> part_;
    std::unique_ptr<uint16_t[]> reverb_buffer_;

    float glide_coefficient_ = 1.0f;
    float target_[kNumParams] = {0.25f, 0.5f, 0.5f, 0.5f, 0.0f};
    float current_[kNumParams] = {0.25f, 0.5f, 0.5f, 0.5f, 0.0f};
    float note_ = 60.0f;
    float hit_brightness_ = 0.0f;  // the last strike's brightness offset, gliding back to 0
    // The note the filter's delay line holds throughout (-1 = mixed), and the non-strum blocks
    // processed at note_ since it last changed.
    float filled_note_ = -1.0f;
    float last_note_ = -1.0f;
    int filled_steps_ = 0;
    int fill_blocks_ = 0;
    int pending_model_ = 0;
    int pending_polyphony_ = 1;

    Strike strikes_[kMaxStrikes] = {};
    int num_strikes_ = 0;

    // Position inside the current 24-frame sub-block, carried across process() calls.
    int sub_block_phase_ = 0;
    float sub_block_peak_ = 0.0f;
    float last_sub_block_peak_ = 0.0f;

    // The FM model with positive feedback holds a steady offset (up to ~0.5), so both
    // outputs pass a one-pole DC blocker, then a soft knee above 0.9.
    bool conditioning_ = true;
    float dc_pole_ = 0.999f;
    float dc_x_[2] = {};
    float dc_y_[2] = {};

    float in_scratch_[kSubBlock] = {};
    float aux_scratch_[kSubBlock] = {};
};
