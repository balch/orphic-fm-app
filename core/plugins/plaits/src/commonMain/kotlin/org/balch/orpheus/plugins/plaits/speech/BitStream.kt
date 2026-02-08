// Copyright 2016 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/speech/lpc_speech_synth_controller.h (BitStream class)

package org.balch.orpheus.plugins.plaits.speech

/**
 * Bit-level reader for LPC-10 encoded word bank data.
 * Reads bits LSB-first with bit reversal per byte.
 */
class BitStream {
    private lateinit var data: IntArray
    private var pos: Int = 0
    private var available: Int = 0
    private var bits: Int = 0

    fun init(data: IntArray, offset: Int = 0) {
        this.data = data
        this.pos = offset
        available = 0
        bits = 0
    }

    fun flush() {
        while (available > 0) {
            getBits(1)
        }
    }

    fun getBits(numBits: Int): Int {
        var shift = numBits
        if (numBits > available) {
            bits = bits shl available
            shift -= available
            bits = bits or reverse(data[pos++])
            available += 8
        }
        bits = bits shl shift
        val result = (bits shr 8) and 0xFF
        bits = bits and 0xFF
        available -= numBits
        return result
    }

    /** Current read position in the data array. */
    val ptr: Int get() = pos

    private fun reverse(b: Int): Int {
        var v = b and 0xFF
        v = ((v shr 4) or (v shl 4)) and 0xFF
        v = (((v and 0xCC) shr 2) or ((v and 0x33) shl 2)) and 0xFF
        v = (((v and 0xAA) shr 1) or ((v and 0x55) shl 1)) and 0xFF
        return v
    }
}
