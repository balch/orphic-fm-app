package org.balch.orpheus.djapp.ai.licklab

import org.balch.orpheus.djapp.ai.songref.intervals
import org.balch.orpheus.features.pulsar.models.ScaleType
import kotlin.math.abs

/** Mirrors `kraken_apply` in `pulsar_kraken.h`: the note a Kraken shift ([target] 0..3, -1 home) plays. */
internal object LickKraken {
    // {root offset, scale index} per home scale index, as kKrakenRelative / kKrakenParallel.
    private val RELATIVE = intArrayOf(3, 1, -3, 0, -3, 10, -4, 1, 0, 4, 0, 5, -2, 1, 4, 0, 2, 0, 3, 1, 3, 2, 0, 11, 0, 12, 3, 15, 3, 2, -3, 13)
    private val PARALLEL = intArrayOf(0, 1, 0, 0, 0, 10, 0, 1, 0, 4, 0, 5, 0, 8, 0, 0, 0, 6, 0, 1, 0, 2, 0, 2, 0, 12, 0, 15, 0, 2, 0, 13)

    fun apply(note: Int, homeRoot: Int, homeScale: ScaleType, target: Int): Int {
        if (target !in 0..3) return note
        val home = homeScale.scaleIndex
        val (offset, scaleIndex) = when (target) {
            0 -> 5 to home
            1 -> 7 to home
            2 -> RELATIVE[home * 2] to RELATIVE[home * 2 + 1]
            else -> PARALLEL[home * 2] to PARALLEL[home * 2 + 1]
        }
        if (scaleIndex == home) return note + offset
        val from = homeScale.intervals()
        val to = ScaleType.entries.first { it.scaleIndex == scaleIndex }.intervals()
        val rel = (note - homeRoot).mod(12)
        val i = from.indexOf(rel)
        if (i < 0) return note + offset
        if (to.size == from.size) return note - rel + offset + to[i]
        return quantize(note + offset, (homeRoot + offset).mod(12), to)
    }

    /** Mirrors `quantize_to_scale`. */
    internal fun quantize(note: Int, root: Int, iv: IntArray): Int {
        if (iv.size >= 12) return note
        val rel = (note - root).mod(12)
        var best = iv[0]
        var bestDist = 12
        for (d in iv) {
            var dist = abs(rel - d)
            if (dist > 6) dist = 12 - dist
            if (dist < bestDist) { bestDist = dist; best = d }
        }
        var r = note - rel + best
        while (r < note - 6 && r + 12 <= 96) r += 12
        while (r > note + 6 && r - 12 >= 24) r -= 12
        if (r < 24) r += 12
        if (r > 96) r -= 12
        return r
    }
}
