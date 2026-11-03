package org.balch.orpheus.features.visualizations.viz.sprite

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb

/** False only on Android below 10, whose hardware canvas drops `drawVertices`. */
internal expect fun spriteBatchSupported(): Boolean

/**
 * A round sprite's alpha from centre (0) to rim (1), as (position, alpha) pairs in rising order.
 * Vertex colours tint it, so a gradient drawn at one strength can be stored normalized to 1.
 */
internal class SpriteCell(vararg val stops: Pair<Float, Float>)

/** Solid out to a one-texel soft rim, for a dot that should read as a plain circle. */
internal val SOLID_DISC = SpriteCell(0f to 1f, 0.94f to 1f, 1f to 0f)

/**
 * Draws thousands of tinted sprites in a few `drawVertices` calls instead of one draw call each.
 * Sprites land in the order they are added; a full mesh goes out and the next one continues it.
 * Nothing allocates per frame. Single-threaded, owned by the draw pass.
 */
internal class SpriteBatch(private val cells: List<SpriteCell>) {
    private val vertices = Vertices(
        VertexMode.Triangles,
        List(QUADS * 4) { Offset.Zero },
        List(QUADS * 4) { Offset.Zero },
        List(QUADS * 4) { Color.Transparent },
        List(QUADS * 6) { i -> i / 6 * 4 + QUAD_CORNERS[i % 6] },
    )
    private val positions = vertices.positions
    private val uvs = vertices.textureCoordinates
    private val colors = vertices.colors

    // White so each vertex keeps its own colour under Modulate, on Android and skiko alike.
    private val paint = Paint().apply {
        color = Color.White
        filterQuality = FilterQuality.Low
        shader = ImageShader(bakeAtlas(cells), TileMode.Clamp, TileMode.Clamp)
    }

    private var canvas: Canvas? = null
    private var count = 0
    private var dirtyQuads = 0

    /** Runs [block] to add this pass's sprites and draws them with [blendMode]. */
    fun draw(canvas: Canvas, blendMode: BlendMode, block: SpriteBatch.() -> Unit) {
        this.canvas = canvas
        paint.blendMode = blendMode
        count = 0
        block()
        if (count > 0) flush()
        this.canvas = null
    }

    /** A round sprite of [cell] centred on ([x], [y]). */
    fun disc(cell: Int, x: Float, y: Float, radius: Float, color: Color) {
        // A negative radius would draw a flipped quad, where drawCircle draws nothing.
        if (radius > 0f) quad(cell, x, y, radius, radius, 1f, 0f, color)
    }

    /** A sprite of half-size [halfW] by [halfH], its width along the unit vector ([cos], [sin]). */
    fun quad(cell: Int, x: Float, y: Float, halfW: Float, halfH: Float, cos: Float, sin: Float, color: Color) {
        if (count == QUADS) flush()
        val argb = color.toArgb()
        val wx = halfW * cos
        val wy = halfW * sin
        val hx = -halfH * sin
        val hy = halfH * cos
        put(0, x - wx - hx, y - wy - hy, argb)
        put(1, x + wx - hx, y + wy - hy, argb)
        put(2, x + wx + hx, y + wy + hy, argb)
        put(3, x - wx + hx, y - wy + hy, argb)
        val left = cell * CELL_SIZE + CELL_INSET
        val right = (cell + 1) * CELL_SIZE - CELL_INSET
        val i = count * 8
        uvs[i] = left; uvs[i + 1] = CELL_INSET
        uvs[i + 2] = right; uvs[i + 3] = CELL_INSET
        uvs[i + 4] = right; uvs[i + 5] = CELL_SIZE - CELL_INSET
        uvs[i + 6] = left; uvs[i + 7] = CELL_SIZE - CELL_INSET
        count++
    }

    private fun put(corner: Int, x: Float, y: Float, argb: Int) {
        val v = count * 4 + corner
        positions[v * 2] = x
        positions[v * 2 + 1] = y
        colors[v] = argb
    }

    private fun flush() {
        // The mesh always draws whole, so collapse whatever the last draw left past this one's end.
        if (dirtyQuads > count) positions.fill(0f, count * 8, dirtyQuads * 8)
        dirtyQuads = count
        canvas?.drawVertices(vertices, BlendMode.Modulate, paint)
        count = 0
    }

    private companion object {
        /** One mesh's sprites; 16-bit indices stay in the positive range Android and Skia agree on. */
        const val QUADS = 1024
        const val CELL_SIZE = 64f

        /** Half a texel, so linear filtering never reads a neighbouring cell. */
        const val CELL_INSET = 0.5f
        val QUAD_CORNERS = intArrayOf(0, 1, 2, 0, 2, 3)

        fun bakeAtlas(cells: List<SpriteCell>): ImageBitmap {
            val size = CELL_SIZE.toInt()
            val atlas = ImageBitmap(size * cells.size, size)
            val canvas = Canvas(atlas)
            val fill = Paint()
            cells.forEachIndexed { i, cell ->
                val left = i * CELL_SIZE
                fill.shader = RadialGradientShader(
                    center = Offset(left + CELL_SIZE / 2f, CELL_SIZE / 2f),
                    radius = CELL_SIZE / 2f,
                    // White at every stop: fading to Color.Transparent would dim the rim toward grey.
                    colors = cell.stops.map { Color.White.copy(alpha = it.second) },
                    colorStops = cell.stops.map { it.first },
                )
                canvas.drawRect(left, 0f, left + CELL_SIZE, CELL_SIZE, fill)
            }
            return atlas
        }
    }
}

/** [SpriteBatch.draw] into this scope's canvas. */
internal fun DrawScope.drawSprites(batch: SpriteBatch, blendMode: BlendMode, block: SpriteBatch.() -> Unit) {
    drawIntoCanvas { batch.draw(it, blendMode, block) }
}
