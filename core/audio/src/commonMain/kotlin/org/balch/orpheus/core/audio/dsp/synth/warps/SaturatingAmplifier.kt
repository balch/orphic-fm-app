package org.balch.orpheus.core.audio.dsp.synth.warps

import org.balch.orpheus.core.audio.dsp.synth.grains.ParameterInterpolator

/**
 * Port of Mutable Instruments Warps SaturatingAmplifier.
 */
class SaturatingAmplifier {
    private var level: Float = 0f
    private var driveInternal: Float = 0f
    private var postGain: Float = 0f
    private var preGain: Float = 0f

    fun init() {
        driveInternal = 0f
        level = 0f
        postGain = 0f
        preGain = 0f
    }

    /**
     * SoftClip function matching stmlib::SoftClip
     */
    private fun softClip(x: Float): Float {
        if (x <= -1.0f) return -1.0f
        if (x >= 1.0f) return 1.0f
        return x * (1.5f - 0.5f * x * x)
    }

    fun process(
        drive: Float,
        limit: Float,
        input: FloatArray,
        output: FloatArray,
        outputRaw: FloatArray,
        size: Int
    ) {
        // Process noise gate and compute raw output
        val driveModulation = ParameterInterpolator()
        driveModulation.init(driveInternal, drive, size)
        var currentLevel = level
        
        for (i in 0 until size) {
            val s = input[i]
            val error = s * s - currentLevel
            currentLevel += error * (if (error > 0.0f) 0.1f else 0.0001f)
            val gatedS = s * (if (currentLevel <= 0.0001f) (1.0f / 0.0001f) * currentLevel else 1.0f)
            
            output[i] = gatedS
            outputRaw[i] += gatedS * driveModulation.next()
        }
        level = currentLevel
        driveInternal = drive

        // Process overdrive / gain
        val drive2 = drive * drive
        val preGainA = drive * 0.5f
        val preGainB = drive2 * drive2 * drive * 24.0f
        val calculatedPreGain = preGainA + (preGainB - preGainA) * drive2
        val driveSquished = drive * (2.0f - drive)
        
        val postGainInput = 0.33f + driveSquished * (calculatedPreGain - 0.33f)
        val calculatedPostGain = 1.0f / softClip(postGainInput)
        
        val preGainModulation = ParameterInterpolator()
        preGainModulation.init(preGain, calculatedPreGain, size)
        val postGainModulation = ParameterInterpolator()
        postGainModulation.init(postGain, calculatedPostGain, size)

        for (i in 0 until size) {
            val pre = preGainModulation.next() * output[i]
            val post = softClip(pre) * postGainModulation.next()
            output[i] = pre + (post - pre) * limit
        }
        
        preGain = calculatedPreGain
        postGain = calculatedPostGain
    }
}
