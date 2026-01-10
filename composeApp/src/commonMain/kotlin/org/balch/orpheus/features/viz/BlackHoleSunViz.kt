package org.balch.orpheus.features.viz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
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
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.CenterPanelStyle
import org.balch.orpheus.ui.viz.Visualization
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.viz.VisualizationLiquidScope
import org.balch.orpheus.util.currentTimeMillis
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// --------------------------------------------------------------------------------
// Particle Types for Accretion Disk
// --------------------------------------------------------------------------------

enum class AccretionParticleType {
    EMITTED,    // Fresh particle from emitter
    ORBITING,   // Particle circling toward center
    ABSORBED    // Particle fading into the disk
}

data class AccretionParticle(
    val id: Int,
    var x: Float,           // Normalized position (-1 to 1) from center
    var y: Float,
    var vx: Float,          // Velocity
    var vy: Float,
    var angularVelocity: Float, // For orbital motion
    var color: Color,
    var size: Float,
    var life: Float,        // 1.0 -> 0.0
    var decay: Float,       // How fast life decreases
    var type: AccretionParticleType,
    var distanceFromCenter: Float = 0f,
    var emitterIndex: Int = 0
)

/**
 * UI State for the Blackhole Sun visualization.
 */
data class BlackholeSunUiState(
    val particles: List<AccretionParticle> = emptyList(),
    val diskGlow: Float = 0f,          // Accumulated glow from absorbed particles
    val diskHeat: FloatArray = FloatArray(8), // Heat per sector for disk coloring
    val masterEnergy: Float = 0f,
    val orbitDirection: Float = 1f,    // 1 = clockwise, -1 = counter-clockwise
    val diskRotation: Float = 0f,      // Accumulated disk rotation angle
    val time: Float = 0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BlackholeSunUiState) return false
        return particles == other.particles &&
               diskGlow == other.diskGlow &&
               diskHeat.contentEquals(other.diskHeat) &&
               masterEnergy == other.masterEnergy &&
               orbitDirection == other.orbitDirection &&
               diskRotation == other.diskRotation &&
               time == other.time
    }
    override fun hashCode(): Int {
        var result = particles.hashCode()
        result = 31 * result + diskGlow.hashCode()
        result = 31 * result + diskHeat.contentHashCode()
        result = 31 * result + masterEnergy.hashCode()
        result = 31 * result + orbitDirection.hashCode()
        result = 31 * result + diskRotation.hashCode()
        result = 31 * result + time.hashCode()
        return result
    }
}

/**
 * Blackhole Sun - Particle-based accretion disk visualization.
 * 
 * Particles emit from 8 orbiting emitters (one per voice), spiral inward
 * like matter falling into a black hole, and fade into a glowing accretion disk.
 * 
 * Uses a fast Canvas-based particle system (like Fireworks) instead of shaders.
 * 
 * Features:
 * - 8 emitters orbiting off-screen (one per voice)
 * - Particles emit tangentially and spiral inward with gravity
 * - Particles fade and "heat" the accretion disk as they approach the event horizon
 * - Prominent visual effect with glowing disk and black hole center
 * 
 * Knob1 (SPIN): Controls orbit direction and speed
 * Knob2 (TRAILS): Controls particle trail length/density
 */
@Inject
@ContributesIntoSet(AppScope::class)
class BlackHoleSunViz(
    private val engine: SynthEngine,
    private val dispatcherProvider: DispatcherProvider,
) : Visualization {

    override val id = "blackhole_sun"
    override val name = "Black Hole Sun"
    override val color = OrpheusColors.neonMagenta
    override val knob1Label = "SPIN"
    override val knob2Label = "DENSITY"
    
    override val liquidEffects = Default

    private var _spinKnob = 0.5f
    private var _densityKnob = 0.5f

    override fun setKnob1(value: Float) { 
        _spinKnob = value.coerceIn(0f, 1f) 
    }
    
    override fun setKnob2(value: Float) { 
        _densityKnob = value.coerceIn(0f, 1f) 
    }

    // Emitter colors - paired per voice
    private val voicePairColors = listOf(
        OrpheusColors.neonMagenta,    // Voices 0-1: Bass
        OrpheusColors.electricBlue,   // Voices 2-3: Low-mid
        OrpheusColors.synthGreen,     // Voices 4-5: Mid
        OrpheusColors.neonCyan        // Voices 6-7: High
    )
    
    private val emitterColors = listOf(
        voicePairColors[0], voicePairColors[0],
        voicePairColors[1], voicePairColors[1],
        voicePairColors[2], voicePairColors[2],
        voicePairColors[3], voicePairColors[3]
    )

    // Emitter state
    private val emitterAngles = FloatArray(8) { i -> (i.toFloat() / 8f) * 2f * PI.toFloat() }
    private val emitterSpeedMultipliers = floatArrayOf(1.0f, 1.4f, 0.7f, 1.2f, 0.85f, 1.5f, 1.1f, 0.75f)

    // Particle system
    private val particles = ArrayList<AccretionParticle>(500)
    private var nextParticleId = 0
    
    // Disk state
    private val diskHeat = FloatArray(8) { 0f }  // Heat per sector
    private var diskGlow = 0f
    private var diskRotation = 0f  // Accumulated rotation angle for the disk
    
    // Constants - BIGGER DISK & PHYSICS (25% larger)
    private val orbitRadius = 0.69f      // Start further out (was 0.55)
    private val eventHorizon = 0.31f     // Bigger event horizon (was 0.25)
    private val blackHoleRadius = 0.15f  // Bigger black hole (was 0.12)
    
    // Dynamic particle limit based on density knob
    private val maxParticles: Int
        get() = 200 + (_densityKnob * 1000).toInt()
    
    // Physics Constants
    private val G = 0.0006f              // Stronger gravity for faster, tighter orbits

    private val _uiState = MutableStateFlow(BlackholeSunUiState())
    val uiState: StateFlow<BlackholeSunUiState> = _uiState.asStateFlow()

    private var vizJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)
    private var animationTime = 0f
    private var smoothedMasterEnergy = 0f

    override fun onActivate() {
        if (vizJob?.isActive == true) return
        animationTime = 0f
        smoothedMasterEnergy = 0f
        particles.clear()
        diskGlow = 0f
        diskHeat.fill(0f)
        
        // Reset emitter angles
        emitterAngles.forEachIndexed { i, _ ->
            emitterAngles[i] = (i.toFloat() / 8f) * 2f * PI.toFloat()
        }
        
        vizJob = scope.launch(dispatcherProvider.default) {
            var lastFrameTime = currentTimeMillis()
            
            while (isActive) {
                val currentTime = currentTimeMillis()
                val deltaTime = ((currentTime - lastFrameTime) / 1000f).coerceAtMost(0.1f)
                lastFrameTime = currentTime
                animationTime += deltaTime

                updateSimulation(deltaTime)

                delay(16) // ~60fps for smooth particles
            }
        }
    }

    override fun onDeactivate() {
        vizJob?.cancel()
        vizJob = null
        particles.clear()
        diskGlow = 0f
        diskHeat.fill(0f)
        _uiState.value = BlackholeSunUiState()
    }

    @Composable
    override fun Content(modifier: Modifier) {
        val state by uiState.collectAsState()
        
        Canvas(modifier = modifier.fillMaxSize()) {
            drawBlackholeSun(state)
        }
    }

    private fun updateSimulation(deltaTime: Float) {
        val voiceLevels = engine.voiceLevelsFlow.value
        val masterLevel = engine.masterLevelFlow.value
        
        smoothedMasterEnergy = smoothedMasterEnergy * 0.9f + masterLevel * 0.1f

        // Calculate orbit direction from knob (reverses at 0.25 and 0.75)
        val directionMultiplier = cos(_spinKnob * 2f * PI.toFloat())
        val speedMagnitude = 0.15f + abs(directionMultiplier) * 0.6f
        val orbitDirection = sign(directionMultiplier).let { if (it == 0f) 1f else it }
        val orbitSpeed = speedMagnitude * orbitDirection

        // Update emitter positions
        for (i in 0 until 8) {
            val speedMult = emitterSpeedMultipliers[i]
            emitterAngles[i] = (emitterAngles[i] + orbitSpeed * speedMult * deltaTime) % (2f * PI.toFloat())
        }

        // Emit particles from active voices
        emitParticles(voiceLevels, masterLevel, orbitDirection)

        // Update particle physics
        updateParticles(deltaTime, orbitDirection)

        // Decay disk heat
        for (i in diskHeat.indices) {
            diskHeat[i] = (diskHeat[i] * (1f - deltaTime * 0.5f)).coerceAtLeast(0f)
        }
        diskGlow = (diskGlow * (1f - deltaTime * 0.3f)).coerceAtLeast(0f)
        
        // Accumulate disk rotation based on audio energy - spins faster with more sound
        val rotationSpeed = orbitDirection * (0.2f + smoothedMasterEnergy * 1.5f + diskGlow * 0.8f)
        diskRotation = (diskRotation + rotationSpeed * deltaTime) % (2f * PI.toFloat())

        // Emit UI state
        _uiState.value = BlackholeSunUiState(
            particles = particles.toList(),
            diskGlow = diskGlow,
            diskHeat = diskHeat.copyOf(),
            masterEnergy = smoothedMasterEnergy,
            orbitDirection = orbitDirection,
            diskRotation = diskRotation,
            time = animationTime
        )
    }

    private fun emitParticles(voiceLevels: FloatArray, masterLevel: Float, orbitDir: Float) {
        if (particles.size >= maxParticles) return

        for (i in 0 until 8) {
            val voiceLevel = voiceLevels.getOrElse(i) { 0f }
            val effectiveLevel = maxOf(voiceLevel, masterLevel * 0.3f)
            
            // Low threshold so we see occasional particles even at quiet levels
            if (effectiveLevel < 0.03f) continue
            
            // EMISSION CURVE:
            // Use quadratic curve so low volumes = sparse occasional particles
            // High volumes = full stream (same as before)
            // effectiveLevel^2 gives: 0.1 -> 0.01, 0.3 -> 0.09, 0.5 -> 0.25, 1.0 -> 1.0
            val scaledLevel = effectiveLevel * effectiveLevel
            val emitChance = scaledLevel * (0.05f + _densityKnob * 0.95f)
            
            if (Random.nextFloat() < emitChance) {
                emitParticle(i, effectiveLevel, orbitDir)
            }
        }
        
        // Remove excess particles if knob was turned down
        val currentMax = maxParticles
        if (particles.size > currentMax) {
            val toRemove = particles.size - currentMax
            if (toRemove > 0) {
                // Remove oldest first (start of list)
                particles.subList(0, toRemove.coerceAtMost(particles.size)).clear()
            }
        }
    }

    private fun emitParticle(emitterIndex: Int, energy: Float, orbitDir: Float) {
        if (particles.size >= maxParticles) return

        val angle = emitterAngles[emitterIndex]
        // Start at edge of visible area
        val startRadius = orbitRadius * (0.9f + Random.nextFloat() * 0.1f)
        val startX = cos(angle) * startRadius
        val startY = sin(angle) * startRadius

        // Tangential velocity (perpendicular to radius) provides the orbit
        val tangentX = -sin(angle) * orbitDir
        val tangentY = cos(angle) * orbitDir

        // PHYSICS FIX: Launch at stable orbital velocity
        // v = sqrt(G / r) needed for circular orbit
        val stableSpeed = sqrt(G / startRadius)
        
        // Randomize slightly: 
        // < 1.0 = Elliptical orbit falling inward
        // > 1.0 = Elliptical orbit swinging outward first
        // We want mostly inward spirals, so 0.6 to 1.1 range
        val velocityScale = 0.7f + Random.nextFloat() * 0.4f
        val launchSpeed = stableSpeed * velocityScale
        
        val vx = tangentX * launchSpeed
        val vy = tangentY * launchSpeed

        val angularVel = 0f // Unused

        val baseColor = emitterColors[emitterIndex]
        // Brightness scales with energy
        val brightness = 0.5f + energy * 0.5f
        val particleColor = baseColor.copy(alpha = brightness)

        particles.add(AccretionParticle(
            id = nextParticleId++,
            x = startX + (Random.nextFloat() - 0.5f) * 0.02f,
            y = startY + (Random.nextFloat() - 0.5f) * 0.02f,
            vx = vx,
            vy = vy,
            angularVelocity = angularVel,
            color = particleColor,
            // MUCH BIGGER PARTICLE SIZES
            size = 4f + energy * 8f + Random.nextFloat() * 6f, // Range ~4 to 18
            life = 1f,
            decay = 0.02f + Random.nextFloat() * 0.03f, // Long life
            type = AccretionParticleType.EMITTED,
            emitterIndex = emitterIndex
        ))
    }

    private fun updateParticles(deltaTime: Float, orbitDir: Float) {
        val iterator = particles.iterator()
        
        while (iterator.hasNext()) {
            val p = iterator.next()
            
            // Calculate distance from center
            p.distanceFromCenter = sqrt(p.x * p.x + p.y * p.y)
            
            // REAL GRAVITY PHYSICS (Newtonian-ish)
            // F = G * M / r^2
            // Acceleration direction is toward center (-x, -y)
            
            // Softening parameter to prevent infinity at center
            val r2 = p.distanceFromCenter * p.distanceFromCenter + 0.001f
            val force = G / r2
            
            val dirX = -p.x / (p.distanceFromCenter + 0.0001f)
            val dirY = -p.y / (p.distanceFromCenter + 0.0001f)
            
            // Apply Gravity
            p.vx += dirX * force
            p.vy += dirY * force
            
            // Drag - Reduced significantly so they don't stop and fall in
            val drag = 0.998f 
            
            p.vx *= drag
            p.vy *= drag
            
            // Move particle
            p.x += p.vx
            p.y += p.vy
            
            // Transition particle types based on distance
            when {
                p.distanceFromCenter < eventHorizon + 0.15f && p.type == AccretionParticleType.EMITTED -> {
                    p.type = AccretionParticleType.ORBITING
                    p.angularVelocity *= 1.3f  // Speed up orbit near disk
                }
                p.distanceFromCenter < eventHorizon && p.type == AccretionParticleType.ORBITING -> {
                    p.type = AccretionParticleType.ABSORBED
                    p.decay *= 2f  // Fade faster when absorbed
                    
                    // Add heat to disk sector
                    val sectorAngle = atan2(p.y, p.x)
                    val sector = ((sectorAngle + PI) / (2 * PI) * 8).toInt().coerceIn(0, 7)
                    diskHeat[sector] = (diskHeat[sector] + 0.25f).coerceAtMost(1f)
                    diskGlow = (diskGlow + 0.06f).coerceAtMost(1f)
                }
            }
            
            // Life decay (fixed base decay, density knob controls count via emission)
            val decayMult = 1.0f 
            p.life -= p.decay * deltaTime * decayMult
            
            // Remove dead or absorbed particles
            if (p.life <= 0f || p.distanceFromCenter < blackHoleRadius) {
                // Final heat contribution when consumed
                if (p.distanceFromCenter < eventHorizon) {
                    val sectorAngle = atan2(p.y, p.x)
                    val sector = ((sectorAngle + PI) / (2 * PI) * 8).toInt().coerceIn(0, 7)
                    diskHeat[sector] = (diskHeat[sector] + 0.08f).coerceAtMost(1f)
                    diskGlow = (diskGlow + 0.02f).coerceAtMost(1f)
                }
                iterator.remove()
            }
        }
    }

    private fun DrawScope.drawBlackholeSun(state: BlackholeSunUiState) {
        val width = size.width
        val height = size.height
        val cx = width / 2f
        val cy = height / 2f
        val scale = min(width, height) / 2f

        // Dark background with subtle gradient
        drawRect(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to OrpheusColors.blackHoleDeep,
                    0.5f to OrpheusColors.blackHoleVoid,
                    1f to OrpheusColors.blackHoleEdge
                ),
                center = Offset(cx, cy),
                radius = scale * 1.5f
            )
        )

        // Accretion disk (outer glow) - nearly invisible when silent
        val diskOuterRadius = eventHorizon + 0.15f
        val diskInnerRadius = eventHorizon - 0.02f
        // Key change: base intensity is near zero, only shows with audio
        val diskIntensity = (state.diskGlow * 0.6f + state.masterEnergy * 0.4f).coerceIn(0f, 0.8f)

        // Disk sector heat colors are now represented by the cloud particles themselves
        // (removed the 8 orbiting sector glow circles)

        // Main disk ring glow - only visible with audio
        if (diskIntensity > 0.03f) {
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.5f to Color.Transparent,
                        0.7f to OrpheusColors.blackHoleDiskPurple.copy(alpha = diskIntensity * 0.3f),
                        0.85f to OrpheusColors.blackHoleDiskOrange.copy(alpha = diskIntensity * 0.4f),
                        0.95f to OrpheusColors.blackHoleDiskGold.copy(alpha = diskIntensity * 0.2f),
                        1f to Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = scale * (diskOuterRadius + 0.03f)
                ),
                radius = scale * (diskOuterRadius + 0.03f),
                center = Offset(cx, cy),
                blendMode = BlendMode.Plus
            )
        }

        // Event horizon glow - only visible with audio activity
        val horizonGlow = (state.diskGlow * 0.5f + state.masterEnergy * 0.3f).coerceIn(0f, 0.7f)
        if (horizonGlow > 0.02f) {
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.6f to Color.Transparent,
                        0.8f to OrpheusColors.blackHoleHorizonGold.copy(alpha = horizonGlow * 0.4f),
                        0.95f to OrpheusColors.blackHoleHorizonOrange.copy(alpha = horizonGlow * 0.25f),
                        1f to Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = scale * eventHorizon
                ),
                radius = scale * eventHorizon,
                center = Offset(cx, cy),
                blendMode = BlendMode.Plus
            )
        }

        // Black hole center (true black with sharp edge)
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to Color.Black,
                    0.85f to Color.Black,
                    1f to OrpheusColors.blackHoleEdge
                ),
                center = Offset(cx, cy),
                radius = scale * blackHoleRadius
            ),
            radius = scale * blackHoleRadius,
            center = Offset(cx, cy)
        )

        // Draw particles as irregular cloud-like shapes
        state.particles.forEach { p ->
            val alpha = (p.life * p.color.alpha).coerceIn(0f, 1f)
            if (alpha < 0.01f) return@forEach

            val px = cx + p.x * scale
            val py = cy + p.y * scale
            
            // Size based on type
            val sizeMultiplier = when (p.type) {
                AccretionParticleType.EMITTED -> 1f
                AccretionParticleType.ORBITING -> 1.2f + (1f - p.distanceFromCenter / eventHorizon) * 0.5f
                AccretionParticleType.ABSORBED -> 0.8f * p.life
            }
            val particleSize = p.size * sizeMultiplier * (scale / 400f)

            // Glow color
            val glowColor = when (p.type) {
                AccretionParticleType.ABSORBED -> {
                    // Shift toward hot orange as particle is absorbed
                    lerpColor(p.color, OrpheusColors.blackHoleParticleOrange, 1f - p.life)
                }
                else -> p.color
            }

            // Calculate angle toward center for cloud stretching
            val angleToCenter = atan2(p.y, p.x)
            
            // Draw irregular cloud shape using multiple overlapping blobs
            // Each particle gets 5-7 sub-blobs positioned with pseudo-noise
            val blobCount = 5 + (p.id % 3)
            val seed = p.id * 1337f
            
            for (i in 0 until blobCount) {
                // Pseudo-noise offsets using sine/cosine of seed values
                val noiseAngle = seed + i * 2.3f + state.time * 0.3f
                val noiseRadius = particleSize * (0.8f + sin(noiseAngle * 0.7f) * 0.6f)
                
                // Offset position - clouds stretch perpendicular to center direction (wrapping around)
                val tangentAngle = angleToCenter + PI.toFloat() / 2f
                val offsetStretch = cos(noiseAngle * 1.3f + i) * particleSize * 1.5f  // Tangential stretch
                val offsetRadial = sin(noiseAngle * 0.9f + i * 1.7f) * particleSize * 0.6f  // Radial variation
                
                val blobX = px + cos(tangentAngle) * offsetStretch + cos(angleToCenter) * offsetRadial
                val blobY = py + sin(tangentAngle) * offsetStretch + sin(angleToCenter) * offsetRadial
                
                // Vary blob size
                val blobSize = noiseRadius * (0.6f + (i.toFloat() / blobCount) * 0.8f)
                
                // Vary alpha per blob for organic feel
                val blobAlpha = alpha * (0.4f + sin(noiseAngle * 0.5f) * 0.3f)
                
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to glowColor.copy(alpha = blobAlpha * 0.7f),
                            0.3f to glowColor.copy(alpha = blobAlpha * 0.5f),
                            0.6f to glowColor.copy(alpha = blobAlpha * 0.2f),
                            1f to Color.Transparent
                        ),
                        center = Offset(blobX, blobY),
                        radius = blobSize * 2.5f
                    ),
                    radius = blobSize * 2.5f,
                    center = Offset(blobX, blobY),
                    blendMode = BlendMode.Plus
                )
            }
            
            // Core glow at center of cloud
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to glowColor.copy(alpha = alpha * 0.6f),
                        0.5f to glowColor.copy(alpha = alpha * 0.3f),
                        1f to Color.Transparent
                    ),
                    center = Offset(px, py),
                    radius = particleSize * 2f
                ),
                radius = particleSize * 2f,
                center = Offset(px, py),
                blendMode = BlendMode.Plus
            )

            // Bright core for emitted particles
            if (p.type == AccretionParticleType.EMITTED && alpha > 0.5f) {
                drawCircle(
                    color = Color.White.copy(alpha = alpha * 0.3f),
                    radius = particleSize * 0.4f,
                    center = Offset(px, py),
                    blendMode = BlendMode.Plus
                )
            }
        }

        // Subtle vignette
        drawRect(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.7f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.3f)
                ),
                center = Offset(cx, cy),
                radius = scale * 1.4f
            )
        )
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

    companion object Companion {
        val Default = VisualizationLiquidEffects(
            frostSmall = 2f,
            frostMedium = 2f,
            frostLarge = 3f,
            tintAlpha = 0.08f,
            top = VisualizationLiquidScope(
                saturation = 1f,
                dispersion = .3f,
                curve = .1f,
                refraction = .3f,
            ),
            bottom = VisualizationLiquidScope(
                saturation = 1f,
                dispersion = .3f,
                curve = .1f,
                refraction = .3f,
            ),
            title = CenterPanelStyle(
                scope = VisualizationLiquidScope(
                    saturation = 3f,
                    dispersion = 1.2f,
                    curve = 0.25f,
                    refraction = 0.8f,
                    contrast = 0.9f,
                ),
                titleColor = OrpheusColors.neonOrange,
                borderColor = OrpheusColors.synthPink.copy(alpha = 0.6f),
                borderWidth = 3.dp,
                titleElevation = 2.dp,
            ),
        )
    }
}
