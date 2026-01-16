package org.balch.orpheus.core.audio.dsp.synth.grains

import org.balch.orpheus.core.audio.dsp.synth.SynthDsp

class GranularProcessor {

    private var numChannels = 2
    private var lowFidelity = false
    private var bypass = false
    private var silence = false
    
    // Large buffer management
    private val maxBlockSize = 256 // Standard block size
    private val bufferSize = 131072 // 128k
    
    // Audio Buffers
    private val buffer = List(2) { AudioBuffer(bufferSize) }
    
    // DSP Units
    private val looper = LoopingSamplePlayer()
    
    // Filters
    private val fbFilter = List(2) { SynthDsp.StateVariableFilter() }
    private val lpFilter = List(2) { SynthDsp.StateVariableFilter() }
    private val hpFilter = List(2) { SynthDsp.StateVariableFilter() }
    
    // State
    private val fbState = List(2) { FloatArray(maxBlockSize) }
    private var freezeLp = 0f
    
    // Parameter smoothers - prevent clicks when parameters change abruptly
    private val dryWetSmoother = OnePoleSmoother(0.002f) // ~5ms smoothing at 44.1kHz
    private val feedbackSmoother = OnePoleSmoother(0.002f)
    private val positionSmoother = OnePoleSmoother(0.005f) // Slightly slower for position
    private val sizeSmoother = OnePoleSmoother(0.005f)
    private val pitchSmoother = OnePoleSmoother(0.01f) // Faster for pitch
    private val densitySmoother = OnePoleSmoother(0.002f)
    private val textureSmoother = OnePoleSmoother(0.002f)
    private var smoothersInitialized = false
    
    // Mode crossfade state - smooth transitions when switching modes
    private var currentMode: GrainsMode = GrainsMode.GRANULAR
    private var previousMode: GrainsMode = GrainsMode.GRANULAR
    private var modeFadePhase = 1.0f // 0 = fully previous mode, 1 = fully current mode
    private val modeFadeDuration = 512 // samples (~12ms at 44.1kHz)
    private var modeFadeSamplesRemaining = 0
    
    // Temporary buffers for mode crossfade
    private var crossfadeLeftBuffer = FloatArray(256)
    private var crossfadeRightBuffer = FloatArray(256)
    
    // Parameters
    val parameters = GrainsParameters()

    fun init(numChannels: Int) {
        this.numChannels = numChannels
        looper.init(numChannels)
        
        fbFilter.forEach { it.init() }
        lpFilter.forEach { it.init() }
        hpFilter.forEach { it.init() }
        
        buffer.forEach { it.clear() }
        
        // Initialize smoothers with default values
        dryWetSmoother.init(0.5f)
        feedbackSmoother.init(0.0f)
        positionSmoother.init(0.2f)
        sizeSmoother.init(0.5f)
        pitchSmoother.init(0.0f)
        densitySmoother.init(0.5f)
        textureSmoother.init(0.5f)
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
        
        // --- 0. Mode Change Detection ---
        // Detect mode changes and initiate crossfade
        if (parameters.mode != currentMode) {
            previousMode = currentMode
            currentMode = parameters.mode
            modeFadeSamplesRemaining = modeFadeDuration
            modeFadePhase = 0f
            
            // Ensure crossfade buffers are large enough
            if (crossfadeLeftBuffer.size < size) {
                crossfadeLeftBuffer = FloatArray(size)
                crossfadeRightBuffer = FloatArray(size)
            }
        }
        
        // --- 1. Apply Feedback & Write to Buffer ---
        
        // One-pole smoothing for freeze parameter to prevent clicks
        // ONE_POLE(freeze_lp_, parameters_.freeze ? 1.0f : 0.0f, 0.0005f)
        val freezeTarget = if (parameters.freeze) 1.0f else 0.0f
        freezeLp += 0.0005f * (freezeTarget - freezeLp)
        
        // Smooth feedback parameter to prevent clicks
        val feedback = feedbackSmoother.process(parameters.feedback)
        // High-pass filter in feedback loop to prevent DC build-up
        // cutoff = (20 + 100 * fb^2) / SampleRate
        val cutoff = (20.0f + 100.0f * feedback * feedback) / SynthDsp.SAMPLE_RATE
        
        fbFilter.forEach { it.setFq(cutoff, 1.0f) }
        
        val fbGain = feedback * (1.0f - freezeLp)
        
        // Temporary buffers for input mixing (mutable)
        // We shouldn't modify input arrays in place if they are const in C++, but here they are passed-in arrays.
        // Safer to use a local temp array or write directly to buffer if meticulous.
        // We need to mix input + feedback, then write to buffer.
        
        // Let's iterate sample by sample to avoid extra allocations
        val stride = 1
        
        for (i in 0 until size) {
            var inL = inputLeft[i]
            var inR = if (numChannels == 2) inputRight[i] else inL
            
            var fbL = fbState[0][i]
            var fbR = if (numChannels == 2) fbState[1][i] else fbL
            
            // High pass filter the feedback signal
            fbL = fbFilter[0].processHp(fbL)
            if (numChannels == 2) {
                fbR = fbFilter[1].processHp(fbR)
            }
            
            // Soft limit and mix
            // in += fb_gain * (SoftLimit(fb_gain * 1.4 * fb + in) - in)
            // effective: in + fb_gain * (saturated_mix - in)
            val mixL = fbGain * 1.4f * fbL + inL
            val mixR = fbGain * 1.4f * fbR + inR
            
            val softMixL = SynthDsp.softClip(mixL)
            val softMixR = SynthDsp.softClip(mixR)
            
            inL += fbGain * (softMixL - inL)
            inR += fbGain * (softMixR - inR)
            
            // Write to AudioBuffers
            // WriteFade handles the logic of "don't write if frozen" internally via the 'write' flag?
            // Yes: buffer_16_[i].WriteFade(..., !parameters_.freeze);
            
            // But WriteFade expects a block.
            // We can't do sample-by-sample here efficiently if AudioBuffer is block-based helper.
            // AudioBuffer.WriteFade is designed for blocks.
            // So we should prepare the "mixed input" block first.
        }
        
        // RE-IMPLEMENTATION with Block Buffers
        val mixedInputL = FloatArray(size)
        val mixedInputR = if (numChannels == 2) FloatArray(size) else mixedInputL
        
        for (i in 0 until size) {
            var inL = inputLeft[i]
            var inR = if (numChannels == 2) inputRight[i] else inL
            
            var fbL = fbState[0][i]
            var fbR = if (numChannels == 2) fbState[1][i] else fbL
            
            fbL = fbFilter[0].processHp(fbL)
            if (numChannels == 2) {
                 fbR = fbFilter[1].processHp(fbR)
            }
            
            val mixL = fbGain * 1.4f * fbL + inL
            val mixR = fbGain * 1.4f * fbR + inR
            
            inL += fbGain * (SynthDsp.softClip(mixL) - inL)
            inR += fbGain * (SynthDsp.softClip(mixR) - inR)
            
            mixedInputL[i] = inL
            if (numChannels == 2) mixedInputR[i] = inR
        }
        
        // Write generated mixed input to buffers
        // WriteFade accepts !freeze
        buffer[0].writeFade(mixedInputL, 0, size, 1, !parameters.freeze)
        if (numChannels == 2) {
            buffer[1].writeFade(mixedInputR, 0, size, 1, !parameters.freeze)
        }
        
        // --- 2. Playback ---
        // We render directly into output buffers
        // Note: LoopingSamplePlayer plays INTO an interleaved or planar buffer? 
        // My port of `LoopingSamplePlayer.play` takes `out: FloatArray`. 
        // And it writes interleaved: L, R (or L, L).
        // I should probably adapt it to write separate channels or de-interleave later.
        // My port says: out[outIdx++] = l; out[outIdx++] = r;
        // So it expects a temporary stereo buffer of size 2*size.
        
        val tempOutput = FloatArray(size * 2)
        looper.play(buffer, parameters, tempOutput, 0, size)
        
        // De-interleave tempOutput back to outputLeft/Right
        for (i in 0 until size) {
            outputLeft[i] = tempOutput[i * 2]
            if (numChannels == 2) {
                outputRight[i] = tempOutput[i * 2 + 1]
            }
        }
        
        // --- 2b. Mode Crossfade Envelope ---
        // Apply fade envelope when switching modes to prevent clicks
        if (modeFadeSamplesRemaining > 0) {
            val fadeIncrement = 1.0f / modeFadeDuration
            
            for (i in 0 until size) {
                if (modeFadeSamplesRemaining > 0) {
                    // Calculate envelope: quick fade-out then fade-in
                    // First half: fade out (1.0 -> 0.0)
                    // Second half: fade in (0.0 -> 1.0)
                    val fadeProgress = 1.0f - (modeFadeSamplesRemaining.toFloat() / modeFadeDuration)
                    
                    // Use a V-shaped envelope: out then in
                    // 0.0 -> 0.5: envelope goes 1.0 -> 0.0 (fade out)
                    // 0.5 -> 1.0: envelope goes 0.0 -> 1.0 (fade in)
                    val envelope = if (fadeProgress < 0.5f) {
                        1.0f - (fadeProgress * 2.0f) // 1.0 -> 0.0
                    } else {
                        (fadeProgress - 0.5f) * 2.0f // 0.0 -> 1.0
                    }
                    
                    // Apply smooth envelope (use cosine for smoother curve)
                    val smoothEnvelope = envelope * envelope * (3f - 2f * envelope) // Smoothstep
                    
                    outputLeft[i] *= smoothEnvelope
                    if (numChannels == 2) {
                        outputRight[i] *= smoothEnvelope
                    }
                    
                    modeFadeSamplesRemaining--
                    modeFadePhase = fadeProgress
                }
            }
        }
        
        // --- 3. Post-Processing Filters (Texture) ---
        // For Looping Delay: texture controls filter
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
        hpFilter.forEach { it.setFq(clampedHp, 1.0f) } // Q=1.0 for HP
        
        for (i in 0 until size) {
            // Apply LP
            outputLeft[i] = lpFilter[0].processLp(outputLeft[i])
            if (numChannels == 2) outputRight[i] = lpFilter[1].processLp(outputRight[i])
            
            // Apply HP
            outputLeft[i] = hpFilter[0].processHp(outputLeft[i])
            if (numChannels == 2) outputRight[i] = hpFilter[1].processHp(outputRight[i])
        }
        
        // --- 4. Store Feedback for Next Block ---
        // "This is what is fed back. Reverb is not fed back."
        // We store the processed output (Dry? No, this is Wet signal from Looper) into fbState.
        // Note: The C++ code copies 'out_' to 'fb_' BEFORE Reverb and Dry/Wet mix.
        // So fbState captures the purely wet signal (filtered).
        outputLeft.copyInto(fbState[0], 0, 0, size)
        if (numChannels == 2) {
             outputRight.copyInto(fbState[1], 0, 0, size)
        }
        
        // --- 5. Dry/Wet Mix ---
        // Use per-sample smoothing to prevent clicks when dryWet changes.
        // This is critical - sudden changes in mix cause discontinuities.
        val targetDryWet = mpParameter(parameters.dryWet)
        
        for (i in 0 until size) {
            // Smooth the dryWet parameter sample-by-sample
            val dryWet = dryWetSmoother.process(targetDryWet)
            
            val wetL = outputLeft[i]
            val wetR = if (numChannels == 2) outputRight[i] else wetL
            
            val inL = inputLeft[i]
            val inR = if (numChannels == 2) inputRight[i] else inL
            
            // Linear blend with smoothed parameter
            outputLeft[i] = inL * (1f - dryWet) + wetL * dryWet
            if (numChannels == 2) outputRight[i] = inR * (1f - dryWet) + wetR * dryWet
        }
    }
    
    // Simplification for dry/wet curve
    private fun mpParameter(value: Float): Float {
        return value.coerceIn(0f, 1f)
    }
}
