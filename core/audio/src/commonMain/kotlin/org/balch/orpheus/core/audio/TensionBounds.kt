package org.balch.orpheus.core.audio

/**
 * Tension-evolution bound meaning "the vibe authored no window here". Safe as a sentinel
 * because every real bound is a 0-1 macro value.
 *
 * Lives in core:audio because the feature model (`EvolutionTension`) and the plugin ports
 * (`PulsarPlugin`) both declare defaults from it and cannot see each other. C++ mirror:
 * `kUnauthoredTensionBound` in `pulsar_limits.h`.
 */
const val UNAUTHORED_TENSION_BOUND = -1f
