package org.balch.orpheus.djapp.ai.songref

import kotlin.math.max
import kotlin.math.roundToInt

/** One fretted note; [string] counts from the top line (0) and [fret] is as written, before capo. */
internal data class TabNote(
    val midi: Int,
    val technique: String?,
    val ghost: Boolean = false,
    val string: Int = 0,
    val fret: Int = 0,
)

internal data class TabColumn(val column: Int, val notes: List<TabNote>)

internal data class TabSystem(
    val firstLine: Int,
    val lineCount: Int,
    val tuning: List<Int>,
    val bars: List<Int>,
    val width: Int,
    val columns: List<TabColumn>,
)

/** A melody note or, with a null [midi], a rest. */
internal data class TimedNote(val midi: Int?, val beats: Float, val technique: String? = null, val ghost: Boolean = false, val restrike: Boolean = false)

private val STANDARD_GUITAR = listOf(64, 59, 55, 50, 45, 40)
private val STANDARD_BASS = listOf(43, 38, 33, 28)
private const val BODY_CHARS = "-0123456789|hpbr/\\~x()*.<>= "
private val GUITAR_TOP = listOf(4, 11, 7, 2)
private val BASS_PCS = listOf(7, 2, 9, 4)
// The separator after the string name may be missing: `e--7-7--|`.
private val LABELLED = Regex("""(?s)^\s*([A-Ga-g][#b]?)(\s*[|:]|\s+|(?=-))(.*)$""")
private val UNLABELLED = Regex("""(?s)^\s*[|:]?(.*)$""")

private data class TabRow(val label: String?, val body: String)

private fun expandTabs(line: String): String = buildString {
    for (c in line) {
        if (c == '\t') { append(' '); while (length % 8 != 0) append(' ') } else append(c)
    }
}.trimEnd()

private fun splitTabLine(raw: String): TabRow? {
    val line = expandTabs(raw)
    val labelled = LABELLED.matchEntire(line)
    val label = labelled?.groupValues?.get(1)
    val body = labelled?.groupValues?.get(3) ?: UNLABELLED.matchEntire(line)?.groupValues?.get(1) ?: return null
    val chars = body.filterNot { it.isWhitespace() }
    if (chars.count { it == '-' } < (if (label != null) 2 else 4)) return null
    if (chars.count { it in BODY_CHARS } * 5 < chars.length * 4) return null
    if (label == null && '|' !in line) return null
    return TabRow(label, body)
}

internal fun isTabLine(line: String): Boolean = splitTabLine(line) != null

/** Systems of 6 tab lines (4 for bass) in text order, splitting runs of stacked systems. */
internal fun findTabSystems(lines: List<String>, capo: Int = 0): List<TabSystem> {
    val rows = lines.map { splitTabLine(it) }
    val systems = mutableListOf<TabSystem>()
    var i = 0
    while (i < rows.size) {
        if (rows[i] == null) { i++; continue }
        var j = i
        while (j < rows.size && rows[j] != null) j++
        var run = (i until j).map { it to rows[it]!! }
        // Unlabelled rows among labelled strings are annotations such as palm-mute marks.
        if (run.size != 4 && run.size != 6 && run.count { it.second.label != null } >= 4) run = run.filter { it.second.label != null }
        val groups = if (run.all { it.second.label != null }) groupByLabels(run) else chunkRun(run)
        groups.filter { it.size >= 4 }.forEach { g -> systems += buildSystem(g.first().first, g.map { it.second }, capo) }
        i = j
    }
    return systems
}

private fun labelPc(label: String): Int? = pitchClassOf(label.replaceFirstChar { it.uppercaseChar() })

private fun labelPc(row: TabRow): Int? = row.label?.let(::labelPc)

// A group ends at 6 strings or at a full G D A E bass; a lowercase low e stays in its group.
private fun groupByLabels(run: List<Pair<Int, TabRow>>): List<List<Pair<Int, TabRow>>> {
    val groups = mutableListOf<MutableList<Pair<Int, TabRow>>>()
    var current = mutableListOf<Pair<Int, TabRow>>()
    for (row in run) {
        current += row
        if (current.size == 6 || current.map { labelPc(it.second) } == BASS_PCS) { groups += current; current = mutableListOf() }
    }
    if (current.isNotEmpty()) groups += current
    return groups
}

private fun chunkRun(run: List<Pair<Int, TabRow>>): List<List<Pair<Int, TabRow>>> {
    val groups = mutableListOf<List<Pair<Int, TabRow>>>()
    var rest = run
    while (rest.size >= 6) { groups += rest.take(6); rest = rest.drop(6) }
    if (rest.size >= 4) groups += rest.take(4)
    return groups
}

private fun buildSystem(firstLine: Int, rows: List<TabRow>, capo: Int): TabSystem {
    val guitarLabels = rows.first().label == "e" || rows.take(4).map { labelPc(it) } == GUITAR_TOP
    val standard = if (rows.size >= 5 || guitarLabels) STANDARD_GUITAR else STANDARD_BASS
    val tuning = rows.mapIndexed { k, row -> row.label?.let { retune(standard[k], it) } ?: standard[k] }
    val width = rows.maxOf { it.body.length }
    val bars = (0 until width).filter { col -> rows.count { it.body.getOrNull(col) == '|' } * 2 >= rows.size }
    val notes = rows.flatMapIndexed { k, row -> parseRow(row.body, k, tuning[k] + capo) }
    val columns = notes.groupBy({ it.first }, { it.second }).entries.sortedBy { it.key }.map { TabColumn(it.key, it.value) }
    // Strummed chords get their hand-typed off-by-one strings back; a system that is not chordal as typed keeps exact columns.
    val plain = TabSystem(firstLine, rows.size, tuning, bars, width, columns)
    val merged = mergeShiftedStrings(columns)
    return if (merged.size < columns.size && plain.copy(columns = merged).isChordal()) plain.copy(columns = merged) else plain
}

// A lone fragment next to a strum (3+ notes) with no string in common is that strum typed a column apart; the smaller joins the larger.
// Single notes stepping across strings are a melody and never merge.
private fun mergeShiftedStrings(columns: List<TabColumn>): List<TabColumn> {
    val out = columns.toMutableList()
    var i = 0
    while (i < out.size - 1) {
        val a = out[i]
        val b = out[i + 1]
        if (b.column - a.column == 1 && (a.notes.size >= 3 || b.notes.size >= 3) && a.notes.none { n -> b.notes.any { it.string == n.string } }) {
            out[i] = TabColumn(if (b.notes.size > a.notes.size) b.column else a.column, (a.notes + b.notes).sortedBy { it.string })
            out.removeAt(i + 1)
        } else {
            i++
        }
    }
    return out
}

// The pitch with the label's class nearest the standard string, so drop D and Eb tunings read right.
private fun retune(standard: Int, label: String): Int {
    val pc = labelPc(label) ?: return standard
    return (standard - 6..standard + 5).first { it.mod(12) == pc }
}

private fun parseRow(body: String, string: Int, open: Int): List<Pair<Int, TabNote>> {
    val out = mutableListOf<Pair<Int, TabNote>>()
    var technique: String? = null
    var ghost = false
    var i = 0
    while (i < body.length) {
        val c = body[i]
        if (c.isDigit()) {
            var fret = c - '0'
            var len = 1
            val next = body.getOrNull(i + 1)
            if (next != null && next.isDigit() && fret * 10 + (next - '0') <= 24) { fret = fret * 10 + (next - '0'); len = 2 }
            out += i to TabNote(open + fret, technique, ghost, string, fret)
            technique = null
            ghost = false
            i += len
            continue
        }
        when (c) {
            'h' -> technique = "hammer"
            'p' -> technique = "pull"
            '/', '\\' -> technique = "slide"
            'b', 'r' -> technique = if (afterNote(body, i)) (if (c == 'b') "bend" else "release") else null
            '=' -> {}
            '(' -> ghost = true
            '~' -> out.lastOrNull()?.let { (col, n) -> if (n.technique == null) out[out.size - 1] = col to n.copy(technique = "vibrato") }
            else -> { technique = null; ghost = false }
        }
        i++
    }
    return out
}

/** More than half the onset columns hold 3+ notes. */
internal fun TabSystem.isChordal(): Boolean = columns.count { it.notes.size >= 3 } * 2 > columns.size

/** Named chord shapes in order, low string first in the shape; consecutive repeats of a name merge. */
internal fun chordEvents(system: TabSystem): List<Pair<ParsedChord, String>> {
    val out = mutableListOf<Pair<ParsedChord, String>>()
    for (col in system.columns) {
        if (col.notes.size < 2) continue
        val chord = nameChordShape(col.notes.map { it.midi }) ?: continue
        if (out.lastOrNull()?.first?.name == chord.name) continue
        out += chord to shapeOf(system, col)
    }
    return out
}

private fun shapeOf(system: TabSystem, col: TabColumn): String {
    val frets = (system.lineCount - 1 downTo 0).map { s -> col.notes.firstOrNull { it.string == s }?.fret }
    val text = frets.map { it?.toString() ?: "x" }
    return if (frets.any { (it ?: 0) > 9 }) text.joinToString("-") else text.joinToString("")
}

// `10b12====r10`: the `=` sustain sits between a bend and its release.
private fun afterNote(body: String, i: Int): Boolean {
    var k = i - 1
    while (k >= 0 && body[k] == '=') k--
    return k >= 0 && (body[k].isDigit() || body[k] == ')')
}

private class Onset(val column: Int, val note: TabNote, val bends: MutableList<TabNote> = mutableListOf())

// Highest note per column; a bend or release on the same string joins the note before it instead of starting its own.
private fun melodyOnsets(system: TabSystem): List<Onset> {
    val out = mutableListOf<Onset>()
    for (col in system.columns) {
        val top = col.notes.maxBy { it.midi }
        val last = out.lastOrNull()
        val sameString = last != null && (last.bends.lastOrNull() ?: last.note).string == top.string
        if ((top.technique == "bend" || top.technique == "release") && sameString) last!!.bends += top else out += Onset(col.column, top)
    }
    return out
}

/** Bar columns with tab on both sides; a closing or opening `|` or `||` does not time a system. */
internal fun TabSystem.hasInteriorBars(): Boolean {
    val set = bars.toSet()
    var lead = 0
    while (lead in set) lead++
    var tail = width - 1
    while (tail in set) tail--
    return bars.any { it in lead..tail }
}

private fun MutableList<TimedNote>.emit(onset: Onset, beats: Float) {
    val share = beats / (onset.bends.size + 1)
    add(TimedNote(onset.note.midi, share, onset.note.technique, onset.note.ghost))
    onset.bends.forEach { add(TimedNote(it.midi, share, it.technique, it.ghost)) }
}

/** Melody notes of the systems in order: exact from bar lines when every system has them, else guessed from spacing. */
internal fun timeNotes(systems: List<TabSystem>): Pair<List<TimedNote>, RhythmSource> {
    val out = mutableListOf<TimedNote>()
    for (system in systems) {
        val onsets = melodyOnsets(system)
        if (onsets.isEmpty()) continue
        if (!system.hasInteriorBars()) { out += spacingTimes(onsets); continue }
        val (leadIn, timed) = barTimes(system, onsets)
        // The gap before a system's first note extends whatever came before; at the very start it is a rest.
        if (out.isNotEmpty()) {
            if (leadIn > 0f) out[out.size - 1] = out.last().let { it.copy(beats = it.beats + leadIn) }
        } else if (leadIn >= 0.25f) {
            out += TimedNote(null, leadIn)
        }
        out += timed
    }
    val rhythm = if (systems.isNotEmpty() && systems.all { it.hasInteriorBars() }) RhythmSource.BARS else RhythmSource.SPACING
    return mergeRests(out) to rhythm
}

// The commonest column gap is an eighth note.
internal fun spacingGaps(columns: List<Int>): List<Float> {
    val gaps = columns.zipWithNext { a, b -> b - a }
    val unit = gaps.filter { it > 0 }.groupingBy { it }.eachCount()
        .maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key })?.key ?: 1
    return gaps.map { max(0.25f, (it.toFloat() / unit * 0.5f * 4).roundToInt() / 4f) } + 0.5f
}

private fun spacingTimes(onsets: List<Onset>): List<TimedNote> {
    val beats = spacingGaps(onsets.map { it.column })
    val out = mutableListOf<TimedNote>()
    onsets.forEachIndexed { i, onset -> out.emit(onset, beats[i]) }
    return out
}

/** Column ranges of the bars with tab in them; the last runs to the edge only when a column in [onsets] sits there. */
internal fun barSegments(system: TabSystem, onsets: List<Int>): List<IntRange> {
    val segments = mutableListOf<IntRange>()
    var start = 0
    for (bar in system.bars) {
        if (bar - start - 1 > 0) segments += start until bar
        start = bar + 1
    }
    if (start < system.width && onsets.any { it >= start }) segments += start until system.width
    return segments
}

/**
 * Beat of [col] in the bar [seg], after a pad column when the first is empty and the width is not a multiple of 4.
 * A beat is a whole number of columns, ties rounded down, so a bar typed a column or two long or short stays on the beat.
 */
internal fun TabSystem.beatInBar(seg: IntRange, col: Int): Float {
    val width = seg.last - seg.first + 1
    val pad = if (columns.none { it.column == seg.first } && width % 4 != 0) 1 else 0
    val slots = max(1, width - pad)
    val perBeat = max(1, (slots + 1) / 4)
    return (col - seg.first - pad).toFloat() / perBeat
}

// Each bar is 4 beats (see beatInBar), snapped to sixteenths; an empty bar is a rest.
// Returns the lead-in before the first note when it sits in the first bar, and the timed notes.
private fun barTimes(system: TabSystem, onsets: List<Onset>): Pair<Float, List<TimedNote>> {
    val segments = barSegments(system, onsets.map { it.column })
    if (segments.isEmpty()) return 0f to spacingTimes(onsets)

    val barOf = IntArray(onsets.size)
    val at = FloatArray(onsets.size)
    onsets.forEachIndexed { i, onset ->
        val k = segments.indexOfFirst { onset.column <= it.last }.let { if (it < 0) segments.lastIndex else it }
        val beat = (system.beatInBar(segments[k], onset.column) * 4).roundToInt().coerceIn(0, 15) / 4f
        barOf[i] = k
        at[i] = max(4f * k + beat, if (i > 0) at[i - 1] + 0.25f else 0f)
    }
    val filled = barOf.toSet()
    val out = mutableListOf<TimedNote>()
    val leadIn = if (barOf[0] == 0) at[0] else 0f
    var cursor = leadIn
    for (i in onsets.indices) {
        if (at[i] > cursor) out += TimedNote(null, at[i] - cursor)
        val end = if (i + 1 < onsets.size) {
            val empty = (barOf[i] + 1 until barOf[i + 1]).firstOrNull { it !in filled }
            max(if (empty != null) 4f * empty else at[i + 1], at[i] + 0.25f)
        } else {
            max(4f * (barOf[i] + 1), at[i] + 0.25f)
        }
        out.emit(onsets[i], end - at[i])
        cursor = end
    }
    val total = 4f * segments.size
    if (total > cursor) out += TimedNote(null, total - cursor)
    return leadIn to out
}

private fun mergeRests(notes: List<TimedNote>): List<TimedNote> {
    val out = mutableListOf<TimedNote>()
    for (n in notes) {
        val last = out.lastOrNull()
        if (n.midi == null && last != null && last.midi == null) out[out.size - 1] = last.copy(beats = last.beats + n.beats) else out += n
    }
    return out
}
