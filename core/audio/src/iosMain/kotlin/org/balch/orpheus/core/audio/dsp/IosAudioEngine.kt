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
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import orpheus_dsp.OrpheusMonitorData
import orpheus_dsp.orpheus_engine_blocks_rendered
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
import orpheus_dsp.orpheus_ios_audio_is_running
import orpheus_dsp.orpheus_ios_audio_set_config_change_callback
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
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.currentRoute
import platform.AVFAudio.otherAudioPlaying
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
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_time
import kotlin.concurrent.Volatile

private const val TARGET_SAMPLE_RATE = 48000.0
private const val TARGET_BUFFER_DURATION = 256.0 / TARGET_SAMPLE_RATE // ~5.3ms

// Watchdog poll period. The render counter is sampled this often and the
// decision in shouldRepair() is made from consecutive samples, so this is
// also the resolution of "how long was it silent".
//
// 1s is chosen against the counter's rate, not against human patience: at
// 48kHz with a 256-frame buffer it advances ~187 times a second, and even at
// a pathological 4096-frame buffer ~11 times a second. One second is
// therefore never a coin flip, the count either moved by double digits or
// the render thread is not running at all.
private const val WATCHDOG_TICK_MS = 1000L

// Consecutive unchanged samples before repairing. Two means repair lands
// somewhere between 2 and 3 seconds after audio actually died: the first
// stale sample can be as young as a hair over one second of silence, the
// second confirms it.
//
// The asymmetry that sets this number: a false positive costs a
// startAndReturnError: on an already-running engine, which is a documented
// no-op. A missed stall costs silence until the user relaunches the app.
// One tick would be defensible. Two buys immunity to a single legitimately
// parked render thread (app resume, buffer-size renegotiation) for one
// extra second of worst-case silence.
private const val STALE_TICKS_BEFORE_REPAIR = 2

// How long the watchdog stands down after an interruption begins. While
// another session owns audio, repairing is us fighting the system for the
// route. This is a backstop, not the normal path: an Ended notification
// clears the deadline as soon as it arrives, and 30s only matters when one
// never does.
//
// It is a cap, not a promise that the interruption is over. See
// otherAudioOwnsTheRoute(), which re-arms it for as long as somebody else is
// actually playing. Without that, the cap expiring mid-call is the watchdog
// deciding a counter we froze ourselves is a stall, and taking the route.
private const val INTERRUPTION_SUSPEND_SECONDS = 30.0

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
    // calls arrive from the main thread and coroutine workers; route-change,
    // interruption and watchdog work runs on hostQueue. The lock closes the
    // capture-pointer-then-destroy race between any of them. Nothing ever
    // blocks on hostQueue while holding the lock, so there is no inversion.
    private val engineLock = NSRecursiveLock()
    private var engine: CPointer<OrpheusEngine>? = null
    private var audioHost: CPointer<OrpheusIosAudio>? = null

    // Cached observation, and only that. AVAudioEngine stops itself when the
    // output hardware's channel count or sample rate changes, so this can be
    // true while nothing is rendering. That is not a bug to be fixed here,
    // it is unfixable from Kotlin: startAndReturnError: really did return YES
    // and the engine really did die milliseconds later. So this value must
    // never gate recovery. It is a fast path for start() and a UI readout.
    @Volatile
    private var _isRunning = false

    // Intent, not observation. True from start() until stop(). The watchdog
    // reconciles this against render progress and repairs whenever the two
    // disagree, which is what makes recovery closed-loop: there is no state
    // in which this is true and nothing is trying to repair.
    @Volatile
    private var shouldBeRunning = false

    // CACurrentMediaTime deadline. While in the future the watchdog stands
    // down, so we never fight the system for audio during a call or Siri.
    @Volatile
    private var suspendWatchdogUntil = 0.0

    // Written from the DspSynthEngine start path (Default dispatcher), read
    // on hostQueue — @Volatile for rigorous cross-thread visibility.
    @Volatile
    private var engineRecreatedCallback: (() -> Unit)? = null
    @Volatile
    private var routeLostCallback: (() -> Unit)? = null
    private var _sampleRate: Int = TARGET_SAMPLE_RATE.toInt()

    // Guarded by engineLock, and they move as a SET: registerNotifications()
    // assigns all three or none, unregisterNotifications() clears all three.
    // That invariant is what lets the registered check test only the first.
    //
    // The lock is not decorative. start() runs on a Dispatchers.Default worker
    // and stop() on the caller's thread, and both touch these; unsynchronized,
    // a lost race leaves a token assigned over the top of a live one, which
    // leaks an observer that nothing will ever remove.
    private var interruptionObserver: NSObjectProtocol? = null
    private var routeChangeObserver: NSObjectProtocol? = null
    private var foregroundObserver: NSObjectProtocol? = null

    // Serial queue owning every host and session lifecycle call, so none of
    // them run on the main thread. Notification handlers and the watchdog
    // both land here, which also serializes them against each other for free.
    private val hostQueue = dispatch_queue_create("org.balch.orpheus.audiohost", null)

    // One StableRef for the whole life of this object, deliberately never
    // disposed. It is the `ctx` handed to the C config-change callback, and
    // orpheus_ios_audio.h is explicit that ctx must outlive
    // orpheus_ios_audio_destroy: an invocation already past its `cb` load can
    // still read a live ctx and call through it after destroy returns. A ref
    // created per host and disposed alongside it would be a use-after-free on
    // exactly the path that creates hosts most often, the repair path. This
    // object is an AppScope singleton, so one ref leaks nothing meaningful.
    private val selfRef = StableRef.create(this)

    // The next four are confined to hostQueue and therefore hold no lock.
    // Every read and every write happens inside a block dispatched there.

    // Last sampled value of the C++ render counter. Compared for CHANGE, not
    // for increase: the counter restarts at 0 when the engine is recreated.
    private var lastBlocksRendered: ULong = 0uL

    // Consecutive samples with no change. See STALE_TICKS_BEFORE_REPAIR.
    private var staleTicks = 0

    // How many stall-driven repairs have been attempted without the render
    // counter moving since. This is the escalation tier for repairHost(), and
    // it has to live out here rather than inside a single call because every
    // signal available *within* a call is the one that lies. Cleared only by
    // shouldRepair() observing the counter move: rendering again is the sole
    // proof a repair worked.
    private var staleRepairs = 0

    // True while a self-rescheduling tick is in flight. Guards against a
    // second chain: every arming path is idempotent, but two chains would
    // double the poll rate and race each other's stale counter.
    private var watchdogChainActive = false

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
        // Intent first, before anything that can fail. Everything below is
        // best effort; this flag is what guarantees the watchdog keeps
        // trying no matter which line throws.
        shouldBeRunning = true
        // Register before configuring. None of the three observers needs an
        // engine, and a route change or interruption arriving mid-init used to
        // land on an unregistered notification centre and vanish. Genuinely
        // idempotent — a repeat returns without touching the live observers —
        // so reaching here on the already-running path below costs nothing.
        registerNotifications()
        if (_isRunning) {
            // Already up as far as we can tell. Still kick: the watchdog
            // chain terminates on stop(), so an early return here could
            // otherwise leave intent true with nothing watching it.
            kickWatchdog(repairNow = false, reason = "start() on a live host")
            return
        }
        log.info { "start() called" }
        try {
            engineLock.lock()
            try {
                // Session configuration belongs inside the lock, not ahead of
                // it. Its own setCategory posts a route change (reason
                // CategoryChange), which hops main queue to hostQueue to
                // handleRouteChange to repairHost(); start() runs on a
                // Dispatchers.Default worker, so the main queue is free to
                // deliver that while we are still building. A repair winning
                // the race would find audioHost and engine both null, fall to
                // step 3, build the engine itself and fire
                // engineRecreatedCallback, buying us a second concurrent
                // loadGraphAndSync() and a spurious flow emit. Holding the
                // lock across construction makes "the engine is being built"
                // a state repairHost() waits for instead of races, which is
                // what repairHost() already assumes. It also removes the
                // concurrent unsynchronized writes to _sampleRate and
                // dspSampleRate.
                configureAudioSession()
                if (engine == null) {
                    engine = orpheus_engine_create(_sampleRate.toFloat())
                    log.info { "C++ engine created at sampleRate=$_sampleRate" }
                }
                if (audioHost == null) {
                    audioHost = orpheus_ios_audio_create(engine, _sampleRate.toDouble())
                    installConfigChangeCallbackLocked()
                }
                startHostLocked()
            } finally {
                engineLock.unlock()
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to start audio engine" }
            // No retry is scheduled here on purpose. shouldBeRunning is
            // already true, so the watchdog owns recovery from this point,
            // including the case where the engine was never created at all.
        }
        kickWatchdog(repairNow = false, reason = "start()")
    }

    override fun stop() {
        log.info { "stop() called" }
        // Clear intent first. A tick already queued on hostQueue reads this
        // before it touches anything and returns without rescheduling, so
        // the chain unwinds itself instead of needing to be cancelled.
        shouldBeRunning = false
        _isRunning = false
        // The escalation ladder counts attempts against THIS host, and both
        // the host and the engine are about to be destroyed. Not a
        // correctness fix: a healthy restart zeroes this on its first tick,
        // long before a stall could read it. It is for the device log, which
        // is the only way this feature can be verified. A "Repair escalation
        // tier 2" line two seconds after a fresh start would badly mislead
        // whoever is powering a speaker off and watching the console.
        // Dispatched rather than assigned: staleRepairs is hostQueue
        // confined, and stop() runs on the caller's thread.
        dispatch_async(hostQueue) { staleRepairs = 0 }
        unregisterNotifications()
        engineLock.lock()
        try {
            // Destroy the host first: it stops AVAudioEngine, which
            // synchronizes with the render thread. Only after that is it
            // safe to destroy the C++ engine the render block was reading.
            //
            // selfRef is deliberately not disposed here. See its declaration.
            audioHost?.let { orpheus_ios_audio_destroy(it) }
            audioHost = null
            engine?.let { orpheus_engine_destroy(it) }
            engine = null
        } finally {
            engineLock.unlock()
        }
        deactivateAudioSession()
    }

    /**
     * Sample the host now instead of waiting for the next scheduled tick,
     * and repair it if it has died. Reached from PlaybackController.play()
     * by way of DspSynthEngine, which calls it synchronously on the main
     * thread, so this hands off and returns immediately. Doing session or
     * engine work inline would block the user's resume tap.
     *
     * Idempotent: on a healthy host the repair's first step is a
     * startAndReturnError: on an already-running engine, which is a no-op.
     *
     * `userInitiated` because this is the one caller that is a person
     * pressing play. It walks past both stand-down gates: the
     * [suspendWatchdogUntil] deadline and [otherAudioOwnsTheRoute]. The
     * category is AVAudioSessionCategoryPlayback, which is not mixable, so
     * "another app is playing" is exactly the case iOS expects an explicit
     * play to win — deferring to it left our own state Playing, the sink
     * unmuted and the session active with nothing coming out, which is the
     * dead-audio trap this whole watchdog exists to close.
     */
    override fun ensureRunning() {
        kickWatchdog(repairNow = true, reason = "ensureRunning()", userInitiated = true)
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
        // tearing down; the host start then fails and repairHost() re-activates
        // on its next pass, so log-and-continue is correct here.
        if (!session.setCategory(AVAudioSessionCategoryPlayback, error = null)) {
            log.warn { "setCategory(playback) failed" }
        }
        session.setPreferredSampleRate(sampleRate = TARGET_SAMPLE_RATE, error = null)
        session.setPreferredIOBufferDuration(duration = TARGET_BUFFER_DURATION, error = null)
        if (!session.setActive(true, error = null)) {
            log.warn { "setActive(true) failed — host start will fail and the watchdog will repair" }
        }

        // Same hazard repairHost() guards against, on the path
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

    /**
     * Register the three notification observers, once. Idempotent, and cheap
     * on the repeat: a second call returns without touching anything.
     *
     * It used to unregister first and re-add unconditionally. That is a worse
     * kind of idempotent — every repeat tore down three live observers and
     * rebuilt them, and the gap in between is a window where a route change or
     * interruption lands on a notification centre that is not listening and is
     * simply lost. start() reaches here on every entry, including the
     * already-running early return, so that window would be routine rather
     * than exotic.
     */
    private fun registerNotifications() {
        engineLock.lock()
        try {
            // Registered already. See the field declarations for why testing
            // one of the three is sufficient.
            if (interruptionObserver != null) return
            registerNotificationsLocked()
        } finally {
            engineLock.unlock()
        }
    }

    /** Must hold [engineLock]; requires all three observers to be null. */
    private fun registerNotificationsLocked() {
        val center = NSNotificationCenter.defaultCenter

        // Delivery stays on the main queue, but no handler body runs there.
        // Both of these make AVAudioSession and AVAudioEngine lifecycle calls,
        // which must not block the main thread, so each body does exactly one
        // thing: hop to hostQueue. That also serializes them against the
        // watchdog rather than letting two repairs interleave.
        interruptionObserver = center.addObserverForName(
            name = AVAudioSessionInterruptionNotification,
            `object` = AVAudioSession.sharedInstance(),
            queue = NSOperationQueue.mainQueue
        ) { notification -> dispatch_async(hostQueue) { handleInterruption(notification) } }

        routeChangeObserver = center.addObserverForName(
            name = AVAudioSessionRouteChangeNotification,
            `object` = AVAudioSession.sharedInstance(),
            queue = NSOperationQueue.mainQueue
        ) { notification -> dispatch_async(hostQueue) { handleRouteChange(notification) } }

        // The app can sit suspended for hours with hostQueue frozen alongside
        // it, so the pending tick lands whenever the system feels like
        // thawing us. Sample as soon as we are demonstrably back instead.
        // `object` is null rather than UIApplication.sharedApplication:
        // registration can run off the main thread, and reading
        // sharedApplication from there is not allowed.
        foregroundObserver = center.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { kickWatchdog(repairNow = true, reason = "app became active") }
    }

    /**
     * Remove all three observers. Idempotent, and safe to call having never
     * registered.
     *
     * Holding [engineLock] across `removeObserver:` is safe: notification
     * delivery does not take this lock. Every handler body is a bare
     * `dispatch_async(hostQueue)` — the foreground one by way of
     * [kickWatchdog], which is nothing but that dispatch — so a delivery
     * racing this call hands its work off and returns rather than blocking on
     * anything we hold.
     */
    private fun unregisterNotifications() {
        engineLock.lock()
        try {
            val center = NSNotificationCenter.defaultCenter
            interruptionObserver?.let { observer: NSObjectProtocol ->
                center.removeObserver(observer)
            }
            routeChangeObserver?.let { observer: NSObjectProtocol ->
                center.removeObserver(observer)
            }
            foregroundObserver?.let { observer: NSObjectProtocol ->
                center.removeObserver(observer)
            }
            interruptionObserver = null
            routeChangeObserver = null
            foregroundObserver = null
        } finally {
            engineLock.unlock()
        }
    }

    /** Runs on [hostQueue]. */
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
                // A vanishing route (a wireless speaker powered off) can
                // arrive as an interruption with reason routeDisconnected,
                // and Apple documents that NO matching Ended ever follows it.
                // Parking the host and waiting would recreate the dead-audio
                // trap through a side door; hand it to the watchdog instead.
                //
                // The reason *key* is iOS 14.5+, but the routeDisconnected
                // *value* is iOS 17.0+ (AVAudioSessionTypes.h:649). Below 17
                // this simply reads null and the watchdog is the sole
                // recovery, which is exactly the design: the notification is
                // a latency win, never the safety net.
                val reasonValue = (userInfo[AVAudioSessionInterruptionReasonKey] as? NSNumber)
                    ?.unsignedLongValue
                val routeDied = reasonValue == AVAudioSessionInterruptionReasonRouteDisconnected
                log.info { "Audio session interruption began (routeDisconnected=$routeDied)" }
                // Stand down while another session owns audio. Repairing
                // through a phone call would be us fighting the system for
                // the route, and losing loudly.
                suspendWatchdogUntil = CACurrentMediaTime() + INTERRUPTION_SUSPEND_SECONDS
                engineLock.lock()
                try {
                    audioHost?.let { orpheus_ios_audio_stop(it) }
                    _isRunning = false
                } finally {
                    engineLock.unlock()
                }
                if (routeDied) {
                    // No Ended is coming, so there is nothing to stand down
                    // for. Lift the suspension immediately and let the
                    // watchdog own it from the next tick.
                    suspendWatchdogUntil = 0.0
                    // Baseline against the counter we just froze, so the
                    // stale run starts here instead of spending its first
                    // tick rediscovering progress made before the stop.
                    resetWatchdogBaseline()
                    // Outside the lock, like handleRouteChange: pause
                    // etiquette must also apply when the route loss surfaces
                    // as an interruption instead of (or before) a route change.
                    routeLostCallback?.invoke()
                }
            }
            AVAudioSessionInterruptionTypeEnded -> {
                log.info { "Audio session interruption ended" }
                suspendWatchdogUntil = 0.0
                val reactivated = AVAudioSession.sharedInstance().setActive(true, error = null)
                if (!reactivated) log.warn { "Audio session reactivation failed after interruption" }
                // Repair now rather than waiting a tick. Failure here is not
                // terminal any more, so there is no budget to preserve and
                // no reason to be careful about spending an attempt.
                kickWatchdogOnHostQueue(repairNow = true, reason = "interruption ended")
            }
        }
    }

    /** Runs on [hostQueue]. */
    private fun handleRouteChange(notification: NSNotification?) {
        val reason = (notification?.userInfo?.get(AVAudioSessionRouteChangeReasonKey) as? NSNumber)
            ?.unsignedLongValue
        val deviceLost = reason == AVAudioSessionRouteChangeReasonOldDeviceUnavailable
        // Where we landed, not just what we lost. "Audio is dead after a
        // disconnect" and "audio moved to the built-in speaker and the user
        // cannot hear it" look identical from a deviceLost flag alone. The
        // rate is here for the same reason: it is what confirms or refutes the
        // deliberate absence of a rate-change branch in repairHost() on a
        // device whose wireless output runs 44.1k and whose speaker runs 48k.
        log.info {
            val session = AVAudioSession.sharedInstance()
            val output = session.currentRoute.outputs.firstOrNull() as? AVAudioSessionPortDescription
            "Audio route changed (reason=$reason, deviceLost=$deviceLost, " +
                "output=${output?.portType}, sessionRate=${session.sampleRate})"
        }
        // Positive evidence that the output moved, so repair now instead of
        // waiting for the watchdog to notice the silence. This handler is no
        // longer the recovery *owner* though: the whole bug was that when the
        // vanished device is the last route event, no further route change
        // ever arrives to drive a handler like this one.
        kickWatchdogOnHostQueue(repairNow = true, reason = "route change")
        if (deviceLost) {
            // The active output device vanished (a wireless speaker powered
            // off). PlaybackController auto-pauses via this, following
            // Apple's convention of not continuing through the built-in
            // speaker unprompted.
            log.info { "Audio route device became unavailable — notifying route-lost callback" }
            routeLostCallback?.invoke()
        }
    }

    // ── Watchdog ──────────────────────────────────────

    /**
     * True when another session is the one making noise right now. A caller
     * that gets `true` has already been put back to sleep: this re-arms
     * [suspendWatchdogUntil] and re-baselines as a side effect.
     *
     * This is the piece that lets the loop stay closed without fighting the
     * system for the route, and it is why the 30s cap is safe to keep.
     * [INTERRUPTION_SUSPEND_SECONDS] is only a cap, and nothing re-armed it.
     * Once it expired mid-interruption the watchdog would find the render
     * counter frozen (frozen because *we* stopped the host on Began), call
     * that a stall, and repair. Step 2 of a repair is setActive(true) on a
     * Playback session, which is not mixable, so the repair would take the
     * route: half a minute after the user switched to a podcast, this app
     * would start playing audibly with nobody having asked it to. Against a
     * phone call it fails the other way, quietly and forever: CallKit refuses
     * the reactivation, and we churn a session activation every couple of
     * seconds and an AVAudioEngine teardown/rebuild every third one, for the
     * length of the call.
     *
     * No value of the constant fixes that. Every finite one expires while the
     * condition it guards is still true, and an infinite one reopens the
     * missing-Ended hole the cap exists to close. So keep the cap and add the
     * condition. Constraint 2 still holds: we never stop polling, we only
     * decline to repair, and the first tick after the other audio ends
     * recovers normally whether or not an Ended notification ever arrives.
     *
     * Every *reactive* repair path consults this, not just the tick. A route
     * change or the config-change callback landing between ticks reaches
     * [repairHost] through [kickWatchdogOnHostQueue], and a tick-only guard
     * would let both walk past it and steal the route anyway.
     *
     * The one exception is an explicit play tap, which arrives
     * `userInitiated` and is not asked. See [ensureRunning]: on a
     * non-mixable Playback category, taking the route from another app is
     * the correct answer to a person pressing play, and it is the only
     * caller that carries that intent. Note the side effects below, which
     * are why the exception has to be "don't call this" rather than "ignore
     * what it returns": a suppressed play tap used to re-arm the very
     * stand-down that suppressed it, so a user tapping play while a podcast
     * ran got silence for as long as the podcast lasted.
     *
     * Must run on [hostQueue]: it writes watchdog state.
     */
    private fun otherAudioOwnsTheRoute(): Boolean {
        if (!AVAudioSession.sharedInstance().otherAudioPlaying) return false
        suspendWatchdogUntil = CACurrentMediaTime() + INTERRUPTION_SUSPEND_SECONDS
        // Same reasoning as the suspension branch in watchdogTick: the counter
        // is parked, not broken, so start the next stale run from here rather
        // than letting the first tick after the stand-down read as an instant
        // false positive.
        resetWatchdogBaseline()
        return true
    }

    /**
     * Hand a watchdog pass to [hostQueue] and return immediately.
     *
     * Every caller is either on the main thread (the resume tap, the
     * foreground notification) or on AVAudioEngine's own internal dispatch
     * queue (the config-change callback). None of them may block, so the
     * dispatch is the entire body.
     */
    private fun kickWatchdog(repairNow: Boolean, reason: String, userInitiated: Boolean = false) {
        dispatch_async(hostQueue) { kickWatchdogOnHostQueue(repairNow, reason, userInitiated) }
    }

    /**
     * Re-baseline the watchdog, optionally repair right now, and make sure
     * the tick chain is running. Must run on [hostQueue].
     *
     * [userInitiated] marks a pass that carries explicit user intent (a play
     * tap, via [ensureRunning]) rather than a reaction to something the
     * system did. Only that pass may walk past the stand-down gates.
     */
    private fun kickWatchdogOnHostQueue(
        repairNow: Boolean,
        reason: String,
        userInitiated: Boolean = false,
    ) {
        if (!shouldBeRunning) return
        // A play tap is not held by the deadline. Nothing clears
        // suspendWatchdogUntil here on purpose: bypassing it buys this one
        // pass an honest attempt, and leaving it set means that if we are
        // genuinely mid-call — where setActive(true) is refused by CallKit
        // and there is nothing to win — the tick chain stays parked instead
        // of churning a rebuild every couple of seconds for the whole call.
        if (repairNow && (userInitiated || CACurrentMediaTime() >= suspendWatchdogUntil)) {
            // Asked second, so we only pay for the session read on a pass that
            // was actually about to repair. Every reactive kick source lands
            // here, which is the point: the deadline above is the only other
            // thing holding those callers back, and it is exactly what expires
            // too early.
            if (!userInitiated && otherAudioOwnsTheRoute()) {
                log.info { "Audio host repair skipped ($reason): another session owns audio" }
            } else {
                log.info { "Audio host repair requested: $reason (userInitiated=$userInitiated)" }
                repairHost()
                // A user-initiated repair that took means we now own the
                // route, so there is nothing left to stand down for. Lift any
                // remaining suspension instead of leaving the tick chain
                // parked for up to INTERRUPTION_SUSPEND_SECONDS over audio it
                // is no longer watching.
                //
                // Gated on _isRunning, which is allowed to lie here precisely
                // because the lie is safe in this direction: a false YES only
                // resumes polling, and the counter catches the stall two ticks
                // later. A false NO leaves us parked, which is the correct
                // answer to the case that actually produces it — mid-call,
                // where CallKit refuses the reactivation and there is nothing
                // to win by trying again every second.
                if (userInitiated && _isRunning) suspendWatchdogUntil = 0.0
            }
        }
        // Fresh sample either way. Whatever kicked us just changed the world,
        // so carrying the old sample forward would either read stale and
        // repair a host that was only briefly parked, or read changed and
        // hide a real stall for a tick.
        //
        // This zeroes staleTicks too, so a kick source firing more often than
        // every other tick pins the stale run at 0 and the escalation ladder
        // never leaves tier 0. Left that way on purpose. It is not a dead
        // state: each of those kicks performs a real tier 0 repair, which is
        // the documented-sufficient action after a self-stop, and the bursts
        // that cause it (a route-change storm, an engine posting config changes
        // through its own teardown) settle in a second or two. Clamping the
        // re-baseline would put new conditional state in front of the one
        // mechanism Constraint 2 rests on, to defend against a burst that has
        // to last forever to matter.
        resetWatchdogBaseline()
        if (!watchdogChainActive) {
            watchdogChainActive = true
            scheduleWatchdogTick()
        }
    }

    private fun scheduleWatchdogTick() {
        dispatch_after(
            dispatch_time(DISPATCH_TIME_NOW, WATCHDOG_TICK_MS * 1_000_000L),
            hostQueue,
        ) { watchdogTick() }
    }

    /**
     * One reconciliation pass: compare intent against observed render
     * progress and repair when they disagree. Runs on [hostQueue] and
     * always reschedules itself while [shouldBeRunning] holds, which is the
     * property that makes recovery closed-loop. There is no give-up branch
     * and no attempt budget, on purpose. Both existed before and both are
     * what left audio dead until relaunch.
     */
    private fun watchdogTick() {
        if (!shouldBeRunning) {
            // stop() owns teardown. Do not reschedule; start() re-arms.
            // This is the one deliberate way out that leaves the chain dead,
            // so it sits above the try below rather than inside it.
            watchdogChainActive = false
            return
        }
        // Everything past here reschedules, however it ends. The reschedule
        // is structural rather than a trailing call because the chain is the
        // only thing standing between shouldBeRunning and permanent silence:
        // a throw below would leave watchdogChainActive true with no tick in
        // flight, and start()'s kick would then see the flag set and decline
        // to re-arm. Constraint 2 violated through a new door. Nothing on
        // this path throws today. The finally is a necessary evil that keeps
        // it that way, so do not fold it back into a trailing call.
        try {
            if (CACurrentMediaTime() < suspendWatchdogUntil) {
                // Interrupted. The counter is legitimately frozen, so take a
                // fresh baseline and let the first tick after the suspension
                // be a real sample rather than an instant false positive.
                resetWatchdogBaseline()
                return
            }
            var blocks: ULong = 0uL
            var hostReportsRunning = 0
            engineLock.lock()
            try {
                blocks = engine?.let { orpheus_engine_blocks_rendered(it) } ?: 0uL
                hostReportsRunning = orpheus_ios_audio_is_running(audioHost)
            } finally {
                engineLock.unlock()
            }
            if (shouldRepair(blocks)) {
                if (otherAudioOwnsTheRoute()) {
                    // Frozen for a reason we already know about. Stand down
                    // again rather than reading somebody else's audio as our
                    // stall. The finally below still reschedules.
                    log.info {
                        "Watchdog: render counter stuck at $blocks, but another session owns " +
                            "audio — standing down instead of repairing"
                    }
                    return
                }
                // avEngine.isRunning is logged, never tested. It is the exact
                // signal that lies in this failure mode (running, producing
                // nothing), so its only job here is to tell the owner reading
                // a device log which of the two shapes they hit.
                log.info {
                    "Watchdog: render counter stuck at $blocks for $staleTicks ticks " +
                        "(avEngine.isRunning=$hostReportsRunning) — repairing audio host"
                }
                // The only caller that has watched the counter fail to move,
                // so the only one allowed to escalate.
                repairHost(stalled = true)
                // Re-read rather than reusing `blocks`: a repair that
                // recreated the engine restarted the counter at 0, and
                // carrying the old value forward would read as a change and
                // buy the dead host a free tick.
                resetWatchdogBaseline()
            }
        } finally {
            scheduleWatchdogTick()
        }
    }

    /**
     * Decide whether the audio host looks dead. [blocks] is the current
     * value of the C++ render counter, which advances once per rendered
     * block and RESETS TO 0 when the engine is recreated, so this compares
     * for CHANGE, not for increase.
     *
     * Tradeoff: fewer stale ticks means faster recovery but more false
     * positives at large buffer sizes or right after an app resume, where
     * the render thread was legitimately parked. More stale ticks means
     * longer audible silence. See [STALE_TICKS_BEFORE_REPAIR] for why the
     * default leans toward repairing.
     *
     * Must run on [hostQueue]: [lastBlocksRendered] and [staleTicks] are
     * confined there.
     */
    private fun shouldRepair(blocks: ULong): Boolean {
        if (blocks != lastBlocksRendered) {
            lastBlocksRendered = blocks
            staleTicks = 0
            // Rendering, so whatever the last repair did, it took. This is
            // the only place the escalation tier resets, and deliberately so:
            // a repair's own return code cannot be trusted to say it worked.
            //
            // Say so out loud, once. A device trace is the only verification
            // this feature will ever get, and a log that says "repairing" over
            // and over and never says whether audio came back cannot answer
            // the one question being asked of it. Guarded on non-zero so this
            // is one line per recovery, not one per tick.
            if (staleRepairs > 0) {
                log.info { "Audio host recovered after $staleRepairs repair(s), blocks=$blocks" }
            }
            staleRepairs = 0
            return false
        }
        staleTicks++
        return staleTicks >= STALE_TICKS_BEFORE_REPAIR
    }

    /** Must run on [hostQueue]. */
    private fun resetWatchdogBaseline() {
        engineLock.lock()
        try {
            lastBlocksRendered = engine?.let { orpheus_engine_blocks_rendered(it) } ?: 0uL
        } finally {
            engineLock.unlock()
        }
        staleTicks = 0
    }

    /**
     * Escalating repair. Must run on [hostQueue].
     *
     * ```
     * 1. start the host again
     * 2. on failure: reactivate the session, start again
     * 3. on failure: destroy and recreate the host, start again
     * ```
     *
     * [stalled] says the caller has *observed* that no blocks are being
     * rendered. It does NOT decide how far the ladder runs. Read it as: is a
     * success rc trustworthy on this pass?
     *
     * - `stalled = false` (route change, play tap, config change): the rc is
     *   taken at face value. Step 1 alone on success; steps 2 and 3 still run
     *   on failure, because a start that reports failure has failed.
     * - `stalled = true`: the counter has been watched not moving, so rc == 0
     *   is known to be a lie, and [staleRepairs] forces the later steps past
     *   it.
     *
     * The consequence to keep in mind is on the non-stalled path: a route
     * change arriving mid-teardown, where `setActive(true)` and
     * `startAndReturnError:` both fail transiently, reaches the step 3 host
     * rebuild on its first pass rather than waiting the transient out. One
     * device disconnect posts several route changes, so that is several
     * AVAudioEngine teardown/rebuild cycles under [engineLock] while the HAL
     * settles. It converges — the loop has no give-up branch — but if device
     * traces show rebuild storms on disconnect, gating step 3 behind a
     * second consecutive failure is the knob, not the tier.
     *
     * Escalation state lives in [staleRepairs] rather than in this call,
     * because within a call there is nothing honest to escalate on. In the
     * failure mode this watchdog exists for, `avEngine.isRunning` is YES
     * while the render block is never pulled, so `orpheus_ios_audio_start` is
     * a documented no-op returning 0. Choosing the tier from that rc would
     * read "success", latch [_isRunning] true, and make steps 2 and 3
     * unreachable. Two ticks later the counter is still frozen and we would
     * do it all again, forever. The irony is worth stating plainly: the
     * health gate exists precisely because `isRunning` lies, so escalation
     * must not be keyed on anything derived from it.
     *
     * The tier cycles (`staleRepairs % 3`) instead of pinning at rebuild.
     * Cycling keeps Constraint 2 without destroying and recreating an
     * AVAudioEngine every two seconds forever in a terminally broken state.
     *
     * Step 1 alone covers the common case. AVAudioEngine keeps its nodes
     * attached and connected across a self-stop, and the mainMixer to output
     * connection re-tracks whatever hardware is present on every restart,
     * which holds only because neither this file nor the ObjC++ side ever
     * sets that connection's format. Restarting is therefore enough to
     * follow the route onto the built-in speaker.
     *
     * There is deliberately no rate-change branch. The source to mixer
     * format stays pinned at the last known good rate and mainMixerNode
     * converts. The branch that used to live here tore down the C++ engine
     * and re-entered stop()/start() from a recovery path, which deactivated
     * the audio session and reloaded the whole wiring graph to accomplish
     * what the mixer does for free.
     *
     * The whole body holds [engineLock], so a control write racing a repair
     * waits out a startAndReturnError:. That is the price of the pointers
     * staying valid across the rebuild in step 3, and it is strictly better
     * than the path this replaces, which did the same work on the main queue.
     */
    private fun repairHost(stalled: Boolean = false) {
        var recreated = false
        // Read the tier before the work, advance it immediately. A repair
        // that appears to succeed but does not actually resume rendering
        // leaves this advanced, so the next stall-driven pass tries harder.
        val tier = if (stalled) staleRepairs % 3 else 0
        if (stalled) staleRepairs++
        engineLock.lock()
        try {
            if (!shouldBeRunning) return
            val session = AVAudioSession.sharedInstance()

            if (tier == 1) {
                // Stop first, which turns step 1 below from a no-op on an
                // engine that claims to be running into a real restart. This
                // is the whole point of the tier: it is the smallest thing
                // that can break the "start returns 0, nothing renders" loop.
                //
                // Safe on hostQueue under engineLock: [avEngine stop]
                // synchronizes with the render thread, and the render thread
                // takes no locks (orpheus_ios_audio.mm is atomics and pointer
                // math only). handleInterruption already does exactly this.
                log.info { "Repair escalation tier 1: stopping the host before restarting it" }
                audioHost?.let { orpheus_ios_audio_stop(it) }
                _isRunning = false
            }

            // A null host fails this with -1 and falls through to step 3,
            // which is the wanted behaviour: it is the first-launch case
            // where start() threw before it built anything.
            var rc = orpheus_ios_audio_start(audioHost)

            // `tier == 2` forces the reactivation too. Without it this whole
            // step is rc-gated, and the defining tier 2 case is rc == 0, the
            // start lying. A session that quietly went inactive would then be
            // rebuilt around rather than reactivated, and only tier 1 of the
            // NEXT cycle would surface it, about two seconds later.
            if (rc != 0 || tier == 2) {
                // A device teardown can deactivate the session out from
                // under us. Reactivating is idempotent when it is already
                // active, so this is cheap to try before anything drastic.
                //
                // Two ways in, so two lines. The defining tier 2 case is
                // rc == 0, and reporting that as "start failed (0)" would be a
                // lie in the one trace this feature can ever be verified from.
                if (rc != 0) {
                    log.warn { "Audio host start failed ($rc), reactivating session and retrying" }
                } else {
                    log.info { "Repair escalation tier 2: reactivating the session before retrying" }
                }
                if (!session.setActive(true, error = null)) {
                    log.warn { "setActive(true) refused during repair" }
                }
                rc = orpheus_ios_audio_start(audioHost)
            }

            // `tier == 2` forces this even when the start above returned 0,
            // because at tier 2 that 0 has already been proven a lie twice.
            if (rc != 0 || tier == 2) {
                if (rc == 0) {
                    log.info {
                        "Repair escalation tier 2: host start reported success but nothing " +
                            "is rendering, rebuilding the host"
                    }
                } else {
                    log.warn { "Audio host start failed again ($rc), rebuilding the host" }
                }
                audioHost?.let { orpheus_ios_audio_destroy(it) }
                audioHost = null
                // Note the guard. On the stall path `engine` is non-null, so
                // the C++ engine survives and `recreated` stays false. That is
                // required, not incidental. Recreating the engine without
                // firing engineRecreatedCallback would hand the new host an
                // engine with no wiring graph, which renders silence forever.
                // The engine is null only when start() threw before building
                // one, and there recreating it and firing the callback is
                // exactly right.
                if (engine == null) {
                    // start() never got as far as an engine. Re-run session
                    // configuration too, then hand DspSynthEngine a fresh
                    // engine to load the wiring graph into — a brand-new
                    // engine has no graph and renders silence without it.
                    configureAudioSession()
                    engine = orpheus_engine_create(_sampleRate.toFloat())
                    log.info { "C++ engine recreated at sampleRate=$_sampleRate" }
                    recreated = true
                }
                audioHost = orpheus_ios_audio_create(engine, _sampleRate.toDouble())
                installConfigChangeCallbackLocked()
                rc = orpheus_ios_audio_start(audioHost)
            }

            _isRunning = rc == 0
            if (rc != 0) {
                // Not terminal. shouldBeRunning is still true, so the next
                // tick tries again, and the one after that, forever.
                log.error { "Audio host repair did not take (rc=$rc), retrying next tick" }
            }
        } finally {
            engineLock.unlock()
        }
        // Fire outside the lock: the consumer launches coroutines that call
        // back into withEngine, and they should never contend with a repair.
        if (recreated) engineRecreatedCallback?.invoke()
    }

    /**
     * Start the current [audioHost]. Must hold [engineLock]. A failure here
     * is recorded and left alone: the watchdog is the only thing that
     * retries now, which is what removes the shared-counter races between
     * concurrent retry chains that the old budget had.
     */
    private fun startHostLocked() {
        val rc = orpheus_ios_audio_start(audioHost)
        _isRunning = rc == 0
        if (rc != 0) log.error { "orpheus_ios_audio_start failed: $rc" }
    }

    /**
     * Point the host's config-change callback at this instance. Must hold
     * [engineLock], and must be called after every
     * `orpheus_ios_audio_create` since the callback lives on the host.
     */
    private fun installConfigChangeCallbackLocked() {
        orpheus_ios_audio_set_config_change_callback(
            audioHost,
            configChangeTrampoline,
            selfRef.asCPointer(),
        )
    }

    /**
     * AVAudioEngine reported a configuration change, which per its header
     * means it has already stopped itself. This is the only in-band notice
     * that audio died, and it is a latency optimisation rather than the
     * safety net: a change arriving between `orpheus_ios_audio_create` and
     * [installConfigChangeCallbackLocked] is silently dropped, so the
     * watchdog has to be able to recover without ever hearing from here.
     */
    private fun onHostConfigChanged() {
        kickWatchdog(repairNow = true, reason = "AVAudioEngine configuration change")
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

        /**
         * The C entry point for `orpheus_ios_audio_set_config_change_callback`.
         *
         * Lives here because [staticCFunction] forbids capturing anything,
         * including an enclosing instance, so the only route back to the
         * engine is round-tripping the `ctx` pointer we handed the host.
         *
         * This runs synchronously on AVAudioEngine's internal dispatch queue
         * (the ObjC++ observer registers with `queue:nil`). It may do one
         * thing and one thing only: hand the signal on. Blocking here blocks
         * that queue, and any engine lifecycle call from here deadlocks
         * against the engine's own synchronous teardown.
         *
         * `ctx` is documented nullable: destroy retracts it to null before
         * tearing the host down, so tolerate that rather than assume a live
         * context.
         */
        private val configChangeTrampoline = staticCFunction<COpaquePointer?, Unit> { ctx ->
            ctx?.asStableRef<IosAudioEngine>()?.get()?.onHostConfigChanged()
        }
    }
}
