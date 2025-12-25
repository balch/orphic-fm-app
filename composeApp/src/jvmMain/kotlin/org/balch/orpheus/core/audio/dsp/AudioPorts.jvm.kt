package org.balch.orpheus.core.audio.dsp

import com.jsyn.ports.UnitInputPort
import com.jsyn.ports.UnitOutputPort

/**
 * JVM implementation of AudioInput wrapping JSyn UnitInputPort.
 */
actual interface AudioInput : AudioPort {
    actual fun set(value: Double)
    actual fun disconnectAll()
}

class JsynAudioInput(private val port: UnitInputPort) : AudioInput {
    override fun set(value: Double) {
        port.set(value)
    }

    override fun disconnectAll() {
        port.disconnectAll()
    }

    // Expose underlying port for JSyn-specific wiring
    val jsynPort: UnitInputPort get() = port
}

/**
 * JVM implementation of AudioOutput wrapping JSyn UnitOutputPort.
 */
actual interface AudioOutput : AudioPort {
    actual fun connect(input: AudioInput)
    actual fun connect(channel: Int, input: AudioInput, inputChannel: Int)
}

class JsynAudioOutput(private val port: UnitOutputPort) : AudioOutput {
    override fun connect(input: AudioInput) {
        if (input is JsynAudioInput) {
            port.connect(input.jsynPort)
        }
    }

    override fun connect(channel: Int, input: AudioInput, inputChannel: Int) {
        if (input is JsynAudioInput) {
            port.connect(channel, input.jsynPort, inputChannel)
        }
    }

    // Expose underlying port for JSyn-specific wiring
    val jsynPort: UnitOutputPort get() = port
}

/**
 * JVM implementation of AudioUnit.
 */
actual interface AudioUnit {
    actual val output: AudioOutput
}
