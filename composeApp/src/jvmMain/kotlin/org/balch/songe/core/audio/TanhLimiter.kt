package org.balch.songe.core.audio

import com.jsyn.ports.UnitInputPort
import com.jsyn.ports.UnitOutputPort
import com.jsyn.unitgen.UnitGenerator

/**
 * A Soft Clipper / Distortion using Tanh waveshaping.
 *
 * Output = tanh(Input * Drive)
 *
 * - Drive = 1.0: Clean pass-through (gentle limiting)
 * - Drive = 3-5: Warm saturation
 * - Drive = 10+: Heavy distortion with harmonic content
 *
 * Post-gain compensates for volume loss at high drive settings.
 */
class TanhLimiter : UnitGenerator() {
    val input: UnitInputPort = UnitInputPort("Input")
    val output: UnitOutputPort = UnitOutputPort("Output")
    val drive: UnitInputPort = UnitInputPort("Drive", 1.0) // Default: no extra drive

    init {
        addPort(input)
        addPort(output)
        addPort(drive)
    }

    override fun generate(start: Int, limit: Int) {
        val inputList = input.values
        val outputList = output.values
        val driveVal = drive.values

        for (i in start until limit) {
            val drv = driveVal[i].coerceIn(1.0, 50.0)
            val inVal = inputList[i] * drv
            // Tanh saturation
            val saturated = kotlin.math.tanh(inVal)
            // Compensate volume loss (tanh squashes peaks)
            val compensation = 1.0 / kotlin.math.tanh(drv.coerceAtMost(3.0))
            outputList[i] = saturated * compensation.coerceAtMost(1.5)
        }
    }
}
