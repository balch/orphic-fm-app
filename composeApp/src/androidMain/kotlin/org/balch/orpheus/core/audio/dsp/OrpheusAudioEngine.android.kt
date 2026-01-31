package org.balch.orpheus.core.audio.dsp

import com.jsyn.JSyn
import com.jsyn.unitgen.LineOut
import com.jsyn.unitgen.UnitGenerator

actual class OrpheusAudioEngine actual constructor() : AudioEngine {
    private val synth = JSyn.createSynthesizer()
    private val lineOut = LineOut()

    override val lineOutLeft: AudioInput = JsynAudioInput(lineOut.input.parts[0])
    override val lineOutRight: AudioInput = JsynAudioInput(lineOut.input.parts[1])

    init {
        synth.add(lineOut)
    }

    override fun start() {
        synth.start()
        lineOut.start()
    }

    override fun stop() {
        lineOut.stop()
        synth.stop()
    }

    override val isRunning: Boolean
        get() = synth.isRunning

    override val sampleRate: Int
        get() = 44100

    override fun addUnit(unit: AudioUnit) {
        // Handle direct UnitGenerators (DrumUnit, ResonatorUnit, etc.)
        if (unit is UnitGenerator) {
            synth.add(unit)
            return
        }

        // Handle wrappers
        when (unit) {
             is JsynEnvelope -> synth.add(unit.jsEnv)
             is JsynDelayLine -> synth.add(unit.jsDelay)
             is JsynPeakFollowerWrapper -> synth.add(unit.jsPeak)
             is JsynLimiter -> synth.add(unit.jsLimiter)
             is JsynMultiplyWrapper -> synth.add(unit.jsUnit)
             is JsynAddWrapper -> synth.add(unit.jsUnit)
             is JsynMultiplyAddWrapper -> synth.add(unit.jsUnit)
             is JsynPassThroughWrapper -> synth.add(unit.jsUnit)
             is JsynSineOscillatorWrapper -> synth.add(unit.jsOsc)
             is JsynTriangleOscillatorWrapper -> synth.add(unit.jsOsc)
             is JsynSquareOscillatorWrapper -> synth.add(unit.jsOsc)
             is JsynSawtoothOscillatorWrapper -> synth.add(unit.jsOsc)
             is JsynMinimumWrapper -> synth.add(unit.jsUnit)
             is JsynMaximumWrapper -> synth.add(unit.jsUnit)
             is JsynLinearRampWrapper -> synth.add(unit.jsRamp)
             is JsynAutomationPlayer -> synth.add(unit.reader)
             is JsynClockUnit -> {
                  synth.add(unit.jsOsc)
                  synth.add(unit.scaler)
             }
             is JsynLooperUnit -> {
                  synth.add(unit.writerLeft)
                  synth.add(unit.writerRight)
                  synth.add(unit.readerLeft)
                  synth.add(unit.readerRight)
                  synth.add(unit.recordGateInput)
                  synth.add(unit.playGateInput)
             }
        }
    }
    
    override fun getCpuLoad(): Float = synth.usage.toFloat()
    
    override fun getCurrentTime(): Double = synth.currentTime
}
