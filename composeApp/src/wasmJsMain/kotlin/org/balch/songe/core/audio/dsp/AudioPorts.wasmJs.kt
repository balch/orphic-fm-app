package org.balch.songe.core.audio.dsp

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
        }
    }
    
    override fun connect(channel: Int, input: AudioInput, inputChannel: Int) {
        when (input) {
            is WebAudioParamInput -> input.connectFrom(node)
            is WebAudioNodeInput -> input.connectFrom(node, channel)
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
