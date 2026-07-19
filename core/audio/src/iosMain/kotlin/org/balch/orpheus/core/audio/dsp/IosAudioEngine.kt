package org.balch.orpheus.core.audio.dsp

import cnames.structs.OrpheusEngine
import cnames.structs.OrpheusIosAudio
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import orpheus_dsp.OrpheusMonitorData
import orpheus_dsp.orpheus_engine_clear_automation
import orpheus_dsp.orpheus_engine_create
import orpheus_dsp.orpheus_engine_destroy
import orpheus_dsp.orpheus_engine_get_monitor
import orpheus_dsp.orpheus_engine_get_port
import orpheus_dsp.orpheus_engine_get_pulsar_arrangement
import orpheus_dsp.orpheus_engine_get_pulsar_viz
import orpheus_dsp.orpheus_engine_get_spectrum
import orpheus_dsp.orpheus_engine_get_turntable_viz
import orpheus_dsp.orpheus_engine_get_viz
import orpheus_dsp.orpheus_engine_is_tts_playing
import orpheus_dsp.orpheus_engine_load_patch
import orpheus_dsp.orpheus_engine_load_tts_audio
import orpheus_dsp.orpheus_engine_master_fade
import orpheus_dsp.orpheus_engine_master_filter
import orpheus_dsp.orpheus_engine_master_scratch
import orpheus_dsp.orpheus_engine_master_tape_stop
import orpheus_dsp.orpheus_engine_master_volume_now
import orpheus_dsp.orpheus_engine_play_tts
import orpheus_dsp.orpheus_engine_set_automation
import orpheus_dsp.orpheus_engine_set_bend
import orpheus_dsp.orpheus_engine_set_delay_mix
import orpheus_dsp.orpheus_engine_set_drive
import orpheus_dsp.orpheus_engine_set_master_volume
import orpheus_dsp.orpheus_engine_set_port
import orpheus_dsp.orpheus_engine_set_vibrato
import orpheus_dsp.orpheus_engine_set_vibrato_rate
import orpheus_dsp.orpheus_engine_set_voice_active
import orpheus_dsp.orpheus_engine_set_voice_decay
import orpheus_dsp.orpheus_engine_set_voice_engine
import orpheus_dsp.orpheus_engine_set_voice_gate
import orpheus_dsp.orpheus_engine_set_voice_harmonics
import orpheus_dsp.orpheus_engine_set_voice_hold
import orpheus_dsp.orpheus_engine_set_voice_morph
import orpheus_dsp.orpheus_engine_set_voice_timbre
import orpheus_dsp.orpheus_engine_set_voice_tune
import orpheus_dsp.orpheus_engine_stop_tts
import orpheus_dsp.orpheus_engine_trigger_drum
import orpheus_dsp.orpheus_ios_audio_create
import orpheus_dsp.orpheus_ios_audio_destroy
import orpheus_dsp.orpheus_ios_audio_start
import orpheus_dsp.orpheus_ios_audio_stop
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.sampleRate
import platform.AVFAudio.setActive
import platform.AVFAudio.setPreferredIOBufferDuration
import platform.AVFAudio.setPreferredSampleRate
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSRecursiveLock
import platform.QuartzCore.CACurrentMediaTime
import platform.darwin.NSObjectProtocol
import kotlin.concurrent.Volatile

private const val TARGET_SAMPLE_RATE = 48000.0
private const val TARGET_BUFFER_DURATION = 256.0 / TARGET_SAMPLE_RATE // ~5.3ms

/**
 * iOS AudioEngine backed by the ObjC++ audio host in liborpheus_dsp
 * (orpheus_ios_audio.mm). The AVAudioEngine graph and its render block
 * live entirely in C/ObjC++ — no Kotlin executes on the CoreAudio
 * real-time thread, so Kotlin/Native GC pauses cannot stall rendering.
 * This class owns the AVAudioSession, lifecycle, notifications, and
 * lock-guarded control forwarding into the C API.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<AudioEngine>())
@Inject
class IosAudioEngine : AudioEngine, NativeDspBridge {

    // All engine/host lifecycle state is guarded by engineLock. Control
    // calls arrive from the main thread and coroutine workers; route-change
    // and interruption handlers run on the main queue. The lock closes the
    // capture-pointer-then-destroy race between any of them.
    private val engineLock = NSRecursiveLock()
    private var engine: CPointer<OrpheusEngine>? = null
    private var audioHost: CPointer<OrpheusIosAudio>? = null
    @Volatile
    private var _isRunning = false
    private var engineRecreatedCallback: (() -> Unit)? = null
    private var _sampleRate: Int = TARGET_SAMPLE_RATE.toInt()
    private var interruptionObserver: NSObjectProtocol? = null
    private var routeChangeObserver: NSObjectProtocol? = null

    private inline fun <T> withEngine(block: (CPointer<OrpheusEngine>) -> T): T? {
        engineLock.lock()
        try {
            return engine?.let(block)
        } finally {
            engineLock.unlock()
        }
    }

    init {
        log.info { "IosAudioEngine created (C++ DSP via cinterop)" }
    }

    override fun start() {
        if (_isRunning) return
        log.info { "start() called" }
        try {
            configureAudioSession()
            engineLock.lock()
            try {
                if (engine == null) {
                    engine = orpheus_engine_create(_sampleRate.toFloat())
                    log.info { "C++ engine created at sampleRate=$_sampleRate" }
                }
                if (audioHost == null) {
                    audioHost = orpheus_ios_audio_create(engine, _sampleRate.toDouble())
                }
                val rc = orpheus_ios_audio_start(audioHost)
                _isRunning = rc == 0
                if (rc != 0) log.error { "orpheus_ios_audio_start failed: $rc" }
            } finally {
                engineLock.unlock()
            }
            registerNotifications()
        } catch (e: Exception) {
            log.error(e) { "Failed to start audio engine" }
        }
    }

    override fun stop() {
        log.info { "stop() called" }
        _isRunning = false
        unregisterNotifications()
        engineLock.lock()
        try {
            // Destroy the host first: it stops AVAudioEngine, which
            // synchronizes with the render thread. Only after that is it
            // safe to destroy the C++ engine the render block was reading.
            audioHost?.let { orpheus_ios_audio_destroy(it) }
            audioHost = null
            engine?.let { orpheus_engine_destroy(it) }
            engine = null
        } finally {
            engineLock.unlock()
        }
        deactivateAudioSession()
    }

    override val isRunning: Boolean get() = _isRunning

    override val sampleRate: Int get() = _sampleRate

    override fun setOnEngineRecreatedCallback(callback: (() -> Unit)?) {
        engineRecreatedCallback = callback
    }

    override fun getCpuLoad(): Float = withEngine { eng ->
        memScoped {
            val mon = alloc<OrpheusMonitorData>()
            orpheus_engine_get_monitor(eng, mon.ptr)
            mon.cpu_load * 100f
        }
    } ?: 0f

    override fun getCurrentTime(): Double = CACurrentMediaTime()

    // -- AudioEngine plugin port forwarding ---
    override fun setPort(uri: String, symbol: String, value: Float) =
        nativeSetPort(uri, symbol, value)

    override fun getPort(uri: String, symbol: String): Float =
        nativeGetPort(uri, symbol)

    override fun triggerDrum(type: Int, accent: Float) =
        nativeTriggerDrum(type, accent)

    // ── Private audio setup ───────────────────────────

    private fun configureAudioSession() {
        val session = AVAudioSession.sharedInstance()
        try {
            session.setCategory(AVAudioSessionCategoryPlayback, error = null)
            session.setPreferredSampleRate(sampleRate = TARGET_SAMPLE_RATE, error = null)
            session.setPreferredIOBufferDuration(duration = TARGET_BUFFER_DURATION, error = null)
            session.setActive(true, error = null)

            _sampleRate = session.sampleRate.toInt()
            dspSampleRate = _sampleRate.toFloat()
            log.info { "Audio session configured: sampleRate=$_sampleRate" }
        } catch (e: Exception) {
            log.error(e) { "Failed to configure audio session" }
            throw e
        }
    }

    private fun deactivateAudioSession() {
        try {
            AVAudioSession.sharedInstance().setActive(
                active = false,
                withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
                error = null
            )
        } catch (e: Exception) {
            log.warn(e) { "Failed to deactivate audio session" }
        }
    }

    // ── Notification handling ─────────────────────────

    private fun registerNotifications() {
        unregisterNotifications() // idempotent: a failed-start retry must not orphan observers
        val center = NSNotificationCenter.defaultCenter

        interruptionObserver = center.addObserverForName(
            name = AVAudioSessionInterruptionNotification,
            `object` = AVAudioSession.sharedInstance(),
            queue = NSOperationQueue.mainQueue
        ) { notification -> handleInterruption(notification) }

        routeChangeObserver = center.addObserverForName(
            name = AVAudioSessionRouteChangeNotification,
            `object` = AVAudioSession.sharedInstance(),
            queue = NSOperationQueue.mainQueue
        ) { _ -> handleRouteChange() }
    }

    private fun unregisterNotifications() {
        val center = NSNotificationCenter.defaultCenter
        interruptionObserver?.let { observer: NSObjectProtocol -> center.removeObserver(observer) }
        routeChangeObserver?.let { observer: NSObjectProtocol -> center.removeObserver(observer) }
        interruptionObserver = null
        routeChangeObserver = null
    }

    private fun handleInterruption(notification: NSNotification?) {
        val userInfo = notification?.userInfo ?: return
        val typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? Long ?: return
        when (typeValue.toULong()) {
            AVAudioSessionInterruptionTypeBegan -> {
                log.info { "Audio session interruption began" }
                engineLock.lock()
                try {
                    audioHost?.let { orpheus_ios_audio_stop(it) }
                } finally {
                    engineLock.unlock()
                }
                _isRunning = false
            }
            AVAudioSessionInterruptionTypeEnded -> {
                log.info { "Audio session interruption ended" }
                val reactivated = AVAudioSession.sharedInstance().setActive(true, error = null)
                if (!reactivated) log.warn { "Audio session reactivation failed after interruption" }
                engineLock.lock()
                try {
                    audioHost?.let { _isRunning = orpheus_ios_audio_start(it) == 0 }
                } finally {
                    engineLock.unlock()
                }
            }
        }
    }

    private fun handleRouteChange() {
        val newRate = AVAudioSession.sharedInstance().sampleRate.toInt()
        var recreated = false
        engineLock.lock()
        try {
            if (engine == null) {
                // Queued notification can outlive stop(). No engine, nothing to rebuild.
                log.warn { "Route change with no engine, ignoring" }
                _isRunning = false
                return
            }
            if (newRate == _sampleRate) {
                // Same rate: rebuild only the audio host so the new route is
                // picked up. The C++ engine (graph, port state, Pulsar recipe)
                // survives. No reload, no audible reset.
                log.info { "Audio route changed (rate unchanged), rebuilding audio host" }
                audioHost?.let { orpheus_ios_audio_destroy(it) }
                audioHost = orpheus_ios_audio_create(engine, _sampleRate.toDouble())
                _isRunning = orpheus_ios_audio_start(audioHost) == 0
            } else {
                // Rate changed: the C++ engine is rate-bound, so rebuild
                // everything, then tell DspSynthEngine to reload the wiring
                // graph + port state into the fresh engine (same contract as
                // Android's Oboe recreate path). stop()/start() re-enter the
                // recursive lock on this thread, which is fine.
                log.info { "Audio route changed ($_sampleRate -> $newRate), full engine rebuild" }
                stop()
                start()
                recreated = true
            }
        } finally {
            engineLock.unlock()
        }
        // Fire outside the lock: the consumer launches a coroutine that calls
        // back into withEngine, and it should never contend with this handler.
        if (recreated) engineRecreatedCallback?.invoke()
    }

    // ── NativeDspBridge implementation ────────────────

    override fun nativeSetVoiceGate(index: Int, active: Boolean) {
        withEngine { orpheus_engine_set_voice_gate(it, index, if (active) 1 else 0) }
    }

    override fun nativeSetVoiceTune(index: Int, tune: Float) {
        withEngine { orpheus_engine_set_voice_tune(it, index, tune) }
    }

    override fun nativeSetVoiceEngine(index: Int, engineIndex: Int) {
        withEngine { orpheus_engine_set_voice_engine(it, index, engineIndex) }
    }

    override fun nativeSetVoiceHarmonics(index: Int, value: Float) {
        withEngine { orpheus_engine_set_voice_harmonics(it, index, value) }
    }

    override fun nativeSetVoiceTimbre(index: Int, value: Float) {
        withEngine { orpheus_engine_set_voice_timbre(it, index, value) }
    }

    override fun nativeSetVoiceMorph(index: Int, value: Float) {
        withEngine { orpheus_engine_set_voice_morph(it, index, value) }
    }

    override fun nativeSetVoiceDecay(index: Int, value: Float) {
        withEngine { orpheus_engine_set_voice_decay(it, index, value) }
    }

    override fun nativeSetVoiceActive(index: Int, active: Boolean) {
        withEngine { orpheus_engine_set_voice_active(it, index, if (active) 1 else 0) }
    }

    override fun nativeSetVoiceHold(index: Int, level: Float) {
        withEngine { orpheus_engine_set_voice_hold(it, index, level) }
    }

    override fun nativeSetMasterVolume(value: Float) {
        withEngine { orpheus_engine_set_master_volume(it, value) }
    }

    override fun nativeMasterFade(target: Float, samples: Int, curve: Int) {
        withEngine { orpheus_engine_master_fade(it, target, samples, curve) }
    }

    override fun nativeMasterTapeStop(samples: Int) {
        withEngine { orpheus_engine_master_tape_stop(it, samples) }
    }

    override fun nativeMasterScratch(samples: Int) {
        withEngine { orpheus_engine_master_scratch(it, samples) }
    }

    override fun nativeMasterFilter(samples: Int) {
        withEngine { orpheus_engine_master_filter(it, samples) }
    }

    override fun nativeMasterVolumeNow(): Float {
        return withEngine { orpheus_engine_master_volume_now(it) } ?: 0f
    }

    override fun nativeSetDrive(value: Float) {
        withEngine { orpheus_engine_set_drive(it, value) }
    }

    override fun nativeSetDelayMix(value: Float) {
        withEngine { orpheus_engine_set_delay_mix(it, value) }
    }

    override fun nativeSetVibrato(value: Float) {
        withEngine { orpheus_engine_set_vibrato(it, value) }
    }

    override fun nativeSetVibratoRate(value: Float) {
        withEngine { orpheus_engine_set_vibrato_rate(it, value) }
    }

    override fun nativeSetBend(value: Float) {
        withEngine { orpheus_engine_set_bend(it, value) }
    }

    override fun nativeSetPort(uri: String, symbol: String, value: Float) {
        withEngine { orpheus_engine_set_port(it, uri, symbol, value) }
    }

    override fun nativeGetPort(uri: String, symbol: String): Float {
        return withEngine { orpheus_engine_get_port(it, uri, symbol) } ?: 0f
    }

    override fun nativeGetMonitor(out: FloatArray) {
        withEngine { eng ->
            memScoped {
                val mon = alloc<OrpheusMonitorData>()
                orpheus_engine_get_monitor(eng, mon.ptr)
                // Copy struct fields to flat float array matching JNI layout:
                // The struct is laid out as contiguous floats, same order as the header
                out[0] = mon.peak_left
                out[1] = mon.peak_right
                out[2] = mon.cpu_load
                for (i in 0 until 12) {
                    out[3 + i] = mon.voice_levels[i]
                }
                out[15] = mon.lfo_output
                out[16] = mon.master_level
                out[17] = mon.bend_position
                out[18] = mon.lfo_output_a
                out[19] = mon.lfo_output_b
            }
        }
    }

    override fun nativeGetViz(channel: Int, outBuf: FloatArray, lastReadPos: IntArray): Int {
        return withEngine { eng ->
            outBuf.usePinned { pinnedBuf ->
                lastReadPos.usePinned { pinnedPos ->
                    orpheus_engine_get_viz(
                        eng, channel, pinnedBuf.addressOf(0), outBuf.size, pinnedPos.addressOf(0)
                    )
                }
            }
        } ?: 0
    }

    override fun nativeGetSpectrum(bands: FloatArray): Int {
        return withEngine { eng ->
            bands.usePinned { pinned ->
                orpheus_engine_get_spectrum(eng, pinned.addressOf(0), bands.size)
            }
        } ?: 0
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun nativeGetTurntableViz(deck: Int, outBuf: FloatArray) {
        withEngine { eng ->
            outBuf.usePinned { pinnedBuf ->
                orpheus_engine_get_turntable_viz(eng, deck, pinnedBuf.addressOf(0))
            }
        }
    }

    override fun nativeTriggerDrum(drumIndex: Int, accent: Float) {
        withEngine { orpheus_engine_trigger_drum(it, drumIndex, accent) }
    }

    override fun nativeLoadGraph(data: ByteArray): Int {
        return withEngine { eng ->
            data.usePinned { pinned ->
                orpheus_engine_load_patch(
                    eng,
                    pinned.addressOf(0).reinterpret(),
                    data.size.toULong()
                )
            }
        } ?: -1
    }

    override fun nativeSetAutomation(
        target: Int,
        voiceIndex: Int,
        times: FloatArray,
        values: FloatArray,
        count: Int
    ) {
        withEngine { eng ->
            times.usePinned { pinnedTimes ->
                values.usePinned { pinnedValues ->
                    orpheus_engine_set_automation(
                        eng,
                        target,
                        voiceIndex,
                        pinnedTimes.addressOf(0),
                        pinnedValues.addressOf(0),
                        count
                    )
                }
            }
        }
    }

    override fun nativeClearAutomation(target: Int, voiceIndex: Int) {
        withEngine { orpheus_engine_clear_automation(it, target, voiceIndex) }
    }

    override fun nativeLoadTtsAudio(samples: FloatArray, sampleRate: Int) {
        withEngine { eng ->
            samples.usePinned { pinned ->
                orpheus_engine_load_tts_audio(
                    eng,
                    pinned.addressOf(0),
                    samples.size,
                    sampleRate
                )
            }
        }
    }

    override fun nativePlayTts() {
        withEngine { orpheus_engine_play_tts(it) }
    }

    override fun nativeStopTts() {
        withEngine { orpheus_engine_stop_tts(it) }
    }

    override fun nativeIsTtsPlaying(): Int {
        return withEngine { orpheus_engine_is_tts_playing(it) } ?: 0
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun nativeGetPulsarArrangement(out: IntArray) {
        withEngine { eng ->
            out.usePinned { pinned ->
                orpheus_engine_get_pulsar_arrangement(eng, pinned.addressOf(0))
            }
        }
    }

    // Bridge scratch for gate int<->boolean conversion; single poll caller.
    private var pulsarGatesBridge = IntArray(0)

    @OptIn(ExperimentalForeignApi::class)
    override fun nativeGetPulsarViz(
        gatesOut: BooleanArray, velocitiesOut: FloatArray,
        playheadsOut: IntArray, stepCountsOut: IntArray,
    ) {
        withEngine { eng ->
            // C API uses int[] for gates (0/1), Kotlin uses BooleanArray — use IntArray as bridge
            if (pulsarGatesBridge.size != gatesOut.size) {
                pulsarGatesBridge = IntArray(gatesOut.size)
            }
            val intGates = pulsarGatesBridge
            intGates.usePinned { pinnedGates ->
                velocitiesOut.usePinned { pinnedVel ->
                    playheadsOut.usePinned { pinnedPlay ->
                        stepCountsOut.usePinned { pinnedSteps ->
                            orpheus_engine_get_pulsar_viz(
                                eng,
                                pinnedGates.addressOf(0),
                                pinnedVel.addressOf(0),
                                pinnedPlay.addressOf(0),
                                pinnedSteps.addressOf(0),
                            )
                        }
                    }
                }
            }
            // Convert int gates (0/1) to BooleanArray
            for (i in intGates.indices) {
                gatesOut[i] = intGates[i] != 0
            }
        }
    }

    // iOS not wired for live active-engine read-back yet — no-op stub.
    override fun nativeGetPulsarActiveEngines(out: IntArray) {
        for (i in out.indices) out[i] = -1
    }

    companion object {
        private val log = logging("IosAudioEngine")
    }
}
