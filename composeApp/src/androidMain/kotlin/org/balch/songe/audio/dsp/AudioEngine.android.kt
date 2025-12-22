package org.balch.songe.audio.dsp

import org.balch.songe.core.audio.dsp.Add
import org.balch.songe.core.audio.dsp.AudioInput
import org.balch.songe.core.audio.dsp.AudioUnit
import org.balch.songe.core.audio.dsp.DelayLine
import org.balch.songe.core.audio.dsp.Envelope
import org.balch.songe.core.audio.dsp.Limiter
import org.balch.songe.core.audio.dsp.Maximum
import org.balch.songe.core.audio.dsp.Minimum
import org.balch.songe.core.audio.dsp.Multiply
import org.balch.songe.core.audio.dsp.MultiplyAdd
import org.balch.songe.core.audio.dsp.PassThrough
import org.balch.songe.core.audio.dsp.PeakFollower
import org.balch.songe.core.audio.dsp.SineOscillator
import org.balch.songe.core.audio.dsp.SquareOscillator
import org.balch.songe.core.audio.dsp.TriangleOscillator

/**
 * Android stub implementation of AudioEngine.
 * TODO: Replace with Oboe-based implementation.
 */
actual class AudioEngine actual constructor() {
    actual fun start() {}
    actual fun stop() {}
    
    actual val isRunning: Boolean = false
    actual val sampleRate: Int = 44100
    
    actual fun addUnit(unit: AudioUnit) {}
    
    actual fun createSineOscillator(): SineOscillator = StubSineOscillator()
    actual fun createTriangleOscillator(): TriangleOscillator = StubTriangleOscillator()
    actual fun createSquareOscillator(): SquareOscillator = StubSquareOscillator()
    actual fun createEnvelope(): Envelope = StubEnvelope()
    actual fun createDelayLine(): DelayLine = StubDelayLine()
    actual fun createPeakFollower(): PeakFollower = StubPeakFollower()
    actual fun createLimiter(): Limiter = StubLimiter()
    actual fun createMultiply(): Multiply = StubMultiply()
    actual fun createAdd(): Add = StubAdd()
    actual fun createMultiplyAdd(): MultiplyAdd = StubMultiplyAdd()
    actual fun createPassThrough(): PassThrough = StubPassThrough()
    actual fun createMinimum(): Minimum = StubMinimum()
    actual fun createMaximum(): Maximum = StubMaximum()
    
    actual val lineOutLeft: AudioInput = StubAudioInput()
    actual val lineOutRight: AudioInput = StubAudioInput()
    
    actual fun getCpuLoad(): Float = 0f
}
