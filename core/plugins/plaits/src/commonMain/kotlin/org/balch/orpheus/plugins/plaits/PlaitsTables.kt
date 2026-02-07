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

    // --- Stiffness LUT for physical modeling resonator (65 entries) ---
    // From plaits/resources.cc lut_stiffness. Indexed by structure [0, 1] * 64.
    @Suppress("FloatingPointLiteralPrecision")
    val LUT_STIFFNESS: FloatArray = floatArrayOf(
        -6.250000000e-02f, -5.859375000e-02f, -5.468750000e-02f, -5.078125000e-02f,
        -4.687500000e-02f, -4.296875000e-02f, -3.906250000e-02f, -3.515625000e-02f,
        -3.125000000e-02f, -2.734375000e-02f, -2.343750000e-02f, -1.953125000e-02f,
        -1.562500000e-02f, -1.171875000e-02f, -7.812500000e-03f, -3.906250000e-03f,
         0.000000000e+00f,  0.000000000e+00f,  0.000000000e+00f,  0.000000000e+00f,
         1.009582073e-03f,  2.416076364e-03f,  4.002252878e-03f,  5.791066350e-03f,
         7.808404022e-03f,  1.008346028e-02f,  1.264915914e-02f,  1.554263074e-02f,
         1.880574864e-02f,  2.248573583e-02f,  2.663584813e-02f,  3.131614488e-02f,
         3.659435812e-02f,  4.254687278e-02f,  4.925983210e-02f,  5.683038428e-02f,
         6.536808837e-02f,  7.499649981e-02f,  8.585495846e-02f,  9.810060511e-02f,
         1.119106556e-01f,  1.274849653e-01f,  1.450489216e-01f,  1.648567056e-01f,
         1.871949702e-01f,  2.123869891e-01f,  2.407973346e-01f,  2.728371538e-01f,
         3.089701187e-01f,  3.497191360e-01f,  3.956739150e-01f,  4.474995013e-01f,
         5.059459012e-01f,  5.718589358e-01f,  6.461924814e-01f,  7.300222738e-01f,
         8.245614757e-01f,  9.311782340e-01f,  1.000037649e+00f,  1.005639154e+00f,
         1.048005353e+00f,  1.183990632e+00f,  1.457101344e+00f,  2.000000000e+00f,
         2.000000000e+00f,
    )

    // --- SVF shift LUT for string damping compensation (257 entries) ---
    // From plaits/resources.cc lut_svf_shift. Indexed by damping_cutoff * 1.0.
    @Suppress("FloatingPointLiteralPrecision")
    val LUT_SVF_SHIFT: FloatArray = floatArrayOf(
        7.500000000e-01f, 7.591880421e-01f, 7.683455389e-01f, 7.774424499e-01f,
        7.864497239e-01f, 7.953397451e-01f, 8.040867240e-01f, 8.126670211e-01f,
        8.210593968e-01f, 8.292451828e-01f, 8.372083767e-01f, 8.449356653e-01f,
        8.524163823e-01f, 8.596424124e-01f, 8.666080494e-01f, 8.733098228e-01f,
        8.797462999e-01f, 8.859178746e-01f, 8.918265520e-01f, 8.974757332e-01f,
        9.028700082e-01f, 9.080149595e-01f, 9.129169800e-01f, 9.175831064e-01f,
        9.220208696e-01f, 9.262381615e-01f, 9.302431183e-01f, 9.340440198e-01f,
        9.376492031e-01f, 9.410669892e-01f, 9.443056230e-01f, 9.473732226e-01f,
        9.502777394e-01f, 9.530269262e-01f, 9.556283121e-01f, 9.580891845e-01f,
        9.604165758e-01f, 9.626172547e-01f, 9.646977214e-01f, 9.666642057e-01f,
        9.685226683e-01f, 9.702788030e-01f, 9.719380415e-01f, 9.735055596e-01f,
        9.749862835e-01f, 9.763848977e-01f, 9.777058532e-01f, 9.789533760e-01f,
        9.801314757e-01f, 9.812439545e-01f, 9.822944161e-01f, 9.832862746e-01f,
        9.842227627e-01f, 9.851069409e-01f, 9.859417053e-01f, 9.867297957e-01f,
        9.874738036e-01f, 9.881761792e-01f, 9.888392394e-01f, 9.894651739e-01f,
        9.900560522e-01f, 9.906138300e-01f, 9.911403551e-01f, 9.916373732e-01f,
        9.921065333e-01f, 9.925493929e-01f, 9.929674233e-01f, 9.933620136e-01f,
        9.937344761e-01f, 9.940860496e-01f, 9.944179041e-01f, 9.947311441e-01f,
        9.950268127e-01f, 9.953058946e-01f, 9.955693195e-01f, 9.958179650e-01f,
        9.960526598e-01f, 9.962741861e-01f, 9.964832825e-01f, 9.966806459e-01f,
        9.968669346e-01f, 9.970427696e-01f, 9.972087374e-01f, 9.973653916e-01f,
        9.975132546e-01f, 9.976528197e-01f, 9.977845524e-01f, 9.979088923e-01f,
        9.980262540e-01f, 9.981370293e-01f, 9.982415876e-01f, 9.983402778e-01f,
        9.984334293e-01f, 9.985213529e-01f, 9.986043419e-01f, 9.986826732e-01f,
        9.987566083e-01f, 9.988263939e-01f, 9.988922628e-01f, 9.989544348e-01f,
        9.990131175e-01f, 9.990685067e-01f, 9.991207871e-01f, 9.991701333e-01f,
        9.992167099e-01f, 9.992606725e-01f, 9.993021676e-01f, 9.993413338e-01f,
        9.993783018e-01f, 9.994131950e-01f, 9.994461297e-01f, 9.994772160e-01f,
        9.995065576e-01f, 9.995342523e-01f, 9.995603927e-01f, 9.995850659e-01f,
        9.996083544e-01f, 9.996303357e-01f, 9.996510834e-01f, 9.996706665e-01f,
        9.996891506e-01f, 9.997065972e-01f, 9.997230647e-01f, 9.997386078e-01f,
        9.997532786e-01f, 9.997671260e-01f, 9.997801962e-01f, 9.997925329e-01f,
        9.998041771e-01f, 9.998151678e-01f, 9.998255416e-01f, 9.998353332e-01f,
        9.998445753e-01f, 9.998532986e-01f, 9.998615323e-01f, 9.998693039e-01f,
        9.998766393e-01f, 9.998835630e-01f, 9.998900981e-01f, 9.998962664e-01f,
        9.999020885e-01f, 9.999075839e-01f, 9.999127708e-01f, 9.999176666e-01f,
        9.999222876e-01f, 9.999266493e-01f, 9.999307661e-01f, 9.999346519e-01f,
        9.999383196e-01f, 9.999417815e-01f, 9.999450491e-01f, 9.999481332e-01f,
        9.999510443e-01f, 9.999537919e-01f, 9.999563854e-01f, 9.999588333e-01f,
        9.999611438e-01f, 9.999633246e-01f, 9.999653831e-01f, 9.999673260e-01f,
        9.999691598e-01f, 9.999708907e-01f, 9.999725245e-01f, 9.999740666e-01f,
        9.999755221e-01f, 9.999768960e-01f, 9.999781927e-01f, 9.999794167e-01f,
        9.999805719e-01f, 9.999816623e-01f, 9.999826915e-01f, 9.999836630e-01f,
        9.999845799e-01f, 9.999854454e-01f, 9.999862623e-01f, 9.999870333e-01f,
        9.999877611e-01f, 9.999884480e-01f, 9.999890964e-01f, 9.999897083e-01f,
        9.999902860e-01f, 9.999908312e-01f, 9.999913458e-01f, 9.999918315e-01f,
        9.999922900e-01f, 9.999927227e-01f, 9.999931311e-01f, 9.999935167e-01f,
        9.999938805e-01f, 9.999942240e-01f, 9.999945482e-01f, 9.999948542e-01f,
        9.999951430e-01f, 9.999954156e-01f, 9.999956729e-01f, 9.999959157e-01f,
        9.999961450e-01f, 9.999963613e-01f, 9.999965656e-01f, 9.999967583e-01f,
        9.999969403e-01f, 9.999971120e-01f, 9.999972741e-01f, 9.999974271e-01f,
        9.999975715e-01f, 9.999977078e-01f, 9.999978364e-01f, 9.999979579e-01f,
        9.999980725e-01f, 9.999981807e-01f, 9.999982828e-01f, 9.999983792e-01f,
        9.999984701e-01f, 9.999985560e-01f, 9.999986370e-01f, 9.999987135e-01f,
        9.999987857e-01f, 9.999988539e-01f, 9.999989182e-01f, 9.999989789e-01f,
        9.999990362e-01f, 9.999990903e-01f, 9.999991414e-01f, 9.999991896e-01f,
        9.999992351e-01f, 9.999992780e-01f, 9.999993185e-01f, 9.999993568e-01f,
        9.999993929e-01f, 9.999994269e-01f, 9.999994591e-01f, 9.999994895e-01f,
        9.999995181e-01f, 9.999995452e-01f, 9.999995707e-01f, 9.999995948e-01f,
        9.999996175e-01f, 9.999996390e-01f, 9.999996593e-01f, 9.999996784e-01f,
        9.999996964e-01f, 9.999997135e-01f, 9.999997296e-01f, 9.999997447e-01f,
        9.999997591e-01f, 9.999997726e-01f, 9.999997853e-01f, 9.999997974e-01f,
        9.999998088e-01f, 9.999998195e-01f, 9.999998296e-01f, 9.999998392e-01f,
        9.999998482e-01f, 9.999998567e-01f, 9.999998648e-01f, 9.999998724e-01f,
        9.999998795e-01f,
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
