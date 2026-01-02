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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Android implementation of BlackholeSunRenderer using AGSL RuntimeShader (API 33+).
 */
actual class BlackholeSunRenderer {
    private var runtimeShader: RuntimeShader? = null
    private val emittersData = FloatArray(8 * 4)
    private val colorsData = FloatArray(8 * 4)
    
    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                runtimeShader = RuntimeShader(BlackholeSunShaderSource.SKSL_SOURCE)
            } catch (e: Exception) {
                println("[Orpheus] WARNING: Blackhole Sun shader compilation failed: ${e.message}")
            }
        }
    }
    
    actual fun isSupported(): Boolean = runtimeShader != null
    
    actual fun dispose() {
        runtimeShader = null
    }
    
    fun getShaderBrush(
        width: Float,
        height: Float,
        emitters: List<PlasmaEmitterData>,
        config: PlasmaEmittersConfig,
        lfoModulation: Float,
        masterEnergy: Float,
        orbitSpeed: Float,
        trailLength: Float,
        time: Float
    ): ShaderBrush? {
        val shader = runtimeShader ?: return null
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shader.setFloatUniform("resolution", width, height)
            shader.setFloatUniform("time", time)
            shader.setIntUniform("emitterCount", minOf(emitters.size, config.emitterCount))
            shader.setFloatUniform("glowIntensity", config.glowIntensity)
            shader.setFloatUniform("streamWidth", config.streamWidth)
            shader.setFloatUniform("orbitRadius", config.orbitRadius)
            shader.setFloatUniform("lfoMod", lfoModulation)
            shader.setFloatUniform("masterEnergy", masterEnergy)
            shader.setFloatUniform("orbitSpeed", orbitSpeed)
            shader.setFloatUniform("trailLength", trailLength)
            
            // Pack emitter data: angle, energy, trailLength, orbitSpeedMult
            emitters.take(8).forEachIndexed { index, emitter ->
                val offset = index * 4
                emittersData[offset] = emitter.angle
                emittersData[offset + 1] = emitter.energy
                emittersData[offset + 2] = emitter.trailLength
                emittersData[offset + 3] = emitter.orbitSpeed
                
                colorsData[offset] = emitter.color.red
                colorsData[offset + 1] = emitter.color.green
                colorsData[offset + 2] = emitter.color.blue
                colorsData[offset + 3] = emitter.color.alpha
            }
            
            // Clear remaining slots
            for (i in emitters.size until 8) {
                val offset = i * 4
                emittersData[offset] = 0f
                emittersData[offset + 1] = 0f
                emittersData[offset + 2] = 0f
                emittersData[offset + 3] = 0f
                colorsData[offset] = 0f
                colorsData[offset + 1] = 0f
                colorsData[offset + 2] = 0f
                colorsData[offset + 3] = 0f
            }
            
            shader.setFloatUniform("emitters", emittersData)
            shader.setFloatUniform("emitterColors", colorsData)
            
            return ShaderBrush(shader)
        }
        return null
    }
}

/**
 * Android BlackholeSunCanvas implementation.
 */
@Composable
actual fun BlackholeSunCanvas(
    modifier: Modifier,
    emitters: List<PlasmaEmitterData>,
    config: PlasmaEmittersConfig,
    lfoModulation: Float,
    masterEnergy: Float,
    orbitSpeed: Float,
    trailLength: Float,
    time: Float
) {
    val renderer = remember { BlackholeSunRenderer() }
    
    DisposableEffect(Unit) {
        onDispose { renderer.dispose() }
    }
    
    if (renderer.isSupported() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Canvas(modifier = modifier.fillMaxSize()) {
            val shaderBrush = renderer.getShaderBrush(
                width = size.width,
                height = size.height,
                emitters = emitters,
                config = config,
                lfoModulation = lfoModulation,
                masterEnergy = masterEnergy,
                orbitSpeed = orbitSpeed,
                trailLength = trailLength,
                time = time
            )
            
            if (shaderBrush != null) {
                drawRect(brush = shaderBrush)
            }
        }
    } else {
        BlackholeSunCanvasFallback(
            modifier = modifier,
            emitters = emitters,
            config = config,
            orbitSpeed = orbitSpeed,
            trailLength = trailLength,
            time = time
        )
    }
}

/**
 * Canvas-based fallback for Android <API 33.
 */
@Composable
private fun BlackholeSunCanvasFallback(
    modifier: Modifier,
    emitters: List<PlasmaEmitterData>,
    config: PlasmaEmittersConfig,
    orbitSpeed: Float,
    trailLength: Float,
    time: Float
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val cx = width / 2f
        val cy = height / 2f
        val orbitR = minOf(width, height) * config.orbitRadius
        
        drawRect(Color(0xFF020106))
        
        emitters.forEach { emitter ->
            val energy = emitter.energy
            if (energy < 0.01f) return@forEach
            
            val emitterX = cx + cos(emitter.angle) * orbitR
            val emitterY = cy + sin(emitter.angle) * orbitR
            
            // Tangential direction
            val tangentX = -sin(emitter.angle) * kotlin.math.sign(orbitSpeed)
            val tangentY = cos(emitter.angle) * kotlin.math.sign(orbitSpeed)
            
            // Direction toward center
            val dx = cx - emitterX
            val dy = cy - emitterY
            val len = sqrt(dx * dx + dy * dy)
            val centerDirX = dx / len
            val centerDirY = dy / len
            
            // Initial direction (mostly tangent, some inward)
            val initDirX = tangentX * 0.7f + centerDirX * 0.3f
            val initDirY = tangentY * 0.7f + centerDirY * 0.3f
            val initLen = sqrt(initDirX * initDirX + initDirY * initDirY)
            val normDirX = initDirX / initLen
            val normDirY = initDirY / initLen
            
            val numParticles = (5 + trailLength * 12).toInt()
            val streamLen = orbitR * 0.6f
            
            for (p in 0 until numParticles) {
                val t = p.toFloat() / numParticles
                val phase = ((time * 1.5f - t * 0.7f) % 1f + 1f) % 1f
                if (phase > trailLength + 0.2f) continue
                
                val travelTime = phase * 1.5f
                
                // Start position + tangential movement
                var particleX = emitterX + normDirX * travelTime * streamLen * 0.35f
                var particleY = emitterY + normDirY * travelTime * streamLen * 0.35f
                
                // Apply gravity curve
                val toCenterX = cx - particleX
                val toCenterY = cy - particleY
                val distToCenter = sqrt(toCenterX * toCenterX + toCenterY * toCenterY)
                val gravityEffect = travelTime * travelTime * 0.35f
                particleX += (toCenterX / distToCenter) * gravityEffect * streamLen
                particleY += (toCenterY / distToCenter) * gravityEffect * streamLen
                
                val particleSize = (6f + energy * 10f) * (1f - phase * 0.3f)
                val alpha = (0.25f + energy * 0.75f) * (1f - phase * 0.7f)
                
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to emitter.color.copy(alpha = alpha * 0.9f),
                            0.4f to emitter.color.copy(alpha = alpha * 0.5f),
                            1f to Color.Transparent
                        ),
                        center = Offset(particleX, particleY),
                        radius = particleSize * 2f
                    ),
                    radius = particleSize * 2f,
                    center = Offset(particleX, particleY),
                    blendMode = BlendMode.Plus
                )
            }
        }
    }
}
