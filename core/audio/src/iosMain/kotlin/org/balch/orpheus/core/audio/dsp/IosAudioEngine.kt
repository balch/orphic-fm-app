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
import platform.AVFAudio.AVAudioSessionInterruptionReasonKey
import platform.AVFAudio.AVAudioSessionInterruptionReasonRouteDisconnected
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.sampleRate
import platform.AVFAudio.setActive
import platform.AVFAudio.setPreferredIOBufferDuration
import platform.AVFAudio.setPreferredSampleRate
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSRecursiveLock
import platform.QuartzCore.CACurrentMediaTime
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import kotlin.concurrent.Volatile

private const val TARGET_SAMPLE_RATE = 48000.0
private const val TARGET_BUFFER_DURATION = 256.0 / TARGET_SAMPLE_RATE // ~5.3ms

// A Bluetooth device powering off leaves the media server mid-transition for
// a few hundred ms; host starts fail transiently in that window.
//
// Delays double per attempt (300/600/1200/2400/4800ms, ~9.3s total) rather
// than staying flat. Teardown is usually quick, but when it runs long a flat
// 5 × 300ms budget expires while the route is still settling, and once the
// budget is spent only a *new* route change revives audio — which never
// arrives when the vanished device was the last route event. Nothing else
// re-attempts: DspSynthEngine.start() early-returns after the first launch,
// so a spent budget means silence until relaunch. Backoff buys ~6x the
// window while still refusing to retry forever.
private const val MAX_HOST_START_RETRIES = 5
private const val HOST_START_BASE_RETRY_DELAY_MS = 300L

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
    // Written from the DspSynthEngine start path (Default dispatcher), read
    // on the main queue — @Volatile for rigorous cross-thread visibility.
    @Volatile
    private var engineRecreatedCallback: (() -> Unit)? = null
    @Volatile
    private var routeLostCallback: (() -> Unit)? = null
    private var _sampleRate: Int = TARGET_SAMPLE_RATE.toInt()
    private var interruptionObserver: NSObjectProtocol? = null
    private var routeChangeObserver: NSObjectProtocol? = null

    // Guarded by engineLock. Consecutive failed host starts; a fresh route
    // change resets the budget so each user-visible event gets full retries.
    private var hostStartRetries = 0

    // Guarded by engineLock. Bumped by stop() so a queued retry block from a
    // previous lifecycle can never restart audio the user deliberately
    // stopped — the block compares its captured generation before acting.
    private var retryGeneration = 0

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
                startHostLocked()
            } finally {
                engineLock.unlock()
            }
            registerNotifications()
        } catch (e: Exception) {
            log.error(e) { "Failed to start audio engine" }
            // Transient failures (session config mid-route-transition) are
            // recoverable — let the retry path re-run the full init.
            scheduleHostStartRetry()
        }
    }

    override fun stop() {
        log.info { "stop() called" }
        _isRunning = false
        unregisterNotifications()
        engineLock.lock()
        try {
            // Invalidate any queued host-start retry: a deliberate stop must
            // not be resurrected by a stale dispatch block firing later.
            retryGeneration++
            hostStartRetries = 0
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

    override fun setOnAudioRouteLostCallback(callback: (() -> Unit)?) {
        routeLostCallback = callback
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
        // These return false instead of throwing when error = null is passed —
        // check every result or failures vanish silently. Activation in
        // particular can be refused for a beat while a Bluetooth device is
        // tearing down; the host start then fails and the retry path
        // re-activates, so log-and-continue is correct here.
        if (!session.setCategory(AVAudioSessionCategoryPlayback, error = null)) {
            log.warn { "setCategory(playback) failed" }
        }
        session.setPreferredSampleRate(sampleRate = TARGET_SAMPLE_RATE, error = null)
        session.setPreferredIOBufferDuration(duration = TARGET_BUFFER_DURATION, error = null)
        if (!session.setActive(true, error = null)) {
            log.warn { "setActive(true) failed — host start will fail and retry" }
        }

        // Same hazard rebuildForCurrentRoute() guards against, on the path
        // that actually creates the C++ engine: a session that just refused
        // activation (or is mid-teardown) can report 0, and start() would
        // feed that straight into orpheus_engine_create() — a 0 Hz engine
        // that renders nothing until some later route change rebuilds it.
        // Keep the last known good rate; _sampleRate starts at
        // TARGET_SAMPLE_RATE, so there is always a sane value to fall back to.
        val reportedRate = session.sampleRate.toInt()
        if (reportedRate > 0) {
            _sampleRate = reportedRate
        } else {
            log.warn { "Session reported sampleRate=$reportedRate — keeping last known good $_sampleRate" }
        }
        dspSampleRate = _sampleRate.toFloat()
        log.info { "Audio session configured: sampleRate=$_sampleRate" }
    }

    private fun deactivateAudioSession() {
        val deactivated = AVAudioSession.sharedInstance().setActive(
            active = false,
            withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
            error = null
        )
        // Refusal here (media server busy during a route transition) is
        // harmless — the session deactivates when the app's I/O goes idle.
        if (!deactivated) log.warn { "setActive(false) refused — continuing" }
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
        ) { notification -> handleRouteChange(notification) }
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
        // Foundation hands us a platform NSNumber here. Kotlin/Native does
        // NOT bridge those in `as? Long` casts (only its own boxed numbers),
        // so the old cast silently nulled and every interruption fell
        // through unhandled. Go through NSNumber explicitly.
        val typeValue = (userInfo[AVAudioSessionInterruptionTypeKey] as? NSNumber)
            ?.unsignedLongValue ?: return
        when (typeValue) {
            AVAudioSessionInterruptionTypeBegan -> {
                // iOS 14.5+: a vanishing route (BT speaker powered off) can
                // arrive as an interruption with reason routeDisconnected —
                // and Apple documents that NO matching Ended ever follows it.
                // Parking the host and waiting would recreate the dead-audio
                // trap through a side door; treat it as a route event instead.
                val reasonValue = (userInfo[AVAudioSessionInterruptionReasonKey] as? NSNumber)
                    ?.unsignedLongValue
                val routeDied = reasonValue == AVAudioSessionInterruptionReasonRouteDisconnected
                log.info { "Audio session interruption began (routeDisconnected=$routeDied)" }
                engineLock.lock()
                try {
                    // Queued retries must not fight the system for audio
                    // while we're interrupted.
                    retryGeneration++
                    audioHost?.let { orpheus_ios_audio_stop(it) }
                    _isRunning = false
                    if (routeDied) {
                        // No Ended will come. Fresh user-visible event —
                        // full retry budget, revive once the teardown
                        // window passes.
                        hostStartRetries = 0
                        scheduleHostStartRetry()
                    }
                } finally {
                    engineLock.unlock()
                }
                // Outside the lock, like handleRouteChange: pause etiquette
                // must also apply when the route loss surfaces as an
                // interruption instead of (or before) a route change.
                if (routeDied) routeLostCallback?.invoke()
            }
            AVAudioSessionInterruptionTypeEnded -> {
                log.info { "Audio session interruption ended" }
                val reactivated = AVAudioSession.sharedInstance().setActive(true, error = null)
                if (!reactivated) log.warn { "Audio session reactivation failed after interruption" }
                engineLock.lock()
                try {
                    // Fresh budget: retries burned mid-interruption (while
                    // another session held audio) must not exhaust recovery.
                    hostStartRetries = 0
                    // Guard, don't null-tolerate: a null host means we were
                    // deliberately stopped — never revive audio from here.
                    if (audioHost != null) startHostLocked()
                } finally {
                    engineLock.unlock()
                }
            }
        }
    }

    private fun handleRouteChange(notification: NSNotification?) {
        val reason = (notification?.userInfo?.get(AVAudioSessionRouteChangeReasonKey) as? NSNumber)
            ?.unsignedLongValue
        val deviceLost = reason == AVAudioSessionRouteChangeReasonOldDeviceUnavailable
        var recreated = false
        engineLock.lock()
        try {
            if (engine == null) {
                // Queued notification can outlive stop(). No engine, nothing to rebuild.
                log.warn { "Route change with no engine, ignoring" }
                _isRunning = false
                return
            }
            // Each user-visible route event gets a fresh retry budget.
            hostStartRetries = 0
            recreated = rebuildForCurrentRoute()
        } finally {
            engineLock.unlock()
        }
        // Fire outside the lock: the consumers launch coroutines that call
        // back into withEngine, and they should never contend with this handler.
        if (recreated) engineRecreatedCallback?.invoke()
        if (deviceLost) {
            // The active output device vanished (BT speaker powered off).
            // PlaybackController auto-pauses via this — Apple's convention of
            // not continuing through the built-in speaker unprompted.
            log.info { "Audio route device became unavailable — notifying route-lost callback" }
            routeLostCallback?.invoke()
        }
    }

    /**
     * Rebuild audio output for whatever route the session now reports.
     * Must hold [engineLock]; requires [engine] != null. Returns true when
     * the C++ engine itself was recreated (rate change), so the caller can
     * fire [engineRecreatedCallback] outside the lock.
     */
    private fun rebuildForCurrentRoute(): Boolean {
        val session = AVAudioSession.sharedInstance()
        val newRate = session.sampleRate.toInt()
        // A mid-teardown session can report 0/garbage — never let that drive
        // a full rebuild that would create a 0 Hz C++ engine. Take the
        // same-rate path (last known good rate) and let retry sort it out.
        return if (newRate <= 0 || newRate == _sampleRate) {
            // Same rate: rebuild only the audio host so the new route is
            // picked up. The C++ engine (graph, port state, Pulsar recipe)
            // survives. No reload, no audible reset.
            log.info { "Rebuilding audio host (rate unchanged at $_sampleRate)" }
            // A Bluetooth teardown can deactivate the session out from under
            // us; reactivate (idempotent when already active) or the host
            // start below fails with a session error.
            if (!session.setActive(true, error = null)) {
                log.warn { "setActive(true) refused during host rebuild — start may fail and retry" }
            }
            audioHost?.let { orpheus_ios_audio_destroy(it) }
            audioHost = orpheus_ios_audio_create(engine, _sampleRate.toDouble())
            startHostLocked()
            false
        } else {
            // Rate changed: the C++ engine is rate-bound, so rebuild
            // everything, then tell DspSynthEngine to reload the wiring
            // graph + port state into the fresh engine (same contract as
            // Android's Oboe recreate path). stop()/start() re-enter the
            // recursive lock on this thread, which is fine. A start failure
            // inside schedules its own retry.
            log.info { "Audio route rate changed ($_sampleRate -> $newRate), full engine rebuild" }
            stop()
            start()
            true
        }
    }

    /**
     * Start the current [audioHost]. Must hold [engineLock]. On failure,
     * schedules a capped retry — one-shot starts are unreliable while a
     * Bluetooth device is tearing down, and without a retry a single miss
     * left audio dead until app relaunch (UI alive, play/pause inert).
     */
    private fun startHostLocked() {
        val rc = orpheus_ios_audio_start(audioHost)
        _isRunning = rc == 0
        if (rc == 0) {
            hostStartRetries = 0
        } else {
            log.error { "orpheus_ios_audio_start failed: $rc" }
            scheduleHostStartRetry()
        }
    }

    private fun scheduleHostStartRetry() {
        val attempt: Int
        val gen: Int
        engineLock.lock()
        try {
            if (hostStartRetries >= MAX_HOST_START_RETRIES) {
                log.error {
                    "Audio host failed after $MAX_HOST_START_RETRIES retries — " +
                        "giving up until the next route change"
                }
                return
            }
            hostStartRetries++
            attempt = hostStartRetries
            gen = retryGeneration
        } finally {
            engineLock.unlock()
        }
        val delayMs = HOST_START_BASE_RETRY_DELAY_MS shl (attempt - 1)
        log.info { "Audio host start retry $attempt/$MAX_HOST_START_RETRIES in ${delayMs}ms" }
        dispatch_after(
            dispatch_time(DISPATCH_TIME_NOW, delayMs * 1_000_000L),
            dispatch_get_main_queue(),
        ) {
            var recreated = false
            var startedFromNothing = false
            engineLock.lock()
            try {
                // Deliberately stopped since this was queued — do not revive.
                if (retryGeneration != gen) return@dispatch_after
                // Recovered some other way (route change beat us to it).
                if (_isRunning) return@dispatch_after
                log.info { "Audio host start retry $attempt firing" }
                if (engine == null) {
                    // First-launch start() failed before creating the engine —
                    // re-run the whole init path.
                    start()
                    startedFromNothing = engine != null
                } else {
                    recreated = rebuildForCurrentRoute()
                }
            } finally {
                engineLock.unlock()
            }
            // A brand-new engine has no graph — DspSynthEngine must load it
            // (same contract as the rate-change rebuild), else audio renders
            // silence even with a healthy host.
            if (recreated || startedFromNothing) engineRecreatedCallback?.invoke()
        }
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
