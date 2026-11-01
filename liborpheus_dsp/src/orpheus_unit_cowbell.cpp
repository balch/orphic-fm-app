#include "orpheus_units.h"
#include "orpheus_engine.h"
#include "cowbell_macros.h"
#include "kotlin_engine_ordinal.h"
#include "orpheus_units_common.h"
#include "orpheus_unit_pulsar.h"
#include <algorithm>
#include <cmath>
#include <cstring>

namespace {

constexpr int kSlots = OrpheusCowbellResonator::kMaxStrikes;
constexpr int kIdleBlocks = 8;
constexpr float kIdlePeak = 1e-5f;
constexpr float kBaseNote = 72.0f;  // C5: the root pitch class sounds in octave 5
constexpr float kEngineImpulseScale = 0.35f;  // the impulse under an engine's hit
constexpr float kGateVelocity = 0.8f;         // SYNTH's held note: accent and its strike
// The VCA's full level (SYNTH held, or a TAP at velocity 1). A tone at the bell's pitch builds
// up in the resonator; above ~0.2 it drives Rings' slow limiter, whose recovery stretches the release.
constexpr float kVcaLevel = 0.1f;
constexpr float kHoldsSynthLevel = 0.02f;  // six-op under SYNTH: at full INTENSITY it builds up ~4x VA
constexpr float kTapGateSeconds = 0.005f;
constexpr float kVcaAttackSeconds = 0.001f;
constexpr float kVcaReleaseSeconds = 0.006f;  // shut (below kVcaFloor) ~60 ms after a gate falls
constexpr float kVcaFloor = 1e-5f;
// SYNTH noise for engines whose hit decays: band-limited, and trimmed as RING raises the
// resonator's Q so it stays under Rings' limiter at full INTENSITY and any RING.
constexpr float kSynthNoiseLevel = 0.35f;
constexpr float kNoiseRingTrim = 0.9f;  // level x (1 - 0.9 x RING)
constexpr float kNoiseCutoffHz = 1000.0f;
constexpr float kNoiseRampSeconds = 0.003f;
constexpr float kExciterDrive = 0.2f;  // an HH hit peaks ~5 dB over the bare impulse
constexpr float kExciterKnob = 0.5f;  // harmonics, timbre and morph, as the drum voices default
// A fill's noise burst: this peak amplitude per unit of velocity and INTENSITY, after the band-pass
// is levelled to the noise's own RMS. The resonator's input scales it down by 8 again.
constexpr float kBurstDrive = 0.1f;
constexpr float kBurstMaxCentreFraction = 0.45f;  // of the sample rate, under Nyquist
constexpr float kBendSmoothSeconds = 0.008f;
constexpr float kScaleGlideSeconds = 0.03f;  // the snapped target's own smoother; tune by ear
// MORE boosts the bell by 1 + kMoreBoost x MORE (+9 dB at full) and its output envelope ducks
// the DJ bus by up to kMoreDuckDb x MORE. Tune by ear.
constexpr float kMoreBoost = 1.8f;
constexpr float kMoreDuckDb = 12.0f;
constexpr float kMoreDuckAttackSeconds = 0.005f;
constexpr float kMoreDuckReleaseSeconds = 0.150f;
constexpr float kMoreDuckKeyPeak = 0.25f;  // envelope that ducks fully; quieter rings duck less
constexpr float kBendRangeSemitones = 24.0f;  // the global bender's MAX_BEND
constexpr float kMaxBentNote = 120.0f;         // MIDI, about 8.4 kHz
// Stereo width at polyphony 2/4 (1 = Rings' hard ping-pong); a lone tap lands within 3 dB of centre.
constexpr float kPolyWidth = 0.15f;

inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
// The global bender's tension curve: 1.5 at full pull.
inline float bend_curve(float b) { return b * (1.0f + std::fabs(b) * 0.5f); }
// Semitones for a curve value, capped so the bent note stays below Nyquist's silence.
inline float bent_semitones(float curve, float note) {
    return std::fmin(curve * kBendRangeSemitones, kMaxBentNote - note);
}
// Stacked strikes and bursts can pile the resonator up to its own ceiling, and the makeup gain
// lifts that past full scale. Linear below kCeilingKnee, then a tanh shoulder that never reaches kCeiling.
constexpr float kCeilingKnee = 0.8f;
constexpr float kCeiling = 0.98f;
inline float soft_ceiling(float x) {
    const float a = std::fabs(x);
    if (a <= kCeilingKnee) return x;
    const float y = kCeilingKnee + (kCeiling - kCeilingKnee) * std::tanh((a - kCeilingKnee) / (kCeiling - kCeilingKnee));
    return x < 0.0f ? -y : y;
}
// One gain from the louder channel, applied to both, so a one-sided peak does not shift the image.
inline float ceiling_gain(float l, float r) {
    const float m = std::fmax(std::fabs(l), std::fabs(r));
    return m <= kCeilingKnee ? 1.0f : soft_ceiling(m) / m;
}
inline uint32_t vel7(uint32_t packed) { return packed & 0x7Fu; }

CowbellPushResult push_strike(std::atomic<uint32_t>* slots, uint32_t packed, uint32_t* overwritten) {
    if (!(packed & kCowbellStrikeValid)) return COWBELL_PUSH_DROPPED;
    // A few passes at most: a lost race against the drain or another pusher retries, and a
    // strike still unplaced after that is dropped rather than spinning.
    for (int attempt = 0; attempt < 4; ++attempt) {
        int quietest = -1;
        uint32_t quietest_word = 0;
        for (int i = 0; i < kSlots; ++i) {
            uint32_t w = slots[i].load(std::memory_order_acquire);
            if (w == 0 && slots[i].compare_exchange_strong(w, packed, std::memory_order_acq_rel,
                                                           std::memory_order_acquire)) {
                return COWBELL_PUSH_PLACED;
            }
            if (w != 0 && (quietest < 0 || vel7(w) < vel7(quietest_word))) {
                quietest = i;
                quietest_word = w;
            }
        }
        if (quietest < 0) continue;  // every slot emptied mid-scan
        if (vel7(quietest_word) >= vel7(packed)) return COWBELL_PUSH_DROPPED;
        // ABA: if the slot was drained and refilled with the identical word, this still
        // succeeds and costs at most one equally quiet strike.
        if (slots[quietest].compare_exchange_strong(quietest_word, packed, std::memory_order_acq_rel,
                                                    std::memory_order_acquire)) {
            *overwritten = quietest_word;
            return COWBELL_PUSH_REPLACED;
        }
    }
    return COWBELL_PUSH_DROPPED;
}

// An exciter trigger: a TAP strike (5 ms gate) or the SYNTH gate's rising edge.
struct ExciterHit {
    int offset;
    float velocity;
    bool tap;
};
// Strikes sounding this block: drained ones, GRID-pending ones landing here, and the gate edge.
constexpr int kMaxFired = 2 * kSlots;
constexpr int kMaxExciterHits = kMaxFired + 1;
constexpr float kJustAfterFraction = 0.25f;  // of a 16th: a strike this soon after a line plays now
// Below this SNAP, GRID passes hits straight through and claims no slot: a pull this small is
// inaudible, and spending the slot would turn the knob into one hit per 16th.
constexpr float kGridPassThroughStrength = 0.15f;

// How an engine's output is bounded, which decides what TAP and SYNTH do with it.
enum ExciterEnvelope {
    ENV_VCA,     // none of its own: a gate-following VCA shapes TAP and SYNTH alike
    ENV_HOLDS,   // its own envelope for TAP; it holds under a gate, so SYNTH uses the VCA
    ENV_DECAYS,  // a hit plays out even under a held gate, so SYNTH adds the noise bed
};

constexpr int kPlaitsSpeech = 15;
constexpr float kSpeechLevel = 0.04f;  // its word lands about as loud as a held VA note
constexpr int kPlaitsSixOp = 2;  // bank 1 only: banks 2 and 3 already sit well under the knee
constexpr float kSixOpLevel = 0.2f;    // its tap rang the resonator's limiter (1.13 before the ceiling); this lands it just under an HH hit

// Plaits indices, with the Kotlin PlaitsEngineId names.
inline ExciterEnvelope exciter_envelope(int engine_index) {
    if (engine_index >= 2 && engine_index <= 4) return ENV_HOLDS;  // SixOpFM, SixOpFM_2, SixOpFM_3
    if (engine_index == kPlaitsSpeech) return ENV_DECAYS;          // Speech: one word per trigger
    if (engine_index >= 19 && engine_index <= 23) return ENV_DECAYS;  // String, Modal, BD, SD, HH
    return ENV_VCA;  // FM (and FMDrum), Noise, Waveshaping, VA, Additive, Grain, Particle, ...
}

// The starting VCA / scale state for a newly adopted engine.
void reset_exciter_envelope(CowbellUnitState& st) {
    const bool holds = exciter_envelope(st.exciter_engine) == ENV_HOLDS;
    st.exciter_env = holds ? 1.0f : 0.0f;
    st.hold_target = 1.0f;
    st.tap_gate_frames = 0;
    st.exciter_tail_quiet = false;
}

inline float peak_abs(const float* x, int n) {
    float p = 0.0f;
    for (int i = 0; i < n; ++i) p = std::fmax(p, std::fabs(x[i]));
    return p;
}

// Uniform in [-1, 1), from a 32-bit LCG kept in the unit state (no shared RNG).
inline float next_noise(uint32_t& seed) {
    seed = seed * 1664525u + 1013904223u;
    return (float)(int32_t)seed * (1.0f / 2147483648.0f);
}

// Renders the exciter into engine->cowbell_exciter_buf, split at each hit and gate edge.
void render_exciter(OrpheusEngine* engine, CowbellUnitState& st, ExciterHit* hits, int num_hits,
                    bool gate, bool gate_rose, float note, int num_frames, float sr) {
    OrpheusVoice& voice = engine->cowbell_exciter;
    float* ex = engine->cowbell_exciter_buf;
    const int engine_index = st.exciter_engine;
    const ExciterEnvelope kind = exciter_envelope(engine_index);
    st.exciter_note = note;
    const int tap_frames = std::max(1, (int)std::lround(kTapGateSeconds * sr));
    const float attack = 1.0f - std::exp(-1.0f / (kVcaAttackSeconds * sr));
    const float release = 1.0f - std::exp(-1.0f / (kVcaReleaseSeconds * sr));

    // Order by offset (insertion sort: stable and allocation-free).
    for (int i = 0; i < num_hits; ++i) hits[i].offset = std::min(std::max(hits[i].offset, 0), num_frames - 1);
    for (int i = 1; i < num_hits; ++i) {
        const ExciterHit h = hits[i];
        int j = i - 1;
        while (j >= 0 && hits[j].offset > h.offset) {
            hits[j + 1] = hits[j];
            --j;
        }
        hits[j + 1] = h;
    }

    // Six-op under SYNTH is a VCA engine: the held level, then shut at release until a TAP.
    if (kind == ENV_HOLDS) {
        if (gate) {
            st.hold_target = kHoldsSynthLevel;
            if (gate_rose) st.exciter_env = kHoldsSynthLevel;  // not a glide down from a TAP's level
        } else if (st.hold_target == kHoldsSynthLevel) {
            st.hold_target = 0.0f;
        }
    }

    int next = 0;
    int pos = 0;
    while (pos < num_frames) {
        bool retrigger = false;
        while (next < num_hits && hits[next].offset <= pos) {
            if (hits[next].tap) {
                st.tap_gate_frames = tap_frames;
                st.tap_level = hits[next].velocity;
                if (!gate) st.hold_target = 1.0f;  // a TAP plays the six-op at its own level
            }
            st.accent = hits[next].velocity;
            retrigger = true;
            ++next;
        }
        // A piece ends at the next hit, the TAP gate's fall, or the block's end.
        int end = num_frames;
        if (next < num_hits) end = std::min(end, hits[next].offset);
        if (st.tap_gate_frames > 0) end = std::min(end, pos + st.tap_gate_frames);
        const int size = end - pos;
        const bool tap_on = st.tap_gate_frames > 0;
        const bool gate_on = gate || tap_on;

        // Old remainder samples would delay the hit up to 23 frames; the voice crossfades instead.
        if (retrigger) {
            voice.remainder_count_ = 0;
            st.exciter_tail_quiet = false;
        }
        const bool vca_shut = (kind == ENV_VCA || (kind == ENV_HOLDS && st.hold_target == 0.0f)) &&
                              st.exciter_env < kVcaFloor;
        if (!gate_on && !retrigger && (vca_shut || st.exciter_tail_quiet)) {
            std::memset(ex + pos, 0, sizeof(float) * size);  // shut, or its tail died: skip the render
            if (vca_shut) st.exciter_env = 0.0f;
        } else {
            voice.Render(engine_index, gate_on ? 1 : 0, note, kExciterKnob, kExciterKnob, kExciterKnob,
                         st.accent, ex + pos, size, LPG_BYPASS, 0.5f, 0.5f, retrigger);
            if (kind != ENV_DECAYS) {
                const float target =
                    kind == ENV_HOLDS ? st.hold_target
                                      : kVcaLevel * std::max(gate ? 1.0f : 0.0f, tap_on ? st.tap_level : 0.0f);
                const float coeff = target > st.exciter_env ? attack : release;
                const float trim = engine_index == kPlaitsSixOp ? kSixOpLevel : 1.0f;
                float env = st.exciter_env;
                for (int i = pos; i < end; ++i) {
                    env += coeff * (target - env);
                    ex[i] *= env * trim;
                }
                st.exciter_env = env;
            } else if (engine_index == kPlaitsSpeech) {
                // A spoken word sits at the bell's pitch and builds up like a held tone.
                for (int i = pos; i < end; ++i) ex[i] *= kSpeechLevel;
            }
            // A self-enveloped tail below the floor for a whole sub-block is done until the next hit.
            if (kind != ENV_VCA && !gate_on && size >= kOrpheusBlockSize && peak_abs(ex + pos, size) < kVcaFloor)
                st.exciter_tail_quiet = true;
        }
        if (tap_on) st.tap_gate_frames -= size;
        pos = end;
    }
}

// SYNTH with no sustaining engine (impulse only, or one whose hit plays out): a steady soft
// noise keeps the resonator singing, ramped in and out over a few ms. Then the strike gain.
void finish_exciter(CowbellUnitState& st, float* ex, bool noise_bed, float ring, float gain_target,
                    int num_frames, float sr) {
    const float noise_level = kSynthNoiseLevel * (1.0f - kNoiseRingTrim * clampf(ring, 0.0f, 1.0f));
    const float noise_target = noise_bed ? noise_level : 0.0f;
    if (noise_target > 0.0f || st.noise_env >= kVcaFloor) {
        const float ramp = 1.0f - std::exp(-1.0f / (kNoiseRampSeconds * sr));
        const float lp = 1.0f - std::exp(-2.0f * 3.14159265f * kNoiseCutoffHz / sr);
        float env = st.noise_env;
        float y = st.noise_lp;
        for (int i = 0; i < num_frames; ++i) {
            env += ramp * (noise_target - env);
            y += lp * (next_noise(st.noise_seed) - y);
            ex[i] += env * y;
        }
        st.noise_env = env;
        st.noise_lp = y;
    } else {
        st.noise_env = 0.0f;
    }

    // INTENSITY drives the exciter like the impulse, ramped so a knob turn under SYNTH can't step.
    const float step = (gain_target - st.exciter_gain) / (float)num_frames;
    float g = st.exciter_gain;
    for (int i = 0; i < num_frames; ++i) {
        g += step;
        ex[i] *= g * kExciterDrive;
    }
    st.exciter_gain = gain_target;
}

// Starts a fill's burst at `offset` frames into this block. A full pool gives the quietest burst's
// place to a louder one; a quieter one is dropped. No allocation: the pool is fixed-size.
void spawn_burst(CowbellUnitState& st, const CowbellFillSpec& spec, int color, float velocity,
                 float strike_gain, int offset, float sr) {
    if (spec.seconds <= 0.0f) return;
    CowbellBurst* slot = nullptr;
    for (CowbellBurst& b : st.bursts) {
        if (b.frames_left <= 0) {
            slot = &b;
            break;
        }
        if (!slot || b.velocity < slot->velocity) slot = &b;
    }
    if (slot->frames_left > 0 && slot->velocity >= velocity) return;

    const float t = spec.random_centre ? 0.5f * (next_noise(st.burst_seed) + 1.0f) : cowbell_hit_brightness(color);
    const float centre =
        std::fmin(spec.centre_lo_hz + (spec.centre_hi_hz - spec.centre_lo_hz) * t, kBurstMaxCentreFraction * sr);
    CowbellBurst& b = *slot;
    b.total_frames = std::max(1, (int)std::lround(spec.seconds * sr));
    b.frames_left = b.total_frames;
    b.delay = offset;
    b.velocity = velocity;
    b.dry = spec.dry;
    // Topology-preserving band-pass (unity gain at the centre), levelled so the noise keeps its RMS.
    b.g = std::tan(3.14159265f * centre / sr);
    b.k = 1.0f / spec.q;
    b.a1 = 1.0f / (1.0f + b.g * (b.g + b.k));
    b.a2 = b.g * b.a1;
    b.a3 = b.g * b.a2;
    b.ic1 = b.ic2 = 0.0f;
    const float level = std::sqrt((0.5f * sr) / (1.5708f * centre / spec.q));
    b.amp = velocity * strike_gain * kBurstDrive * level;
}

bool bursts_alive(const CowbellUnitState& st) {
    for (const CowbellBurst& b : st.bursts) {
        if (b.frames_left > 0) return true;
    }
    return false;
}

// Adds every live burst to `wet` (the resonator's input) and its dry share to `dry`.
void render_bursts(CowbellUnitState& st, float* wet, float* dry, int num_frames) {
    for (CowbellBurst& b : st.bursts) {
        if (b.frames_left <= 0) continue;
        const float inv_total = 1.0f / (float)b.total_frames;
        const int start = std::min(std::max(b.delay, 0), num_frames);
        const int end = std::min(num_frames, start + b.frames_left);
        float ic1 = b.ic1, ic2 = b.ic2;
        int left = b.frames_left;
        for (int i = start; i < end; ++i, --left) {
            const float v3 = next_noise(st.burst_seed) - ic2;
            const float v1 = b.a1 * ic1 + b.a2 * v3;
            const float v2 = ic2 + b.a2 * ic1 + b.a3 * v3;
            ic1 = 2.0f * v1 - ic1;
            ic2 = 2.0f * v2 - ic2;
            const float rest = (float)left * inv_total;
            const float x = b.k * v1 * rest * rest * rest * b.amp;
            wet[i] += x * (1.0f - b.dry);
            dry[i] += x * b.dry;
        }
        b.ic1 = ic1;
        b.ic2 = ic2;
        b.frames_left = left;
        b.delay = 0;
    }
}

struct FiredStrike {
    CowbellStrike strike;
    int offset;
};

inline void fire(FiredStrike* fired, int* n, uint32_t packed, int offset) {
    CowbellStrike s;
    if (*n < kMaxFired && cowbell_decode_strike(packed, &s)) fired[(*n)++] = {s, offset};
}

// GRID: holds each drained strike for the next 16th (pulled `strength` of the way), keeping
// the loudest per slot, and returns what sounds this block. Off, with no transport, or below
// kGridPassThroughStrength, all play now, pending ones included.
int snap_strikes(CowbellUnitState& st, const PulsarGridExport& g, bool snapping, float strength,
                 const uint32_t* words, int num_words, int num_frames, FiredStrike* fired) {
    int n = 0;
    if (!snapping || strength < kGridPassThroughStrength) {
        for (int i = 0; i < st.pending_count; ++i) fire(fired, &n, st.pending[i].packed, 0);
        st.pending_count = 0;
        for (int i = 0; i < num_words; ++i) fire(fired, &n, words[i], 0);
        return n;
    }

    // The next line from this block's start, and its 16th.
    const bool in_block = g.boundary_count > 0;
    const float next = in_block ? (float)g.boundary_samples[0] : (float)num_frames + g.samples_to_next;
    const int64_t next_slot = in_block ? g.boundary_steps[0] : g.next_step;
    // The 16th in progress at the block's start, long or short under swing.
    const float since_prev = g.samples_since_last;
    const float step = since_prev + next;
    const bool just_after = next > 0.0f && since_prev <= kJustAfterFraction * step;
    const int target = (int)std::lround(clampf(strength, 0.0f, 1.0f) * std::fmax(next, 0.0f));

    if (just_after && num_words > 0) {
        // The line just passed: its slot sounds now, once, with the loudest of these.
        int loudest = 0;
        for (int i = 1; i < num_words; ++i) {
            if (vel7(words[i]) > vel7(words[loudest])) loudest = i;
        }
        if (next_slot - 1 != st.last_fired_slot) {
            st.last_fired_slot = next_slot - 1;
            fire(fired, &n, words[loudest], 0);
        }
    } else if (next_slot != st.last_fired_slot) {
        for (int i = 0; i < num_words; ++i) {
            int at = -1;
            for (int k = 0; k < st.pending_count; ++k) {
                if (st.pending[k].slot == next_slot) at = k;
            }
            if (at < 0 && st.pending_count < kSlots) {
                at = st.pending_count++;
                st.pending[at].packed = 0;
            } else if (at < 0) {
                at = 0;  // full (not reachable at sane tempos): over the quietest
                for (int k = 1; k < st.pending_count; ++k) {
                    if (vel7(st.pending[k].packed) < vel7(st.pending[at].packed)) at = k;
                }
            }
            CowbellPendingStrike& p = st.pending[at];
            if (p.packed == 0 || vel7(words[i]) > vel7(p.packed)) p = {words[i], next_slot, target};
        }
    }

    // Pending strikes landing in this block sound at their offset; a line this block (or one
    // already passed) caps the wait, so a tempo change can't carry a strike past its slot.
    int kept = 0;
    for (int k = 0; k < st.pending_count; ++k) {
        CowbellPendingStrike p = st.pending[k];
        if (p.slot < next_slot) p.remaining = 0;
        for (int b = 0; b < g.boundary_count && b < kPulsarGridMaxBoundaries; ++b) {
            if (g.boundary_steps[b] == p.slot) p.remaining = std::min(p.remaining, g.boundary_samples[b]);
        }
        if (p.remaining < num_frames) {
            fire(fired, &n, p.packed, std::max(p.remaining, 0));
            st.last_fired_slot = std::max(st.last_fired_slot, p.slot);
        } else {
            p.remaining -= num_frames;
            st.pending[kept++] = p;
        }
    }
    st.pending_count = kept;
    return n;
}

}  // namespace

// The offset (root-relative integer semitones) on a scale degree nearest `semitones`, never above
// `max_semitones`. A tie goes to the lower degree.
float snap_to_scale(float semitones, const PulsarScale& scale, float max_semitones) {
    const int count = scale.count < 1 ? 1 : (scale.count > 12 ? 12 : scale.count);
    float best = 0.0f;
    float best_dist = 1e30f;
    for (int n = -60; n <= 60; ++n) {
        if ((float)n > max_semitones) break;
        const int pc = ((n % 12) + 12) % 12;
        bool on_scale = false;
        for (int i = 0; i < count; ++i) on_scale = on_scale || scale.degrees[i] == pc;
        if (!on_scale) continue;
        const float d = std::fabs((float)n - semitones);
        if (d < best_dist) {
            best_dist = d;
            best = (float)n;
        }
    }
    return best;
}

CowbellPushResult cowbell_push_strike_result(OrpheusEngine* engine, uint32_t packed,
                                             uint32_t* overwritten) {
    uint32_t replaced_word = 0;
    const CowbellPushResult result = push_strike(engine->cowbell_strike_slots, packed, &replaced_word);
    if (overwritten) *overwritten = replaced_word;
#ifdef ORPHEUS_TESTING
    std::atomic<uint32_t>& counter = result == COWBELL_PUSH_PLACED     ? engine->cowbell_push_placed
                                     : result == COWBELL_PUSH_REPLACED ? engine->cowbell_push_replaced
                                                                       : engine->cowbell_push_dropped;
    counter.fetch_add(1, std::memory_order_relaxed);
#endif
    return result;
}

void cowbell_push_strike(OrpheusEngine* engine, uint32_t packed) {
    cowbell_push_strike_result(engine, packed, nullptr);
}

void cowbell_on_graph_swap(OrpheusEngine* engine, uint32_t graph_generation) {
    for (auto& slot : engine->cowbell_strike_slots) slot.store(0, std::memory_order_relaxed);
    CowbellUnitState& st = engine->cowbell_state;
    st.pending_count = 0;
    st.last_fired_slot = -1;
    for (CowbellBurst& b : st.bursts) b.frames_left = 0;
    st.duck_env = 0.0f;
    st.duck_from = 1.0f;
    st.duck_gain = 1.0f;
    st.graph_generation = graph_generation;
}

// Steps the duck once per block from the bell's post-ceiling block peak. `more` of 0 keeps the
// gain at exactly 1, so the bus is untouched.
static void update_duck(CowbellUnitState& st, float more, float peak, int num_frames, float sr) {
    const float tau = peak > st.duck_env ? kMoreDuckAttackSeconds : kMoreDuckReleaseSeconds;
    st.duck_env += block_smooth_coeff(sr, num_frames, tau) * (peak - st.duck_env);
    if (st.duck_env < 1e-5f) st.duck_env = 0.0f;
    st.duck_from = st.duck_gain;
    if (more <= 0.0f) {
        st.duck_gain = 1.0f;
        return;
    }
    const float keyed = clampf(st.duck_env / kMoreDuckKeyPeak, 0.0f, 1.0f);
    st.duck_gain = std::pow(10.0f, -kMoreDuckDb * more * keyed / 20.0f);
}

void cowbell_apply_duck(GraphUnit* u, OrpheusEngine* engine, int num_frames) {
    const CowbellUnitState& st = engine->cowbell_state;
    if (st.duck_from == 1.0f && st.duck_gain == 1.0f) return;
    const float step = (st.duck_gain - st.duck_from) / (float)num_frames;
    for (int p = 0; p < kMaxOutputPorts; p++) {
        float gain = st.duck_from;
        for (int i = 0; i < num_frames; i++) {
            gain += step;
            u->output_buffers[p][i] *= gain;
        }
    }
}

void unit_process_cowbell(GraphUnit* u, OrpheusEngine* engine, int num_frames, float sr) {
    float* out = u->output_buffers[OPORT_OUT];
    float* aux = u->output_buffers[OPORT_OUT_RIGHT];
    CowbellUnitState& st = engine->cowbell_state;
    OrpheusCowbellResonator& reso = engine->cowbell_reso;
    VizRing& viz = engine->viz_rings[VIZ_COWBELL_OUT];

    // 1. Drain the strike slots. A disabled unit drops them so they can't burst on re-enable.
    int num_drained = 0;
    float min_velocity = 1.0f;
    for (auto& slot : engine->cowbell_strike_slots) {
        const uint32_t w = slot.exchange(0, std::memory_order_acq_rel);
        CowbellStrike s;
        if (w && cowbell_decode_strike(w, &s)) {
            st.last_drained_words[num_drained++] = w;
            min_velocity = std::fmin(min_velocity, s.velocity);
        }
    }
    st.last_drain_count = num_drained;
    st.last_drain_min_velocity = num_drained ? min_velocity : 0.0f;

    if (!u->enabled) {
        st.pending_count = 0;
        for (CowbellBurst& b : st.bursts) b.frames_left = 0;
        st.last_fire_count = 0;
        std::memset(out, 0, sizeof(float) * num_frames);
        std::memset(aux, 0, sizeof(float) * num_frames);
        st.duck_env = 0.0f;
        st.duck_from = st.duck_gain;
        st.duck_gain = 1.0f;
        viz.write(0.0f);
        return;
    }

    // 1b. GRID: what sounds this block, and where. Pending strikes count down even while idle.
    const PulsarGridExport& grid = engine->pulsar_grid;
    const bool snapping = engine->cowbell_grid.load(std::memory_order_relaxed) && grid.running &&
                          grid.samples_per_step > 0.0f;
    FiredStrike fired[kMaxFired];
    const int num_strikes = snap_strikes(st, grid, snapping,
                                         engine->cowbell_grid_strength.load(std::memory_order_relaxed),
                                         st.last_drained_words, num_drained, num_frames, fired);
    st.last_fire_count = num_strikes;

    // 2. Knobs. The offsets ride on the macro's structure and on a centred position.
    const float intensity = engine->cowbell_intensity.load(std::memory_order_relaxed);
    const CowbellParams p = cowbell_macros(engine->cowbell_metal.load(std::memory_order_relaxed),
                                           engine->cowbell_ring.load(std::memory_order_relaxed),
                                           intensity);
    const float structure_offset = engine->cowbell_structure_offset.load(std::memory_order_relaxed);
    const float position_offset = engine->cowbell_position_offset.load(std::memory_order_relaxed);
    reso.set_model(p.model);
    reso.set_polyphony(p.polyphony);
    reso.set_structure(clampf(p.structure + structure_offset, 0.0f, 0.9995f));
    reso.set_damping(p.damping);
    reso.set_position(clampf(0.5f + position_offset, 0.0f, 1.0f));

    // 3. Pitch: the vibe's root pitch class in octave 5, shifted by whole octaves.
    int root = engine->pulsar_root_note.load(std::memory_order_relaxed) % 12;
    if (root < 0) root += 12;
    int octave = engine->cowbell_octave.load(std::memory_order_relaxed);
    octave = octave < -2 ? -2 : (octave > 2 ? 2 : octave);
    const float note = kBaseNote + (float)root + 12.0f * (float)octave;
    reso.set_note(note);

    // 4. Wake. Any strike, a held gate or a bend wakes it; a rising gate strikes too.
    // The gate is sampled once per block, so an on/off pulse inside one block is missed. Fine for
    // a mouse; part 4's touch may need an edge counter.
    const bool gate = engine->cowbell_gate.load(std::memory_order_relaxed);
    const bool gate_rose = gate && !st.gate_prev;
    st.gate_prev = gate;
    const float bend_target = engine->cowbell_bend.load(std::memory_order_relaxed);
    const bool active = num_strikes > 0 || gate || bend_target != 0.0f || bursts_alive(st);
    // From silence the next strike applies p.polyphony, so the width starts there.
    const float wake_width = p.polyphony > 1 ? kPolyWidth : 1.0f;
    if (active && st.idle) st.width = wake_width;
    if (active) st.idle = false;
    float level_target = clampf(engine->cowbell_level.load(std::memory_order_relaxed), 0.0f, 1.0f);
    const float more_target = clampf(engine->cowbell_more.load(std::memory_order_relaxed), 0.0f, 1.0f);
    if (!st.level_started) {
        st.level = level_target;
        st.more = more_target;
        st.bend = bend_target;
        st.exciter_bend = bent_semitones(bend_curve(bend_target), note);
        st.width = wake_width;
        st.exciter_gain = p.strike_gain;
        st.level_started = true;
    }
    if (st.idle) {
        std::memset(out, 0, sizeof(float) * num_frames);
        std::memset(aux, 0, sizeof(float) * num_frames);
        st.level = level_target;
        st.more = more_target;
        update_duck(st, more_target, 0.0f, num_frames, sr);
        st.bend = bend_target;
        st.exciter_bend = bent_semitones(bend_curve(bend_target), note);
        st.exciter_gain = p.strike_gain;
        viz.write(0.0f);
        return;
    }

    // 5. Bend: the bender's tension curve on the smoothed amount, with a swell in brightness
    // and level as it pulls.
    st.bend += block_smooth_coeff(sr, num_frames, kBendSmoothSeconds) * (bend_target - st.bend);
    const float curve = bend_curve(st.bend);
    const float swell = std::fabs(curve) / 1.5f * clampf(intensity, 0.0f, 1.0f);
    float bend_semitones = bent_semitones(curve, note);
    // BEND TO SCALE: while Pulsar plays, the bend lands on its scale and glides there. Anything
    // else leaves the free path above untouched.
    const bool to_scale = engine->cowbell_bend_scale.load(std::memory_order_relaxed) &&
                          engine->pulsar_playing.load(std::memory_order_relaxed) != 0;
    if (to_scale) {
        int scale_index = engine->pulsar_scale_index.load(std::memory_order_relaxed);
        scale_index = scale_index < 0 ? 0 : (scale_index >= kNumPulsarScales ? kNumPulsarScales - 1 : scale_index);
        const float target = snap_to_scale(bend_semitones, kPulsarScales[scale_index], kMaxBentNote - note);
        if (!st.scale_active) st.scale_bend = bend_semitones;
        st.scale_bend += block_smooth_coeff(sr, num_frames, kScaleGlideSeconds) * (target - st.scale_bend);
        bend_semitones = st.scale_bend;
    }
    st.scale_active = to_scale;
    reso.set_bend_semitones(bend_semitones);
    // The exciter's pitch follows the resonator's own glide on top.
    st.exciter_bend += block_smooth_coeff(sr, num_frames, OrpheusCowbellResonator::kGlideSeconds) *
                       (bend_semitones - st.exciter_bend);
    reso.set_brightness(clampf(p.brightness + 0.2f * swell, 0.0f, 1.0f));
    level_target *= 1.0f + 0.4f * swell;

    // 6. Strikes. An engine change waits for one, then the voice sees a fresh edge.
    if (num_strikes > 0 || gate_rose) {
        const int ordinal = engine->cowbell_engine.load(std::memory_order_relaxed);
        const int want = ordinal == -1 ? -1 : kotlin_engine_ordinal_to_plaits(ordinal);
        if ((ordinal == -1 || want >= 0) && want != st.exciter_engine) {
            st.exciter_engine = want;
            reset_exciter_envelope(st);
            engine->cowbell_exciter.trigger_state_ = false;
        }
    }
    const bool excited = st.exciter_engine >= 0;
    // Only hits that fire the engine (TAP strikes, the gate's edge) sit under its sound.
    const float hit_scale = excited ? kEngineImpulseScale : 1.0f;
    // Exciter triggers: TAP strikes (kind 0) open a 5 ms gate; the rising gate retriggers.
    ExciterHit hits[kMaxExciterHits];
    int num_hits = 0;
    if (gate_rose) {
        reso.strike(kGateVelocity * p.strike_gain * hit_scale, 0);
        hits[num_hits++] = {0, kGateVelocity, false};
    }
    for (int i = 0; i < num_strikes; ++i) {
        const CowbellStrike& s = fired[i].strike;
        const int offset = fired[i].offset;
        const bool fires = s.kind == 0;
        const CowbellFillSpec& fill = cowbell_fill_spec(s.kind);
        reso.strike(s.velocity * p.strike_gain * (fires ? hit_scale : fill.strike_scale), offset,
                    cowbell_hit_brightness_offset(s.color));
        if (fires && num_hits < kMaxExciterHits) hits[num_hits++] = {offset, s.velocity, true};
        spawn_burst(st, fill, s.color, s.velocity, p.strike_gain, offset, sr);
    }

    // 7. Render: the exciter into the resonator's input.
    const float* in = nullptr;
    const bool noise_bed = gate && (!excited || exciter_envelope(st.exciter_engine) == ENV_DECAYS);
    if (excited || noise_bed || st.noise_env >= kVcaFloor) {
        float* ex = engine->cowbell_exciter_buf;
        if (excited) {
            render_exciter(engine, st, hits, num_hits, gate, gate_rose, note + st.exciter_bend, num_frames, sr);
        } else {
            std::memset(ex, 0, sizeof(float) * num_frames);
        }
        finish_exciter(st, ex, noise_bed, engine->cowbell_ring.load(std::memory_order_relaxed), p.strike_gain,
                       num_frames, sr);
        in = ex;
    } else {
        st.exciter_gain = p.strike_gain;
        std::memset(engine->cowbell_exciter_buf, 0, sizeof(float) * num_frames);  // nothing feeds the bell
    }
    if (excited) st.exciter_trigger_count += num_hits;
    // Fill bursts join the resonator's input; their dry share is added to its output below.
    float* dry = engine->cowbell_burst_dry_buf;
    const bool bursting = bursts_alive(st);
    if (bursting) {
        float* ex = engine->cowbell_exciter_buf;
        if (!in) in = ex;  // zeroed above
        std::memset(dry, 0, sizeof(float) * num_frames);
        render_bursts(st, ex, dry, num_frames);
    }
    reso.process(in, out, aux, num_frames);
    if (bursting) {
        for (int i = 0; i < num_frames; ++i) {
            out[i] += dry[i];
            aux[i] += dry[i];
        }
    }

    // 8. Stereo: one voice gives a two-pickup pair; polyphonic Rings sends alternate voices hard
    // left and right, so that ping-pong is narrowed (mid/side), crossfaded over a block.
    const float width_target = reso.polyphony() > 1 ? kPolyWidth : 1.0f;
    const float width_step = (width_target - st.width) / (float)num_frames;
    float width = st.width;
    for (int i = 0; i < num_frames && (width_target != 1.0f || st.width != 1.0f); ++i) {
        width += width_step;
        const float mid = 0.5f * (out[i] + aux[i]);
        const float side = 0.5f * (out[i] - aux[i]) * width;
        out[i] = mid + side;
        aux[i] = mid - side;
    }
    st.width = width_target;

    // Idle is judged before the level, so a muted bell still decays to silence before sleeping.
    float raw_peak = 0.0f;
    for (int i = 0; i < num_frames; ++i) {
        raw_peak = std::fmax(raw_peak, std::fmax(std::fabs(out[i]), std::fabs(aux[i])));
    }
    if (active || raw_peak >= kIdlePeak) {
        st.quiet_blocks = 0;
    } else if (++st.quiet_blocks >= kIdleBlocks) {
        st.idle = true;
    }

    // 9. Level, ramped across the block, with the makeup gain and a soft ceiling under full scale.
    const float level_step = (level_target - st.level) / (float)num_frames;
    float level = st.level;
    // MORE multiplies the output by 1 + kMoreBoost x more ahead of the ceiling; at 0 the factor is exactly 1.
    const float more_step = (more_target - st.more) / (float)num_frames;
    float more = st.more;
    float peak = 0.0f;
    float pre_peak = 0.0f;
    for (int i = 0; i < num_frames; ++i) {
        level += level_step;
        more += more_step;
        const float boost = 1.0f + kMoreBoost * more;
        const float l = out[i] * level * kCowbellOutputGain * boost;
        const float r = aux[i] * level * kCowbellOutputGain * boost;
        pre_peak = std::fmax(pre_peak, std::fmax(std::fabs(l), std::fabs(r)));
        const float g = ceiling_gain(l, r);
        out[i] = l * g;
        aux[i] = r * g;
        peak = std::fmax(peak, std::fmax(std::fabs(out[i]), std::fabs(aux[i])));
    }
    st.level = level_target;
    st.more = more_target;
    st.last_pre_ceiling_peak = pre_peak;
    update_duck(st, more_target, peak, num_frames, sr);

    // 10. Viz: block peak.
    viz.write(peak);
}
