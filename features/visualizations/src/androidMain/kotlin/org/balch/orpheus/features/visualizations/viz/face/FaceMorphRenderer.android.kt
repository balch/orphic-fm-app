package org.balch.orpheus.features.visualizations.viz.face

import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader.TileMode
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap

/** AGSL on API 33+, crossfade below that. */
actual class FaceMorphRenderer {
    private var shader: RuntimeShader? = null
    private val slots = arrayOfNulls<ImageBitmap>(3)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                shader = RuntimeShader(FaceMorphShaderSource.SKSL)
            } catch (e: Exception) {
                // isSupported() goes false and the caller crossfades instead.
            }
        }
    }

    actual fun isSupported(): Boolean = shader != null

    actual fun dispose() {
        shader = null
        slots.fill(null)
    }

    fun brush(width: Float, height: Float, inputs: FaceMorphInputs): ShaderBrush? {
        val s = shader ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null

        fun child(slot: Int, name: String, bitmap: ImageBitmap) {
            if (slots[slot] === bitmap) return
            s.setInputShader(name, BitmapShader(bitmap.asAndroidBitmap(), TileMode.CLAMP, TileMode.CLAMP))
            slots[slot] = bitmap
        }
        child(0, "faceA", inputs.faceA)
        child(1, "faceB", inputs.faceB)
        child(2, "bias", inputs.bias)
        s.setFloatUniform("resolution", width, height)
        s.setFloatUniform("imageSize", inputs.faceA.width.toFloat(), inputs.faceA.height.toFloat())
        s.setFloatUniform("biasSize", inputs.bias.width.toFloat(), inputs.bias.height.toFloat())
        s.setFloatUniform("t", inputs.t)
        s.setFloatUniform("flicker", inputs.flicker)
        s.setFloatUniform("glitch", inputs.glitch)
        s.setFloatUniform("crt", inputs.crt)
        s.setFloatUniform("mono", inputs.mono)
        s.setFloatUniform("time", inputs.time)
        return ShaderBrush(s)
    }
}

@Composable
actual fun FaceMorphCanvas(modifier: Modifier, inputs: () -> FaceMorphInputs) {
    val renderer = remember { FaceMorphRenderer() }
    DisposableEffect(Unit) { onDispose { renderer.dispose() } }

    if (renderer.isSupported()) {
        Canvas(modifier.fillMaxSize()) {
            renderer.brush(size.width, size.height, inputs())?.let { drawRect(brush = it) }
        }
    } else {
        FaceMorphFallback(modifier, inputs)
    }
}
