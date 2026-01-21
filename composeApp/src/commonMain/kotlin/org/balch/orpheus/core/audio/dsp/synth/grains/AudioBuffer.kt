package org.balch.orpheus.core.audio.dsp.synth.grains

const val K_CROSS_FADE_SIZE = 256
const val K_INTERPOLATION_TAIL = 8

class AudioBuffer(sizeWithTail: Int) {
    // size_ in original is size - tail
    val size: Int = sizeWithTail - K_INTERPOLATION_TAIL
    
    private val buffer = FloatArray(sizeWithTail)
    private val tailBuffer = FloatArray(K_CROSS_FADE_SIZE)
    
    var head: Int = 0
        private set
    private var crossfadeCounter: Int = 0
    
    fun clear() {
        buffer.fill(0f)
        tailBuffer.fill(0f)
        head = 0
        crossfadeCounter = 0
    }

    fun resync(newHead: Int) {
        head = newHead
        crossfadeCounter = 0
    }
    
    // Basic write of a single sample
    private fun writeSample(sample: Float) {
        buffer[head] = sample
        
        // Replicate to tail for interpolation wrap-around
        if (head < K_INTERPOLATION_TAIL) {
            buffer[head + size] = sample
        }
        
        head++
        if (head >= size) {
            head = 0
        }
    }

    fun writeFade(input: FloatArray, startOffset: Int, count: Int, stride: Int, write: Boolean) {
        var inIdx = startOffset
        var samplesToDo = count
        
        if (!write) {
            // Recording stopped: capture samples into tailBuffer for crossfade
            if (crossfadeCounter < K_CROSS_FADE_SIZE) {
                while (samplesToDo > 0) {
                    if (crossfadeCounter < K_CROSS_FADE_SIZE) {
                        tailBuffer[crossfadeCounter++] = input[inIdx]
                        inIdx += stride
                    }
                    samplesToDo--
                }
            }
        } else if (crossfadeCounter == 0 && 
                   head >= K_INTERPOLATION_TAIL && 
                   head < (size - samplesToDo)) {
            // Fast path: writing, no crossfade, safely in middle of buffer
            while (samplesToDo > 0) {
                buffer[head] = input[inIdx]
                head++
                inIdx += stride
                samplesToDo--
            }
        } else {
            // Slow path: writing with crossfade or wrap-around
            while (samplesToDo > 0) {
                var sample = input[inIdx]
                if (crossfadeCounter > 0) {
                    crossfadeCounter--
                    val tailSample = tailBuffer[K_CROSS_FADE_SIZE - 1 - crossfadeCounter]
                    // Linear crossfade from tailBuffer to new input
                    // gain goes from close to 1.0 (start of fade) to 0.0
                    val gain = (crossfadeCounter + 1) / K_CROSS_FADE_SIZE.toFloat()
                    sample += (tailSample - sample) * gain
                }
                writeSample(sample)
                inIdx += stride
                samplesToDo--
            }
        }
    }

    // Read using Hermite interpolation
    // integral: integer index
    // fractional: 16-bit fractional part (0-65535) represented as Int
    fun readHermite(integralInput: Int, fractional: Int): Float {
        var i0 = integralInput % size
        if (i0 < 0) i0 += size
        
        val im1 = if (i0 == 0) size - 1 else i0 - 1
        val i1 = if (i0 == size - 1) 0 else i0 + 1
        val i2 = if (i1 == size - 1) 0 else i1 + 1
        
        val xm1 = buffer[im1]
        val x0 = buffer[i0]
        val x1 = buffer[i1]
        val x2 = buffer[i2]
        
        val t = fractional / 65536.0f
        
        val c = (x1 - xm1) * 0.5f
        val v = x0 - x1
        val w = c + v
        val a = w + v + (x2 - x0) * 0.5f
        val bNeg = w + a
        
        return ((((a * t) - bNeg) * t + c) * t + x0)
    }
}
