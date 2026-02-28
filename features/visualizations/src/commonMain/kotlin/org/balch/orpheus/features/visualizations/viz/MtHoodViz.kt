package org.balch.orpheus.features.visualizations.viz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.ui.infrastructure.CenterPanelStyle
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidScope
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.Visualization
import kotlin.math.abs
import kotlin.math.sin
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
               smokeParticles == other.smokeParticles &&
               masterEnergy == other.masterEnergy &&
               lfoModulation == other.lfoModulation &&
               voiceLevels.contentEquals(other.voiceLevels) &&
               eruptionLevel == other.eruptionLevel &&
               time == other.time
    }
    override fun hashCode(): Int {
        var result = lavaParticles.hashCode()
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
@ContributesIntoSet(AppScope::class, binding = binding<Visualization>())
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
    private val smokeParticles = mutableListOf<SmokeParticle>()
    private var nextLavaId = 0
    private var nextSmokeId = 0
    private var active = false
    private var animationTime = 0f
    private var smoothedEruption = 0f

    private val maxLavaParticles = 200  // More lava for 3 peaks + valley pools
    private val maxSmokeParticles = 20

    // Pre-allocated Path objects to avoid per-frame heap allocations
    private val backRidgePath = Path()
    private val hoodPath = Path()
    private val rimPath = Path()
    private val foregroundPath = Path()
    private val flowPath = Path()  // Reused for each lava flow segment

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

    // Valley positions — between the peaks, lava pools here when loud
    private data class ValleyPoint(val x: Float, val y: Float, val width: Float)
    private val valleyPoints = listOf(
        ValleyPoint(0.30f, 0.55f, 0.14f),  // Valley between far-left ridge and Hood
        ValleyPoint(0.68f, 0.52f, 0.12f),  // Valley between Hood and far-right ridge
        ValleyPoint(0.50f, 0.53f, 0.08f),  // Saddle just right of Hood base
    )

    override fun onActivate() {
        active = true
        animationTime = 0f
        smoothedEruption = 0f
        lavaParticles.clear()
        smokeParticles.clear()
        nextLavaId = 0
        nextSmokeId = 0
    }

    override fun onDeactivate() {
        active = false
        lavaParticles.clear()
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

                    updateSimulation(voiceLevels, masterLevel, lfoValue, dt)

                    _uiState.value = MtHoodUiState(
                        lavaParticles = lavaParticles.toList(),
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

        val state = _uiState.value
        val sunsetT = _sunsetKnob

        Canvas(modifier = modifier.fillMaxSize()) {
            drawSky(sunsetT, state.lfoModulation, state.time)
            drawSunGlow(sunsetT, state.eruptionLevel)
            drawBackRidge()
            drawValleyLava(state.eruptionLevel, state.lfoModulation, state.time)
            drawMtHood(state.eruptionLevel)
            drawLavaGlow(state.eruptionLevel)
            drawLavaFlows(state.eruptionLevel, state.time)
            drawLavaParticles(state.lavaParticles)
            drawSmokeParticles(state.smokeParticles)
            drawForegroundRidge()
        }
    }

    // -- Simulation --

    private fun updateSimulation(
        voiceLevels: FloatArray, masterLevel: Float, lfoValue: Float, dt: Float
    ) {
        val targetEruption = (masterLevel * heatMultiplier).coerceIn(0f, 1f)
        smoothedEruption += (targetEruption - smoothedEruption) * (3f * dt).coerceAtMost(1f)

        // Emit lava from all three peaks
        if (smoothedEruption > 0.05f && lavaParticles.size < maxLavaParticles) {
            for (peak in eruptionPoints) {
                val peakEruption = smoothedEruption * peak.strength
                if (peakEruption < 0.05f) continue
                val emitCount = (peakEruption * 5f * dt * 60f).toInt().coerceIn(0, 4)
                for (i in 0 until emitCount) {
                    emitLavaParticle(voiceLevels, lfoValue, peak.x, peak.y, peakEruption)
                }
            }
        }

        // Valley lava pools — when loud AND LFO is swirling
        val lfoIntensity = abs(lfoValue)
        val valleyLavaStrength = (smoothedEruption * lfoIntensity * 2f).coerceIn(0f, 1f)
        if (valleyLavaStrength > 0.15f && lavaParticles.size < maxLavaParticles) {
            for (valley in valleyPoints) {
                val emitCount = (valleyLavaStrength * 3f * dt * 60f).toInt().coerceIn(0, 3)
                for (i in 0 until emitCount) {
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

        // Update lava particles — heavier, longer lasting
        val lavaIter = lavaParticles.iterator()
        while (lavaIter.hasNext()) {
            val p = lavaIter.next()
            p.life -= p.decay * dt
            if (p.life <= 0f) { lavaIter.remove(); continue }
            p.vy += 0.33f * dt  // Slightly reduced gravity — heavy but with pull
            p.vx += lfoValue * 0.03f * dt  // Stronger LFO drift
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.size *= (1f - 0.12f * dt)  // Shrinks much slower — stays chunky
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

    private fun emitLavaParticle(
        voiceLevels: FloatArray, lfoValue: Float,
        emitX: Float, emitY: Float, strength: Float
    ) {
        val color = pickLavaColor(voiceLevels)
        val spread = 0.04f + strength * 0.03f
        val upVelocity = -(0.12f + strength * 0.30f + Random.nextFloat() * 0.08f)

        lavaParticles.add(LavaParticle(
            id = nextLavaId++,
            x = emitX + (Random.nextFloat() - 0.5f) * spread,
            y = emitY - 0.01f,
            vx = (Random.nextFloat() - 0.5f) * 0.06f,
            vy = upVelocity,
            color = color,
            size = 0.008f + Random.nextFloat() * 0.012f,  // Bigger particles
            life = 1f,
            decay = 0.18f + Random.nextFloat() * 0.15f,   // Much slower decay — lasts ~4-6s
        ))
    }

    private fun emitValleyLava(
        voiceLevels: FloatArray, valley: ValleyPoint, strength: Float
    ) {
        val color = pickLavaColor(voiceLevels)
        // Valley lava pools: low velocity, spreads horizontally, long-lived
        lavaParticles.add(LavaParticle(
            id = nextLavaId++,
            x = valley.x + (Random.nextFloat() - 0.5f) * valley.width,
            y = valley.y + (Random.nextFloat() - 0.5f) * 0.03f,
            vx = (Random.nextFloat() - 0.5f) * 0.02f,  // Slow horizontal spread
            vy = -0.01f + Random.nextFloat() * 0.02f,   // Nearly stationary
            color = color,
            size = 0.010f + Random.nextFloat() * 0.015f * strength,  // Big glowing pools
            life = 1f,
            decay = 0.12f + Random.nextFloat() * 0.10f,  // Very long lived
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

    private fun DrawScope.drawBackRidge() {
        val w = size.width
        val h = size.height
        backRidgePath.reset()
        backRidgePath.moveTo(0f, h * 0.55f)
        backRidgePath.lineTo(w * 0.1f, h * 0.50f)
        backRidgePath.lineTo(w * 0.25f, h * 0.52f)
        backRidgePath.lineTo(w * 0.35f, h * 0.48f)
        backRidgePath.lineTo(w * 0.5f, h * 0.53f)
        backRidgePath.lineTo(w * 0.65f, h * 0.46f)
        backRidgePath.lineTo(w * 0.8f, h * 0.50f)
        backRidgePath.lineTo(w * 0.9f, h * 0.48f)
        backRidgePath.lineTo(w, h * 0.52f)
        backRidgePath.lineTo(w, h)
        backRidgePath.lineTo(0f, h)
        backRidgePath.close()
        drawPath(path = backRidgePath, color = OrpheusColors.hoodBackRidge)
    }

    private fun DrawScope.drawMtHood(eruptionLevel: Float) {
        val w = size.width
        val h = size.height
        val px = peakX * w
        val py = peakY * h

        hoodPath.reset()
        hoodPath.moveTo(0f, h * 0.70f)
        hoodPath.lineTo(w * 0.15f, h * 0.62f)
        hoodPath.lineTo(w * 0.25f, h * 0.55f)
        hoodPath.lineTo(w * 0.38f, h * 0.44f)
        hoodPath.lineTo(w * 0.44f, h * 0.40f)
        hoodPath.lineTo(px - w * 0.01f, py + h * 0.005f)
        hoodPath.lineTo(px, py)
        hoodPath.lineTo(px + w * 0.015f, py + h * 0.003f)
        hoodPath.lineTo(w * 0.54f, h * 0.41f)
        hoodPath.lineTo(w * 0.58f, h * 0.43f)
        hoodPath.lineTo(w * 0.60f, h * 0.42f)
        hoodPath.lineTo(w * 0.65f, h * 0.46f)
        hoodPath.lineTo(w * 0.70f, h * 0.50f)
        hoodPath.lineTo(w * 0.78f, h * 0.55f)
        hoodPath.lineTo(w * 0.88f, h * 0.62f)
        hoodPath.lineTo(w, h * 0.68f)
        hoodPath.lineTo(w, h)
        hoodPath.lineTo(0f, h)
        hoodPath.close()
        drawPath(path = hoodPath, color = OrpheusColors.hoodMountain)

        val rimAlpha = (0.2f + eruptionLevel * 0.5f).coerceAtMost(0.7f)
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
        drawPath(
            path = rimPath,
            color = OrpheusColors.hoodRimGlow.copy(alpha = rimAlpha),
            style = Stroke(width = 2f + eruptionLevel * 2f),
        )
    }

    private fun DrawScope.drawForegroundRidge() {
        val w = size.width
        val h = size.height
        foregroundPath.reset()
        foregroundPath.moveTo(0f, h * 0.82f)
        foregroundPath.lineTo(w * 0.1f, h * 0.80f)
        foregroundPath.lineTo(w * 0.3f, h * 0.83f)
        foregroundPath.lineTo(w * 0.5f, h * 0.79f)
        foregroundPath.lineTo(w * 0.7f, h * 0.82f)
        foregroundPath.lineTo(w * 0.85f, h * 0.80f)
        foregroundPath.lineTo(w, h * 0.83f)
        foregroundPath.lineTo(w, h)
        foregroundPath.lineTo(0f, h)
        foregroundPath.close()
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

    private fun DrawScope.drawLavaParticles(particles: List<LavaParticle>) {
        for (p in particles) {
            val alpha = (p.life * p.life).coerceIn(0f, 1f)
            val px = p.x * size.width
            val py = p.y * size.height
            val radius = p.size * size.height

            drawCircle(
                color = p.color.copy(alpha = alpha * 0.3f),
                radius = radius * 3f,
                center = Offset(px, py),
                blendMode = BlendMode.Plus,
            )
            drawCircle(
                color = p.color.copy(alpha = alpha),
                radius = radius,
                center = Offset(px, py),
            )
        }
    }

    private fun DrawScope.drawSmokeParticles(particles: List<SmokeParticle>) {
        for (p in particles) {
            val px = p.x * size.width
            val py = p.y * size.height
            val radius = p.size * size.height
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
        // Very see-through and shiny glass panels — high refraction, low tint, high saturation
        val Default = VisualizationLiquidEffects(
            frostSmall = 2f,     // Minimal frost for clarity
            frostMedium = 3f,
            frostLarge = 4f,
            tintAlpha = 0.05f,   // Very transparent panels
            top = VisualizationLiquidScope(
                saturation = 1.2f,
                dispersion = 1.0f,
                curve = .2f,
                refraction = 0.9f,   // High refraction for shiny glass look
            ),
            bottom = VisualizationLiquidScope(
                saturation = 1.0f,
                dispersion = .8f,
                curve = .2f,
                refraction = 0.8f,   // High refraction
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
