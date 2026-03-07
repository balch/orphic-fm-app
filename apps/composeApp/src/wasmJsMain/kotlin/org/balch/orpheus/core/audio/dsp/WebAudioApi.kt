@file:Suppress("NOTHING_TO_INLINE")
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.balch.orpheus.core.audio.dsp


/**
 * Typed Arrays for Wasm interop
 */
external class Float32Array(length: Int) : JsAny {
    val length: Int
    @JsName("get")
    operator fun get(index: Int): Float
    @JsName("set")
    operator fun set(index: Int, value: Float)
}

external class Uint8Array(length: Int) : JsAny {
    val length: Int
    @JsName("get")
    operator fun get(index: Int): Byte
    @JsName("set")
    operator fun set(index: Int, value: Byte)
}

/**
 * Kotlin/WASM external declarations for Web Audio API.
 * These map to the browser's native Web Audio interfaces.
 */

@JsName("AudioContext")
external class AudioContext {
    val sampleRate: Float
    val destination: AudioDestinationNode
    val currentTime: Double
    val state: String  // "suspended", "running", "closed"
    
    fun createOscillator(): OscillatorNode
    fun createGain(): GainNode
    fun createDelay(maxDelayTime: Double = definedExternally): DelayNode
    fun createAnalyser(): AnalyserNode
    fun createWaveShaper(): WaveShaperNode
    fun createChannelSplitter(numberOfOutputs: Int = definedExternally): ChannelSplitterNode
    fun createChannelMerger(numberOfInputs: Int = definedExternally): ChannelMergerNode
    fun createConstantSource(): ConstantSourceNode
    fun createBuffer(numberOfChannels: Int, length: Int, sampleRate: Float): AudioBuffer
    fun createBufferSource(): AudioBufferSourceNode
    fun createBiquadFilter(): BiquadFilterNode
    fun createScriptProcessor(bufferSize: Int, numberOfInputChannels: Int, numberOfOutputChannels: Int): ScriptProcessorNode
    
    fun resume(): JsAny  // Returns Promise
    fun suspend(): JsAny // Returns Promise
    fun close(): JsAny   // Returns Promise
}

external interface AudioNode : JsAny {
    val context: AudioContext
    val numberOfInputs: Int
    val numberOfOutputs: Int
    
    fun connect(destination: AudioNode, outputIndex: Int = definedExternally, inputIndex: Int = definedExternally): AudioNode
    fun connect(destination: AudioParam, outputIndex: Int = definedExternally)
    fun disconnect()
    fun disconnect(destination: AudioNode)
    fun disconnect(destination: AudioParam)
}

external interface AudioParam : JsAny {
    var value: Float
    val defaultValue: Float
    val minValue: Float
    val maxValue: Float
    
    fun setValueAtTime(value: Float, startTime: Double): AudioParam
    fun linearRampToValueAtTime(value: Float, endTime: Double): AudioParam
    fun exponentialRampToValueAtTime(value: Float, endTime: Double): AudioParam
    fun setTargetAtTime(target: Float, startTime: Double, timeConstant: Double): AudioParam
    fun cancelScheduledValues(cancelTime: Double): AudioParam
}

external interface AudioDestinationNode : AudioNode {
    val maxChannelCount: Int
}

external interface OscillatorNode : AudioNode {
    val frequency: AudioParam
    val detune: AudioParam
    var type: String  // "sine", "square", "sawtooth", "triangle", "custom"
    
    fun start(time: Double = definedExternally)
    fun stop(time: Double = definedExternally)
}

external interface GainNode : AudioNode {
    val gain: AudioParam
}

external interface BiquadFilterNode : AudioNode {
    val frequency: AudioParam
    val detune: AudioParam
    val Q: AudioParam
    val gain: AudioParam
    var type: String // "lowpass", "highpass", "bandpass", "lowshelf", "highshelf", "peaking", "notch", "allpass"
}

external interface DelayNode : AudioNode {
    val delayTime: AudioParam
}

external interface AnalyserNode : AudioNode {
    var fftSize: Int
    val frequencyBinCount: Int
    var minDecibels: Float
    var maxDecibels: Float
    var smoothingTimeConstant: Float
    
    fun getFloatTimeDomainData(array: Float32Array)
    fun getByteTimeDomainData(array: Uint8Array)
    fun getFloatFrequencyData(array: Float32Array)
    fun getByteFrequencyData(array: Uint8Array)
}

external interface WaveShaperNode : AudioNode {
    var curve: Float32Array?
    var oversample: String  // "none", "2x", "4x"
}

external interface ChannelSplitterNode : AudioNode
external interface ChannelMergerNode : AudioNode

external interface ConstantSourceNode : AudioNode {
    val offset: AudioParam
    fun start(time: Double = definedExternally)
    fun stop(time: Double = definedExternally)
}

external interface ScriptProcessorNode : AudioNode {
    var onaudioprocess: (AudioProcessingEvent) -> Unit
}

external interface AudioProcessingEvent : JsAny {
    val inputBuffer: AudioBuffer
    val outputBuffer: AudioBuffer
}

external interface AudioBuffer : JsAny {
    val sampleRate: Float
    val length: Int
    val duration: Double
    val numberOfChannels: Int
    fun getChannelData(channel: Int): Float32Array
}

external interface AudioBufferSourceNode : AudioNode {
    var buffer: AudioBuffer?
    val playbackRate: AudioParam
    val detune: AudioParam
    var loop: Boolean
    var loopStart: Double
    var loopEnd: Double
    fun start(time: Double = definedExternally, offset: Double = definedExternally, duration: Double = definedExternally)
    fun stop(time: Double = definedExternally)
}

// Helper to create AudioContext - not inline for WASM compatibility
fun createAudioContext(): AudioContext = js("new AudioContext()")

@JsName("OrphicFM")
external object OrphicFM {
    @JsName("syncNode")
    fun syncNode(node: AudioNode)
}

// --- AudioWorklet API declarations ---

external interface AudioWorkletNode : AudioNode {
    val port: MessagePort
}

external interface MessagePort : JsAny {
    fun postMessage(message: JsAny)
    fun postMessage(message: JsAny, transfer: JsArray<JsAny>)
}

// --- JS bridge functions ---
// These delegate to dsp-worklet-bridge.js loaded as a regular script.
// Kotlin/WASM js() only supports single-expression bodies.

fun jsAddWorkletModule(ctx: AudioContext, url: String): JsAny =
    js("ctx.audioWorklet.addModule(url)")

fun jsCreateWorkletNode(ctx: AudioContext, name: String): AudioWorkletNode =
    js("new AudioWorkletNode(ctx, name, { outputChannelCount: [2] })")

fun jsSetInterval(ms: Int, callback: () -> Unit): Int =
    js("setInterval(callback, ms)")

fun jsClearInterval(id: Int): Unit =
    js("clearInterval(id)")

/**
 * Create a new Float32Array of the given length.
 */
fun jsNewFloat32Array(length: Int): Float32Array =
    js("new Float32Array(length)")

/**
 * Post a stereo buffer to a worklet node's port with Transferable arrays.
 * Uses a single js() expression for Kotlin/WASM compatibility.
 */
fun jsPostBufferToWorklet(node: AudioWorkletNode, left: Float32Array, right: Float32Array): Unit =
    js("node.port.postMessage({ type: 'buffer', left: left, right: right }, [left.buffer, right.buffer])")

/**
 * Attach a then-handler to a JS Promise.
 */
fun jsPromiseThen(promise: JsAny, callback: () -> Unit): Unit =
    js("promise.then(callback)")

/**
 * Set up a listener that stores queue depth in a global variable.
 * Avoids Kotlin/WASM callback parameter conversion issues.
 */
fun jsSetupQueueDepthListener(node: AudioWorkletNode): Unit =
    js("node.port.onmessage = function(e) { if (e.data.type === 'queueDepth') { globalThis.__orpheusQueueDepth = e.data.depth; globalThis.__orpheusUnderruns = e.data.underruns || 0 } }")

/**
 * Read the latest queue depth stored by the worklet listener.
 */
fun jsGetQueueDepth(): Int =
    js("globalThis.__orpheusQueueDepth || 0")

/**
 * Read the cumulative underrun count reported by the worklet.
 */
fun jsGetUnderrunCount(): Int =
    js("globalThis.__orpheusUnderruns || 0")

/**
 * High-resolution timestamp in milliseconds (sub-ms precision).
 */
fun jsPerformanceNow(): Double =
    js("performance.now()")
