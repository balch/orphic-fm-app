package org.balch.orpheus.features.visualizations.viz

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** Linear magnitude -> normalized 0..1 bar height via a dB window. */
fun magnitudeToHeight(mag: Float, floorDb: Float = -70f, ceilDb: Float = 0f): Float {
    val db = 20f * log10(mag + 1e-9f)
    return ((db - floorDb) / (ceilDb - floorDb)).coerceIn(0f, 1f)
}

/** Spectral tilt: lift highs to counter music's natural HF rolloff. 0 at band 0. */
fun applyTilt(mag: Float, bandIndex: Int, bandCount: Int, tiltKnob: Float): Float {
    if (bandCount <= 1) return mag
    val tiltDb = tiltKnob * 9f * (bandIndex.toFloat() / (bandCount - 1))
    return mag * 10f.pow(tiltDb / 20f)
}

/**
 * Fast-attack / slow-decay bar ballistics with peak-hold caps. Mutates in place;
 * owned by the Compose frame loop (single-threaded main). decayKnob 0..1 = fall speed.
 */
class SpectrumBallistics(val bandCount: Int) {
    val heights = FloatArray(bandCount)
    val peaks = FloatArray(bandCount)

    fun update(targets: FloatArray, decayKnob: Float) {
        val fall = 0.02f + decayKnob * 0.10f   // 0.02..0.12 per frame
        val peakFall = fall * 0.25f
        val n = minOf(bandCount, targets.size)
        for (i in 0 until n) {
            val t = targets[i]
            heights[i] = if (t >= heights[i]) t else max(t, heights[i] - fall)
            peaks[i] = if (t >= peaks[i]) t else max(heights[i], peaks[i] - peakFall)
        }
    }
}
