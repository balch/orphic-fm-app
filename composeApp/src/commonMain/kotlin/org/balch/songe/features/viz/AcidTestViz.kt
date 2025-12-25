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
import org.balch.songe.ui.theme.SongeColors
import org.balch.songe.ui.viz.Visualization
import org.balch.songe.ui.viz.VisualizationLiquidEffects
import org.balch.songe.ui.viz.VisualizationLiquidScope
import org.balch.songe.ui.widgets.VizBackground
import org.balch.songe.util.currentTimeMillis
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * UI state for the Acid Test visualization - slow morphing plasma bubbles.
 */
data class AcidTestUiState(
    val blobs: List<Blob> = emptyList(),
    val lfoModulation: Float = 0f,
    val masterEnergy: Float = 0f
)

/**
 * Acid Test visualization - psychedelic plastic bubble morphing effect.
 * Uses the same Blob structure as LavaLamp for compatibility with VizBackground.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class AcidTestViz(
    private val engine: SongeEngine,
    private val dispatcherProvider: DispatcherProvider,
) : Visualization {

    override val id = "acidTest"
    override val name = "Acid Test"
    override val color = SongeColors.softPurple
    override val knob1Label = "Color"
    override val knob2Label = "Morph"

    override val liquidEffects = Default

    private var _colorKnob = 0.5f
    private var _morphKnob = 0.5f

    override fun setKnob1(value: Float) {
        _colorKnob = value.coerceIn(0f, 1f)
    }

    override fun setKnob2(value: Float) {
        _morphKnob = value.coerceIn(0f, 1f)
    }

    // Psychedelic color palette - plastic bubble colors
    private val plasmaColors = listOf(
        Color(0xFFFF3399), // Hot pink
        Color(0xFF00CCFF), // Cyan
        Color(0xFFFF6600), // Orange
        Color(0xFF33FF66), // Green
        Color(0xFFCC33FF), // Purple
        Color(0xFFFFCC00), // Gold
        Color(0xFF3366FF), // Blue
        Color(0xFFFF3333), // Red
    )

    // Uses LavaLamp's Blob class for VizBackground compatibility
    private val blobs = mutableListOf<Blob>()
    private var nextBlobId = 0
    private var time = 0f

    // Fewer, MUCH bigger blobs
    private val numBlobs = 6
    
    // Target positions for slow drifting
    private val targetX = FloatArray(numBlobs) { 0.5f }
    private val targetY = FloatArray(numBlobs) { 0.5f }
    private val phases = FloatArray(numBlobs) { 0f }

    private val _uiState = MutableStateFlow(AcidTestUiState())
    val uiState: StateFlow<AcidTestUiState> = _uiState.asStateFlow()

    private var vizJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)
    private var smoothedEnergy = 0f

    override fun onActivate() {
        if (vizJob?.isActive == true) return
        smoothedEnergy = 0f
        time = 0f
        blobs.clear()
        
        // Spawn fixed set of plasma blobs
        repeat(numBlobs) { i ->
            val angle = (i.toFloat() / numBlobs) * 2f * PI.toFloat()
            val dist = 0.15f + Random.nextFloat() * 0.2f
            
            targetX[i] = 0.3f + Random.nextFloat() * 0.4f
            targetY[i] = 0.3f + Random.nextFloat() * 0.4f
            phases[i] = Random.nextFloat() * 2f * PI.toFloat()
            
            blobs.add(Blob(
                id = nextBlobId++,
                x = 0.5f + cos(angle) * dist,
                y = 0.5f + sin(angle) * dist,
                radius = 0.25f + Random.nextFloat() * 0.15f, // HUGE radius
                velocityY = 0f,
                color = plasmaColors[i % plasmaColors.size],
                voiceIndex = i,
                energy = 0.4f, // Lower brightness
                alpha = 0.5f   // More translucent
            ))
        }

        vizJob = scope.launch(dispatcherProvider.default) {
            var lastFrameTime = currentTimeMillis()

            while (isActive) {
                val currentTime = currentTimeMillis()
                val deltaTime = (currentTime - lastFrameTime) / 1000f
                lastFrameTime = currentTime

                val voiceLevels = engine.voiceLevelsFlow.value
                val lfoValue = engine.lfoOutputFlow.value
                val masterLevel = engine.masterLevelFlow.value

                smoothedEnergy = smoothedEnergy * 0.95f + masterLevel * 0.05f
                time += deltaTime

                updateBlobs(voiceLevels, masterLevel, lfoValue, deltaTime)

                _uiState.value = AcidTestUiState(
                    blobs = blobs.toList(),
                    lfoModulation = lfoValue,
                    masterEnergy = smoothedEnergy
                )

                delay(50) // ~20fps for smoother, slower feel
            }
        }
    }

    override fun onDeactivate() {
        vizJob?.cancel()
        vizJob = null
        blobs.clear()
        smoothedEnergy = 0f
        time = 0f
        _uiState.value = AcidTestUiState()
    }

    private fun updateBlobs(voiceLevels: FloatArray, masterLevel: Float, lfoValue: Float, deltaTime: Float) {
        val morphSpeed = 0.3f + _morphKnob * 0.7f
        
        for ((index, blob) in blobs.withIndex()) {
            val voiceLevel = voiceLevels.getOrElse(index % 8) { 0f }
            
            // Faster drift toward target for more visible movement
            val driftSpeed = 0.012f
            blob.x += (targetX[index] - blob.x) * driftSpeed
            blob.y += (targetY[index] - blob.y) * driftSpeed
            
            // Add wobble motion
            val wobble = 0.002f * sin(phases[index] * 2f)
            blob.x += wobble * cos(phases[index])
            blob.y += wobble * sin(phases[index])
            
            // When close to target, pick new target
            val distToTarget = sqrt(
                (targetX[index] - blob.x) * (targetX[index] - blob.x) + 
                (targetY[index] - blob.y) * (targetY[index] - blob.y)
            )
            if (distToTarget < 0.05f) {
                targetX[index] = 0.1f + Random.nextFloat() * 0.8f
                targetY[index] = 0.1f + Random.nextFloat() * 0.8f
            }
            
            // Plasma morphing - phase advances
            phases[index] += deltaTime * morphSpeed
            
            // Radius oscillates for breathing/morphing effect
            val baseRadius = 0.22f + _morphKnob * 0.12f
            val breathe = sin(phases[index]) * 0.06f
            val energyPulse = smoothedEnergy * 0.08f
            blob.radius = baseRadius + breathe + energyPulse
            
            // Lower brightness - more translucent
            blob.energy = 0.35f + voiceLevel * 0.15f + masterLevel * 0.1f
            blob.alpha = 0.5f + voiceLevel * 0.15f // Subtler
            
            // Color shifts based on colorKnob and LFO
            val colorShift = ((_colorKnob * 8f + lfoValue * 2f + phases[index] * 0.08f) % 8f).toInt()
            blob.color = plasmaColors[(blob.id + colorShift) % plasmaColors.size]
        }
    }

    @Composable
    override fun Content(modifier: Modifier) {
        val state by uiState.collectAsState()
        
        // Use the same VizBackground as LavaLamp for consistent rendering
        VizBackground(
            modifier = modifier,
            blobs = state.blobs,
            lfoModulation = state.lfoModulation,
            masterEnergy = state.masterEnergy
        )
    }

    companion object {
        val Default = VisualizationLiquidEffects(
            frostSmall = 6f,
            frostMedium = 10f,
            frostLarge = 14f,
            tintAlpha = 0.03f,
            top = VisualizationLiquidScope(
                saturation = 1.2f,
                contrast = 0.7f,
                refraction = 2.5f,  // High refraction
                curve = .25f,
                dispersion = 2.0f,  // High dispersion
            ),
            bottom = VisualizationLiquidScope(
                saturation = 1.5f,
                contrast = 0.75f,
                refraction = 3.5f,  // Very high refraction
                curve = .2f,
                dispersion = 3.0f,  // Very high dispersion
            ),
        )
    }
}
