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

data class ShaderLampUiState(
    val blobs: List<Blob> = emptyList(),
    val lfoModulation: Float = 0f,
    val masterEnergy: Float = 0f,
    val animationTime: Float = 0f
)

/**
 * A blob that always exists but changes visibility based on audio.
 */
data class LavaBlob(
    val id: Int,
    var x: Float,
    var y: Float,
    var radius: Float,
    var velocityX: Float,
    var velocityY: Float,
    var color: Color,
    var voiceIndex: Int,
    var alpha: Float = 0.001f,
    var targetRadius: Float = 0.04f,
    var targetX: Float = 0.5f,
    var targetY: Float = 0.5f,
    var targetChangeTime: Long = 0
)

/**
 * Shader Lamp - High-performance lava lamp style visualization.
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

    override fun setKnob1(value: Float) { _speedKnob = value.coerceIn(0f, 1f) }
    override fun setKnob2(value: Float) { _sizeKnob = value.coerceIn(0f, 1f) }

    private val voicePairColors = listOf(
        SongeColors.neonMagenta,
        SongeColors.electricBlue,
        SongeColors.synthGreen,
        SongeColors.neonCyan
    )

    private val blobs = mutableListOf<LavaBlob>()
    private var animationTime = 0f

    private val blobCount = 8
    private val baseRadius = 0.15f  // Slightly larger base
    private val maxRadius = 0.30f   // Larger max
    private val baseSpeed = 0.0004f // Slower force-based movement
    
    private val speedMultiplier: Float get() = 0.4f + (_speedKnob * 1.6f)
    private val sizeMultiplier: Float get() = 0.6f + (_sizeKnob * 1.4f)
    
    private val shaderConfig: MetaballsConfig
        get() = MetaballsConfig(
            maxBalls = blobCount,
            threshold = 1.0f,
            glowIntensity = 0.4f,
            blendSoftness = 0.5f
        )

    private val _uiState = MutableStateFlow(ShaderLampUiState())
    val uiState: StateFlow<ShaderLampUiState> = _uiState.asStateFlow()

    private var vizJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onActivate() {
        if (vizJob?.isActive == true) return
        animationTime = 0f
        
        blobs.clear()
        for (i in 0 until blobCount) {
            blobs.add(LavaBlob(
                id = i,
                x = 0.2f + Random.nextFloat() * 0.6f,
                y = 0.2f + Random.nextFloat() * 0.6f,
                radius = baseRadius,
                velocityX = (Random.nextFloat() - 0.5f) * 0.002f,
                velocityY = (Random.nextFloat() - 0.5f) * 0.002f,
                color = voicePairColors[i / 2],
                voiceIndex = i,
                alpha = 0.001f,
                targetRadius = baseRadius,
                targetX = Random.nextFloat(),
                targetY = Random.nextFloat(),
                targetChangeTime = currentTimeMillis() + Random.nextLong(1000, 5000)
            ))
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
                delay(30)
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
    
    private fun LavaBlob.toBlob(): Blob = Blob(
        id = id,
        x = x,
        y = y,
        radius = radius,
        velocityY = velocityY,
        color = color,
        voiceIndex = voiceIndex,
        // Boost energy (color weight/influence) when active to retain color vibrancy
        energy = if (alpha > 0.005f) 0.6f + alpha * 0.4f else alpha * 120f,
        alpha = alpha,
        age = 0f
    )

    private fun updateBlobs(voiceLevels: FloatArray, masterLevel: Float, lfoValue: Float, deltaTime: Float) {
        val currentTime = currentTimeMillis()
        val globalSpeed = baseSpeed * speedMultiplier
        
        for (blob in blobs) {
            val voiceLevel = voiceLevels.getOrElse(blob.voiceIndex) { 0f }
            val totalEnergy = voiceLevel + masterLevel * 0.4f
            
            // ALPHA: Steeper quadratic curve - stays silent then pops in vibrantly
            val audioPower = (voiceLevel * 2.5f + masterLevel * 0.5f).coerceIn(0f, 1f)
            val targetAlpha = audioPower * audioPower
            blob.alpha = (blob.alpha * 0.85f + targetAlpha * 0.15f).coerceIn(0.0001f, 1.0f)
            
            // SIZE: Grows with energy
            val targetRad = (baseRadius + (totalEnergy * (maxRadius - baseRadius))) * sizeMultiplier
            blob.radius = (blob.radius * 0.93f + targetRad * 0.07f)
            
            // TARGET WANDERING
            if (currentTime > blob.targetChangeTime) {
                blob.targetX = 0.1f + Random.nextFloat() * 0.8f
                blob.targetY = 0.1f + Random.nextFloat() * 0.8f
                blob.targetChangeTime = currentTime + 4000L + Random.nextLong(6000)
            }
            
            val dx = blob.targetX - blob.x
            val dy = blob.targetY - blob.y
            val dist = sqrt(dx * dx + dy * dy)
            
            if (dist > 0.02f) {
                val wanderForce = globalSpeed * (1.0f + totalEnergy * 2.0f)
                blob.velocityX += (dx / dist) * wanderForce
                blob.velocityY += (dy / dist) * wanderForce
            }
            
            // LFO Swirl
            val lfoMag = lfoValue * 0.0008f * speedMultiplier
            blob.velocityX += lfoMag * cos(animationTime * 0.7f + blob.id)
            blob.velocityY += lfoMag * sin(animationTime * 0.7f + blob.id)
            
            // LAVA LAMP ATTRACTION / REPULSION
            for (other in blobs) {
                if (other.id == blob.id) continue
                val ox = other.x - blob.x
                val oy = other.y - blob.y
                val odist = sqrt(ox * ox + oy * oy)
                if (odist < 0.001f) continue
                
                val combinedR = (blob.radius + other.radius) * 0.5f
                if (odist < combinedR * 1.2f) {
                    // Repel when too close
                    val repel = 0.0002f * (combinedR * 1.2f - odist)
                    blob.velocityX -= (ox / odist) * repel
                    blob.velocityY -= (oy / odist) * repel
                } else if (odist < 0.4f) {
                    // Gentle attraction when in range
                    val attract = 0.00005f * (totalEnergy + 0.1f)
                    blob.velocityX += (ox / odist) * attract
                    blob.velocityY += (oy / odist) * attract
                }
            }
            
            // MOMENTUM & DAMPING
            // Higher friction at rest, less friction when energetic
            val damping = 0.96f + (totalEnergy * 0.02f).coerceAtMost(0.035f)
            blob.velocityX *= damping
            blob.velocityY *= damping
            
            // APPLY PHYSICS
            blob.x += blob.velocityX
            blob.y += blob.velocityY
            
            // SOFT BOUNDARIES
            val margin = 0.1f
            if (blob.x < margin) { blob.velocityX += 0.001f; blob.targetX = 0.5f }
            if (blob.x > 1.0f - margin) { blob.velocityX -= 0.001f; blob.targetX = 0.5f }
            if (blob.y < margin) { blob.velocityY += 0.001f; blob.targetY = 0.5f }
            if (blob.y > 1.0f - margin) { blob.velocityY -= 0.001f; blob.targetY = 0.5f }
            
            blob.x = blob.x.coerceIn(0.02f, 0.98f)
            blob.y = blob.y.coerceIn(0.02f, 0.98f)
        }
    }

    companion object {
        val Default = VisualizationLiquidEffects(
            frostSmall = 5f,
            frostMedium = 7f,
            frostLarge = 9f,
            tintAlpha = 0.12f,
            top = VisualizationLiquidScope(
                saturation = .75f,
                dispersion = .8f,
                curve = .15f,
                refraction = 0.4f,
            ),
            bottom = VisualizationLiquidScope(
                saturation = .75f,
                dispersion = .4f,
                curve = .15f,
                refraction = 0.2f,
            ),
        )
    }
}
