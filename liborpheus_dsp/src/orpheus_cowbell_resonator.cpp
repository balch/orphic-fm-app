#include "orpheus_cowbell_resonator.h"

#include "rings/dsp/part.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <new>

namespace {

constexpr int kReverbBufferSize = 32768;
constexpr float kSilencePeak = 1e-4f;
constexpr int kPrimeBlocks = 20;  // > the note filter's 16-entry delay, plus its first jump
// Part::Init's note filter: a 4 ms delay at the control rate, capped at 15 entries.
constexpr float kNoteFilterDelaySeconds = 0.004f;
constexpr float kDcCutoffHz = 10.0f;
constexpr float kLutParamMax = 0.9995f;
constexpr float kKnee = 0.9f;  // outputs stay linear below this and approach 1.0 above it
constexpr float kMaxBendSemitones = 36.0f;  // the bender's 24 st times its 1.5 curve peak

inline float clamp01(float v) { return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v); }

}  // namespace

OrpheusCowbellResonator::OrpheusCowbellResonator() = default;
OrpheusCowbellResonator::~OrpheusCowbellResonator() = default;

void OrpheusCowbellResonator::init(float sample_rate) {
    // Process-wide: must precede Part::Init, and never change under a live instance.
    rings::SetSampleRate(sample_rate);

    // Part's constructor and Init leave some state unset (reverb filters, step counter), so
    // start from zeroed memory. operator new pairs with the default deleter's delete.
    void* mem = ::operator new(sizeof(rings::Part));
    std::memset(mem, 0, sizeof(rings::Part));
    part_.reset(new (mem) rings::Part());
    reverb_buffer_.reset(new uint16_t[kReverbBufferSize]());
    part_->Init(reverb_buffer_.get());
    // Polyphony stays at Init's 1 until prime(), so new voices copy the primed note.
    part_->set_model(static_cast<rings::ResonatorModel>(pending_model_));

    glide_coefficient_ = 1.0f - std::exp(-static_cast<float>(kSubBlock) / (kGlideSeconds * sample_rate));
    // One block jumps the filter to the note, then one write per delay entry and one spare.
    const int delay = std::min(15, static_cast<int>(kNoteFilterDelaySeconds * sample_rate / kSubBlock));
    fill_blocks_ = delay + 2;
    std::copy(target_, target_ + kNumParams, current_);
    num_strikes_ = 0;
    sub_block_phase_ = 0;
    sub_block_peak_ = 0.0f;
    last_sub_block_peak_ = 0.0f;
    hit_brightness_ = 0.0f;
    dc_pole_ = 1.0f - 2.0f * 3.14159265f * kDcCutoffHz / sample_rate;
    for (int c = 0; c < 2; ++c) dc_x_[c] = dc_y_[c] = 0.0f;
    // Here, not on the audio thread. Settings made later reach a silent bell by the
    // from-silence jump at its first strike.
    prime();
}

void OrpheusCowbellResonator::finish_output(float* x, int size, int channel) {
    if (!conditioning_) return;
    float x1 = dc_x_[channel];
    float y1 = dc_y_[channel];
    for (int i = 0; i < size; ++i) {
        const float y = x[i] - x1 + dc_pole_ * y1;
        x1 = x[i];
        y1 = y;
        // Re-centring a lopsided FM drone can peak past 1, so overs bend into a soft knee.
        const float a = std::fabs(y);
        if (a > kKnee) {
            const float t = (a - kKnee) / (1.0f - kKnee);
            x[i] = std::copysign(kKnee + (1.0f - kKnee) * t / (1.0f + t), y);
        } else {
            x[i] = y;
        }
    }
    dc_x_[channel] = x1;
    dc_y_[channel] = y1;
}

void OrpheusCowbellResonator::prime() {
    // The note filter hands an outgoing voice the note it held ~4 ms ago, read from a delay
    // line that starts at zero (MIDI 0). Fill it with silent blocks so no voice drops to 8 Hz.
    // Voices added by set_polyphony start at voice 0's note, so that waits for the last block.
    rings::PerformanceState ps = {};
    ps.note = note_;
    rings::Patch patch = {current_[kStructure], current_[kBrightness], current_[kDamping],
                          current_[kPosition]};
    float out[kSubBlock];
    std::memset(in_scratch_, 0, sizeof(in_scratch_));
    for (int i = 0; i < kPrimeBlocks; ++i) {
        if (i == kPrimeBlocks - 1) part_->set_polyphony(pending_polyphony_);
        part_->Process(ps, patch, in_scratch_, out, aux_scratch_, kSubBlock);
        // A droning FM voice (damping near 1) also settles the DC blockers here.
        finish_output(out, kSubBlock, 0);
        finish_output(aux_scratch_, kSubBlock, 1);
    }
    filled_note_ = last_note_ = note_;
    filled_steps_ = fill_blocks_;
}

void OrpheusCowbellResonator::fill_note_filter() {
    rings::PerformanceState ps = {};
    ps.internal_exciter = false;
    ps.note = note_;
    ps.fm = current_[kBend];
    rings::Patch patch = {current_[kStructure], current_[kBrightness], current_[kDamping],
                          current_[kPosition]};
    float out[kSubBlock];
    std::memset(in_scratch_, 0, sizeof(in_scratch_));
    for (int i = 0; i < fill_blocks_; ++i) part_->Process(ps, patch, in_scratch_, out, aux_scratch_, kSubBlock);
    filled_note_ = last_note_ = note_;
    filled_steps_ = fill_blocks_;
}

void OrpheusCowbellResonator::set_model(int model) {
    pending_model_ = std::min(std::max(model, 0), kNumModels - 1);
}

void OrpheusCowbellResonator::set_polyphony(int voices) {
    pending_polyphony_ = voices >= 4 ? 4 : (voices >= 2 ? 2 : 1);
}

void OrpheusCowbellResonator::set_note(float midi_note) { note_ = midi_note; }
void OrpheusCowbellResonator::set_structure(float v) { set_param(kStructure, v); }
void OrpheusCowbellResonator::set_brightness(float v) { set_param(kBrightness, v); }
void OrpheusCowbellResonator::set_damping(float v) { set_param(kDamping, v); }
void OrpheusCowbellResonator::set_position(float v) { set_param(kPosition, v); }
void OrpheusCowbellResonator::set_bend_semitones(float st) {
    target_[kBend] = std::isfinite(st) ? std::min(std::max(st, -kMaxBendSemitones), kMaxBendSemitones) : 0.0f;
}

void OrpheusCowbellResonator::set_param(Param p, float v) {
    // Structure and damping index lookup tables with interpolation, and at exactly 1.0 they
    // read one entry past the end (the firmware caps structure at 0.9995 for this).
    const float hi = (p == kStructure || p == kDamping) ? kLutParamMax : 1.0f;
    target_[p] = std::min(clamp01(v), hi);
}

void OrpheusCowbellResonator::strike(float velocity, int offset, float brightness_offset) {
    Strike s = {clamp01(velocity), std::max(offset, 0),
                std::isfinite(brightness_offset) ? std::min(std::max(brightness_offset, -1.0f), 1.0f) : 0.0f};
    if (num_strikes_ < kMaxStrikes) {
        strikes_[num_strikes_++] = s;
        return;
    }
    // Full: the new strike replaces the quietest pending one if it is louder.
    int quietest = 0;
    for (int i = 1; i < num_strikes_; ++i) {
        if (strikes_[i].velocity < strikes_[quietest].velocity) quietest = i;
    }
    if (s.velocity > strikes_[quietest].velocity) strikes_[quietest] = s;
}

int OrpheusCowbellResonator::model() const {
    return part_ ? static_cast<int>(part_->model()) : pending_model_;
}

int OrpheusCowbellResonator::polyphony() const {
    return part_ ? static_cast<int>(part_->polyphony()) : pending_polyphony_;
}

void OrpheusCowbellResonator::render_piece(const float* in, float* out, float* aux, int size,
                                           float impulse, bool strum, bool from_silence) {
    rings::PerformanceState ps = {};
    ps.internal_exciter = false;  // strikes arrive as impulses on the input
    ps.tonic = 0.0f;              // note + tonic sounds at its true pitch (69 = 440 Hz)
    ps.note = note_;
    ps.fm = current_[kBend];      // Rings adds it to every voice's own note, unfiltered
    rings::Patch patch = {current_[kStructure], clamp01(current_[kBrightness] + hit_brightness_),
                          current_[kDamping], current_[kPosition]};

    // Model and polyphony changes re-init the voices, so they wait for a strike. A strike from
    // silence re-inits them too (set_polyphony always marks them dirty), so oscillator phases
    // and Rings' own per-block ramps don't carry settings from before the silence.
    const bool changed = static_cast<int>(part_->model()) != pending_model_ ||
                         part_->polyphony() != pending_polyphony_;
    // The note filter hands an outgoing voice the note it held ~4 ms ago. If the note moved
    // while the bell was silent, refill it first, or a quick second strike retunes this voice.
    if (strum && from_silence && filled_note_ != note_) fill_note_filter();
    if (strum && (changed || from_silence)) {
        part_->set_model(static_cast<rings::ResonatorModel>(pending_model_));
        part_->set_polyphony(pending_polyphony_);
        // Fresh voices ramp their filters in from zero over one block, which would swallow
        // the strike's impulse; run that block silently first (output discarded).
        float settle[kSubBlock];
        std::memset(in_scratch_, 0, sizeof(in_scratch_));
        part_->Process(ps, patch, in_scratch_, settle, aux_scratch_, kSubBlock);
    }

    if (in) {
        std::memcpy(in_scratch_, in, sizeof(float) * size);
    } else {
        std::memset(in_scratch_, 0, sizeof(float) * size);
    }
    in_scratch_[0] += impulse;
    ps.strum = strum;
    part_->Process(ps, patch, in_scratch_, out, aux, static_cast<size_t>(size));
    // A note change jumps the filter, leaving its delay line mixed; only non-strum blocks write it.
    if (note_ != last_note_) {
        filled_steps_ = 0;
        filled_note_ = -1.0f;
    } else if (!strum && filled_steps_ < fill_blocks_ && ++filled_steps_ == fill_blocks_) {
        filled_note_ = note_;
    }
    last_note_ = note_;
    finish_output(out, size, 0);
    finish_output(aux, size, 1);

    float peak = sub_block_peak_;
    for (int i = 0; i < size; ++i) {
        peak = std::max(peak, std::max(std::fabs(out[i]), std::fabs(aux[i])));
    }
    sub_block_peak_ = peak;
}

void OrpheusCowbellResonator::process(const float* in, float* out, float* aux, int num_frames) {
    if (!part_ || num_frames <= 0) {
        if (num_frames > 0) {
            std::memset(out, 0, sizeof(float) * num_frames);
            if (aux) std::memset(aux, 0, sizeof(float) * num_frames);
        }
        num_strikes_ = 0;
        return;
    }
    // Order pending strikes by offset (at most 8, insertion sort); late ones land on the last frame.
    for (int i = 0; i < num_strikes_; ++i) {
        strikes_[i].offset = std::min(strikes_[i].offset, num_frames - 1);
    }
    for (int i = 1; i < num_strikes_; ++i) {
        Strike s = strikes_[i];
        int j = i - 1;
        while (j >= 0 && strikes_[j].offset > s.offset) {
            strikes_[j + 1] = strikes_[j];
            --j;
        }
        strikes_[j + 1] = s;
    }

    int next = 0;
    int pos = 0;
    while (pos < num_frames) {
        if (sub_block_phase_ == 0) {
            // One glide step per 24-frame sub-block; from silence, jump to the targets.
            last_sub_block_peak_ = sub_block_peak_;
            sub_block_peak_ = 0.0f;
            const bool silent = last_sub_block_peak_ < kSilencePeak;
            for (int p = 0; p < kNumParams; ++p) {
                current_[p] = silent ? target_[p]
                                     : current_[p] + glide_coefficient_ * (target_[p] - current_[p]);
            }
            hit_brightness_ = silent ? 0.0f : hit_brightness_ * (1.0f - glide_coefficient_);
        }

        // A piece ends at the sub-block edge, the call's end, or the next strike.
        int end = std::min(num_frames, pos + (kSubBlock - sub_block_phase_));
        float impulse = 0.0f;
        float colour = 0.0f;
        bool strum = false;
        while (next < num_strikes_ && strikes_[next].offset == pos) {
            if (!strum || strikes_[next].velocity > impulse) colour = strikes_[next].brightness_offset;
            impulse = std::max(impulse, strikes_[next].velocity);  // same frame: keep the loudest
            strum = true;
            ++next;
        }
        if (strum) hit_brightness_ = colour;  // each hit sets its own, 0 included
        if (next < num_strikes_ && strikes_[next].offset < end) end = strikes_[next].offset;

        // A strike off the grid starts mid sub-block, so the silence jump is checked here too.
        const bool from_silence =
            strum && std::max(sub_block_peak_, last_sub_block_peak_) < kSilencePeak;
        if (from_silence) std::copy(target_, target_ + kNumParams, current_);

        const int size = end - pos;
        render_piece(in ? in + pos : nullptr, out + pos, aux ? aux + pos : aux_scratch_, size, impulse,
                     strum, from_silence);
        pos = end;
        sub_block_phase_ = (sub_block_phase_ + size) % kSubBlock;
    }
    num_strikes_ = 0;
}
