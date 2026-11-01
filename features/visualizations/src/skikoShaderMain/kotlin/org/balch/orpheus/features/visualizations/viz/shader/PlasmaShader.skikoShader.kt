package org.balch.orpheus.features.visualizations.viz.shader

import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeShader
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode

/** SkSL through skiko, shared by JVM, WASM and iOS like [MetaballsRenderer]. */
actual class PlasmaShader actual constructor(source: String) {
    private var effect: RuntimeEffect? = try {
        RuntimeEffect.makeForShader(source)
    } catch (e: Exception) {
        null // isSupported goes false and the caller draws its fallback
    }
    private var builder: RuntimeShaderBuilder? = effect?.let { RuntimeShaderBuilder(it) }
    private val images = HashMap<String, Bitmap>()

    actual val isSupported: Boolean get() = builder != null

    actual fun uniform(name: String, value: Float) { builder?.uniform(name, value) }
    actual fun uniform(name: String, x: Float, y: Float) { builder?.uniform(name, x, y) }
    actual fun uniform(name: String, values: FloatArray) { builder?.uniform(name, values) }

    actual fun alphaImage(name: String, pixels: ByteArray, width: Int, height: Int) {
        val b = builder ?: return
        val bitmap = images.getOrPut(name) { Bitmap() }
        // installPixels copies into fresh native memory that Skia frees once the last frame using it
        // is done; immutable, so making the shader doesn't copy it again.
        bitmap.installPixels(ImageInfo(width, height, ColorType.ALPHA_8, ColorAlphaType.PREMUL), pixels, width)
        bitmap.setImmutable()
        b.child(name, bitmap.makeShader(FilterTileMode.REPEAT, FilterTileMode.CLAMP, SamplingMode.LINEAR))
    }

    actual fun brush(): ShaderBrush? = builder?.let { ShaderBrush(it.makeShader().asComposeShader()) }

    actual fun dispose() {
        builder = null
        images.values.forEach { it.close() }
        images.clear()
        effect?.close()
        effect = null
    }
}
