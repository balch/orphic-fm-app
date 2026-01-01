package org.balch.orpheus.features.viz.shader

import android.graphics.RuntimeShader
import android.os.Build
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
import org.balch.orpheus.features.viz.Blob

/**
 * Android implementation using AGSL RuntimeShader (API 33+).
 * Falls back to Canvas-based rendering on older versions.
 */
actual class MetaballsRenderer {
    private var runtimeShader: RuntimeShader? = null
    private val ballsData = FloatArray(16 * 4)
    private val colorsData = FloatArray(16 * 4)
    
    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                // AGSL is nearly identical to SKSL
                runtimeShader = RuntimeShader(MetaballsShaderSource.SKSL_SOURCE)
            } catch (e: Exception) {
                println("[Orpheus] WARNING: AGSL shader compilation failed: ${e.message}")
            }
        }
    }
    
    actual fun isSupported(): Boolean = runtimeShader != null
    
    actual fun dispose() {
        runtimeShader = null
    }
    
    /**
     * Get a ShaderBrush with updated uniforms for rendering.
     * Updates all shader uniforms and returns a brush that can be used to fill shapes.
     */
    fun getShaderBrush(
        width: Float,
        height: Float,
        blobs: List<Blob>,
        config: MetaballsConfig,
        lfoModulation: Float,
        masterEnergy: Float,
        time: Float
    ): ShaderBrush? {
        val shader = runtimeShader ?: return null
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shader.setFloatUniform("resolution", width, height)
            shader.setFloatUniform("time", time)
            shader.setIntUniform("ballCount", minOf(blobs.size, config.maxBalls))
            shader.setFloatUniform("threshold", config.threshold)
            shader.setFloatUniform("glowIntensity", config.glowIntensity)
            shader.setFloatUniform("lfoMod", lfoModulation)
            shader.setFloatUniform("masterEnergy", masterEnergy)
            
            // Pack balls and colors data (reusing existing arrays)
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
            
            // Clear remaining slots if fewer than 16 blobs
            for (i in blobs.size until 16) {
                val offset = i * 4
                ballsData[offset] = 0f
                ballsData[offset + 1] = 0f
                ballsData[offset + 2] = 0f
                ballsData[offset + 3] = 0f
                colorsData[offset] = 0f
                colorsData[offset + 1] = 0f
                colorsData[offset + 2] = 0f
                colorsData[offset + 3] = 0f
            }
            
            shader.setFloatUniform("balls", ballsData)
            shader.setFloatUniform("colors", colorsData)
            
            return ShaderBrush(shader)
        }
        return null
    }
}

/**
 * Android MetaballsCanvas implementation.
 * Uses ShaderBrush to fill a rect with the shader output (generative shader).
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
    
    if (renderer.isSupported() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // Shader-based rendering using ShaderBrush
        // This draws the shader output directly as content (generative shader)
        Canvas(modifier = modifier.fillMaxSize()) {
            val shaderBrush = renderer.getShaderBrush(
                width = size.width,
                height = size.height,
                blobs = blobs,
                config = config,
                lfoModulation = lfoModulation,
                masterEnergy = masterEnergy,
                time = time
            )
            
            if (shaderBrush != null) {
                drawRect(brush = shaderBrush)
            }
        }
    } else {
        // Fallback to Canvas-based rendering
        MetaballsCanvasFallback(
            modifier = modifier,
            blobs = blobs,
            lfoModulation = lfoModulation,
            masterEnergy = masterEnergy
        )
    }
}

/**
 * Canvas-based fallback for Android < API 33.
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
