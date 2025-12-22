package org.balch.songe.audio.dsp

import org.balch.songe.core.audio.dsp.AudioInput
import org.balch.songe.core.audio.dsp.AudioOutput
import org.balch.songe.core.audio.dsp.AudioPort

/**
 * Android stub implementation of AudioInput.
 */
actual interface AudioInput : AudioPort {
    actual fun set(value: Double)
    actual fun disconnectAll()
}

class StubAudioInput : AudioInput {
    override fun set(value: Double) {}
    override fun disconnectAll() {}
}

/**
 * Android stub implementation of AudioOutput.
 */
actual interface AudioOutput : AudioPort {
    actual fun connect(input: AudioInput)
    actual fun connect(channel: Int, input: AudioInput, inputChannel: Int)
}

class StubAudioOutput : AudioOutput {
    override fun connect(input: AudioInput) {}
    override fun connect(channel: Int, input: AudioInput, inputChannel: Int) {}
}

/**
 * Android stub implementation of AudioUnit.
 */
actual interface AudioUnit {
    actual val output: AudioOutput
}
