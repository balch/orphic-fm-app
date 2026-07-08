package org.balch.orpheus.core.audio.dsp

import cnames.structs.OrpheusEngine
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.arrayMemberAt
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
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
import orpheus_dsp.orpheus_engine_process_deinterleaved
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
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.AVAudioSourceNode
import platform.AVFAudio.sampleRate
import platform.AVFAudio.setActive
import platform.AVFAudio.setPreferredIOBufferDuration
import platform.AVFAudio.setPreferredSampleRate
import platform.CoreAudioTypes.AudioBuffer
import platform.CoreAudioTypes.AudioBufferList
import platform.Foundation.NSDate
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObjectProtocol
import kotlin.concurrent.Volatile

private const val TARGET_SAMPLE_RATE = 48000.0
private const val TARGET_BUFFER_DURATION = 256.0 / TARGET_SAMPLE_RATE // ~5.3ms

/**
 * iOS AudioEngine backed by AVAudioEngine + liborpheus_dsp via cinterop.
 * Audio rendering happens entirely in C++ — no Kotlin in the audio callback.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<AudioEngine>())
@Inject
class IosAudioEngine : AudioEngine, NativeDspBridge {

    @Volatile
    private var engine: CPointer<OrpheusEngine>? = null
    private var avAudioEngine: AVAudioEngine? = null
    private var sourceNode: AVAudioSourceNode? = null
    private var _isRunning = false
    private var _sampleRate: Int = TARGET_SAMPLE_RATE.toInt()
    private var interruptionObserver: NSObjectProtocol? = null
    private var routeChangeObserver: NSObjectProtocol? = null

    init {
        log.info { "IosAudioEngine created (C++ DSP via cinterop)" }
    }

    override fun start() {
        if (_isRunning) return
        log.info { "start() called" }

        try {
            configureAudioSession()
            createEngine()
            setupAudioGraph()
            registerNotifications()
            startAudioEngine()
        } catch (e: Exception) {
            log.error(e) { "Failed to start audio engine" }
        }
    }

    override fun stop() {
        log.info { "stop() called" }
        _isRunning = false
        unregisterNotifications()

        // Capture engine pointer, then null it so the render callback
        // sees null and returns silence during teardown.
        val eng = engine
        engine = null

        avAudioEngine?.stop()
        avAudioEngine = null
        sourceNode = null

        eng?.let { orpheus_engine_destroy(it) }

        deactivateAudioSession()
    }

    override val isRunning: Boolean get() = _isRunning

    override val sampleRate: Int get() = _sampleRate

    override fun getCpuLoad(): Float {
        // AVAudioEngine doesn't expose CPU load directly; use monitor data
        val out = FloatArray(20)
        nativeGetMonitor(out)
        return out[2] * 100f // cpu_load field, scaled to percentage
    }

    override fun getCurrentTime(): Double {
        // Use CACurrentMediaTime equivalent via NSDate
        return NSDate().timeIntervalSince1970
    }

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

    private fun createEngine() {
        engine = orpheus_engine_create(_sampleRate.toFloat())
        log.info { "C++ engine created at sampleRate=$_sampleRate" }
    }

    private fun setupAudioGraph() {
        val audioEngine = AVAudioEngine()
        val mainMixer = audioEngine.mainMixerNode
        val renderFormat = AVAudioFormat(
            standardFormatWithSampleRate = _sampleRate.toDouble(),
            channels = 2u
        )

        // Create source node with render callback.
        // The render block is called from the real-time audio thread.
        // Read this.engine each invocation — never capture a local copy, as the
        // engine pointer changes on stop()/start() cycles.
        //
        // AVAudioEngine uses deinterleaved format (separate L/R AudioBuffers).
        // C++ orpheus_engine_process writes interleaved stereo (L,R,L,R,...).
        // We render into a pinned scratch buffer, then deinterleave into the ABL.
        val srcNode = AVAudioSourceNode(
            renderBlock = { _, _, frameCount, bufferListPtr ->
                val eng = engine ?: return@AVAudioSourceNode 0
                val abl = bufferListPtr!!.pointed
                // Flexible array member offset: AudioBufferList contains one AudioBuffer at end,
                // so mBuffers offset = sizeOf(AudioBufferList) - sizeOf(AudioBuffer).
                // This accounts for alignment padding after mNumberBuffers.
                val mBuffersOffset = sizeOf<AudioBufferList>() - sizeOf<AudioBuffer>()
                val leftPtr = abl.arrayMemberAt<AudioBuffer>(mBuffersOffset)[0]
                    .mData?.reinterpret<FloatVar>()
                val rightPtr = abl.arrayMemberAt<AudioBuffer>(mBuffersOffset + sizeOf<AudioBuffer>())[0]
                    .mData?.reinterpret<FloatVar>()

                if (leftPtr != null && rightPtr != null) {
                    // C++ renders interleaved internally, deinterleaves into L/R
                    orpheus_engine_process_deinterleaved(
                        eng, leftPtr, rightPtr, frameCount.toInt()
                    )
                }

                0 // noErr (OSStatus)
            }
        )

        audioEngine.attachNode(srcNode)
        audioEngine.connect(srcNode, to = mainMixer, format = renderFormat)

        avAudioEngine = audioEngine
        sourceNode = srcNode
    }

    private fun startAudioEngine() {
        avAudioEngine?.let { ae ->
            try {
                ae.startAndReturnError(null)
                _isRunning = true
                log.info { "AVAudioEngine started" }
            } catch (e: Exception) {
                log.error(e) { "Failed to start AVAudioEngine" }
            }
        }
    }

    // ── Notification handling ─────────────────────────

    private fun registerNotifications() {
        val center = NSNotificationCenter.defaultCenter

        interruptionObserver = center.addObserverForName(
            name = AVAudioSessionInterruptionNotification,
            `object` = AVAudioSession.sharedInstance(),
            queue = null
        ) { notification -> handleInterruption(notification) }

        routeChangeObserver = center.addObserverForName(
            name = AVAudioSessionRouteChangeNotification,
            `object` = AVAudioSession.sharedInstance(),
            queue = null
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
                avAudioEngine?.stop()
                _isRunning = false
            }
            AVAudioSessionInterruptionTypeEnded -> {
                log.info { "Audio session interruption ended" }
                startAudioEngine()
            }
        }
    }

    private fun handleRouteChange() {
        log.info { "Audio route changed, fully restarting engine" }
        // Full stop + start to rebuild everything: C++ engine, audio graph,
        // and format — sample rate may have changed on route change.
        stop()
        start()
    }

    // ── NativeDspBridge implementation ────────────────

    override fun nativeSetVoiceGate(index: Int, active: Boolean) {
        engine?.let { orpheus_engine_set_voice_gate(it, index, if (active) 1 else 0) }
    }

    override fun nativeSetVoiceTune(index: Int, tune: Float) {
        engine?.let { orpheus_engine_set_voice_tune(it, index, tune) }
    }

    override fun nativeSetVoiceEngine(index: Int, engineIndex: Int) {
        engine?.let { orpheus_engine_set_voice_engine(it, index, engineIndex) }
    }

    override fun nativeSetVoiceHarmonics(index: Int, value: Float) {
        engine?.let { orpheus_engine_set_voice_harmonics(it, index, value) }
    }

    override fun nativeSetVoiceTimbre(index: Int, value: Float) {
        engine?.let { orpheus_engine_set_voice_timbre(it, index, value) }
    }

    override fun nativeSetVoiceMorph(index: Int, value: Float) {
        engine?.let { orpheus_engine_set_voice_morph(it, index, value) }
    }

    override fun nativeSetVoiceDecay(index: Int, value: Float) {
        engine?.let { orpheus_engine_set_voice_decay(it, index, value) }
    }

    override fun nativeSetVoiceActive(index: Int, active: Boolean) {
        engine?.let { orpheus_engine_set_voice_active(it, index, if (active) 1 else 0) }
    }

    override fun nativeSetVoiceHold(index: Int, level: Float) {
        engine?.let { orpheus_engine_set_voice_hold(it, index, level) }
    }

    override fun nativeSetMasterVolume(value: Float) {
        engine?.let { orpheus_engine_set_master_volume(it, value) }
    }

    override fun nativeMasterFade(target: Float, samples: Int, curve: Int) {
        engine?.let { orpheus_engine_master_fade(it, target, samples, curve) }
    }

    override fun nativeMasterTapeStop(samples: Int) {
        engine?.let { orpheus_engine_master_tape_stop(it, samples) }
    }

    override fun nativeMasterScratch(samples: Int) {
        engine?.let { orpheus_engine_master_scratch(it, samples) }
    }

    override fun nativeMasterFilter(samples: Int) {
        engine?.let { orpheus_engine_master_filter(it, samples) }
    }

    override fun nativeMasterVolumeNow(): Float {
        return engine?.let { orpheus_engine_master_volume_now(it) } ?: 0f
    }

    override fun nativeSetDrive(value: Float) {
        engine?.let { orpheus_engine_set_drive(it, value) }
    }

    override fun nativeSetDelayMix(value: Float) {
        engine?.let { orpheus_engine_set_delay_mix(it, value) }
    }

    override fun nativeSetVibrato(value: Float) {
        engine?.let { orpheus_engine_set_vibrato(it, value) }
    }

    override fun nativeSetVibratoRate(value: Float) {
        engine?.let { orpheus_engine_set_vibrato_rate(it, value) }
    }

    override fun nativeSetBend(value: Float) {
        engine?.let { orpheus_engine_set_bend(it, value) }
    }

    override fun nativeSetPort(uri: String, symbol: String, value: Float) {
        engine?.let { orpheus_engine_set_port(it, uri, symbol, value) }
    }

    override fun nativeGetPort(uri: String, symbol: String): Float {
        return engine?.let { orpheus_engine_get_port(it, uri, symbol) } ?: 0f
    }

    override fun nativeGetMonitor(out: FloatArray) {
        engine?.let { eng ->
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
        return engine?.let { eng ->
            outBuf.usePinned { pinnedBuf ->
                lastReadPos.usePinned { pinnedPos ->
                    orpheus_engine_get_viz(
                        eng,
                        channel,
                        pinnedBuf.addressOf(0),
                        outBuf.size,
                        pinnedPos.addressOf(0)
                    )
                }
            }
        } ?: 0
    }

    override fun nativeGetSpectrum(bands: FloatArray): Int {
        return engine?.let { eng ->
            bands.usePinned { pinned ->
                orpheus_engine_get_spectrum(eng, pinned.addressOf(0), bands.size)
            }
        } ?: 0
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun nativeGetTurntableViz(deck: Int, outBuf: FloatArray) {
        engine?.let { eng ->
            outBuf.usePinned { pinnedBuf ->
                orpheus_engine_get_turntable_viz(eng, deck, pinnedBuf.addressOf(0))
            }
        }
    }

    override fun nativeTriggerDrum(drumIndex: Int, accent: Float) {
        engine?.let { orpheus_engine_trigger_drum(it, drumIndex, accent) }
    }

    override fun nativeLoadGraph(data: ByteArray): Int {
        return engine?.let { eng ->
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
        engine?.let { eng ->
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
        engine?.let { orpheus_engine_clear_automation(it, target, voiceIndex) }
    }

    override fun nativeLoadTtsAudio(samples: FloatArray, sampleRate: Int) {
        engine?.let { eng ->
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
        engine?.let { orpheus_engine_play_tts(it) }
    }

    override fun nativeStopTts() {
        engine?.let { orpheus_engine_stop_tts(it) }
    }

    override fun nativeIsTtsPlaying(): Int {
        return engine?.let { orpheus_engine_is_tts_playing(it) } ?: 0
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun nativeGetPulsarArrangement(out: IntArray) {
        engine?.let { eng ->
            out.usePinned { pinned ->
                orpheus_engine_get_pulsar_arrangement(eng, pinned.addressOf(0))
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun nativeGetPulsarViz(
        gatesOut: BooleanArray, velocitiesOut: FloatArray,
        playheadsOut: IntArray, stepCountsOut: IntArray,
    ) {
        engine?.let { eng ->
            // C API uses int[] for gates (0/1), Kotlin uses BooleanArray — use IntArray as bridge
            val intGates = IntArray(gatesOut.size)
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
