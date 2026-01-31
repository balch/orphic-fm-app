package org.balch.orpheus.plugins.grains.engine

import org.balch.orpheus.core.audio.dsp.synth.OnePoleSmoother
import org.balch.orpheus.core.audio.dsp.synth.SynthDsp
import kotlin.math.pow

/**
 * Granular Processor - main processing class for the Grains unit.
 * 
 * This is a port of Mutable Instruments Clouds' GranularProcessor.
 * It supports multiple playback modes:
 * 
 * - GRANULAR: Classic granular synthesis with overlapping grains
 * - LOOPING_DELAY: Delay line (not frozen) or loop player (frozen)
 * - SHIMMER: Ethereal shimmer effect - grains pitched up with heavy diffusion
 * 
 * The processor handles:
 * - Feedback with soft-limiting and high-pass filtering
 * - Writing to circular audio buffers
 * - Mode-specific playback
 * - Post-processing filters (texture-controlled LP/HP)
 * - Pitch shifting (in looping delay mode)
 * - Dry/wet mixing
 */
class GranularProcessor {

    private var numChannels = 2
    private var lowFidelity = false
    private var bypass = false
    private var silence = false
    
    // Large buffer management
    private val maxBlockSize = 256
    private val bufferSize = 131072 // 128k samples (~3 sec at 44.1kHz)
    
    // Audio Buffers (stereo)
    private val buffer = List(2) { AudioBuffer(bufferSize) }
    
    // DSP Units - one for each mode
    private val granularPlayer = GranularSamplePlayer(maxGrains = 32)
    private val looper = LoopingSamplePlayer()
    private val shimmerPlayer = ShimmerPlayer(maxGrains = 32)
    private val pitchShifter = PitchShifter(bufferSize = 4096)
    private val diffuser = Diffuser()
    
    // Filters
    private val fbFilter = List(2) { SynthDsp.StateVariableFilter() }
    private val lpFilter = List(2) { SynthDsp.StateVariableFilter() }
    private val hpFilter = List(2) { SynthDsp.StateVariableFilter() }
    
    // Feedback state
    private val fbState = List(2) { FloatArray(maxBlockSize) }
    private var freezeLp = 0f
    
    // Parameter smoothers
    private val dryWetSmoother = OnePoleSmoother(0.002f)
    private val feedbackSmoother = OnePoleSmoother(0.1f)
    private var smoothersInitialized = false
    
    // Mode crossfade state
    private var currentMode: GrainsMode = GrainsMode.GRANULAR
    private var previousMode: GrainsMode = GrainsMode.GRANULAR
    private var modeFadePhase = 1.0f
    private val modeFadeDuration = 512
    private var modeFadeSamplesRemaining = 0
    
    // Temporary buffers
    private var tempOutputL = FloatArray(maxBlockSize)
    private var tempOutputR = FloatArray(maxBlockSize)
    
    // Parameters (exposed for external access)
    val parameters = GrainsParameters()

    fun init(numChannels: Int) {
        this.numChannels = numChannels
        
        granularPlayer.init(numChannels)
        looper.init(numChannels)
        shimmerPlayer.init(numChannels)
        pitchShifter.init()
        diffuser.init()
        
        fbFilter.forEach { it.init() }
        lpFilter.forEach { it.init() }
        hpFilter.forEach { it.init() }
        
        buffer.forEach { it.clear() }
        
        dryWetSmoother.init(0.5f)
        feedbackSmoother.init(0.0f)
        smoothersInitialized = true
    }
    
    fun process(
        inputLeft: FloatArray,
        inputRight: FloatArray,
        outputLeft: FloatArray,
        outputRight: FloatArray,
        size: Int
    ) {
        if (bypass) {
            inputLeft.copyInto(outputLeft, 0, 0, size)
            if (numChannels == 2) {
                inputRight.copyInto(outputRight, 0, 0, size)
            }
            return
        }
        
        if (silence) {
            outputLeft.fill(0f, 0, size)
            outputRight.fill(0f, 0, size)
            return
        }
        
        // Update parameter smoothing (must be called every block)
        parameters.updateSmoothing()
        
        // Ensure temp buffers are large enough
        if (tempOutputL.size < size) {
            tempOutputL = FloatArray(size)
            tempOutputR = FloatArray(size)
        }
        
        // --- 0. Mode Change Detection ---
        if (parameters.mode != currentMode) {
            previousMode = currentMode
            currentMode = parameters.mode
            modeFadeSamplesRemaining = modeFadeDuration
            modeFadePhase = 0f
        }
        
        // --- 1. Apply Feedback & Write to Buffer ---
        val freezeTarget = if (parameters.freeze) 1.0f else 0.0f
        freezeLp += 0.0005f * (freezeTarget - freezeLp)
        
        val feedback = feedbackSmoother.process(parameters.feedback)
        val cutoff = (20.0f + 100.0f * feedback * feedback) / SynthDsp.SAMPLE_RATE
        
        fbFilter.forEach { it.setFq(cutoff, 1.0f) }
        
        val fbGain = feedback * (1.0f - freezeLp)
        
        // Prepare mixed input with feedback
        val mixedInputL = FloatArray(size)
        val mixedInputR = if (numChannels == 2) FloatArray(size) else mixedInputL
        
        for (i in 0 until size) {
            var inL = inputLeft[i]
            var inR = if (numChannels == 2) inputRight[i] else inL
            
            var fbL = if (i < fbState[0].size) fbState[0][i] else 0f
            var fbR = if (numChannels == 2 && i < fbState[1].size) fbState[1][i] else fbL
            
            // High-pass filter the feedback to prevent DC buildup
            fbL = fbFilter[0].processHp(fbL)
            if (numChannels == 2) {
                fbR = fbFilter[1].processHp(fbR)
            }
            
            // Soft-limit and mix
            val mixL = fbGain * 1.4f * fbL + inL
            val mixR = fbGain * 1.4f * fbR + inR
            
            inL += fbGain * (SynthDsp.softClip(mixL) - inL)
            inR += fbGain * (SynthDsp.softClip(mixR) - inR)
            
            mixedInputL[i] = inL
            if (numChannels == 2) mixedInputR[i] = inR
        }
        
        // Write to audio buffers (unless frozen)
        buffer[0].writeFade(mixedInputL, 0, size, 1, !parameters.freeze)
        if (numChannels == 2) {
            buffer[1].writeFade(mixedInputR, 0, size, 1, !parameters.freeze)
        }
        
        // --- 2. Mode-Specific Playback ---
        val tempOutput = FloatArray(size * 2) // Interleaved stereo
        
        when (currentMode) {
            GrainsMode.GRANULAR -> {
                // Use granular sample player
                granularPlayer.play(buffer, parameters, tempOutput, 0, size)
            }
            
            GrainsMode.LOOPING_DELAY -> {
                // Use looping delay player
                looper.play(buffer, parameters, tempOutput, 0, size)
            }
            
            GrainsMode.SHIMMER -> {
                // Use shimmer player - grains pitched up with heavy diffusion
                shimmerPlayer.play(buffer, parameters, tempOutput, 0, size)
            }
        }
        
        // De-interleave to output
        for (i in 0 until size) {
            outputLeft[i] = tempOutput[i * 2]
            outputRight[i] = if (numChannels == 2) tempOutput[i * 2 + 1] else outputLeft[i]
        }
        
        // --- 2b. Pitch Shifter (for Looping Delay mode when not frozen) ---
        if (currentMode == GrainsMode.LOOPING_DELAY && 
            (!parameters.freeze || looper.synchronized)) {
            
            // Calculate pitch ratio from semitones
            val pitchSemitones = (parameters.smoothedPitch() - 0.5f) * 48f
            val pitchRatio = 2f.pow(pitchSemitones / 12f)
            
            pitchShifter.setRatio(pitchRatio)
            pitchShifter.setSize(parameters.smoothedSize())
            
            // Process pitch shifting in-place
            pitchShifter.process(outputLeft, outputRight, tempOutputL, tempOutputR, size)
            
            // Copy back to output
            tempOutputL.copyInto(outputLeft, 0, 0, size)
            tempOutputR.copyInto(outputRight, 0, 0, size)
        }
        
        // --- 2c. Mode Crossfade Envelope ---
        if (modeFadeSamplesRemaining > 0) {
            for (i in 0 until size) {
                if (modeFadeSamplesRemaining > 0) {
                    val fadeProgress = 1.0f - (modeFadeSamplesRemaining.toFloat() / modeFadeDuration)
                    
                    // V-shaped envelope: fade out then fade in
                    val envelope = if (fadeProgress < 0.5f) {
                        1.0f - (fadeProgress * 2.0f)
                    } else {
                        (fadeProgress - 0.5f) * 2.0f
                    }
                    
                    // Smoothstep for smoother curve
                    val smoothEnvelope = envelope * envelope * (3f - 2f * envelope)
                    
                    outputLeft[i] *= smoothEnvelope
                    if (numChannels == 2) {
                        outputRight[i] *= smoothEnvelope
                    }
                    
                    modeFadeSamplesRemaining--
                    modeFadePhase = fadeProgress
                }
            }
        }
        
        // --- 2d. Diffusion ---
        // In GRANULAR mode: texture > 0.75 activates diffusion (smears transients)
        // In LOOPING_DELAY: density controls diffusion
        // In SHIMMER: always heavy diffusion (0.7-1.0)
        val diffusionAmount = when (currentMode) {
            GrainsMode.GRANULAR -> {
                val texture = parameters.smoothedTexture()
                if (texture > 0.75f) (texture - 0.75f) * 4f else 0f
            }
            GrainsMode.SHIMMER -> {
                // Shimmer always uses heavy diffusion (0.7 to 1.0 based on density)
                0.7f + parameters.smoothedDensity() * 0.3f
            }
            GrainsMode.LOOPING_DELAY -> {
                // In delay mode, density controls diffusion
                parameters.smoothedDensity()
            }
        }
        diffuser.setAmount(diffusionAmount)
        diffuser.process(outputLeft, outputRight, size)
        
        // --- 3. Post-Processing Filters ---
        // Texture controls LP/HP filter sweep for looping delay mode only
        if (currentMode == GrainsMode.LOOPING_DELAY) {
            val texture = parameters.texture
            val lpCutoff = 0.5f * SynthDsp.semitonesToRatio(
                (if (texture < 0.5f) texture - 0.5f else 0.0f) * 216.0f
            )
            val hpCutoff = 0.25f * SynthDsp.semitonesToRatio(
                (if (texture < 0.5f) -0.5f else texture - 1.0f) * 216.0f
            )
            
            val clampedLp = lpCutoff.coerceIn(0f, 0.499f)
            val clampedHp = hpCutoff.coerceIn(0f, 0.499f)
            
            val lpQ = 1.0f + 3.0f * (1.0f - feedback) * (0.5f - clampedLp)
            
            lpFilter.forEach { it.setFq(clampedLp, lpQ) }
            hpFilter.forEach { it.setFq(clampedHp, 1.0f) }
            
            for (i in 0 until size) {
                outputLeft[i] = lpFilter[0].processLp(outputLeft[i])
                if (numChannels == 2) outputRight[i] = lpFilter[1].processLp(outputRight[i])
                
                outputLeft[i] = hpFilter[0].processHp(outputLeft[i])
                if (numChannels == 2) outputRight[i] = hpFilter[1].processHp(outputRight[i])
            }
        }
        // For Granular mode, texture controls window shape (handled in GranularSamplePlayer)
        
        // --- 4. Store Feedback for Next Block ---
        // Capture wet signal BEFORE dry/wet mix for feedback path
        for (i in 0 until size) {
            if (i < fbState[0].size) fbState[0][i] = outputLeft[i]
            if (numChannels == 2 && i < fbState[1].size) fbState[1][i] = outputRight[i]
        }
        
        // --- 5. Dry/Wet Mix ---
        val targetDryWet = parameters.dryWet.coerceIn(0f, 1f)
        
        for (i in 0 until size) {
            val dryWet = dryWetSmoother.process(targetDryWet)
            
            val wetL = outputLeft[i]
            val wetR = if (numChannels == 2) outputRight[i] else wetL
            
            val inL = inputLeft[i]
            val inR = if (numChannels == 2) inputRight[i] else inL
            
            outputLeft[i] = inL * (1f - dryWet) + wetL * dryWet
            if (numChannels == 2) {
                outputRight[i] = inR * (1f - dryWet) + wetR * dryWet
            }
        }
    }
}
