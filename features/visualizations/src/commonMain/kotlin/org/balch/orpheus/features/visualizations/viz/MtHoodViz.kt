package org.balch.orpheus.features.visualizations.viz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.features.visualizations.viz.shader.MtHoodLavaSksl
import org.balch.orpheus.features.visualizations.viz.shader.PlasmaShader
import org.balch.orpheus.ui.infrastructure.CenterPanelStyle
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidScope
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.Visualization
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// --------------------------------------------------------------------------------
// Data Structures
// --------------------------------------------------------------------------------

data class LavaParticle(
    val id: Int,
    var x: Float,       // normalized 0-1
    var y: Float,       // normalized 0-1 (0=top in Canvas coords)
    var vx: Float,      // horizontal velocity
    var vy: Float,      // vertical velocity (positive = downward)
    var color: Color,
    var size: Float,    // radius in normalized units
    var life: Float,    // 1.0 -> 0.0
    var decay: Float,   // life decrease per second
    var sliding: Boolean = false, // landed: creeps down the mountain's face
    var pooled: Boolean = false,  // welled up in a valley, or stuck at the foot: stays put
    var drift: Float = 0f,        // sideways creep while sliding, away from the vent
)

data class SmokeParticle(
    val id: Int,
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,      // negative = upward
    var alpha: Float,
    var size: Float,
    var life: Float,
    var decay: Float,
)

data class MtHoodUiState(
    val lavaParticles: List<LavaParticle> = emptyList(),
    val ejecta: List<LavaParticle> = emptyList(),
    val smokeParticles: List<SmokeParticle> = emptyList(),
    val masterEnergy: Float = 0f,
    val lfoModulation: Float = 0f,
    val voiceLevels: FloatArray = FloatArray(8),
    val eruptionLevel: Float = 0f,  // smoothed eruption intensity 0-1
    val time: Float = 0f,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MtHoodUiState) return false
        return lavaParticles == other.lavaParticles &&
               ejecta == other.ejecta &&
               smokeParticles == other.smokeParticles &&
               masterEnergy == other.masterEnergy &&
               lfoModulation == other.lfoModulation &&
               voiceLevels.contentEquals(other.voiceLevels) &&
               eruptionLevel == other.eruptionLevel &&
               time == other.time
    }
    override fun hashCode(): Int {
        var result = lavaParticles.hashCode()
        result = 31 * result + ejecta.hashCode()
        result = 31 * result + smokeParticles.hashCode()
        result = 31 * result + masterEnergy.hashCode()
        result = 31 * result + lfoModulation.hashCode()
        result = 31 * result + voiceLevels.contentHashCode()
        result = 31 * result + eruptionLevel.hashCode()
        result = 31 * result + time.hashCode()
        return result
    }
}

// --------------------------------------------------------------------------------
// Mt. Hood Visualization
// --------------------------------------------------------------------------------

@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<Visualization>())
class MtHoodViz(
    private val engine: SynthEngine,
) : Visualization {

    override val id = "mt_hood"
    override val name = "Mt. Hood"
    override val color = OrpheusColors.neonOrange
    override val knob1Label = "HEAT"
    override val knob2Label = "SUNSET"

    override val liquidEffects = Default

    private var _heatKnob = 0.7f   // Default hotter — erupts more easily
    private var _sunsetKnob = 0.5f  // Start darker, halfway to twilight

    override fun setKnob1(value: Float) { _heatKnob = value.coerceIn(0f, 1f) }
    override fun setKnob2(value: Float) { _sunsetKnob = value.coerceIn(0f, 1f) }

    // Derived multipliers — higher base so eruption triggers easily
    private val heatMultiplier: Float get() = 0.8f + (_heatKnob * 2.2f)

    // Voice pair colors for lava particles
    private val voicePairColors = listOf(
        OrpheusColors.neonMagenta,
        OrpheusColors.electricBlue,
        OrpheusColors.synthGreen,
        OrpheusColors.neonCyan,
    )

    // Particle pools
    private val lavaParticles = mutableListOf<LavaParticle>()
    // Fiery sparks thrown from the vents; one that lands joins the sticky flow.
    private val ejecta = mutableListOf<LavaParticle>()
    private val maxEjecta = 160
    // Per vent: a slow follower of its band's level, and how long until it may throw again.
    private val ventEnvelope = FloatArray(3)
    private val ventCooldown = FloatArray(3)
    // Lava sizes are fractions of the screen's shorter side; this converts one to a fraction of its
    // width, from the last draw.
    private var sizeToX = 0.5f
    private val smokeParticles = mutableListOf<SmokeParticle>()
    private var nextLavaId = 0
    private var nextSmokeId = 0
    private var active = false
    private var animationTime = 0f
    private var smoothedEruption = 0f

    // A lava lamp: few, big, slow blobs. The shader melts them together; the Canvas fallback draws them as dots.
    // Three slots are left for the domes over the vents.
    private val maxLavaParticles = MtHoodLavaSksl.MAX_BLOBS - 3
    private val maxSmokeParticles = 20

    // Pre-allocated Path objects to avoid per-frame heap allocations
    private val backRidgePath = Path()
    private val hoodPath = Path()
    private val rimPath = Path()
    private val foregroundPath = Path()
    private val flowPath = Path()  // Reused for each lava flow segment
    private var silhouetteSize = Size.Zero

    // Pre-allocated gradient color-stop arrays to avoid per-frame heap allocations.
    // Colors are updated in-place each frame; only the float stop positions are truly static.
    private val skyStops = arrayOf(0f to Color.Transparent, 0.5f to Color.Transparent, 0.85f to Color.Transparent, 1f to Color.Transparent)
    private val sunGlowStops = arrayOf(0f to Color.Transparent, 0.3f to Color.Transparent, 1f to Color.Transparent)
    private val lavaGlowStops = arrayOf(0f to Color.Transparent, 0.4f to Color.Transparent, 0.7f to Color.Transparent, 1f to Color.Transparent)
    private val valleyGlowStops = arrayOf(0f to Color.Transparent, 0.3f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color.Transparent)

    private val _uiState = mutableStateOf(MtHoodUiState(), neverEqualPolicy())

    // Mt. Hood summit position (normalized coords)
    // Asymmetric peak: left of center, matching the photo reference
    private val peakX = 0.48f
    private val peakY = 0.38f  // ~62% up from bottom (Canvas y=0 is top, so 0.38 = 62% up)

    // All three eruption points: main peak + two distant back ridge peaks
    private data class EruptionPoint(val x: Float, val y: Float, val strength: Float)
    private val eruptionPoints = listOf(
        EruptionPoint(0.48f, 0.38f, 1.0f),   // Main Hood peak — strongest
        EruptionPoint(0.10f, 0.50f, 0.5f),   // Far left back ridge peak
        EruptionPoint(0.90f, 0.48f, 0.5f),   // Far right back ridge peak
    )

    // The ridges' skylines (normalized, y down): drawn as silhouettes, and the ground the lava
    // lands on and creeps down.
    private val backRidgeLine = floatArrayOf(
        0f, 0.55f, 0.1f, 0.50f, 0.25f, 0.52f, 0.35f, 0.48f, 0.5f, 0.53f, 0.65f, 0.46f, 0.8f, 0.50f,
        0.9f, 0.48f, 1f, 0.52f,
    )
    private val hoodLine = floatArrayOf(
        0f, 0.70f, 0.15f, 0.62f, 0.25f, 0.55f, 0.38f, 0.44f, 0.44f, 0.40f, 0.47f, 0.385f, 0.48f, 0.38f,
        0.495f, 0.383f, 0.54f, 0.41f, 0.58f, 0.43f, 0.60f, 0.42f, 0.65f, 0.46f, 0.70f, 0.50f,
        0.78f, 0.55f, 0.88f, 0.62f, 1f, 0.68f,
    )
    private val foregroundLine = floatArrayOf(
        0f, 0.82f, 0.1f, 0.80f, 0.3f, 0.83f, 0.5f, 0.79f, 0.7f, 0.82f, 0.85f, 0.80f, 1f, 0.83f,
    )

    // Valley positions — between the peaks, lava pools here when loud
    private data class ValleyPoint(val x: Float, val y: Float, val width: Float)
    private val valleyPoints = listOf(
        ValleyPoint(0.30f, 0.55f, 0.14f),  // Valley between far-left ridge and Hood
        ValleyPoint(0.68f, 0.52f, 0.12f),  // Valley between Hood and far-right ridge
        ValleyPoint(0.50f, 0.53f, 0.08f),  // Saddle just right of Hood base
    )

    override fun onActivate() {
        active = true
        engine.setSpectrumEnabled(true)
        animationTime = 0f
        smoothedEruption = 0f
        lavaParticles.clear()
        ejecta.clear()
        ventEnvelope.fill(0f)
        ventCooldown.fill(0f)
        smokeParticles.clear()
        nextLavaId = 0
        nextSmokeId = 0
    }

    override fun onDeactivate() {
        active = false
        engine.setSpectrumEnabled(false)
        lavaParticles.clear()
        ejecta.clear()
        smokeParticles.clear()
        animationTime = 0f
        smoothedEruption = 0f
        _uiState.value = MtHoodUiState()
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
                    animationTime += dt

                    val voiceLevels = engine.voiceLevelsFlow.value
                    val lfoValue = engine.lfoOutputFlow.value
                    val masterLevel = engine.masterLevelFlow.value

                    updateSimulation(voiceLevels, masterLevel, lfoValue, engine.spectrumFlow.value, dt)

                    _uiState.value = MtHoodUiState(
                        lavaParticles = lavaParticles.toList(),
                        ejecta = ejecta.toList(),
                        smokeParticles = smokeParticles.toList(),
                        masterEnergy = masterLevel,
                        lfoModulation = lfoValue,
                        voiceLevels = voiceLevels.copyOf(),
                        eruptionLevel = smoothedEruption,
                        time = animationTime,
                    )
                }
            }
        }

        val sunsetT = _sunsetKnob
        val lava = remember { PlasmaShader(MtHoodLavaSksl.SOURCE) }
        DisposableEffect(lava) { onDispose { lava.dispose() } }

        Canvas(modifier = modifier.fillMaxSize()) {
            // Read inside drawScope so per-frame _uiState writes only invalidate
            // the draw pass, not recompose this @Composable.
            val state = _uiState.value
            if (size.width > 0f) sizeToX = size.minDimension / size.width
            buildSilhouettes()
            drawSky(sunsetT, state.lfoModulation, state.time)
            drawSunGlow(sunsetT, state.eruptionLevel)
            drawBackRidge()
            if (!lava.isSupported) drawValleyLava(state.eruptionLevel, state.lfoModulation, state.time)
            drawMtHood(state.eruptionLevel)
            drawLavaGlow(state.eruptionLevel)
            if (lava.isSupported) {
                drawLavaLamp(lava, state.lavaParticles, state.eruptionLevel)
                drawLavaParticles(state.ejecta)
            } else {
                drawLavaFlows(state.eruptionLevel, state.time)
                // Half size: the lamp's blobs are sized to melt together, and dots that big read as balloons.
                drawLavaParticles(state.lavaParticles, scale = 0.5f)
                drawLavaParticles(state.ejecta)
            }
            drawSmokeParticles(state.smokeParticles)
            drawForegroundRidge()
        }
    }

    // -- Simulation --

    private fun updateSimulation(
        voiceLevels: FloatArray, masterLevel: Float, lfoValue: Float, bands: FloatArray, dt: Float
    ) {
        val targetEruption = (masterLevel * heatMultiplier).coerceIn(0f, 1f)
        smoothedEruption += (targetEruption - smoothedEruption) * (3f * dt).coerceAtMost(1f)

        // Lava wells up from all three peaks as tongues; louder means likelier.
        for (peak in eruptionPoints) {
            val peakEruption = smoothedEruption * peak.strength
            if (peakEruption < 0.05f) continue
            if (Random.nextFloat() < peakEruption * 0.2f * dt * 60f) {
                emitTongue(voiceLevels, peak, peakEruption)
            }
        }

        // A fountain of sparks while it erupts, and a tall burst when a vent's band hits: bass on
        // the main peak, mids and highs on the ridges.
        for ((v, peak) in eruptionPoints.withIndex()) {
            val level = ventLevel(v, bands, masterLevel)
            ventEnvelope[v] += (level - ventEnvelope[v]) * (1f - exp(-dt / 0.3f))
            ventCooldown[v] -= dt
            var hit = 0f
            if (ventCooldown[v] <= 0f && level > 0.12f && level > ventEnvelope[v] * 1.35f) {
                hit = ((level / max(ventEnvelope[v], 0.01f) - 1.35f) / 0.65f).coerceIn(0.3f, 1f)
                ventCooldown[v] = 0.12f
            }
            val peakEruption = smoothedEruption * peak.strength
            if (peakEruption < 0.05f) continue
            repeat(stochasticCount(peakEruption * 4f * dt * 60f).coerceAtMost(3)) {
                if (ejecta.size < maxEjecta) emitEjecta(voiceLevels, peak.x, peak.y, peakEruption, 0f)
            }
            if (hit > 0f) repeat((4 + 10 * peakEruption).toInt()) {
                if (ejecta.size < maxEjecta) emitEjecta(voiceLevels, peak.x, peak.y, peakEruption, hit)
            }
        }

        // Valley lava pools — when loud AND LFO is swirling
        val lfoIntensity = abs(lfoValue)
        val valleyLavaStrength = (smoothedEruption * lfoIntensity * 2f).coerceIn(0f, 1f)
        if (valleyLavaStrength > 0.15f) {
            for (valley in valleyPoints) {
                if (lavaParticles.size >= maxLavaParticles) break
                if (Random.nextFloat() < valleyLavaStrength * 0.012f * dt * 60f) {
                    emitValleyLava(voiceLevels, valley, valleyLavaStrength)
                }
            }
        }

        // Smoke from main peak
        if (smokeParticles.size < maxSmokeParticles) {
            val smokeRate = 0.4f + smoothedEruption * 3f
            if (Random.nextFloat() < smokeRate * dt) {
                emitSmokeParticle()
            }
        }

        // Sparks arc and fall; one that lands while the flow has room becomes part of it.
        val ejectaIter = ejecta.iterator()
        while (ejectaIter.hasNext()) {
            val p = ejectaIter.next()
            p.life -= p.decay * dt
            if (p.life <= 0f) { ejectaIter.remove(); continue }
            p.vy += 0.33f * dt
            p.vx += lfoValue * 0.03f * dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.size *= (1f - 0.12f * dt)
            if (p.vy > 0f && p.y > ground(p.x)) {
                ejectaIter.remove()
                if (Random.nextFloat() < 0.35f && makeRoom(1)) {
                    val side = p.x - nearestVent(p.x)
                    lavaParticles.add(p.copy(
                        y = ground(p.x), vx = 0f, vy = 0f, size = p.size * 1.6f,
                        life = p.life.coerceAtLeast(0.7f), decay = 0.08f + Random.nextFloat() * 0.05f,
                        sliding = true, drift = (if (side < 0f) -1f else 1f) * (0.3f + Random.nextFloat() * 0.7f),
                    ))
                }
            }
        }

        // Lava bulges up over the vent, slumps back onto the rim, then runs down the slope under it,
        // slowing as it cools until it sticks. Moving lava keeps its heat; pooled lava crusts over,
        // and all of it cools faster when the music is quiet, so loudness refills the mountain.
        val quietCooling = 1f + 2f * (1f - smoothedEruption)
        val lavaIter = lavaParticles.iterator()
        while (lavaIter.hasNext()) {
            val p = lavaIter.next()
            p.life -= p.decay * quietCooling * (if (p.pooled) 2f else 0.5f) * dt
            if (p.life <= 0f || p.x < -0.05f || p.x > 1.05f) { lavaIter.remove(); continue }
            if (!p.sliding) {
                p.vy += 0.12f * dt
                p.vx += lfoValue * 0.01f * dt
                p.x += p.vx * dt
                p.y += p.vy * dt
                if (p.vy > 0f && p.y > ground(p.x)) {
                    p.sliding = true
                    p.y = ground(p.x)
                    val side = p.x - nearestVent(p.x)
                    p.drift = (if (side < 0f) -1f else 1f) * (0.3f + Random.nextFloat() * 0.7f)
                }
            } else if (!p.pooled) {
                // Hot lava runs, cooling lava crawls (speed goes as life^4), so a tongue's hot front
                // runs off and its cooler tail necks and pinches off behind it.
                val hot = p.life * p.life
                val run = 0.008f + 0.09f * hot * hot
                // Along the slope of the ridge above it, and sinking down the face, so flows run down
                // the mountain's front and string into the valleys.
                val slope = (ground(p.x + 0.004f) - ground(p.x - 0.004f)) / 0.008f
                val norm = 1f / sqrt(1f + slope * slope)
                val targetVx = run * ((if (slope >= 0f) 1f else -1f) * norm + p.drift * 0.15f)
                val targetVy = run * (abs(slope) * norm + 0.4f)
                val ease = (2f * dt).coerceAtMost(1f)
                p.vx += (targetVx + lfoValue * 0.001f - p.vx) * ease
                p.vy += (targetVy - p.vy) * ease
                p.x += p.vx * dt
                p.y = maxOf(p.y + p.vy * dt, ground(p.x))
                // It pools at the foot, or where it has cooled to a crawl on level ground.
                if (p.y > lineAt(foregroundLine, p.x) - p.size * 0.5f || (run < 0.012f && abs(slope) < 0.2f)) {
                    p.pooled = true
                    p.vx = 0f
                    p.vy = 0f
                }
            }
            p.size *= (1f - 0.02f * dt)
        }

        // Update smoke particles
        val smokeIter = smokeParticles.iterator()
        while (smokeIter.hasNext()) {
            val p = smokeIter.next()
            p.life -= p.decay * dt
            if (p.life <= 0f) { smokeIter.remove(); continue }
            p.vy -= 0.01f * dt
            p.vx += sin(animationTime * 2f + p.id) * 0.005f * dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.alpha = (p.life * 0.3f).coerceIn(0f, 0.3f)
            p.size += 0.01f * dt
        }
    }

    /**
     * A tongue of lava from [peak]: a train of blobs one radius apart down one side of it, the front
     * hottest so it runs ahead, plus one bulging up over the vent to slump onto the rim.
     */
    private fun emitTongue(voiceLevels: FloatArray, peak: EruptionPoint, strength: Float) {
        val n = 3 + Random.nextInt(2)
        if (!makeRoom(n + 1)) return
        val color = pickLavaColor(voiceLevels)
        val size = 0.012f + Random.nextFloat() * 0.014f
        val side = if (Random.nextBoolean()) 1f else -1f
        for (j in 0 until n) {
            val x = peak.x + side * size * sizeToX * (j + 0.5f)
            lavaParticles.add(LavaParticle(
                id = nextLavaId++,
                x = x,
                y = ground(x) + size * 0.3f,
                vx = 0f,
                vy = 0f,
                color = color,
                size = size,
                life = 1f - 0.1f * (n - 1 - j),
                decay = 0.07f + Random.nextFloat() * 0.05f,  // 8 to 14 s at full heat
                sliding = true,
                drift = side * (0.3f + Random.nextFloat() * 0.7f),
            ))
        }
        lavaParticles.add(LavaParticle(
            id = nextLavaId++,
            x = peak.x + (Random.nextFloat() - 0.5f) * 0.03f,
            y = peak.y - 0.005f,
            vx = (Random.nextFloat() - 0.5f) * 0.03f,
            vy = -(0.005f + strength * 0.025f + Random.nextFloat() * 0.015f),
            color = color,
            size = size,
            life = 1f,
            decay = 0.07f + Random.nextFloat() * 0.05f,
        ))
    }

    /** Frees room for [n] more blobs by dropping the coolest pooled ones; false if there aren't enough. */
    private fun makeRoom(n: Int): Boolean {
        while (lavaParticles.size + n > maxLavaParticles) {
            var coolest = -1
            for (i in lavaParticles.indices) {
                val p = lavaParticles[i]
                if (p.pooled && (coolest < 0 || p.life < lavaParticles[coolest].life)) coolest = i
            }
            if (coolest < 0) return false
            lavaParticles.removeAt(coolest)
        }
        return true
    }

    /** A vent's level: its band of the spectrum (bass, mids, highs), or the master level without one. */
    private fun ventLevel(vent: Int, bands: FloatArray, masterLevel: Float): Float {
        if (bands.size < VENT_BANDS[3]) return masterLevel
        val from = VENT_BANDS[vent]
        val to = VENT_BANDS[vent + 1]
        var sum = 0f
        for (i in from until to) sum += bands[i]
        return sum / (to - from)
    }

    /** [x] rounded up or down at random, in proportion, so a per-frame rate holds at any frame rate. */
    private fun stochasticCount(x: Float): Int {
        val n = x.toInt()
        return n + if (Random.nextFloat() < x - n) 1 else 0
    }

    /**
     * A spark thrown from a vent: the original fountain's size, speed and fire colours. A [hit]
     * throws it taller and in a narrower column.
     */
    private fun emitEjecta(voiceLevels: FloatArray, emitX: Float, emitY: Float, strength: Float, hit: Float) {
        val spread = (0.04f + strength * 0.03f) * (1f - 0.6f * hit)
        ejecta.add(LavaParticle(
            id = nextLavaId++,
            x = emitX + (Random.nextFloat() - 0.5f) * spread,
            y = emitY - 0.01f,
            vx = (Random.nextFloat() - 0.5f) * 0.12f * (1f - 0.6f * hit),
            vy = -(0.12f + strength * 0.30f + Random.nextFloat() * 0.08f) * (1f + 0.8f * hit),
            color = pickLavaColor(voiceLevels),
            size = 0.006f + Random.nextFloat() * 0.01f,
            life = 1f,
            decay = 0.18f + Random.nextFloat() * 0.15f,
        ))
    }

    private fun emitValleyLava(
        voiceLevels: FloatArray, valley: ValleyPoint, strength: Float
    ) {
        // Wells up in place and stays, a big slow pool.
        lavaParticles.add(LavaParticle(
            id = nextLavaId++,
            x = valley.x + (Random.nextFloat() - 0.5f) * valley.width,
            y = valley.y + 0.01f,
            vx = (Random.nextFloat() - 0.5f) * 0.02f,
            vy = 0f,
            color = pickLavaColor(voiceLevels),
            size = 0.018f + Random.nextFloat() * 0.014f * strength,
            life = 1f,
            decay = 0.1f + Random.nextFloat() * 0.1f,
            sliding = true,
            pooled = true,
        ))
    }

    private fun pickLavaColor(voiceLevels: FloatArray): Color {
        var maxVoice = 0
        var maxLevel = 0f
        for (i in voiceLevels.indices step 2) {
            val pairLevel = voiceLevels[i] + voiceLevels.getOrElse(i + 1) { 0f }
            if (pairLevel > maxLevel) {
                maxLevel = pairLevel
                maxVoice = i / 2
            }
        }
        val baseColor = voicePairColors[maxVoice.coerceIn(0, 3)]
        val t = Random.nextFloat()
        return if (t < 0.35f) OrpheusColors.hoodLavaYellow
               else if (t < 0.65f) OrpheusColors.hoodLavaOrange
               else baseColor
    }

    private fun emitSmokeParticle() {
        // Smoke from a random eruption point
        val peak = eruptionPoints[Random.nextInt(eruptionPoints.size)]
        smokeParticles.add(SmokeParticle(
            id = nextSmokeId++,
            x = peak.x + (Random.nextFloat() - 0.5f) * 0.04f,
            y = peak.y - 0.02f,
            vx = (Random.nextFloat() - 0.5f) * 0.01f,
            vy = -(0.02f + Random.nextFloat() * 0.02f),
            alpha = 0.2f,
            size = 0.01f + Random.nextFloat() * 0.01f,
            life = 1f,
            decay = 0.15f + Random.nextFloat() * 0.1f,
        ))
    }

    // -- Drawing: Sky & Stars --

    private val starPositions: List<Pair<Float, Float>> = buildList {
        val rng = Random(42)
        for (i in 0 until 40) {
            add(rng.nextFloat() to rng.nextFloat() * 0.5f)
        }
    }

    private fun DrawScope.drawSky(sunsetT: Float, lfoMod: Float, time: Float) {
        val topColor = lerpColor(OrpheusColors.hoodSkyTop, OrpheusColors.hoodTwilightTop, sunsetT)
        val midColor = lerpColor(OrpheusColors.hoodSkyMid, OrpheusColors.hoodTwilightMid, sunsetT)
        val horizonColor = lerpColor(OrpheusColors.hoodHorizon, OrpheusColors.hoodTwilightHorizon, sunsetT)

        skyStops[0] = 0f to topColor
        skyStops[1] = 0.5f to midColor
        skyStops[2] = 0.85f to horizonColor
        skyStops[3] = 1f to horizonColor
        val skyBrush = Brush.verticalGradient(*skyStops)
        drawRect(brush = skyBrush, size = size)

        val starAlpha = ((sunsetT - 0.5f) * 2f).coerceIn(0f, 1f)
        if (starAlpha > 0.01f) {
            for ((sx, sy) in starPositions) {
                val twinkle = (sin(time * (2f + sx * 3f) + sx * 100f) * 0.3f + 0.7f)
                val alpha = starAlpha * twinkle * (0.5f + sx * 0.5f)
                drawCircle(
                    color = Color.White.copy(alpha = alpha.coerceIn(0f, 1f)),
                    radius = 1f + sx * 1.5f,
                    center = Offset(sx * size.width, sy * size.height),
                )
            }
        }

        val brightStarAlpha = 0.6f + sunsetT * 0.4f
        drawCircle(
            color = Color.White.copy(alpha = brightStarAlpha),
            radius = 2.5f,
            center = Offset(peakX * size.width + size.width * 0.06f, peakY * size.height - size.height * 0.12f),
        )
    }

    private fun DrawScope.drawSunGlow(sunsetT: Float, eruptionLevel: Float) {
        val sunY = peakY + 0.05f + sunsetT * 0.15f
        val sunAlpha = (1f - sunsetT * 0.8f).coerceIn(0.1f, 0.8f)
        val glowRadius = size.height * (0.25f + eruptionLevel * 0.05f)

        val sunCenter = Offset(peakX * size.width, sunY * size.height)
        sunGlowStops[0] = 0f to OrpheusColors.hoodLavaYellow.copy(alpha = sunAlpha * 0.3f)
        sunGlowStops[1] = 0.3f to OrpheusColors.hoodHorizon.copy(alpha = sunAlpha * 0.5f)
        sunGlowStops[2] = 1f to Color.Transparent
        val glowBrush = Brush.radialGradient(
            colorStops = sunGlowStops,
            center = sunCenter,
            radius = glowRadius,
        )
        drawRect(brush = glowBrush, size = size)
    }

    // -- Drawing: Mountain Silhouettes --

    /**
     * The silhouettes and rim depend only on size, so they are built when it changes. A path rebuilt
     * every frame gets a new id, and Skia re-rasterizes or re-triangulates it each time: about a
     * screen of CPU masks per frame on a Fold.
     */
    private fun DrawScope.buildSilhouettes() {
        if (size == silhouetteSize) return
        silhouetteSize = size
        val w = size.width
        val h = size.height
        val px = peakX * w
        val py = peakY * h

        backRidgePath.reset()
        for (i in backRidgeLine.indices step 2) {
            if (i == 0) backRidgePath.moveTo(0f, backRidgeLine[1] * h) else backRidgePath.lineTo(backRidgeLine[i] * w, backRidgeLine[i + 1] * h)
        }
        backRidgePath.lineTo(w, h)
        backRidgePath.lineTo(0f, h)
        backRidgePath.close()

        hoodPath.reset()
        for (i in hoodLine.indices step 2) {
            if (i == 0) hoodPath.moveTo(0f, hoodLine[1] * h) else hoodPath.lineTo(hoodLine[i] * w, hoodLine[i + 1] * h)
        }
        hoodPath.lineTo(w, h)
        hoodPath.lineTo(0f, h)
        hoodPath.close()

        rimPath.reset()
        rimPath.moveTo(w * 0.25f, h * 0.55f)
        rimPath.lineTo(w * 0.38f, h * 0.44f)
        rimPath.lineTo(w * 0.44f, h * 0.40f)
        rimPath.lineTo(px, py)
        rimPath.lineTo(w * 0.54f, h * 0.41f)
        rimPath.lineTo(w * 0.58f, h * 0.43f)
        rimPath.lineTo(w * 0.60f, h * 0.42f)
        rimPath.lineTo(w * 0.65f, h * 0.46f)
        rimPath.lineTo(w * 0.70f, h * 0.50f)
        rimPath.lineTo(w * 0.78f, h * 0.55f)

        foregroundPath.reset()
        for (i in foregroundLine.indices step 2) {
            if (i == 0) foregroundPath.moveTo(0f, foregroundLine[1] * h) else foregroundPath.lineTo(foregroundLine[i] * w, foregroundLine[i + 1] * h)
        }
        foregroundPath.lineTo(w, h)
        foregroundPath.lineTo(0f, h)
        foregroundPath.close()
    }

    private fun DrawScope.drawBackRidge() {
        drawPath(path = backRidgePath, color = OrpheusColors.hoodBackRidge)
    }

    private fun DrawScope.drawMtHood(eruptionLevel: Float) {
        drawPath(path = hoodPath, color = OrpheusColors.hoodMountain)

        val rimAlpha = (0.2f + eruptionLevel * 0.5f).coerceAtMost(0.7f)
        drawPath(
            path = rimPath,
            color = OrpheusColors.hoodRimGlow.copy(alpha = rimAlpha),
            style = Stroke(width = 2f + eruptionLevel * 2f),
        )
    }

    private fun DrawScope.drawForegroundRidge() {
        drawPath(path = foregroundPath, color = OrpheusColors.hoodForeground)
    }

    // -- Drawing: Lava & Smoke --

    private fun DrawScope.drawLavaGlow(eruptionLevel: Float) {
        // Glow at all three eruption points
        for (peak in eruptionPoints) {
            val peakGlow = eruptionLevel * peak.strength
            val glowAlpha = (0.15f + peakGlow * 0.6f).coerceAtMost(0.8f)
            val glowRadius = size.height * (0.06f + peakGlow * 0.14f)
            val center = Offset(peak.x * size.width, peak.y * size.height)

            lavaGlowStops[0] = 0f to OrpheusColors.hoodLavaYellow.copy(alpha = glowAlpha)
            lavaGlowStops[1] = 0.4f to OrpheusColors.hoodLavaOrange.copy(alpha = glowAlpha * 0.6f)
            lavaGlowStops[2] = 0.7f to OrpheusColors.hoodLavaRed.copy(alpha = glowAlpha * 0.3f)
            lavaGlowStops[3] = 1f to Color.Transparent
            val brush = Brush.radialGradient(
                colorStops = lavaGlowStops,
                center = center,
                radius = glowRadius,
            )
            drawRect(brush = brush, size = size, blendMode = BlendMode.Plus)
        }
    }

    private fun DrawScope.drawValleyLava(eruptionLevel: Float, lfoMod: Float, time: Float) {
        // Glowing lava pools in valleys — visible when loud + LFO active
        val lfoIntensity = abs(lfoMod)
        val valleyStrength = (eruptionLevel * lfoIntensity * 2.5f).coerceIn(0f, 1f)
        if (valleyStrength < 0.1f) return

        val w = size.width
        val h = size.height
        val poolAlpha = valleyStrength * 0.6f

        for (valley in valleyPoints) {
            val cx = valley.x * w
            val cy = valley.y * h
            val poolW = valley.width * w * (0.8f + valleyStrength * 0.4f)
            val poolH = h * 0.03f * (0.5f + valleyStrength)
            val wobble = sin(time * 1.8f + valley.x * 10f) * w * 0.005f

            // Lava pool glow — wide elliptical radial gradient
            valleyGlowStops[0] = 0f to OrpheusColors.hoodLavaYellow.copy(alpha = poolAlpha * 0.8f)
            valleyGlowStops[1] = 0.3f to OrpheusColors.hoodLavaOrange.copy(alpha = poolAlpha * 0.6f)
            valleyGlowStops[2] = 0.6f to OrpheusColors.hoodLavaRed.copy(alpha = poolAlpha * 0.3f)
            valleyGlowStops[3] = 1f to Color.Transparent
            val poolBrush = Brush.radialGradient(
                colorStops = valleyGlowStops,
                center = Offset(cx + wobble, cy),
                radius = poolW * 0.6f,
            )
            drawRect(brush = poolBrush, size = size, blendMode = BlendMode.Plus)
        }
    }

    private fun DrawScope.drawLavaFlows(eruptionLevel: Float, time: Float) {
        if (eruptionLevel < 0.2f) return

        val w = size.width
        val h = size.height
        val flowAlpha = ((eruptionLevel - 0.2f) / 0.8f).coerceIn(0f, 0.9f)
        val flowWidth = 4f + eruptionLevel * 10f

        // Main peak — left flow down into valley
        flowPath.reset()
        flowPath.moveTo(peakX * w, peakY * h)
        flowPath.quadraticTo(
            w * 0.42f, h * 0.50f,
            w * 0.36f + sin(time * 1.5f) * w * 0.01f, h * 0.58f,
        )
        drawLavaFlowPath(flowAlpha, flowWidth)

        // Main peak — right flow
        flowPath.reset()
        flowPath.moveTo(peakX * w, peakY * h)
        flowPath.quadraticTo(
            w * 0.56f, h * 0.48f,
            w * 0.62f + sin(time * 1.2f + 2f) * w * 0.01f, h * 0.56f,
        )
        drawLavaFlowPath(flowAlpha * 0.8f, flowWidth * 0.8f)

        // Far-left ridge peak — flow down right side toward center
        if (eruptionLevel > 0.3f) {
            flowPath.reset()
            flowPath.moveTo(w * 0.10f, h * 0.50f)
            flowPath.quadraticTo(
                w * 0.18f, h * 0.53f,
                w * 0.25f + sin(time * 1.3f + 1f) * w * 0.008f, h * 0.56f,
            )
            drawLavaFlowPath(flowAlpha * 0.6f, flowWidth * 0.6f)
        }

        // Far-right ridge peak — flow down left side toward center
        if (eruptionLevel > 0.35f) {
            flowPath.reset()
            flowPath.moveTo(w * 0.90f, h * 0.48f)
            flowPath.quadraticTo(
                w * 0.82f, h * 0.51f,
                w * 0.74f + sin(time * 1.1f + 3f) * w * 0.008f, h * 0.54f,
            )
            drawLavaFlowPath(flowAlpha * 0.5f, flowWidth * 0.6f)
        }
    }

    /** Draws the current flowPath with glow. Reuses the shared flowPath field. */
    private fun DrawScope.drawLavaFlowPath(alpha: Float, width: Float) {
        drawPath(
            path = flowPath,
            color = OrpheusColors.hoodLavaOrange.copy(alpha = alpha),
            style = Stroke(width = width),
        )
        drawPath(
            path = flowPath,
            color = OrpheusColors.hoodLavaRed.copy(alpha = alpha * 0.4f),
            style = Stroke(width = width * 3f),
            blendMode = BlendMode.Plus,
        )
    }

    /** Fiery dots with an additive glow and a short streak behind, [scale] times their size. */
    private fun DrawScope.drawLavaParticles(particles: List<LavaParticle>, scale: Float = 1f) {
        for (p in particles) {
            val alpha = (p.life * p.life).coerceIn(0f, 1f)
            val color = lavaColor(p, p.life)
            val px = p.x * size.width
            val py = p.y * size.height
            val radius = p.size * size.minDimension * scale

            // Where it was 50 ms ago, so a thrown spark reads as a streak of fire.
            drawLine(
                color = color.copy(alpha = alpha * 0.5f),
                start = Offset(px, py),
                end = Offset(px - p.vx * size.width * 0.05f, py - p.vy * size.height * 0.05f),
                strokeWidth = radius * 1.4f,
                cap = StrokeCap.Round,
                blendMode = BlendMode.Plus,
            )
            drawCircle(
                color = color.copy(alpha = alpha * 0.3f),
                radius = radius * 3f,
                center = Offset(px, py),
                blendMode = BlendMode.Plus,
            )
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = radius,
                center = Offset(px, py),
            )
        }
    }

    private val blobUniforms = FloatArray(MtHoodLavaSksl.MAX_BLOBS * 4)
    private val tintUniforms = FloatArray(MtHoodLavaSksl.MAX_BLOBS * 4)
    private val axisUniforms = FloatArray(MtHoodLavaSksl.MAX_BLOBS * 2)

    /**
     * The lava as one molten surface: each blob shrinks as it cools, the shader melts them together,
     * and a dome over each vent swells with its eruption so the lava wells up into every stream.
     */
    private fun DrawScope.drawLavaLamp(shader: PlasmaShader, particles: List<LavaParticle>, eruptionLevel: Float) {
        var count = 0
        for (p in particles) {
            if (count >= MtHoodLavaSksl.MAX_BLOBS) break
            val px = p.vx * size.width
            val py = p.vy * size.height
            val speed = sqrt(p.vx * p.vx + p.vy * p.vy)
            // Moving lava stays hot; lava that has stopped cools toward a crust.
            val heat = p.life * (0.4f + 0.6f * if (p.sliding) min(1f, speed / LAVA_RUN_MAX) else 1f)
            val radius = p.size * size.minDimension * (0.5f + 0.5f * min(1f, p.life * 1.6f))
            if (p.pooled) {
                // A pool flattens into a lake, spreading wider as it cools.
                setBlob(count, p.x * size.width, p.y * size.height, radius, min(3.2f, 2.2f + (1f - p.life) * 1.5f), 0f, lavaColor(p, heat))
            } else {
                // Stretched along its flow and thinned to match, so a running flow is a narrow
                // rivulet and a stuck one stays lumpy.
                val stretch = 1.3f + min(1.2f, sqrt(px * px + py * py) / (p.size * size.minDimension) * 0.6f)
                val angle = if (px == 0f && py == 0f) 1.5708f else atan2(py, px)
                val thin = radius * sqrt(1.3f / stretch)
                setBlob(count, p.x * size.width, p.y * size.height, thin, stretch, angle, lavaColor(p, heat))
            }
            count++
        }
        for (peak in eruptionPoints) {
            val e = eruptionLevel * peak.strength
            if (e < 0.05f || count >= MtHoodLavaSksl.MAX_BLOBS) continue
            val radius = size.minDimension * (0.005f + 0.015f * e)
            setBlob(count, peak.x * size.width, peak.y * size.height + radius * 0.3f, radius, 1.5f, 0f, fireRamp(0.9f))
            count++
        }
        shader.uniform("resolution", size.width, size.height)
        shader.uniform("count", count.toFloat())
        shader.uniform("blobs", blobUniforms)
        shader.uniform("tints", tintUniforms)
        shader.uniform("axes", axisUniforms)
        shader.brush()?.let { drawRect(it) }
    }

    private fun setBlob(i: Int, x: Float, y: Float, radius: Float, stretch: Float, angle: Float, color: Color) {
        val k = i * 4
        blobUniforms[k] = x
        blobUniforms[k + 1] = y
        blobUniforms[k + 2] = radius
        blobUniforms[k + 3] = stretch
        tintUniforms[k] = color.red
        tintUniforms[k + 1] = color.green
        tintUniforms[k + 2] = color.blue
        axisUniforms[i * 2] = cos(angle)
        axisUniforms[i * 2 + 1] = sin(angle)
    }

    /** Lava at [heat]: fresh runs yellow-white, cooling through orange and red to a dark crust, tinted by its voice. */
    private fun lavaColor(p: LavaParticle, heat: Float): Color = lerp(fireRamp(heat), p.color, 0.12f * heat.coerceIn(0f, 1f))

    private val fireStops = arrayOf(
        Color(0xFF3A0804), Color(0xFFB0180A), Color(0xFFFF4A0C), Color(0xFFFF9A1C), Color(0xFFFFD45A),
    )

    /** Lava's colour at [heat] 0 (crust) to 1 (fresh). */
    private fun fireRamp(heat: Float): Color {
        val t = heat.coerceIn(0f, 1f) * (fireStops.size - 1)
        val i = t.toInt().coerceAtMost(fireStops.size - 2)
        return lerp(fireStops[i], fireStops[i + 1], t - i)
    }

    /** The vent whose peak is nearest [x]: lava creeps away from it. */
    private fun nearestVent(x: Float): Float = eruptionPoints.minBy { abs(it.x - x) }.x

    /** The ground's height (normalized, y down) at [x]: the highest of the three ridges. */
    private fun ground(x: Float): Float =
        min(lineAt(backRidgeLine, x), min(lineAt(hoodLine, x), lineAt(foregroundLine, x)))

    private fun lineAt(line: FloatArray, x: Float): Float {
        for (i in 2 until line.size step 2) {
            if (x <= line[i]) {
                val x0 = line[i - 2]
                val y0 = line[i - 1]
                return y0 + (line[i + 1] - y0) * (x - x0) / (line[i] - x0)
            }
        }
        return line[line.size - 1]
    }

    private fun DrawScope.drawSmokeParticles(particles: List<SmokeParticle>) {
        for (p in particles) {
            val px = p.x * size.width
            val py = p.y * size.height
            val radius = p.size * size.minDimension
            drawCircle(
                color = OrpheusColors.hoodSmoke.copy(alpha = p.alpha),
                radius = radius,
                center = Offset(px, py),
            )
        }
    }

    // -- Utility --

    private fun lerpColor(a: Color, b: Color, t: Float): Color =
        lerp(a, b, t.coerceIn(0f, 1f))

    companion object {
        /** The fastest a fresh blob runs, normalized per second: full heat while moving at it. */
        private const val LAVA_RUN_MAX = 0.098f

        /** Spectrum bands each vent listens to: bass for the main peak, then mids, then highs. */
        private val VENT_BANDS = intArrayOf(0, 8, 22, 40)

        val Default = VisualizationLiquidEffects(
            frostSmall = 1f,
            frostMedium = 2f,
            frostLarge = 3f,
            tintAlpha = 0.1f,
            top = VisualizationLiquidScope(
                saturation = 2.0f,
                dispersion = .4f,
                curve = .01f,
                refraction = 0.4f,
            ),
            bottom = VisualizationLiquidScope(
                saturation = 1.5f,
                dispersion = .1f,
            ),
            title = CenterPanelStyle(
                scope = VisualizationLiquidScope(
                    saturation = 5f,
                    dispersion = 2f,
                    curve = .4f,
                    refraction = 1.2f,  // Maximum refraction for shiny title
                    contrast = .6f,
                ),
                titleColor = OrpheusColors.neonOrange,
                borderColor = OrpheusColors.neonOrange.copy(alpha = 0.3f),
                borderWidth = 2.dp,
                titleElevation = 16.dp,
            ),
        )
    }
}
