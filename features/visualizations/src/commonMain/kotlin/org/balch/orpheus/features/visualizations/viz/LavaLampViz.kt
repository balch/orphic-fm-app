package org.balch.orpheus.features.visualizations.viz

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.ui.infrastructure.CenterPanelStyle
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidScope
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.Blob
import org.balch.orpheus.ui.viz.Visualization
import org.balch.orpheus.ui.widgets.VizBackground
import kotlin.math.sin
import kotlin.random.Random

/**
 * UI state for the lava lamp background.
 */
data class LavaLampUiState(
    val blobs: List<Blob> = emptyList(),
    val lfoModulation: Float = 0f,    // -1 to 1, affects color hue shift
    val masterEnergy: Float = 0f      // 0-1, overall brightness multiplier
)

/**
 * Lava Lamp visualization using Compose withFrameNanos for vsync-aligned animation.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class LavaLampViz(
    private val engine: SynthEngine,
    private val dispatcherProvider: DispatcherProvider,
) : Visualization {

    override val id = "lava_lamp"
    override val name = "Lava Lamp"
    override val color = OrpheusColors.deepPurple
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

    private val voicePairColors = listOf(
        OrpheusColors.neonMagenta,    // Voices 1-2: Bass (magenta)
        OrpheusColors.electricBlue,   // Voices 3-4: Mid (blue)
        OrpheusColors.synthGreen,     // Voices 5-6: Mid (green)
        OrpheusColors.neonCyan        // Voices 7-8: High (cyan)
    )

    // Internal blob state
    private val blobs = mutableListOf<Blob>()
    private var nextBlobId = 0
    private val pendingAdds = mutableListOf<Blob>()
    private var active = false

    // Configuration — all rates are per-second
    private val maxBlobs = 32
    private val baseSpawnThreshold = 0.08f
    private val driftPerSec = 0.075f
    private val tearOffRadiusBase = 0.08f
    private val fadePerSec = 1.2f
    private val growthPerSec = 0.05f
    private val shrinkPerSec = 0.05f

    private val speedMultiplier: Float get() = 0.3f + (_speedKnob * 2.7f)
    private val sizeMultiplier: Float get() = 0.3f + (_sizeKnob * 1.7f)

    private val baseDriftSpeed: Float get() = driftPerSec * speedMultiplier
    private val tearOffRadius: Float get() = tearOffRadiusBase * sizeMultiplier
    private val maxBlobRadius: Float get() = 0.18f * sizeMultiplier

    // neverEqualPolicy: always recompose since blobs are mutated in-place
    private val _uiState = mutableStateOf(LavaLampUiState(), neverEqualPolicy())

    override fun onActivate() {
        active = true
    }

    override fun onDeactivate() {
        active = false
        blobs.clear()
        _uiState.value = LavaLampUiState()
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
                    val dt = if (lastFrameNanos == 0L) 0.016f
                    else ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
                    lastFrameNanos = frameNanos

                    val voiceLevels = engine.voiceLevelsFlow.value
                    val lfoValue = engine.lfoOutputFlow.value
                    val masterLevel = engine.masterLevelFlow.value

                    updateBlobs(voiceLevels, masterLevel, lfoValue, dt)

                    _uiState.value = LavaLampUiState(
                        blobs = ArrayList(blobs),
                        lfoModulation = lfoValue,
                        masterEnergy = masterLevel
                    )
                }
            }
        }

        val state = _uiState.value
        VizBackground(
            modifier = modifier,
            blobs = state.blobs,
            lfoModulation = state.lfoModulation,
            masterEnergy = state.masterEnergy
        )
    }

    private fun updateBlobs(voiceLevels: FloatArray, masterLevel: Float, lfoValue: Float, dt: Float) {
        // Scale spawn probability so rate is independent of fps
        // Original was tuned at 25fps, so spawnScale = dt * 25 ≈ 1.0 at 25fps
        val spawnScale = (dt * 25f).coerceAtMost(1f)

        for (voiceIndex in 0 until 8) {
            val level = voiceLevels.getOrElse(voiceIndex) { 0f }
            val effectiveLevel = maxOf(level, masterLevel * 0.6f)
            if (effectiveLevel > baseSpawnThreshold && blobs.size < maxBlobs) {
                maybeSpawnBlob(voiceIndex, effectiveLevel, spawnScale)
            }
        }

        if (masterLevel > baseSpawnThreshold * 0.8f && blobs.size < maxBlobs) {
            maybeSpawnAmbientBlob(masterLevel, lfoValue, spawnScale)
        }

        pendingAdds.clear()
        val lfoSpeedMod = 1f + (lfoValue * 0.3f)
        val driftThisFrame = baseDriftSpeed * lfoSpeedMod * dt

        val iter = blobs.iterator()
        while (iter.hasNext()) {
            val blob = iter.next()
            val voiceLevel = voiceLevels.getOrElse(blob.voiceIndex) { 0f }
            blob.energy = maxOf(voiceLevel, masterLevel * 0.7f)

            blob.y += driftThisFrame

            // Sinusoidal horizontal wobble
            val wobblePhase = blob.age * speedMultiplier * 1.5f + blob.id * 1.7f
            val wobbleAmount = 0.08f * (1f + blob.radius * 8f) * dt
            blob.x = (blob.x + sin(wobblePhase) * wobbleAmount + lfoValue * 0.02f * dt)
                .coerceIn(0.02f, 0.98f)

            if (blob.energy > baseSpawnThreshold * 0.5f) {
                blob.radius = (blob.radius + growthPerSec * blob.energy * dt).coerceAtMost(maxBlobRadius)
                blob.alpha = (blob.alpha + fadePerSec * dt).coerceAtMost(0.7f)
            } else {
                blob.radius = (blob.radius - shrinkPerSec * dt).coerceAtLeast(0.02f)
                blob.alpha = (blob.alpha - fadePerSec * dt).coerceAtLeast(0f)
            }

            blob.age += dt

            if (blob.radius > tearOffRadius && Random.nextFloat() < 0.02f * spawnScale && blobs.size + pendingAdds.size < maxBlobs) {
                pendingAdds.add(spawnChildBlob(blob))
                blob.radius *= 0.7f
            }

            if (blob.y > 1.1f || blob.alpha <= 0f || blob.radius < 0.02f) {
                iter.remove()
            }
        }

        blobs.addAll(pendingAdds)
    }

    private fun maybeSpawnAmbientBlob(masterLevel: Float, lfoValue: Float, spawnScale: Float) {
        if (blobs.size >= maxBlobs) return
        if (Random.nextFloat() > masterLevel * 0.15f * speedMultiplier * spawnScale) return

        val colorIndex = ((lfoValue + 1f) / 2f * 3.99f).toInt().coerceIn(0, 3)
        val color = voicePairColors[colorIndex]

        blobs.add(Blob(
            id = nextBlobId++,
            x = Random.nextFloat() * 0.8f + 0.1f,
            y = -0.05f + Random.nextFloat() * 0.1f,
            radius = (0.015f + (masterLevel * 0.025f)) * sizeMultiplier,
            vy = baseDriftSpeed * 0.8f,
            color = color.copy(alpha = 0.35f),
            voiceIndex = colorIndex * 2,
            energy = masterLevel
        ))
    }

    private fun maybeSpawnBlob(voiceIndex: Int, level: Float, spawnScale: Float) {
        val existingCount = blobs.count { it.voiceIndex == voiceIndex }
        if (existingCount >= 3) return
        if (Random.nextFloat() > level * 0.3f * speedMultiplier * spawnScale) return

        val pairIndex = voiceIndex / 2
        val color = voicePairColors[pairIndex]
        val baseX = when (voiceIndex) {
            in 0..3 -> 0.15f + (voiceIndex * 0.1f)
            else -> 0.55f + ((voiceIndex - 4) * 0.1f)
        }

        blobs.add(Blob(
            id = nextBlobId++,
            x = baseX + (Random.nextFloat() - 0.5f) * 0.1f,
            y = -0.05f + Random.nextFloat() * 0.1f,
            radius = (0.02f + (level * 0.03f)) * sizeMultiplier,
            vy = baseDriftSpeed,
            color = color.copy(alpha = 0.45f),
            voiceIndex = voiceIndex,
            energy = level
        ))
    }

    private fun spawnChildBlob(parent: Blob): Blob {
        val direction = if (Random.nextBoolean()) 1f else -1f
        val childX = (parent.x + direction * parent.radius * 0.5f).coerceIn(0f, 1f)

        return Blob(
            id = nextBlobId++,
            x = childX,
            y = parent.y,
            radius = parent.radius * 0.4f,
            vy = baseDriftSpeed * 1.5f,
            color = parent.color.copy(alpha = 0.35f),
            voiceIndex = parent.voiceIndex,
            energy = parent.energy * 0.7f,
            alpha = 0.6f
        )
    }

    companion object {
        val Default = VisualizationLiquidEffects(
            frostSmall = 2f,
            frostMedium = 6f,
            frostLarge = 8f,
            tintAlpha = 0.12f,
            top = VisualizationLiquidScope(
                saturation = 0.40f,
                dispersion = .8f,
                curve = .15f,
                refraction = 0.4f,
            ),
            bottom = VisualizationLiquidScope(
                saturation = 1f,
                dispersion = .4f,
                curve = .15f,
                refraction = 0.8f,
            ),
            title = CenterPanelStyle(
                scope = VisualizationLiquidScope(
                    saturation = 4f,
                    dispersion = .2f,
                    curve = .1f,
                    refraction = 0.2f,
                ),
                titleColor = OrpheusColors.synthGreen,
                borderColor = OrpheusColors.neonMagenta.copy(alpha = 0.4f),
                borderWidth = 3.dp,
                titleElevation = 12.dp,
            ),
        )
    }
}
