package org.balch.orpheus.core.audio.dsp.synth.flux

/**
 * Sequence of random values with déjà-vu loop control.
 * 
 * This is the core of Marbles' pattern looping/mutation system:
 * - Maintains a loop buffer of up to 16 random decisions
 * - Can lock into a repeating loop (déjà-vu = 0.5)
 * - Can randomly permute loop values (déjà-vu > 0.5)
 * - Can generate completely new values (déjà-vu < 0.5)
 * 
 * Ported from Mutable Instruments Marbles.
 */
class RandomSequence {
    companion object {
        const val DEJA_VU_BUFFER_SIZE = 16
        const val HISTORY_BUFFER_SIZE = 16
        const val MAX_UINT32 = 4294967296.0f
    }
    
    private lateinit var randomStream: RandomStream
    private val loop = FloatArray(DEJA_VU_BUFFER_SIZE)
    private val history = FloatArray(HISTORY_BUFFER_SIZE)
    
    private var loopWriteHead = 0
    private var length = 8
    private var step = 0
    
    // Replay state for locking X channels together
    private var recordHead = 0
    private var replayHead = -1
    private var replayStart = 0
    private var replayHash: UInt = 0u
    private var replayShift: UInt = 0u
    
    private var dejaVu = 0.0f
    
    // Pointers for "redo" functionality (rewriting history when external CV is sampled)
    private var redoReadIndex = 0
    private var redoWriteIndex = -1
    private var redoWriteHistoryIndex = -1
    
    fun init(randomStream: RandomStream) {
        this.randomStream = randomStream
        for (i in 0 until DEJA_VU_BUFFER_SIZE) {
            loop[i] = randomStream.getFloat()
        }
        history.fill(0.0f)
        
        loopWriteHead = 0
        length = 8
        step = 0
        
        recordHead = 0
        replayHead = -1
        replayStart = 0
        dejaVu = 0.0f
        replayHash = 0u
        replayShift = 0u
        
        redoReadIndex = 0
        redoWriteIndex = -1
        redoWriteHistoryIndex = -1
    }
    
    fun clone(source: RandomSequence) {
        randomStream = source.randomStream
        source.loop.copyInto(loop)
        source.history.copyInto(history)
        
        loopWriteHead = source.loopWriteHead
        length = source.length
        step = source.step
        
        recordHead = source.recordHead
        replayHead = source.replayHead
        replayStart = source.replayStart
        replayHash = source.replayHash
        replayShift = source.replayShift
        
        dejaVu = source.dejaVu
        
        redoReadIndex = source.redoReadIndex
        redoWriteIndex = source.redoWriteIndex
        redoWriteHistoryIndex = source.redoWriteHistoryIndex
    }
    
    fun record() {
        replayStart = recordHead
        replayHead = -1
    }
    
    fun replayPseudoRandom(hash: UInt) {
        replayHead = replayStart
        replayHash = hash
        replayShift = 0u
    }
    
    fun replayShifted(shift: UInt) {
        replayHead = replayStart
        replayHash = 0u
        replayShift = shift
    }
    
    private fun getReplayValue(): Float {
        val h = ((replayHead - 1 - replayShift.toInt() + 2 * HISTORY_BUFFER_SIZE) % HISTORY_BUFFER_SIZE)
        return if (replayHash == 0u) {
            history[h]
        } else {
            var word = (history[h] * MAX_UINT32).toUInt()
            word = (word xor replayHash) * 1664525u + 1013904223u
            word.toFloat() / MAX_UINT32
        }
    }
    
    /**
     * Rewrite the most recent value (used when sampling external CV).
     */
    fun rewriteValue(value: Float): Float {
        if (replayHead >= 0) {
            return getReplayValue()
        }
        
        if (redoWriteIndex >= 0) {
            loop[redoWriteIndex] = 1.0f + value
        }
        var result = loop[redoReadIndex]
        if (result >= 1.0f) {
            result -= 1.0f
        } else {
            result = 0.5f
        }
        if (redoWriteHistoryIndex >= 0) {
            history[redoWriteHistoryIndex] = result
        }
        return result
    }
    
    /**
     * Generate the next value in the sequence.
     * 
     * @param deterministic If true, uses the provided value instead of random
     * @param value The deterministic value to use (if deterministic = true)
     */
    fun nextValue(deterministic: Boolean, value: Float): Float {
        if (replayHead >= 0) {
            replayHead = (replayHead + 1) % HISTORY_BUFFER_SIZE
            return getReplayValue()
        }
        
        val pSqrt = 2.0f * dejaVu - 1.0f
        val p = pSqrt * pSqrt
        val mutate = randomStream.getFloat() < p
        
        if (mutate && dejaVu <= 0.5f) {
            // Generate a new value and put it at the end of the loop
            redoWriteIndex = loopWriteHead
            loop[loopWriteHead] = if (deterministic) {
                1.0f + value
            } else {
                randomStream.getFloat()
            }
            loopWriteHead = (loopWriteHead + 1) % DEJA_VU_BUFFER_SIZE
            step = length - 1
        } else {
            // Do not generate a new value, just replay the loop or jump randomly through it
            redoWriteIndex = -1
            if (mutate /* implied: dejaVu > 0.5f */) {
                step = (randomStream.getFloat() * length.toFloat()).toInt()
            } else {
                step = step + 1
                if (step >= length) {
                    step = 0
                }
            }
        }
        
        val i = (loopWriteHead + DEJA_VU_BUFFER_SIZE - length + step) % DEJA_VU_BUFFER_SIZE
        redoReadIndex = i
        var result = loop[i]
        if (result >= 1.0f) {
            result -= 1.0f
        } else if (deterministic) {
            // We ask for a deterministic value (shift register), but the loop contains random values
            result = 0.5f
        }
        
        redoWriteHistoryIndex = recordHead
        history[recordHead] = result
        recordHead = (recordHead + 1) % HISTORY_BUFFER_SIZE
        return result
    }
    
    /**
     * Generate a vector of correlated random values from a single seed.
     */
    fun nextVector(destination: FloatArray, size: Int) {
        val seed = nextValue(false, 0.0f)
        var word = (seed * MAX_UINT32).toUInt()
        for (i in 0 until size) {
            destination[i] = word.toFloat() / MAX_UINT32
            word = word * 1664525u + 1013904223u
        }
    }
    
    fun setDejaVu(dejaVu: Float) {
        this.dejaVu = dejaVu
    }
    
    fun setLength(length: Int) {
        if (length < 1 || length > DEJA_VU_BUFFER_SIZE) {
            return
        }
        this.length = length
        step = step % length
    }
    
    fun getDejaVu(): Float = dejaVu
    fun getLength(): Int = length
    
    fun reset() {
        step = length - 1
    }
}
