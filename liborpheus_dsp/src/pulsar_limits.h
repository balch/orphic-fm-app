#pragma once

// Canonical Pulsar arrangement capacities.
//
// These govern PREALLOCATED wire buffers — the arrangement crosses into the audio
// thread through fixed-size atomic arrays so the render path never allocates. That
// pins the same numbers in four places that must agree exactly:
//
//   1. the wire arrays in orpheus_engine.h        (pulsar_section_*)
//   2. the runtime structs in orpheus_unit_pulsar.h (ArrangementParams, SectionParam)
//   3. the routing bounds checks in orpheus_engine_routing.cpp
//   4. Arrangement.MAX_SECTIONS on the Kotlin side
//
// They used to be bare `8` literals duplicated across all four, which meant bumping
// one silently corrupted the others: the routing bounds SILENTLY DROP out-of-range
// writes (no crash, just a section that never arrives), and a mismatched stride reads
// another section's transitions. Everything now derives from here, static_asserts in
// orpheus_engine.h tie the arrays to it, and PulsarSectionLimitsTest parses this file
// to hold the Kotlin side in lockstep.
//
// Raising kMaxSections costs ~520 bytes of atomics per section and nothing else.

static constexpr int kNumPulsarTracks = 8;

// Sequencer and lick step cap. Here rather than orpheus_unit_pulsar.h so orpheus_engine.h can
// size PulsarViz without pulling in the whole Pulsar unit.
static constexpr int kMaxPulsarSteps = 64;

// Sections per arrangement.
static constexpr int kMaxSections = 12;

// Outgoing transition edges per section. Distinct from kMaxSections — they were both
// 8 for a long time, which hid a stride bug where the C++ transition unpack indexed by
// kMaxSections while Kotlin wrote by this value. Keep them independent.
static constexpr int kMaxSectionTransitions = 8;

// Per-section wire strides (floats/ints per section in each array). Governs allocation
// size for BOTH arrays below, but only pulsar_section_data's own indexing uses the full
// width (slot 17 the bass-channel pin, slots 21-25 are SectionWeather, slot 26 is the
// pinned lick, slots 27-30 are SectionStreet, slot 31 the section volume, slots 32-36 the
// rampTo macros, slot 37 the volume envelope's point count and 38.. its (bar, level) pairs, then the 5
// SectionFog slots at kSectionFogSlot, then the 3 SectionDelay slots at kSectionDelaySlot) —
// pulsar_section_tension_data is indexed by its own kSectionTensionFields stride.
static constexpr int kMaxSectionVolumePoints = 6;
static constexpr int kSectionFogSlot = 38 + kMaxSectionVolumePoints * 2;   // 50: SectionFog, 5 slots
static constexpr int kSectionDelaySlot = kSectionFogSlot + 5;   // 55: SectionDelay, 3 slots
static constexpr int kSectionDataFields = 58;   // pulsar_section_data, pulsar_section_tension_data
static_assert(kSectionDataFields == kSectionDelaySlot + 3,
              "slot 37 is the volume envelope count, its pairs follow it, then 5 fog and 3 delay slots");
// pulsar_section_tension_data's stride: the 21 vibe-level tension fields plus the arc.
static constexpr int kSectionTensionFields = 22;
static_assert(kSectionTensionFields <= kSectionDataFields,
              "pulsar_section_tension_data is sized by kSectionDataFields");
static constexpr int kSectionProgressionSlots = 12;  // degrees + glides
static constexpr int kSectionCompingHumanFields = 4;

// Per-track stride of pulsar_track_ducking: the six DuckingProfile fields plus a
// trailing declared flag. The flag is what keeps a track whose vibe authored no
// profile on the engine's own duck constants — all-zero values are a legitimate
// "do not duck me", so they cannot double as "the author said nothing".
// Kotlin: DuckingProfile.WIRE_FIELDS (pinned in PulsarSectionLimitsTest).
static constexpr int kTrackDuckingFields = 7;

// Notated-score events per track. 4096 x 12 bytes x 8 tracks is ~393KB of preallocated
// wire buffer. Mirrored by NotatedScore.MAX_SCORE_EVENTS on the Kotlin side.
static constexpr int kMaxScoreEvents = 4096;

// The transition unpack in orpheus_unit_pulsar.cpp indexes by kMaxSectionTransitions,
// so the array must be at least that wide per section. Catches the case where someone
// raises kMaxSectionTransitions past the reserved stride.
static_assert(kMaxSectionTransitions >= 1, "need at least one edge per section");
static_assert(kMaxSections >= 1, "need at least one section");

// Tension-evolution bound meaning "the vibe authored no window here". Safe as a sentinel
// because every real bound is a 0-1 macro value. Lives here because orpheus_engine.h and
// orpheus_unit_pulsar.h both declare defaults from it. Kotlin: UNAUTHORED_TENSION_BOUND.
static constexpr float kUnauthoredTensionBound = -1.0f;
