package org.balch.orpheus.features.mediapipe.shader

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlin.math.pow
import kotlinx.coroutines.isActive
import org.balch.orpheus.core.audio.SynthEngine

/**
 * Draws the camera image through an audio-reactive SKSL shader.
 *
 * When the shader is supported, the camera feed is dimmed, blurred, swirled,
 * and morphed based on audio output levels. When unsupported (old Android,
 * wasmJs error), falls back to simple alpha-based dimming.
 *
 * @param cameraImage Current camera frame as an ImageBitmap
 * @param engine SynthEngine providing audio-reactive flows, or null for preview
 * @param modifier Layout modifier
 */
@Composable
fun CameraEffectCanvas(
    cameraImage: ImageBitmap,
    engine: SynthEngine?,
    modifier: Modifier = Modifier,
) {
    val renderer = remember { CameraEffectRenderer() }
    DisposableEffect(Unit) { onDispose { renderer.dispose() } }

    if (!renderer.isSupported() || engine == null) {
        FallbackCameraCanvas(cameraImage, engine, modifier)
        return
    }

    // Animation time — advances every display frame
    var elapsed by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var startNanos = 0L
        while (isActive) {
            withFrameNanos { frameNanos ->
                if (startNanos == 0L) startNanos = frameNanos
                elapsed = (frameNanos - startNanos) / 1_000_000_000f
            }
        }
    }

    Canvas(modifier = modifier) {
        val master = engine.masterLevelFlow.value
        val peak = engine.peakFlow.value
        val lfo = engine.lfoOutputFlow.value

        val brush = renderer.getShaderBrush(
            width = size.width,
            height = size.height,
            cameraImage = cameraImage,
            masterLevel = master,
            peakLevel = peak,
            lfoMod = lfo,
            time = elapsed,
        )
        if (brush != null) {
            drawRect(brush = brush)
        }
    }
}

/**
 * Fallback: draws the camera image with alpha-based dimming only.
 * Used when the runtime shader isn't supported or engine is null.
 */
@Composable
private fun FallbackCameraCanvas(
    cameraImage: ImageBitmap,
    engine: SynthEngine?,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val master = engine?.masterLevelFlow?.value ?: 0.5f
        val brightness = 0.25f + 0.75f * master.toDouble().pow(0.6).toFloat()

        drawImage(
            image = cameraImage,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            alpha = brightness,
        )
    }
}
