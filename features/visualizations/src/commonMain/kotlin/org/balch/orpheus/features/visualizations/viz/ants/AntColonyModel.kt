package org.balch.orpheus.features.visualizations.viz.ants

import androidx.compose.ui.geometry.Offset

// --------------------------------------------------------------------------------
// Constants
// --------------------------------------------------------------------------------

// Grid dimensions
internal const val GRID_W = 24
internal const val GRID_D = 24
internal const val GRID_H = 16

// Population limits
internal const val MAX_ANTS = 400
internal const val MAX_DEBRIS = 200

// Trail spacing
internal const val ANT_SPEED_JITTER = 0.15f       // ±15% random speed variation for natural spacing
internal const val ANT_MIN_SPAWN_DIST_SQ = 0.004f  // Min squared distance between ants at spawn

// Isometric projection
internal const val ISO_COS = 0.866025f       // cos(30°)
internal const val ISO_SIN = 0.5f            // sin(30°)
internal const val CUBE_SIZE = 0.022f
internal const val ISO_CENTER_X = 0.5f       // Normalized screen center X
internal const val ISO_CENTER_Y = 0.65f      // Normalized screen center Y

// Off-screen detection thresholds (iso screen space)
internal const val SCREEN_MIN_X = -0.1f
internal const val SCREEN_MAX_X = 1.1f
internal const val SCREEN_MIN_Y = -0.05f
internal const val SCREEN_MAX_Y = 1.05f

// Ant movement
internal const val ANT_BASE_SPEED = 0.126f         // Grid-normalized units/sec at silence
internal const val ANT_MASTER_SPEED_BOOST = 0.081f  // Extra speed from master level
internal const val ANT_PEAK_SPEED_BOOST = 0.108f    // Extra speed from peak master volume
internal const val ANT_VOICE_SPEED_BOOST = 0.045f   // Extra speed from loudest voice
internal const val ANT_ALIVE_TIMEOUT = 12f           // Seconds before stuck ant is recycled
internal const val ANT_DEPOSIT_TIME = 0.15f          // Seconds to place a block
internal const val ANT_FLEE_SPEED = 0.6f             // Speed when fleeing destruction
internal const val ANT_FLEE_MULTIPLIER = 2f          // Movement multiplier for fleeing
internal const val TARGET_ARRIVE_DIST = 0.02f        // Grid dist to snap to build target
internal const val EXIT_WAYPOINT_DIST = 0.08f        // Grid dist to snap to exit waypoint (trail end)

// Trail polyline
internal const val TRAIL_POINTS = 48                  // Points per trail polyline

// Curl noise flow field
internal const val FLOW_BASE_TURBULENCE = 0.6f       // Noise frequency at silence (low = large lazy eddies)
internal const val FLOW_MAX_TURBULENCE = 2.5f        // Noise frequency at full volume
internal const val FLOW_BASE_DRIFT = 0.03f           // Time advancement per sec at silence
internal const val FLOW_MASTER_DRIFT = 0.08f         // Extra drift per sec at full volume
internal const val FLOW_TRANSIENT_BOOST = 0.15f      // Peak transient drift burst
internal const val FLOW_TRANSIENT_DECAY = 3f         // Exponential decay rate for transients
internal const val FLOW_LFO_BIAS = 0.06f             // LFO global current strength
internal const val FLOW_DUO_SECTOR_STRENGTH = 1.0f   // Extra turbulence from loud duo sector
internal const val FLOW_STEP_SCALE = 0.18f           // Velocity scaling for trail integration steps
internal const val FLOW_EPSILON = 0.12f              // Finite difference epsilon (larger = smoother gradients)
internal const val FLOW_ATTRACTOR_STRENGTH = 0.5f    // Pull toward mound center (keeps trails on-screen)
internal const val FLOW_BLEND_RATE = 3f              // Polyline blend speed (old→new per sec)

// Smoothing rates (higher = faster response)
internal const val SMOOTH_MASTER_RATE = 3f
internal const val SMOOTH_LFO_RATE = 5f
internal const val SMOOTH_LFO_AB_RATE = 8f
internal const val SMOOTH_DUO_RATE = 4f

// Spawn rate
internal const val ANT_SPAWN_RATE = 25f     // Max ants/sec at full master volume
internal const val NUM_TRAILS = 6            // Number of approach trails from edges

// Build target scoring weights
internal const val BUILD_HEIGHT_PENALTY = 3f      // Penalize stacking
internal const val BUILD_NEIGHBOR_BONUS = 4f      // Reward lateral accretion
internal const val BUILD_RADIAL_WEIGHT = 0.4f     // Small radial spread factor
internal const val BUILD_NOISE_RANGE = 5f         // Random noise range for variety
internal const val BUILD_MIN_CANDIDATES = 40      // Min candidates before early-exit

// Mountain profile peaks: (centerX, centerZ, heightFraction, widthSigma)
internal const val PEAK0_X = 0.45f; internal const val PEAK0_Z = 0.48f
internal const val PEAK0_HEIGHT = 0.95f; internal const val PEAK0_WIDTH = 0.045f
internal const val PEAK1_X = 0.60f; internal const val PEAK1_Z = 0.35f
internal const val PEAK1_HEIGHT = 0.55f; internal const val PEAK1_WIDTH = 0.06f
internal const val PEAK2_X = 0.35f; internal const val PEAK2_Z = 0.65f
internal const val PEAK2_HEIGHT = 0.45f; internal const val PEAK2_WIDTH = 0.05f

// Blob rendering
internal const val BLOB_BASE_SIZE_FACTOR = 0.6f   // baseSize = CUBE_SIZE * w * this
internal const val BLOB_SURFACE_ALPHA = 0.15f      // Base alpha for surface blobs
internal const val BLOB_INTERIOR_ALPHA = 0.08f     // Base alpha for interior blobs
internal const val BLOB_GLOW_EXPAND = 1.8f         // Surface glow radius multiplier
internal const val BLOB_GLOW_ENERGY_SCALE = 1.2f   // Extra glow from duo energy

// Color indices
internal const val FIRE_COLOR_INDEX = 13

// Destruction durations
internal const val STOMP_DURATION = 3.5f
internal const val FIRE_ANT_DURATION = 4f
internal const val MAG_GLASS_DURATION = 4.5f

// Ant drawing dimensions (pixels)
internal const val ANT_SEG_RADIUS = 5f        // Base segment radius
internal const val ANT_SEG_GAP = 3.5f         // Gap between segments
internal const val ANT_LEG_LENGTH = 8f        // Total leg length
internal const val ANT_GLOW_RADIUS = 18f      // Ambient glow circle

// --------------------------------------------------------------------------------
// Data Structures
// --------------------------------------------------------------------------------

internal class Grid {
    val cells = IntArray(GRID_W * GRID_D * GRID_H)
    var blockCount = 0
        private set

    fun get(x: Int, y: Int, z: Int): Int {
        if (x !in 0 until GRID_W || y !in 0 until GRID_H || z !in 0 until GRID_D) return 0
        return cells[x + z * GRID_W + y * GRID_W * GRID_D]
    }

    fun set(x: Int, y: Int, z: Int, value: Int) {
        if (x !in 0 until GRID_W || y !in 0 until GRID_H || z !in 0 until GRID_D) return
        val idx = x + z * GRID_W + y * GRID_W * GRID_D
        val old = cells[idx]
        cells[idx] = value
        if (old == 0 && value != 0) blockCount++
        else if (old != 0 && value == 0) blockCount--
    }

    fun clear() {
        cells.fill(0)
        blockCount = 0
    }

    fun topHeight(x: Int, z: Int): Int {
        for (y in GRID_H - 1 downTo 0) {
            if (get(x, y, z) != 0) return y
        }
        return -1
    }

    fun canPlace(x: Int, y: Int, z: Int): Boolean {
        if (get(x, y, z) != 0) return false
        if (y == 0) return true
        if (get(x, y - 1, z) == 0) return false
        return true
    }
}

internal enum class AntState { WALKING_TO_MOUND, DEPOSITING, WALKING_AWAY, FLEEING }

// Trail: polyline generated by integrating through the curl noise flow field.
// Each frame, the polyline is rebuilt from the anchor by stepping through the field,
// then blended with the previous frame's polyline for smooth morphing.
internal class TrailPath(
    val anchorX: Float, val anchorZ: Float,       // Fixed spawn point (off-screen)
    val baseDirX: Float, val baseDirZ: Float,     // Base direction toward mound
    val baseLength: Float,                         // Straight-line distance to mound center
) {
    val xs = FloatArray(TRAIL_POINTS)
    val zs = FloatArray(TRAIL_POINTS)
    val prevXs = FloatArray(TRAIL_POINTS)         // Previous frame for blending
    val prevZs = FloatArray(TRAIL_POINTS)
    var arcLength = 0f                             // Actual polyline length (recomputed each frame)
}

internal class Ant(
    var x: Float = 0f,
    var z: Float = 0f,
    var targetX: Int = 0,
    var targetZ: Int = 0,
    var targetY: Int = 0,
    var state: AntState = AntState.WALKING_TO_MOUND,
    var speed: Float = 0.15f,
    var colorIndex: Int = 1,
    var active: Boolean = false,
    var depositTimer: Float = 0f,
    var headingAngle: Float = 0f,  // Direction ant is facing (radians)
    var trailIndex: Int = 0,       // Which trail this ant follows (0-5)
    var reachedWaypoint: Boolean = false,  // On trail polyline (false) or direct-walking (true)?
    var exitTrailIndex: Int = 0,   // Trail to exit on (randomly chosen at deposit)
    var aliveTime: Float = 0f,     // Seconds since activation (safety timeout)
    var pathProgress: Float = 0f,  // Position along trail polyline (0..TRAIL_POINTS-1)
)

internal class Debris(
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var vz: Float = 0f,
    var colorIndex: Int = 1,
    var life: Float = 1f,
    var decay: Float = 0.5f,
    var size: Float = 0.005f,
    var active: Boolean = false,
)

internal enum class VizPhase { BUILDING, DESTRUCTION }
internal enum class DestructionType { STOMP, FIRE_ANT_WAR, MAGNIFYING_GLASS }

internal fun isoProject(gx: Float, gy: Float, gz: Float, centerX: Float, centerY: Float): Offset {
    val cx = gx - GRID_W / 2f
    val cz = gz - GRID_D / 2f
    val screenX = (cx - cz) * ISO_COS * CUBE_SIZE + centerX
    val screenY = (cx + cz) * ISO_SIN * CUBE_SIZE - gy * CUBE_SIZE + centerY
    return Offset(screenX, screenY)
}

/** Allocation-free variant for hot loops. Writes screenX to out[0], screenY to out[1]. */
internal fun isoProjectInto(gx: Float, gy: Float, gz: Float, centerX: Float, centerY: Float, out: FloatArray) {
    val cx = gx - GRID_W / 2f
    val cz = gz - GRID_D / 2f
    out[0] = (cx - cz) * ISO_COS * CUBE_SIZE + centerX
    out[1] = (cx + cz) * ISO_SIN * CUBE_SIZE - gy * CUBE_SIZE + centerY
}

internal data class AntColonyUiState(
    val blockCount: Int = 0,
    val phase: VizPhase = VizPhase.BUILDING,
    val destructionType: DestructionType = DestructionType.STOMP,
    val destructionProgress: Float = 0f,
    val time: Float = 0f,
    val stompY: Float = -0.5f,
    val stompPhase: Int = 0,
    val magGlassCenterX: Float = 0.5f,
    val magGlassCenterY: Float = 0.3f,
)
