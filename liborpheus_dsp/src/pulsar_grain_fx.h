#pragma once

// Grain FX: Beads' delay-line grains over a Pulsar track's own voice. The track buffer is
// recorded into a delay line and replayed by DelayGranularPlayer, written then rendered
// per sample as beads::GranularProcessor does. Triggers and pitch picks are Mist's.

#include "pulsar_mist.h"

// pulsar_mist.h already pulled in beads/resources.h and the grain player under its macro
// guard; these headers read neither TEST nor the table macros.
#include "beads/dsp/delay_granular_player.h"
#include "beads/dsp/delay_line.h"

namespace grainfx {

constexpr float kLookbackMs = 80.0f;   // how far behind the write head a grain starts
constexpr int kMaxDelay = 96000;       // 2 s at 48 kHz
// Grain FX's own level in place of Mist's kOutGain: at the Kotlin defaults a blend-1
// cloud lands within 1 dB of the dry voice. Applied before the limiter.
constexpr float kMakeupGain = 3.3f;

// Beads size whose ComputeGrainDuration is half of its 4 s maximum, solved from its
// shortness curve: 1.025 * (1 - 1.5 * (s - 1/3)) * 108 = 12 semitones.
constexpr float kGrainFxSizeMax = 0.3333333f + (1.0f - 1.0f / (9.0f * 1.025f)) / 1.5f;

// One bank holding the line, its interpolation tail and the freeze tail Init places at
// the end of the last bank. The defaults (3 x 256 KB) would be four times this.
constexpr size_t kFreezeTailBytes = 256 * sizeof(beads::FloatFrame);
constexpr size_t kBankBytes = (kMaxDelay + 4) * sizeof(int16_t) + kFreezeTailBytes;
using Line = beads::DelayLine<beads::RESOLUTION_16_BIT, kMaxDelay, 1, kBankBytes, 1>;

static_assert(sizeof(Line::ItemType) == sizeof(int16_t), "mono 16-bit items");
static_assert(kBankBytes >= (Line::MAX_DELAY + Line::interpolation_tail) * sizeof(Line::ItemType)
                            + Line::freeze_tail_size * sizeof(beads::FloatFrame),
              "freeze tail must not overlap the line");

}  // namespace grainfx

// Per-track grain FX. Lives in PulsarState beside mist[]: Beads classes have deleted
// copy operations and the line storage is about 196 KB.
struct PulsarGrainFxState {
    grainfx::Line line;
    alignas(8) uint8_t storage[grainfx::kBankBytes + grainfx::kFreezeTailBytes];  // sized to Init's assert
    beads::DelayGranularPlayer grains;
    beads::TriggerGenerator trigger;
    beads::Limiter limiter;
    float sample_rate = 0.0f;    // 0 until the first render
    float gain_dry = 1.0f;       // smoothed blend gains
    float gain_wet = 0.0f;
    bool prev_gate = false;
    bool deferred = false;       // a trigger was withheld during the hold
    int hold_left = 0;           // samples left in the note-on hold
    uint32_t rng = 0x47524658u;
    uint32_t grains_started = 0;

    void Init(float sr) {
        sample_rate = sr;
        line.Init(storage, sizeof(storage));
        grains.Init(sr);
        trigger.Init(sr);
        InitLimiter();
        gain_dry = 1.0f;
        gain_wet = 0.0f;
        prev_gate = false;
        deferred = false;
        hold_left = 0;
    }

    void InitLimiter() { limiter.Init(1.0f / (sample_rate * 6.0f), 0.9f); }   // Mist's limiter
};

namespace grainfx {

struct Params {
    float note;
    int mode;
    int scatter_mode;
    int voicing;
    float scatter;
    float rate;
    bool random_timing;
    float shape;
    float blend;    // 0 the plain voice, 1 the cloud alone
    float size;     // grain length, a blip at 0 to 2 s at 1
};

// GrainEnvelope::Start's attack share of a grain, from 0 up to shape 1/3 to all of it at 1.
inline float attack_fraction(float shape) {
    const float s = (shape - 0.33333333f) * 1.5f;
    if (s < 0.0f) return 0.0f;
    return s < 0.5f ? s * s * s * 4.0f : s;
}

// How long the first grain after a note-on waits: until a quarter of its attack covers
// what is left of the old note in the lookback.
inline int hold_samples(float shape, float grain_samples, float sample_rate) {
    const float lookback = kLookbackMs * sample_rate / 1000.0f;
    const float hold = lookback - 0.25f * attack_fraction(shape) * grain_samples;
    return hold > 0.0f ? static_cast<int>(hold + 0.5f) : 0;
}

// Drops the recording and every grain so a cloud never replays an earlier engine or section.
inline void wake(PulsarGrainFxState& st) {
    if (st.sample_rate > 0.0f) {
        st.line.Reset();
        st.grains.Reset();
        st.trigger.Init(st.sample_rate);
        st.InitLimiter();   // its peak follower would duck the first new grains
    }
    st.prev_gate = false;
    st.deferred = false;
    st.hold_left = 0;
    st.gain_dry = 1.0f;
    st.gain_wet = 0.0f;
}

// Records `io`, then scales it in place by the dry gain. grain_mid and grain_side are the
// grains as (l+r)/2 and (l-r)/2 at the wet gain.
inline void process_block(PulsarGrainFxState& st, const Params& p, const mist::Gate& g,
                          const mist::PitchContext& pc, float sample_rate,
                          float* io, float* grain_mid, float* grain_side, int num_frames) {
    if (num_frames <= 0) return;
    if (num_frames > mist::kMaxBlock) num_frames = mist::kMaxBlock;
    if (st.sample_rate != sample_rate) st.Init(sample_rate);

    const float note = mist::clamp_note(p.note);
    const float scatter = mist::clamp_unit(p.scatter);
    const float shape = mist::clamp_unit(p.shape);

    beads::TriggerGenerator::Output triggers[mist::kMaxBlock];
    mist::render_triggers(st.trigger, st.prev_gate, g, p.mode, mist::clamp_unit(p.rate),
                          p.random_timing, triggers, num_frames);

    // The player starts a grain at time * (duration - 2 * safety) + safety behind the head.
    const float safety = static_cast<float>(beads::DelayGrain::head_crossing_safety_zone + 1);
    const float lookback = kLookbackMs * sample_rate / 1000.0f;
    const float span = static_cast<float>(st.line.duration()) - 2.0f * safety;

    beads::Parameters bp;
    mist::set_param(bp.time, (lookback - safety) / span);
    mist::set_param(bp.size, mist::kSizeMin + mist::clamp_unit(p.size) * (kGrainFxSizeMax - mist::kSizeMin));
    mist::set_param(bp.shape, shape);
    mist::set_param(bp.pitch, 0.0f);
    bp.monophonic_mode = false;
    if (p.scatter_mode == mist::kScatterRaw) {
        bp.pitch.attenuverter = scatter;   // Beads' uniform randomizer, 24 semitones
    } else if (scatter > 0.0f) {
        // A zero cv leaves the picked pitch alone but still spreads grains in the pan field.
        bp.pitch.has_cv = true;
        bp.pitch.attenuverter = -scatter;
    }

    const int off = (g.trig_off > 0 && g.trig_off < num_frames) ? g.trig_off : 0;
    const int hold = hold_samples(shape, st.grains.ComputeGrainDuration(bp.size.value()), sample_rate);

    beads::FloatFrame frames[mist::kMaxBlock];
    for (int i = 0; i < num_frames; i++) {
        // Everything a finished hold withheld collapses into one grain, taken before a
        // note-on on this same sample can arm the next hold.
        const bool fire_deferred = st.hold_left == 0 && st.deferred;
        if (fire_deferred) st.deferred = false;
        // A note-on arms the hold only when none is running, so faster notes still get
        // their deferred grain when the running hold ends.
        if (g.note_on && i == off && st.hold_left == 0) st.hold_left = hold;
        beads::TriggerGenerator::Output t = triggers[i];
        if (st.hold_left > 0) {
            st.deferred |= t.trigger;
            t.trigger = false;
            st.hold_left--;
        }
        if (fire_deferred) {
            if (!t.trigger) t.fractional_sample = 0.0f;
            t.trigger = true;
        }
        if (t.trigger) {
            // Playback-rate ratio relative to the recording, which already sounds the note.
            bp.pitch.base = mist::pick_pitch(note, p.scatter_mode, scatter, p.voicing, pc, st.rng) - note;
            st.grains_started++;
        }
        beads::FloatFrame in;
        in.l = in.r = io[i];   // the mono line stores (l + r) / 2
        st.line.Write(in);
        frames[i].l = 0.0f;
        frames[i].r = 0.0f;
        st.grains.Render(&st.line, t, bp, &frames[i]);
    }
    // Makeup before the limiter, which catches in-phase grains summing past full scale.
    for (int i = 0; i < num_frames; i++) {
        frames[i].l *= kMakeupGain;
        frames[i].r *= kMakeupGain;
    }
    st.limiter.Process(frames, num_frames);

    const float b = mist::clamp_unit(p.blend);
    const float dry_target = (1.0f - b) * (1.0f + b);
    const float wet_target = b * (2.0f - b);
    const float inv = 1.0f / static_cast<float>(num_frames);
    const float dry_step = (dry_target - st.gain_dry) * inv;
    const float wet_step = (wet_target - st.gain_wet) * inv;
    for (int i = 0; i < num_frames; i++) {
        st.gain_dry += dry_step;
        st.gain_wet += wet_step;
        io[i] *= st.gain_dry;
        grain_mid[i] = 0.5f * (frames[i].l + frames[i].r) * st.gain_wet;
        grain_side[i] = 0.5f * (frames[i].l - frames[i].r) * st.gain_wet;
    }
    st.gain_dry = dry_target;   // no float residue
    st.gain_wet = wet_target;
}

}  // namespace grainfx
