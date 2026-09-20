package org.balch.orpheus.features.visualizations.viz.face

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.min
import kotlin.math.roundToInt

/** Zoom applied by both the shader and the crossfade fallback, so they frame identically. */
internal const val FACE_ZOOM = 1.1f

/** One frame of the dissolve: the two keyframes either side of the morph, and the blend t. */
class FaceMorphInputs(
    val faceA: ImageBitmap,
    val faceB: ImageBitmap,
    val bias: ImageBitmap,
    val t: Float,
    val flicker: Float,
    val glitch: Float,
    val crt: Float,
    val mono: Float,
    val time: Float,
)

/** Skia RuntimeEffect on JVM, iOS and WASM; AGSL RuntimeShader on Android 13+. */
expect class FaceMorphRenderer() {
    fun isSupported(): Boolean
    fun dispose()
}

/** [inputs] is read while drawing, so a new frame redraws the face without recomposing it. */
@Composable
expect fun FaceMorphCanvas(modifier: Modifier, inputs: () -> FaceMorphInputs)

/**
 * No shader: a plain crossfade, fit (not cropped) and zoomed to match the shader path. Ghosts
 * mid-blend, which the shader exists to avoid.
 */
@Composable
internal fun FaceMorphFallback(modifier: Modifier, inputs: () -> FaceMorphInputs) {
    // The shader's grey has no equivalent here, so desaturate the images instead; without this
    // the fallback would keep showing colour while the rest of the broadcast has gone mono.
    val grey = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    Canvas(modifier.fillMaxSize()) {
        val inputs = inputs()
        val filter = if (inputs.mono > 0.5f) grey else null
        // Fill first so the letterboxed area away from the zoomed image is dark, not transparent.
        drawRect(Color(0xFF0B0D0E))
        val scale = min(size.width / inputs.faceA.width, size.height / inputs.faceA.height) * FACE_ZOOM
        val dst = IntSize((inputs.faceA.width * scale).roundToInt(), (inputs.faceA.height * scale).roundToInt())
        val at = IntOffset(((size.width - dst.width) / 2f).roundToInt(), ((size.height - dst.height) / 2f).roundToInt())
        drawImage(inputs.faceA, dstOffset = at, dstSize = dst, colorFilter = filter)
        drawImage(inputs.faceB, dstOffset = at, dstSize = dst, alpha = inputs.t.coerceIn(0f, 1f), colorFilter = filter)
    }
}
