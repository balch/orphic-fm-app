package org.balch.orpheus.core.audio.dsp

import com.jsyn.JSyn
import com.jsyn.unitgen.LineOut
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * JVM actual implementation of AudioEngine using JSyn.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OrpheusAudioEngine @Inject constructor() : AudioEngine {
    private val synth = JSyn.createSynthesizer()
    private val lineOut = LineOut()

    // Use JSyn PassThrough units as connection points for stereo output
    private val lineOutLeftProxy = com.jsyn.unitgen.PassThrough()
    private val lineOutRightProxy = com.jsyn.unitgen.PassThrough()

    init {
        synth.add(lineOutLeftProxy)
        synth.add(lineOutRightProxy)

        // Connect proxies to LineOut
        lineOutLeftProxy.output.connect(0, lineOut.input, 0)
        lineOutRightProxy.output.connect(0, lineOut.input, 1)
    }

    override fun start() {
        synth.add(lineOut)
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
        get() = synth.frameRate.toInt()

    override fun addUnit(unit: AudioUnit) {
        // Extract the underlying JSyn unit and add to synth
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

            is JsynLooperUnit -> {
                 synth.add(unit.writerLeft)
                 synth.add(unit.writerRight)
                 synth.add(unit.readerLeft)
                 synth.add(unit.readerRight)
                 synth.add(unit.recordGateInput)
                 synth.add(unit.playGateInput)
            }
            is JsynClockUnit -> {
                 synth.add(unit.jsOsc)
                 synth.add(unit.scaler)
            }

            is com.jsyn.unitgen.UnitGenerator -> synth.add(unit)
        }
    }



    override val lineOutLeft: AudioInput
        get() = JsynAudioInput(lineOutLeftProxy.input)

    override val lineOutRight: AudioInput
        get() = JsynAudioInput(lineOutRightProxy.input)

    override fun getCpuLoad(): Float = (synth.usage * 100f).toFloat()

    override fun getCurrentTime(): Double = synth.currentTime
}
