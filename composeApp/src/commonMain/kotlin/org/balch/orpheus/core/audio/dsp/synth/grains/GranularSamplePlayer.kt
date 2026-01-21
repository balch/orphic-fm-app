package org.balch.orpheus.core.audio.dsp.synth.grains

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Granular Sample Player - a port of Mutable Instruments Clouds' granular processor.
 * 
 * This generates overlapping grains from a recording buffer with:
 * - POSITION: Where in the buffer to read grains from
 * - SIZE: Duration of each grain
 * - PITCH: Playback speed of each grain (V/Oct)
 * - DENSITY: How many grains overlap (grain rate)
 * - TEXTURE: Grain window shape (triangle to Hann)
 * 
 * The DENSITY parameter determines whether grains are scheduled:
 * - Deterministically (regular intervals when density < 0.5)
 * - Probabilistically (random timing when density > 0.5)
 */
class GranularSamplePlayer(
    private val maxGrains: Int = 32
) {
    // Grain pool
    private val grains = Array(maxGrains) { GranularGrain() }
    private val availableGrains = IntArray(maxGrains)
    
    // Envelope buffer for batch processing
    private val envelopeBuffer = FloatArray(256)
    
    // State
    private var numChannels = 2
    private var numGrains = 0f
    private var gainNormalization = 1f
    private var grainSizeHint = 1024f
    private var grainRatePhasor = 0f
    
    fun init(numChannels: Int) {
        this.numChannels = numChannels
        grains.forEach { it.init() }
        numGrains = 0f
        gainNormalization = 1f
        grainSizeHint = 1024f
        grainRatePhasor = 0f
    }
    
    /**
     * Play granular audio from the buffer.
     * 
     * @param buffer Audio buffers [left, right]
     * @param parameters Granular parameters
     * @param out Output array (interleaved stereo)
     * @param startOffset Starting index in output
     * @param size Number of samples to process
     */
    fun play(
        buffer: List<AudioBuffer>,
        parameters: GrainsParameters,
        out: FloatArray,
        startOffset: Int,
        size: Int
    ) {
        // Calculate overlap from density
        // Density < 0.47: deterministic seeding, overlap increases as density decreases
        // Density 0.47-0.53: dead zone, NO grains are generated
        // Density > 0.53: probabilistic seeding, overlap increases as density increases
        val density = parameters.smoothedDensity()
        val useDeterministicSeed = density < 0.5f
        
        // Dead zone: at center, no grains
        val inDeadZone = density > 0.47f && density < 0.53f
        
        val overlap = when {
            density >= 0.53f -> (density - 0.53f) * 2.12f
            density <= 0.47f -> (0.47f - density) * 2.12f
            else -> 0f // Dead zone
        }
        
        // Cube the overlap for more dramatic effect
        val overlapCubed = overlap * overlap * overlap
        val targetNumGrains = maxGrains * overlapCubed
        
        // Calculate seeding probability/interval
        val p = if (inDeadZone) 0f else targetNumGrains / grainSizeHint
        val spaceBetweenGrains = if (inDeadZone || targetNumGrains < 0.001f) {
            Float.MAX_VALUE // Never seed
        } else {
            grainSizeHint / targetNumGrains
        }
        
        // Only reset phasor for probabilistic mode (keeps deterministic mode regular)
        if (useDeterministicSeed) {
            // Keep phasor running for regular intervals
        } else {
            grainRatePhasor = -1000f // Reset for probabilistic (random) mode
        }
        
        // Build list of available grains
        var numAvailableGrains = 0
        for (i in 0 until maxGrains) {
            if (!grains[i].active) {
                availableGrains[numAvailableGrains] = i
                numAvailableGrains++
            }
        }
        
        // Schedule new grains sample by sample (skip if in dead zone)
        var seedTrigger = parameters.trigger
        if (!inDeadZone || seedTrigger) {
            for (t in 0 until size) {
                grainRatePhasor += 1f
                
                val seedProbabilistic = !useDeterministicSeed && 
                        !inDeadZone &&
                        Random.nextFloat() < p && 
                        targetNumGrains > numGrains
                
                val seedDeterministic = useDeterministicSeed && 
                        !inDeadZone &&
                        grainRatePhasor >= spaceBetweenGrains
                
                val seed = seedProbabilistic || seedDeterministic || seedTrigger
                
                if (numAvailableGrains > 0 && seed) {
                    numAvailableGrains--
                    val index = availableGrains[numAvailableGrains]
                    
                    scheduleGrain(
                        grain = grains[index],
                        parameters = parameters,
                        preDelay = t,
                        bufferSize = buffer[0].size,
                        bufferHead = buffer[0].head - size + t
                    )
                    
                    grainRatePhasor = 0f
                    seedTrigger = false
                }
            }
        }
        
        // Clear output buffer
        for (i in 0 until size * 2) {
            out[startOffset + i] = 0f
        }
        
        // Overlap-add all active grains
        for (i in 0 until maxGrains) {
            val grain = grains[i]
            if (grain.active) {
                grain.overlapAdd(buffer, out, startOffset, size, numChannels)
            }
        }
        
        // Calculate normalization factor
        val activeGrains = maxGrains - numAvailableGrains
        
        // Smooth the grain count
        val activeFloat = activeGrains.toFloat()
        if (activeFloat > numGrains) {
            numGrains += 0.9f * (activeFloat - numGrains)
        } else {
            numGrains += 0.2f * (activeFloat - numGrains)
        }
        
        // Normalize based on active grain count
        val targetNorm = if (numGrains > 2f) {
            1f / sqrt(numGrains - 1f)
        } else {
            1f
        }
        
        // Window shape affects gain (sharper windows need more gain)
        val windowShape = parameters.smoothedTexture().coerceIn(0f, 0.75f) * 1.333f
        val windowGain = (1f + 2f * windowShape).coerceIn(1f, 2f)
        val finalNorm = targetNorm * (1f + (windowGain - 1f) * overlapCubed)
        
        // Apply normalization
        for (t in 0 until size) {
            gainNormalization += 0.01f * (finalNorm - gainNormalization)
            out[startOffset + t * 2] *= gainNormalization
            out[startOffset + t * 2 + 1] *= gainNormalization
        }
    }
    
    private fun scheduleGrain(
        grain: GranularGrain,
        parameters: GrainsParameters,
        preDelay: Int,
        bufferSize: Int,
        bufferHead: Int
    ) {
        val position = parameters.smoothedPosition()
        
        // Pitch in semitones: map 0-1 to -24 to +24 semitones
        val pitchSemitones = (parameters.smoothedPitch() - 0.5f) * 48f
        val pitchRatio = 2f.pow(pitchSemitones / 12f)
        val invPitchRatio = 2f.pow(-pitchSemitones / 12f)
        
        // Grain size: map 0-1 to ~5ms to ~500ms at 44.1kHz
        // Using a lookup table approximation from original
        val sizeParam = parameters.smoothedSize()
        val grainSize = (64f + sizeParam * sizeParam * sizeParam * 8000f)
            .coerceIn(64f, bufferSize * 0.25f)
        
        // Window shape from texture (0-0.75 range, maps to triangle-to-Hann)
        val windowShape = parameters.smoothedTexture().coerceIn(0f, 0.75f) * 1.333f
        
        // Random stereo panning
        val pan = 0.5f + parameters.stereoSpread * (Random.nextFloat() - 0.5f)
        val gainL: Float
        val gainR: Float
        
        if (numChannels == 1) {
            // Equal power panning for mono
            gainL = cos((1f - pan) * PI.toFloat() / 2f)
            gainR = cos(pan * PI.toFloat() / 2f)
        } else {
            // Linear crossfade for stereo
            if (pan < 0.5f) {
                gainL = 1f
                gainR = 2f * pan
            } else {
                gainR = 1f
                gainL = 2f * (1f - pan)
            }
        }
        
        // Limit grain size when pitch is up to avoid overrunning buffer
        var actualGrainSize = grainSize
        if (pitchRatio > 1f) {
            actualGrainSize = min(grainSize, bufferSize * 0.25f * invPitchRatio)
        }
        
        // Calculate available buffer space
        val eatenByPlayHead = actualGrainSize * pitchRatio
        val eatenByRecordingHead = actualGrainSize
        val available = bufferSize - eatenByPlayHead - eatenByRecordingHead
        
        // Calculate start position
        val start = bufferHead - (position * available + eatenByPlayHead).toInt()
        
        // Start the grain
        grain.start(
            preDelay = preDelay,
            bufferSize = bufferSize,
            start = start,
            width = actualGrainSize.toInt(),
            phaseIncrement = (pitchRatio * 65536f).toInt(),
            windowShape = windowShape,
            gainL = gainL,
            gainR = gainR
        )
        
        // Update grain size hint
        grainSizeHint += 0.1f * (actualGrainSize - grainSizeHint)
    }
}

/**
 * A single grain for the granular sample player.
 * 
 * This is a more sophisticated grain than the simple Grain class,
 * with pre-delay support and proper envelope rendering.
 */
class GranularGrain {
    var active = false
        private set
    
    private var firstSample = 0
    private var width = 0
    private var phase = 0
    private var phaseIncrement = 65536 // 16.16 fixed point, 1.0 = 65536
    private var preDelay = 0
    
    private var envelopeSmoothness = 0f
    private var envelopeSlope = 0f
    private var envelopePhase = 2f
    private var envelopePhaseIncrement = 0f
    
    private var gainL = 1f
    private var gainR = 1f
    
    fun init() {
        active = false
        envelopePhase = 2f
    }
    
    fun start(
        preDelay: Int,
        bufferSize: Int,
        start: Int,
        width: Int,
        phaseIncrement: Int,
        windowShape: Float,
        gainL: Float,
        gainR: Float
    ) {
        this.preDelay = preDelay
        this.width = width
        this.firstSample = ((start % bufferSize) + bufferSize) % bufferSize
        this.phaseIncrement = phaseIncrement
        this.phase = 0
        this.envelopePhase = 0f
        this.envelopePhaseIncrement = 2f / width.toFloat()
        
        // Window shape: 0-0.5 controls slope (triangle variants)
        //              0.5-1 controls smoothness (towards Hann)
        if (windowShape >= 0.5f) {
            envelopeSmoothness = (windowShape - 0.5f) * 2f
            envelopeSlope = 0f
        } else {
            envelopeSmoothness = 0f
            envelopeSlope = 0.5f / (windowShape + 0.01f)
        }
        
        this.gainL = gainL
        this.gainR = gainR
        this.active = true
    }
    
    /**
     * Overlap-add this grain's output to the destination buffer.
     */
    fun overlapAdd(
        buffer: List<AudioBuffer>,
        destination: FloatArray,
        destOffset: Int,
        size: Int,
        numChannels: Int
    ) {
        if (!active) return
        
        var destIdx = destOffset
        var samplesToProcess = size
        
        // Handle pre-delay (grain doesn't start until pre_delay samples pass)
        while (preDelay > 0 && samplesToProcess > 0) {
            destIdx += 2
            samplesToProcess--
            preDelay--
        }
        
        val bufferSize = buffer[0].size
        
        while (samplesToProcess > 0) {
            // Render envelope
            var gain = envelopePhase
            gain = if (gain >= 1f) 2f - gain else gain
            
            // Apply window shaping
            if (envelopeSmoothness > 0f) {
                // Smooth towards Hann window using LUT approximation
                // For now, use simple cosine approximation
                val window = 0.5f * (1f - cos(gain * PI.toFloat()))
                gain += envelopeSmoothness * (window - gain)
            } else if (envelopeSlope > 0f) {
                // Sharper triangle window
                gain *= envelopeSlope
                if (gain > 1f) gain = 1f
            }
            
            envelopePhase += envelopePhaseIncrement
            
            if (envelopePhase >= 2f) {
                active = false
                break
            }
            
            // Calculate sample index with 16.16 fixed point
            val sampleIndex = firstSample + (phase shr 16)
            val fractional = phase and 0xFFFF
            
            // Read from buffer
            val l = buffer[0].readHermite(sampleIndex, fractional) * gain
            
            if (numChannels == 1) {
                destination[destIdx] += l * gainL
                destination[destIdx + 1] += l * gainR
            } else {
                val r = buffer[1].readHermite(sampleIndex, fractional) * gain
                // Crossfeed for stereo spread effect
                destination[destIdx] += l * gainL + r * (1f - gainR)
                destination[destIdx + 1] += r * gainR + l * (1f - gainL)
            }
            
            destIdx += 2
            phase += phaseIncrement
            samplesToProcess--
        }
    }
}
