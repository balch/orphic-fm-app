package org.balch.orpheus.djapp.ai.songref

import org.balch.orpheus.features.pulsar.models.RootNote
import org.balch.orpheus.features.pulsar.models.ScaleType

private val CANDIDATE_MODES = listOf(
    ScaleType.MAJOR, ScaleType.MINOR, ScaleType.DORIAN,
    ScaleType.MIXOLYDIAN, ScaleType.PHRYGIAN, ScaleType.LYDIAN,
)

// +1 minor third, -1 major third, 0 neither (pentatonics, sus chords).
private fun IntArray.thirdSign(): Int = when {
    3 in this && 4 !in this -> 1
    4 in this && 3 !in this -> -1
    else -> 0
}

private fun scaleName(root: Int, scale: ScaleType): String =
    pitchClassName(root) + " " + scale.name.lowercase().replace('_', ' ')

private data class Candidate(val root: Int, val scale: ScaleType, val score: Double)

/** Best key for the chords and notes; null when there is nothing to go on. */
internal fun guessKey(
    chords: List<ParsedChord>,
    notes: List<Int>,
    hint: String?,
    fixedRoot: RootNote? = null,
    fixedScale: ScaleType? = null,
): SongKey? {
    if (chords.isEmpty() && notes.isEmpty()) return null
    val hinted = hint?.let { parseChordName(it) }
    val hintMinor = hinted?.let { chordTones(it.quality).thirdSign() }
    val tonics = when {
        fixedRoot != null -> listOf(fixedRoot.noteIndex)
        hinted != null -> listOf(hinted.root)
        else -> rankTonics(chords, notes)
    }
    val modes = when {
        fixedScale != null -> listOf(fixedScale)
        hintMinor != null && hintMinor != 0 ->
            CANDIDATE_MODES.filter { it.intervals().thirdSign() == hintMinor }
        else -> CANDIDATE_MODES
    }
    val topChord = chords.groupingBy { it.name }.eachCount().let { counts ->
        val max = counts.values.maxOrNull()
        chords.firstOrNull { counts[it.name] == max }
    }
    val lastNote = notes.lastOrNull()?.rem(12)
    val topNotePc = notes.groupingBy { it % 12 }.eachCount().let { counts ->
        val max = counts.values.maxOrNull()
        notes.map { it % 12 }.firstOrNull { counts[it] == max }
    }
    val lowestPc = notes.minOrNull()?.rem(12)

    // Stage 2: the mode on a fixed root, by fit, notes, third match and a small MAJOR/MINOR preference.
    fun modesOn(root: Int): List<Candidate> = modes.map { scale ->
        val inScale = BooleanArray(12).also { m -> scale.intervals().forEach { m[(root + it) % 12] = true } }
        val third = scale.intervals().thirdSign()
        var s = 0.0
        for (c in chords) {
            val tones = chordTones(c.quality)
            val out = tones.count { !inScale[(c.root + it) % 12] }
            s += if (out == 0) 2.0 else 2.0 - 1.5 * out
            if (c.root == root) {
                val ct = tones.thirdSign()
                if (ct != 0 && third != 0) s += if (ct == third) 1.5 else -2.0
            }
        }
        for (n in notes) if (inScale[n % 12]) s += 0.25
        if (chords.isNotEmpty()) {
            if (chords.first().root == root) s += 2.0
            if (chords.last().root == root) s += 1.0
            if (topChord?.root == root) s += 1.5
        } else {
            if (lastNote == root) s += 1.0
            if (topNotePc == root) s += 1.0
            if (lowestPc == root) s += 0.5
        }
        if (scale == ScaleType.MAJOR || scale == ScaleType.MINOR) s += 0.3
        Candidate(root, scale, s)
    }.sortedByDescending { it.score }

    val scored = modesOn(tonics.first())
    val runnerUp = tonics.getOrNull(1)?.let { modesOn(it).first() }
    val best = scored.first()
    // With the scale fixed and the root free, the contest is between roots in that scale.
    val second = scored.getOrNull(1)?.score ?: runnerUp?.score ?: 0.0
    val alternatives = (listOfNotNull(scored.getOrNull(1), runnerUp) + scored.drop(2)).take(2)
    val from = when {
        fixedRoot != null || fixedScale != null -> "request"
        hinted != null -> if (chords.isNotEmpty()) "page+chords" else "page"
        chords.isNotEmpty() && notes.isNotEmpty() -> "chords+notes"
        chords.isNotEmpty() -> "chords"
        else -> "notes"
    }
    return SongKey(
        rootNote = rootNoteOf(best.root),
        scaleType = best.scale,
        name = scaleName(best.root, best.scale),
        confidence = ((best.score - second) / maxOf(best.score, 1.0)).coerceIn(0.0, 1.0).toFloat(),
        from = from,
        alternatives = alternatives.map { scaleName(it.root, it.scale) },
    )
}

// Stage 1: roots ranked by tonic evidence alone, so borrowed chords cannot pull the root away by fit.
// Note evidence counts half when there are chords too; ties go to the first chord's root, then the lower pitch class.
// Notes are each column's lowest (see parse).
private fun rankTonics(chords: List<ParsedChord>, notes: List<Int>): List<Int> {
    val score = DoubleArray(12)
    if (chords.isNotEmpty()) {
        val counts = IntArray(12).also { c -> chords.forEach { c[it.root]++ } }
        val most = counts.max()
        score[chords.first().root] += 2.0
        score[chords.last().root] += 1.0
        score[chords.first { counts[it.root] == most }.root] += 1.5
        for (pc in 0..11) score[pc] += 0.5 * counts[pc] * 4.0 / chords.size
    }
    if (notes.isNotEmpty()) {
        val w = if (chords.isEmpty()) 1.0 else 0.5
        val pcs = notes.map { it.mod(12) }
        val counts = IntArray(12).also { c -> pcs.forEach { c[it]++ } }
        val most = counts.max()
        score[pcs.last()] += w
        score[pcs.first { counts[it] == most }] += w
        score[notes.min().mod(12)] += 0.5 * w
        // A pitch class that fills the tab outweighs one that only ends it or sits lowest.
        for (pc in 0..11) score[pc] += 2.0 * w * counts[pc] / pcs.size
    }
    val first = chords.firstOrNull()?.root
    return (0..11).sortedWith(compareByDescending<Int> { score[it] }.thenByDescending { it == first }.thenBy { it })
}

/** Index of the chord root in the key's scale, null when it is not a scale tone. */
internal fun chordDegree(root: Int, key: SongKey): Int? {
    val pc = (root - key.rootNote.noteIndex).mod(12)
    return key.scaleType.intervals().indexOf(pc).takeIf { it >= 0 }
}
