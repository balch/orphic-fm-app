package org.balch.orpheus.features.visualizations.viz

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.log10

/** Quietest peak a lane shows; anything below sits on the lane's centre line. */
internal const val LANE_FLOOR_DB = -48f

/** A per-block peak below this is silence: the lane draws no trace. */
internal const val LANE_SILENCE = 0.001f

/** A per-block peak as a lane height 0..1, in dB so a quiet hi-hat still reads beside the kick. */
internal fun laneLevel(peak: Float): Float {
    if (peak <= 0f) return 0f
    return ((20f * log10(peak) - LANE_FLOOR_DB) / -LANE_FLOOR_DB).coerceIn(0f, 1f)
}

/** Points a scrolled trace draws at most across its width; buckets merge samples beyond this. */
internal const val MAX_SCROLL_POINTS = 130

/** Samples a point merges so that [visible] samples take at most [MAX_SCROLL_POINTS] points. */
internal fun scrollStride(visible: Int): Int = ((visible + MAX_SCROLL_POINTS - 1) / MAX_SCROLL_POINTS).coerceAtLeast(1)

/**
 * The buckets a window of [visible] samples ending at [head] (scroll-relative) touches, first and
 * last, a bucket of slack each side so a point always waits past the clip on both edges.
 */
internal fun scrollBuckets(head: Double, visible: Int, stride: Int): IntRange =
    (kotlin.math.floor(head).toInt() - visible).floorDiv(stride) - 1..kotlin.math.ceil(head).toInt().floorDiv(stride) + 1

/** The loudest sample of [ring] (ending at [ringEnd], scroll-relative) at or after [from]. */
internal fun ringPeakSince(ring: FloatArray, ringEnd: Int, from: Int): Float {
    var peak = 0f
    for (i in maxOf(0, from - (ringEnd - ring.size)) until ring.size) if (ring[i] > peak) peak = ring[i]
    return peak
}

/** Lane trace width; the silent lanes' flat traces match it. */
internal val LANE_TRACE_WIDTH = 2.5.dp

/** Envelope half-height as a share of the lane: at rest, and at a full-velocity hit. */
private const val PUMP_REST_HALF = 0.32f
private const val PUMP_PEAK_HALF = 0.47f

/** A full pump lights the line this far toward white, the transport zip's own amount. */
private const val PUMP_LIGHTEN = 0.5f
private val LANE_METER_WIDTH = 3.dp

/** A [Stroke] rebuilt only when its pixel width changes (a new density), never per frame. */
internal class StrokeCache {
    private var stroke = Stroke(0f)

    fun of(width: Float): Stroke {
        if (stroke.width != width) stroke = Stroke(width)
        return stroke
    }
}

/**
 * The Orphoscope's Pulsar lanes: one band per track, each a mirrored peak envelope around its own
 * centre line, so eight tracks read side by side instead of piling on one axis. Butt-capped strokes
 * of a few dp and rects only: a much wider stroked or filled path this long becomes a CPU mask
 * re-uploaded every frame.
 */
internal class OrphoscopeLanes(private val colors: List<Color>) {
    private val paths = Array(colors.size) { Path() }
    private val levels = FloatArray(MAX_SCROLL_POINTS + 8)
    private val traceStroke = StrokeCache()

    /**
     * True when any track has signal in the [visible] samples up to [head], which is when the
     * lanes show. [ends] are the rings' ends, scroll-relative like [head].
     */
    fun anyLive(rings: Array<FloatArray>, ends: IntArray, head: Double, visible: Int): Boolean {
        val from = kotlin.math.floor(head).toInt() - visible
        for (t in rings.indices) if (ringPeakSince(rings[t], ends[t], from) >= LANE_SILENCE) return true
        return false
    }

    /**
     * Draws the lanes stacked in [area], each in its track colour. [glow] (0..1) brightens the
     * traces and each lane's level meter. Each lane's [pump] (0..1) swells its envelope and lights
     * its line toward white, as the transport's paused zip lights its path. The [rings] scroll
     * evenly: [head] (scroll-relative, like [ends]) sits at the right edge and [visible] samples
     * span the width, in buckets fixed to the samples, so a shape only ever slides. Returns the x
     * the traces start at, right of the meters.
     */
    fun draw(
        scope: DrawScope,
        rings: Array<FloatArray>,
        ends: IntArray,
        head: Double,
        visible: Int,
        area: Rect,
        glow: Float,
        pump: FloatArray,
    ): Float = with(scope) {
        val count = colors.size
        val laneH = area.height / count
        val traceWidth = LANE_TRACE_WIDTH.toPx()
        val meterWidth = LANE_METER_WIDTH.toPx()
        val traceLeft = area.left + meterWidth * 2f
        val traceW = area.right - traceLeft
        if (traceW <= 0f) return@with traceLeft
        val stride = scrollStride(visible)
        val buckets = scrollBuckets(head, visible, stride)
        val perSample = traceW / visible
        val from = kotlin.math.floor(head).toInt() - visible

        for (t in 0 until count) {
            val data = rings[t]
            val end = ends[t]
            val color = colors[t]
            val cy = area.top + laneH * (t + 0.5f)
            val swell = pump[t].coerceIn(0f, 1f)
            val half = laneH * (PUMP_REST_HALF + (PUMP_PEAK_HALF - PUMP_REST_HALF) * swell)
            val live = data.isNotEmpty() && ringPeakSince(data, end, from) >= LANE_SILENCE

            if (live) {
                // A meter of the latest level at the lane's edge. A tint across the whole lane read
                // the same but cost more than all eight traces together.
                val latest = laneLevel(data[data.size - 1]) * half
                drawRect(
                    color.copy(alpha = 0.5f + glow * 0.4f),
                    Offset(area.left, cy - latest),
                    Size(meterWidth, latest * 2f),
                )
            }
            val traceColor = lerp(color, Color.White, PUMP_LIGHTEN * swell)
                .copy(alpha = (0.45f + glow * 0.45f + 0.1f * swell).coerceAtMost(1f))
            if (!live) {
                // A silent track keeps its lane: a flat trace, so a kick that rests never vanishes.
                drawRect(traceColor, Offset(traceLeft, cy - traceWidth / 2f), Size(traceW, traceWidth))
                continue
            }
            drawRect(color.copy(alpha = 0.10f), Offset(traceLeft, cy - 0.5f), Size(traceW, 1f))

            var points = 0
            for (b in buckets) levels[points++] = laneLevel(bucketPeak(data, end, b, stride, signed = false)) * half
            // A bucket's point sits at its centre, placed by time, so the shape slides a fraction
            // of a sample per frame instead of jumping a bucket at a time.
            val firstX = area.right - (head - (buckets.first * stride + stride / 2.0)).toFloat() * perSample
            val step = stride * perSample
            // Upper edge, then the lower edge as a second contour of the same path.
            val path = paths[t]
            path.rewind()
            for (p in 0 until points) {
                val x = firstX + p * step
                val y = cy - levels[p]
                if (p == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            for (p in 0 until points) {
                val x = firstX + p * step
                val y = cy + levels[p]
                if (p == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            // The edge points wait outside the trace; the clip is a rect, so it stays on the GPU.
            clipRect(traceLeft, area.top, area.right, area.bottom) {
                drawPath(path, traceColor, style = traceStroke.of(traceWidth))
            }
        }
        traceLeft
    }
}
