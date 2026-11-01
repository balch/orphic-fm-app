package org.balch.orpheus.djapp.ai.songref

import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType
import kotlin.math.abs

private val SHARP_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private val NATURAL_PCS = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

/** "C" -> 0, "C#" -> 1, "Db" -> 1. The letter must be upper case. */
internal fun pitchClassOf(name: String): Int? {
    if (name.isEmpty() || name.length > 2) return null
    val base = NATURAL_PCS[name[0]] ?: return null
    val shift = when (name.getOrNull(1)) {
        null -> 0
        '#' -> 1
        'b' -> -1
        else -> return null
    }
    return (base + shift + 12) % 12
}

internal fun pitchClassName(pc: Int): String = SHARP_NAMES[((pc % 12) + 12) % 12]

/** Scientific pitch name with sharps, C4 = 60. */
internal fun noteName(midi: Int): String = pitchClassName(midi) + (midi.floorDiv(12) - 1)

internal fun rootNoteOf(pc: Int): RootNote = RootNote.entries[((pc % 12) + 12) % 12]

/** Mirrors `kPulsarScales` in orpheus_unit_pulsar.h, indexed by `scaleIndex`. */
private val SCALE_INTERVALS: Array<IntArray> = arrayOf(
    intArrayOf(0, 2, 3, 5, 7, 8, 10),
    intArrayOf(0, 2, 4, 5, 7, 9, 11),
    intArrayOf(0, 2, 4, 7, 9),
    intArrayOf(0, 1, 3, 5, 7, 8, 10),
    intArrayOf(0, 2, 4, 6, 8, 10),
    intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
    intArrayOf(0, 2, 3, 5, 7, 9, 10),
    intArrayOf(0, 2, 4, 6, 7, 9, 11),
    intArrayOf(0, 2, 4, 5, 7, 9, 10),
    intArrayOf(0, 2, 3, 5, 7, 8, 11),
    intArrayOf(0, 3, 5, 7, 10),
    intArrayOf(0, 2, 3, 7, 8),
    intArrayOf(0, 1, 5, 7, 10),
    intArrayOf(0, 3, 5, 6, 7, 10),
    intArrayOf(0, 3, 5, 6, 10),
    intArrayOf(0, 2, 3, 4, 7, 9),
)

internal fun ScaleType.intervals(): IntArray = SCALE_INTERVALS[scaleIndex]

internal data class Degree(val degree: Int, val offScale: Boolean)

/** Mirrors `midi_to_lick_degree`: nearest scale tone, no wrap, ties to the lower index. */
internal fun midiToDegree(midi: Int, root: Int, scale: ScaleType, lickOctave: Int): Degree {
    val rel = midi - (root + lickOctave * 12)
    val octave = rel.floorDiv(12)
    val pc = rel.mod(12)
    val iv = scale.intervals()
    var best = 0
    var bestDist = Int.MAX_VALUE
    for (i in iv.indices) {
        val d = abs(iv[i] - pc)
        if (d < bestDist) { bestDist = d; best = i }
    }
    return Degree(octave * iv.size + best, bestDist != 0)
}

/** Octave of the lowest note above the root, as the lick renderer's `lickOctave`. */
internal fun lickOctaveFor(lowestMidi: Int, root: Int): Int =
    (lowestMidi - root).floorDiv(12).coerceIn(0, 8)
