package org.balch.orpheus.djapp.ai.songref

import org.balch.orpheus.features.pulsar.models.LpgMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

private const val TOLERANCE = 0.08f

private val GRID_ORDER = listOf(
    PluckGrid.DOWNBEATS to listOf(0f),
    PluckGrid.EIGHTH_OFFBEATS to listOf(0.5f),
    PluckGrid.EIGHTHS to listOf(0f, 0.5f),
    PluckGrid.SIXTEENTH_OFFBEATS to listOf(0.25f, 0.75f),
    PluckGrid.TRIPLETS to listOf(0f, 1f / 3, 2f / 3),
    PluckGrid.SIXTEENTHS to listOf(0f, 0.25f, 0.5f, 0.75f),
)

private fun lpgModeOf(grid: PluckGrid) = when (grid) {
    PluckGrid.DOWNBEATS, PluckGrid.MIXED -> LpgMode.PLUCK
    PluckGrid.EIGHTHS -> LpgMode.PLUCK_REPEAT_8TH
    PluckGrid.EIGHTH_OFFBEATS -> LpgMode.PLUCK_REPEAT_8TH_OFF
    PluckGrid.SIXTEENTH_OFFBEATS -> LpgMode.PLUCK_REPEAT_16TH_OFF
    PluckGrid.TRIPLETS -> LpgMode.PLUCK_REPEAT_TRIPLET
    PluckGrid.SIXTEENTHS -> LpgMode.PLUCK_REPEAT
}

// Distance from a bar position to the nearest point of a grid, counting the next beat's first point.
private fun distance(pos: Float, points: List<Float>): Float {
    val f = pos - floor(pos)
    return (points + (points.first() + 1f)).minOf { abs(f - it) }
}

private fun snap(pos: Float, points: List<Float>): Float {
    val base = floor(pos)
    val f = pos - base
    return base + (points + (points.first() + 1f)).minBy { abs(f - it) }
}

/** Stroke grid of the chordal [systems]: every onset column, not the merged chord events; null without any. */
internal fun pluckOf(systems: List<TabSystem>): SongPluck? {
    val positions = mutableListOf<Float>()
    var bars = 0f
    var strokes = 0
    for (system in systems) {
        val cols = system.columns.map { it.column }
        if (cols.isEmpty()) continue
        if (system.hasInteriorBars()) {
            val segments = barSegments(system, cols)
            if (segments.isEmpty()) continue
            strokes += cols.size
            val used = mutableSetOf<Int>()
            for (col in cols) {
                val k = segments.indexOfFirst { col <= it.last }.let { if (it < 0) segments.lastIndex else it }
                used += k
                positions += system.beatInBar(segments[k], col).coerceIn(0f, 4f)
            }
            bars += used.size
        } else {
            strokes += cols.size
            val beats = spacingGaps(cols)
            var at = 0f
            for (b in beats) { positions += at % 4f; at += b }
            bars += at / 4f
        }
    }
    if (positions.isEmpty() || bars <= 0f) return null
    val rhythm = if (systems.filter { it.columns.isNotEmpty() }.all { it.hasInteriorBars() }) RhythmSource.BARS else RhythmSource.SPACING
    // Hand-spaced mixed subdivisions only fit the 16th grid; re-picking every 16th would buzz, so call it mixed.
    val grid = GRID_ORDER.firstOrNull { (_, pts) -> positions.all { distance(it, pts) <= TOLERANCE } }
        ?.takeUnless { rhythm == RhythmSource.SPACING && it.first == PluckGrid.SIXTEENTHS }
    val onsets = positions.map { p ->
        val snapped = grid?.let { snap(p, it.second) } ?: p
        ((snapped % 4f) * 100).roundToInt() / 100f
    }.distinct().sorted()
    val kind = grid?.first ?: PluckGrid.MIXED
    return SongPluck(kind, lpgModeOf(kind), onsets, (strokes / bars * 10).roundToInt() / 10f, rhythm)
}
