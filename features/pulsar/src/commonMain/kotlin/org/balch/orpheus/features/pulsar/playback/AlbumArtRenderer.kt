package org.balch.orpheus.features.pulsar.playback

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.TileMode
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Multiplatform procedural album art for vibes that don't carry a fixed
 * cover photo (the STEALTH album). Pure shapes — no text — so the OS can
 * draw the title separately above/below the artwork without duplication.
 *
 * Output is deterministic from [seed] (typically `title.hashCode().toLong()`):
 * the same vibe always yields the same image, so caching at the call site
 * is safe and there's no visual flicker if a producer is recreated.
 *
 * Uses only `androidx.compose.ui.graphics` primitives, so the same code
 * runs on Android, JVM, iOS, and WASM.
 */
object AlbumArtRenderer {

    fun render(seed: Long, size: Int = 1080): ImageBitmap {
        val bitmap = ImageBitmap(size, size)
        val canvas = Canvas(bitmap)
        val rng = Random(seed)
        val palette = PALETTES[rng.nextInt(PALETTES.size)]
        when (rng.nextInt(3)) {
            0 -> drawTieDye(canvas, rng, palette, size.toFloat())
            1 -> drawClouds(canvas, rng, palette, size.toFloat())
            else -> drawRipples(canvas, rng, palette, size.toFloat())
        }
        return bitmap
    }

    /**
     * Sweep gradient base + overlapping radial blobs in screen blend.
     * Reads as classic tie-dye spiral with soft color falloff.
     */
    private fun drawTieDye(canvas: Canvas, rng: Random, palette: List<Color>, size: Float) {
        val center = Offset(size / 2f, size / 2f)
        val rect = Rect(Offset.Zero, Size(size, size))

        // Sweep base (palette + first repeated to close the loop seamlessly).
        val sweepColors = (palette + palette.first()).map { it }
        val sweepPaint = Paint().apply {
            shader = Brush.sweepGradient(colors = sweepColors, center = center)
                .toShader(rect)
        }
        canvas.drawRect(rect, sweepPaint)

        // 5–7 overlapping radial color blobs, screen-blended for a glow.
        val blobCount = 5 + rng.nextInt(3)
        repeat(blobCount) {
            val cx = size * (0.15f + rng.nextFloat() * 0.7f)
            val cy = size * (0.15f + rng.nextFloat() * 0.7f)
            val r = size * (0.25f + rng.nextFloat() * 0.35f)
            val color = palette[rng.nextInt(palette.size)]
            val brush = Brush.radialGradient(
                colors = listOf(color, color.copy(alpha = 0f)),
                center = Offset(cx, cy),
                radius = r,
                tileMode = TileMode.Clamp,
            )
            val paint = Paint().apply {
                shader = brush.toShader(Rect(cx - r, cy - r, cx + r, cy + r))
                blendMode = BlendMode.Screen
            }
            canvas.drawCircle(Offset(cx, cy), r, paint)
        }
    }

    /**
     * Dark vertical sky gradient + many soft pastel blobs to suggest
     * depth — reads as a 3-D cloudscape.
     */
    private fun drawClouds(canvas: Canvas, rng: Random, palette: List<Color>, size: Float) {
        val rect = Rect(Offset.Zero, Size(size, size))

        // Two-stop vertical sky from coolest → warmest palette extremes.
        val skyPaint = Paint().apply {
            shader = Brush.linearGradient(
                colors = listOf(palette.first(), palette.last()),
                start = Offset(0f, 0f),
                end = Offset(0f, size),
            ).toShader(rect)
        }
        canvas.drawRect(rect, skyPaint)

        // 30–50 soft tinted blobs via radial gradients with alpha falloff.
        val cloudCount = 30 + rng.nextInt(20)
        repeat(cloudCount) {
            val cx = rng.nextFloat() * size
            val cy = rng.nextFloat() * size
            val r = size * (0.05f + rng.nextFloat() * 0.18f)
            val tint = blend(palette[rng.nextInt(palette.size)], Color.White, 0.45f + rng.nextFloat() * 0.35f)
            val alpha = 0.3f + rng.nextFloat() * 0.5f
            val brush = Brush.radialGradient(
                colors = listOf(tint.copy(alpha = alpha), tint.copy(alpha = 0f)),
                center = Offset(cx, cy),
                radius = r,
                tileMode = TileMode.Clamp,
            )
            val paint = Paint().apply {
                shader = brush.toShader(Rect(cx - r, cy - r, cx + r, cy + r))
                blendMode = BlendMode.Screen
            }
            canvas.drawCircle(Offset(cx, cy), r, paint)
        }
    }

    /**
     * Concentric expanding rings from an off-center origin + a bright
     * highlight + scattered starlight pricks. Psychedelic ripple feel.
     */
    private fun drawRipples(canvas: Canvas, rng: Random, palette: List<Color>, size: Float) {
        val rect = Rect(Offset.Zero, Size(size, size))
        canvas.drawRect(rect, Paint().apply { color = palette[0] })

        val cx = size * (0.3f + rng.nextFloat() * 0.4f)
        val cy = size * (0.3f + rng.nextFloat() * 0.4f)
        val origin = Offset(cx, cy)
        val maxR = size * 1.2f
        val ringCount = 18 + rng.nextInt(12)
        val ringStep = maxR / ringCount

        // Rings: outermost to innermost so inner rings paint over outer.
        for (i in ringCount downTo 1) {
            val r = i * ringStep
            val baseColor = palette[i % palette.size]
            val alpha = 0.45f + 0.5f * (1f - i.toFloat() / ringCount)
            val ringPaint = Paint().apply {
                color = baseColor.copy(alpha = alpha)
                style = PaintingStyle.Stroke
                strokeWidth = ringStep * (0.6f + rng.nextFloat() * 0.3f)
            }
            canvas.drawCircle(origin, r, ringPaint)
        }

        // Bright origin glow.
        val glowR = size * 0.25f
        val glowBrush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to Color.White,
                0.4f to palette[1],
                1f to Color.Transparent,
            ),
            center = origin,
            radius = glowR,
            tileMode = TileMode.Clamp,
        )
        val glowPaint = Paint().apply {
            shader = glowBrush.toShader(Rect(cx - glowR, cy - glowR, cx + glowR, cy + glowR))
            blendMode = BlendMode.Screen
        }
        canvas.drawCircle(origin, glowR, glowPaint)

        // Scattered starlight pricks.
        val starPaint = Paint().apply {
            blendMode = BlendMode.Screen
        }
        repeat(30) {
            val angle = rng.nextFloat() * 2f * kotlin.math.PI.toFloat()
            val dist = size * (0.1f + rng.nextFloat() * 0.4f)
            val sx = cx + cos(angle) * dist
            val sy = cy + sin(angle) * dist
            starPaint.color = Color.White.copy(alpha = 0.6f + rng.nextFloat() * 0.4f)
            canvas.drawCircle(Offset(sx, sy), 2f + rng.nextFloat() * 6f, starPaint)
        }
    }

    private fun blend(a: Color, b: Color, t: Float): Color = Color(
        red = a.red * (1 - t) + b.red * t,
        green = a.green * (1 - t) + b.green * t,
        blue = a.blue * (1 - t) + b.blue * t,
    )

    // 8 curated palettes — each 5 vivid colors that play well together.
    private val PALETTES: List<List<Color>> = listOf(
        listOf(0xFF2E1A47, 0xFF7B2C8E, 0xFFEF476F, 0xFFFFA552, 0xFFFFD166), // Sunset
        listOf(0xFF0B1D3A, 0xFF1B4D6B, 0xFF1E9A9A, 0xFF7FE5C4, 0xFFFF6B6B), // Ocean
        listOf(0xFF1A0033, 0xFF4B0082, 0xFFD400FF, 0xFFFF1493, 0xFF00E5FF), // Neon
        listOf(0xFF0F2417, 0xFF1F5732, 0xFF8FC93A, 0xFFEAB464, 0xFF6FB1FC), // Forest
        listOf(0xFF1A1A1A, 0xFF3D0000, 0xFFB22222, 0xFFFF7F00, 0xFFFFE4B5), // Volcanic
        listOf(0xFF14143C, 0xFF3F2B96, 0xFFA8C0FF, 0xFF7AFCAE, 0xFFE8F1F2), // Aurora
        listOf(0xFFE8B86D, 0xFFC97D60, 0xFF8E2D2D, 0xFF45224A, 0xFF1B1B3A), // Desert
        listOf(0xFFB6E388, 0xFFFFC857, 0xFFE56399, 0xFF7768AE, 0xFF255F85), // Citrus
    ).map { values -> values.map { Color(it) } }
}

/**
 * Build a Shader for the given Brush over the given paint area. Brush
 * carries gradient parameters but doesn't expose a Shader directly; this
 * is the standard CMP idiom.
 */
private fun Brush.toShader(rect: Rect): androidx.compose.ui.graphics.Shader {
    // Hack: route through a temporary Paint to extract the shader Brush
    // would set. Cleaner once Brush exposes createShader publicly.
    val paint = Paint()
    applyTo(rect.size, paint, 1f)
    return paint.shader ?: error("Brush did not produce a Shader")
}
