package org.balch.orpheus.djapp.ai.songref

import org.balch.orpheus.features.pulsar.models.Lick
import org.balch.orpheus.features.pulsar.models.LickStep
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A timed melody as one or more Licks in [key], split so no part passes 16 beats or the lick step limit. */
internal fun toMelodies(
    notes: List<TimedNote>,
    rhythm: RhythmSource,
    key: SongKey,
    label: String,
    section: Int?,
    phraseSlot: Int?,
    firstIndex: Int,
): List<SongMelody> {
    val lowest = notes.mapNotNull { it.midi }.minOrNull() ?: return emptyList()
    val root = key.rootNote.noteIndex
    val octave = lickOctaveFor(lowest, root)
    return splitParts(notes).mapIndexed { p, part ->
        val melody = part.map { n ->
            val midi = n.midi ?: return@map MelodyNote(null, null, -1, n.beats, n.technique)
            val d = midiToDegree(midi, root, key.scaleType, octave)
            MelodyNote(midi, noteName(midi), d.degree, n.beats, n.technique, d.offScale)
        }
        val steps = part.zip(melody) { n, m -> LickStep(m.degree, n.beats, if (n.ghost) 0.4f else 0.8f, if (n.restrike) 0f else glideFor(n.technique)) }
        val total = part.sumOf { it.beats.toDouble() }
        SongMelody(
            index = firstIndex + p,
            section = section,
            label = if (p == 0) label else "$label (part ${p + 1})",
            phraseSlot = phraseSlot,
            rhythm = rhythm,
            notes = melody,
            lick = Lick(steps, (ceil(total / 4 - 1e-6) * 4).toInt()),
            lickOctave = octave,
            offScaleNotes = melody.count { it.offScale },
        )
    }
}

private fun glideFor(technique: String?): Float = when (technique) {
    "hammer", "pull" -> 0.1f
    "slide" -> 0.3f
    "bend", "release" -> 0.4f
    else -> -1f
}

private const val MAX_PART_BEATS = 16f
private const val EPS = 1e-4f

// A Fill track loops at most 16 beats. Cut at the last whole bar that fits, else the last step boundary.
// A note that cannot fit is cut at 16 beats; its rest restrikes (glideRate 0) at the start of the next part.
private fun splitParts(notes: List<TimedNote>): List<List<TimedNote>> {
    val parts = mutableListOf<List<TimedNote>>()
    var rest = notes
    while (rest.size > Lick.MAX_LICK_STEPS || rest.sumOf { it.beats.toDouble() } > MAX_PART_BEATS + EPS) {
        var beats = 0f
        var fit = 0
        var lastBar = 0
        while (fit < minOf(rest.size, Lick.MAX_LICK_STEPS) && beats + rest[fit].beats <= MAX_PART_BEATS + EPS) {
            beats += rest[fit].beats
            fit++
            if (isWholeBar(beats)) lastBar = fit
        }
        if (fit == 0) {
            val n = rest[0]
            parts += listOf(n.copy(beats = MAX_PART_BEATS))
            rest = listOf(n.copy(beats = n.beats - MAX_PART_BEATS, restrike = true)) + rest.drop(1)
        } else {
            val cut = if (lastBar > 0) lastBar else fit
            parts += rest.subList(0, cut)
            rest = rest.subList(cut, rest.size)
        }
    }
    parts += rest
    return parts
}

private fun isWholeBar(beats: Float): Boolean = beats > 0f && abs(beats / 4f - (beats / 4f).roundToInt()) < 1e-4f
