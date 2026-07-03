package org.balch.orpheus.features.pulsar

/**
 * Display names for the 12 chromatic root notes (C .. B).
 * Indexed by [org.balch.orpheus.features.pulsar.models.RootNote.noteIndex].
 */
val PULSAR_NOTE_NAMES: List<String> = listOf(
    "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B",
)

/**
 * Display names for each [org.balch.orpheus.features.pulsar.models.ScaleType],
 * indexed by [org.balch.orpheus.features.pulsar.models.ScaleType.scaleIndex].
 */
val PULSAR_SCALE_NAMES: List<String> = listOf(
    "Minor", "Major", "Pentatonic", "Phrygian", "Whole Tone", "Chromatic",
    "Dorian", "Lydian", "Mixolydian", "Harm Minor", "Min Penta", "Hirajoshi", "In Sen",
    "Blues", "Blues Pent", "Maj Blues",
)
