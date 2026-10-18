#pragma once

// Mist: a granular wavetable voice for Pulsar tracks (engine id 300), composed from
// the Beads grain player, trigger generator and wavetable oscillator. The render
// loop follows beads::Synth::Render at pichenettes/eurorack d8f4739.

#include "pulsar_mod_ranges.h"
#include "stmlib/stmlib.h"
#include "stmlib/dsp/dsp.h"   // before TEST is undefined, so Clip16 takes the portable path
#include "stmlib/utils/gate_flags.h"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>

// orpheus_dsp is built with TEST defined so stmlib takes its non-ARM paths. Beads
// reads the same macro to printf on grain stealing, which must not run on the
// audio thread.

// Beads' table macros share names with the Plaits ones and every TU sees this header,
// so they are scoped here; pop restores whichever definition was there.
#pragma push_macro("TEST")
#pragma push_macro("LUT_SINE")
#pragma push_macro("LUT_SINE_SIZE")
#pragma push_macro("WAV_INTEGRATED_WAVES")
#pragma push_macro("WAV_INTEGRATED_WAVES_SIZE")
#undef TEST
#undef LUT_SINE
#undef LUT_SINE_SIZE
#undef WAV_INTEGRATED_WAVES
#undef WAV_INTEGRATED_WAVES_SIZE
#include "beads/dsp/limiter.h"
#include "beads/dsp/trigger_generator.h"
#include "beads/dsp/wavetable_granular_player.h"
#include "beads/dsp/wavetable_voice.h"
#pragma pop_macro("WAV_INTEGRATED_WAVES_SIZE")
#pragma pop_macro("WAV_INTEGRATED_WAVES")
#pragma pop_macro("LUT_SINE_SIZE")
#pragma pop_macro("LUT_SINE")
#pragma pop_macro("TEST")

constexpr int kMistEngineId = 300;

// kEngineModRanges is [24] and cannot index 300. Harmonics is a stepped bank
// selector; an author who wants one bank pins it.
static constexpr EngineModRange kMistModRange =
    { 0.00f,1.00f, 0.00f,1.00f, 0.00f,1.00f, 12.0f, true,true, 0.0f,0.0f,0.0f, 24 };

// Per-track Mist voice. Lives in PulsarState, not PulsarTrackState: Beads classes
// have deleted copy operations.
struct PulsarMistState {
    beads::TriggerGenerator trigger;
    beads::WavetableGranularPlayer grains;
    beads::WavetableVoice osc;
    beads::Limiter limiter;
    float sample_rate = 0.0f;    // 0 until the first render
    float gain_osc = 0.0f;       // smoothed layer gains
    float gain_grain = 0.0f;
    bool prev_gate = false;
    uint32_t rng = 0x4D495354u;

    void Init(float sr) {
        sample_rate = sr;
        trigger.Init(sr);
        grains.Init(sr);
        osc.Init(sr);
        limiter.Init(1.0f / (sr * 6.0f), 0.9f);   // Synth's output limiter
        gain_osc = gain_grain = 0.0f;
        prev_gate = false;
    }
};

namespace mist {

enum Mode { kModeBurst = 0, kModeSingle = 1, kModeContinuous = 2 };
enum ScatterMode { kScatterChord = 0, kScatterScale = 1, kScatterRaw = 2 };
// Which tone a scattered grain takes. Mirrored by GrainVoicingId and GrainVoicing.
enum Voicing { kVoicingEven = 0, kVoicingCentered = 1, kVoicingRising = 2 };

// Reach of grainScatter = 1, either side of the played note.
constexpr float kScatterSemitones = 24.0f;

// Pitch classes a scattered grain may land on. Bit n is pitch class n.
struct PitchContext {
    uint16_t chord_mask = 0x0FFF;
    uint16_t scale_mask = 0x0FFF;
    int note_lo = 0;
    int note_hi = 127;
};

inline uint32_t next_random(uint32_t& s) {
    s ^= s << 13; s ^= s >> 17; s ^= s << 5;
    return s;
}

inline float random_unit(uint32_t& s) {
    return static_cast<float>(next_random(s) >> 8) * (1.0f / 16777216.0f);
}

inline int pitch_class(int note) { return ((note % 12) + 12) % 12; }

inline uint16_t pitch_class_mask(int root, const uint8_t* degrees, int count) {
    uint16_t m = 0;
    for (int i = 0; i < count; i++) m |= static_cast<uint16_t>(1u << pitch_class(root + degrees[i]));
    return m;
}

// Triad on a scale degree, built the way compute_chord_tones builds it.
inline uint16_t chord_mask(int root, int chord_degree, const uint8_t* degrees, int count) {
    const int base = ((chord_degree % 7) + 7) % 7;
    uint16_t m = 0;
    for (int step = 0; step <= 4; step += 2) {
        const int deg = (base + step) % 7;
        const int semis = (deg < count) ? degrees[deg] : 0;
        m |= static_cast<uint16_t>(1u << pitch_class(root + semis));
    }
    return m;
}

// Which candidate a grain takes. Candidates are sorted low to high. For EVEN and CENTERED
// `nearest` is the index closest to the played note; for RISING it is the lowest index allowed.
inline int choose_candidate(int count, int nearest, int voicing, uint32_t& rng) {
    if (voicing == kVoicingCentered) {
        // Weight falls one step per index either side, so the played note stays the peak
        // wherever it sits; the short side is cut off at the edge, not folded onto it.
        const int reach = std::max(nearest, count - 1 - nearest);
        const int below = nearest, above = count - 1 - nearest;
        const int total = count * (reach + 1) - below * (below + 1) / 2 - above * (above + 1) / 2;
        float r = random_unit(rng) * static_cast<float>(total);
        for (int i = 0; i < count; i++) {
            const float w = static_cast<float>(reach + 1 - std::abs(i - nearest));
            if (r < w) return i;
            r -= w;
        }
        return nearest;   // float rounding let the walk run off the end
    }
    if (voicing == kVoicingRising) {
        // The anchor and the tones above it, so the cloud never dips below the played note.
        const int above = count - nearest;
        return nearest + static_cast<int>(random_unit(rng) * static_cast<float>(above)) % above;
    }
    return static_cast<int>(random_unit(rng) * static_cast<float>(count)) % count;
}

// The pitch a newborn grain takes. RAW returns the note: Beads scatters it itself.
inline float pick_pitch(float note, int scatter_mode, float scatter, int voicing,
                        const PitchContext& c, uint32_t& rng) {
    if (scatter_mode == kScatterRaw) return note;
    const uint16_t mask = (scatter_mode == kScatterChord) ? c.chord_mask : c.scale_mask;
    const float span = scatter * kScatterSemitones;
    const int centre = static_cast<int>(std::lround(note));
    int lo = static_cast<int>(std::ceil(note - span));
    int hi = static_cast<int>(std::floor(note + span));
    if (lo < c.note_lo) lo = c.note_lo;
    if (hi > c.note_hi) hi = c.note_hi;

    static constexpr int kMaxCandidates = 49;   // 24 either side plus the note
    int candidates[kMaxCandidates];
    int count = 0, nearest = 0, best = 1 << 30, first_at_or_above = -1;
    for (int n = lo; n <= hi && count < kMaxCandidates; n++) {
        if (!((mask >> pitch_class(n)) & 1)) continue;
        const int d = std::abs(n - centre);
        if (d < best) { best = d; nearest = count; }
        if (first_at_or_above < 0 && n >= centre) first_at_or_above = count;
        candidates[count++] = n;
    }
    if (count == 0) return note;
    // RISING starts at the first tone at or above the note, which may not be the nearest.
    const int anchor = (voicing != kVoicingRising) ? nearest
                     : (first_at_or_above >= 0 ? first_at_or_above : count - 1);
    return static_cast<float>(candidates[choose_candidate(count, anchor, voicing, rng)]);
}

constexpr int kMaxBlock = 512;       // kMaxFrames
constexpr int kNumBanks = 12;        // beads::kNumBanks lives in synth.h, which is not built
constexpr float kRootNote = 48.0f;   // MIDI note of beads::kWavetableRootNote, 130.81 Hz
constexpr float kSizeMin = 1.0f / 3.0f;   // Beads' shortest forward grain, about 8 ms
constexpr float kSizeMax = 1.0f;          // 4 s; pull back if long grains smear a mix
constexpr float kOscGain = 0.5f;          // Synth::Render's post_gain_dry
constexpr float kOutGain = 0.7f;          // level against the other engines, see test_mist
constexpr float kWidth = 1.0f;            // side-channel gain at the Pulsar mix
// The low edge of Beads' tap zone (0.48..0.52). Taps report less regularity toward
// the middle, which Beads turns into pan spread; at the edge a single stays centred.
constexpr float kSingleDensity = 0.48f;

struct Params {
    float note;
    float harmonics;    // wavetable bank
    float timbre;       // position in the bank
    float morph;        // grain length
    int mode;
    int scatter_mode;
    int voicing;
    float scatter;
    float rate;
    bool random_timing;
    float shape;
    float blend;        // 0 oscillator, 1 grains
};

struct Gate {
    int trig_off = 0;            // sample where gate_post takes over from gate_pre
    bool gate_pre = false;
    bool gate_post = false;
    bool note_on = false;        // a note starts at trig_off
    const int* ticks = nullptr;  // step-boundary sample offsets in this block
    int tick_count = 0;
};

inline float clamp_unit(float x) { return x < 0.0f ? 0.0f : (x > 1.0f ? 1.0f : x); }

// Drops grains left frozen while the track ran another engine. The generator is reset
// too, so its own gate memory and prev_gate are both LOW and the next note-on has an edge.
inline void wake(PulsarMistState& st) {
    if (st.sample_rate > 0.0f) {
        st.grains.Reset();
        st.trigger.Init(st.sample_rate);
    }
    st.prev_gate = false;
}

// The pitch conversion is a table lookup, so the note must stay in the MIDI range. Taken
// by reference and tested on its bits: -ffast-math folds a by-value NaN test to false.
inline float clamp_note(const float& x) {
    uint32_t bits;
    std::memcpy(&bits, &x, sizeof bits);
    if (((bits >> 23) & 0xFFu) == 0xFFu) return 60.0f;
    return x < 0.0f ? 0.0f : (x > 127.0f ? 127.0f : x);
}

inline void set_param(beads::RandomizedParameter& rp, float base) {
    rp.base = base;
    rp.attenuverter = 0.0f;
    rp.cv = 0.0f;
    rp.has_cv = false;
}

// Renders one block. osc_out is the steady layer, which the caller envelopes;
// grain_mid and grain_side are the self-enveloped grain layer as (l+r)/2 and (l-r)/2.
inline void process_mist_block(PulsarMistState& st, const Params& p, const Gate& g,
                               const PitchContext& pc, float sample_rate,
                               float* osc_out, float* grain_mid, float* grain_side,
                               int num_frames) {
    if (num_frames <= 0) return;
    if (num_frames > kMaxBlock) num_frames = kMaxBlock;
    if (st.sample_rate != sample_rate) st.Init(sample_rate);

    const float note = clamp_note(p.note);
    const bool continuous = (p.mode == kModeContinuous);
    const float rate = clamp_unit(p.rate);
    const float scatter = clamp_unit(p.scatter);

    // Gate stream. The pulse-train path re-derives edges from the HIGH bit alone, so a
    // legato note-on is one LOW sample. The divider path reads RISING directly and leaves
    // prev_gate, the mirror of the generator's gate memory, untouched.
    stmlib::GateFlags flags[kMaxBlock];
    if (continuous) {
        std::memset(flags, 0, sizeof(stmlib::GateFlags) * num_frames);
        for (int k = 0; k < g.tick_count; k++) {
            const int at = g.ticks[k];
            if (at >= 0 && at < num_frames)
                flags[at] = stmlib::GATE_FLAG_RISING | stmlib::GATE_FLAG_HIGH;
        }
    } else {
        const int off = (g.trig_off > 0 && g.trig_off < num_frames) ? g.trig_off : 0;
        for (int i = 0; i < num_frames; i++) {
            bool high = (i < off) ? g.gate_pre : g.gate_post;
            if (g.note_on && i == off && st.prev_gate) high = false;
            flags[i] = high ? stmlib::GATE_FLAG_HIGH : stmlib::GATE_FLAG_LOW;
            st.prev_gate = high;
        }
    }

    // grainRate and grainRandomTiming are Beads' one bipolar DENSITY knob: below centre
    // is even, above is random. Centre is one grain per trigger when gated and silence
    // when clocked.
    const float density = (p.mode == kModeSingle)
        ? kSingleDensity
        : (p.random_timing ? 0.5f + 0.5f * rate : 0.5f - 0.5f * rate);
    const beads::SeedButtonState seed =
        continuous ? beads::SEED_BUTTON_STATE_LOCKED : beads::SEED_BUTTON_STATE_OFF;

    beads::TriggerGenerator::Output triggers[kMaxBlock];
    st.trigger.Render(seed, flags, density, 0.0f, 0.0f, triggers, num_frames);

    beads::Parameters bp;
    // Below 1: at exactly 1.0 Beads interpolates toward a wave row that does not exist for
    // the last bank.
    set_param(bp.time, std::min(clamp_unit(p.timbre), 0.9999f));
    set_param(bp.size, kSizeMin + clamp_unit(p.morph) * (kSizeMax - kSizeMin));
    set_param(bp.shape, clamp_unit(p.shape));
    set_param(bp.pitch, note - kRootNote);
    bp.monophonic_mode = false;
    if (p.scatter_mode == kScatterRaw) {
        bp.pitch.attenuverter = scatter;   // Beads' uniform randomizer, 24 semitones
    } else if (scatter > 0.0f) {
        // has_cv with a zero cv leaves the pitch we picked alone, but still counts
        // as randomness, which is what Beads uses to spread grains in the pan field.
        bp.pitch.has_cv = true;
        bp.pitch.attenuverter = -scatter;
    }

    int bank = static_cast<int>(clamp_unit(p.harmonics) * static_cast<float>(kNumBanks));
    if (bank > kNumBanks - 1) bank = kNumBanks - 1;

    const float b = clamp_unit(p.blend);
    const float osc_target = (1.0f - b) * (1.0f + b) * kOscGain * kOutGain;
    const float grain_target = b * (2.0f - b) * kOutGain;

    beads::FloatFrame frames[kMaxBlock];
    std::memset(frames, 0, sizeof(beads::FloatFrame) * num_frames);
    if (osc_target > 0.0f || st.gain_osc > 0.0f) {
        bool freeze[kMaxBlock] = {};
        st.osc.Render(bank, 16, freeze, bp, frames, num_frames);
    }
    const float inv = 1.0f / static_cast<float>(num_frames);
    const float osc_step = (osc_target - st.gain_osc) * inv;
    for (int i = 0; i < num_frames; i++) {
        st.gain_osc += osc_step;
        osc_out[i] = frames[i].l * st.gain_osc;
    }
    st.gain_osc = osc_target;   // no float residue, so a silent layer stops rendering

    // Grains into the same frame buffer, reused from zero.
    const float root_pitch = note;
    for (int i = 0; i < num_frames; i++) {
        if (triggers[i].trigger) {
            bp.pitch.base = pick_pitch(root_pitch, p.scatter_mode, scatter, p.voicing, pc, st.rng) - kRootNote;
        }
        frames[i].l = 0.0f;
        frames[i].r = 0.0f;
        st.grains.Render(bank, 16, triggers[i], false, bp, &frames[i]);
    }
    // 32 in-phase grains sum to several times full scale; Synth limits here too.
    st.limiter.Process(frames, num_frames);

    const float grain_step = (grain_target - st.gain_grain) * inv;
    for (int i = 0; i < num_frames; i++) {
        st.gain_grain += grain_step;
        grain_mid[i] = 0.5f * (frames[i].l + frames[i].r) * st.gain_grain;
        grain_side[i] = 0.5f * (frames[i].l - frames[i].r) * st.gain_grain;
    }
    st.gain_grain = grain_target;
}

}  // namespace mist
