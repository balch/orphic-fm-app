package org.balch.orpheus.features.visualizations.viz.spectrogram

import androidx.compose.ui.graphics.Color
import org.balch.orpheus.core.plugin.viz.SpectrogramSpec
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/** The spectrogram's frequency axis: [ROWS] log-spaced rows from [F_MIN] (row 0) to [F_MAX]. */
object OrphogramScale {
    const val ROWS = SpectrogramSpec.ROWS
    const val F_MIN = SpectrogramSpec.F_MIN_HZ
    const val F_MAX = SpectrogramSpec.F_MAX_HZ

    /** Level a fresh or silent row starts from; well under any floor [levelIndex] can reach. */
    const val SILENT_DB = -200f

    /** The held peak never drops below this, so near-silence stays black instead of being stretched. */
    const val MIN_PEAK_DB = -50f

    /** How fast the held peak falls once the loud part has passed. */
    const val PEAK_RELEASE_DB_PER_SECOND = 8f

    fun rowHz(row: Int): Float = F_MIN * (F_MAX / F_MIN).pow(row.toFloat() / (ROWS - 1))

    /** Where [hz] sits on the axis, 0 at [F_MIN] and 1 at [F_MAX]. */
    fun axisFraction(hz: Float): Float = (ln(hz / F_MIN) / ln(F_MAX / F_MIN))

    // Black -> indigo -> violet -> electric blue -> pale lavender, cooled from the offline renderer's look.
    private val stops = arrayOf(
        intArrayOf(7, 6, 12), intArrayOf(60, 20, 160), intArrayOf(130, 50, 245),
        intArrayOf(60, 150, 255), intArrayOf(200, 195, 255),
    )

    /** The colour map in 256 steps, index 0 at the floor and 255 at the peak. */
    val palette: Array<Color> = Array(256) { i ->
        val pos = i / 255f * (stops.size - 1)
        val k = pos.toInt().coerceAtMost(stops.size - 2)
        val t = pos - k
        val a = stops[k]
        val b = stops[k + 1]
        Color(
            red = ((a[0] + (b[0] - a[0]) * t) / 255f),
            green = ((a[1] + (b[1] - a[1]) * t) / 255f),
            blue = ((a[2] + (b[2] - a[2]) * t) / 255f),
        )
    }

    /** [db] as a [palette] index: the floor is [rangeDb] under [peakDb]. */
    fun levelIndex(db: Float, peakDb: Float, rangeDb: Float): Int {
        val x = (db - (peakDb - rangeDb)) / rangeDb
        return (x * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    private const val FIELD_RADIUS = 5
    private val fieldWeights = FloatArray(2 * FIELD_RADIUS + 1) { k ->
        val d = (k - FIELD_RADIUS) / 2f
        kotlin.math.exp(-0.5f * d * d)
    }.let { w -> val sum = w.sum(); FloatArray(w.size) { w[it] / sum } }

    /**
     * The plasma field at row [r] of [column] (levels 0..1, low to high): the level plus its
     * blur down the column, so a lone partial still reaches the surface as a thin tube while
     * neighbouring partials pool into one body.
     */
    fun fieldAt(column: FloatArray, r: Int): Float {
        var blur = 0f
        for (k in fieldWeights.indices) {
            val i = (r + k - FIELD_RADIUS).coerceIn(0, column.size - 1)
            blur += fieldWeights[k] * column[i]
        }
        return (0.55f * column[r] + 0.9f * blur).coerceIn(0f, 1f)
    }

    /** The held peak after [seconds]: jumps up to a louder [columnPeakDb], falls slowly otherwise. */
    fun heldPeak(previousDb: Float, columnPeakDb: Float, seconds: Float): Float =
        max(MIN_PEAK_DB, max(columnPeakDb, previousDb - PEAK_RELEASE_DB_PER_SECOND * seconds))
}
