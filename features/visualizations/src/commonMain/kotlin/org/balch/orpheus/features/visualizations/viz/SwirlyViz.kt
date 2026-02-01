package org.balch.orpheus.features.visualizations.viz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.ui.infrastructure.CenterPanelStyle
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidScope
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.DynamicVisualization
import org.balch.orpheus.ui.viz.Visualization
import org.balch.orpheus.util.currentTimeMillis
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * UI State for Swirly visualization.
 */
data class SwirlyUiState(
    val time: Float = 0f,
    val masterEnergy: Float = 0f,
    val lfoModulation: Float = 0f,
    val voiceLevels: FloatArray = FloatArray(8)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as SwirlyUiState
        return time == other.time && masterEnergy == other.masterEnergy &&
               lfoModulation == other.lfoModulation && voiceLevels.contentEquals(other.voiceLevels)
    }
    override fun hashCode(): Int {
        var result = time.hashCode()
        result = 31 * result + masterEnergy.hashCode()
        result = 31 * result + lfoModulation.hashCode()
        result = 31 * result + voiceLevels.contentHashCode()
        return result
    }
}


/**
 * Swirly vortex visualization - funnel with spiral lines.
 * 
 * Creates a dark center vortex with spiral lines leading down
 * into the abyss. The funnel effect draws the eye inward.
 *
 * Knob1 (SPIN): Controls rotation speed
 * Knob2 (DEPTH): Controls spiral tightness
 */
@Inject
@ContributesIntoSet(AppScope::class, binding = binding<Visualization>())
class SwirlyViz(
    private val engine: SynthEngine,
    private val dispatcherProvider: DispatcherProvider,
) : DynamicVisualization {

    override val id = "swirly"
    override val name = "Swirly"
    override val color = OrpheusColors.electricBlue
    override val knob1Label = "SPIN"
    override val knob2Label = "DEPTH"
    
    // Higher saturation to let swirl colors shine through
    override val liquidEffects = Default

    private var _spinKnob = 0.5f
    private var _depthKnob = 0.5f

    override fun setKnob1(value: Float) {
        _spinKnob = value.coerceIn(0f, 1f)
    }

    override fun setKnob2(value: Float) {
        _depthKnob = value.coerceIn(0f, 1f)
    }

    // Pre-computed spiral colors - muted purples/blues
    private val spiralColors = listOf(
        OrpheusColors.swirlyPurple,  // Purple
        OrpheusColors.swirlyBluePurple,  // Blue-purple
        OrpheusColors.swirlyTealBlue,  // Teal-blue
        OrpheusColors.swirlyMutedPurple,  // Muted purple
        OrpheusColors.swirlyTeal,  // Teal
        OrpheusColors.swirlyPinkPurple   // Pink-purple
    )

    private val _uiState = MutableStateFlow(SwirlyUiState())
    val uiState: StateFlow<SwirlyUiState> = _uiState.asStateFlow()

    private var vizJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)
    private var animationTime = 0f
    private var smoothedEnergy = 0f

    private val _liquidEffects = MutableStateFlow(Default)
    override val liquidEffectsFlow: Flow<VisualizationLiquidEffects> = _liquidEffects.asStateFlow()

    override fun onActivate() {
        if (vizJob?.isActive == true) return
        animationTime = 0f
        smoothedEnergy = 0f

        vizJob = scope.launch(dispatcherProvider.default) {
            var lastFrameTime = currentTimeMillis()

            while (isActive) {
                val currentTime = currentTimeMillis()
                val deltaTime = (currentTime - lastFrameTime) / 1000f
                lastFrameTime = currentTime

                val voiceLevels = engine.voiceLevelsFlow.value
                val lfoValue = engine.lfoOutputFlow.value
                val masterLevel = engine.masterLevelFlow.value

                smoothedEnergy = smoothedEnergy * 0.9f + masterLevel * 0.1f

                // NEGATIVE for counter-clockwise spin direction
                val baseSpeed = -(0.1f + _spinKnob * 0.25f)
                val audioBoost = -smoothedEnergy * 0.2f
                animationTime += deltaTime * (baseSpeed + audioBoost)

                _uiState.value = SwirlyUiState(
                    time = animationTime,
                    masterEnergy = smoothedEnergy,
                    lfoModulation = lfoValue,
                    voiceLevels = voiceLevels
                )

                // Dynamic Title Effects
                val lfoShift = (lfoValue + 1f) / 2f
                val titleColor = if (lfoShift > 0.5f) {
                    lerpColor(OrpheusColors.swirlyPurple, OrpheusColors.swirlyPinkBright, (lfoShift - 0.5f) * 2f)
                } else {
                    lerpColor(OrpheusColors.swirlyPurple, OrpheusColors.swirlyLightBlue, (0.5f - lfoShift) * 2f)
                }

                val currentEffects = Default.copy(
                    title = Default.title.copy(
                        titleColor = titleColor,
                        titleElevation = (4.dp + (smoothedEnergy * 12).dp),
                        borderWidth = (1.dp + (smoothedEnergy * 2).dp),
                        borderColor = titleColor.copy(alpha = 0.3f + smoothedEnergy * 0.5f)
                    )
                )
                _liquidEffects.value = currentEffects

                delay(40) // ~25fps
            }
        }
    }

    override fun onDeactivate() {
        vizJob?.cancel()
        vizJob = null
        animationTime = 0f
        smoothedEnergy = 0f
        _uiState.value = SwirlyUiState()
    }

    @Composable
    override fun Content(modifier: Modifier) {
        val state by uiState.collectAsState()
        val path = remember { Path() }

        Canvas(modifier = modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val maxRadius = minOf(w, h) * 0.7f

            val time = state.time
            val energy = state.masterEnergy
            val lfo = state.lfoModulation

            // Background gradient - lighter at edges, DARK at center
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Black,          // Pure black center
                        OrpheusColors.fireworksBackground,          // Very dark
                        OrpheusColors.blackHoleDeep,          // Dark purple tint
                        OrpheusColors.darkVoid           // Edge color
                    ),
                    center = Offset(cx, cy),
                    radius = maxRadius * 1.2f
                ),
                radius = maxRadius * 1.5f,
                center = Offset(cx, cy)
            )

            // Spiral tightness from depth knob
            val spiralTightness = 2f + _depthKnob * 4f  // 2-6 rotations

            // Number of spiral arms
            val armCount = 6

            // Draw spiral lines leading into the center
            for (arm in 0 until armCount) {
                val armAngleOffset = (arm.toFloat() / armCount) * 2f * PI.toFloat()
                
                // Voice-based brightness for this arm
                val voiceIdx = arm % 4
                val voiceEnergy = (state.voiceLevels.getOrElse(voiceIdx * 2) { 0f } +
                                   state.voiceLevels.getOrElse(voiceIdx * 2 + 1) { 0f }) / 2f

                // Color for this arm
                val armColor = spiralColors[arm % spiralColors.size]
                
                // Draw spiral path from outside to center (reusing path object)
                path.reset()
                val segments = 60
                
                for (i in 0..segments) {
                    val t = i.toFloat() / segments  // 0 at outer, 1 at center
                    
                    // Radius decreases as we go toward center
                    val radius = maxRadius * (1f - t * 0.95f)
                    
                    // Angle increases as we spiral in
                    val spiralAngle = armAngleOffset + time + t * spiralTightness * 2f * PI.toFloat()
                    
                    // LFO adds wobble
                    val wobble = sin(t * 8f + time * 2f) * lfo * 0.03f * radius
                    
                    val x = cx + (radius + wobble) * cos(spiralAngle)
                    val y = cy + (radius + wobble) * sin(spiralAngle)
                    
                    if (i == 0) {
                        path.moveTo(x, y)
                    } else {
                        path.lineTo(x, y)
                    }
                }
                
                // Alpha - LESS visible when no sound, more with audio
                val baseAlpha = 0.1f + voiceEnergy * 0.5f + energy * 0.4f
                
                // Stroke width - THICKER overall, varies with voice energy
                val strokeWidth = 8f + energy * 5f + voiceEnergy * 7f  // 8-20 range
                
                // Color shift based on LFO - warmer or cooler
                val lfoShift = (lfo + 1f) / 2f  // 0-1
                val shiftedColor = if (lfoShift > 0.5f) {
                    // Warmer - shift toward magenta
                    lerpColor(armColor, Color(0xFFAA5599), (lfoShift - 0.5f) * 0.4f)
                } else {
                    // Cooler - shift toward cyan
                    lerpColor(armColor, Color(0xFF5599AA), (0.5f - lfoShift) * 0.4f)
                }
                
                drawPath(
                    path = path,
                    color = shiftedColor.copy(alpha = baseAlpha.coerceIn(0f, 1f)),
                    style = Stroke(
                        width = strokeWidth,
                        cap = StrokeCap.Round
                    )
                )
                
                // Glow effect - uses shifted color too
                drawPath(
                    path = path,
                    color = shiftedColor.copy(alpha = (baseAlpha * 0.25f).coerceIn(0f, 1f)),
                    style = Stroke(
                        width = strokeWidth * 2.5f,
                        cap = StrokeCap.Round
                    )
                )
            }

            // Dark center vortex overlay - pure black hole
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Black,
                        Color.Black.copy(alpha = 0.8f),
                        Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = maxRadius * 0.15f
                ),
                radius = maxRadius * 0.15f,
                center = Offset(cx, cy)
            )
        }
    }

    private fun lerpColor(c1: Color, c2: Color, t: Float): Color {
        val ct = t.coerceIn(0f, 1f)
        return Color(
            red = c1.red + (c2.red - c1.red) * ct,
            green = c1.green + (c2.green - c1.green) * ct,
            blue = c1.blue + (c2.blue - c1.blue) * ct,
            alpha = c1.alpha + (c2.alpha - c1.alpha) * ct
        )
    }

    companion object {
        val Default = VisualizationLiquidEffects(
            frostSmall = 2f,
            frostMedium = 4f,
            frostLarge = 6f,
            tintAlpha = 0.10f,
            top = VisualizationLiquidScope(
                refraction = .25f,
                curve = .15f,
                saturation = 2f,
                dispersion = .5f,
            ),
            bottom = VisualizationLiquidScope(
                refraction = .25f,
                curve = .15f,
                saturation = 1.5f,
                dispersion = .2f,
            ),
            title = CenterPanelStyle(
                // Swirly specific default
                scope = VisualizationLiquidScope(
                    contrast = 1.1f,
                    saturation = 5f,
                    dispersion = .3f,
                    refraction = .25f,
                    curve = .15f,
                ),
                titleColor = OrpheusColors.swirlyPurple, // Deep Purple base
                borderColor = OrpheusColors.swirlyPurple.copy(alpha = 0.3f),
                titleElevation = 4.dp
            )
        )
    }
}
