package org.balch.orpheus.core.audio.dsp.synth.grains

import kotlin.math.min
import kotlin.math.pow

const val K_LOOP_CROSSFADE_DURATION = 64.0f

class LoopingSamplePlayer {
    private var phase = 0f
    private var currentDelay = 0f
    private var loopPoint = 0f
    private var loopDuration = 0f
    private var tailStart = 0f
    private var tailDuration = 1.0f
    private var loopReset = 0f
    
    var synchronized = false
        private set
    
    private var numChannels = 2
    private var tapDelay = 0
    private var tapDelayCounter = 0
    
    // Granular synthesis engine for delay mode
    private val grainEngine = GrainEngine(maxGrains = 12)
    
    fun init(numChannels: Int) {
        this.numChannels = numChannels
        phase = 0f
        currentDelay = 0f
        loopPoint = 0f
        loopDuration = 0f
        tapDelay = 0
        tapDelayCounter = 0
        synchronized = false
        tailDuration = 1.0f
        grainEngine.init(numChannels)
    }
    
    fun play(
        buffer: List<AudioBuffer>, // [0] -> left/mono, [1] -> right
        parameters: GrainsParameters,
        out: FloatArray,
        startOffset: Int,
        size: Int
    ) {
        val maxDelay = buffer[0].size - K_LOOP_CROSSFADE_DURATION
        
        // Tap tempo / sync logic
        // This advances the tap delay counter by the block size
        tapDelayCounter += size
        if (tapDelayCounter > maxDelay) {
            tapDelay = 0
            tapDelayCounter = 0
            synchronized = false
        }
        
        if (parameters.trigger) {
            tapDelay = tapDelayCounter
            tapDelayCounter = 0
            // If tap delay is reasonable (> 128 samples), we consider it synchronized
            synchronized = tapDelay > 128
            loopReset = phase
            phase = 0f
        }
        
        // Update parameter smoothing to avoid clicks
        parameters.updateSmoothing()
        
        // Output pointer emulation
        var outIdx = startOffset
        var samplesTodo = size
        
        if (!parameters.freeze) {
            // Granular Delay Mode
            // SIZE controls grain size, DENSITY controls grain overlap
            
            while (samplesTodo > 0) {
                var targetDelay = parameters.position * maxDelay
                if (synchronized) {
                    targetDelay = tapDelay.toFloat()
                }
                
                // Calculate grain size from SIZE parameter
                // Map SIZE (0-1) to grain size range: 5ms to 500ms at 44.1kHz
                // 5ms = 220 samples, 500ms = 22050 samples
                val minGrainSize = 220f  // ~5ms at 44.1kHz
                val maxGrainSize = 22050f // ~500ms at 44.1kHz
                val grainSize = minGrainSize + parameters.size * (maxGrainSize - minGrainSize)
                
                // Get current write head position
                val writeHead = buffer[0].head
                
                // Calculate pitch ratio from pitch parameter
                val pitchSemitones = parameters.smoothedPitch() * 24f // ±2 octaves
                val pitchRatio = semitonesToRatio(pitchSemitones)
                
                // Density controls grain overlap
                val density = parameters.smoothedDensity().coerceIn(0f, 1f)
                
                // Optional: Add slight spray/randomness for more organic texture
                // Could expose as separate parameter, for now keep it subtle
                val spray = grainSize * 0.05f // 5% position randomness
                
                // Get current mode from parameters (0=Granular, 1=Reverse, 2=Shimmer, etc.)
                val mode = when (parameters.mode) {
                    GrainsMode.GRANULAR -> 0
                    GrainsMode.REVERSE -> 1
                    GrainsMode.SHIMMER -> 2
                }
                
                // Process left channel
                val l = grainEngine.process(
                    buffer = buffer,
                    writeHead = writeHead,
                    grainSize = grainSize,
                    density = density,
                    delayTime = targetDelay,
                    pitchRatio = pitchRatio,
                    spray = spray,
                    mode = mode,
                    channelIndex = 0
                )
                
                // Process right channel (or duplicate left for mono)
                val r = if (numChannels == 2) {
                    grainEngine.process(
                        buffer = buffer,
                        writeHead = writeHead,
                        grainSize = grainSize,
                        density = density,
                        delayTime = targetDelay,
                        pitchRatio = pitchRatio,
                        spray = spray,
                        mode = mode,
                        channelIndex = 1
                    )
                } else {
                    l
                }
                
                // Write to output
                if (numChannels == 1) {
                    out[outIdx++] = l
                    out[outIdx++] = l
                } else {
                    out[outIdx++] = l
                    out[outIdx++] = r
                }
                
                samplesTodo--
            }
            phase = 0f
            
        } else {
            // Freeze / Looping Mode
            var loopPointVar = parameters.position * maxDelay * 15.0f / 16.0f
            loopPointVar += K_LOOP_CROSSFADE_DURATION
            
            val d = parameters.size
            var loopDurationVar = (0.01f + 0.99f * d * d * d) * maxDelay
            
            if (synchronized) {
                loopDurationVar = tapDelay.toFloat()
            }
            
            if (loopPointVar + loopDurationVar >= maxDelay) {
                loopPointVar = maxDelay - loopDurationVar
            }
            
            val phaseIncrement = if (synchronized) 1.0f else semitonesToRatio(parameters.smoothedPitch())
            
            while (samplesTodo > 0) {
                if (phase >= loopDuration || phase == 0f) {
                    if (phase >= loopDuration) {
                        loopReset = loopDuration
                    }
                    if (loopReset >= loopDuration) {
                        loopReset = loopDuration
                    }
                    tailStart = loopDuration - loopReset + loopPoint
                    phase = 0f
                    tailDuration = min(K_LOOP_CROSSFADE_DURATION, K_LOOP_CROSSFADE_DURATION * phaseIncrement)
                    
                    loopPoint = loopPointVar
                    loopDuration = loopDurationVar
                }
                
                phase += phaseIncrement
                
                var gain = 1.0f
                if (tailDuration != 0f) {
                    gain = phase / tailDuration
                    gain = gain.coerceIn(0f, 1f)
                }
                
                // Read main loop
                // "delay_int = (buffer->head() - 4 + buffer->size()) << 12;"
                // Here it doesn't depend on loop index 'size'! 
                // It seems in Freeze mode, we allow the write head to move away (we are frozen, so we ignore input, but the buffer head tracks where input WOULD be).
                // Actually, in Freeze, GranularProcessor DOES NOT write to buffer (unless spectral).
                // So head is static?
                // Wait, GranularProcessor::ProcessGranular:
                // "if (playback_mode_ != PLAYBACK_MODE_SPECTRAL) ... buffer_16_[i].WriteFade(..., !parameters_.freeze);"
                // If freeze is true, WriteFade is called with write=false.
                // WriteFade(..., false) does not advance write_head_.
                // So head is static.
                
                // So logical 'now' is buffer.head.
                // position = head - (loop_duration - phase + loop_point)
                
                val head = buffer[0].head.toFloat() - 4f
                val bufferSize = buffer[0].size.toFloat()
                
                // Main read
                var readPos = head - (loopDuration - phase + loopPoint)
                // Normalize
                while (readPos < 0) readPos += bufferSize
                while (readPos >= bufferSize) readPos -= bufferSize
                
                val integral = readPos.toInt()
                val fractional = ((readPos - integral) * 65536.0f).toInt()
                
                var l = buffer[0].readHermite(integral, fractional)
                var r = 0f
                if (numChannels == 2) {
                    r = buffer[1].readHermite(integral, fractional)
                }
                
                l *= gain
                r *= gain
                
                // Crossfade from old loop ("Tail")
                if (gain != 1.0f) {
                    val fadeGain = 1.0f - gain
                    // position = head - (-phase + tail_start)
                    var tailPos = head - (-phase + tailStart)
                    while (tailPos < 0) tailPos += bufferSize
                    while (tailPos >= bufferSize) tailPos -= bufferSize
                    
                    val tIntegral = tailPos.toInt()
                    val tFractional = ((tailPos - tIntegral) * 65536.0f).toInt()
                    
                    val tl = buffer[0].readHermite(tIntegral, tFractional)
                    l += tl * fadeGain
                    
                    if (numChannels == 2) {
                        val tr = buffer[1].readHermite(tIntegral, tFractional)
                        r += tr * fadeGain
                    }
                }
                
                if (numChannels == 1) {
                    out[outIdx++] = l
                    out[outIdx++] = l // Mono expand
                } else {
                    out[outIdx++] = l
                    out[outIdx++] = r
                }
                
                samplesTodo--
            }
        }
    }
    
    private fun semitonesToRatio(semitones: Float): Float {
        return 2.0f.pow(semitones / 12.0f)
    }
}
