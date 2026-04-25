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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.ui.infrastructure.CenterPanelStyle
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidEffects
import org.balch.orpheus.ui.infrastructure.VisualizationLiquidScope
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.Visualization
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

// --------------------------------------------------------------------------------
// Data Structures
// --------------------------------------------------------------------------------

private data class Heart(
    var x: Float,           // 0..1 normalized horizontal
    var y: Float,           // 0..1 normalized vertical (0 = top)
    var vy: Float,          // upward drift speed (units/sec)
    var size: Float,        // base radius in normalized units
    val color: Color,
    var life: Float,        // 1.0 -> 0.0
    val decay: Float,
    var pulsePhase: Float,  // independent heartbeat oscillator (radians)
    val pulseRate: Float,   // radians/sec
    // Break animation. willBreak is decided at spawn from current music energy
    // (BREAK_PROB_MIN..MAX). breakAt is the heart's age (seconds) at which the
    // sequence starts; breakAge counts seconds since the sequence began (-1 =
    // not started yet). willFall picks the minority of broken hearts that
    // continue dropping after the hinge opens — those spawn higher up so the
    // fall is visible on screen.
    var age: Float = 0f,
    val willBreak: Boolean = false,
    val willFall: Boolean = false,
    val breakAt: Float = 0f,
    var breakAge: Float = -1f,
)

private data class HeartbeatUiState(
    val hearts: List<Heart> = emptyList(),
    val masterEnergy: Float = 0f,
    val flashIntensity: Float = 0f,
)

// --------------------------------------------------------------------------------
// Visualization Implementation
// --------------------------------------------------------------------------------

/**
 * Heartbeat — various-size hearts that pulse with the music.
 *
 * Each spawned heart picks its color from the voice-duo (bass/low-mid/mid/high)
 * that triggered it, mirroring the per-duo color mapping used by the Bugs viz.
 * Heart size = voice loudness at spawn time. Quantity = master energy.
 *
 * Knobs:
 *   GLOW   — color richness / glow alpha
 *   BREAKS — how sensitive the broken-heart count is to music energy + mood.
 *            At 0 the break rate stays at its baseline floor regardless of
 *            audio dynamics; at 1 the rate swings up to the ceiling on
 *            peaks (loud + transient = many breaks).
 */
@Inject
@ContributesIntoSet(FeatureScope::class, binding = binding<Visualization>())
class HeartbeatViz(
    private val engine: SynthEngine,
) : Visualization {

    override val id = "heartbeat"
    override val name = "Heartbeat"
    override val color = OrpheusColors.synthPink
    override val knob1Label = "GLOW"
    override val knob2Label = "BREAKS"
    override val liquidEffects = DefaultEffects

    // Knobs
    private var _glow = 0.45f         // color/glow intensity
    private var _sensitivity = 0.35f  // how strongly energy+mood drive break rate

    override fun setKnob1(value: Float) { _glow = value.coerceIn(0f, 1f) }
    override fun setKnob2(value: Float) { _sensitivity = value.coerceIn(0f, 1f) }

    // Per-duo color palette — same mapping convention as AntColonyViz so the
    // user's mental model carries over (low voices = magenta, high = green, etc.).
    private val duoColors = arrayOf(
        OrpheusColors.antNeonMagenta,    // Duo 0 — bass / kick
        OrpheusColors.antNeonCyan,       // Duo 1 — low-mid
        OrpheusColors.antNeonOrange,     // Duo 2 — mid (lead)
        OrpheusColors.antNeonGreen,      // Duo 3 — high (pads / sparkle)
        OrpheusColors.antNeonYellow,     // Duo 4 — extra
        OrpheusColors.antNeonPink,       // Duo 5 — extra
    )

    // State
    private val _uiState = mutableStateOf(HeartbeatUiState(), neverEqualPolicy())
    private val hearts = ArrayList<Heart>()
    private var active = false

    // Smoothed audio
    private var smoothedMaster = 0f
    private var smoothedPeak = 0f
    private var previousMaster = 0f
    private val smoothedDuoLevels = FloatArray(MAX_DUOS)

    // Spawn pacing
    private var spawnAccum = 0f

    override fun onActivate() {
        active = true
        hearts.clear()
        smoothedMaster = 0f
        smoothedPeak = 0f
        previousMaster = 0f
        smoothedDuoLevels.fill(0f)
        spawnAccum = 0f
    }

    override fun onDeactivate() {
        active = false
        hearts.clear()
        _uiState.value = HeartbeatUiState()
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

                    updateSimulation(dt)
                }
            }
        }

        val state = _uiState.value
        Canvas(modifier = modifier.fillMaxSize()) {
            drawRect(OrpheusColors.vizBackground)

            // Soft red bloom on big bass beats — barely visible at low energy
            if (state.flashIntensity > 0.01f) {
                drawRect(
                    color = Color(0xFFFF2244).copy(alpha = state.flashIntensity.coerceAtMost(0.18f)),
                    blendMode = BlendMode.Plus,
                )
            }

            state.hearts.forEach { heart ->
                drawHeart(heart, state.masterEnergy)
            }
        }
    }

    // ----------------------------------------------------------------------------
    // Simulation
    // ----------------------------------------------------------------------------

    private fun updateSimulation(dt: Float) {
        val voiceLevels = engine.voiceLevelsFlow.value
        val masterLevel = engine.masterLevelFlow.value
        val peakLevel = engine.peakFlow.value

        // Smoothed audio
        smoothedMaster += (masterLevel - smoothedMaster) * (8f * dt).coerceAtMost(1f)
        smoothedPeak += (peakLevel - smoothedPeak) * (12f * dt).coerceAtMost(1f)

        // Per-duo loudness (each duo = 2 voices summed, capped at 1.0)
        val numDuos = minOf(MAX_DUOS, voiceLevels.size / 2)
        for (d in 0 until numDuos) {
            val a = voiceLevels.getOrElse(d * 2) { 0f }
            val b = voiceLevels.getOrElse(d * 2 + 1) { 0f }
            val target = (a + b).coerceAtMost(1f)
            smoothedDuoLevels[d] += (target - smoothedDuoLevels[d]) * (10f * dt).coerceAtMost(1f)
        }

        // Beat detection — sudden master-level rise spawns a burst
        val energyDelta = masterLevel - previousMaster
        val isBeat = energyDelta > BEAT_DELTA_THRESHOLD && masterLevel > BEAT_LEVEL_THRESHOLD
        previousMaster = masterLevel

        // Continuous spawn — purely proportional to smoothed master energy.
        // Silent music => zero spawns. Loud music => swarm.
        val baseRate = BASE_SPAWN_RATE * smoothedMaster
        spawnAccum += baseRate * dt
        while (spawnAccum >= 1f) {
            spawnAccum -= 1f
            spawnHeart(numDuos, isBeat = false)
        }

        // Beat burst — extra hearts on transients. Burst size scales with the
        // strength of the peak so subtle beats add 1-2, big bass hits add 4-6.
        if (isBeat) {
            val burstCount = 1 + (smoothedPeak * 6f).toInt().coerceIn(0, 5)
            repeat(burstCount) { spawnHeart(numDuos, isBeat = true) }
        }

        // Update hearts: drift up, decay life, advance pulse phase, advance break.
        val gravity = -DRIFT_BASE_SPEED * (1f + smoothedMaster * 0.6f)
        for (i in hearts.indices.reversed()) {
            val h = hearts[i]
            h.y += gravity * h.vy * dt
            h.age += dt

            // Advance break sequence after the per-heart delay elapses.
            if (h.willBreak && h.age >= h.breakAt) {
                h.breakAge = (if (h.breakAge < 0f) 0f else h.breakAge) + dt
            }

            // Pulse rate is multiplied by phase: faster while tension builds,
            // peaks during the tear, then drops to a slow beat after separation.
            val pulseMul = breakPulseMultiplier(h.breakAge)
            h.pulsePhase += h.pulseRate * pulseMul * dt

            // Slow the life-decay during the separation phase so the user
            // actually sees the broken halves drift apart before they fade.
            val decayMul = if (inSeparationPhase(h.breakAge)) 0.55f else 1f
            h.life -= h.decay * decayMul * dt
            // Slight horizontal drift via pulsePhase coupling — gentle sway
            h.x += sin(h.pulsePhase * 0.3f) * 0.015f * dt

            if (h.life <= 0f || h.y < -0.15f) hearts.removeAt(i)
        }

        // Cap population (perf safety) — single fixed ceiling now that the
        // density knob is gone.
        while (hearts.size > MAX_HEARTS) hearts.removeAt(0)

        val flash = (smoothedMaster - 0.55f).coerceAtLeast(0f) * 0.35f * _glow
        // hearts is shared by reference — both updateSimulation and Canvas drawing
        // run on the main thread (LaunchedEffect + withFrameNanos), so there's no
        // race. neverEqualPolicy() drives recomposition regardless of identity.
        _uiState.value = HeartbeatUiState(
            hearts = hearts,
            masterEnergy = smoothedMaster,
            flashIntensity = flash,
        )
    }

    /**
     * Spawn one heart. Color comes from a duo selected by current per-duo loudness
     * (weighted random — louder duos spawn more). Size is proportional to that
     * duo's level. Beat-spawned hearts get a size + life boost.
     */
    private fun spawnHeart(numDuos: Int, isBeat: Boolean) {
        if (numDuos <= 0) return
        val duo = pickWeightedDuo(numDuos)
        val duoLevel = smoothedDuoLevels[duo]
        val color = duoColors[duo % duoColors.size]

        // Size scales with that duo's level — quiet voice = tiny heart, loud bass = big heart.
        // Lower duos (0..1) get a size bonus so bass beats produce visually bigger hearts.
        val duoSizeBonus = (1f - duo / numDuos.toFloat()) * 0.3f
        val baseSize = HEART_MIN_SIZE + duoLevel * HEART_LEVEL_SIZE_GAIN + duoSizeBonus
        val size = if (isBeat) baseSize * 1.4f else baseSize

        // Break dice — probability is the sum of a baseline floor plus a
        // sensitivity-scaled lift that tracks music energy + mood.
        //   energy = sustained loudness (smoothedMaster)
        //   mood   = transient excitement (smoothedPeak)
        // The BREAKS knob (_sensitivity) controls how strongly that combined
        // signal drives the rate above its floor:
        //   knob = 0  → flat at BREAK_PROB_MIN regardless of music
        //   knob = 1  → swings up to BREAK_PROB_MAX on energy/mood peaks
        val energyMood = (smoothedMaster + smoothedPeak * 0.5f).coerceIn(0f, 1f)
        val breakProb = BREAK_PROB_MIN +
            (BREAK_PROB_MAX - BREAK_PROB_MIN) * _sensitivity * energyMood
        val willBreak = Random.nextFloat() < breakProb
        // 30% of broken hearts also fall after hinging open. The other 70%
        // hinge open in place and fade.
        val willFall = willBreak && Random.nextFloat() < BREAK_FALL_FRACTION

        // Spawn near bottom; slight x bias toward the duo's "lane".
        // Falling-broken hearts spawn near the top of the screen so the fall
        // animation has somewhere to go before the heart fades out.
        val laneX = (duo + 0.5f) / numDuos
        val xJitter = (Random.nextFloat() - 0.5f) * 0.4f
        val spawnX = (laneX + xJitter).coerceIn(0.05f, 0.95f)
        val spawnY = if (willFall) {
            FALLER_SPAWN_Y_MIN + Random.nextFloat() * (FALLER_SPAWN_Y_MAX - FALLER_SPAWN_Y_MIN)
        } else {
            1.05f + Random.nextFloat() * 0.08f
        }

        // Drift speed — variation keeps the field alive.
        // Fallers don't drift naturally (they spawn near the top and need to
        // stay there until the break; the fall comes from the break animation).
        val vy = if (willFall) 0f
            else DRIFT_VY_MIN + Random.nextFloat() * (DRIFT_VY_MAX - DRIFT_VY_MIN)

        // Pulse rate — random spread around 80 BPM (~1.33 Hz) so hearts don't sync uniformly
        val pulseRate = (1.0f + Random.nextFloat() * 0.8f) * (PI.toFloat() * 2f) // 1.0..1.8 Hz

        val breakAt = if (willBreak)
            BREAK_DELAY_MIN + Random.nextFloat() * (BREAK_DELAY_MAX - BREAK_DELAY_MIN)
        else 0f

        val baseLife = if (isBeat) 1.2f else 0.8f + Random.nextFloat() * 0.3f
        // Broken hearts get a longer life so the four-phase animation has time
        // to play out before the heart fades. Fallers get even more so the
        // descent is visible all the way down.
        val life = when {
            willFall -> baseLife * 2.2f
            willBreak -> baseLife * 1.7f
            else -> baseLife
        }
        val decay = HEART_DECAY_MIN + Random.nextFloat() * (HEART_DECAY_MAX - HEART_DECAY_MIN)

        hearts.add(
            Heart(
                x = spawnX, y = spawnY, vy = vy,
                size = size.coerceAtMost(HEART_MAX_SIZE),
                color = color,
                life = life, decay = decay,
                pulsePhase = Random.nextFloat() * (PI.toFloat() * 2f),
                pulseRate = pulseRate,
                willBreak = willBreak,
                willFall = willFall,
                breakAt = breakAt,
            )
        )
    }

    /** Weighted random duo pick — probability ∝ smoothed loudness. Falls back to round-robin if silent. */
    private fun pickWeightedDuo(numDuos: Int): Int {
        var total = 0f
        for (d in 0 until numDuos) total += smoothedDuoLevels[d]
        if (total < 0.001f) return Random.nextInt(numDuos)
        var roll = Random.nextFloat() * total
        for (d in 0 until numDuos) {
            roll -= smoothedDuoLevels[d]
            if (roll <= 0f) return d
        }
        return numDuos - 1
    }

    // ----------------------------------------------------------------------------
    // Drawing
    // ----------------------------------------------------------------------------

    private fun DrawScope.drawHeart(h: Heart, masterEnergy: Float) {
        // Pulsing — size scales with master energy + the heart's own oscillator
        val pulse = 1f + 0.18f * masterEnergy + 0.12f * sin(h.pulsePhase)
        val drawSize = h.size * pulse

        val cx = h.x * size.width
        val cy = h.y * size.height
        val pixelSize = drawSize * size.minDimension

        // Life envelope — fade in slightly then fade out
        val lifeAlpha = (h.life * 1.2f).coerceIn(0f, 1f)
        val glowMul = 0.5f + _glow * 0.5f
        val finalAlpha = (lifeAlpha * glowMul).coerceIn(0f, 1f)
        val tinted = h.color.copy(alpha = finalAlpha)

        translate(cx, cy) {
            when {
                // Pre-break or non-breaking: render the whole heart as before.
                h.breakAge < 0f -> drawWholeHeart(pixelSize, tinted, finalAlpha, h.color)

                // Tension phase — whole heart with a growing red flush overlay.
                h.breakAge < BREAK_TENSION_END -> {
                    drawWholeHeart(pixelSize, tinted, finalAlpha, h.color)
                    val t = h.breakAge / BREAK_TENSION_END
                    drawHeartPath(
                        pixelSize,
                        BREAK_FLUSH_COLOR.copy(alpha = finalAlpha * 0.45f * t),
                        filled = true,
                    )
                }

                // Tear phase — whole heart with a widening jagged crack down the centre.
                h.breakAge < BREAK_TEAR_END -> {
                    drawWholeHeart(pixelSize, tinted, finalAlpha, h.color)
                    val tearT = (h.breakAge - BREAK_TENSION_END) /
                        (BREAK_TEAR_END - BREAK_TENSION_END)
                    drawCrack(pixelSize, tearT, finalAlpha)
                }

                // Separation phase — two halves hinge open at the base; the
                // ~30% marked willFall continue to drop after the hinge opens.
                else -> drawSeparatedHalves(
                    pixelSize, tinted, finalAlpha, h.color, h.breakAge, h.willFall,
                )
            }
        }
    }

    private fun DrawScope.drawWholeHeart(
        pixelSize: Float, tinted: Color, finalAlpha: Float, baseColor: Color,
    ) {
        if (_glow > 0.05f) {
            drawHeartPath(
                pixelSize * 1.45f,
                baseColor.copy(alpha = finalAlpha * 0.18f * _glow),
                filled = true,
            )
        }
        drawHeartPath(pixelSize, tinted, filled = true)
        drawHeartPath(
            pixelSize,
            Color.White.copy(alpha = finalAlpha * 0.35f * _glow),
            filled = false,
        )
    }

    private fun DrawScope.drawCrack(pixelSize: Float, progress: Float, finalAlpha: Float) {
        // Dark crack widens from a hairline to a visible split.
        val widthUnits = 0.012f + progress * 0.10f
        scale(pixelSize, pixelSize, pivot = Offset.Zero) {
            drawPath(
                crackPath,
                Color.Black.copy(alpha = (finalAlpha * (0.55f + progress * 0.45f)).coerceAtMost(1f)),
                style = Stroke(width = widthUnits),
            )
            // White shimmer along the edge as the crack opens — sells the tear.
            drawPath(
                crackPath,
                Color.White.copy(alpha = finalAlpha * 0.35f * progress),
                style = Stroke(width = widthUnits * 0.4f),
            )
        }
    }

    private fun DrawScope.drawSeparatedHalves(
        pixelSize: Float, tinted: Color, finalAlpha: Float,
        baseColor: Color, breakAge: Float, willFall: Boolean,
    ) {
        val sepElapsed = (breakAge - BREAK_TEAR_END).coerceAtLeast(0f)
        // Each half drifts OUTWARD with a slight tilt — they read as two
        // genuinely separated pieces, not a hinge. The rotation is small;
        // the visual gap is mostly created by the horizontal drift.
        val tiltDeg = (sepElapsed * BREAK_TILT_RATE_DEG_PER_HALF)
            .coerceAtMost(BREAK_TILT_MAX_DEG_PER_HALF)
        val drift = (sepElapsed * BREAK_HALF_DRIFT_RATE)
            .coerceAtMost(BREAK_HALF_DRIFT_MAX) * pixelSize
        // Each half rotates around ITS OWN bottom point (after the drift
        // moves it sideways), so the tilt looks anchored to the half's base
        // rather than swinging from a shared hinge.
        val pivot = Offset(0f, pixelSize)
        // Only fallers actually translate downward, and only AFTER the drift
        // has reached its max. Fall is scaled by canvas height (not heart
        // size) so the descent crosses the screen regardless of heart size.
        val fall = if (willFall) {
            val driftDuration = BREAK_HALF_DRIFT_MAX / BREAK_HALF_DRIFT_RATE
            val fallElapsed = (sepElapsed - driftDuration).coerceAtLeast(0f)
            fallElapsed * fallElapsed * size.height * BREAK_FALL_GRAVITY
        } else 0f
        val outline = Color.White.copy(alpha = finalAlpha * 0.35f * _glow)

        translate(0f, fall) {
            // Left half: drift left, then tilt slightly left around its own base.
            translate(-drift, 0f) {
                drawHeartHalf(
                    pixelSize, tinted, finalAlpha, baseColor, outline,
                    path = leftHeartHalfPath, tiltDegrees = -tiltDeg, pivot = pivot,
                )
            }
            // Right half: drift right, then tilt slightly right around its own base.
            translate(drift, 0f) {
                drawHeartHalf(
                    pixelSize, tinted, finalAlpha, baseColor, outline,
                    path = rightHeartHalfPath, tiltDegrees = tiltDeg, pivot = pivot,
                )
            }
        }
    }

    private fun DrawScope.drawHeartHalf(
        pixelSize: Float, tinted: Color, finalAlpha: Float,
        baseColor: Color, outline: Color,
        path: Path, tiltDegrees: Float, pivot: Offset,
    ) {
        rotate(degrees = tiltDegrees, pivot = pivot) {
            scale(pixelSize, pixelSize, pivot = Offset.Zero) {
                if (_glow > 0.05f) {
                    scale(1.45f, 1.45f, pivot = Offset.Zero) {
                        drawPath(
                            path,
                            baseColor.copy(alpha = finalAlpha * 0.15f * _glow),
                            blendMode = BlendMode.Plus,
                        )
                    }
                }
                drawPath(path, tinted, blendMode = BlendMode.Plus)
                drawPath(
                    path, outline,
                    style = Stroke(width = 1.5f / pixelSize),
                    blendMode = BlendMode.Plus,
                )
            }
        }
    }

    // Cubic-bezier heart at unit radius — bottom point at (0, +1), peak cusp at
    // (0, -0.4), lobes flaring to (±1.2, -0.7). Built once and rescaled per draw
    // via DrawScope.scale(), avoiding a Path allocation per heart per frame.
    private val unitHeartPath = Path().apply {
        moveTo(0f, 1f)
        cubicTo(-1.4f, 0.30f, -1.2f, -0.95f, 0f, -0.35f)
        cubicTo(1.2f, -0.95f, 1.4f, 0.30f, 0f, 1f)
        close()
    }

    // Left half: lobe + jagged inner edge from cusp back down to the base.
    // The inner edge mirrors the points of [crackPath] so the two halves tile
    // perfectly at hingeDegrees=0 and reveal the zigzag silhouette as they
    // hinge open.
    private val leftHeartHalfPath = Path().apply {
        moveTo(0f, 1f)
        cubicTo(-1.4f, 0.30f, -1.2f, -0.95f, 0f, -0.35f)
        // Jagged edge back to base — same xy points as crackPath, in cusp→base order.
        lineTo(-0.07f, -0.10f)
        lineTo(0.06f, 0.12f)
        lineTo(-0.06f, 0.36f)
        lineTo(0.07f, 0.58f)
        lineTo(-0.04f, 0.80f)
        lineTo(0f, 1f)
        close()
    }

    // Right half: jagged inner edge from base up to cusp, then right lobe back down.
    private val rightHeartHalfPath = Path().apply {
        moveTo(0f, 1f)
        // Jagged edge in base→cusp order — same points reversed.
        lineTo(-0.04f, 0.80f)
        lineTo(0.07f, 0.58f)
        lineTo(-0.06f, 0.36f)
        lineTo(0.06f, 0.12f)
        lineTo(-0.07f, -0.10f)
        lineTo(0f, -0.35f)
        cubicTo(1.2f, -0.95f, 1.4f, 0.30f, 0f, 1f)
        close()
    }

    // Jagged crack path in unit space — runs from the top cusp to the bottom point.
    // Used as an overlay during the tear phase, then implicitly carried forward
    // by the half silhouettes during the separation phase.
    private val crackPath = Path().apply {
        moveTo(0.02f, -0.35f)
        lineTo(-0.07f, -0.10f)
        lineTo(0.06f, 0.12f)
        lineTo(-0.06f, 0.36f)
        lineTo(0.07f, 0.58f)
        lineTo(-0.04f, 0.80f)
        lineTo(0.02f, 1.00f)
    }

    private fun DrawScope.drawHeartPath(pixelRadius: Float, color: Color, filled: Boolean) {
        scale(pixelRadius, pixelRadius, pivot = Offset.Zero) {
            if (filled) {
                drawPath(unitHeartPath, color, blendMode = BlendMode.Plus)
            } else {
                // Stroke width is in *scaled* coords — divide by pixelRadius so it
                // ends up at a constant 1.5 device pixels regardless of heart size.
                drawPath(
                    unitHeartPath,
                    color,
                    style = Stroke(width = 1.5f / pixelRadius),
                    blendMode = BlendMode.Plus,
                )
            }
        }
    }

    companion object {
        private const val MAX_DUOS = 6

        // Spawn pacing — at master=1 this spawns ~38/sec.
        // Silent music spawns nothing because the rate is purely smoothedMaster-proportional.
        private const val BASE_SPAWN_RATE = 38f
        private const val BEAT_DELTA_THRESHOLD = 0.15f  // master-level jump that counts as a beat
        private const val BEAT_LEVEL_THRESHOLD = 0.10f  // floor master must exceed for a beat

        // Heart sizing (normalized — multiplied by canvas.minDimension at draw time)
        private const val HEART_MIN_SIZE = 0.018f
        private const val HEART_LEVEL_SIZE_GAIN = 0.075f
        private const val HEART_MAX_SIZE = 0.13f

        // Heart life
        private const val HEART_DECAY_MIN = 0.18f
        private const val HEART_DECAY_MAX = 0.45f

        // Drift
        private const val DRIFT_BASE_SPEED = 0.18f      // base upward speed (units/sec)
        private const val DRIFT_VY_MIN = 0.6f
        private const val DRIFT_VY_MAX = 1.4f

        // Population cap (perf safety) — generous fixed ceiling.
        private const val MAX_HEARTS = 240

        // ── Break animation tuning ─────────────────────────────────────────
        // Probability range a heart will break, lerped against smoothedMaster.
        // Silent music: 10%. Full energy: 33%.
        private const val BREAK_PROB_MIN = 0.10f
        private const val BREAK_PROB_MAX = 0.33f
        // Per-heart delay (seconds) between spawn and the start of the sequence.
        private const val BREAK_DELAY_MIN = 0.25f
        private const val BREAK_DELAY_MAX = 0.65f
        // Phase boundaries on the breakAge timeline (seconds since sequence began).
        // 0 .. BREAK_TENSION_END        — accelerated beating (red flush builds)
        // BREAK_TENSION_END .. BREAK_TEAR_END — visible jagged crack widens
        // BREAK_TEAR_END .. life-end    — two halves drift apart with slow beat
        private const val BREAK_TENSION_END = 0.40f
        private const val BREAK_TEAR_END = 0.58f
        // Pulse-rate multipliers for each phase relative to baseline.
        private const val BREAK_PULSE_FAST = 1.85f
        private const val BREAK_PULSE_TEAR = 2.50f
        private const val BREAK_PULSE_SLOW = 0.55f
        // Half-half motion after separation begins. Each half drifts OUTWARD
        // and tilts slightly so the two pieces read as genuinely separated
        // shards rather than a hinge swinging open. Drift creates the visible
        // gap; the small tilt sells the "torn" feel.
        private const val BREAK_TILT_MAX_DEG_PER_HALF = 6f         // ~12° total tilt
        private const val BREAK_TILT_RATE_DEG_PER_HALF = 12f       // ~0.5s to fully tilt
        private const val BREAK_HALF_DRIFT_MAX = 0.12f             // fraction of pixelSize per half
        private const val BREAK_HALF_DRIFT_RATE = 0.24f            // reaches max in ~0.5s
        // Fraction of broken hearts that ALSO fall after the hinge fully opens.
        // The other (1 - this) hinge open in place and just fade.
        private const val BREAK_FALL_FRACTION = 0.30f
        // Spawn-Y range for the falling subset — placed near the top so the
        // fall has somewhere visible to go before the heart fades.
        private const val FALLER_SPAWN_Y_MIN = 0.05f
        private const val FALLER_SPAWN_Y_MAX = 0.22f
        // Faller gravity: fraction of canvas height per second^2. Tuned so the
        // heart traverses ~half the canvas in ~1s of fall.
        private const val BREAK_FALL_GRAVITY = 0.55f

        // Red flush overlay tint applied during the tension phase.
        private val BREAK_FLUSH_COLOR = Color(0xFFFF1F3A)

        private fun breakPulseMultiplier(breakAge: Float): Float = when {
            breakAge < 0f -> 1f
            breakAge < BREAK_TENSION_END -> BREAK_PULSE_FAST
            breakAge < BREAK_TEAR_END -> BREAK_PULSE_TEAR
            else -> BREAK_PULSE_SLOW
        }

        private fun inSeparationPhase(breakAge: Float): Boolean =
            breakAge >= BREAK_TEAR_END

        val DefaultEffects = VisualizationLiquidEffects(
            frostSmall = 1f,
            frostMedium = 2f,
            frostLarge = 3f,
            tintAlpha = 0.03f,
            top = VisualizationLiquidScope(
                saturation = 1.6f,
                dispersion = 0.4f,
                curve = 0.05f,
                refraction = 0.15f,
            ),
            bottom = VisualizationLiquidScope(
                saturation = 1.2f,
                dispersion = 0.2f,
                curve = 0.15f,
                refraction = 0.10f,
            ),
            title = CenterPanelStyle(
                scope = VisualizationLiquidScope(contrast = 1.4f, saturation = 2f),
                titleColor = OrpheusColors.synthPink,
                borderColor = OrpheusColors.synthPink.copy(alpha = 0.45f),
                titleElevation = 6.dp,
            ),
        )
    }
}
