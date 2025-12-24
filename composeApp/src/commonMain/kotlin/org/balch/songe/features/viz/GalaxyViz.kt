package org.balch.songe.features.viz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
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
import org.balch.songe.util.currentTimeMillis
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * UI State for Galaxy visualization.
 */
data class GalaxyUiState(
    val rotationAngle: Float = 0f,
    val masterEnergy: Float = 0f,
    val lfoModulation: Float = 0f,
    val voiceLevels: FloatArray = FloatArray(8)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as GalaxyUiState
        return rotationAngle == other.rotationAngle && masterEnergy == other.masterEnergy &&
               lfoModulation == other.lfoModulation && voiceLevels.contentEquals(other.voiceLevels)
    }
    override fun hashCode(): Int {
        var result = rotationAngle.hashCode()
        result = 31 * result + masterEnergy.hashCode()
        result = 31 * result + lfoModulation.hashCode()
        result = 31 * result + voiceLevels.contentHashCode()
        return result
    }
}

/**
 * A particle/star in the galaxy with pre-computed color.
 */
data class Star(
    val radiusFactor: Float,     // 0-1, how far from center
    val branchIndex: Int,        // which spiral arm (0-3)
    val angleOffset: Float,      // random offset within arm
    val radiusOffset: Float,     // random scatter perpendicular to arm
    val brightness: Float,       // base brightness 0-1
    val size: Float,             // base size
    val color: Color             // Pre-computed color based on radius
)

/**
 * Galaxy visualization - procedural spiral galaxy with particle stars.
 * 
 * OPTIMIZED: Pre-computes star colors, reduces allocation per frame.
 * Uses simple solid circles instead of radial gradients for performance.
 *
 * Knob1 (SPIN): Controls rotation speed
 * Knob2 (ARMS): Controls spiral arm tightness
 */
@Inject
@ContributesIntoSet(AppScope::class)
class GalaxyViz(
    private val engine: SongeEngine,
    private val dispatcherProvider: DispatcherProvider,
) : Visualization {

    override val id = "galaxy"
    override val name = "Galaxy"
    override val color = SongeColors.neonCyan
    override val knob1Label = "SPIN"
    override val knob2Label = "ARMS"

    private var _spinKnob = 0.5f
    private var _armsKnob = 0.5f

    override fun setKnob1(value: Float) {
        _spinKnob = value.coerceIn(0f, 1f)
    }

    override fun setKnob2(value: Float) {
        _armsKnob = value.coerceIn(0f, 1f)
    }

    // Galaxy colors - warm core to cool rim
    private val coreColor = Color(0xFFFF6030)      // Warm orange
    private val midColor = Color(0xFFAA40AA)       // Purple transition
    private val rimColor = Color(0xFF1B3984)       // Cool blue

    private val _uiState = MutableStateFlow(GalaxyUiState())
    val uiState: StateFlow<GalaxyUiState> = _uiState.asStateFlow()

    private var vizJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)
    private var rotationAngle = 0f
    private var smoothedEnergy = 0f

    // REDUCED star count for performance, PRE-COMPUTE colors
    private val starCount = 800  // Reduced from 3000
    private val stars: List<Star> = generateStars()

    private fun generateStars(): List<Star> {
        val random = Random(42) // Fixed seed for consistent galaxy
        return List(starCount) {
            val radiusFactor = random.nextFloat().pow(0.5f)
            val brightness = 0.3f + random.nextFloat() * 0.7f
            
            // Pre-compute color based on radius
            val t = radiusFactor
            val starColor = when {
                t < 0.3f -> lerpColor(coreColor, midColor, t / 0.3f)
                else -> lerpColor(midColor, rimColor, (t - 0.3f) / 0.7f)
            }
            
            Star(
                radiusFactor = radiusFactor,
                branchIndex = random.nextInt(4), // 4 spiral arms
                angleOffset = (random.nextFloat() - 0.5f) * 0.8f,
                radiusOffset = (random.nextFloat() - 0.5f) * 0.15f,
                brightness = brightness,
                size = 2f + random.nextFloat() * 4f,  // 2-6 base size
                color = starColor
            )
        }
    }

    override fun onActivate() {
        if (vizJob?.isActive == true) return
        rotationAngle = 0f
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

                smoothedEnergy = smoothedEnergy * 0.92f + masterLevel * 0.08f

                // Rotation - NEGATIVE for reverse direction
                val baseSpeed = -(0.02f + _spinKnob * 0.08f)
                val audioBoost = -smoothedEnergy * 0.1f
                rotationAngle += deltaTime * (baseSpeed + audioBoost)

                _uiState.value = GalaxyUiState(
                    rotationAngle = rotationAngle,
                    masterEnergy = smoothedEnergy,
                    lfoModulation = lfoValue,
                    voiceLevels = voiceLevels
                )

                delay(40) // ~25fps for less CPU usage
            }
        }
    }

    override fun onDeactivate() {
        vizJob?.cancel()
        vizJob = null
        rotationAngle = 0f
        smoothedEnergy = 0f
        _uiState.value = GalaxyUiState()
    }

    @Composable
    override fun Content(modifier: Modifier) {
        val state by uiState.collectAsState()

        Canvas(modifier = modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val maxRadius = minOf(w, h) * 0.7f

            val rotation = state.rotationAngle
            val energy = state.masterEnergy
            val lfo = state.lfoModulation
            val spinFactor = 1.0f + _armsKnob * 2.0f

            // Brighter base intensity (+10%)
            val baseIntensity = 0.4f + energy * 0.45f  // 0.4-0.85 range

            // Deep space background
            drawRect(Color(0xFF000008))

            // Draw stars - SIMPLE CIRCLES, no gradients per star
            for (star in stars) {
                val baseAngle = (star.branchIndex.toFloat() / 4f) * 2f * PI.toFloat()
                val spiralAngle = star.radiusFactor * spinFactor * 2f * PI.toFloat()
                val angle = baseAngle + spiralAngle + star.angleOffset + rotation
                val radius = star.radiusFactor * maxRadius * (1f + star.radiusOffset)

                val x = cx + radius * cos(angle)
                val y = cy + radius * sin(angle)

                // Voice-based brightness AND size variation
                val voiceIdx = star.branchIndex
                val voiceEnergy = (state.voiceLevels.getOrElse(voiceIdx * 2) { 0f } +
                                   state.voiceLevels.getOrElse(voiceIdx * 2 + 1) { 0f }) / 2f

                // More musical reactivity: voice affects alpha and size significantly
                val alpha = (star.brightness * baseIntensity * (0.5f + voiceEnergy * 0.8f)).coerceIn(0f, 1f)
                
                // Size varies with energy, LFO, AND individual voice level
                val sizeMultiplier = 1f + energy * 0.6f + lfo * 0.3f + voiceEnergy * 0.8f
                val starSize = star.size * sizeMultiplier

                // LFO shifts the hue slightly - blend toward different color
                val lfoColorShift = (lfo + 1f) / 2f  // 0-1 range
                val shiftedColor = if (lfoColorShift > 0.5f) {
                    // Shift toward cyan/white
                    lerpColor(star.color, Color(0xFF80C0FF), (lfoColorShift - 0.5f) * 0.3f)
                } else {
                    // Shift toward magenta
                    lerpColor(star.color, Color(0xFFFF80C0), (0.5f - lfoColorShift) * 0.3f)
                }

                // Use shifted color
                drawCircle(
                    color = shiftedColor.copy(alpha = alpha),
                    radius = starSize,
                    center = Offset(x, y),
                    blendMode = BlendMode.Plus
                )

                // Optional soft halo for brighter stars only
                if (star.brightness > 0.75f) {
                    drawCircle(
                        color = star.color.copy(alpha = alpha * 0.3f),
                        radius = starSize * 2.5f,
                        center = Offset(x, y),
                        blendMode = BlendMode.Plus
                    )
                }
            }

            // Subtle center point
            drawCircle(
                color = Color.White.copy(alpha = 0.15f * baseIntensity),
                radius = 2f + energy * 1f,
                center = Offset(cx, cy),
                blendMode = BlendMode.Plus
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
}
