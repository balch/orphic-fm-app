package org.balch.orpheus.features.pulsar.models

import kotlinx.serialization.Serializable

/**
 * How [Vibe.lickMutation] changes a lick on repeat. [RANDOM] can fill a rest and jump a
 * note to any degree. [CHORD_TONES] keeps every rest, moves a note one scale step at most,
 * and lands each moved note on the chord sounding under it. Ordinal is the engine value.
 */
@Serializable
enum class LickMutationMode { RANDOM, CHORD_TONES }
