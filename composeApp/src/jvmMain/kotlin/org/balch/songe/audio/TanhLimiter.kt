package org.balch.songe.audio

import com.jsyn.ports.UnitInputPort
import com.jsyn.ports.UnitOutputPort
import com.jsyn.unitgen.UnitGenerator

/**
 * A simple Soft Clipper / Limiter using a Tanh approximation.
 * Output = tanh(Input)
 * 
 * Ensures output stays within -1.0 to 1.0 range gracefully.
 */
class TanhLimiter : UnitGenerator() {
    val input: UnitInputPort = UnitInputPort("Input")
    val output: UnitOutputPort = UnitOutputPort("Output")

    init {
        addPort(input)
        addPort(output)
    }

    override fun generate(start: Int, limit: Int) {
        val inputList = input.values
        val outputList = output.values

        for (i in start until limit) {
            val inVal = inputList[i]
            // Simple Tanh approximation or Math.tanh
            // Math.tanh is cleaner but slightly slower. For safety limiter it's fine.
            outputList[i] = kotlin.math.tanh(inVal)
        }
    }
}
