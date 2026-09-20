package org.balch.orpheus.features.visualizations.viz.face

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeShader
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Shader

actual class FaceMorphRenderer {
    private var effect: RuntimeEffect? = null
    private var builder: RuntimeShaderBuilder? = null

    // Image shaders are rebuilt only when a keyframe changes, which is once per stage crossing.
    // The Image backing each shader must stay alive as long as its shader is in use, and be
    // closed on rebuild/dispose, see CameraEffectRenderer.jvm.kt for the house pattern.
    private val slots = arrayOfNulls<ImageBitmap>(3)
    private val images = arrayOfNulls<Image>(3)
    private val shaders = arrayOfNulls<Shader>(3)

    init {
        try {
            effect = RuntimeEffect.makeForShader(FaceMorphShaderSource.SKSL)
            builder = effect?.let { RuntimeShaderBuilder(it) }
        } catch (e: Exception) {
            // isSupported() goes false and the caller crossfades instead.
        }
    }

    actual fun isSupported(): Boolean = builder != null

    actual fun dispose() {
        shaders.forEach { it?.close() }
        images.forEach { it?.close() }
        shaders.fill(null); images.fill(null); slots.fill(null)
        builder = null
        effect?.close(); effect = null
    }

    private fun child(slot: Int, name: String, bitmap: ImageBitmap, b: RuntimeShaderBuilder) {
        if (slots[slot] !== bitmap) {
            shaders[slot]?.close()
            images[slot]?.close()
            val image = Image.makeFromBitmap(bitmap.asSkiaBitmap())
            images[slot] = image
            shaders[slot] = image.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP)
            slots[slot] = bitmap
        }
        b.child(name, shaders[slot]!!)
    }

    fun brush(width: Float, height: Float, inputs: FaceMorphInputs): ShaderBrush? {
        val b = builder ?: return null
        child(0, "faceA", inputs.faceA, b)
        child(1, "faceB", inputs.faceB, b)
        child(2, "bias", inputs.bias, b)
        b.uniform("resolution", width, height)
        b.uniform("imageSize", inputs.faceA.width.toFloat(), inputs.faceA.height.toFloat())
        b.uniform("biasSize", inputs.bias.width.toFloat(), inputs.bias.height.toFloat())
        b.uniform("t", inputs.t)
        b.uniform("flicker", inputs.flicker)
        b.uniform("glitch", inputs.glitch)
        b.uniform("crt", inputs.crt)
        b.uniform("mono", inputs.mono)
        b.uniform("time", inputs.time)
        return ShaderBrush(b.makeShader().asComposeShader())
    }
}

@Composable
actual fun FaceMorphCanvas(modifier: Modifier, inputs: FaceMorphInputs) {
    val renderer = remember { FaceMorphRenderer() }
    DisposableEffect(Unit) { onDispose { renderer.dispose() } }

    if (renderer.isSupported()) {
        Canvas(modifier.fillMaxSize()) {
            renderer.brush(size.width, size.height, inputs)?.let { drawRect(brush = it) }
        }
    } else {
        FaceMorphFallback(modifier, inputs)
    }
}
