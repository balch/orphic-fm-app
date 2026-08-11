#pragma once

// Canonical Pulsar arrangement capacities.
//
// These govern PREALLOCATED wire buffers — the arrangement crosses into the audio
// thread through fixed-size atomic arrays so the render path never allocates. That
// makes the numbers load-bearing in four places that must agree exactly:
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

// Sections per arrangement.
static constexpr int kMaxSections = 12;

// Outgoing transition edges per section. Distinct from kMaxSections — they were both
// 8 for a long time, which hid a stride bug where the C++ transition unpack indexed by
// kMaxSections while Kotlin wrote by this value. Keep them independent.
static constexpr int kMaxSectionTransitions = 8;

// Per-section wire strides (floats/ints per section in each array).
static constexpr int kSectionDataFields = 21;   // pulsar_section_data, pulsar_section_tension_data
static constexpr int kSectionProgressionSlots = 12;  // degrees + glides
static constexpr int kSectionCompingHumanFields = 4;

// The transition unpack in orpheus_unit_pulsar.cpp indexes by kMaxSectionTransitions,
// so the array must be at least that wide per section. Catches the case where someone
// raises kMaxSectionTransitions past the reserved stride.
static_assert(kMaxSectionTransitions >= 1, "need at least one edge per section");
static_assert(kMaxSections >= 1, "need at least one section");

// Tension-evolution bound meaning "the vibe authored no window here". Safe as a sentinel
// because every real bound is a 0-1 macro value. Lives here because orpheus_engine.h and
// orpheus_unit_pulsar.h both declare defaults from it. Kotlin: UNAUTHORED_TENSION_BOUND.
static constexpr float kUnauthoredTensionBound = -1.0f;
