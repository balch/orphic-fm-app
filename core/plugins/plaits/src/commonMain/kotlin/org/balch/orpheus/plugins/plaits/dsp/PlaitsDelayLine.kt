// Copyright 2014 Emilie Gillet.
// Ported to Kotlin by Orpheus project. Original: MIT License.
// Port of plaits/dsp/physical_modelling/delay_line.h

package org.balch.orpheus.plugins.plaits.dsp

/**
 * Circular delay line with linear and Hermite interpolation.
 * Decrementing write pointer, same as Plaits C++ implementation.
 *
 * @param maxDelay maximum delay length in samples
 */
class PlaitsDelayLine(private val maxDelay: Int) {
    private val line = FloatArray(maxDelay)
    private var writePtr = 0

    fun reset() {
        line.fill(0f)
        writePtr = 0
    }

    fun write(sample: Float) {
        line[writePtr] = sample
        writePtr = (writePtr - 1 + maxDelay) % maxDelay
    }

    /** Read with linear interpolation at fractional delay. */
    fun read(delay: Float): Float {
        val integral = delay.toInt()
        val fractional = delay - integral
        val a = line[(writePtr + integral) % maxDelay]
        val b = line[(writePtr + integral + 1) % maxDelay]
        return a + (b - a) * fractional
    }

    /** Read with Hermite interpolation at fractional delay. */
    fun readHermite(delay: Float): Float {
        val integral = delay.toInt()
        val fractional = delay - integral
        val t = writePtr + integral + maxDelay
        val xm1 = line[(t - 1) % maxDelay]
        val x0 = line[t % maxDelay]
        val x1 = line[(t + 1) % maxDelay]
        val x2 = line[(t + 2) % maxDelay]
        val c = (x1 - xm1) * 0.5f
        val v = x0 - x1
        val w = c + v
        val a = w + v + (x2 - x0) * 0.5f
        val bNeg = w + a
        val f = fractional
        return ((a * f - bNeg) * f + c) * f + x0
    }

    /** Allpass filter using the delay line. */
    fun allpass(sample: Float, delay: Float, coefficient: Float): Float {
        val read = this.read(delay)
        val writeVal = sample + coefficient * read
        write(writeVal)
        return -writeVal * coefficient + read
    }

    /** Allpass with integer delay. */
    fun allpass(sample: Float, delay: Int, coefficient: Float): Float {
        val read = line[(writePtr + delay) % maxDelay]
        val writeVal = sample + coefficient * read
        write(writeVal)
        return -writeVal * coefficient + read
    }
}
