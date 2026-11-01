package org.balch.orpheus.features.visualizations.viz.shader

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.ui.graphics.ShaderBrush
import java.nio.ByteBuffer

/** AGSL on Android 13+ (API 33); older versions report unsupported and draw the fallback. */
actual class PlasmaShader actual constructor(source: String) {
    private var shader: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                RuntimeShader(source)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

    // One mutable bitmap per child, refilled in place each frame; the BitmapShader made once
    // sees the new pixels because copyPixelsFromBuffer bumps the bitmap's generation.
    private class Child(val bitmap: Bitmap, val buffer: ByteBuffer)
    private val children = HashMap<String, Child>()

    actual val isSupported: Boolean get() = shader != null

    actual fun uniform(name: String, value: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) shader?.setFloatUniform(name, value)
    }

    actual fun uniform(name: String, x: Float, y: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) shader?.setFloatUniform(name, x, y)
    }

    actual fun uniform(name: String, values: FloatArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) shader?.setFloatUniform(name, values)
    }

    actual fun alphaImage(name: String, pixels: ByteArray, width: Int, height: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val s = shader ?: return
        var child = children[name]
        if (child == null || child.bitmap.width != width || child.bitmap.height != height) {
            child?.bitmap?.recycle()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
            child = Child(bitmap, ByteBuffer.allocate(width * height))
            children[name] = child
            val bitmapShader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
            bitmapShader.filterMode = BitmapShader.FILTER_MODE_LINEAR
            s.setInputShader(name, bitmapShader)
        }
        child.buffer.clear()
        child.buffer.put(pixels, 0, width * height)
        child.buffer.rewind()
        child.bitmap.copyPixelsFromBuffer(child.buffer)
    }

    // The RuntimeShader is one mutable object, so one brush serves every frame.
    private var brush: ShaderBrush? = null

    actual fun brush(): ShaderBrush? = brush ?: shader?.let { ShaderBrush(it) }?.also { brush = it }

    actual fun dispose() {
        shader = null
        brush = null
        children.values.forEach { it.bitmap.recycle() }
        children.clear()
    }
}
