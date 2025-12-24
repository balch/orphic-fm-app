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
import org.balch.songe.ui.widgets.VizBackground
import org.balch.songe.util.currentTimeMillis
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
 * Represents a lava lamp blob/bubble in the visualization.
 */
data class Blob(
    val id: Int,
    var x: Float,              // 0-1 normalized horizontal position
    var y: Float,              // 0-1 normalized vertical position (0=bottom, 1=top)
    var radius: Float,         // 0-0.3 normalized radius
    var velocityY: Float,      // upward drift velocity
    var color: Color, // color based on voice pair
    var voiceIndex: Int,       // source voice 0-7
    var energy: Float,         // current audio energy driving this blob
    var alpha: Float = 1f,     // fade alpha for dying blobs
    var age: Float = 0f        // age in seconds for lifecycle
)


/**
 * Lava Lamp visualization implementation.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class LavaLampViz(
    private val engine: SongeEngine,
    private val dispatcherProvider: DispatcherProvider,
) : Visualization {

    override val id = "lava_lamp"
    override val name = "Lava Lamp"
    override val color = SongeColors.deepPurple
    override val knob1Label = "SPEED"
    override val knob2Label = "SIZE"
    
    // Moderate translucency for lava lamp blobs
    override val liquidEffects = VisualizationLiquidEffects(
        frostSmall = 5f, frostMedium = 7f, frostLarge = 9f,
        tintAlpha = 0.12f, saturation = 0.70f, contrast = 0.75f
    )

    // Knob values (0-1 normalized, default 0.5)
    private var _speedKnob = 0.5f
    private var _sizeKnob = 0.5f

    override fun setKnob1(value: Float) {
        _speedKnob = value.coerceIn(0f, 1f)
    }

    override fun setKnob2(value: Float) {
        _sizeKnob = value.coerceIn(0f, 1f)
    }

    // Colors for each voice pair (matches Lyra-8 grouping)
    private val voicePairColors = listOf(
        SongeColors.neonMagenta,    // Voices 1-2: Bass (magenta)
        SongeColors.electricBlue,   // Voices 3-4: Mid (blue)
        SongeColors.synthGreen,     // Voices 5-6: Mid (green)
        SongeColors.neonCyan        // Voices 7-8: High (cyan)
    )

    // Internal blob state
    private val blobs = mutableListOf<Blob>()
    private var nextBlobId = 0

    // Configuration (base values, modified by knobs)
    private val maxBlobs = 32 // Increased count
    private val baseSpawnThreshold = 0.08f
    private val baseDriftSpeedBase = 0.002f 
    private val tearOffRadiusBase = 0.12f
    private val fadeSpeed = 0.05f
    private val growthRate = 0.003f
    private val shrinkRate = 0.002f
    
    // Computed values from knobs with INCREASED IMPACT (0.1x to 4.0x/5.0x)
    private val speedMultiplier: Float get() = 0.1f + (_speedKnob * 4.9f) // 0.1x to 5.0x
    private val sizeMultiplier: Float get() = 0.2f + (_sizeKnob * 3.8f)   // 0.2x to 4.0x
    
    private val baseDriftSpeed: Float get() = baseDriftSpeedBase * speedMultiplier
    private val tearOffRadius: Float get() = tearOffRadiusBase * sizeMultiplier
    private val maxBlobRadius: Float get() = 0.35f * sizeMultiplier // Larger potential size

    private val _uiState = MutableStateFlow(LavaLampUiState())
    val uiState: StateFlow<LavaLampUiState> = _uiState.asStateFlow()

    private var vizJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onActivate() {
        if (vizJob?.isActive == true) return
        
        vizJob = scope.launch(dispatcherProvider.default) {
            var lastFrameTime = currentTimeMillis()
            
            while (isActive) {
                val currentTime = currentTimeMillis()
                val deltaTime = (currentTime - lastFrameTime) / 1000f
                lastFrameTime = currentTime

                val voiceLevels = engine.voiceLevelsFlow.value
                val lfoValue = engine.lfoOutputFlow.value
                val masterLevel = engine.masterLevelFlow.value

                // Update physics and spawn new blobs
                updateBlobs(voiceLevels, masterLevel, lfoValue, deltaTime)

                _uiState.value = LavaLampUiState(
                    blobs = blobs.toList(),
                    lfoModulation = lfoValue,
                    masterEnergy = masterLevel
                )

                delay(40) // ~25fps for less CPU
            }
        }
    }

    override fun onDeactivate() {
        vizJob?.cancel()
        vizJob = null
        blobs.clear() // Clear state on deactivate
        _uiState.value = LavaLampUiState()
    }

    @Composable
    override fun Content(modifier: Modifier) {
        val state by uiState.collectAsState()
        // Delegate to the composable that knows how to draw this state
        // Reusing the existing VizBackground logic but moving logic here if needed.
        // For now, we update VizBackground to take this state or we pass it here.
        // Actually, better to keep the renderer separate or reuse the composable.
        // But VizBackground was previously the renderer.
        // Let's call the specific renderer for LavaLamp
        
        // We will need to expose the actual rendering composable.
        // Since VizBackground currently imports LavaLampUiState, we can reuse pieces of it.
        // But VizBackground.kt is currently the 'Panel Surface', wait.
        // No, VizBackground.kt handles the drawing.
        
        VizBackground(
            modifier = modifier,
            blobs = state.blobs,
            lfoModulation = state.lfoModulation,
            masterEnergy = state.masterEnergy
        )
    }

    private fun updateBlobs(voiceLevels: FloatArray, masterLevel: Float, lfoValue: Float, deltaTime: Float) {
        // Calculate total voice activity
        
        // Spawn/Update logic similar to before but tuned
        
        // Spawn new blobs for active voices
        for (voiceIndex in 0 until 8) {
            val level = voiceLevels.getOrElse(voiceIndex) { 0f }
            val effectiveLevel = maxOf(level, masterLevel * 0.6f)
            if (effectiveLevel > baseSpawnThreshold && blobs.size < maxBlobs) {
                maybeSpawnBlob(voiceIndex, effectiveLevel)
            }
        }
        
        // Spawn ambient blobs
        if (masterLevel > baseSpawnThreshold * 0.8f && blobs.size < maxBlobs) {
            maybeSpawnAmbientBlob(masterLevel, lfoValue)
        }

        // Update existing blobs
        val blobsToRemove = mutableListOf<Blob>()
        val blobsToAdd = mutableListOf<Blob>()

        for (blob in blobs) {
            val voiceLevel = voiceLevels.getOrElse(blob.voiceIndex) { 0f }
            blob.energy = maxOf(voiceLevel, masterLevel * 0.7f)

            // LFO affects drift speed
            val lfoSpeedMod = 1f + (lfoValue * 0.3f)

            // Drift upward
            blob.velocityY = baseDriftSpeed * lfoSpeedMod
            blob.y += blob.velocityY

            // Grow/Shrink
            if (blob.energy > baseSpawnThreshold * 0.5f) {
                blob.radius = (blob.radius + growthRate * blob.energy).coerceAtMost(maxBlobRadius)
                blob.alpha = (blob.alpha + 0.1f).coerceAtMost(1f)
            } else {
                blob.radius = (blob.radius - shrinkRate).coerceAtLeast(0.02f)
                blob.alpha = (blob.alpha - fadeSpeed).coerceAtLeast(0f)
            }

            blob.age += deltaTime

            // Tear off
            if (blob.radius > tearOffRadius && Random.nextFloat() < 0.02f && blobs.size + blobsToAdd.size < maxBlobs) {
                val childBlob = spawnChildBlob(blob)
                if (childBlob != null) {
                    blobsToAdd.add(childBlob)
                    blob.radius *= 0.7f 
                }
            }

            if (blob.y > 1.1f || blob.alpha <= 0f || blob.radius < 0.02f) {
                blobsToRemove.add(blob)
            }
        }

        blobs.removeAll(blobsToRemove.toSet())
        blobs.addAll(blobsToAdd)
    }
    
    private fun maybeSpawnAmbientBlob(masterLevel: Float, lfoValue: Float) {
        if (blobs.size >= maxBlobs) return
        if (Random.nextFloat() > masterLevel * 0.15f * speedMultiplier) return // Speed affects ambient spawn rate
        
        val colorIndex = ((lfoValue + 1f) / 2f * 3.99f).toInt().coerceIn(0, 3)
        val color = voicePairColors[colorIndex]
        
        val x = Random.nextFloat() * 0.8f + 0.1f
        
        blobs.add(Blob(
            id = nextBlobId++,
            x = x,
            y = -0.05f + Random.nextFloat() * 0.1f,
            radius = (0.02f + (masterLevel * 0.04f)) * sizeMultiplier, // Affected by SIZE
            velocityY = baseDriftSpeed * 0.8f,
            color = color.copy(alpha = 0.5f),  // Reduced brightness
            voiceIndex = colorIndex * 2,
            energy = masterLevel
        ))
    }

    private fun maybeSpawnBlob(voiceIndex: Int, level: Float) {
        val existingCount = blobs.count { it.voiceIndex == voiceIndex }
        if (existingCount >= 3) return 

        if (Random.nextFloat() > level * 0.3f * speedMultiplier) return // Speed affects voice spawn rate

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
            radius = (0.03f + (level * 0.05f)) * sizeMultiplier, // Affected by SIZE
            velocityY = baseDriftSpeed,
            color = color.copy(alpha = 0.6f),  // Reduced brightness
            voiceIndex = voiceIndex,
            energy = level
        ))
    }

    private fun spawnChildBlob(parent: Blob): Blob? {
        val direction = if (Random.nextBoolean()) 1f else -1f
        val childX = (parent.x + direction * parent.radius * 0.5f).coerceIn(0f, 1f)

        return Blob(
            id = nextBlobId++,
            x = childX,
            y = parent.y,
            radius = parent.radius * 0.4f,
            velocityY = baseDriftSpeed * 1.5f,
            color = parent.color.copy(alpha = 0.5f),  // Reduced brightness
            voiceIndex = parent.voiceIndex,
            energy = parent.energy * 0.7f,
            alpha = 0.8f
        )
    }
}
