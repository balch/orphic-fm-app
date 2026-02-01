package org.balch.orpheus.features.visualizations.viz.shader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import org.balch.orpheus.ui.viz.Blob
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/**
 * WASM/JS implementation using Skiko's RuntimeShaderBuilder.
 * Similar to JVM implementation since both use Skiko.
 */
actual class MetaballsRenderer {
    private var runtimeEffect: RuntimeEffect? = null
    private var shaderBuilder: RuntimeShaderBuilder? = null
    
    init {
        try {
            runtimeEffect = RuntimeEffect.makeForShader(MetaballsShaderSource.SKSL_SOURCE)
            shaderBuilder = runtimeEffect?.let { RuntimeShaderBuilder(it) }
        } catch (e: Exception) {
            println("[Orpheus] WARNING: Metaballs shader compilation failed: ${e.message}")
        }
    }
    
    actual fun isSupported(): Boolean = shaderBuilder != null
    
    actual fun dispose() {
        shaderBuilder = null
        runtimeEffect?.close()
        runtimeEffect = null
    }
    
    fun getShaderBrush(
        width: Float,
        height: Float,
        blobs: List<Blob>,
        config: MetaballsConfig,
        lfoModulation: Float,
        masterEnergy: Float,
        time: Float
    ): ShaderBrush? {
        val builder = shaderBuilder ?: return null
        
        builder.uniform("resolution", width, height)
        builder.uniform("time", time)
        builder.uniform("ballCount", minOf(blobs.size, config.maxBalls))
        builder.uniform("threshold", config.threshold)
        builder.uniform("glowIntensity", config.glowIntensity)
        builder.uniform("lfoMod", lfoModulation)
        builder.uniform("masterEnergy", masterEnergy)
        
        val ballsData = FloatArray(16 * 4)
        val colorsData = FloatArray(16 * 4)
        
        blobs.take(16).forEachIndexed { index, blob ->
            val offset = index * 4
            ballsData[offset] = blob.x
            ballsData[offset + 1] = blob.y
            ballsData[offset + 2] = blob.radius
            ballsData[offset + 3] = blob.energy
            
            colorsData[offset] = blob.color.red
            colorsData[offset + 1] = blob.color.green
            colorsData[offset + 2] = blob.color.blue
            colorsData[offset + 3] = blob.alpha
        }
        
        builder.uniform("balls", ballsData)
        builder.uniform("colors", colorsData)
        
        val shader = builder.makeShader()
        return ShaderBrush(shader)
    }
}

/**
 * WASM MetaballsCanvas implementation.
 */
@Composable
actual fun MetaballsCanvas(
    modifier: Modifier,
    blobs: List<Blob>,
    config: MetaballsConfig,
    lfoModulation: Float,
    masterEnergy: Float,
    time: Float
) {
    val renderer = remember { MetaballsRenderer() }
    
    DisposableEffect(Unit) {
        onDispose { renderer.dispose() }
    }
    
    if (renderer.isSupported()) {
        Canvas(modifier = modifier.fillMaxSize()) {
            val brush = renderer.getShaderBrush(
                width = size.width,
                height = size.height,
                blobs = blobs,
                config = config,
                lfoModulation = lfoModulation,
                masterEnergy = masterEnergy,
                time = time
            )
            
            if (brush != null) {
                drawRect(brush = brush)
            }
        }
    } else {
        MetaballsCanvasFallback(
            modifier = modifier,
            blobs = blobs,
            lfoModulation = lfoModulation,
            masterEnergy = masterEnergy
        )
    }
}

/**
 * Canvas-based fallback for WASM when shaders aren't available.
 */
@Composable
private fun MetaballsCanvasFallback(
    modifier: Modifier,
    blobs: List<Blob>,
    lfoModulation: Float,
    masterEnergy: Float
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        
        blobs.forEach { blob ->
            val screenX = blob.x * width
            val screenY = (1f - blob.y) * height
            val screenRadius = blob.radius * height
            
            if (screenRadius < 2f) return@forEach
            
            val effectiveAlpha = (blob.alpha * blob.energy.coerceIn(0.3f, 1f)).coerceIn(0f, 1f)
            val brightCore = blob.color.copy(alpha = effectiveAlpha * 0.9f)
            val coreColor = blob.color.copy(alpha = effectiveAlpha * 0.7f)
            val glowColor = blob.color.copy(alpha = effectiveAlpha * 0.35f)
            
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to brightCore,
                        0.3f to coreColor,
                        0.6f to glowColor,
                        1f to Color.Transparent
                    ),
                    center = Offset(screenX, screenY),
                    radius = screenRadius * 1.5f
                ),
                radius = screenRadius * 1.5f,
                center = Offset(screenX, screenY),
                blendMode = BlendMode.Plus
            )
        }
    }
}
