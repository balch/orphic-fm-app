package org.balch.orpheus.features.visualizations.viz

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.features.visualizations.viz.shader.MetaballsCanvas
import org.balch.orpheus.features.visualizations.viz.shader.MetaballsConfig
import org.balch.orpheus.ui.infrastructure.CenterPanelStyle
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidScope
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.Blob
import org.balch.orpheus.ui.viz.Visualization
import kotlin.math.cos
import kotlin.math.pow
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
    private val engine: SynthEngine,
    private val dispatcherProvider: DispatcherProvider,
) : Visualization {

    override val id = "shader_lamp"
    override val name = "Shader Lamp"
    override val color = OrpheusColors.neonCyan
    override val knob1Label = "SPEED"
    override val knob2Label = "SIZE"

    override val liquidEffects = Default

    private var _speedKnob = 0.5f
    private var _sizeKnob = 0.5f

    override fun setKnob1(value: Float) { _speedKnob = value.coerceIn(0f, 1f) }
    override fun setKnob2(value: Float) { _sizeKnob = value.coerceIn(0f, 1f) }

    private val voicePairColors = listOf(
        OrpheusColors.neonMagenta,
        OrpheusColors.electricBlue,
        OrpheusColors.synthGreen,
        OrpheusColors.neonCyan
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

    private val _uiState = mutableStateOf(ShaderLampUiState(), neverEqualPolicy())
    private var active = false

    override fun onActivate() {
        active = true
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
                targetChangeTime = 0L
            ))
        }
    }

    override fun onDeactivate() {
        active = false
        blobs.clear()
        animationTime = 0f
        _uiState.value = ShaderLampUiState()
    }

    @Composable
    override fun Content(modifier: Modifier) {
        // Frame-synchronized animation loop
        LaunchedEffect(Unit) {
            var lastFrameNanos = 0L

            while (true) {
                withFrameNanos { frameNanos ->
                    if (!active) {
                        lastFrameNanos = frameNanos
                        return@withFrameNanos
                    }

                    val dt = if (lastFrameNanos == 0L) {
                        0.016f // first frame: assume 60fps
                    } else {
                        ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
                    }
                    lastFrameNanos = frameNanos
                    animationTime += dt

                    val voiceLevels = engine.voiceLevelsFlow.value
                    val lfoValue = engine.lfoOutputFlow.value
                    val masterLevel = engine.masterLevelFlow.value

                    updateBlobs(voiceLevels, masterLevel, lfoValue, dt)

                    _uiState.value = ShaderLampUiState(
                        blobs = blobs.map { it.toBlob() },
                        lfoModulation = lfoValue,
                        masterEnergy = masterLevel,
                        animationTime = animationTime
                    )
                }
            }
        }

        val state = _uiState.value

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
//        velocityY = velocityY,
        color = color,
        voiceIndex = voiceIndex,
        // Boost energy (color weight/influence) when active to retain color vibrancy
        energy = if (alpha > 0.005f) 0.6f + alpha * 0.4f else alpha * 120f,
        alpha = alpha,
        age = 0f
    )

    private fun updateBlobs(voiceLevels: FloatArray, masterLevel: Float, lfoValue: Float, deltaTime: Float) {
        // All forces/damping were tuned at ~33fps (delay(30)). Normalize so
        // fs = 1.0 at 33fps, allowing the same constants to work at any fps.
        val fs = (deltaTime * 33f).coerceIn(0.1f, 3f)
        val globalSpeed = baseSpeed * speedMultiplier

        for (blob in blobs) {
            val voiceLevel = voiceLevels.getOrElse(blob.voiceIndex) { 0f }
            val totalEnergy = voiceLevel + masterLevel * 0.4f

            // ALPHA: exponential smoothing scaled by frame step
            val audioPower = (voiceLevel * 2.5f + masterLevel * 0.5f).coerceIn(0f, 1f)
            val targetAlpha = audioPower * audioPower
            blob.alpha = (blob.alpha * (1f - 0.15f * fs) + targetAlpha * 0.15f * fs).coerceIn(0.0001f, 1.0f)

            // SIZE: exponential smoothing scaled by frame step
            val targetRad = (baseRadius + (totalEnergy * (maxRadius - baseRadius))) * sizeMultiplier
            blob.radius = blob.radius * (1f - 0.07f * fs) + targetRad * 0.07f * fs

            // TARGET WANDERING
            blob.targetChangeTime -= (deltaTime * 1000f).toLong()
            if (blob.targetChangeTime <= 0) {
                blob.targetX = 0.1f + Random.nextFloat() * 0.8f
                blob.targetY = 0.1f + Random.nextFloat() * 0.8f
                blob.targetChangeTime = 4000L + Random.nextLong(6000)
            }

            val dx = blob.targetX - blob.x
            val dy = blob.targetY - blob.y
            val dist = sqrt(dx * dx + dy * dy)

            if (dist > 0.02f) {
                val wanderForce = globalSpeed * (1.0f + totalEnergy * 2.0f) * fs
                blob.velocityX += (dx / dist) * wanderForce
                blob.velocityY += (dy / dist) * wanderForce
            }

            // LFO Swirl
            val lfoMag = lfoValue * 0.0008f * speedMultiplier * fs
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
                    val repel = 0.0002f * (combinedR * 1.2f - odist) * fs
                    blob.velocityX -= (ox / odist) * repel
                    blob.velocityY -= (oy / odist) * repel
                } else if (odist < 0.4f) {
                    val attract = 0.00005f * (totalEnergy + 0.1f) * fs
                    blob.velocityX += (ox / odist) * attract
                    blob.velocityY += (oy / odist) * attract
                }
            }

            // MOMENTUM & DAMPING — exponentiate damping by frame step
            val baseDamping = 0.96f + (totalEnergy * 0.02f).coerceAtMost(0.035f)
            val damping = baseDamping.pow(fs)
            blob.velocityX *= damping
            blob.velocityY *= damping

            // APPLY PHYSICS
            blob.x += blob.velocityX
            blob.y += blob.velocityY

            // SOFT BOUNDARIES (scale nudge by fs)
            val margin = 0.1f
            val nudge = 0.001f * fs
            if (blob.x < margin) { blob.velocityX += nudge; blob.targetX = 0.5f }
            if (blob.x > 1.0f - margin) { blob.velocityX -= nudge; blob.targetX = 0.5f }
            if (blob.y < margin) { blob.velocityY += nudge; blob.targetY = 0.5f }
            if (blob.y > 1.0f - margin) { blob.velocityY -= nudge; blob.targetY = 0.5f }

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
            title = CenterPanelStyle(
                scope = VisualizationLiquidScope(
                    saturation = 4f,
                    dispersion = 1.5f,
                    curve = .3f,
                    refraction = 1f,
                    contrast = .75f,
                ),
                titleColor = OrpheusColors.neonMagenta,
                borderColor = OrpheusColors.neonMagenta.copy(alpha = 0.4f),
                borderWidth = 3.dp,
                titleElevation = 12.dp,
            ),
        )
    }
}
