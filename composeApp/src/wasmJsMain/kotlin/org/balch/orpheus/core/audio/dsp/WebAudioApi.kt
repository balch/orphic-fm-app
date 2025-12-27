@file:Suppress("NOTHING_TO_INLINE")
@file:OptIn(ExperimentalWasmJsInterop::class)

package org.balch.orpheus.core.audio.dsp

import org.khronos.webgl.Float32Array
import org.khronos.webgl.Uint8Array

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
@JsFun("() => new AudioContext()")
external fun createAudioContext(): AudioContext
