package org.balch.orpheus.features.visualizations.viz

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
import kotlin.math.sqrt
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
    override val knob2Label = "DENSITY"

    // Liquid/Glassmorphism theme matching LavaLamp/Galaxy
    override val liquidEffects = Default
    
    // Knobs
    private var _colorKnob = 0.5f // Controls color intensity/brightness
    private var _countKnob = 0.5f // Controls quantity/frequency

    override fun setKnob1(value: Float) {
        _colorKnob = value.coerceIn(0f, 1f)
    }

    override fun setKnob2(value: Float) {
        _countKnob = value.coerceIn(0f, 1f)
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
    private fun applyColorIntensity(c: Color): Color {
        // Knob 1: COLOR
        // 0.0 -> Dim, desaturated, transparent
        // 0.5 -> Normal
        // 1.0 -> Extremely bright, opaque, max saturation
        
        // Base intensity factor from knob
        val intensity = 0.2f + (_colorKnob * 0.8f) // 0.2 to 1.0
        
        return Color(
            red = c.red,
            green = c.green,
            blue = c.blue,
            alpha = (c.alpha * intensity).coerceIn(0f, 1f)
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
        val energyDelta = masterLevel - previousEnergy
        // Beat sensitivity
        val beatThreshold = 0.2f
        val isBeat = energyDelta > 0.15f && masterLevel > beatThreshold
        
        timeSinceLastLaunch += dt

        // Logic to launch rockets
        // Volume controls IF we see fireworks.
        // Count Knob controls HOW MANY / HOW OFTEN.
        
        val volFactor = masterLevel.coerceIn(0f, 1f)
        
        // Base Interval heavily modified by knob and volume
        // Knob 0.0 -> very slow (2.0s)
        // Knob 1.0 -> very fast (0.1s)
        val knobFactor = 2.0f - (_countKnob * 1.9f) // 2.0 to 0.1
        
        // If volume is low, we slow down even more or stop
        if (volFactor > 0.05f) { // Silence threshold
            // Volume boosts speed
            val adjustedInterval = knobFactor / (1f + volFactor * 2f)
            
            if (isBeat && timeSinceLastLaunch > 0.1f) {
                // Beat trigger - maybe launch multiple if knob is high
                val count = if (_countKnob > 0.7f) Random.nextInt(1, 3) else 1
                repeat(count) { launchFirework(masterLevel) }
                timeSinceLastLaunch = 0f
            } else if (timeSinceLastLaunch > adjustedInterval) {
                // Time based trigger
                 launchFirework(masterLevel)
                timeSinceLastLaunch = 0f
            }
        }
        
        // Voice reaction: fountains and sparkles
        // Scale probability by count knob
        for (i in voiceLevels.indices) {
            val level = voiceLevels[i]
            if (level > 0.4f && level > previousVoiceLevels.getOrElse(i) { 0f }) {
                if (Random.nextFloat() < (0.2f + _countKnob * 0.8f)) {
                    val xNorm = 0.1f + (i * 0.1f) % 0.8f
                    if (i < 2) { 
                        spawnFountain(xNorm, level, applyColorIntensity(OrpheusColors.neonOrange))
                    } else if (i > 5) {
                        spawnSparkle(xNorm, level, applyColorIntensity(OrpheusColors.neonCyan)) 
                    }
                }
            }
        }

        // 2. Physics & Update
        val gravity = 800f 
        val baseDecay = 0.5f // Fixed decay, knob used for count now
        
        val count = particles.size
        for (i in count - 1 downTo 0) {
            val p = particles[i]
            
            // Integrate position
            val newVx = p.vx * 0.98f // Drag
            val newVy = p.vy + gravity * dt
            
            // Life decay
            var newLife = p.life - p.decay * dt * baseDecay
            
            if (p.type == ParticleType.ROCKET) {
                newLife = 1f 
                // Explode at apex
                if (newVy > -50f) { 
                     explodeRocket(p, masterLevel)
                     particles.removeAt(i)
                     continue
                }
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

        // Limit particle count based on knob (performance + density control)
        val maxParticles = 500 + (_countKnob * 2500).toInt()
        if (particles.size > maxParticles) {
            val toRemove = particles.size - maxParticles
            if (toRemove > 0) {
                particles.subList(0, toRemove).clear()
            }
        }

        previousEnergy = masterLevel
        if (previousVoiceLevels.size != voiceLevels.size) {
            previousVoiceLevels = FloatArray(voiceLevels.size)
        }
        for(i in voiceLevels.indices) previousVoiceLevels[i] = voiceLevels[i]

        val flash = (masterLevel - 0.6f).coerceAtLeast(0f) * 0.4f * _colorKnob // Flash also obeys color knob
        _uiState.value = FireworksUiState(
            particles = particles.toList(),
            masterEnergy = masterLevel,
            flashIntensity = flash
        )
        
        // Dynamic Title Effects
        val titleColor = lerpColor(OrpheusColors.synthPink, OrpheusColors.neonCyan, (lfoValue + 1f) / 2f)
        val elevation = 6.dp + (masterLevel * 10).dp
        _liquidEffects.value = Default.copy(
             title = Default.title.copy(
                 titleColor = titleColor,
                 titleElevation = elevation,
                 titleSize = (22 + masterLevel * 3).sp,
                 borderColor = titleColor.copy(alpha = 0.5f * _colorKnob)
             )
        )
    }
    
    // ----------------------------------------------------------------------------
    // Spawning Logic
    // ----------------------------------------------------------------------------
    
    private fun launchFirework(intensity: Float) {
        val startX = Random.nextFloat() * 800f + 100f // 100..900
        val startY = 1100f 
        
        // Target Height logic (corrected physics)
        val targetY = 100f + Random.nextFloat() * 300f 
        
        val dist = targetY - startY
        val gravity = 800f 
        val launchSpeed = sqrt(-2 * gravity * dist) 
        
        val speedVar = (Random.nextFloat() - 0.5f) * 50f
        
        particles.add(Particle(
            x = startX,
            y = startY,
            vx = (Random.nextFloat() - 0.5f) * 80f,
            vy = -(launchSpeed + speedVar),
            color = applyColorIntensity(colors.random()),
            size = 4f,
            life = 1f,
            decay = 0f,
            type = ParticleType.ROCKET
        ))
    }
    
    private fun explodeRocket(parent: Particle, intensity: Float) {
        // Count Knob affects explosion density too
        val countMult = 0.5f + _countKnob // 0.5x to 1.5x particles
        
        val baseCount = if (intensity < 0.2f) 30 else 80
        val pCount = ((baseCount + (intensity * 100)) * countMult).toInt().coerceAtMost(300)
        
        val type = if (Random.nextBoolean()) ParticleType.SPARK else ParticleType.GLITTER
        val color = parent.color
        val secondaryColor = if (Random.nextBoolean()) applyColorIntensity(colors.random()) else color
        
        for (i in 0 until pCount) {
             val angle = Random.nextFloat() * 2 * PI.toFloat()
             val speed = Random.nextFloat() * (200f + intensity * 300f)
             
             particles.add(Particle(
                 x = parent.x,
                 y = parent.y,
                 vx = parent.vx + cos(angle) * speed,
                 vy = parent.vy + sin(angle) * speed,
                 color = if (Random.nextFloat() > 0.7f) secondaryColor else color,
                 size = Random.nextFloat() * 3f + 1f,
                 life = 1.0f,
                 decay = 0.5f + Random.nextFloat(), 
                 type = type
             ))
        }
        
        // Flash core
        particles.add(Particle(
             x = parent.x,
             y = parent.y,
             vx = 0f, vy = 0f,
             color = Color.White.copy(alpha = _colorKnob), // Obey knob
             size = 20f + intensity * 20f,
             life = 0.2f,
             decay = 5f,
             type = ParticleType.CORE
        ))
    }
    
    private fun spawnFountain(xNorm: Float, intensity: Float, color: Color) {
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
         particles.add(Particle(
             x = xNorm * 1000f + (Random.nextFloat() - 0.5f) * 100f,
             y = Random.nextFloat() * 500f,
             vx = 0f,
             vy = 50f, 
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
            
            drawRect(OrpheusColors.fireworksBackground) 
            
            if (state.flashIntensity > 0.01f) {
                drawRect(
                    color = Color.White.copy(alpha = state.flashIntensity.coerceIn(0f, 0.2f)),
                    blendMode = BlendMode.Plus
                )
            }
            
            state.particles.forEach { p ->
                val alpha = p.life.coerceIn(0f, 1f)
                val drawColor = p.color.copy(alpha = (p.color.alpha * alpha).coerceIn(0f, 1f))
                val px = p.x * scaleX
                val py = p.y * scaleY
                val pScale = p.size * (0.5f + alpha * 0.5f) 
                
                when (p.type) {
                    ParticleType.ROCKET -> {
                         drawLine(
                             color = Color.White.copy(alpha = 0.5f * _colorKnob),
                             start = Offset(px - p.vx * 0.05f * scaleX, py - p.vy * 0.05f * scaleY),
                             end = Offset(px, py),
                             strokeWidth = 2f
                         )
                         drawCircle(
                             color = Color.White.copy(alpha = _colorKnob),
                             radius = 2f * scaleX,
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
            frostSmall = 1f,
            frostMedium = 2f,
            frostLarge = 4f,
            tintAlpha = 0.04f,
            top = VisualizationLiquidScope(
                saturation = 2.0f,
                dispersion = .5f,
                curve = 0.02f,
                refraction = 0.2f,
            ),
            bottom = VisualizationLiquidScope(
                saturation = 1f,
                dispersion = .2f,
                curve = 0.2f,
                refraction = 0.1f,
            ),
            title = CenterPanelStyle(
                scope = VisualizationLiquidScope(contrast = 1.5f, saturation = 2f),
                titleColor = OrpheusColors.synthPink,
                borderColor = OrpheusColors.synthPink.copy(alpha = 0.4f),
                titleElevation = 8.dp
            )
        )
    }
}
