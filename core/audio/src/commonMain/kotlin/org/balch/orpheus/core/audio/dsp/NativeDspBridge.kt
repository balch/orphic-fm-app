package org.balch.orpheus.core.audio.dsp

/**
 * Interface for forwarding DSP control calls to a native C++ engine.
 * Implemented by platform-specific AudioEngine implementations (e.g., OboeAudioEngine).
 * When present, DspSynthEngine forwards voice/parameter control through this bridge
 * in addition to (or instead of) the Kotlin DSP graph.
 */
interface NativeDspBridge {
    fun nativeSetVoiceGate(index: Int, active: Boolean)
    fun nativeSetVoiceTune(index: Int, tune: Float)
    fun nativeSetVoiceEngine(index: Int, engineIndex: Int)
    fun nativeSetVoiceHarmonics(index: Int, value: Float)
    fun nativeSetVoiceTimbre(index: Int, value: Float)
    fun nativeSetVoiceMorph(index: Int, value: Float)
    fun nativeSetVoiceDecay(index: Int, value: Float)
    fun nativeSetVoiceActive(index: Int, active: Boolean)
    fun nativeSetVoiceHold(index: Int, level: Float)
    fun nativeSetMasterVolume(value: Float)
    fun nativeMasterFade(target: Float, samples: Int, curve: Int)
    fun nativeMasterTapeStop(samples: Int)
    fun nativeMasterScratch(samples: Int)
    fun nativeMasterFilter(samples: Int)
    fun nativeMasterVolumeNow(): Float
    fun nativeSetDrive(value: Float)
    fun nativeSetDelayMix(value: Float)
    fun nativeSetVibrato(value: Float)
    fun nativeSetVibratoRate(value: Float)
    fun nativeSetBend(value: Float)
    fun nativeSetPort(uri: String, symbol: String, value: Float)
    fun nativeGetPort(uri: String, symbol: String): Float
    fun nativeGetMonitor(out: FloatArray)

    /**
     * Cumulative audio underrun (xrun) count for the current output stream.
     * Only meaningful on Android (exclusive MMAP underruns are invisible to
     * the OS mixer — the client is the sole observer). Other platforms
     * return 0. The counter resets when the stream is reopened, so callers
     * must treat decreases as a reset, not an error.
     */
    fun nativeGetXRunCount(): Int = 0
    fun nativeGetViz(channel: Int, outBuf: FloatArray, lastReadPos: IntArray): Int
    fun nativeGetSpectrum(bands: FloatArray): Int
    fun nativeGetTurntableViz(deck: Int, outBuf: FloatArray)
    fun nativeTriggerDrum(drumIndex: Int, accent: Float)
    fun nativeLoadGraph(data: ByteArray): Int
    fun nativeSetAutomation(target: Int, voiceIndex: Int, times: FloatArray, values: FloatArray, count: Int)
    fun nativeClearAutomation(target: Int, voiceIndex: Int)
    fun nativeLoadTtsAudio(samples: FloatArray, sampleRate: Int)
    fun nativePlayTts()
    fun nativeStopTts()
    fun nativeIsTtsPlaying(): Int
    fun nativeGetPulsarViz(
        gatesOut: BooleanArray,
        velocitiesOut: FloatArray,
        playheadsOut: IntArray,
        stepCountsOut: IntArray,
    )
    /** Reads the live per-track active engine ids (what the DSP is playing) into out[8]. */
    fun nativeGetPulsarActiveEngines(out: IntArray)
    fun nativeGetPulsarArrangement(out: IntArray)

    /**
     * Register a callback fired when the underlying C++ engine is recreated
     * (e.g. on Android, an audio output route change with a different sample
     * rate causes Oboe to destroy/recreate the engine — the new engine has
     * NO graph and audio is silent until reloaded).
     *
     * The callback runs on whatever thread native code chooses; consumers
     * should marshal to a safe coroutine scope before doing real work.
     *
     * Default: no-op. Platforms whose audio engine never recreates itself
     * (current desktop/wasm) don't need to override.
     */
    fun setOnEngineRecreatedCallback(callback: (() -> Unit)?) {}

    /**
     * Register a callback fired when the active audio output route's device
     * disappears (e.g. a Bluetooth speaker powering off — iOS route change
     * reason `oldDeviceUnavailable`). Feeds the auto-pause etiquette in
     * PlaybackController via DspSynthEngine's AudioRouteMonitor flow.
     *
     * Runs on whatever thread the platform delivers route events on (iOS:
     * the main queue); consumers must not block.
     *
     * Default: no-op. Platforms without route-loss awareness (current
     * desktop/wasm/Android) don't need to override.
     */
    fun setOnAudioRouteLostCallback(callback: (() -> Unit)?) {}
}
