package org.balch.orpheus.core.audio.dsp

import com.jsyn.JSyn
import com.jsyn.unitgen.LineOut
import com.jsyn.unitgen.UnitGenerator
import org.balch.orpheus.core.audio.AndroidAudioDeviceManager

/**
 * Android actual implementation of AudioEngine using JSyn.
 */
actual class AudioEngine actual constructor() {
    private val synth = JSyn.createSynthesizer(AndroidAudioDeviceManager())
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

    actual fun start() {
        synth.add(lineOut)
        synth.start()
        lineOut.start()
    }

    actual fun stop() {
        lineOut.stop()
        synth.stop()
    }

    actual val isRunning: Boolean
        get() = synth.isRunning

    actual val sampleRate: Int
        get() = synth.frameRate

    actual fun addUnit(unit: AudioUnit) {
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
            is JsynMinimumWrapper -> synth.add(unit.jsUnit)
            is JsynMaximumWrapper -> synth.add(unit.jsUnit)
            is JsynLinearRampWrapper -> synth.add(unit.jsRamp)
            is JsynAutomationPlayer -> synth.add(unit.reader)
            is JsynDrumUnit -> synth.add(unit)
            is JsynResonatorUnit -> synth.add(unit)
            is JsynGrainsUnit -> synth.add(unit)
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

    // Helper to add raw JSyn units (for migration)
    fun addJsynUnit(unit: UnitGenerator) {
        synth.add(unit)
    }

    actual fun createSineOscillator(): SineOscillator = JsynSineOscillatorWrapper()
    actual fun createTriangleOscillator(): TriangleOscillator = JsynTriangleOscillatorWrapper()
    actual fun createSquareOscillator(): SquareOscillator = JsynSquareOscillatorWrapper()
    actual fun createEnvelope(): Envelope = JsynEnvelope()
    actual fun createDelayLine(): DelayLine = JsynDelayLine()
    actual fun createPeakFollower(): PeakFollower = JsynPeakFollowerWrapper()
    actual fun createLimiter(): Limiter = JsynLimiter()
    actual fun createMultiply(): Multiply = JsynMultiplyWrapper()
    actual fun createAdd(): Add = JsynAddWrapper()
    actual fun createMultiplyAdd(): MultiplyAdd = JsynMultiplyAddWrapper()
    actual fun createPassThrough(): PassThrough = JsynPassThroughWrapper()
    actual fun createMinimum(): Minimum = JsynMinimumWrapper()
    actual fun createMaximum(): Maximum = JsynMaximumWrapper()
    actual fun createLinearRamp(): LinearRamp = JsynLinearRampWrapper()
    actual fun createAutomationPlayer(): AutomationPlayer = JsynAutomationPlayer()
    actual fun createDrumUnit(): DrumUnit = JsynDrumUnit()
    actual fun createResonatorUnit(): ResonatorUnit = JsynResonatorUnit()
    actual fun createGrainsUnit(): GrainsUnit = JsynGrainsUnit()
    actual fun createLooperUnit(): LooperUnit = JsynLooperUnit()

    actual val lineOutLeft: AudioInput
        get() = JsynAudioInput(lineOutLeftProxy.input)

    actual val lineOutRight: AudioInput
        get() = JsynAudioInput(lineOutRightProxy.input)

    actual fun getCpuLoad(): Float = (synth.usage * 100f).toFloat()

    actual fun getCurrentTime(): Double = synth.currentTime
}
