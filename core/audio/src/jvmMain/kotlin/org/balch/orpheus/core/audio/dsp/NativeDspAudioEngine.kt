package org.balch.orpheus.core.audio.dsp

import com.diamondedge.logging.logging
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * JVM desktop AudioEngine backed by the C++ DSP engine (liborpheus_desktop).
 *
 * Audio output uses javax.sound.sampled with a pull model:
 * a dedicated thread calls [DesktopDspBridge.nativeProcess] to fill interleaved stereo
 * float buffers, then writes them to a [SourceDataLine].
 */
class NativeDspAudioEngine : AudioEngine, NativeDspBridge {

    private val bridge = DesktopDspBridge()

    private val sampleRateHz = 48000
    private val bufferFrames = 512
    private val channels = 2

    @Volatile
    private var running = false
    private var audioThread: Thread? = null
    private var line: SourceDataLine? = null

    init {
        log.info { "NativeDspAudioEngine created (C++ DSP)" }
    }

    // -- AudioEngine ----------------------------------------------------------

    override fun start() {
        if (running) return
        log.info { "start() called" }

        bridge.nativeOpen(sampleRateHz)
        dspSampleRate = sampleRateHz.toFloat()
        log.info { "nativeOpen($sampleRateHz) completed" }

        // PCM_SIGNED, 48 kHz, 16-bit, stereo, little-endian (universally supported)
        val bytesPerSample = 2
        val format = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            sampleRateHz.toFloat(),
            16,
            channels,
            channels * bytesPerSample,
            sampleRateHz.toFloat(),
            false // little-endian
        )

        val sdl = AudioSystem.getSourceDataLine(format)
        val lineBufferSize = bufferFrames * channels * bytesPerSample * 2 // 2x headroom
        sdl.open(format, lineBufferSize)
        sdl.start()
        line = sdl
        log.info { "SourceDataLine opened: bufferSize=$lineBufferSize, format=$format" }

        running = true

        val thread = Thread({
            val floatBuf = FloatArray(bufferFrames * channels)
            val byteBuf = ByteArray(bufferFrames * channels * bytesPerSample)
            val bb = ByteBuffer.wrap(byteBuf).order(ByteOrder.LITTLE_ENDIAN)

            log.info { "Audio thread started" }
            while (running) {
                bridge.nativeProcess(floatBuf)
                bb.clear()
                for (sample in floatBuf) {
                    val clamped = sample.coerceIn(-1f, 1f)
                    bb.putShort((clamped * 32767f).toInt().toShort())
                }
                sdl.write(byteBuf, 0, byteBuf.size)
            }
            log.info { "Audio thread exiting" }
        }, "OrpheusAudio")
        thread.isDaemon = true
        thread.priority = Thread.MAX_PRIORITY
        thread.start()
        audioThread = thread

        log.info { "Audio engine started" }
    }

    override fun stop() {
        if (!running) return
        log.info { "stop() called" }

        running = false
        audioThread?.join(2000)
        audioThread = null

        line?.stop()
        line?.close()
        line = null

        bridge.nativeClose()
        log.info { "Audio engine stopped" }
    }

    override val isRunning: Boolean
        get() = running

    override val sampleRate: Int
        get() = sampleRateHz

    override fun addUnit(unit: AudioUnit) {
        // No-op: C++ engine manages its own units
    }

    override fun setUnitEnabled(unit: AudioUnit, enabled: Boolean) {
        // No-op: C++ engine manages its own units
    }

    override val lineOutLeft: AudioInput
        get() = NoOpAudioInput

    override val lineOutRight: AudioInput
        get() = NoOpAudioInput

    override fun getCpuLoad(): Float = (bridge.nativeGetCpuLoad() * 100f).toFloat()

    override fun getCurrentTime(): Double = System.nanoTime() / 1_000_000_000.0

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

    companion object {
        private val log = logging("NativeDspAudioEngine")
    }
}

/** Placeholder AudioInput -- C++ handles all routing internally. */
private object NoOpAudioInput : AudioInput {
    override fun set(value: Double) {}
    override fun disconnectAll() {}
}
