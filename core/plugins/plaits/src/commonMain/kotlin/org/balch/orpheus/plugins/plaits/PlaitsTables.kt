package org.balch.orpheus.plugins.plaits

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lookup tables for Plaits engine ports.
 * Generated programmatically from formulas in plaits/resources/lookup_tables.py.
 */
object PlaitsTables {

    // --- Sine LUT: sin(2*pi*t) for t in [0, 1.25], 641 entries ---
    // Size 512 + 512/4 + 1 = 641 entries, covering [0, 1.25] periods
    val SINE: FloatArray = FloatArray(641) { i ->
        sin(2.0 * PI * i / 512.0).toFloat()
    }

    // --- FM Frequency Quantizer: 130 entries ---
    val FM_FREQUENCY_QUANTIZER: FloatArray = generateFmQuantizer()

    // --- Wavefolder table 1: windowed sine + arctan blend, 516 entries ---
    val FOLD: FloatArray = generateFold()

    // --- Wavefolder table 2: analog circuit model, 516 entries ---
    val FOLD_2: FloatArray = generateFold2()

    // --- 5+1 waveshaper curves, each 257 entries (pre-divided by 32768) ---
    val WS_INVERSE_TAN: FloatArray = generateWsInverseTan()
    val WS_INVERSE_SIN: FloatArray = generateWsInverseSin()
    val WS_LINEAR: FloatArray = generateWsLinear()
    val WS_BUMP: FloatArray = generateWsBump()
    val WS_DOUBLE_BUMP: FloatArray = generateWsDoubleBump()

    /** Indexed by shape_integral (0..5), 6th is sentinel copy. */
    val WAVESHAPER_TABLES: Array<FloatArray> = arrayOf(
        WS_INVERSE_TAN, WS_INVERSE_SIN, WS_LINEAR, WS_BUMP, WS_DOUBLE_BUMP, WS_DOUBLE_BUMP
    )

    // --- 4x Downsampler FIR coefficients ---
    val DOWNSAMPLER_FIR: FloatArray = floatArrayOf(
        0.02442415f, 0.09297315f, 0.16712938f, 0.21547332f
    )

    // =====================================================================
    // Table generation functions
    // =====================================================================

    private fun generateFmQuantizer(): FloatArray {
        val ratios = doubleArrayOf(
            0.5, 0.5 * 2.0.pow(16.0 / 1200.0),
            sqrt(2.0) / 2.0, PI / 4.0,
            1.0, 1.0 * 2.0.pow(16.0 / 1200.0),
            sqrt(2.0), PI / 2.0,
            7.0 / 4.0, 2.0, 2.0 * 2.0.pow(16.0 / 1200.0),
            9.0 / 4.0, 11.0 / 4.0,
            2.0 * sqrt(2.0), 3.0, PI,
            sqrt(3.0) * 2.0, 4.0,
            sqrt(2.0) * 3.0, PI * 3.0 / 2.0,
            5.0, sqrt(2.0) * 4.0, 8.0
        )

        // Convert ratios to semitone offsets and triple each
        val scale = mutableListOf<Double>()
        for (ratio in ratios) {
            val semitones = 12.0 * log2(ratio)
            scale.add(semitones)
            scale.add(semitones)
            scale.add(semitones)
        }

        // Expand to next power of 2 by inserting midpoints at largest gaps
        val targetSize = 128 // 2^7, next power of 2 >= 69
        while (scale.size < targetSize) {
            var maxGap = -1.0
            var gapIdx = 0
            for (i in 0 until scale.size - 1) {
                val gap = scale[i + 1] - scale[i]
                if (gap > maxGap) {
                    maxGap = gap
                    gapIdx = i
                }
            }
            scale.add(gapIdx + 1, (scale[gapIdx] + scale[gapIdx + 1]) / 2.0)
        }

        // Add 2 sentinel entries
        scale.add(scale.last())
        scale.add(scale.last())

        return FloatArray(scale.size) { scale[it].toFloat() }
    }

    private fun generateFold(): FloatArray {
        // 512 + 4 entries, x in [-1, 1+epsilon]
        val size = 516
        return FloatArray(size) { i ->
            val x = (i.toDouble() / 256.0) - 1.0
            val sine = sin(8.0 * PI * x)
            val window = exp(-x * x * 4.0).pow(2.0)
            val bipolarFold = sine * window + atan(3.0 * x) * (1.0 - window)
            // Normalize by max (approximate; the max of the original is ~1.258)
            (bipolarFold / 1.258).toFloat()
        }
    }

    private fun generateFold2(): FloatArray {
        val size = 516
        return FloatArray(size) { i ->
            val x = (i.toDouble() / 256.0) - 1.0
            val vIn = x * 12.0

            val stage1 = deadband(vIn, 10e3, 100e3)
            val stage2 = deadband(vIn, 49.9e3, 44.2e3)
            val stage3 = deadband(vIn, 91e3, 18e3)
            val stage4 = deadband(vIn, 30e3, 71.4e3)
            val stage5 = deadband(vIn, 68e3, 33.0e3)

            val stage45 = -33.0 / 71.4 * stage4 - 33.0 / 33.0 * stage5 - 33.0 / 240.0 * vIn
            val vOut = diode(-150.0 / 100.0 * stage1 - 150.0 / 44.2 * stage2 -
                150.0 / 18.0 * stage3 - 150.0 / 33.0 * stage45)
            // Normalize by max (approximate)
            (vOut / 0.7).toFloat()
        }
    }

    private fun deadband(vIn: Double, rIn: Double, rLoad: Double,
                         rFb: Double = 150.0e3, vSat: Double = 10.47): Double {
        var vOut = -rFb / rIn * vIn
        vOut = vOut.coerceIn(-vSat, vSat)
        return (vIn * rFb * rLoad + vOut * rIn * rLoad) / (rFb * rLoad + rIn * rLoad + rIn * rFb)
    }

    private fun diode(x: Double): Double = 0.7 * x / (0.3 + abs(x))

    // Waveshaper tables: 257 entries each (128 negative mirrored + 129 positive)
    // The C++ stores int16, we pre-convert to float [-1, 1]

    private fun flip(halfTable: DoubleArray): FloatArray {
        // halfTable has 129 entries (0..128), representing x in [0, 1]
        // Output: 257 entries = mirrored negative (128 entries) + positive (129 entries)
        val result = FloatArray(257)
        for (i in 0 until 128) {
            result[i] = (-halfTable[128 - i]).toFloat()
        }
        for (i in 0..128) {
            result[128 + i] = halfTable[i].toFloat()
        }
        return result
    }

    private fun generateWsInverseTan(): FloatArray {
        val n = 129
        val half = DoubleArray(n) { i ->
            val x = i.toDouble() / 128.0
            val tanScale = atan(8.0 * cos(PI * 0.0)) // tan at x=0 => atan(8) ≈ 1.446
            // inverse_tan = arccos(tan(scale * (1 - 2x)) / 8) / pi
            val s = atan(8.0 * cos(PI * 0.0)) // scale = atan(8*1)
            kotlin.math.acos((kotlin.math.tan(s * (1.0 - 2.0 * x)) / 8.0).coerceIn(-1.0, 1.0)) / PI
        }
        return flip(half)
    }

    private fun generateWsInverseSin(): FloatArray {
        val n = 129
        val half = DoubleArray(n) { i ->
            val x = i.toDouble() / 128.0
            // inverse_sin = arccos(1 - 2x) / pi
            kotlin.math.acos((1.0 - 2.0 * x).coerceIn(-1.0, 1.0)) / PI
        }
        return flip(half)
    }

    private fun generateWsLinear(): FloatArray {
        val n = 129
        val half = DoubleArray(n) { i -> i.toDouble() / 128.0 }
        return flip(half)
    }

    private fun generateWsBump(): FloatArray {
        val n = 129
        val half = DoubleArray(n) { i ->
            val x = i.toDouble() / 128.0
            val fadeCrop = minOf(1.0, 4.0 - 4.0 * x)
            (1.0 - cos(PI * x * 1.5)) * (1.0 - cos(PI * fadeCrop)) / 4.5
        }
        return flip(half)
    }

    private fun generateWsDoubleBump(): FloatArray {
        val n = 129
        val half = DoubleArray(n) { i ->
            val x = i.toDouble() / 128.0
            sin(PI * x * 1.5)
        }
        return flip(half)
    }

    // Compute accurate normalization for fold tables at init
    init {
        // Normalize FOLD table
        var maxFold = 0f
        for (v in FOLD) { if (abs(v) > maxFold) maxFold = abs(v) }
        if (maxFold > 0f) for (i in FOLD.indices) FOLD[i] /= maxFold

        // Normalize FOLD_2 table
        var maxFold2 = 0f
        for (v in FOLD_2) { if (abs(v) > maxFold2) maxFold2 = abs(v) }
        if (maxFold2 > 0f) for (i in FOLD_2.indices) FOLD_2[i] /= maxFold2
    }
}
