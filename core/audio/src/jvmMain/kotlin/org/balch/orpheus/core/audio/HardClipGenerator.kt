package org.balch.orpheus.core.audio

import com.jsyn.ports.UnitInputPort
import com.jsyn.ports.UnitOutputPort
import com.jsyn.unitgen.UnitGenerator

/**
 * Hard clipper that clamps output to ±1.0.
 * 100% transparent for in-range signals.
 */
class HardClipGenerator : UnitGenerator() {
    val input: UnitInputPort = UnitInputPort("Input")
    val output: UnitOutputPort = UnitOutputPort("Output")

    init {
        addPort(input)
        addPort(output)
    }

    override fun generate(start: Int, limit: Int) {
        val inp = input.values
        val out = output.values
        for (i in start until limit) {
            out[i] = inp[i].coerceIn(-1.0, 1.0)
        }
    }
}
