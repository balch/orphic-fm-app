package org.balch.orpheus.core.audio.dsp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Android [OutputCapture] over the native record ring. The stream rate is not fixed at 48000,
 * so [start] pins the rate the native side armed with and [sampleRate] holds it for the take.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OboeOutputCapture(private val bridge: OboeAudioBridge) : OutputCapture {
    @Volatile
    private var latchedRate = 0

    // Between takes there is no latch, so a store opened before start() sees the live rate.
    override val sampleRate: Int
        get() {
            if (latchedRate > 0) return latchedRate
            val live = bridge.nativeGetSampleRate()
            return if (live > 0) live else FALLBACK_RATE
        }

    override val isCapturing: Boolean get() = bridge.nativeIsRecording()

    override fun start() {
        val armedRate = bridge.nativeStartRecording()
        if (armedRate <= 0) {
            bridge.nativeStopRecording()
            throw IllegalStateException("No audio stream to record")
        }
        latchedRate = armedRate
    }

    override fun stop() {
        bridge.nativeStopRecording()
        latchedRate = 0
    }

    override fun drain(into: FloatArray): Int = bridge.nativeDrainRecording(into)
    override fun droppedFrames(): Long = bridge.nativeRecordDroppedFrames()

    private companion object {
        const val FALLBACK_RATE = 48000
    }
}
