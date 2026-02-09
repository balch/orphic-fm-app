package org.balch.orpheus.core.audio

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

    // Cached compensation to avoid redundant tanh() per sample
    private var cachedDrive = 1.0
    private var cachedCompensation = 1.0

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
            // Only recompute compensation when drive changes
            if (drv != cachedDrive) {
                cachedDrive = drv
                cachedCompensation = (1.0 / kotlin.math.tanh(drv.coerceAtMost(3.0))).coerceAtMost(1.5)
            }
            val inVal = inputList[i] * drv
            // Tanh saturation
            outputList[i] = kotlin.math.tanh(inVal) * cachedCompensation
        }
    }
}
