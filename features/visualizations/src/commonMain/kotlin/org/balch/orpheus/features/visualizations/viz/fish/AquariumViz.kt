package org.balch.orpheus.features.visualizations.viz.fish

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.ui.infrastructure.CenterPanelStyle
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidScope
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.Visualization
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class AquariumUiState(
    val fish: List<Fish> = emptyList(),
    val bubbles: List<Bubble> = emptyList(),
    val masterEnergy: Float = 0f,
    val lfoModulation: Float = 0f,
    val currentAngle: Float = 0f,
)

data class Bubble(
    val id: Int,
    var x: Float,
    var y: Float,
    var radius: Float,
    var alpha: Float = 0.3f,
    var wobblePhase: Float = 0f,
)

@Inject
@ContributesIntoSet(AppScope::class)
class AquariumViz(
    private val engine: SynthEngine,
) : Visualization {

    override val id = "aquarium"
    override val name = "Aquarium"
    override val color = OrpheusColors.aquariumTeal
    override val knob1Label = "FISH"
    override val knob2Label = "CURRENT"

    override val liquidEffects = Default

    private var _fishKnob = 0.35f
    private var _currentKnob = 0.3f

    override fun setKnob1(value: Float) {
        _fishKnob = value.coerceIn(0f, 1f)
    }

    override fun setKnob2(value: Float) {
        _currentKnob = value.coerceIn(0f, 1f)
    }

    private val fishColors = listOf(
        OrpheusColors.fishOrange,
        OrpheusColors.fishBlue,
        OrpheusColors.fishRed,
        OrpheusColors.fishGold,
        OrpheusColors.fishTeal,
        OrpheusColors.fishSilver,
        OrpheusColors.fishPurple,
        OrpheusColors.fishPink,
    )

    private val fish = mutableListOf<Fish>()
    private val bubbles = mutableListOf<Bubble>()
    private var nextFishId = 0
    private var nextBubbleId = 0
    private var active = false
    private var smoothedEnergy = 0f
    private var currentAngle = 0f

    private val _uiState = mutableStateOf(AquariumUiState(), neverEqualPolicy())

    // Pre-allocated paths to avoid per-frame heap allocations
    private val shaftPaths = Array(3) { Path() }
    private val bodyPath = Path()
    private val tailPath = Path()

    // Pre-allocated static gradient colors
    private val bgGradientColors = listOf(
        OrpheusColors.aquariumTeal.copy(alpha = 0.4f),
        OrpheusColors.aquariumDeep,
    )

    // Max fish from knob; actual count scales with music energy
    private val maxFishFromKnob: Int
        get() = (3 + (_fishKnob * 17f)).toInt().coerceIn(3, 20)

    private val targetFishCount: Int
        get() {
            // No fish when silent, ramp up with music
            // smoothedEnergy 0.0 → 0 fish, 0.05 → 1 fish, scales up to maxFishFromKnob
            val energyFactor = (smoothedEnergy * 8f).coerceIn(0f, 1f)
            return (maxFishFromKnob * energyFactor).toInt()
        }

    private val currentStrength: Float
        get() = 0.05f + _currentKnob * 0.25f  // always some current, knob adds more

    // Current swirl state — continuous rotation with organic variation
    private var currentPhase = 0f  // drives sinusoidal swirl
    private var swirlSpeed = 1.0f  // music-reactive swirl speed multiplier

    override fun onActivate() {
        active = true
        fish.clear()
        bubbles.clear()
        nextFishId = 0
        nextBubbleId = 0
        smoothedEnergy = 0f
        currentAngle = 0f
        currentPhase = 0f
        swirlSpeed = 1.0f
    }

    override fun onDeactivate() {
        active = false
        fish.clear()
        bubbles.clear()
        _uiState.value = AquariumUiState()
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

                    // Fast attack, slower release — react to notes quickly
                    smoothedEnergy = if (masterLevel > smoothedEnergy) {
                        smoothedEnergy * 0.7f + masterLevel * 0.3f   // fast attack
                    } else {
                        smoothedEnergy * 0.95f + masterLevel * 0.05f  // slow release
                    }

                    manageFishPopulation()
                    updateFish(voiceLevels, masterLevel, lfoValue, dt)
                    updateBubbles(masterLevel, dt)

                    _uiState.value = AquariumUiState(
                        fish = ArrayList(fish),
                        bubbles = ArrayList(bubbles),
                        masterEnergy = smoothedEnergy,
                        lfoModulation = lfoValue,
                        currentAngle = currentAngle,
                    )
                }
            }
        }

        val state = _uiState.value
        AquariumCanvas(modifier = modifier, state = state)
    }

    private fun manageFishPopulation() {
        val target = targetFishCount

        // Add fish if under target (only non-fading fish count)
        val activeFish = fish.count { !it.isFadingOut }
        if (activeFish < target) {
            // Spawn one per frame for gradual appearance
            spawnFish()
        }

        // Mark excess fish for fade-out (one per frame to avoid infinite loop)
        if (activeFish > target) {
            val candidate = fish.lastOrNull { !it.isFadingOut && it.alpha >= 0.5f }
            candidate?.isFadingOut = true
        }

        // Hard cap: remove nearly-faded fish if list grows too large (active + fading)
        if (fish.size > maxFishFromKnob * 2) {
            fish.removeAll { it.isFadingOut && it.alpha < 0.3f }
        }
    }

    private fun spawnFish() {
        val id = nextFishId++
        val voiceIdx = id % 8
        val baseSize = 0.04f + Random.nextFloat() * 0.03f
        fish.add(
            Fish(
                id = id,
                baseColor = fishColors[voiceIdx],
                baseSize = baseSize,
                x = Random.nextFloat() * 0.8f + 0.1f,
                y = Random.nextFloat() * 0.8f + 0.1f,
                vx = (Random.nextFloat() - 0.5f) * 0.1f,
                vy = (Random.nextFloat() - 0.5f) * 0.05f,
                heading = Random.nextFloat() * 2f * PI.toFloat(),
                smoothHeading = Random.nextFloat() * 2f * PI.toFloat(),
                voiceIndex = voiceIdx,
                alpha = 0.01f,
            )
        )
    }

    private fun updateFish(voiceLevels: FloatArray, masterLevel: Float, lfo: Float, dt: Float) {
        // ── Swirling current: continuous rotation + LFO modulation + music bursts ──
        swirlSpeed = swirlSpeed * 0.95f + (1f + smoothedEnergy * 3f) * 0.05f
        currentPhase += dt * swirlSpeed * (0.8f + _currentKnob * 1.5f)
        // Swirl direction changes organically using layered sine waves
        currentAngle = sin(currentPhase * 0.7f) * 1.5f +
            sin(currentPhase * 1.3f + 2f) * 0.8f +
            lfo * 1.2f
        val currentDx = cos(currentAngle) * currentStrength
        val currentDy = sin(currentAngle) * currentStrength * 0.6f

        val speedScale = 0.8f + smoothedEnergy * 2.5f  // more range: 0.8x to 3.3x

        // Frame-rate independent damping: 0.85 per second (lighter than before)
        // ~0.85 remaining per second, frame-rate independent
        // For small dt, exp approximation: 0.85^dt ≈ 1 + ln(0.85)*dt
        val dampingRate = -0.1625f  // ln(0.85)
        val damping = (1f + dampingRate * dt).coerceIn(0.5f, 1f)

        for (f in fish) {
            val voiceLevel = voiceLevels.getOrElse(f.voiceIndex) { 0f }
            // Faster energy tracking — react more to music
            f.energy = f.energy * 0.7f + voiceLevel * 0.3f

            // Bigger pulse when voice active: up to 35% scale boost
            val targetPulse = if (voiceLevel > 0.08f) 1f + voiceLevel * 0.35f else 1f
            f.pulseScale += (targetPulse - f.pulseScale) * 0.15f

            if (f.isFadingOut) {
                f.alpha = (f.alpha - dt * 0.8f).coerceAtLeast(0f)
            } else if (f.alpha < 1f) {
                f.alpha = (f.alpha + dt * 0.8f).coerceAtMost(1f)
            }

            // ── Boids forces ──
            var sepX = 0f; var sepY = 0f
            var alignX = 0f; var alignY = 0f
            var cohX = 0f; var cohY = 0f
            var neighbors = 0

            for (other in fish) {
                if (other.id == f.id) continue
                val dx = other.x - f.x
                val dy = other.y - f.y
                val dist = sqrt(dx * dx + dy * dy)

                if (dist < NEIGHBOR_RADIUS && dist > 0.001f) {
                    neighbors++
                    if (dist < SEPARATION_DIST) {
                        sepX -= dx / dist
                        sepY -= dy / dist
                    }
                    alignX += cos(other.heading)
                    alignY += sin(other.heading)
                    cohX += other.x
                    cohY += other.y
                }
            }

            var ax = 0f
            var ay = 0f

            if (neighbors > 0) {
                // Separation — keep personal space
                ax += sepX * 1.0f
                ay += sepY * 1.0f
                // Alignment — reduced so fish don't lock heading
                ax += (alignX / neighbors - cos(f.heading)) * ALIGNMENT_STRENGTH
                ay += (alignY / neighbors - sin(f.heading)) * ALIGNMENT_STRENGTH
                // Cohesion — stronger schooling pull
                val cohCx = cohX / neighbors
                val cohCy = cohY / neighbors
                ax += (cohCx - f.x) * COHESION_STRENGTH
                ay += (cohCy - f.y) * COHESION_STRENGTH
            }

            // Boundary avoidance — stronger push
            val margin = 0.1f
            if (f.x < margin) ax += (margin - f.x) * 5f
            if (f.x > 1f - margin) ax -= (f.x - (1f - margin)) * 5f
            if (f.y < margin) ay += (margin - f.y) * 5f
            if (f.y > 1f - margin) ay -= (f.y - (1f - margin)) * 5f

            // Current force
            ax += currentDx
            ay += currentDy

            // Random wandering impulse — prevents settling into a locked direction
            val wanderAngle = f.heading + (Random.nextFloat() - 0.5f) * 1.5f
            ax += cos(wanderAngle) * 0.15f
            ay += sin(wanderAngle) * 0.15f

            // Music reactivity: active voice fish dart and change direction
            val voiceBoost = if (voiceLevel > 0.08f) 1f + voiceLevel * 1.0f else 1f
            // Sudden note onset causes a direction jolt
            if (voiceLevel > 0.3f && f.energy < voiceLevel * 0.7f) {
                val joltAngle = f.heading + (Random.nextFloat() - 0.5f) * PI.toFloat()
                ax += cos(joltAngle) * voiceLevel * 2f
                ay += sin(joltAngle) * voiceLevel * 2f
            }

            // Apply forces
            f.vx += ax * dt * speedScale * voiceBoost
            f.vy += ay * dt * speedScale * voiceBoost

            // Frame-rate independent damping
            f.vx *= damping
            f.vy *= damping

            // Speed limit
            val speed = sqrt(f.vx * f.vx + f.vy * f.vy)
            val maxSpeed = 0.4f * speedScale
            if (speed > maxSpeed) {
                f.vx = f.vx / speed * maxSpeed
                f.vy = f.vy / speed * maxSpeed
            }

            // Update position
            f.x += f.vx * dt
            f.y += f.vy * dt

            // Update heading from velocity
            if (speed > 0.005f) {
                f.heading = atan2(f.vy, f.vx)
            }
            // Smooth heading — faster interpolation for snappier turns
            val headingDiff = angleDiff(f.smoothHeading, f.heading)
            f.smoothHeading += headingDiff * 0.25f

            // Tail animation — faster when moving/energized
            f.tailPhase += dt * (4f + speed * 30f + f.energy * 8f)
        }

        fish.removeAll { it.alpha <= 0f }
    }

    private fun angleDiff(from: Float, to: Float): Float {
        var diff = to - from
        while (diff > PI) diff -= 2f * PI.toFloat()
        while (diff < -PI) diff += 2f * PI.toFloat()
        return diff
    }

    private fun updateBubbles(masterLevel: Float, dt: Float) {
        if (bubbles.size < 8 && Random.nextFloat() < 0.02f + masterLevel * 0.03f) {
            bubbles.add(Bubble(
                id = nextBubbleId++,
                x = Random.nextFloat() * 0.8f + 0.1f,
                y = 1.05f,
                radius = 0.003f + Random.nextFloat() * 0.006f,
                wobblePhase = Random.nextFloat() * 2f * PI.toFloat(),
            ))
        }

        val iter = bubbles.iterator()
        while (iter.hasNext()) {
            val b = iter.next()
            b.y -= dt * (0.06f + b.radius * 2f)
            b.wobblePhase += dt * 3f
            b.x += sin(b.wobblePhase) * 0.001f

            if (b.y < -0.05f) iter.remove()
        }
    }

    @Composable
    private fun AquariumCanvas(modifier: Modifier, state: AquariumUiState) {
        Canvas(modifier = modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            drawRect(
                brush = Brush.verticalGradient(colors = bgGradientColors)
            )

            drawLightShafts(w, h, state.masterEnergy)

            for (b in state.bubbles) {
                drawCircle(
                    color = OrpheusColors.aquariumBubble,
                    radius = b.radius * w,
                    center = Offset(b.x * w, b.y * h),
                    alpha = b.alpha,
                )
            }

            for (f in state.fish) {
                drawFish(f, w, h)
            }
        }
    }

    private fun DrawScope.drawLightShafts(w: Float, h: Float, energy: Float) {
        val shaftAlpha = 0.03f + energy * 0.02f
        val shaftColor = OrpheusColors.aquariumLight.copy(alpha = shaftAlpha)

        for (i in 0 until 3) {
            val startX = w * (0.3f + i * 0.25f)
            val shaftWidth = w * 0.08f
            val path = shaftPaths[i]
            path.reset()
            path.moveTo(startX, 0f)
            path.lineTo(startX + shaftWidth, 0f)
            path.lineTo(startX - w * 0.1f + shaftWidth, h)
            path.lineTo(startX - w * 0.1f, h)
            path.close()
            drawPath(path, shaftColor)
        }
    }

    private fun DrawScope.drawFish(f: Fish, w: Float, h: Float) {
        if (f.alpha <= 0.01f) return

        val cx = f.x * w
        val cy = f.y * h
        val fishLen = f.baseSize * w * f.pulseScale
        val fishHeight = fishLen * 0.4f

        val brightness = 1f + f.energy * 0.8f  // much brighter flash on active voice
        val fishColor = f.baseColor.copy(
            red = (f.baseColor.red * brightness).coerceAtMost(1f),
            green = (f.baseColor.green * brightness).coerceAtMost(1f),
            blue = (f.baseColor.blue * brightness).coerceAtMost(1f),
            alpha = f.alpha
        )

        val tailSwing = sin(f.tailPhase) * fishHeight * 0.5f

        rotate(
            degrees = f.smoothHeading * 180f / PI.toFloat(),
            pivot = Offset(cx, cy)
        ) {
            bodyPath.reset()
            bodyPath.moveTo(cx + fishLen * 0.5f, cy)
            bodyPath.cubicTo(
                cx + fishLen * 0.3f, cy - fishHeight * 0.5f,
                cx - fishLen * 0.1f, cy - fishHeight * 0.5f,
                cx - fishLen * 0.3f, cy
            )
            bodyPath.cubicTo(
                cx - fishLen * 0.1f, cy + fishHeight * 0.5f,
                cx + fishLen * 0.3f, cy + fishHeight * 0.5f,
                cx + fishLen * 0.5f, cy
            )
            bodyPath.close()
            drawPath(bodyPath, fishColor)

            // Forked tail fin
            tailPath.reset()
            tailPath.moveTo(cx - fishLen * 0.3f, cy)
            tailPath.lineTo(cx - fishLen * 0.55f, cy - fishHeight * 0.5f + tailSwing)
            tailPath.lineTo(cx - fishLen * 0.4f, cy + tailSwing * 0.3f)
            tailPath.lineTo(cx - fishLen * 0.55f, cy + fishHeight * 0.5f + tailSwing)
            tailPath.close()
            drawPath(tailPath, fishColor.copy(alpha = fishColor.alpha * 0.8f))

            val eyeX = cx + fishLen * 0.25f
            val eyeR = fishLen * 0.05f
            drawCircle(Color.White.copy(alpha = f.alpha), eyeR, Offset(eyeX, cy - fishHeight * 0.1f))
            drawCircle(Color.Black.copy(alpha = f.alpha), eyeR * 0.5f, Offset(eyeX, cy - fishHeight * 0.1f))
        }
    }

    companion object {
        // Boids tuning constants
        private const val NEIGHBOR_RADIUS = 0.2f
        private const val SEPARATION_DIST = 0.06f
        private const val ALIGNMENT_STRENGTH = 0.15f
        private const val COHESION_STRENGTH = 0.5f

        val Default = VisualizationLiquidEffects(
            frostSmall = 3f,
            frostMedium = 5f,
            frostLarge = 7f,
            tintAlpha = 0.10f,
            top = VisualizationLiquidScope(
                saturation = 0.5f,
                dispersion = 0.6f,
                curve = 0.2f,
                refraction = 0.5f,
            ),
            bottom = VisualizationLiquidScope(
                saturation = 0.8f,
                dispersion = 0.3f,
                curve = 0.2f,
                refraction = 0.6f,
            ),
            title = CenterPanelStyle(
                scope = VisualizationLiquidScope(
                    saturation = 2f,
                    dispersion = 0.3f,
                    curve = 0.15f,
                    refraction = 0.3f,
                ),
                titleColor = OrpheusColors.aquariumTeal,
                borderColor = OrpheusColors.aquariumTeal.copy(alpha = 0.3f),
                borderWidth = 2.dp,
                titleElevation = 8.dp,
            ),
        )
    }
}
