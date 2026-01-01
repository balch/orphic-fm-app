package org.balch.orpheus.features.viz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.ui.theme.OrpheusColors
import org.balch.orpheus.ui.viz.CenterPanelStyle
import org.balch.orpheus.ui.viz.DynamicVisualization
import org.balch.orpheus.ui.viz.Visualization
import org.balch.orpheus.ui.viz.VisualizationLiquidEffects
import org.balch.orpheus.ui.viz.VisualizationLiquidScope
import org.balch.orpheus.util.currentTimeMillis
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// --------------------------------------------------------------------------------
// Data Structures
// --------------------------------------------------------------------------------

enum class ParticleType {
    ROCKET,     // The ascending rocket
    SPARK,      // Standard explosion particle
    TRAIL,      // Trail left by sparkles
    CORE,       // Bright center flash
    GLITTER     // Twinkling lingering particles
}

data class Particle(
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    val color: Color,
    val size: Float,
    val life: Float,        // 1.0 -> 0.0
    val decay: Float,       // How fast life decreases
    val type: ParticleType,
    val phase: Float = 0f,  // For twinkling/animation
    val id: Int = Random.nextInt()
)

data class FireworksUiState(
    val particles: List<Particle> = emptyList(),
    val masterEnergy: Float = 0f,
    val flashIntensity: Float = 0f // Screen flash on big bass hits
)

// --------------------------------------------------------------------------------
// Visualization Implementation
// --------------------------------------------------------------------------------

@Inject
@ContributesIntoSet(AppScope::class, binding = binding<Visualization>())
class FireworksViz(
    private val engine: SynthEngine,
    private val dispatcherProvider: DispatcherProvider,
) : DynamicVisualization {

    override val id = "fireworks"
    override val name = "Fireworks"
    override val color = OrpheusColors.synthPink
    override val knob1Label = "DEPTH"
    override val knob2Label = "DECAY"

    // Night sky theme
    override val liquidEffects = Default
    
    // Knobs
    private var _depthKnob = 0.5f // Controls color depth (Bright -> Dark/Deep)
    private var _decayKnob = 0.5f 

    override fun setKnob1(value: Float) {
        _depthKnob = value.coerceIn(0f, 1f)
    }

    override fun setKnob2(value: Float) {
        _decayKnob = value.coerceIn(0f, 1f)
    }

    // State
    private val _uiState = MutableStateFlow(FireworksUiState())
    val uiState: StateFlow<FireworksUiState> = _uiState.asStateFlow()

    private var vizJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    // Simulation State (kept mutable inside the loop for performance)
    private val particles = ArrayList<Particle>()
    private var previousEnergy = 0f
    private var previousVoiceLevels = FloatArray(8)
    private var timeSinceLastLaunch = 0f
    
    // Config
    private val launchIntervalMin = 0.2f // Seconds
    private val colors = listOf(
        OrpheusColors.neonCyan,
        OrpheusColors.neonMagenta,
        OrpheusColors.neonOrange,
        OrpheusColors.electricBlue,
        OrpheusColors.synthGreen,
        OrpheusColors.synthPink,
        OrpheusColors.warmGlow,
        Color.White
    )
    
    // Helper to control brightness/depth
    private fun applyDepth(c: Color): Color {
        // Depth 0: Original Bright
        // Depth 1: Darker/Deeper (less luminance)
        if (_depthKnob < 0.01f) return c
        
        val factor = 1f - (_depthKnob * 0.7f) // Keep 30% brightness at max depth
        return Color(
            red = c.red * factor,
            green = c.green * factor,
            blue = c.blue * factor,
            alpha = c.alpha * (1f - _depthKnob * 0.2f)
        )
    }

    private val _liquidEffects = MutableStateFlow(Default)
    override val liquidEffectsFlow: Flow<VisualizationLiquidEffects> = _liquidEffects.asStateFlow()

    override fun onActivate() {
        if (vizJob?.isActive == true) return
        particles.clear()
        
        vizJob = scope.launch(dispatcherProvider.default) {
            var lastFrameTime = currentTimeMillis()

            while (isActive) {
                val currentTime = currentTimeMillis()
                val dt = (currentTime - lastFrameTime) / 1000f
                lastFrameTime = currentTime

                // limit dt to avoid huge jumps on frame drops
                val deltaTime = dt.coerceAtMost(0.1f)

                updateSimulation(deltaTime)

                delay(16) // ~60fps target
            }
        }
    }
    
    override fun onDeactivate() {
        vizJob?.cancel()
        vizJob = null
        particles.clear()
        _uiState.value = FireworksUiState()
    }

    private fun updateSimulation(dt: Float) {
        val masterLevel = engine.masterLevelFlow.value
        val voiceLevels = engine.voiceLevelsFlow.value
        val lfoValue = engine.lfoOutputFlow.value

        // 1. Detect Triggers
        // Energy spike detection
        val energyDelta = masterLevel - previousEnergy
        val isBeat = energyDelta > 0.1f && masterLevel > 0.3f
        
        timeSinceLastLaunch += dt

        // Logic to launch rockets
        // Launch on beats or randomly if enough time passed
        if ((isBeat && timeSinceLastLaunch > launchIntervalMin) || 
            (timeSinceLastLaunch > 2.0f && Random.nextFloat() < 0.05f)) {
            
            launchFirework(masterLevel)
            timeSinceLastLaunch = 0f
        }
        
        // Voice reaction: specific voices can continuously spawn particles (fountains)
        for (i in voiceLevels.indices) {
            val level = voiceLevels[i]
            if (level > 0.4f && level > previousVoiceLevels.getOrElse(i) { 0f }) {
                // Determine spawn position based on voice index (spread periodically)
                val xNorm = 0.1f + (i * 0.1f) % 0.8f
                // Low voices -> Low fountains, High voices -> Sparkles high up
                if (i < 2) { // Bass
                    spawnFountain(xNorm, level, applyDepth(OrpheusColors.neonOrange))
                } else if (i > 5) { // High hats / synth
                    spawnSparkle(xNorm, level, applyDepth(OrpheusColors.neonCyan)) 
                }
            }
        }

        // 2. Physics & Update
        val gravity = 550f
        val baseDecay = 0.3f + (1f - _decayKnob) * 0.5f // Knob controls 'sustain' (inverse of decay)
        
        // Iterate backwards to allow safe removal
        // Capture size at start to avoid iterating over newly spawned particles in the same frame
        val count = particles.size
        for (i in count - 1 downTo 0) {
            val p = particles[i]
            
            // Integrate position
            val newVx = p.vx * 0.98f // Drag
            val newVy = p.vy + gravity * dt
            
            // Life decay
            var newLife = p.life - p.decay * dt * baseDecay
            
            if (p.type == ParticleType.ROCKET) {
                newLife = 1f // Rockets persist until explosion
                
                // Rocket Logic: Explode at apex (when velocity slows down enough)
                // Launch is negative Vy (UP). Gravity adds positive Vy (DOWN).
                // Apex is when Vy approaches 0.
                if (newVy > -100f) { 
                     explodeRocket(p)
                     particles.removeAt(i)
                     continue
                }
            } else if (p.type == ParticleType.SPARK) {
                 // Sparks leave trails (optional, skipping for performance/simplicity)
            }

            if (newLife <= 0f) {
                particles.removeAt(i)
            } else {
                 particles[i] = p.copy(
                     x = p.x + newVx * dt,
                     y = p.y + newVy * dt,
                     vx = newVx,
                     vy = newVy,
                     life = newLife
                 )
            }
        }

        
        // Limit particle count
        if (particles.size > 1500) {
            // Remove oldest indices (start of list usually if appended)
            // Or random. Random is better to avoid chopping one firework.
            // But just trimming the end (newest? oldest?)
            // `particles` is appened to end. So index 0 is oldest.
            val toRemove = particles.size - 1500
            if (toRemove > 0) {
                particles.subList(0, toRemove).clear()
            }
        }

        previousEnergy = masterLevel
        if (previousVoiceLevels.size != voiceLevels.size) {
            previousVoiceLevels = FloatArray(voiceLevels.size)
        }
        for(i in voiceLevels.indices) previousVoiceLevels[i] = voiceLevels[i]

        val flash = (masterLevel - 0.5f).coerceAtLeast(0f) * 0.2f
        _uiState.value = FireworksUiState(
            particles = particles.toList(), // Copy for UI thread
            masterEnergy = masterLevel,
            flashIntensity = flash
        )
        
        // Dynamic Title Effects
        val titleColor = lerpColor(Color.White, color, (lfoValue + 1f) / 2f)
        val elevation = 6.dp + (masterLevel * 10).dp
        _liquidEffects.value = Default.copy(
             title = Default.title.copy(
                 titleColor = titleColor,
                 titleElevation = elevation,
                 titleSize = (22 + masterLevel * 3).sp
             )
        )
    }
    
    // ----------------------------------------------------------------------------
    // Spawning Logic
    // ----------------------------------------------------------------------------
    
    private fun launchFirework(intensity: Float) {
        // Start from bottom of screen (we'll map 0..1 later to screen coords)
        // Assume logical coords: W=0..1, H=0..1 (aspect ratio handled in draw)
        // Actually, let's use pixel-like coords relative to a 1000x1000 virtual space?
        // Or normalized. Normalized is easier.
        // X: 0.1 .. 0.9
        // Y: 1.1 (below screen)
        
        val startX = Random.nextFloat() * 0.8f + 0.1f
        val startY = 1.1f
        
        // Velocity needed to reach 0.2..0.5 Y
        // v^2 = u^2 + 2as. v=0 at apex. u = sqrt(-2as). s = targetY - startY.
        // H = 1.0 (bottom) -> 0.2 (top). s approx -0.8.
        // a = gravity (e.g. 1.0 units/sec^2).
        // Let's tune manually.
        
        // Target height random 0.1 (high) to 0.4 (mid)
        val targetH = 0.1f + Random.nextFloat() * 0.3f
        // Speed to reach that?
        // Vy = - (1000 + Random) 
        // We'll use pixels in logic to be consistent with GalaxyViz if it used pixels, 
        // but Galaxy used logic coords centered at 0,0.
        // Let's use logic Space 0..1
        
        // But draw expects absolute coordinates? 
        // Best to use a virtual resolution, e.g. 1000.0f height.
        
        val speed = -(1200f + Random.nextFloat() * 400f) // negative is UP
        
        particles.add(Particle(
            x = startX * 1000f, // Map 0..1 to 0..1000
            y = 1100f,
            vx = (Random.nextFloat() - 0.5f) * 100f, // Drift
            vy = speed * (0.8f + intensity * 0.4f), // Harder hits go higher
            color = applyDepth(colors.random()),
            size = 4f,
            life = 1f,
            decay = 0f,
            type = ParticleType.ROCKET
        ))
    }
    
    private fun explodeRocket(parent: Particle) {
        // Boom!
        val pCount = Random.nextInt(40, 100)
        val type = if (Random.nextBoolean()) ParticleType.SPARK else ParticleType.GLITTER
        val color = parent.color
        val secondaryColor = if (Random.nextBoolean()) applyDepth(colors.random()) else color
        
        for (i in 0 until pCount) {
             val angle = Random.nextFloat() * 2 * PI.toFloat()
             val speed = Random.nextFloat() * 300f 
             
             particles.add(Particle(
                 x = parent.x,
                 y = parent.y,
                 vx = parent.vx + cos(angle) * speed,
                 vy = parent.vy + sin(angle) * speed,
                 color = if (Random.nextFloat() > 0.7f) secondaryColor else color,
                 size = Random.nextFloat() * 3f + 1f,
                 life = 1.0f,
                 decay = 0.5f + Random.nextFloat(), // Variance
                 type = type
             ))
        }
        
        // Flash core
        particles.add(Particle(
             x = parent.x,
             y = parent.y,
             vx = 0f, vy = 0f,
             color = Color.White,
             size = 20f,
             life = 0.2f,
             decay = 5f,
             type = ParticleType.CORE
        ))
    }
    
    private fun spawnFountain(xNorm: Float, intensity: Float, color: Color) {
         // Spawns from bottom
         particles.add(Particle(
             x = xNorm * 1000f + (Random.nextFloat() - 0.5f) * 20f,
             y = 1000f,
             vx = (Random.nextFloat() - 0.5f) * 100f,
             vy = -(400f + intensity * 400f),
             color = color,
             size = 2f + intensity * 2f,
             life = 0.8f,
             decay = 2f,
             type = ParticleType.SPARK
         ))
    }
    
    private fun spawnSparkle(xNorm: Float, intensity: Float, color: Color) {
         // Spawns random high location
         particles.add(Particle(
             x = xNorm * 1000f + (Random.nextFloat() - 0.5f) * 100f,
             y = Random.nextFloat() * 500f,
             vx = 0f,
             vy = 50f, // Dripping down
             color = color,
             size = 1f + intensity,
             life = 0.5f,
             decay = 3f,
             type = ParticleType.GLITTER
         ))
    }

    // ----------------------------------------------------------------------------
    // Drawing
    // ----------------------------------------------------------------------------

    @Composable
    override fun Content(modifier: Modifier) {
        val state by uiState.collectAsState()
        
        Canvas(modifier = modifier.fillMaxSize()) {
            val scaleX = size.width / 1000f
            val scaleY = size.height / 1000f
            
            // Draw Background
            drawRect(OrpheusColors.deepSpaceBlue) // Consistent deep background
            
            // Draw Flash based on bass
            if (state.flashIntensity > 0.01f) {
                drawRect(
                    color = Color.White.copy(alpha = state.flashIntensity.coerceIn(0f, 0.3f)),
                    blendMode = BlendMode.Plus
                )
            }
            
            // Draw Particles
            state.particles.forEach { p ->
                val alpha = p.life.coerceIn(0f, 1f)
                val drawColor = p.color.copy(alpha = alpha)
                val px = p.x * scaleX
                val py = p.y * scaleY
                val pScale = p.size * (0.5f + alpha * 0.5f) // Shrink as they die
                
                when (p.type) {
                    ParticleType.ROCKET -> {
                         // Draw trail
                         drawLine(
                             color = Color.White.copy(alpha = 0.5f),
                             start = Offset(px - p.vx * 0.05f * scaleX, py - p.vy * 0.05f * scaleY),
                             end = Offset(px, py),
                             strokeWidth = 2f
                         )
                         drawCircle(
                             color = Color.White,
                             radius = 2f * scaleX, // small dot
                             center = Offset(px, py)
                         )
                    }
                    else -> {
                        drawCircle(
                            color = drawColor,
                            radius = pScale * scaleX,
                            center = Offset(px, py),
                            blendMode = BlendMode.Plus
                        )
                    }
                }
            }
        }
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

    companion object {
        val Default = VisualizationLiquidEffects(
            frostSmall = 0f, // Clear view
            frostMedium = 0f,
            frostLarge = 0f,
            tintAlpha = 0.05f,
            title = CenterPanelStyle(
                scope = VisualizationLiquidScope(contrast = 1.4f, saturation = 1.2f),
                titleColor = OrpheusColors.synthPink,
                borderColor = OrpheusColors.synthPink.copy(alpha = 0.4f),
                titleElevation = 8.dp
            )
        )
    }
}
