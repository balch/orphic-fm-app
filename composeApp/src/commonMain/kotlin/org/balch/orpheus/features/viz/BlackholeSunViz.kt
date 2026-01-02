package org.balch.orpheus.features.viz

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.features.viz.shader.BlackholeSunCanvas
import org.balch.orpheus.features.viz.shader.PlasmaEmitterData
import org.balch.orpheus.features.viz.shader.PlasmaEmittersConfig
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.CenterPanelStyle
import org.balch.orpheus.ui.viz.Visualization
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.viz.VisualizationLiquidScope
import org.balch.orpheus.util.currentTimeMillis
import kotlin.math.PI

/**
 * UI State for the Blackhole Sun visualization.
 */
data class BlackholeSunUiState(
    val emitters: List<PlasmaEmitterData> = emptyList(),
    val lfoModulation: Float = 0f,
    val masterEnergy: Float = 0f,
    val orbitSpeed: Float = 0.5f,
    val trailLength: Float = 1f,
    val animationTime: Float = 0f
)

/**
 * Blackhole Sun - Plasma beams being pulled into a gravitational center.
 * 
 * Each of the 8 synth voices has a dedicated emitter that orbits around the 
 * edge of the screen (off-screen). When a voice plays, its emitter shoots 
 * colored plasma beams that curve toward the center like light being pulled
 * into a black hole.
 * 
 * Features:
 * - 8 emitters orbiting off-screen (one per voice)
 * - Beams emit tangentially and curve inward via gravity
 * - Volume-based brightness with maintained bolt shape
 * - Orbit direction reverses at 3 and 9 o'clock knob positions
 * 
 * Knob1 (SPIN): Controls orbit direction and speed (reverses at 0.25/0.75)
 * Knob2 (TRAILS): Controls beam length - max = continuous streams, min = short bursts
 */
@Inject
@ContributesIntoSet(AppScope::class)
class BlackholeSunViz(
    private val engine: SynthEngine,
    private val dispatcherProvider: DispatcherProvider,
) : Visualization {

    override val id = "blackhole_sun"
    override val name = "Blackhole Sun"
    override val color = OrpheusColors.neonMagenta
    override val knob1Label = "SPIN"
    override val knob2Label = "TRAILS"
    
    override val liquidEffects = Default

    private var _orbitSpeedKnob = 0.5f  // Orbit speed/direction
    private var _trailLengthKnob = 1f   // Trail length (default max for continuous)

    override fun setKnob1(value: Float) { 
        _orbitSpeedKnob = value.coerceIn(0f, 1f) 
    }
    
    override fun setKnob2(value: Float) { 
        _trailLengthKnob = value.coerceIn(0f, 1f) 
    }

    // Emitter colors - using theme colors, paired per voice (2 voices per color)
    private val voicePairColors = listOf(
        OrpheusColors.neonMagenta,    // Voices 0-1: Bass (magenta)
        OrpheusColors.electricBlue,   // Voices 2-3: Low-mid (blue)
        OrpheusColors.synthGreen,     // Voices 4-5: Mid (green)
        OrpheusColors.neonCyan        // Voices 6-7: High (cyan)
    )
    
    // Map each voice to its pair color
    private val emitterColors = listOf(
        voicePairColors[0], // Voice 0
        voicePairColors[0], // Voice 1
        voicePairColors[1], // Voice 2
        voicePairColors[1], // Voice 3
        voicePairColors[2], // Voice 4
        voicePairColors[2], // Voice 5
        voicePairColors[3], // Voice 6
        voicePairColors[3]  // Voice 7
    )
    
    // Each emitter orbits at a different speed for organic motion
    private val emitterSpeedMultipliers = listOf(
        1.0f,   // Voice 0
        1.4f,   // Voice 1 - faster
        0.7f,   // Voice 2 - slower
        1.2f,   // Voice 3
        0.85f,  // Voice 4
        1.5f,   // Voice 5 - fast
        1.1f,   // Voice 6
        0.75f   // Voice 7 - slow
    )
    
    // Emitter states - each orbits independently at different speeds
    private val emitterStates = MutableList(8) { i ->
        EmitterState(
            voiceIndex = i,
            angle = (i.toFloat() / 8f) * 2f * PI.toFloat(),
            orbitSpeedMult = emitterSpeedMultipliers[i],
            energy = 0f,
            color = emitterColors[i]
        )
    }
    
    private val shaderConfig: PlasmaEmittersConfig
        get() = PlasmaEmittersConfig(
            emitterCount = 8,
            glowIntensity = 0.7f + _trailLengthKnob * 0.3f,  // Increased brightness
            streamWidth = 0.010f + _trailLengthKnob * 0.006f, // Slightly smaller for more bolts
            orbitRadius = 0.65f  // Off-screen
        )

    private val _uiState = MutableStateFlow(BlackholeSunUiState())
    val uiState: StateFlow<BlackholeSunUiState> = _uiState.asStateFlow()

    private var vizJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)
    private var animationTime = 0f
    private var smoothedMasterEnergy = 0f
    private var _currentSignedOrbitSpeed = 0.5f

    override fun onActivate() {
        if (vizJob?.isActive == true) return
        animationTime = 0f
        smoothedMasterEnergy = 0f
        
        // Reset emitter states with initial positions
        emitterStates.forEachIndexed { i, state ->
            state.angle = (i.toFloat() / 8f) * 2f * PI.toFloat()
            state.energy = 0f
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
                
                // Smooth master energy
                smoothedMasterEnergy = smoothedMasterEnergy * 0.9f + masterLevel * 0.1f

                // Update each emitter
                updateEmitters(voiceLevels, lfoValue, deltaTime)

                _uiState.value = BlackholeSunUiState(
                    emitters = emitterStates.map { it.toPlasmaEmitterData(_trailLengthKnob) },
                    lfoModulation = lfoValue,
                    masterEnergy = smoothedMasterEnergy,
                    orbitSpeed = _currentSignedOrbitSpeed,
                    trailLength = _trailLengthKnob,
                    animationTime = animationTime
                )
                
                delay(25) // ~40fps for smooth animation
            }
        }
    }

    override fun onDeactivate() {
        vizJob?.cancel()
        vizJob = null
        animationTime = 0f
        smoothedMasterEnergy = 0f
        emitterStates.forEach { it.energy = 0f }
        _uiState.value = BlackholeSunUiState()
    }

    @Composable
    override fun Content(modifier: Modifier) {
        val state by uiState.collectAsState()
        
        BlackholeSunCanvas(
            modifier = modifier,
            emitters = state.emitters,
            config = shaderConfig,
            lfoModulation = state.lfoModulation,
            masterEnergy = state.masterEnergy,
            orbitSpeed = state.orbitSpeed,
            trailLength = state.trailLength,
            time = state.animationTime
        )
    }
    
    private fun updateEmitters(voiceLevels: FloatArray, lfoValue: Float, deltaTime: Float) {
        // Orbit direction changes using cosine wave
        // At 0.0 and 1.0: clockwise, At 0.5: counter-clockwise
        // At 0.25 and 0.75: direction reverses (speed = 0)
        val directionMultiplier = kotlin.math.cos(_orbitSpeedKnob * 2f * PI.toFloat())
        
        // Speed magnitude
        val speedMagnitude = 0.1f + kotlin.math.abs(directionMultiplier) * 0.7f
        _currentSignedOrbitSpeed = speedMagnitude * kotlin.math.sign(directionMultiplier)
        
        for (emitter in emitterStates) {
            val voiceLevel = voiceLevels.getOrElse(emitter.voiceIndex) { 0f }
            
            // Energy responds quickly to voice activity
            val targetEnergy = voiceLevel
            val attackSpeed = 0.4f
            val decaySpeed = 0.1f
            
            emitter.energy = if (targetEnergy > emitter.energy) {
                (emitter.energy * (1f - attackSpeed) + targetEnergy * attackSpeed)
            } else {
                (emitter.energy * (1f - decaySpeed) + targetEnergy * decaySpeed)
            }.coerceIn(0f, 1f)
            
            // Update orbit angle with direction from knob
            val lfoSpeedMod = 1f + lfoValue * 0.15f
            val individualSpeed = emitter.orbitSpeedMult * _currentSignedOrbitSpeed * lfoSpeedMod
            emitter.angle = (emitter.angle + individualSpeed * deltaTime) % (2f * PI.toFloat())
        }
    }

    /**
     * Internal mutable state for a single emitter.
     */
    private data class EmitterState(
        val voiceIndex: Int,
        var angle: Float,
        val orbitSpeedMult: Float,
        var energy: Float,
        val color: Color
    ) {
        fun toPlasmaEmitterData(trailLength: Float): PlasmaEmitterData = PlasmaEmitterData(
            angle = angle,
            energy = energy,
            trailLength = trailLength,
            color = color,
            voiceIndex = voiceIndex,
            orbitSpeed = orbitSpeedMult
        )
    }

    companion object {
        val Default = VisualizationLiquidEffects(
            frostSmall = 2f,
            frostMedium = 2f,
            frostLarge = 3f,
            tintAlpha = 0.08f,
            top = VisualizationLiquidScope(
                saturation = 1f,
                dispersion = .3f,
                curve = .1f,
                refraction = .3f,
            ),
            bottom = VisualizationLiquidScope(
                saturation = 1f,
                dispersion = .3f,
                curve = .1f,
                refraction = .3f,
            ),
            title = CenterPanelStyle(
                scope = VisualizationLiquidScope(
                    saturation = 3f,
                    dispersion = 1.2f,
                    curve = 0.25f,
                    refraction = 0.8f,
                    contrast = 0.9f,
                ),
                titleColor = OrpheusColors.neonOrange,
                borderColor = OrpheusColors.synthPink.copy(alpha = 0.6f),
                borderWidth = 3.dp,
                titleElevation = 2.dp,
            ),
        )
    }
}
