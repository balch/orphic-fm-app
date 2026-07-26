package org.balch.orpheus.core.audio.dsp

/**
 * Global sample rate for all DSP processing.
 * Set by the platform AudioEngine before audio starts.
 * Default is 48kHz; overwritten at runtime with the actual hardware rate.
 */
var dspSampleRate = 48000f

/**
 * Platform-independent audio engine interface.
 */
interface AudioEngine {
    /** Start audio processing */
    fun start()

    /** Stop audio processing */
    fun stop()

    /** Check if engine is running */
    val isRunning: Boolean

    /** Sample rate in Hz */
    val sampleRate: Int

    // Monitoring
    /** Get current CPU load (0.0 - 1.0) */
    fun getCpuLoad(): Float

    /** Get current audio time in seconds */
    fun getCurrentTime(): Double

    // Plugin port forwarding — overridden by native engines to forward to C++.
    /** Set a plugin control port value */
    fun setPort(uri: String, symbol: String, value: Float) {}
    /** Get a plugin control port value */
    fun getPort(uri: String, symbol: String): Float = 0f
    /** Trigger a drum voice (type 0=BD, 1=SD, 2=HH) */
    fun triggerDrum(type: Int, accent: Float) {}

    /**
     * Verify the host is actually rendering and repair it if not. Default
     * no-op — platforms whose host cannot die out from under them (Android,
     * Desktop, WASM) need no override.
     *
     * `IosAudioEngine.ensureRunning` is the one override: it kicks the
     * AVAudioEngine host watchdog immediately instead of waiting for the next
     * scheduled poll. It hands the pass to the audio host's serial queue and
     * returns immediately, so callers on the main thread do not block.
     */
    fun ensureRunning() {}
}
