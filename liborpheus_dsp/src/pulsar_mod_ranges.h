#pragma once
#include <cstdint>

// Per-engine modulation range tables for Pulsar beat machine.
// Indexed by Plaits engine ID (0-23), matching the Engine enum in PulsarVibe.kt.
// Defines safe parameter sweep zones for LFO modulation, avoiding dead zones,
// mode switches, and discontinuities in the MI engine implementations.

struct EngineModRange {
    float harmonics_min;
    float harmonics_max;
    float timbre_min;
    float timbre_max;
    float morph_min;
    float morph_max;
    float pitch_cents;
    bool  harmonics_safe;
    bool  morph_safe;
    // Playability floors: minimum values to avoid artifacts (aliasing, crackling)
    // Applied as max(param, floor) before voice render — prevents bad-sounding combos.
    float harmonics_floor;
    float timbre_floor;
    float morph_floor;
    uint8_t note_min;   // lowest MIDI note that sounds clean (0 = no limit)
};

// Table format: {harm_min, harm_max, timb_min, timb_max, morph_min, morph_max,
//                pitch_cents, harm_safe, morph_safe,
//                harm_floor, timb_floor, morph_floor, note_min}
static constexpr EngineModRange kEngineModRanges[24] = {
    // 0: VCF — VirtualAnalogVCF (bass filter sweep)
    { 0.10f,0.90f, 0.10f,0.90f, 0.10f,0.90f,  8.0f, true,true,  0.0f,0.1f,0.0f, 30 },
    // 1: PD — PhaseDistortion (metallic artifacts below note 33)
    { 0.10f,0.90f, 0.10f,0.90f, 0.10f,0.90f,  8.0f, true,true,  0.0f,0.2f,0.0f, 33 },
    // 2: DX — SixOp FM1
    { 0.00f,1.00f, 0.10f,0.90f, 0.10f,0.90f,  8.0f, true,true,  0.0f,0.0f,0.0f, 0 },
    // 3: DX2 — SixOp FM2
    { 0.00f,1.00f, 0.10f,0.90f, 0.10f,0.90f,  8.0f, true,true,  0.0f,0.0f,0.0f, 0 },
    // 4: DX3 — SixOp FM3
    { 0.00f,1.00f, 0.10f,0.90f, 0.10f,0.90f,  8.0f, true,true,  0.0f,0.0f,0.0f, 0 },
    // 5: TRN — WaveTerrain (chaotic at low timbre/morph)
    { 0.10f,0.90f, 0.10f,0.90f, 0.10f,0.90f, 12.0f, true,true,  0.0f,0.15f,0.1f, 0 },
    // 6: ENS — StringMachine (clean at all settings)
    { 0.00f,1.00f, 0.10f,0.90f, 0.10f,0.90f, 12.0f, true,true,  0.0f,0.0f,0.0f, 0 },
    // 7: NES — Chiptune (quantized internally, always clean)
    { 0.00f,1.00f, 0.00f,1.00f, 0.00f,1.00f,  4.0f, true,true,  0.0f,0.0f,0.0f, 0 },
    // 8: VA — VirtualAnalog (aliasing at low timbre + low notes)
    { 0.00f,1.00f, 0.10f,0.90f, 0.10f,0.90f, 12.0f, true,true,  0.0f,0.35f,0.0f, 40 },
    // 9: WSH — Waveshaping (harsh distortion at very low timbre)
    { 0.10f,0.90f, 0.10f,0.90f, 0.10f,0.90f,  8.0f, true,true,  0.0f,0.2f,0.0f, 0 },
    // 10: FM (carrier disappears at timbre=0)
    { 0.00f,1.00f, 0.10f,0.90f, 0.10f,0.90f,  8.0f, true,true,  0.0f,0.15f,0.0f, 0 },
    // 11: GRN — Grain (carrier bleed at harmonics 0.5)
    { 0.00f,0.45f, 0.10f,0.90f, 0.20f,0.80f, 12.0f, true,true,  0.0f,0.0f,0.0f, 0 },
    // 12: ADD — Additive (most forgiving)
    { 0.00f,1.00f, 0.00f,1.00f, 0.20f,0.80f, 12.0f, true,true,  0.0f,0.0f,0.0f, 0 },
    // 13: WTB — Wavetable (fully safe)
    { 0.00f,1.00f, 0.00f,1.00f, 0.00f,1.00f, 12.0f, true,true,  0.0f,0.0f,0.0f, 0 },
    // 14: CHD — Chord (harmonics is quantized chord select)
    { 0.00f,0.00f, 0.20f,0.80f, 0.00f,1.00f,  4.0f, false,true, 0.0f,0.0f,0.0f, 0 },
    // 15: SPK — Speech (model switches at harmonics 0.33/0.67)
    { 0.00f,0.00f, 0.20f,0.80f, 0.00f,1.00f,  4.0f, false,true, 0.0f,0.0f,0.0f, 0 },
    // 16: SWM — Swarm (smooth clustering)
    { 0.00f,1.00f, 0.10f,0.90f, 0.10f,0.90f, 12.0f, true,true,  0.0f,0.0f,0.0f, 0 },
    // 17: NSE — Noise (percussive, no pitch)
    { 0.10f,0.90f, 0.20f,0.80f, 0.10f,0.90f,  0.0f, true,true,  0.0f,0.0f,0.0f, 0 },
    // 18: PAR — Particle (morph mode switch at 0.5)
    { 0.10f,0.80f, 0.20f,0.70f, 0.05f,0.45f,  8.0f, true,true,  0.0f,0.0f,0.0f, 0 },
    // 19: STR — String (dead zone at harmonics 0.24-0.26)
    { 0.28f,0.80f, 0.20f,0.90f, 0.10f,0.90f, 12.0f, true,true,  0.28f,0.0f,0.0f, 0 },
    // 20: MOD — Modal (smoothest engine)
    { 0.00f,1.00f, 0.15f,0.85f, 0.10f,0.90f, 12.0f, true,true,  0.0f,0.0f,0.0f, 0 },
    // 21: BD — BassDrum (self-enveloped)
    { 0.20f,0.80f, 0.30f,0.70f, 0.20f,0.80f,  0.0f, true,true,  0.0f,0.0f,0.0f, 0 },
    // 22: SD — SnareDrum (self-enveloped)
    { 0.20f,0.80f, 0.30f,0.70f, 0.20f,0.80f,  0.0f, true,true,  0.0f,0.0f,0.0f, 0 },
    // 23: HH — HiHat (narrow safe range)
    { 0.30f,0.70f, 0.30f,0.70f, 0.30f,0.70f,  0.0f, true,true,  0.0f,0.0f,0.0f, 0 },
};

// Apply bipolar LFO modulation to a base parameter value, clamped to the
// safe range for the current engine. If !safe, returns base unchanged.
//
// lfo_bipolar: LFO output, ALREADY scaled by the caller's depth (Pulsar keeps the scaled copy
//              in ts.mod_lfo_output[]). Scaling it again here would square the depth.
// bias_depth:  [0, 1], the same depth the caller used. Recentres `base` toward the range centre
//              so a wide swing is not clipped asymmetrically. Must track the depth: at depth 0
//              there is no swing, so recentring is a pure detune of an authored value.
// range_min/max: safe parameter range from kEngineModRanges
// safe:        harmonics_safe or morph_safe from kEngineModRanges
inline float apply_mod(float base, float lfo_bipolar, float bias_depth,
                       float range_min, float range_max, bool safe) {
    if (!safe) return base;
    // Authored value, unvalidated upstream; past 1.0 the recentring overshoots the centre.
    if (bias_depth < 0.0f) bias_depth = 0.0f;
    if (bias_depth > 1.0f) bias_depth = 1.0f;
    float center = (range_min + range_max) * 0.5f;
    float biased = base + (center - base) * bias_depth * 0.5f;
    float half_range = (range_max - range_min) * 0.5f;
    float result = biased + lfo_bipolar * half_range;
    if (result < range_min) result = range_min;
    if (result > range_max) result = range_max;
    return result;
}

// Three-band energy curve for TEXTURE/FX tracks (5-7):
// Full volume at extremes, ducked in mid-zone (0.4-0.6).
inline float texture_energy_curve(float energy) {
    if (energy < 0.35f) return 1.0f;
    if (energy < 0.45f) return 1.0f - (energy - 0.35f) * (0.95f / 0.10f);
    if (energy < 0.55f) return 0.05f;
    if (energy < 0.65f) return 0.05f + (energy - 0.55f) * (0.95f / 0.10f);
    return 1.0f;
}

// Inverse boost for lick/bass tracks (3-4) in mid-energy zone.
inline float lick_bass_energy_boost(float energy) {
    if (energy < 0.35f) return 1.0f;
    if (energy < 0.55f) return 1.0f + (energy - 0.35f) * (0.3f / 0.20f);
    if (energy < 0.65f) return 1.3f - (energy - 0.55f) * (0.3f / 0.10f);
    return 1.0f;
}
