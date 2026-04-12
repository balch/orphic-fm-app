package org.balch.orpheus.core.audio.dsp

import com.diamondedge.logging.logging

/**
 * JVM desktop AudioEngine backed by the C++ DSP engine (liborpheus_desktop).
 *
 * Audio output uses miniaudio: a native CoreAudio/WASAPI/ALSA callback
 * calls DesktopEngine::process() directly from the OS audio thread.
 * No Java Sound mixer, no float-to-int16 conversion, no JNI per-buffer round-trip.
 */
class NativeDspAudioEngine : AudioEngine, NativeDspBridge {

    private val bridge = DesktopDspBridge()

    private val sampleRateHz = 48000

    @Volatile
    private var running = false

    init {
        log.info { "NativeDspAudioEngine created (C++ DSP + miniaudio)" }
    }

    // -- AudioEngine ----------------------------------------------------------

    override fun start() {
        if (running) return
        log.info { "start() called" }

        bridge.nativeOpen(sampleRateHz)
        dspSampleRate = sampleRateHz.toFloat()
        log.info { "nativeOpen($sampleRateHz) completed" }

        if (!bridge.nativeStartAudio()) {
            log.error { "Failed to start miniaudio device" }
            bridge.nativeClose()
            return
        }

        running = true
        log.info { "Audio engine started (miniaudio)" }
    }

    override fun stop() {
        if (!running) return
        log.info { "stop() called" }

        running = false
        bridge.nativeStopAudio()
        bridge.nativeClose()
        log.info { "Audio engine stopped" }
    }

    override val isRunning: Boolean
        get() = running

    override val sampleRate: Int
        get() = sampleRateHz

    override fun getCpuLoad(): Float = (bridge.nativeGetCpuLoad() * 100f).toFloat()

    override fun getCurrentTime(): Double = System.nanoTime() / 1_000_000_000.0

    // -- AudioEngine plugin port forwarding (delegates to C++ bridge) ----------
    override fun setPort(uri: String, symbol: String, value: Float) = bridge.nativeSetPort(uri, symbol, value)
    override fun getPort(uri: String, symbol: String): Float = bridge.nativeGetPort(uri, symbol)
    override fun triggerDrum(type: Int, accent: Float) = bridge.nativeTriggerDrum(type, accent)

    // -- NativeDspBridge implementation ---------------------------------------

    override fun nativeSetVoiceGate(index: Int, active: Boolean) = bridge.nativeSetVoiceGate(index, active)
    override fun nativeSetVoiceTune(index: Int, tune: Float) = bridge.nativeSetVoiceTune(index, tune)
    override fun nativeSetVoiceEngine(index: Int, engineIndex: Int) = bridge.nativeSetVoiceEngine(index, engineIndex)
    override fun nativeSetVoiceHarmonics(index: Int, value: Float) = bridge.nativeSetVoiceHarmonics(index, value)
    override fun nativeSetVoiceTimbre(index: Int, value: Float) = bridge.nativeSetVoiceTimbre(index, value)
    override fun nativeSetVoiceMorph(index: Int, value: Float) = bridge.nativeSetVoiceMorph(index, value)
    override fun nativeSetVoiceDecay(index: Int, value: Float) = bridge.nativeSetVoiceDecay(index, value)
    override fun nativeSetVoiceActive(index: Int, active: Boolean) = bridge.nativeSetVoiceActive(index, active)
    override fun nativeSetVoiceHold(index: Int, level: Float) = bridge.nativeSetVoiceHold(index, level)
    override fun nativeSetMasterVolume(value: Float) = bridge.nativeSetMasterVolume(value)
    override fun nativeSetDrive(value: Float) = bridge.nativeSetDrive(value)
    override fun nativeSetDelayMix(value: Float) = bridge.nativeSetDelayMix(value)
    override fun nativeSetVibrato(value: Float) = bridge.nativeSetVibrato(value)
    override fun nativeSetVibratoRate(value: Float) = bridge.nativeSetVibratoRate(value)
    override fun nativeSetBend(value: Float) = bridge.nativeSetBend(value)
    override fun nativeSetPort(uri: String, symbol: String, value: Float) = bridge.nativeSetPort(uri, symbol, value)
    override fun nativeGetPort(uri: String, symbol: String): Float = bridge.nativeGetPort(uri, symbol)
    override fun nativeGetMonitor(out: FloatArray) = bridge.nativeGetMonitor(out)
    override fun nativeTriggerDrum(drumIndex: Int, accent: Float) = bridge.nativeTriggerDrum(drumIndex, accent)
    override fun nativeLoadGraph(data: ByteArray): Int = bridge.nativeLoadGraph(data)
    override fun nativeSetAutomation(target: Int, voiceIndex: Int, times: FloatArray, values: FloatArray, count: Int) =
        bridge.nativeSetAutomation(target, voiceIndex, times, values, count)
    override fun nativeClearAutomation(target: Int, voiceIndex: Int) =
        bridge.nativeClearAutomation(target, voiceIndex)
    override fun nativeLoadTtsAudio(samples: FloatArray, sampleRate: Int) =
        bridge.nativeLoadTtsAudio(samples, sampleRate)
    override fun nativePlayTts() = bridge.nativePlayTts()
    override fun nativeStopTts() = bridge.nativeStopTts()
    override fun nativeIsTtsPlaying(): Int = bridge.nativeIsTtsPlaying()
    override fun nativeGetViz(channel: Int, outBuf: FloatArray, lastReadPos: IntArray): Int =
        bridge.nativeGetViz(channel, outBuf, lastReadPos)
    override fun nativeGetTurntableViz(deck: Int, outBuf: FloatArray) =
        bridge.nativeGetTurntableViz(deck, outBuf)
    override fun nativeGetPulsarViz(
        gatesOut: BooleanArray, velocitiesOut: FloatArray,
        playheadsOut: IntArray, stepCountsOut: IntArray,
    ) = bridge.nativeGetPulsarViz(gatesOut, velocitiesOut, playheadsOut, stepCountsOut)
    override fun nativeGetPulsarArrangement(out: IntArray) = bridge.nativeGetPulsarArrangement(out)

    companion object {
        private val log = logging("NativeDspAudioEngine")
    }
}
