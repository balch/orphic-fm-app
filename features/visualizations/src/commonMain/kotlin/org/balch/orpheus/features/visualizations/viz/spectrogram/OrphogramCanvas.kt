package org.balch.orpheus.features.visualizations.viz.spectrogram

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.balch.orpheus.features.visualizations.viz.shader.PlasmaShader
import org.balch.orpheus.features.visualizations.viz.shader.PlasmaSksl
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A scrolling history of spectrum columns, [cols] x [OrphogramScale.ROWS], used as a ring:
 * each frame writes only the new columns. [level] holds each row's brightness (0..255) and
 * [field] the same blurred down the column, which the plasma shader melts into a surface; both
 * are one byte a pixel, highest frequency on the top row. With [paintPalette] it also paints
 * [image] in the flat colour map, for the Canvas fallback. Single-threaded, owned by the frame loop.
 */
internal class OrphogramRing(val cols: Int = 512, paintPalette: Boolean = true) {
    private val rows = OrphogramScale.ROWS
    val level = ByteArray(cols * rows)
    val field = ByteArray(cols * rows)
    val image: ImageBitmap? = if (paintPalette) ImageBitmap(cols, rows) else null
    private val canvas = image?.let { Canvas(it) }
    private val paint = Paint()

    /** The column the next write goes to; the newest written column is the one before it. */
    var head = 0
        private set

    /** Progress toward the next column, 0..1, so the scroll can move between whole columns. */
    var progress = 0f
        private set

    /** Columns written so far, never wrapping, so the plasma skin can scroll with the picture. */
    var written = 0L
        private set

    private val held = FloatArray(rows) { OrphogramScale.SILENT_DB }
    private val previous = FloatArray(rows) { OrphogramScale.SILENT_DB }
    private val column = FloatArray(rows)
    private var fresh = false
    private var lastSeen: FloatArray? = null
    private var peakDb = OrphogramScale.MIN_PEAK_DB
    private var sinceColumn = 0f

    init {
        paint.color = OrphogramScale.palette[0]
        canvas?.drawRect(0f, 0f, cols.toFloat(), rows.toFloat(), paint)
    }

    /**
     * Folds [latestDb] (one dB value per row, low to high) in, if it is a frame not seen yet, and
     * writes as many columns as [seconds] covers when [spanSeconds] fills the ring.
     */
    fun advance(latestDb: FloatArray?, seconds: Float, spanSeconds: Float, rangeDb: Float) {
        if (latestDb != null && latestDb !== lastSeen) {
            lastSeen = latestDb
            if (latestDb.size == rows) {
                // Max-hold between columns, so a slow scroll still shows a short hit.
                for (r in 0 until rows) held[r] = if (fresh) max(held[r], latestDb[r]) else latestDb[r]
            } else {
                held.fill(OrphogramScale.SILENT_DB)
            }
            fresh = true
        }
        sinceColumn += seconds
        val step = progress + seconds * cols / spanSeconds
        val count = step.toInt()
        progress = step - count
        if (count == 0) return

        // With nothing new, held still equals previous, so the column repeats.
        var columnPeak = OrphogramScale.SILENT_DB
        for (r in 0 until rows) columnPeak = max(columnPeak, held[r])
        peakDb = OrphogramScale.heldPeak(peakDb, columnPeak, sinceColumn)
        sinceColumn = 0f

        // A fast scroll writes several columns from one frame: ease them from the last column in.
        val written = min(count, cols)
        for (k in 1..written) writeColumn(k.toFloat() / written, rangeDb)
        held.copyInto(previous)
        fresh = false
    }

    private fun writeColumn(t: Float, rangeDb: Float) {
        for (r in 0 until rows) {
            val db = previous[r] + (held[r] - previous[r]) * t
            column[r] = OrphogramScale.levelIndex(db, peakDb, rangeDb) / 255f
        }
        for (r in 0 until rows) {
            val i = (rows - 1 - r) * cols + head
            level[i] = (column[r] * 255f + 0.5f).toInt().toByte()
            field[i] = (OrphogramScale.fieldAt(column, r) * 255f + 0.5f).toInt().toByte()
        }
        canvas?.let { paintColumn(it) }
        head = (head + 1) % cols
        written++
    }

    private fun paintColumn(canvas: Canvas) {
        val x = head.toFloat()
        var runStart = 0
        var runIndex = -1
        // Rows that share a colour go out as one rect; row 0 (lowest) is the bitmap's bottom.
        for (r in 0..rows) {
            val index = if (r == rows) -1 else (column[r] * 255f + 0.5f).toInt()
            if (index != runIndex) {
                if (runIndex >= 0) {
                    paint.color = OrphogramScale.palette[runIndex]
                    canvas.drawRect(x, (rows - r).toFloat(), x + 1f, (rows - runStart).toFloat(), paint)
                }
                runStart = r
                runIndex = index
            }
        }
    }
}

/** Gridlines at these frequencies, faint enough to sit behind the picture. */
private val gridHz = floatArrayOf(100f, 200f, 500f, 1_000f, 2_000f, 5_000f)
private val gridColor = Color.White.copy(alpha = 0.07f)

/**
 * Draws the spectrogram scrolling right to left, newest at the right edge, and advances it each
 * frame from [source] (the latest dB rows, or null when there are none). Plasma where runtime
 * shaders run; the flat colour map elsewhere.
 */
@Composable
internal fun OrphogramCanvas(
    modifier: Modifier,
    spanSeconds: () -> Float,
    rangeDb: () -> Float,
    source: () -> FloatArray?,
) {
    val shader = remember { PlasmaShader(PlasmaSksl.ORPHOGRAM) }
    DisposableEffect(shader) { onDispose { shader.dispose() } }
    val ring = remember(shader) { OrphogramRing(paintPalette = !shader.isSupported) }
    var frame by remember { mutableIntStateOf(0) }
    var time by remember { mutableFloatStateOf(0f) }
    val currentSource by rememberUpdatedState(source)
    LaunchedEffect(ring) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val seconds = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                ring.advance(currentSource(), seconds, spanSeconds(), rangeDb())
                time += seconds
                frame++
            }
        }
    }
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame
        if (shader.isSupported) drawPlasma(shader, ring, time) else drawRing(ring)
        drawGrid()
    }
}

private fun DrawScope.drawPlasma(shader: PlasmaShader, ring: OrphogramRing, time: Float) {
    shader.uniform("resolution", size.width, size.height)
    shader.uniform("time", time)
    // Matches drawRing: the newest column slides in from past the right edge as progress grows.
    shader.uniform("head", ring.head + ring.progress + 1f)
    shader.uniform("ringSize", ring.cols.toFloat(), OrphogramScale.ROWS.toFloat())
    // Screen widths scrolled so far; wrapped so float precision holds over a long session.
    val screens = (ring.written % (4096L * (ring.cols - 2)) + ring.progress) / (ring.cols - 2)
    shader.uniform("scroll", screens)
    shader.alphaImage("level", ring.level, ring.cols, OrphogramScale.ROWS)
    shader.alphaImage("field", ring.field, ring.cols, OrphogramScale.ROWS)
    shader.brush()?.let { drawRect(it) }
}

private fun DrawScope.drawRing(ring: OrphogramRing) {
    val image = ring.image ?: return
    drawRect(OrphogramScale.palette[0])
    val cols = ring.cols
    val h = size.height.roundToInt()
    // One column hangs off each side, so the scroll can slide by part of a column.
    val colWidth = size.width / (cols - 2)
    val right = size.width + (1f - ring.progress) * colWidth
    val left = right - cols * colWidth
    val olderCount = cols - ring.head
    val olderWidth = (olderCount * colWidth).roundToInt()
    val newerWidth = (cols * colWidth).roundToInt() - olderWidth
    translate(left = left) {
        if (olderCount > 0) {
            drawImage(
                image, IntOffset(ring.head, 0), IntSize(olderCount, OrphogramScale.ROWS),
                IntOffset.Zero, IntSize(olderWidth, h), filterQuality = FilterQuality.Low,
            )
        }
        if (ring.head > 0) {
            drawImage(
                image, IntOffset.Zero, IntSize(ring.head, OrphogramScale.ROWS),
                IntOffset(olderWidth, 0), IntSize(newerWidth, h), filterQuality = FilterQuality.Low,
            )
        }
    }
}

private fun DrawScope.drawGrid() {
    for (hz in gridHz) {
        val y = size.height * (1f - OrphogramScale.axisFraction(hz))
        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
    }
}
