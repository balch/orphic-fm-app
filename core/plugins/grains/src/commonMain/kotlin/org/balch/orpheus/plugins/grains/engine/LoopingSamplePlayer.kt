package org.balch.orpheus.plugins.grains.engine

import kotlin.math.min
import kotlin.math.pow

const val K_LOOP_CROSSFADE_DURATION = 64.0f

/**
 * Looping Sample Player - a port of Mutable Instruments Clouds' looping delay mode.
 * 
 * This implements the "Looping Delay" mode from Clouds which provides:
 * - When NOT frozen: A simple delay line with smooth position control
 * - When frozen: A looped section of the buffer with crossfade at loop boundaries
 * 
 * In REVERSE mode, the loop is played backwards through the buffer.
 * 
 * This is NOT granular synthesis - it's continuous buffer playback with optional looping.
 */
class LoopingSamplePlayer {
    // Phase tracks playback position within the loop (when frozen)
    private var phase = 0f
    
    // Delay line state (when not frozen)
    private var currentDelay = 0f
    
    // Loop parameters (when frozen)
    private var loopPoint = 0f
    private var loopDuration = 0f
    private var tailStart = 0f
    private var tailDuration = 1.0f
    private var loopReset = 0f
    
    // Tap tempo synchronization
    var synchronized = false
        private set
    
    private var numChannels = 2
    private var tapDelay = 0
    private var tapDelayCounter = 0
    
    fun init(numChannels: Int) {
        this.numChannels = numChannels
        phase = 0f
        currentDelay = 1000f // Start with ~23ms delay
        loopPoint = 0f
        loopDuration = 22050f // ~0.5s at 44.1kHz - matches size=0.5 default
        tailStart = 0f
        tailDuration = 1.0f
        loopReset = 0f
        tapDelay = 0
        tapDelayCounter = 0
        synchronized = false
    }
    
    /**
     * Play audio from the buffer.
     * 
     * When freeze is OFF:
     *   - Simple delay line: reads from buffer at a position determined by position parameter
     *   - Position controls delay time (0 = short delay, 1 = long delay)
     *   - Pitch shifting is applied via external pitch shifter in GranularProcessor
     * 
     * When freeze is ON:
     *   - Loops a section of the frozen buffer
     *   - Position controls where in the buffer to loop
     *   - Size controls the loop duration
     *   - In REVERSE mode, plays the loop backwards
     *   - Crossfade at loop boundaries prevents clicks
     * 
     * @param buffer Audio buffers to read from [left, right]
     * @param parameters Processing parameters
     * @param out Output array (interleaved stereo: L,R,L,R,...)
     * @param startOffset Starting index in output array
     * @param size Number of samples to process
     */
    fun play(
        buffer: List<AudioBuffer>,
        parameters: GrainsParameters,
        out: FloatArray,
        startOffset: Int,
        size: Int
    ) {
        val maxDelay = buffer[0].size - K_LOOP_CROSSFADE_DURATION
        
        // Tap tempo synchronization logic
        tapDelayCounter += size
        if (tapDelayCounter > maxDelay) {
            tapDelay = 0
            tapDelayCounter = 0
            synchronized = false
        }
        
        if (parameters.trigger) {
            tapDelay = tapDelayCounter
            tapDelayCounter = 0
            synchronized = tapDelay > 128
            loopReset = phase
            phase = 0f
        }
        
        var outIdx = startOffset
        var samplesTodo = size
        
        if (!parameters.freeze) {
            // ====================================================================
            // NON-FROZEN MODE: Simple Delay Line
            // ====================================================================
            // This matches the original Clouds behavior: just a delay with smooth
            // position control. Pitch shifting is done via external pitch shifter.
            
            while (samplesTodo > 0) {
                // Target delay time from position parameter
                var targetDelay = parameters.smoothedPosition() * maxDelay
                if (synchronized) {
                    targetDelay = tapDelay.toFloat()
                }
                
                // Smooth the delay time to prevent clicks when position changes
                // Original: float error = (target_delay - current_delay_);
                //          float delay = current_delay_ + 0.00005f * error;
                val error = targetDelay - currentDelay
                val delay = currentDelay + 0.00005f * error
                currentDelay = delay
                
                // Calculate buffer read position
                // delay_int = (buffer->head() - 4 - remaining_samples + buffer->size()) << 12
                // delay_int -= static_cast<int32_t>(delay * 4096.0f)
                val head = buffer[0].head
                val bufferSize = buffer[0].size
                
                // Calculate position as fixed-point for interpolation
                // The original uses 12-bit fixed point (4096 = 1.0)
                val delayInt = ((head - 4 - samplesTodo + bufferSize) * 4096) - 
                               (delay * 4096f).toInt()
                
                // Extract integer and fractional parts
                val integral = delayInt shr 12
                val fractional = (delayInt and 0xFFF) shl 4 // Convert to 16-bit fractional
                
                val l = buffer[0].readHermite(integral, fractional)
                val r = if (numChannels == 2) {
                    buffer[1].readHermite(integral, fractional)
                } else {
                    l
                }
                
                // Write to output (interleaved stereo)
                out[outIdx++] = l
                out[outIdx++] = r
                
                samplesTodo--
            }
            
            phase = 0f
            
        } else {
            // ====================================================================
            // FROZEN MODE: Looping Playback (with optional reverse)
            // ====================================================================
            // The buffer is frozen (not recording). We play back a loop section.
            // In REVERSE mode, we play the loop backwards.
            
            // Calculate loop point from position parameter
            // loop_point = position * max_delay * 15/16 + crossfade_duration
            var loopPointTarget = parameters.smoothedPosition() * maxDelay * 15.0f / 16.0f
            loopPointTarget += K_LOOP_CROSSFADE_DURATION
            
            // Calculate loop duration from size parameter
            // loop_duration = (0.01 + 0.99 * d^3) * max_delay
            // With size=0.5: d³=0.125, duration≈17k samples (0.4s)
            // With size=1.0: d³=1.0, duration≈131k samples (3s)
            val d = parameters.smoothedSize()
            var loopDurationTarget = (0.01f + 0.99f * d * d * d) * maxDelay
            
            // Minimum loop duration - increased for usability
            // 4096 samples ≈ 93ms at 44.1kHz - a reasonable minimum loop
            val minLoopDuration = 4096f
            loopDurationTarget = loopDurationTarget.coerceAtLeast(minLoopDuration)
            
            if (synchronized) {
                loopDurationTarget = tapDelay.toFloat().coerceAtLeast(minLoopDuration)
            }
            
            // Ensure loop doesn't exceed buffer bounds
            if (loopPointTarget + loopDurationTarget >= maxDelay) {
                loopPointTarget = maxDelay - loopDurationTarget
            }
            loopPointTarget = loopPointTarget.coerceAtLeast(0f)
            
            // Phase increment: 1.0 for normal speed, or pitch-shifted if not synchronized
            // Pitch parameter is 0-1, map to -24 to +24 semitones (±2 octaves)
            // At 0.5 (center), pitch ratio is 1.0 (no change)
            val pitchSemitones = (parameters.smoothedPitch() - 0.5f) * 48f
            val phaseIncrement = if (synchronized) {
                1.0f
            } else {
                semitonesToRatio(pitchSemitones)
            }
            
            // In LOOPING_DELAY mode when frozen, we can optionally play backwards
            // For now, forward playback. Reverse could be a separate toggle.
            val isReverse = false // Could be exposed as UI toggle in future
            
            while (samplesTodo > 0) {
                // Check for loop wrap or start
                if (phase >= loopDuration || phase == 0f) {
                    if (phase >= loopDuration) {
                        loopReset = loopDuration
                    }
                    if (loopReset >= loopDuration) {
                        loopReset = loopDuration
                    }
                    
                    // Calculate tail start for crossfade
                    tailStart = loopDuration - loopReset + loopPoint
                    phase = 0f
                    tailDuration = min(
                        K_LOOP_CROSSFADE_DURATION,
                        K_LOOP_CROSSFADE_DURATION * phaseIncrement
                    )
                    
                    // Latch new loop parameters at the loop boundary
                    loopPoint = loopPointTarget
                    loopDuration = loopDurationTarget
                }
                
                // Advance phase (always forward, direction is handled in read position)
                phase += phaseIncrement
                
                // Calculate crossfade gain at loop boundaries
                var gain = 1.0f
                if (tailDuration != 0f) {
                    // Ramp from 0 to 1 over tailDuration samples
                    gain = (phase - phaseIncrement) / tailDuration
                    gain = gain.coerceIn(0f, 1f)
                }
                
                // === Main Loop Read ===
                val head = buffer[0].head.toFloat() - 4f
                val bufferSize = buffer[0].size.toFloat()
                
                // Calculate read position
                // For forward: position = head - (loop_duration - phase + loop_point)
                // For reverse: we want to read backwards through the loop
                // 
                // In reverse mode, when phase=0 we read from the END of the loop,
                // and when phase approaches loop_duration we read from the START.
                // 
                // Forward: readOffset = loopDuration - phase
                // Reverse: readOffset = phase (inverted)
                val readOffset = if (isReverse) {
                    // Reverse: start from end of loop (phase=0 -> offset=0, phase=dur -> offset=dur)
                    phase - phaseIncrement
                } else {
                    // Forward: start from beginning (phase=0 -> offset=dur, phase=dur -> offset=0)
                    loopDuration - (phase - phaseIncrement)
                }
                
                var readPos = head - (readOffset + loopPoint)
                
                // Wrap to buffer bounds
                while (readPos < 0) readPos += bufferSize
                while (readPos >= bufferSize) readPos -= bufferSize
                
                val integral = readPos.toInt()
                val fractional = ((readPos - integral) * 65536.0f).toInt()
                
                var l = buffer[0].readHermite(integral, fractional) * gain
                var r = if (numChannels == 2) {
                    buffer[1].readHermite(integral, fractional) * gain
                } else {
                    l
                }
                
                // === Crossfade Tail Read ===
                // When gain < 1, we're in the crossfade region at loop start
                // Blend in samples from the end of the previous loop
                if (gain < 1.0f) {
                    val fadeGain = 1.0f - gain
                    
                    // Tail position: similar to main read but from previous loop end
                    val tailPhase = phase - phaseIncrement
                    val tailOffset = if (isReverse) {
                        tailPhase
                    } else {
                        loopDuration - tailPhase
                    }
                    
                    var tailPos = head - (tailOffset + tailStart)
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
                
                // Write to output (interleaved stereo)
                out[outIdx++] = l
                out[outIdx++] = r
                
                samplesTodo--
            }
        }
    }
    
    private fun semitonesToRatio(semitones: Float): Float {
        return 2.0f.pow(semitones / 12.0f)
    }
}
