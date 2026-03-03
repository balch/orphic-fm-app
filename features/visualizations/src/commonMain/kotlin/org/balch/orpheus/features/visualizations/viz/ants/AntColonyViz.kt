package org.balch.orpheus.features.visualizations.viz.ants

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// --------------------------------------------------------------------------------
// Ant Colony Visualization
// --------------------------------------------------------------------------------

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<Visualization>())
class AntColonyViz(
    private val engine: SynthEngine,
) : Visualization {

    override val id = "ant_colony"
    override val name = "Bugs"
    override val color = OrpheusColors.antNeonCyan
    override val knob1Label = "DOOM"
    override val knob2Label = "TYPE"
    override val knob2ValueFormatter: (Float) -> String = { v ->
        when {
            v < 0.25f -> "STOMP"
            v < 0.50f -> "FIRE"
            v < 0.75f -> "RANDOM"
            else -> "LENS"
        }
    }

    override val liquidEffects = DefaultEffects

    private var _doomThreshold = 0.6f
    private var _doomType = 0.5f   // Default 0.5 = Random destruction

    override fun setKnob1(value: Float) { _doomThreshold = value.coerceIn(0f, 1f) }
    override fun setKnob2(value: Float) { _doomType = value.coerceIn(0f, 1f) }

    private val thresholdBlockCount: Int
        get() {
            val minFraction = 0.20f
            val maxFraction = 1.0f
            val fraction = minFraction + _doomThreshold * (maxFraction - minFraction)
            return (fraction * MAX_MOUND_BLOCKS).toInt()
        }

    // Knob 2 layout: 0-25% Stomp, 25-50% Fire Ants, 50-75% Random, 75-100% Mag Glass
    private val selectedDestructionType: DestructionType
        get() = when {
            _doomType < 0.25f -> DestructionType.STOMP
            _doomType < 0.50f -> DestructionType.FIRE_ANT_WAR
            _doomType < 0.75f -> DestructionType.entries[Random.nextInt(3)]
            else -> DestructionType.MAGNIFYING_GLASS
        }

    private val voiceColors = arrayOf(
        OrpheusColors.antNeonMagenta,    // Duo 0 (Bass)
        OrpheusColors.antNeonMagenta,
        OrpheusColors.antNeonCyan,       // Duo 1 (Low-mid)
        OrpheusColors.antNeonCyan,
        OrpheusColors.antNeonOrange,     // Duo 2 (Mid)
        OrpheusColors.antNeonOrange,
        OrpheusColors.antNeonGreen,      // Duo 3 (High)
        OrpheusColors.antNeonGreen,
        OrpheusColors.antNeonYellow,     // Duo 4 (REPL)
        OrpheusColors.antNeonYellow,
        OrpheusColors.antNeonPink,       // Duo 5 (REPL)
        OrpheusColors.antNeonPink,
        OrpheusColors.antFireRed,        // FIRE_COLOR_INDEX sentinel
    )

    // Pre-allocated constants and simulation objects
    private val clusterOffsets = intArrayOf(-1, 0, 1, 0, 0, -1, 0, 1) // dx,dz pairs
    private val grid = Grid()
    private val ants = Array(MAX_ANTS) { Ant() }
    private val debris = Array(MAX_DEBRIS) { Debris() }
    private var trails = generateTrails()

    // State machine
    private var phase = VizPhase.BUILDING
    private var destructionType = DestructionType.STOMP
    private var destructionProgress = 0f
    private var destructionDuration = 4f

    // Smoothed audio values (accessible during draw)
    private var smoothedMaster = 0f
    private var smoothedPeak = 0f   // peak master volume (0..1)
    private var smoothedLfo = 0f    // combined LFO (for glow)
    private var smoothedLfoA = 0f   // oscillator A (-1..1)
    private var smoothedLfoB = 0f   // oscillator B (-1..1)
    // Curl noise flow field state
    private var flowTime = 0f            // Slowly advancing noise time dimension
    private var transientBoost = 0f      // Decaying transient drift speed
    private val duoLevels = FloatArray(6)  // Per-duo loudness for bloom
    // Pre-allocated scratch arrays to avoid Pair allocation in hot loops
    private val curlResult = FloatArray(2)
    private val trailPosResult = FloatArray(2)
    private val isoScratch = FloatArray(2)
    private var antSpawnAccum = 0f
    private var nextTrailIndex = 0  // Round-robin trail assignment

    // Animation
    private var active = false
    private var animationTime = 0f

    // Destruction-specific state
    private var fireAntSeed = 42L   // Fixed seed, reset at destruction start
    private var stompY = -0.5f
    private var stompPhase = 0
    private var fireAntProgress = 0f
    private var magGlassAngle = 0f
    private var magGlassCenterX = 0.5f
    private var magGlassCenterY = 0.3f

    // Pre-computed trig constants for hot loops
    private val PI_F = PI.toFloat()
    private val TWO_PI_F = (2.0 * PI).toFloat()
    private val HALF_PI_F = (PI / 2.0).toFloat()
    private val SECTOR_WIDTH = TWO_PI_F / 6f

    // Pre-allocated paths for drawing
    private val bootPath = Path()
    // Pre-allocated Stroke constants
    private val strokeBoot = Stroke(width = 3f)
    private val strokeCrack = Stroke(width = 2f)
    private val strokeLens = Stroke(width = 2f)
    private val strokeLeg = Stroke(width = 1f)

    private val _uiState = mutableStateOf(AntColonyUiState(), neverEqualPolicy())

    override fun onActivate() {
        active = true
        animationTime = 0f
        smoothedMaster = 0f
        smoothedPeak = 0f
        smoothedLfo = 0f
        smoothedLfoA = 0f
        smoothedLfoB = 0f
        flowTime = 0f
        transientBoost = 0f
        duoLevels.fill(0f)
        antSpawnAccum = 0f
        nextTrailIndex = 0
        trails = generateTrails()
        phase = VizPhase.BUILDING
        destructionProgress = 0f
        grid.clear()
        for (ant in ants) ant.active = false
        for (d in debris) d.active = false
    }

    override fun onDeactivate() {
        active = false
        grid.clear()
        for (ant in ants) ant.active = false
        for (d in debris) d.active = false
        _uiState.value = AntColonyUiState()
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
                    val lfoA = engine.lfoAOutputFlow.value
                    val lfoB = engine.lfoBOutputFlow.value
                    val masterLevel = engine.masterLevelFlow.value
                    val peakLevel = engine.peakFlow.value

                    updateSimulation(voiceLevels, masterLevel, peakLevel, lfoValue, lfoA, lfoB, dt)

                    _uiState.value = AntColonyUiState(
                        blockCount = grid.blockCount,
                        phase = phase,
                        destructionType = destructionType,
                        destructionProgress = destructionProgress,
                        time = animationTime,
                        stompY = stompY,
                        stompPhase = stompPhase,
                        magGlassCenterX = magGlassCenterX,
                        magGlassCenterY = magGlassCenterY,
                    )
                }
            }
        }

        val state = _uiState.value

        Canvas(modifier = modifier.fillMaxSize()) {
            drawBackground()
            drawGroundPlane()
            drawBlocks(grid, voiceColors, duoLevels, smoothedMaster, smoothedLfo)
            drawAnts(ants, voiceColors, strokeLeg)
            drawDebris(debris, voiceColors)

            if (state.phase == VizPhase.DESTRUCTION) {
                when (state.destructionType) {
                    DestructionType.STOMP -> drawStompEffect(
                        state.destructionProgress, state.stompY, state.stompPhase,
                        bootPath, strokeBoot, strokeCrack,
                    )
                    DestructionType.FIRE_ANT_WAR -> drawFireAntEffect(
                        state.destructionProgress, Random(fireAntSeed),
                    )
                    DestructionType.MAGNIFYING_GLASS -> drawMagnifyingGlassEffect(
                        state.magGlassCenterX, state.magGlassCenterY,
                        animationTime, strokeLens,
                    )
                }
            }
        }
    }

    // ============================================================================
    // Simulation
    // ============================================================================

    private fun updateSimulation(
        voiceLevels: FloatArray, masterLevel: Float, peakLevel: Float,
        lfoValue: Float, lfoA: Float, lfoB: Float, dt: Float
    ) {
        smoothedMaster += (masterLevel - smoothedMaster) * (SMOOTH_MASTER_RATE * dt).coerceAtMost(1f)
        smoothedPeak += (peakLevel - smoothedPeak) * (SMOOTH_MASTER_RATE * dt).coerceAtMost(1f)
        smoothedLfo += (lfoValue - smoothedLfo) * (SMOOTH_LFO_RATE * dt).coerceAtMost(1f)
        smoothedLfoA += (lfoA - smoothedLfoA) * (SMOOTH_LFO_AB_RATE * dt).coerceAtMost(1f)
        smoothedLfoB += (lfoB - smoothedLfoB) * (SMOOTH_LFO_AB_RATE * dt).coerceAtMost(1f)

        // Rebuild trail polylines
        updateTrails(dt)

        // Update per-duo loudness for bloom rendering
        val numDuos = minOf(6, voiceLevels.size / 2)
        for (d in 0 until numDuos) {
            val raw = voiceLevels.getOrElse(d * 2) { 0f } + voiceLevels.getOrElse(d * 2 + 1) { 0f }
            duoLevels[d] += (raw - duoLevels[d]) * (SMOOTH_DUO_RATE * dt).coerceAtMost(1f)
        }

        when (phase) {
            VizPhase.BUILDING -> updateBuilding(voiceLevels, dt)
            VizPhase.DESTRUCTION -> updateDestruction(dt)
        }
    }

    private fun updateBuilding(voiceLevels: FloatArray, dt: Float) {
        // Check threshold
        if (grid.blockCount >= thresholdBlockCount && grid.blockCount > 0) {
            startDestruction()
            return
        }

        // Compute max voice level once
        var maxVoiceLevel = 0f
        for (v in voiceLevels) { if (v > maxVoiceLevel) maxVoiceLevel = v }

        // Spawn ants — much faster rate for 1-4 min construction
        val spawnRate = smoothedMaster * ANT_SPAWN_RATE
        antSpawnAccum += spawnRate * dt
        while (antSpawnAccum >= 1f) {
            antSpawnAccum -= 1f
            spawnAnt(voiceLevels, maxVoiceLevel)
        }

        // Update ants
        updateAnts(maxVoiceLevel, dt)
    }

    // ─── Trail generation ───────────────────────────────────────────────

    private fun generateTrails(): Array<TrailPath> = Array(NUM_TRAILS) { generateTrailFromZone(it) }

    /**
     * 6 dedicated zones around the screen perimeter — guarantees well-separated origins.
     * 0: top-left, 1: top-right, 2: right, 3: bottom-right, 4: bottom-left, 5: left
     */
    private fun generateTrailFromZone(zone: Int): TrailPath {
        val spawnScreenX: Float; val spawnScreenY: Float
        when (zone) {
            0 -> { spawnScreenX = 0.05f + Random.nextFloat() * 0.30f; spawnScreenY = -0.15f }
            1 -> { spawnScreenX = 0.65f + Random.nextFloat() * 0.30f; spawnScreenY = -0.15f }
            2 -> { spawnScreenX = 1.15f; spawnScreenY = 0.25f + Random.nextFloat() * 0.50f }
            3 -> { spawnScreenX = 0.65f + Random.nextFloat() * 0.30f; spawnScreenY = 1.15f }
            4 -> { spawnScreenX = 0.05f + Random.nextFloat() * 0.30f; spawnScreenY = 1.15f }
            else -> { spawnScreenX = -0.15f; spawnScreenY = 0.25f + Random.nextFloat() * 0.50f }
        }
        return buildTrailPath(spawnScreenX, spawnScreenY)
    }

    private fun buildTrailPath(ssx: Float, ssy: Float): TrailPath {
        // Inverse iso: screen-normalized → grid-normalized (ground level, gy=0)
        val u = (ssx - ISO_CENTER_X) / (ISO_COS * CUBE_SIZE)
        val v = (ssy - ISO_CENTER_Y) / (ISO_SIN * CUBE_SIZE)
        val cx = (u + v) / 2f
        val cz = (v - u) / 2f
        val spawnX = (cx + GRID_W / 2f) / GRID_W
        val spawnZ = (cz + GRID_D / 2f) / GRID_D

        // Direction toward mound center (grid center)
        val dx = 0.5f - spawnX; val dz = 0.5f - spawnZ
        val len = sqrt(dx * dx + dz * dz).coerceAtLeast(0.001f)

        return TrailPath(
            anchorX = spawnX, anchorZ = spawnZ,
            baseDirX = dx / len, baseDirZ = dz / len,
            baseLength = len,
        )
    }

    // ─── Trail update (curl noise flow field) ─────────────────────────

    /** Rebuild all trail polylines by integrating through the curl noise flow field. */
    private fun updateTrails(dt: Float) {
        // Advance flow time — base drift + music-driven acceleration + transient bursts
        val driftRate = FLOW_BASE_DRIFT + smoothedMaster * FLOW_MASTER_DRIFT + transientBoost
        flowTime += driftRate * dt

        // Decay transient boost
        transientBoost *= (1f - FLOW_TRANSIENT_DECAY * dt).coerceAtLeast(0f)

        // Detect peak transients (sudden loudness spikes)
        if (smoothedPeak > smoothedMaster + 0.15f) {
            transientBoost = FLOW_TRANSIENT_BOOST
        }

        // Compute global parameters
        val turbulence = FLOW_BASE_TURBULENCE +
                smoothedMaster * (FLOW_MAX_TURBULENCE - FLOW_BASE_TURBULENCE)
        val lfoBaseX = smoothedLfoA * FLOW_LFO_BIAS
        val lfoBaseZ = smoothedLfoB * FLOW_LFO_BIAS

        // Blend rate: how fast old polyline converges to new (higher = less smoothing)
        val blend = (FLOW_BLEND_RATE * dt).coerceAtMost(1f)

        for (trail in trails) {
            // Save current polyline as "previous" before rebuilding
            trail.xs.copyInto(trail.prevXs)
            trail.zs.copyInto(trail.prevZs)

            // Forward-integrate new polyline from anchor through the flow field.
            // Use a uniform step size so all trails (near and far) get the same
            // curl noise resolution. Long trails (top zones) just have more of
            // their polyline off-screen, which is fine — ants are invisible there.
            val stepSize = FLOW_STEP_SCALE / TRAIL_POINTS
            var x = trail.anchorX
            var z = trail.anchorZ
            trail.xs[0] = x
            trail.zs[0] = z

            for (i in 1 until TRAIL_POINTS) {
                val duoTurb = duoSectorTurbulence(x, z)
                curlNoise(x, z, turbulence, duoTurb, lfoBaseX, lfoBaseZ, curlResult)

                // Directional drift along trail's base direction (toward mound, not a single point)
                // Strength scaled by baseLength so longer trails drift proportionally faster
                val driftX = trail.baseDirX * FLOW_ATTRACTOR_STRENGTH * trail.baseLength
                val driftZ = trail.baseDirZ * FLOW_ATTRACTOR_STRENGTH * trail.baseLength

                x += (curlResult[0] + driftX) * stepSize
                z += (curlResult[1] + driftZ) * stepSize

                // Blend new position with previous frame for smooth morphing
                // Points near anchor (low i) need less blending, points near mound (high i) need more
                val pointBlend = blend * (1f - i.toFloat() / TRAIL_POINTS * 0.5f)
                trail.xs[i] = trail.prevXs[i] + (x - trail.prevXs[i]) * pointBlend
                trail.zs[i] = trail.prevZs[i] + (z - trail.prevZs[i]) * pointBlend

                // Feed blended position back into integration so next step is coherent
                x = trail.xs[i]
                z = trail.zs[i]
            }

            // Compute actual polyline arc length for correct ant speed
            var arcLen = 0f
            for (i in 1 until TRAIL_POINTS) {
                val dx = trail.xs[i] - trail.xs[i - 1]
                val dz = trail.zs[i] - trail.zs[i - 1]
                arcLen += sqrt(dx * dx + dz * dz)
            }
            trail.arcLength = arcLen.coerceAtLeast(0.01f)
        }
    }

    /**
     * Compute curl noise velocity at (x, z) in grid-normalized space.
     * Writes result to out[0]=vx, out[1]=vz (divergence-free velocity vector).
     */
    private fun curlNoise(
        x: Float, z: Float,
        turbulence: Float,
        duoTurbulence: Float,
        lfoBaseX: Float, lfoBaseZ: Float,
        out: FloatArray,
    ) {
        val freq = turbulence + duoTurbulence
        val e = FLOW_EPSILON
        val nx = x * freq
        val nz = z * freq

        // Curl: perpendicular gradient of scalar potential
        out[0] = (SimplexNoise.noise(nx, nz + e, flowTime) -
                  SimplexNoise.noise(nx, nz - e, flowTime)) / (2f * e) + lfoBaseX
        out[1] = -(SimplexNoise.noise(nx + e, nz, flowTime) -
                   SimplexNoise.noise(nx - e, nz, flowTime)) / (2f * e) + lfoBaseZ
    }

    /**
     * Get extra turbulence contribution from the duo sector at (x, z).
     * Grid is divided into 6 angular sectors from center, one per duo.
     */
    private fun duoSectorTurbulence(x: Float, z: Float): Float {
        val dx = x - 0.5f
        val dz = z - 0.5f
        val angle = (atan2(dz, dx) + PI_F) // 0..2π

        var totalTurbulence = 0f
        for (duo in 0 until 6) {
            val sectorCenter = duo * SECTOR_WIDTH + SECTOR_WIDTH * 0.5f
            var diff = angle - sectorCenter
            if (diff > PI_F) diff -= TWO_PI_F
            else if (diff < -PI_F) diff += TWO_PI_F
            if (abs(diff) < SECTOR_WIDTH) {
                val blend = cos(diff / SECTOR_WIDTH * HALF_PI_F)
                totalTurbulence += duoLevels[duo] * blend * blend * FLOW_DUO_SECTOR_STRENGTH
            }
        }
        return totalTurbulence
    }

    /** Interpolate (x, z) along a trail polyline at the given progress (0..TRAIL_POINTS-1).
     *  Writes to out[0]=x, out[1]=z to avoid Pair allocation. */
    private fun trailPositionAt(trail: TrailPath, progress: Float, out: FloatArray) {
        val idx = progress.toInt().coerceIn(0, TRAIL_POINTS - 2)
        val frac = progress - idx
        out[0] = trail.xs[idx] + (trail.xs[idx + 1] - trail.xs[idx]) * frac
        out[1] = trail.zs[idx] + (trail.zs[idx + 1] - trail.zs[idx]) * frac
    }

    /** Get the trail direction (dx, dz) at the given progress for heading computation. */
    private fun trailHeadingAt(trail: TrailPath, progress: Float, reversed: Boolean = false): Float {
        val idx = progress.toInt().coerceIn(0, TRAIL_POINTS - 2)
        val dx = trail.xs[idx + 1] - trail.xs[idx]
        val dz = trail.zs[idx + 1] - trail.zs[idx]
        return if (reversed) atan2(-dz, -dx) else atan2(dz, dx)
    }

    // ─── Ant spawning ───────────────────────────────────────────────────

    private fun spawnAnt(voiceLevels: FloatArray, maxVoiceLevel: Float) {
        var ant: Ant? = null
        for (a in ants) { if (!a.active) { ant = a; break } }
        if (ant == null) return

        val trailIdx = nextTrailIndex % trails.size
        nextTrailIndex = (nextTrailIndex + 1) % trails.size
        val trail = trails[trailIdx]

        // Check min spacing against same-trail ants near spawn
        for (other in ants) {
            if (!other.active || other === ant) continue
            if (other.trailIndex != trailIdx) continue
            val odx = other.x - trail.anchorX
            val odz = other.z - trail.anchorZ
            if (odx * odx + odz * odz < ANT_MIN_SPAWN_DIST_SQ) return
        }

        ant.trailIndex = trailIdx
        ant.x = trail.anchorX
        ant.z = trail.anchorZ
        ant.reachedWaypoint = false
        ant.pathProgress = 0f

        findBuildTarget(ant)

        ant.colorIndex = pickWeightedDuoColor(voiceLevels) + 1
        ant.state = AntState.WALKING_TO_MOUND
        val baseSpeed = ANT_BASE_SPEED + smoothedMaster * ANT_MASTER_SPEED_BOOST + smoothedPeak * ANT_PEAK_SPEED_BOOST + maxVoiceLevel * ANT_VOICE_SPEED_BOOST
        ant.speed = baseSpeed * (1f + (Random.nextFloat() - 0.5f) * ANT_SPEED_JITTER)
        ant.depositTimer = 0f
        ant.aliveTime = 0f
        ant.headingAngle = atan2(ISO_CENTER_X - ant.z, ISO_CENTER_X - ant.x)
        ant.active = true
    }

    /**
     * Asymmetric mountain profile using overlapping Gaussian peaks.
     * Returns the max allowed height (float) at grid position (gx, gz).
     */
    private fun mountainMaxHeight(gx: Int, gz: Int): Float {
        val nx = gx.toFloat() / GRID_W  // 0..1 normalized
        val nz = gz.toFloat() / GRID_D

        // Main peak — off-center, tall
        val dx0 = nx - PEAK0_X
        val dz0 = nz - PEAK0_Z
        val peak0 = GRID_H * PEAK0_HEIGHT * exp(-(dx0 * dx0 + dz0 * dz0) / PEAK0_WIDTH)

        // Shoulder ridge — shorter + wider
        val dx1 = nx - PEAK1_X
        val dz1 = nz - PEAK1_Z
        val peak1 = GRID_H * PEAK1_HEIGHT * exp(-(dx1 * dx1 + dz1 * dz1) / PEAK1_WIDTH)

        // Second shoulder — medium height
        val dx2 = nx - PEAK2_X
        val dz2 = nz - PEAK2_Z
        val peak2 = GRID_H * PEAK2_HEIGHT * exp(-(dx2 * dx2 + dz2 * dz2) / PEAK2_WIDTH)

        return max(max(peak0, peak1), peak2)
    }

    private fun findBuildTarget(ant: Ant) {
        val centerX = GRID_W / 2
        val centerZ = GRID_D / 2

        var bestX = centerX
        var bestZ = centerZ
        var bestY = 0
        var bestScore = Float.MAX_VALUE
        var candidatesChecked = 0

        for (r in 0..GRID_W / 2) {
            for (dx in -r..r) {
                for (dz in -r..r) {
                    if (abs(dx) != r && abs(dz) != r) continue
                    val gx = centerX + dx
                    val gz = centerZ + dz
                    if (gx !in 0 until GRID_W || gz !in 0 until GRID_D) continue

                    val topY = grid.topHeight(gx, gz)
                    val placeY = topY + 1
                    if (placeY >= GRID_H) continue
                    if (!grid.canPlace(gx, placeY, gz)) continue

                    // Hard-reject placements above the mountain profile
                    val maxH = mountainMaxHeight(gx, gz)
                    if (placeY > maxH) continue

                    // Lateral accretion: count horizontal neighbors that have blocks
                    var neighbors = 0
                    if (gx > 0 && grid.topHeight(gx - 1, gz) >= placeY - 1) neighbors++
                    if (gx < GRID_W - 1 && grid.topHeight(gx + 1, gz) >= placeY - 1) neighbors++
                    if (gz > 0 && grid.topHeight(gx, gz - 1) >= placeY - 1) neighbors++
                    if (gz < GRID_D - 1 && grid.topHeight(gx, gz + 1) >= placeY - 1) neighbors++

                    // Scoring: prefer ground level, lateral neighbors, and spreading out
                    val heightPenalty = placeY * BUILD_HEIGHT_PENALTY
                    val neighborBonus = neighbors * BUILD_NEIGHBOR_BONUS
                    val radialDist = sqrt((dx * dx + dz * dz).toFloat())
                    val noise = Random.nextFloat() * BUILD_NOISE_RANGE
                    val score = heightPenalty - neighborBonus + radialDist * BUILD_RADIAL_WEIGHT + noise

                    if (score < bestScore) {
                        bestScore = score
                        bestX = gx
                        bestZ = gz
                        bestY = placeY
                    }
                    candidatesChecked++
                }
            }
            if (candidatesChecked > BUILD_MIN_CANDIDATES && bestScore < Float.MAX_VALUE) break
        }

        ant.targetX = bestX
        ant.targetZ = bestZ
        ant.targetY = bestY
    }


    /** Move a fleeing ant outward from center and deactivate when off-screen. */
    private fun updateFleeingAnt(ant: Ant, dt: Float) {
        val dx = ant.x - ISO_CENTER_X
        val dz = ant.z - ISO_CENTER_X
        val dist = sqrt(dx * dx + dz * dz).coerceAtLeast(0.01f)
        ant.x += (dx / dist) * ant.speed * ANT_FLEE_MULTIPLIER * dt
        ant.z += (dz / dist) * ant.speed * ANT_FLEE_MULTIPLIER * dt
        ant.headingAngle = atan2(dz, dx)

        val gx = ant.x * GRID_W
        val gz = ant.z * GRID_D
        isoProjectInto(gx, 0f, gz, ISO_CENTER_X, ISO_CENTER_Y, isoScratch)
        if (isoScratch[0] < SCREEN_MIN_X || isoScratch[0] > SCREEN_MAX_X ||
            isoScratch[1] < SCREEN_MIN_Y || isoScratch[1] > SCREEN_MAX_Y) {
            ant.active = false
        }
    }

    private fun updateAnts(maxVoiceLevel: Float, dt: Float) {
        for (ant in ants) {
            if (!ant.active) continue

            ant.aliveTime += dt
            // Safety timeout: deactivate ants that have been alive too long (stuck)
            if (ant.aliveTime > ANT_ALIVE_TIMEOUT) { ant.active = false; continue }

            // Voice energy modulates ant speed
            ant.speed = ANT_BASE_SPEED + smoothedMaster * ANT_MASTER_SPEED_BOOST + smoothedPeak * ANT_PEAK_SPEED_BOOST + maxVoiceLevel * ANT_VOICE_SPEED_BOOST

            when (ant.state) {
                AntState.WALKING_TO_MOUND -> {
                    val trail = trails[ant.trailIndex]

                    if (!ant.reachedWaypoint) {
                        // Phase 1: Follow trail polyline
                        val progressSpeed = ant.speed * TRAIL_POINTS / trail.arcLength
                        ant.pathProgress += progressSpeed * dt

                        if (ant.pathProgress >= TRAIL_POINTS - 1f) {
                            // Reached end of trail polyline — switch to direct walk
                            ant.reachedWaypoint = true
                            trailPositionAt(trail, (TRAIL_POINTS - 1).toFloat(), trailPosResult)
                            ant.x = trailPosResult[0]; ant.z = trailPosResult[1]
                        } else {
                            trailPositionAt(trail, ant.pathProgress, trailPosResult)
                            ant.x = trailPosResult[0]; ant.z = trailPosResult[1]
                            ant.headingAngle = trailHeadingAt(trail, ant.pathProgress)
                        }
                    } else {
                        // Phase 2: Direct walk to build target
                        val goalX = (ant.targetX + 0.5f) / GRID_W
                        val goalZ = (ant.targetZ + 0.5f) / GRID_D
                        val dx = goalX - ant.x; val dz = goalZ - ant.z
                        val dist = sqrt(dx * dx + dz * dz)

                        if (dist < TARGET_ARRIVE_DIST) {
                            ant.state = AntState.DEPOSITING
                            ant.depositTimer = ANT_DEPOSIT_TIME
                        } else {
                            val move = ant.speed * dt / dist
                            ant.x += dx * move; ant.z += dz * move
                            if (dx * dx + dz * dz > 1e-8f) {
                                ant.headingAngle = atan2(dz, dx)
                            }
                        }
                    }
                }
                AntState.DEPOSITING -> {
                    ant.depositTimer -= dt
                    if (ant.depositTimer <= 0f) {
                        // Place primary block
                        if (grid.canPlace(ant.targetX, ant.targetY, ant.targetZ)) {
                            grid.set(ant.targetX, ant.targetY, ant.targetZ, ant.colorIndex)
                        }
                        // Cluster: attempt 1-3 extra blocks in adjacent cells
                        val extras = 1 + Random.nextInt(3)
                        repeat(extras) {
                            val oi = (Random.nextInt(4)) * 2
                            val nx = ant.targetX + clusterOffsets[oi]
                            val nz = ant.targetZ + clusterOffsets[oi + 1]
                            if (nx !in 0 until GRID_W || nz !in 0 until GRID_D) return@repeat
                            val ny = grid.topHeight(nx, nz) + 1
                            if (ny >= GRID_H) return@repeat
                            if (!grid.canPlace(nx, ny, nz)) return@repeat
                            if (ny > mountainMaxHeight(nx, nz)) return@repeat
                            grid.set(nx, ny, nz, ant.colorIndex)
                        }
                        // Pick a random exit trail (outbound lane)
                        ant.exitTrailIndex = Random.nextInt(trails.size)
                        ant.reachedWaypoint = false
                        ant.pathProgress = (TRAIL_POINTS - 1).toFloat()
                        ant.aliveTime = 0f  // Reset timeout so outbound walk gets full budget
                        ant.state = AntState.WALKING_AWAY
                    }
                }
                AntState.WALKING_AWAY -> {
                    val exitTrail = trails[ant.exitTrailIndex]

                    if (!ant.reachedWaypoint) {
                        // Phase 1: Walk from deposit location to trail end point
                        val trailEndX = exitTrail.xs[TRAIL_POINTS - 1]
                        val trailEndZ = exitTrail.zs[TRAIL_POINTS - 1]
                        val dx = trailEndX - ant.x; val dz = trailEndZ - ant.z
                        val dist = sqrt(dx * dx + dz * dz)

                        if (dist < EXIT_WAYPOINT_DIST) {
                            ant.reachedWaypoint = true
                            ant.pathProgress = (TRAIL_POINTS - 1).toFloat()
                        } else {
                            val move = ant.speed * dt / dist
                            ant.x += dx * move; ant.z += dz * move
                            if (dx * dx + dz * dz > 1e-8f) {
                                ant.headingAngle = atan2(dz, dx)
                            }
                        }
                    } else {
                        // Phase 2: Follow exit trail polyline in reverse
                        val progressSpeed = ant.speed * TRAIL_POINTS / exitTrail.arcLength
                        ant.pathProgress -= progressSpeed * dt

                        if (ant.pathProgress <= 0f) {
                            ant.active = false
                        } else {
                            trailPositionAt(exitTrail, ant.pathProgress, trailPosResult)
                            ant.x = trailPosResult[0]; ant.z = trailPosResult[1]
                            ant.headingAngle = trailHeadingAt(exitTrail, ant.pathProgress, reversed = true)
                        }
                    }

                    // Deactivate when off-screen in iso screen space
                    val gx = ant.x * GRID_W
                    val gz = ant.z * GRID_D
                    isoProjectInto(gx, 0f, gz, ISO_CENTER_X, ISO_CENTER_Y, isoScratch)
                    if (isoScratch[0] !in SCREEN_MIN_X..SCREEN_MAX_X ||
                        isoScratch[1] < SCREEN_MIN_Y || isoScratch[1] > SCREEN_MAX_Y) {
                        ant.active = false
                    }
                }
                AntState.FLEEING -> updateFleeingAnt(ant, dt)
            }
        }
    }

    /**
     * Weighted random duo selection: probability proportional to duo loudness.
     * Returns a voice index (0-11) suitable for colorIndex = result + 1.
     */
    private fun pickWeightedDuoColor(voiceLevels: FloatArray): Int {
        // Compute loudness per duo (6 duos from 12 voices)
        val numDuos = minOf(6, voiceLevels.size / 2)
        var totalLevel = 0f
        for (d in 0 until numDuos) {
            val a = voiceLevels.getOrElse(d * 2) { 0f }
            val b = voiceLevels.getOrElse(d * 2 + 1) { 0f }
            totalLevel += a + b
        }

        if (totalLevel < 0.001f) {
            // All silent — round-robin fallback
            val duo = nextTrailIndex % numDuos
            return duo * 2
        }

        // Weighted random pick
        var roll = Random.nextFloat() * totalLevel
        for (d in 0 until numDuos) {
            val a = voiceLevels.getOrElse(d * 2) { 0f }
            val b = voiceLevels.getOrElse(d * 2 + 1) { 0f }
            roll -= (a + b)
            if (roll <= 0f) return d * 2
        }
        return 0 // fallback
    }

    // ============================================================================
    // Destruction
    // ============================================================================

    private fun startDestruction() {
        phase = VizPhase.DESTRUCTION
        destructionType = selectedDestructionType
        destructionProgress = 0f

        for (ant in ants) {
            if (ant.active && ant.state != AntState.FLEEING) {
                ant.state = AntState.FLEEING
                ant.speed = ANT_FLEE_SPEED
            }
        }

        when (destructionType) {
            DestructionType.STOMP -> {
                stompY = -0.3f
                stompPhase = 0
                destructionDuration = STOMP_DURATION
            }
            DestructionType.FIRE_ANT_WAR -> {
                fireAntProgress = 0f
                fireAntSeed = Random.nextLong()
                destructionDuration = FIRE_ANT_DURATION
            }
            DestructionType.MAGNIFYING_GLASS -> {
                magGlassAngle = 0f
                destructionDuration = MAG_GLASS_DURATION
            }
        }
    }

    private fun updateDestruction(dt: Float) {
        destructionProgress += dt / destructionDuration

        // Update fleeing ants
        for (ant in ants) {
            if (!ant.active || ant.state != AntState.FLEEING) continue
            updateFleeingAnt(ant, dt)
        }

        // Update debris
        for (d in debris) {
            if (!d.active) continue
            d.life -= d.decay * dt
            if (d.life <= 0f) { d.active = false; continue }
            d.vy += 0.5f * dt
            d.x += d.vx * dt
            d.y += d.vy * dt
            d.z += d.vz * dt
        }

        when (destructionType) {
            DestructionType.STOMP -> updateStomp()
            DestructionType.FIRE_ANT_WAR -> updateFireAntWar()
            DestructionType.MAGNIFYING_GLASS -> updateMagnifyingGlass(dt)
        }

        if (destructionProgress >= 1f) {
            finishDestruction()
        }
    }

    private fun updateStomp() {
        val t = destructionProgress
        when {
            t < 0.3f -> {
                stompPhase = 0
                stompY = -0.3f + t / 0.3f * 0.75f
            }
            t < 0.4f -> {
                if (stompPhase == 0) {
                    stompPhase = 1
                    explodeAllBlocks()
                }
                stompY = 0.45f
            }
            else -> {
                stompPhase = 2
                stompY = 0.45f - (t - 0.4f) / 0.6f * 0.8f
            }
        }
    }

    private fun updateFireAntWar() {
        fireAntProgress = destructionProgress
        val removeRadius = (1f - fireAntProgress) * GRID_W / 2f
        val removeRadiusSq = removeRadius * removeRadius
        val cx = GRID_W / 2
        val cz = GRID_D / 2

        for (x in 0 until GRID_W) {
            for (z in 0 until GRID_D) {
                val dx = (x - cx).toFloat()
                val dz = (z - cz).toFloat()
                if (dx * dx + dz * dz > removeRadiusSq) {
                    for (y in 0 until GRID_H) {
                        val colorIdx = grid.get(x, y, z)
                        if (colorIdx == 0) continue
                        if (colorIdx == FIRE_COLOR_INDEX) {
                            spawnDebris(x, y, z, colorIdx, isFireAnt = true)
                            grid.set(x, y, z, 0)
                        } else {
                            grid.set(x, y, z, FIRE_COLOR_INDEX)
                        }
                    }
                }
            }
        }
    }

    private fun updateMagnifyingGlass(dt: Float) {
        magGlassAngle += dt * 1.5f
        val sweep = destructionProgress
        val radius = GRID_W / 2f * (1f - sweep * 0.5f)
        val fx = GRID_W / 2f + cos(magGlassAngle) * radius * sweep
        val fz = GRID_D / 2f + sin(magGlassAngle) * radius * sweep
        magGlassCenterX = fx / GRID_W
        magGlassCenterY = fz / GRID_D

        val burnRadius = 2f + sweep * 3f
        val burnRadiusSq = burnRadius * burnRadius
        for (x in 0 until GRID_W) {
            for (z in 0 until GRID_D) {
                val dx = x - fx
                val dz = z - fz
                if (dx * dx + dz * dz < burnRadiusSq) {
                    for (y in GRID_H - 1 downTo 0) {
                        if (grid.get(x, y, z) != 0) {
                            spawnDebris(x, y, z, grid.get(x, y, z), isFireAnt = false)
                            grid.set(x, y, z, 0)
                        }
                    }
                }
            }
        }
    }

    private fun explodeAllBlocks() {
        for (x in 0 until GRID_W) {
            for (z in 0 until GRID_D) {
                for (y in 0 until GRID_H) {
                    val colorIdx = grid.get(x, y, z)
                    if (colorIdx != 0) {
                        spawnDebris(x, y, z, colorIdx, isFireAnt = false)
                        grid.set(x, y, z, 0)
                    }
                }
            }
        }
    }

    private fun spawnDebris(gx: Int, gy: Int, gz: Int, colorIndex: Int, isFireAnt: Boolean) {
        var d: Debris? = null
        for (item in debris) { if (!item.active) { d = item; break } }
        if (d == null) return
        val dx = (gx - GRID_W / 2f) / GRID_W
        val dz = (gz - GRID_D / 2f) / GRID_D

        d.x = (gx + 0.5f) / GRID_W
        d.y = gy.toFloat() / GRID_H
        d.z = (gz + 0.5f) / GRID_D
        d.vx = dx * 2f + (Random.nextFloat() - 0.5f) * 0.3f
        d.vy = -(0.2f + Random.nextFloat() * 0.4f)
        d.vz = dz * 2f + (Random.nextFloat() - 0.5f) * 0.3f
        d.colorIndex = if (isFireAnt) FIRE_COLOR_INDEX else colorIndex
        d.life = 1f
        d.decay = 0.4f + Random.nextFloat() * 0.3f
        d.size = 0.004f + Random.nextFloat() * 0.004f
        d.active = true
    }

    private fun finishDestruction() {
        phase = VizPhase.BUILDING
        destructionProgress = 0f
        grid.clear()
        for (ant in ants) { ant.active = false }
        for (d in debris) { d.active = false }
        antSpawnAccum = 0f
        trails = generateTrails()  // New random trail origins each cycle
    }

    companion object {
        const val MAX_MOUND_BLOCKS = (GRID_W * GRID_D * GRID_H * 0.35f).toInt()

        // Match BlackHoleSunViz glass effect — see-through with low frost
        val DefaultEffects = VisualizationLiquidEffects(
            frostSmall = 1f,
            frostMedium = 1f,
            frostLarge = 1f,
            tintAlpha = 0.02f,
            top = VisualizationLiquidScope(
                dispersion = 0f,
                curve = 0f,
                refraction = 0f,
                contrast = .85f,
            ),
            bottom = VisualizationLiquidScope(
                dispersion = 0f,
                curve = 0f,
                refraction = 0f,
            ),
            title = CenterPanelStyle(
                scope = VisualizationLiquidScope(
                    saturation = 3f,
                    dispersion = 1.2f,
                    curve = 0.25f,
                    refraction = 0.8f,
                    contrast = 0.9f,
                ),
                titleColor = OrpheusColors.antNeonCyan,
                borderColor = OrpheusColors.antNeonCyan.copy(alpha = 0.4f),
                borderWidth = 2.dp,
                titleElevation = 2.dp,
            ),
        )
    }
}
