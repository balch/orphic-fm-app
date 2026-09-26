package org.balch.orpheus.core.audio.dsp

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import java.util.concurrent.Executors

/**
 * Oboe-backed AudioEngine for Android using liborpheus_dsp.
 * Audio rendering happens entirely in C++ — no JNI in the audio callback.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<AudioEngine>())
@Inject
class OboeAudioEngine(
    private val bridge: OboeAudioBridge,
    private val application: Application,
) : AudioEngine, NativeDspBridge by bridge {

    private var routeLostCallback: (() -> Unit)? = null

    private val repairExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "oboe-repair").apply { isDaemon = true }
    }

    // The system sends this just before audio falls back to the built-in speaker
    // (BT speaker off, headphones unplugged), never when a device connects.
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            log.info { "Audio becoming noisy: output device lost" }
            routeLostCallback?.invoke()
        }
    }

    init {
        log.info { "OboeAudioEngine created (C++ DSP)" }
    }

    override fun setOnAudioRouteLostCallback(callback: (() -> Unit)?) {
        val wasRegistered = routeLostCallback != null
        routeLostCallback = callback
        if (callback != null && !wasRegistered) {
            application.registerReceiver(
                becomingNoisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else if (callback == null && wasRegistered) {
            runCatching { application.unregisterReceiver(becomingNoisyReceiver) }
        }
    }

    override fun start() {
        if (isRunning) return
        log.info { "start() called" }

        val openResult = bridge.nativeOpen()
        log.info { "nativeOpen() returned $openResult (0=OK)" }
        if (openResult != 0) {
            log.error { "Oboe stream FAILED to open, result=$openResult" }
            return
        }

        val sampleRate = bridge.nativeGetSampleRate()
        val framesPerBuffer = bridge.nativeGetFramesPerBuffer()
        log.info { "Stream opened: sampleRate=$sampleRate, framesPerBuffer=$framesPerBuffer" }
        dspSampleRate = sampleRate.toFloat()

        val startResult = bridge.nativeRequestStart()
        log.info { "nativeRequestStart() returned $startResult (0=OK)" }
        if (startResult != 0) {
            log.error { "Oboe stream FAILED to start, result=$startResult" }
        }
    }

    override fun stop() {
        bridge.nativeStop()
    }

    // A route change whose reopen failed leaves the host with no stream, and nothing else restarts
    // it. The reopen can take hundreds of ms, so it runs on repairExecutor, never the caller.
    override fun ensureRunning() {
        repairExecutor.execute { bridge.nativeEnsureRunning() }
    }

    override val isRunning: Boolean
        get() = bridge.nativeIsRunning()

    override val sampleRate: Int
        get() = bridge.nativeGetSampleRate().let { if (it > 0) it else 48000 }

    override fun getCpuLoad(): Float = (bridge.nativeGetCpuLoad() * 100f).toFloat()

    override fun getCurrentTime(): Double = System.nanoTime() / 1_000_000_000.0

    // -- AudioEngine plugin port forwarding (delegates to C++ bridge) ----------
    override fun setPort(uri: String, symbol: String, value: Float) = bridge.nativeSetPort(uri, symbol, value)
    override fun getPort(uri: String, symbol: String): Float = bridge.nativeGetPort(uri, symbol)
    override fun triggerDrum(type: Int, accent: Float) = bridge.nativeTriggerDrum(type, accent)

    companion object {
        private val log = logging("OboeAudioEngine")
    }
}
