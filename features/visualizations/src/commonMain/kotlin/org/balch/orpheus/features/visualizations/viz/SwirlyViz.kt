package org.balch.orpheus.features.visualizations.viz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.ui.infrastructure.CenterPanelStyle
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidScope
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.DynamicVisualization
import org.balch.orpheus.ui.viz.Visualization
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
 * Swirly vortex visualization using Compose withFrameNanos for vsync-aligned animation.
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

    override val liquidEffects = Default

    private var _spinKnob = 0.5f
    private var _depthKnob = 0.5f

    override fun setKnob1(value: Float) {
        _spinKnob = value.coerceIn(0f, 1f)
    }

    override fun setKnob2(value: Float) {
        _depthKnob = value.coerceIn(0f, 1f)
    }

    private val spiralColors = listOf(
        OrpheusColors.swirlyPurple,
        OrpheusColors.swirlyBluePurple,
        OrpheusColors.swirlyTealBlue,
        OrpheusColors.swirlyMutedPurple,
        OrpheusColors.swirlyTeal,
        OrpheusColors.swirlyPinkPurple
    )

    private val _uiState = mutableStateOf(SwirlyUiState(), neverEqualPolicy())

    private var animationTime = 0f
    private var smoothedEnergy = 0f
    private var active = false

    private val _liquidEffects = MutableStateFlow(Default)
    override val liquidEffectsFlow: Flow<VisualizationLiquidEffects> = _liquidEffects.asStateFlow()

    override fun onActivate() {
        active = true
        animationTime = 0f
        smoothedEnergy = 0f
    }

    override fun onDeactivate() {
        active = false
        animationTime = 0f
        smoothedEnergy = 0f
        _uiState.value = SwirlyUiState()
    }

    @Composable
    override fun Content(modifier: Modifier) {
        LaunchedEffect(Unit) {
            var lastFrameNanos = 0L
            while (true) {
                withFrameNanos { frameNanos ->
                    if (!active) {
                        lastFrameNanos = frameNanos
                        return@withFrameNanos
                    }
                    val deltaTime = if (lastFrameNanos == 0L) 0.016f
                    else ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
                    lastFrameNanos = frameNanos

                    val voiceLevels = engine.voiceLevelsFlow.value
                    val lfoValue = engine.lfoOutputFlow.value
                    val masterLevel = engine.masterLevelFlow.value

                    smoothedEnergy = smoothedEnergy * 0.9f + masterLevel * 0.1f

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

                    _liquidEffects.value = Default.copy(
                        title = Default.title.copy(
                            titleColor = titleColor,
                            titleElevation = (4.dp + (smoothedEnergy * 12).dp),
                            borderWidth = (1.dp + (smoothedEnergy * 2).dp),
                            borderColor = titleColor.copy(alpha = 0.3f + smoothedEnergy * 0.5f)
                        )
                    )
                }
            }
        }

        val state = _uiState.value
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
                        Color.Black,
                        OrpheusColors.fireworksBackground,
                        OrpheusColors.blackHoleDeep,
                        OrpheusColors.darkVoid
                    ),
                    center = Offset(cx, cy),
                    radius = maxRadius * 1.2f
                ),
                radius = maxRadius * 1.5f,
                center = Offset(cx, cy)
            )

            val spiralTightness = 2f + _depthKnob * 4f
            val armCount = 6

            for (arm in 0 until armCount) {
                val armAngleOffset = (arm.toFloat() / armCount) * 2f * PI.toFloat()

                val voiceIdx = arm % 4
                val voiceEnergy = (state.voiceLevels.getOrElse(voiceIdx * 2) { 0f } +
                                   state.voiceLevels.getOrElse(voiceIdx * 2 + 1) { 0f }) / 2f

                val armColor = spiralColors[arm % spiralColors.size]

                path.reset()
                val segments = 60

                for (i in 0..segments) {
                    val t = i.toFloat() / segments
                    val radius = maxRadius * (1f - t * 0.95f)
                    val spiralAngle = armAngleOffset + time + t * spiralTightness * 2f * PI.toFloat()
                    val wobble = sin(t * 8f + time * 2f) * lfo * 0.03f * radius

                    val x = cx + (radius + wobble) * cos(spiralAngle)
                    val y = cy + (radius + wobble) * sin(spiralAngle)

                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }

                val baseAlpha = 0.1f + voiceEnergy * 0.5f + energy * 0.4f
                val strokeWidth = 8f + energy * 5f + voiceEnergy * 7f

                val lfoShift = (lfo + 1f) / 2f
                val shiftedColor = if (lfoShift > 0.5f) {
                    lerpColor(armColor, Color(0xFFAA5599), (lfoShift - 0.5f) * 0.4f)
                } else {
                    lerpColor(armColor, Color(0xFF5599AA), (0.5f - lfoShift) * 0.4f)
                }

                drawPath(
                    path = path,
                    color = shiftedColor.copy(alpha = baseAlpha.coerceIn(0f, 1f)),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                drawPath(
                    path = path,
                    color = shiftedColor.copy(alpha = (baseAlpha * 0.25f).coerceIn(0f, 1f)),
                    style = Stroke(width = strokeWidth * 2.5f, cap = StrokeCap.Round)
                )
            }

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
                scope = VisualizationLiquidScope(
                    contrast = 1.1f,
                    saturation = 5f,
                    dispersion = .3f,
                    refraction = .25f,
                    curve = .15f,
                ),
                titleColor = OrpheusColors.swirlyPurple,
                borderColor = OrpheusColors.swirlyPurple.copy(alpha = 0.3f),
                titleElevation = 4.dp
            )
        )
    }
}
