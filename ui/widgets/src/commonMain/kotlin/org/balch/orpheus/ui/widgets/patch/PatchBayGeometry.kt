package org.balch.orpheus.ui.widgets.patch

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.balch.orpheus.core.audio.patch.PatchDest
import org.balch.orpheus.core.audio.patch.PatchSource
import org.balch.orpheus.core.audio.patch.VoiceSlot

/** A jack in the bay: an output on the left or a knob input on the right. */
sealed interface JackId {
    data class Out(val source: PatchSource) : JackId
    data class In(val dest: PatchDest, val slot: VoiceSlot) : JackId
}

/**
 * A cable from [from] to [to] as [samples] + 1 points: a cubic S that leaves and arrives level,
 * like the mockups. The middle point (index samples / 2) is where an amount knob sits.
 */
fun cableCurve(from: Offset, to: Offset, samples: Int = 24): List<Offset> {
    val n = samples.coerceAtLeast(1)
    val reach = maxOf(kotlin.math.abs(to.x - from.x) * 0.5f, 24f)
    val c1 = Offset(from.x + reach, from.y)
    val c2 = Offset(to.x - reach, to.y)
    return List(n + 1) { i ->
        val t = i.toFloat() / n
        val u = 1f - t
        val a = u * u * u
        val b = 3f * u * u * t
        val c = 3f * u * t * t
        val d = t * t * t
        Offset(
            a * from.x + b * c1.x + c * c2.x + d * to.x,
            a * from.y + b * c1.y + c * c2.y + d * to.y,
        )
    }
}

/** The index of the curve nearest [point] when it is within [tolerance], else null. */
fun hitCable(point: Offset, curves: List<List<Offset>>, tolerance: Float): Int? {
    var best: Int? = null
    var bestDistance = tolerance
    curves.forEachIndexed { index, curve ->
        val d = distanceToPolyline(point, curve)
        if (d <= bestDistance) {
            best = index
            bestDistance = d
        }
    }
    return best
}

/** The jack whose centre is nearest [point] when it is within [radius], else null. */
fun nearestJack(point: Offset, jacks: Map<JackId, Offset>, radius: Float): JackId? =
    jacks.entries
        .map { it.key to (it.value - point).getDistance() }
        .filter { it.second <= radius }
        .minByOrNull { it.second }
        ?.first

/**
 * Where each curve's amount knob sits: the curve point nearest its middle whose [box] (the dial at
 * [anchor] inside it) clears every knob placed before it. Ends are skipped so knobs stay off the
 * jacks. When nothing clears, the point with the least overlap wins. Same input, same layout.
 */
fun placeKnobs(curves: List<List<Offset>>, box: Size, anchor: Offset): List<Offset> {
    val placed = mutableListOf<Rect>()
    return curves.map { curve ->
        if (curve.isEmpty()) return@map Offset.Zero
        val mid = curve.size / 2
        val margin = curve.size / 6
        val candidates = (margin..(curve.lastIndex - margin))
            .sortedWith(compareBy({ kotlin.math.abs(it - mid) }, { it }))
            .ifEmpty { listOf(mid) }
        fun rectAt(i: Int) = Rect(curve[i] - anchor, box)
        val pick = candidates.firstOrNull { i -> placed.none { it.overlaps(rectAt(i)) } }
            ?: candidates.minBy { i -> placed.sumOf { overlapArea(it, rectAt(i)).toDouble() } }
        placed += rectAt(pick)
        curve[pick]
    }
}

private fun overlapArea(a: Rect, b: Rect): Float {
    val i = a.intersect(b)
    return if (i.width > 0f && i.height > 0f) i.width * i.height else 0f
}

private fun distanceToPolyline(p: Offset, line: List<Offset>): Float {
    if (line.isEmpty()) return Float.MAX_VALUE
    if (line.size == 1) return (line[0] - p).getDistance()
    var best = Float.MAX_VALUE
    for (i in 0 until line.lastIndex) best = minOf(best, distanceToSegment(p, line[i], line[i + 1]))
    return best
}

private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val lengthSq = ab.x * ab.x + ab.y * ab.y
    if (lengthSq == 0f) return (p - a).getDistance()
    val t = (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / lengthSq).coerceIn(0f, 1f)
    return (p - (a + ab * t)).getDistance()
}
