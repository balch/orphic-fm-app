package org.balch.orpheus.core.audio.dsp
import org.khronos.webgl.get

/**
 * WASM implementation of AudioInput.
 * Wraps either an AudioParam (for control values) or a connection point on an AudioNode.
 */
actual interface AudioInput : AudioPort {
    actual fun set(value: Double)
    actual fun disconnectAll()
}

/**
 * AudioInput backed by an AudioParam - used for frequency, gain, delay time, etc.
 */
class WebAudioParamInput(
    private val param: AudioParam,
    private val context: AudioContext
) : AudioInput {
    private val connectedSources = mutableListOf<AudioNode>()
    
    override fun set(value: Double) {
        // Direct set value at current time (snaps any automation)
        param.setValueAtTime(value.toFloat(), context.currentTime)
    }
    
    override fun disconnectAll() {
        connectedSources.forEach { source ->
            try {
                source.disconnect(param)
            } catch (_: Throwable) {
                // Ignore disconnection errors
            }
        }
        connectedSources.clear()
    }
    
    /** Connect an audio source to this param for audio-rate modulation */
    fun connectFrom(source: AudioNode) {
        source.connect(param)
        connectedSources.add(source)
    }
    
    /** Get the underlying AudioParam for direct Web Audio API connections */
    val webAudioParam: AudioParam get() = param
}

/**
 * AudioInput backed by an AudioNode's input - used for signal routing.
 */
class WebAudioNodeInput(
    private val node: AudioNode,
    private val inputIndex: Int = 0,
    private val context: AudioContext
) : AudioInput {
    private val connectedSources = mutableListOf<AudioNode>()
    
    private var constantSource: ConstantSourceNode? = null

    override fun set(value: Double) {
        // Use a ConstantSourceNode to provide a constant signal to the input
        if (constantSource == null) {
            constantSource = context.createConstantSource()
            constantSource!!.connect(node, 0, inputIndex)
            constantSource!!.start()
            connectedSources.add(constantSource!!)
        }
        
        // Update the value
        constantSource!!.offset.value = value.toFloat()
    }
    
    override fun disconnectAll() {
        connectedSources.forEach { source ->
            try {
                source.disconnect(node)
                if (source === constantSource) {
                    constantSource?.stop()
                    constantSource = null
                }
            } catch (_: Throwable) {
                // Ignore disconnection errors
            }
        }
        connectedSources.clear()
        // constantSource was cleared inside loop if it was in the list, but double check
        if (constantSource != null) {
            try { constantSource?.stop() } catch(_: Throwable) {}
            constantSource = null
        }
    }
    
    /** Connect an audio source to this node input */
    fun connectFrom(source: AudioNode, outputIndex: Int = 0) {
        source.connect(node, outputIndex, inputIndex)
        connectedSources.add(source)
    }
    
    /** Get the underlying AudioNode for direct connections */
    val webAudioNode: AudioNode get() = node
    val webAudioInputIndex: Int get() = inputIndex
}

/**
 * AudioInput that handles manual sets and node connections via a ScriptProcessorNode bridge.
 * Useful for parameters that need to trigger Kotlin logic (like Envelope gate).
 */
class WebAudioManualInput(
    private val context: AudioContext,
    private val onValueChange: (Double) -> Unit
) : AudioInput {
    private var scriptNode: ScriptProcessorNode? = null
    private var currentValue = 0.0

    override fun set(value: Double) {
        currentValue = value
        onValueChange(value)
    }

    override fun disconnectAll() {
        scriptNode?.disconnect()
        scriptNode = null
    }

    fun connectFrom(source: AudioNode, outputIndex: Int = 0) {
        if (scriptNode == null) {
            // Smaller buffer for better responsiveness (256 samples ~= 5.8ms @ 44.1kHz)
            scriptNode = context.createScriptProcessor(256, 1, 1)
            scriptNode!!.onaudioprocess = { event ->
                val data = event.inputBuffer.getChannelData(0)
                if (data.length > 0) {
                    // Check for transitions within the buffer for better gate detection
                    // For parameters, just taking the first value is often enough, 
                    // but for gates, we want to see if it crossed the 0.5 threshold anywhere.
                    var foundValue = data[0].toDouble()
                    for (i in 0 until data.length) {
                        val v = data[i].toDouble()
                        if ((v > 0.5 && currentValue <= 0.5) || (v <= 0.5 && currentValue > 0.5)) {
                            foundValue = v
                            break
                        }
                    }
                    
                    if (foundValue != currentValue) {
                        currentValue = foundValue
                        onValueChange(foundValue)
                    }
                }
            }
            scriptNode!!.connect(context.destination)
        }
        source.connect(scriptNode!!, outputIndex, 0)
    }
}

/**
 * WASM implementation of AudioOutput.
 */
actual interface AudioOutput : AudioPort {
    actual fun connect(input: AudioInput)
    actual fun connect(channel: Int, input: AudioInput, inputChannel: Int)
}

/**
 * AudioOutput backed by an AudioNode.
 */
class WebAudioNodeOutput(
    private val node: AudioNode,
    private val outputIndex: Int = 0
) : AudioOutput {
    override fun connect(input: AudioInput) {
        when (input) {
            is WebAudioParamInput -> input.connectFrom(node)
            is WebAudioNodeInput -> input.connectFrom(node, outputIndex)
            is WebAudioManualInput -> input.connectFrom(node, outputIndex)
        }
    }
    
    override fun connect(channel: Int, input: AudioInput, inputChannel: Int) {
        when (input) {
            is WebAudioParamInput -> input.connectFrom(node)
            is WebAudioNodeInput -> input.connectFrom(node, channel)
            is WebAudioManualInput -> input.connectFrom(node, channel)
        }
    }
    
    /** Get the underlying AudioNode for direct Web Audio API connections */
    val webAudioNode: AudioNode get() = node
    val webAudioOutputIndex: Int get() = outputIndex
}

/**
 * WASM implementation of AudioUnit.
 */
actual interface AudioUnit {
    actual val output: AudioOutput
}
