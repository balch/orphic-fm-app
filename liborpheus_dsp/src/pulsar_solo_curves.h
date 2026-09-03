#pragma once
#include <cmath>
#include <cstdint>

// ---------------------------------------------------------------------------
// Solo curves: how the band builds behind a soloist and how a drum lead arcs.
//
// Every constant here is EAR TUNE. Randomness is hashed from the solo seed, never
// drawn from the shared mutation RNG, so it cannot reorder the music.
// ---------------------------------------------------------------------------

// Shape: each solo bends its build early (<1) or late (>1). p^shape.
inline constexpr float kShapeMin = 0.6f;
inline constexpr float kShapeMax = 1.6f;

// Kit ride (always-active SUPPORT member under a melodic soloist).
inline constexpr float kKitJitter          = 0.04f;   // per-bar wobble on the ride
inline constexpr float kKitRideVolumeEnd   = 0.15f;   // velocity mod at full build
inline constexpr float kKitRideDensityStart = -0.15f; // opening density cut (the old constant)
inline constexpr float kKitRideDensityEnd  = 0.10f;   // closing density boost
inline constexpr float kKitRideFillFrom    = 0.70f;   // progress past which fills arm
inline constexpr float kKitRideFill        = 0.30f;   // solo_fill_mod on those bars
inline constexpr float kKitRideSimplifyUntil = 0.50f; // ghosts return past this

// Support ease: non-kit SUPPORT density duck shrinks by this fraction at full build.
inline constexpr float kSupportEase = 0.5f;

// Drum arc (the drummer's own span).
inline constexpr float kDrumJitter       = 0.05f;
inline constexpr float kDrumOverlayStart = 0.60f;   // lick accents start this loud
inline constexpr float kDrumHatStart     = 0.35f;
inline constexpr float kDrumHatEnd       = 0.85f;
inline constexpr float kDrumGhostEnd     = 0.35f;
inline constexpr float kClimaxVelStart   = 0.55f;   // snare ramp start on the climax beat

inline constexpr uint32_t kKitSalt  = 0x4B4954u;
inline constexpr uint32_t kDrumSalt = 0x44524Du;

// Pure hash in [0,1]. Not a stream: the same (a, b) always gives the same value.
inline float solo_hash01(uint32_t a, uint32_t b) {
    uint32_t h = a * 0x9E3779B1u;
    h ^= b + 0x7F4A7C15u + (h << 6) + (h >> 2);
    h ^= h >> 16; h *= 0x85EBCA6Bu;
    h ^= h >> 13; h *= 0xC2B2AE35u;
    h ^= h >> 16;
    return static_cast<float>(h & 0xFFFFFFu) / 16777215.0f;
}

inline float solo_shape(uint32_t solo_seed, uint32_t salt) {
    return kShapeMin + (kShapeMax - kShapeMin) * solo_hash01(solo_seed, salt);
}

inline float shaped_progress(float p, float shape) {
    if (p <= 0.0f) return 0.0f;
    if (p >= 1.0f) return 1.0f;
    return std::pow(p, shape);
}

inline float bar_jitter(uint32_t solo_seed, int bar, uint32_t salt, float amount) {
    return (solo_hash01(solo_seed ^ salt, static_cast<uint32_t>(bar)) * 2.0f - 1.0f) * amount;
}

inline float curve_clamp01(float x) { return x < 0.0f ? 0.0f : (x > 1.0f ? 1.0f : x); }

struct KitRide {
    float volume_mod;
    float density_mod;
    float fill_mod;
    bool  simplify;
};

// The kit under a melodic solo: thin and plain at first, louder and busier by the end.
inline KitRide kit_ride(float progress, uint32_t solo_seed, int bar) {
    float p = shaped_progress(progress, solo_shape(solo_seed, kKitSalt));
    p = curve_clamp01(p + bar_jitter(solo_seed, bar, kKitSalt, kKitJitter));
    KitRide r;
    r.volume_mod  = kKitRideVolumeEnd * p;
    r.density_mod = kKitRideDensityStart + (kKitRideDensityEnd - kKitRideDensityStart) * p;
    r.fill_mod    = (p > kKitRideFillFrom) ? kKitRideFill : 0.0f;
    r.simplify    = (p < kKitRideSimplifyUntil);
    return r;
}

struct DrumArc {
    float overlay_gain;
    float hat_prob;
    float ghost_prob;
    bool  climax;
};

// The drummer's span: the lick accents fade in over the groove, hats and ghosts climb,
// and the last bar ends in a fill.
inline DrumArc drum_arc(float progress, bool last_bar, uint32_t solo_seed, int bar) {
    float p = shaped_progress(progress, solo_shape(solo_seed, kDrumSalt));
    p = curve_clamp01(p + bar_jitter(solo_seed, bar, kDrumSalt, kDrumJitter));
    DrumArc a;
    a.overlay_gain = kDrumOverlayStart + (1.0f - kDrumOverlayStart) * p;
    a.hat_prob     = kDrumHatStart + (kDrumHatEnd - kDrumHatStart) * p;
    a.ghost_prob   = kDrumGhostEnd * p;
    a.climax       = last_bar;
    return a;
}
