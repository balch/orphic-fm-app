package org.balch.orpheus.djapp.ai.licklab

import org.balch.orpheus.features.pulsar.models.Lick
import org.balch.orpheus.features.pulsar.models.LickStep
import org.balch.orpheus.features.pulsar.models.Rest
import org.balch.orpheus.features.pulsar.models.Vibe
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** [chromaticNotes] are step indices snapped from off-scale notes; [chromaticMidi] is what was played, same order. */
data class ExportedLick(
    val steps: List<LickStep>,
    val loopLength: Int,
    val chromaticNotes: List<Int> = emptyList(),
    val chromaticMidi: List<Int> = emptyList(),
    /** Notes dropped because two snapped to the same 16th (the later one is kept). */
    val mergedCount: Int = 0,
    /** Step index to the degree it was clamped from, for notes outside -32..63. */
    val clamped: Map<Int, Int> = emptyMap(),
    /** Step indices played under a held key without a slide: a paste re-attacks them. */
    val hammered: Set<Int> = emptySet(),
) {
    /** More steps than a [org.balch.orpheus.features.pulsar.models.Lick] accepts. */
    val overCap: Boolean get() = steps.size > Lick.MAX_LICK_STEPS
}

object TakeExport {
    private const val GRID = 0.25

    /** Played on a track that follows chords: the sequencer transposes the lick, live play did not. */
    const val FOLLOWS_CHORDS_NOTE = "follows chords: played untransposed, a paste may transpose with the chords"

    private fun snap(beat: Double): Double = (beat / GRID).roundToInt() * GRID

    internal fun snappedBar(firstStart: Double, beatsPerBar: Int): Int = floor(snap(firstStart) / beatsPerBar).toInt()

    fun toSteps(take: Take, vibe: Vibe, slot: SlotRef): ExportedLick {
        val bar = take.notes.firstOrNull()?.let { snappedBar(it.startBeat, take.beatsPerBar) } ?: take.startBar
        val barStart = (bar * take.beatsPerBar).toDouble()
        // Quantise starts; a note that lands on its predecessor's start replaces it.
        val quantised = ArrayList<Pair<Double, TakeNote>>()
        var merged = 0
        for (n in take.notes) {
            val s = snap(n.startBeat)
            if (quantised.lastOrNull()?.first == s) { quantised.removeAt(quantised.lastIndex); merged++ }
            quantised += s to n
        }
        val steps = ArrayList<LickStep>()
        val chromatic = ArrayList<Int>()
        val chromaticMidi = ArrayList<Int>()
        val clamped = LinkedHashMap<Int, Int>()
        val hammered = LinkedHashSet<Int>()
        var cursor = barStart
        var end = barStart
        quantised.forEachIndexed { i, (start, n) ->
            val nextStart = quantised.getOrNull(i + 1)?.first ?: Double.MAX_VALUE
            val stop = minOf(max(snap(n.endBeat), start + GRID), nextStart)
            if (start > cursor) steps += Rest((start - cursor).toFloat())
            val degree = when (val d = LickPitch.midiToDegree(n.midi, vibe, slot)) {
                is DegreeOrChromatic.Degree -> d.degree
                is DegreeOrChromatic.Chromatic -> {
                    chromatic += steps.size
                    chromaticMidi += n.midi
                    d.nearest
                }
            }
            val safe = degree.coerceIn(LickStep.MIN_DEGREE, LickStep.MAX_DEGREE)
            if (safe != degree) clamped[steps.size] = degree
            val prev = quantised.getOrNull(i - 1)?.second
            if (prev != null && prev.endBeat >= n.startBeat && !n.slidIn) hammered += steps.size
            steps += LickStep(
                scaleDegree = safe,
                duration = (stop - start).toFloat(),
                velocity = (n.velocity * 100).roundToInt() / 100f,
                glideRate = if (n.slidIn) n.glideRate.coerceIn(0f, 1f) else -1f,
            )
            cursor = stop
            end = stop
        }
        val bars = max(1, ceil((end - barStart) / take.beatsPerBar).toInt())
        return ExportedLick(steps, bars * take.beatsPerBar, chromatic, chromaticMidi, merged, clamped, hammered)
    }

    /** Paste-ready Kotlin for the vibe file: the property assignment, or a bare `Lick(` for a pool entry. */
    fun toKotlin(lick: ExportedLick, slot: SlotRef, followsChords: Boolean = false): String {
        val (header, prefix) = when (slot.slot) {
            LickSlot.Lead -> null to "lick = "
            LickSlot.Bass -> null to "bassLine = "
            LickSlot.Pool -> "// lickRotation pool entry ${slot.index}" to ""
            LickSlot.Solo -> "// solo lick, track ${slot.index} (melodicOverride)" to "lick = "
        }
        return buildString {
            if (lick.overCap) appendLine("// over ${Lick.MAX_LICK_STEPS} steps: split this take before pasting")
            if (followsChords) appendLine("// $FOLLOWS_CHORDS_NOTE")
            if (header != null) appendLine(header)
            appendLine("${prefix}Lick(steps = listOf(")
            lick.steps.forEachIndexed { i, s ->
                append("    ").append(stepText(s)).append(',')
                val c = lick.chromaticNotes.indexOf(i)
                if (c >= 0) append(" // played ").append(playedName(lick.chromaticMidi.getOrNull(c)))
                lick.clamped[i]?.let { append(" // clamped from degree ").append(it) }
                if (i in lick.hammered) append(" // hammered")
                appendLine()
            }
            append("), loopLength = ${lick.loopLength})")
        }
    }

    private fun playedName(midi: Int?): String {
        if (midi == null) return "off the scale"
        val name = LickPitch.pitchName(midi)
        return if (name.length == 1) "$name natural" else name
    }

    private fun stepText(s: LickStep): String = when {
        s.rest -> "Rest(${f(s.duration)})"
        s.glideRate >= 0f -> "LickStep(${s.scaleDegree}, ${f(s.duration)}, ${f(s.velocity)}, glideRate = ${f(s.glideRate)})"
        else -> "LickStep(${s.scaleDegree}, ${f(s.duration)}, ${f(s.velocity)})"
    }

    /** A Float literal with at most 2 decimals and no platform-specific formatting: `1f`, `0.5f`, `0.25f`. */
    private fun f(x: Float): String {
        val hundredths = (x * 100).roundToInt()
        val whole = hundredths / 100
        val frac = hundredths % 100
        if (frac == 0) return "${whole}f"
        val digits = frac.toString().padStart(2, '0').trimEnd('0')
        return "$whole.${digits}f"
    }
}
