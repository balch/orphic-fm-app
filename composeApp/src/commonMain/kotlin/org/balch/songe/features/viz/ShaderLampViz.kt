package org.balch.songe.features.viz

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.balch.songe.core.audio.SongeEngine
import org.balch.songe.core.coroutines.DispatcherProvider
import org.balch.songe.features.viz.shader.MetaballsCanvas
import org.balch.songe.features.viz.shader.MetaballsConfig
import org.balch.songe.ui.theme.SongeColors
import org.balch.songe.ui.viz.Visualization
import org.balch.songe.ui.viz.VisualizationLiquidEffects
import org.balch.songe.ui.viz.VisualizationLiquidScope
import org.balch.songe.util.currentTimeMillis
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * UI state for the shader lamp background.
 */
data class ShaderLampUiState(
    val blobs: List<Blob> = emptyList(),
    val lfoModulation: Float = 0f,
    val masterEnergy: Float = 0f,
    val animationTime: Float = 0f
)

/**
 * Extended blob data for wandering behavior.
 */
data class WanderingBlob(
    val id: Int,
    var x: Float,              // 0-1 normalized position
    var y: Float,
    var radius: Float,         // 0-1 normalized radius
    var velocityX: Float,      // Movement velocity
    var velocityY: Float,
    var targetX: Float,        // Wander target
    var targetY: Float,
    var color: Color,
    var voiceIndex: Int,
    var energy: Float,
    var alpha: Float = 1f,
    var age: Float = 0f,
    var wanderAngle: Float = 0f,
    var wanderPhase: Float = 0f
)


/**
 * Shader Lamp visualization - metaballs shader implementation.
 * Blobs spawn randomly and wander around, interacting with each other.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class ShaderLampViz(
    private val engine: SongeEngine,
    private val dispatcherProvider: DispatcherProvider,
) : Visualization {

    override val id = "shader_lamp"
    override val name = "Shader Lamp"
    override val color = SongeColors.neonCyan
    override val knob1Label = "SPEED"
    override val knob2Label = "SIZE"
    
    override val liquidEffects = Default

    private var _speedKnob = 0.5f
    private var _sizeKnob = 0.5f

    override fun setKnob1(value: Float) {
        _speedKnob = value.coerceIn(0f, 1f)
    }

    override fun setKnob2(value: Float) {
        _sizeKnob = value.coerceIn(0f, 1f)
    }

    // Colors for each voice pair
    private val voicePairColors = listOf(
        SongeColors.neonMagenta,
        SongeColors.electricBlue,
        SongeColors.synthGreen,
        SongeColors.neonCyan
    )

    private val blobs = mutableListOf<WanderingBlob>()
    private var nextBlobId = 0
    private var animationTime = 0f

    // More blobs for better effect
    private var _maxBlobs = 16
    val maxBlobs: Int get() = _maxBlobs
    
    // Shader config - moderate threshold
    private val shaderConfig: MetaballsConfig
        get() = MetaballsConfig(
            maxBalls = _maxBlobs,
            threshold = 1.2f,
            glowIntensity = 0.35f,
            blendSoftness = 0.5f
        )
    
    // Physics - more active movement
    private val baseWanderSpeed = 0.025f  // Faster base movement
    private val baseSpawnThreshold = 0.08f
    private val minRadius = 0.015f        // Starting size (very small)
    private val baseRadius = 0.05f        // Default visible size
    private val maxRadiusBase = 0.10f     // Max size with audio
    
    private val speedMultiplier: Float get() = 0.4f + (_speedKnob * 1.6f)
    private val sizeMultiplier: Float get() = 0.6f + (_sizeKnob * 1.0f)
    private val maxRadius: Float get() = maxRadiusBase * sizeMultiplier

    private val _uiState = MutableStateFlow(ShaderLampUiState())
    val uiState: StateFlow<ShaderLampUiState> = _uiState.asStateFlow()

    private var vizJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onActivate() {
        if (vizJob?.isActive == true) return
        animationTime = 0f
        blobs.clear()
        
        // Start with several small blobs scattered around
        for (i in 0 until 6) {
            spawnRandomBlob(voiceIndex = i % 8)
        }
        
        vizJob = scope.launch(dispatcherProvider.default) {
            var lastFrameTime = currentTimeMillis()
            
            while (isActive) {
                val currentTime = currentTimeMillis()
                val deltaTime = (currentTime - lastFrameTime) / 1000f
                lastFrameTime = currentTime
                animationTime += deltaTime

                val voiceLevels = engine.voiceLevelsFlow.value
                val lfoValue = engine.lfoOutputFlow.value
                val masterLevel = engine.masterLevelFlow.value

                updateBlobs(voiceLevels, masterLevel, lfoValue, deltaTime)

                _uiState.value = ShaderLampUiState(
                    blobs = blobs.map { it.toBlob() },
                    lfoModulation = lfoValue,
                    masterEnergy = masterLevel,
                    animationTime = animationTime
                )

                delay(33)
            }
        }
    }

    override fun onDeactivate() {
        vizJob?.cancel()
        vizJob = null
        blobs.clear()
        animationTime = 0f
        _uiState.value = ShaderLampUiState()
    }

    @Composable
    override fun Content(modifier: Modifier) {
        val state by uiState.collectAsState()
        
        MetaballsCanvas(
            modifier = modifier,
            blobs = state.blobs,
            config = shaderConfig,
            lfoModulation = state.lfoModulation,
            masterEnergy = state.masterEnergy,
            time = state.animationTime
        )
    }
    
    private fun WanderingBlob.toBlob(): Blob = Blob(
        id = id,
        x = x,
        y = y,
        radius = radius,
        velocityY = velocityY,
        color = color,
        voiceIndex = voiceIndex,
        energy = energy,
        alpha = alpha,
        age = age
    )

    private fun updateBlobs(voiceLevels: FloatArray, masterLevel: Float, lfoValue: Float, deltaTime: Float) {
        val wanderSpeed = baseWanderSpeed * speedMultiplier
        
        // Spawn new blobs based on audio - more aggressive spawning
        for (voiceIndex in 0 until 8) {
            val level = voiceLevels.getOrElse(voiceIndex) { 0f }
            if (level > baseSpawnThreshold && blobs.size < _maxBlobs) {
                val existingForVoice = blobs.count { it.voiceIndex == voiceIndex }
                if (existingForVoice < 3 && Random.nextFloat() < level * 0.15f) {
                    spawnRandomBlob(voiceIndex)
                }
            }
        }

        val blobsToRemove = mutableListOf<WanderingBlob>()

        for (blob in blobs) {
            val voiceLevel = voiceLevels.getOrElse(blob.voiceIndex) { 0f }
            
            // Energy drives visibility
            blob.energy = (voiceLevel * 0.7f) + (masterLevel * 0.3f)
            
            // Pick new wander target periodically
            blob.wanderPhase += deltaTime
            if (blob.wanderPhase > 2f + Random.nextFloat() * 2f) {
                blob.wanderPhase = 0f
                // Keep targets well within bounds (0.15 to 0.85)
                blob.targetX = 0.15f + Random.nextFloat() * 0.7f
                blob.targetY = 0.15f + Random.nextFloat() * 0.7f
            }
            
            // Wandering with Perlin-like noise
            blob.wanderAngle += (Random.nextFloat() - 0.5f) * 0.4f
            val noiseX = cos(blob.wanderAngle + animationTime * 0.8f) * 0.003f
            val noiseY = sin(blob.wanderAngle * 1.3f + animationTime * 0.6f) * 0.003f
            
            // Move toward target
            val dx = blob.targetX - blob.x
            val dy = blob.targetY - blob.y
            val dist = sqrt(dx * dx + dy * dy)
            
            if (dist > 0.02f) {
                val moveSpeed = wanderSpeed * (0.5f + blob.energy * 0.5f)
                blob.velocityX = (dx / dist) * moveSpeed + noiseX
                blob.velocityY = (dy / dist) * moveSpeed + noiseY
            } else {
                // Reached target, pick new one
                blob.targetX = 0.15f + Random.nextFloat() * 0.7f
                blob.targetY = 0.15f + Random.nextFloat() * 0.7f
            }
            
            // LFO adds circular motion
            val lfoStrength = lfoValue * 0.008f * speedMultiplier
            blob.velocityX += lfoStrength * cos(animationTime * 1.5f + blob.id.toFloat())
            blob.velocityY += lfoStrength * sin(animationTime * 1.5f + blob.id.toFloat())
            
            // Apply velocity
            blob.x += blob.velocityX
            blob.y += blob.velocityY
            
            // Hard bounds - keep well within screen (0.1 to 0.9)
            if (blob.x < 0.1f) { blob.x = 0.1f; blob.velocityX = kotlin.math.abs(blob.velocityX) * 0.5f; blob.targetX = 0.4f + Random.nextFloat() * 0.3f }
            if (blob.x > 0.9f) { blob.x = 0.9f; blob.velocityX = -kotlin.math.abs(blob.velocityX) * 0.5f; blob.targetX = 0.3f + Random.nextFloat() * 0.3f }
            if (blob.y < 0.1f) { blob.y = 0.1f; blob.velocityY = kotlin.math.abs(blob.velocityY) * 0.5f; blob.targetY = 0.4f + Random.nextFloat() * 0.3f }
            if (blob.y > 0.9f) { blob.y = 0.9f; blob.velocityY = -kotlin.math.abs(blob.velocityY) * 0.5f; blob.targetY = 0.3f + Random.nextFloat() * 0.3f }
            
            // Blob-blob soft repulsion
            for (other in blobs) {
                if (other.id == blob.id) continue
                val ox = other.x - blob.x
                val oy = other.y - blob.y
                val distance = sqrt(ox * ox + oy * oy)
                val minDist = (blob.radius + other.radius) * 1.2f
                
                if (distance < minDist && distance > 0.001f) {
                    val push = (minDist - distance) * 0.03f
                    blob.x -= (ox / distance) * push
                    blob.y -= (oy / distance) * push
                }
            }
            
            // Radius: grows from small spawn size to target size based on age and audio
            val targetRadius = if (blob.age < 0.5f) {
                // First 0.5 seconds: grow from min to base
                minRadius + (baseRadius - minRadius) * (blob.age / 0.5f)
            } else {
                // After: base size + audio influence
                baseRadius + (voiceLevel * (maxRadius - baseRadius))
            }
            blob.radius = blob.radius * 0.9f + targetRadius * 0.1f
            blob.radius = blob.radius.coerceIn(minRadius, maxRadius) * sizeMultiplier
            
            // Alpha based on audio - subtle at rest, visible with sound
            val audioLevel = voiceLevels.getOrElse(blob.voiceIndex) { 0f }
            val targetAlpha = 0.08f + (audioLevel * 0.6f) + (masterLevel * 0.25f)
            blob.alpha = (blob.alpha * 0.85f + targetAlpha * 0.15f).coerceIn(0.05f, 0.9f)
            
            blob.age += deltaTime
            
            // Remove very old low-energy blobs
            if (blob.age > 15f && blob.energy < 0.2f && Random.nextFloat() < 0.02f) {
                blobsToRemove.add(blob)
            }
        }

        blobs.removeAll(blobsToRemove.toSet())
        
        // Keep minimum blob count for visual presence
        val minBlobs = 4 + (masterLevel * 4).toInt()  // 4-8 minimum based on audio
        while (blobs.size < minBlobs && blobs.size < _maxBlobs) {
            spawnRandomBlob(voiceIndex = Random.nextInt(8))
        }
    }
    
    private fun spawnRandomBlob(voiceIndex: Int) {
        val pairIndex = voiceIndex / 2
        val color = voicePairColors[pairIndex]
        
        // Spawn well within bounds
        val x = 0.2f + Random.nextFloat() * 0.6f
        val y = 0.2f + Random.nextFloat() * 0.6f
        
        blobs.add(WanderingBlob(
            id = nextBlobId++,
            x = x,
            y = y,
            radius = minRadius,  // Start at minimum size (no flash!)
            velocityX = (Random.nextFloat() - 0.5f) * 0.01f,
            velocityY = (Random.nextFloat() - 0.5f) * 0.01f,
            targetX = 0.15f + Random.nextFloat() * 0.7f,
            targetY = 0.15f + Random.nextFloat() * 0.7f,
            color = color,
            voiceIndex = voiceIndex,
            energy = 0.1f,  // Start low
            alpha = 0.05f,  // Start nearly invisible
            wanderAngle = Random.nextFloat() * 6.28f,
            wanderPhase = Random.nextFloat() * 2f
        ))
    }

    companion object {
        val Default = VisualizationLiquidEffects(
            frostSmall = 5f,
            frostMedium = 7f,
            frostLarge = 9f,
            tintAlpha = 0.12f,
            top = VisualizationLiquidScope(
                saturation = 0.40f,
                contrast = 0.75f,
                dispersion = .8f,
            ),
            bottom = VisualizationLiquidScope(
                saturation = 0.50f,
                contrast = 0.75f,
                dispersion = .4f,
            ),
        )
    }
}
