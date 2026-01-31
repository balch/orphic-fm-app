package org.balch.orpheus.plugins.grains.engine
import org.balch.orpheus.core.audio.dsp.synth.OnePoleSmoother
import org.balch.orpheus.core.audio.dsp.synth.ParameterInterpolator
import org.balch.orpheus.core.audio.dsp.synth.SynthDsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Shimmer Player - Creates ethereal shimmer effects.
 * 
 * Shimmer combines:
 * - Granular synthesis with grains pitched UP (typically +12 semitones/octave)
 * - Heavy diffusion for washy, blurred textures
 * - High grain overlap for sustained, ambient sound
 * - Long grain durations for smoothness
 * 
 * The PITCH knob controls how much pitch-shift is applied:
 * - At center (0.5): +12 semitones (octave up) - classic shimmer
 * - Turn down: Less pitch shift, more subtle
 * - Turn up: More pitch shift, up to +24 semitones (2 octaves)
 * 
 * TEXTURE controls grain envelope smoothness (use higher values for shimmer)
 * DENSITY controls overlap and diffusion
 * SIZE controls grain length (longer = smoother shimmer)
 */
class ShimmerPlayer(
    private val maxGrains: Int = 32
) {
    // Grain pool
    private val grains = Array(maxGrains) { ShimmerGrain() }
    private val availableGrains = IntArray(maxGrains)
    
    // State
    private var numChannels = 2
    private var numGrains = 0f
    private var gainNormalization = 1f
    private var grainSizeHint = 4096f // Longer grains by default
    private var grainRatePhasor = 0f
    
    fun init(numChannels: Int) {
        this.numChannels = numChannels
        grains.forEach { it.init() }
        numGrains = 0f
        gainNormalization = 1f
        grainSizeHint = 4096f
        grainRatePhasor = 0f
    }
    
    /**
     * Play shimmer audio from the buffer.
     */
    fun play(
        buffer: List<AudioBuffer>,
        parameters: GrainsParameters,
        out: FloatArray,
        startOffset: Int,
        size: Int
    ) {
        // Shimmer always uses high overlap for washy sound
        // Density controls the amount of shimmer vs dry
        val density = parameters.smoothedDensity()
        
        // Always use probabilistic seeding for shimmer (more organic)
        // Fixed high overlap for shimmer effect
        val overlap = 0.7f + density * 0.3f // 0.7 to 1.0
        val overlapCubed = overlap * overlap * overlap
        val targetNumGrains = maxGrains * overlapCubed
        
        val p = targetNumGrains / grainSizeHint
        
        // Build list of available grains
        var numAvailableGrains = 0
        for (i in 0 until maxGrains) {
            if (!grains[i].active) {
                availableGrains[numAvailableGrains] = i
                numAvailableGrains++
            }
        }
        
        // Schedule new grains
        var seedTrigger = parameters.trigger
        for (t in 0 until size) {
            grainRatePhasor += 1f
            
            val shouldSeed = Random.nextFloat() < p && targetNumGrains > numGrains
            
            if (numAvailableGrains > 0 && (shouldSeed || seedTrigger)) {
                numAvailableGrains--
                val index = availableGrains[numAvailableGrains]
                
                scheduleGrain(
                    grain = grains[index],
                    parameters = parameters,
                    preDelay = t,
                    bufferSize = buffer[0].size,
                    bufferHead = buffer[0].head - size + t
                )
                
                seedTrigger = false
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
        
        // Normalize
        val activeGrains = maxGrains - numAvailableGrains
        val activeFloat = activeGrains.toFloat()
        numGrains += if (activeFloat > numGrains) {
            0.9f * (activeFloat - numGrains)
        } else {
            0.2f * (activeFloat - numGrains)
        }
        
        // Shimmer typically needs more gain due to high overlap
        val targetNorm = if (numGrains > 2f) {
            0.7f / kotlin.math.sqrt(numGrains - 1f)
        } else {
            0.7f
        }
        
        for (t in 0 until size) {
            gainNormalization += 0.01f * (targetNorm - gainNormalization)
            out[startOffset + t * 2] *= gainNormalization
            out[startOffset + t * 2 + 1] *= gainNormalization
        }
    }
    
    private fun scheduleGrain(
        grain: ShimmerGrain,
        parameters: GrainsParameters,
        preDelay: Int,
        bufferSize: Int,
        bufferHead: Int
    ) {
        val position = parameters.smoothedPosition()
        
        // Shimmer pitch: map 0-1 to +0 to +24 semitones
        // At center (0.5), we get +12 semitones (octave up) - classic shimmer
        // pitchKnob = 0.5 -> +12 semitones
        // pitchKnob = 0.0 -> +0 semitones (no shift)
        // pitchKnob = 1.0 -> +24 semitones (2 octaves)
        val shimmerPitch = parameters.smoothedPitch() * 24f
        val pitchRatio = 2f.pow(shimmerPitch / 12f)
        val invPitchRatio = 2f.pow(-shimmerPitch / 12f)
        
        // Shimmer uses longer grains for smoothness
        val sizeParam = parameters.smoothedSize()
        // Map to longer grain sizes: 2048 to 16384 samples (~46ms to ~370ms)
        val grainSize = (2048f + sizeParam * sizeParam * 14336f)
            .coerceIn(2048f, bufferSize * 0.25f)
        
        // Shimmer uses very smooth (Hann) envelopes
        val windowShape = 0.6f + parameters.smoothedTexture() * 0.4f // 0.6 to 1.0
        
        // Random stereo panning for width
        val pan = 0.5f + 0.4f * (Random.nextFloat() - 0.5f)
        val gainL: Float
        val gainR: Float
        
        if (pan < 0.5f) {
            gainL = 1f
            gainR = 2f * pan
        } else {
            gainR = 1f
            gainL = 2f * (1f - pan)
        }
        
        // Limit grain size when pitch is up
        var actualGrainSize = grainSize
        if (pitchRatio > 1f) {
            actualGrainSize = min(grainSize, bufferSize * 0.25f * invPitchRatio)
        }
        
        // Calculate start position
        val eatenByPlayHead = actualGrainSize * pitchRatio
        val eatenByRecordingHead = actualGrainSize
        val available = bufferSize - eatenByPlayHead - eatenByRecordingHead
        val start = bufferHead - (position * available + eatenByPlayHead).toInt()
        
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
        
        grainSizeHint += 0.1f * (actualGrainSize - grainSizeHint)
    }
}

/**
 * A grain optimized for shimmer effects - always uses smooth Hann-like envelopes.
 */
private class ShimmerGrain {
    var active = false
        private set
    
    private var firstSample = 0
    private var width = 0
    private var phase = 0
    private var phaseIncrement = 65536
    private var preDelay = 0
    
    private var envelopePhase = 2f
    private var envelopePhaseIncrement = 0f
    private var windowShape = 0.8f
    
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
        this.windowShape = windowShape
        this.gainL = gainL
        this.gainR = gainR
        this.active = true
    }
    
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
        
        // Handle pre-delay
        while (preDelay > 0 && samplesToProcess > 0) {
            destIdx += 2
            samplesToProcess--
            preDelay--
        }
        
        val bufferSize = buffer[0].size
        
        while (samplesToProcess > 0) {
            // Shimmer always uses smooth Hann-like envelope
            var gain = envelopePhase
            gain = if (gain >= 1f) 2f - gain else gain
            
            // Apply Hann window (smooth cosine-based)
            val window = 0.5f * (1f - cos(gain * PI.toFloat()))
            gain = gain + windowShape * (window - gain)
            
            envelopePhase += envelopePhaseIncrement
            
            if (envelopePhase >= 2f) {
                active = false
                break
            }
            
            // Read from buffer with fixed-point interpolation
            val sampleIndex = firstSample + (phase shr 16)
            val fractional = phase and 0xFFFF
            
            val l = buffer[0].readHermite(sampleIndex, fractional) * gain
            
            if (numChannels == 1) {
                destination[destIdx] += l * gainL
                destination[destIdx + 1] += l * gainR
            } else {
                val r = buffer[1].readHermite(sampleIndex, fractional) * gain
                destination[destIdx] += l * gainL
                destination[destIdx + 1] += r * gainR
            }
            
            destIdx += 2
            phase += phaseIncrement
            samplesToProcess--
        }
    }
}
