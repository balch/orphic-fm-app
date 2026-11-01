package org.balch.orpheus.djapp.ai.licklab

import org.balch.orpheus.djapp.ai.songref.intervals
import org.balch.orpheus.djapp.ai.songref.noteName as songNoteName
import org.balch.orpheus.djapp.ai.songref.pitchClassName
import org.balch.orpheus.features.pulsar.models.LickSource
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.Vibe
import kotlin.math.abs

enum class LickSlot { Lead, Bass, Pool, Solo }

/** [index] is the pool entry for [LickSlot.Pool] and the track for [LickSlot.Solo]. */
data class SlotRef(val slot: LickSlot, val index: Int = 0)

sealed interface DegreeOrChromatic {
    data class Degree(val degree: Int) : DegreeOrChromatic

    /** Off the scale: [nearest] is the degree it snaps to. */
    data class Chromatic(val nearest: Int, val midi: Int) : DegreeOrChromatic
}

/** Degree 0 and degree/pitch conversion for a lick slot, mirroring `pulsar_pattern_gen.h`. */
object LickPitch {
    /** Mirrors `lick_octave_base`: the offset (a multiple of 12) added to the root pitch class. */
    internal fun octaveBase(lickOctave: Int, root: Int, rangeLow: Int, rangeHigh: Int): Int {
        if (lickOctave >= 0) return lickOctave * 12
        val mid = ((rangeLow + rangeHigh) / 2).coerceIn(24, 84)
        val base = (mid - root).coerceAtLeast(0)
        return base / 12 * 12
    }

    /** MIDI note of degree 0 for [slot]. Auto octave uses the genre's note range. */
    fun baseMidi(vibe: Vibe, slot: SlotRef): Int {
        val octave = if (slot.slot == LickSlot.Bass) vibe.bassLineOctave else vibe.lickOctave
        val root = vibe.rootNote.noteIndex
        val base = root + octaveBase(octave, root, vibe.genre.noteRangeLow, vibe.genre.noteRangeHigh)
        return if (slot.slot == LickSlot.Solo) {
            base + 12 * (vibe.tracks.getOrNull(slot.index)?.soloBehavior?.melodicOverride?.octaveShift ?: 0)
        } else {
            base
        }
    }

    /** Mirrors `lick_degree_to_midi`: negative degrees wrap down an octave per scale length. */
    fun degreeToMidi(degree: Int, vibe: Vibe, slot: SlotRef): Int =
        degreeToMidi(degree, baseMidi(vibe, slot), vibe.scaleType.intervals().asList())

    /** [degreeToMidi] from degree 0's MIDI note and the scale's semitones, as the UI state carries them. */
    fun degreeToMidi(degree: Int, baseMidi: Int, scale: List<Int>): Int {
        val octave = degree.floorDiv(scale.size)
        return (baseMidi + octave * 12 + scale[degree.mod(scale.size)]).coerceIn(0, 127)
    }

    /** The degree [midi] sits on above [baseMidi], or null when it is off the scale. */
    fun degreeOf(midi: Int, baseMidi: Int, scale: List<Int>): Int? {
        val rel = midi - baseMidi
        val i = scale.indexOf(rel.mod(12))
        return if (i < 0) null else rel.floorDiv(12) * scale.size + i
    }

    /** Inverse of [degreeToMidi]; ties snap to the lower degree, as `midi_to_lick_degree` does. */
    fun midiToDegree(midi: Int, vibe: Vibe, slot: SlotRef): DegreeOrChromatic {
        val iv = vibe.scaleType.intervals()
        val rel = midi - baseMidi(vibe, slot)
        val pc = rel.mod(12)
        var best = 0
        var bestDist = Int.MAX_VALUE
        for (i in iv.indices) {
            val d = abs(iv[i] - pc)
            if (d < bestDist) { bestDist = d; best = i }
        }
        val degree = rel.floorDiv(12) * iv.size + best
        return if (bestDist == 0) DegreeOrChromatic.Degree(degree) else DegreeOrChromatic.Chromatic(degree, midi)
    }

    /** Scientific pitch name with sharps, C4 = 60. */
    fun noteName(midi: Int): String = songNoteName(midi)

    /** Pitch class only, "C" or "C#". */
    internal fun pitchName(midi: Int): String = pitchClassName(midi)

    fun defaultSlotFor(vibe: Vibe, track: Int): SlotRef {
        val role = vibe.tracks.getOrNull(track)?.role
        val bass = role is TrackRole.Melodic && role.lickSource == LickSource.BASS
        return SlotRef(if (bass) LickSlot.Bass else LickSlot.Lead)
    }
}
