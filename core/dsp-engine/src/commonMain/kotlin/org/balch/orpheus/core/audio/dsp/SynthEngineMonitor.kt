package org.balch.orpheus.core.audio.dsp

import com.diamondedge.logging.logging
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.plugin.viz.PULSAR_MAX_STEPS
import org.balch.orpheus.core.plugin.viz.PULSAR_NUM_TRACKS
import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import org.balch.orpheus.core.plugin.viz.PulsarVizData

/**
 * Owns all monitoring StateFlows and signal visualization polling.
 * Extracted from DspSynthEngine for composition — not DI-injected.
 */
class SynthEngineMonitor(
    private val nativeBridge: NativeDspBridge,
    private val dispatcherProvider: DispatcherProvider,
    private val monitoringScope: AppCoroutineScope,
) {
    // Reactive monitoring flows
    private val _peakFlow = MutableStateFlow(0f)
    val peakFlow: StateFlow<Float> = _peakFlow.asStateFlow()

    private val _cpuLoadFlow = MutableStateFlow(0f)
    val cpuLoadFlow: StateFlow<Float> = _cpuLoadFlow.asStateFlow()

    // Audio underruns (xruns) for the current output stream. Android-only
    // signal (0 elsewhere); resets when the stream reopens on route changes.
    private val _xrunCountFlow = MutableStateFlow(0)
    val xrunCountFlow: StateFlow<Int> = _xrunCountFlow.asStateFlow()

    private val _voiceLevelsFlow = MutableStateFlow(FloatArray(12))
    val voiceLevelsFlow: StateFlow<FloatArray> = _voiceLevelsFlow.asStateFlow()

    private val _lfoOutputFlow = MutableStateFlow(0f)
    val lfoOutputFlow: StateFlow<Float> = _lfoOutputFlow.asStateFlow()
    private val _lfoAOutputFlow = MutableStateFlow(0f)
    val lfoAOutputFlow: StateFlow<Float> = _lfoAOutputFlow.asStateFlow()
    private val _lfoBOutputFlow = MutableStateFlow(0f)
    val lfoBOutputFlow: StateFlow<Float> = _lfoBOutputFlow.asStateFlow()

    private val _masterLevelFlow = MutableStateFlow(0f)
    val masterLevelFlow: StateFlow<Float> = _masterLevelFlow.asStateFlow()

    private val _bendFlow = MutableStateFlow(0f)
    val bendFlow: StateFlow<Float> = _bendFlow.asStateFlow()

    // Signal visualization flows (60fps oscilloscope data)
    private val _lfoVizFlow = MutableStateFlow(FloatArray(0))
    val lfoVizFlow: StateFlow<FloatArray> = _lfoVizFlow.asStateFlow()
    private val _warpsCarrierVizFlow = MutableStateFlow(FloatArray(0))
    val warpsCarrierVizFlow: StateFlow<FloatArray> = _warpsCarrierVizFlow.asStateFlow()
    private val _warpsModVizFlow = MutableStateFlow(FloatArray(0))
    val warpsModVizFlow: StateFlow<FloatArray> = _warpsModVizFlow.asStateFlow()
    private val _warpsOutVizFlow = MutableStateFlow(FloatArray(0))
    val warpsOutVizFlow: StateFlow<FloatArray> = _warpsOutVizFlow.asStateFlow()
    private val _delayInVizFlow = MutableStateFlow(FloatArray(0))
    val delayInVizFlow: StateFlow<FloatArray> = _delayInVizFlow.asStateFlow()
    private val _delayFbVizFlow = MutableStateFlow(FloatArray(0))
    val delayFbVizFlow: StateFlow<FloatArray> = _delayFbVizFlow.asStateFlow()
    private val _delayOutVizFlow = MutableStateFlow(FloatArray(0))
    val delayOutVizFlow: StateFlow<FloatArray> = _delayOutVizFlow.asStateFlow()
    private val _reverbInVizFlow = MutableStateFlow(FloatArray(0))
    val reverbInVizFlow: StateFlow<FloatArray> = _reverbInVizFlow.asStateFlow()
    private val _reverbOutVizFlow = MutableStateFlow(FloatArray(0))
    val reverbOutVizFlow: StateFlow<FloatArray> = _reverbOutVizFlow.asStateFlow()
    private val _fluxCvVizFlow = MutableStateFlow(FloatArray(0))
    val fluxCvVizFlow: StateFlow<FloatArray> = _fluxCvVizFlow.asStateFlow()
    private val _resoInVizFlow = MutableStateFlow(FloatArray(0))
    val resoInVizFlow: StateFlow<FloatArray> = _resoInVizFlow.asStateFlow()
    private val _resoOutVizFlow = MutableStateFlow(FloatArray(0))
    val resoOutVizFlow: StateFlow<FloatArray> = _resoOutVizFlow.asStateFlow()
    private val _drumOutVizFlow = MutableStateFlow(FloatArray(0))
    val drumOutVizFlow: StateFlow<FloatArray> = _drumOutVizFlow.asStateFlow()
    private val _grainsInVizFlow = MutableStateFlow(FloatArray(0))
    val grainsInVizFlow: StateFlow<FloatArray> = _grainsInVizFlow.asStateFlow()
    private val _grainsOutVizFlow = MutableStateFlow(FloatArray(0))
    val grainsOutVizFlow: StateFlow<FloatArray> = _grainsOutVizFlow.asStateFlow()
    private val _lfoCh1VizFlow = MutableStateFlow(FloatArray(0))
    val lfoCh1VizFlow: StateFlow<FloatArray> = _lfoCh1VizFlow.asStateFlow()
    private val _lfoCh2VizFlow = MutableStateFlow(FloatArray(0))
    val lfoCh2VizFlow: StateFlow<FloatArray> = _lfoCh2VizFlow.asStateFlow()
    private val _lfoCh3VizFlow = MutableStateFlow(FloatArray(0))
    val lfoCh3VizFlow: StateFlow<FloatArray> = _lfoCh3VizFlow.asStateFlow()
    private val _bassOutVizFlow = MutableStateFlow(FloatArray(0))
    val bassOutVizFlow: StateFlow<FloatArray> = _bassOutVizFlow.asStateFlow()
    private val _djVizFlowA = MutableStateFlow(FloatArray(0))
    val djVizFlowA: StateFlow<FloatArray> = _djVizFlowA.asStateFlow()
    private val _djVizFlowB = MutableStateFlow(FloatArray(0))
    val djVizFlowB: StateFlow<FloatArray> = _djVizFlowB.asStateFlow()
    private val _djOutVizFlow = MutableStateFlow(FloatArray(0))
    val djOutVizFlow: StateFlow<FloatArray> = _djOutVizFlow.asStateFlow()
    private val _beatPhaseFlow = MutableStateFlow(0f)
    val beatPhaseFlow: StateFlow<Float> = _beatPhaseFlow.asStateFlow()
    private val _masterOutVizFlow = MutableStateFlow(FloatArray(0))
    val masterOutVizFlow: StateFlow<FloatArray> = _masterOutVizFlow.asStateFlow()
    private val _hornInVizFlow = MutableStateFlow(FloatArray(0))
    val hornInVizFlow: StateFlow<FloatArray> = _hornInVizFlow.asStateFlow()
    private val _hornOutVizFlow = MutableStateFlow(FloatArray(0))
    val hornOutVizFlow: StateFlow<FloatArray> = _hornOutVizFlow.asStateFlow()
    private val _hornPhaseVizFlow = MutableStateFlow(FloatArray(0))
    val hornPhaseVizFlow: StateFlow<FloatArray> = _hornPhaseVizFlow.asStateFlow()
    private val _wooferPhaseVizFlow = MutableStateFlow(FloatArray(0))
    val wooferPhaseVizFlow: StateFlow<FloatArray> = _wooferPhaseVizFlow.asStateFlow()
    private val _tidesCh0VizFlow = MutableStateFlow(FloatArray(0))
    val tidesCh0VizFlow: StateFlow<FloatArray> = _tidesCh0VizFlow.asStateFlow()
    private val _tidesCh1VizFlow = MutableStateFlow(FloatArray(0))
    val tidesCh1VizFlow: StateFlow<FloatArray> = _tidesCh1VizFlow.asStateFlow()
    private val _tidesCh2VizFlow = MutableStateFlow(FloatArray(0))
    val tidesCh2VizFlow: StateFlow<FloatArray> = _tidesCh2VizFlow.asStateFlow()
    private val _tidesCh3VizFlow = MutableStateFlow(FloatArray(0))
    val tidesCh3VizFlow: StateFlow<FloatArray> = _tidesCh3VizFlow.asStateFlow()

    // Pulsar step grid visualization
    private val _pulsarVizFlow = MutableStateFlow(PulsarVizData())
    val pulsarVizFlow: StateFlow<PulsarVizData> = _pulsarVizFlow.asStateFlow()
    private val _pulsarTrackVizFlows = List(PULSAR_NUM_TRACKS) { MutableStateFlow(FloatArray(0)) }
    val pulsarTrackVizFlows: List<StateFlow<FloatArray>> = _pulsarTrackVizFlows.map { it.asStateFlow() }
    private val _arrangementStateFlow = MutableStateFlow<PulsarArrangementState?>(null)
    val arrangementStateFlow: StateFlow<PulsarArrangementState?> = _arrangementStateFlow.asStateFlow()

    // Reusable buffers for Pulsar viz polling (avoid allocations)
    private val pulsarGates = BooleanArray(PULSAR_NUM_TRACKS * PULSAR_MAX_STEPS)
    private val pulsarVelocities = FloatArray(PULSAR_NUM_TRACKS * PULSAR_MAX_STEPS)
    private val pulsarPlayheads = IntArray(PULSAR_NUM_TRACKS)
    private val pulsarStepCounts = IntArray(PULSAR_NUM_TRACKS)
    private val arrangementBuf = IntArray(6)

    // Guards every poll-lifecycle transition below (the five Job refs plus the
    // uiVisible / monitoringActive / *Requested flags). start/stopMonitoring run
    // on the orchestrator's Default thread while setUiVisible/setVizEnabled arrive
    // on the UI main thread; without this lock the check-then-act in the
    // launch*Poll helpers races and can leak or double-launch polls.
    private val pollLock = SynchronizedObject()

    private var monitoringJob: Job? = null
    private var vizJob: Job? = null
    private var turntableVizJob: Job? = null
    private var pulsarVizJob: Job? = null
    private var arrangementPollJob: Job? = null
    var startRequested = false

    // Volatile for the lone cross-thread read in DspSynthEngine.start(); all
    // mutations happen under pollLock.
    @Volatile
    var vizRequested = false
        private set

    /** True between startMonitoring() and stopMonitoring() — i.e. engine running. */
    private var monitoringActive = false

    /** UI visibility gate for high-frequency polls. Defaults to visible
     *  (desktop/WASM never call setUiVisible). */
    private var uiVisible = true

    /** Turntable viz was requested by UI (survives a background pause/resume). */
    private var turntableVizRequested = false

    // Reusable IntArray(1) for JNI read position — avoids allocation per channel per poll
    private val vizReadPosBuf = IntArray(1)

    private fun pollVizChannel(
        channel: Int, readPositions: IntArray, vizBuf: FloatArray,
        flow: MutableStateFlow<FloatArray>
    ) {
        vizReadPosBuf[0] = readPositions[channel]
        val count = nativeBridge.nativeGetViz(channel, vizBuf, vizReadPosBuf)
        readPositions[channel] = vizReadPosBuf[0]
        if (count > 0) {
            flow.value = appendToVizRing(flow.value, vizBuf, count)
        }
    }

    private fun pollTurntableViz(
        deck: Int, buf: FloatArray,
        flow: MutableStateFlow<FloatArray>
    ) {
        nativeBridge.nativeGetTurntableViz(deck, buf)
        // Only emit if there's actual data (check if any sample is non-zero)
        flow.value = buf.copyOf()
    }

    private fun appendToVizRing(ring: FloatArray, src: FloatArray, count: Int): FloatArray {
        val maxSize = VIZ_BUF_SIZE
        val total = ring.size + count
        val result = FloatArray(minOf(total, maxSize))
        val keepFromOld = result.size - count
        if (keepFromOld > 0 && ring.size >= keepFromOld) {
            ring.copyInto(result, 0, ring.size - keepFromOld, ring.size)
        }
        // When count > maxSize, keep the most recent samples (tail of src)
        val srcStart = maxOf(0, count - result.size)
        val srcCount = minOf(count, result.size)
        val destOffset = maxOf(0, result.size - srcCount)
        src.copyInto(result, destOffset, srcStart, srcStart + srcCount)
        return result
    }

    /**
     * Start the monitoring polling coroutines.
     *
     * The arrangement poll always runs while the engine runs — it feeds
     * background consumers (PulsarSongEnding's song auto-advance and the
     * media-session metadata path). The monitor + Pulsar viz polls only feed
     * UI and are gated on [uiVisible] (see [setUiVisible]).
     */
    fun startMonitoring() = synchronized(pollLock) {
        monitoringActive = true
        launchArrangementPoll()
        if (uiVisible) {
            launchMonitorPoll()
            launchPulsarVizPoll()
            // If the signal scope / turntable viz was requested before the engine
            // started (e.g. Orphoscope is the launch viz), honour it now —
            // setVizEnabled alone can't launch the poll until isRunning is true.
            if (vizRequested) launchVizPoll()
            if (turntableVizRequested) launchTurntableVizPoll()
        }
    }

    /**
     * Pause/resume UI-only polling based on app visibility. When the app is
     * backgrounded the 60Hz Pulsar/turntable/signal-monitor polls and the 5Hz
     * monitor poll burn CPU for nobody — Android kills the app for excessive
     * background CPU. The audio engine and the arrangement poll keep running
     * (background playback + song auto-advance are features).
     *
     * Idempotent; safe to call before [startMonitoring] or when the engine
     * isn't running (relaunch is gated on [monitoringActive]).
     */
    fun setUiVisible(visible: Boolean): Unit = synchronized(pollLock) {
        if (uiVisible == visible) return@synchronized
        uiVisible = visible
        if (visible) {
            log.info {
                "UI visible — resuming UI polls " +
                    "(monitoring=$monitoringActive viz=$vizRequested turntable=$turntableVizRequested)"
            }
            if (monitoringActive) {
                launchMonitorPoll()
                launchPulsarVizPoll()
                if (vizRequested) launchVizPoll()
                // Turntable viz is a UI-only poll like the others — only relaunch
                // it while the engine is actually monitoring, otherwise a resume
                // after stopMonitoring() would poll a stopped engine.
                if (turntableVizRequested) launchTurntableVizPoll()
            }
        } else {
            log.info { "UI hidden — pausing UI polls (audio + arrangement poll keep running)" }
            monitoringJob?.cancel()
            monitoringJob = null
            pulsarVizJob?.cancel()
            pulsarVizJob = null
            vizJob?.cancel()
            vizJob = null
            turntableVizJob?.cancel()
            turntableVizJob = null
        }
    }

    /** 5Hz poll of peak/cpu/voice-level/LFO meters (UI-only consumers). */
    private fun launchMonitorPoll() {
        if (monitoringJob != null) return
        monitoringJob = monitoringScope.launch(dispatcherProvider.io) {
            val monitorBuf = FloatArray(20) // OrpheusMonitorData: peak_l, peak_r, cpu, voice_levels[12], lfo, master, bend, lfo_a, lfo_b
            while (isActive) {
                nativeBridge.nativeGetMonitor(monitorBuf)
                _peakFlow.value = maxOf(monitorBuf[0], monitorBuf[1])
                _cpuLoadFlow.value = monitorBuf[2] * 100f
                // voice_levels[0..11] at indices 3..14
                val levels = FloatArray(12) { monitorBuf[3 + it] }
                _voiceLevelsFlow.value = levels
                _lfoOutputFlow.value = monitorBuf[15]
                _masterLevelFlow.value = monitorBuf[16]
                _bendFlow.value = monitorBuf[17]
                _lfoAOutputFlow.value = monitorBuf[18]
                _lfoBOutputFlow.value = monitorBuf[19]

                val xruns = nativeBridge.nativeGetXRunCount()
                val previous = _xrunCountFlow.value
                if (xruns > previous) {
                    log.warn {
                        "Audio underrun: +${xruns - previous} (total=$xruns, " +
                            "dspLoad=${(monitorBuf[2] * 100f).toInt()}%)"
                    }
                }
                // xruns < previous means the stream was reopened (counter reset)
                _xrunCountFlow.value = xruns

                delay(MONITOR_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * ~60fps poll of the Pulsar step grid so the playhead doesn't skip steps.
     * Runs whenever monitoring runs and the UI is visible, regardless of the
     * viz waveform toggle. UI-only consumers (Pulsar/Mixer panels).
     */
    private fun launchPulsarVizPoll() {
        if (pulsarVizJob != null) return
        pulsarVizJob = monitoringScope.launch(dispatcherProvider.io) {
            val pulsarVizBuf = FloatArray(VIZ_BUF_SIZE)
            val pulsarReadPos = IntArray(VIZ_CHANNEL_COUNT)
            while (isActive) {
                nativeBridge.nativeGetPulsarViz(
                    pulsarGates, pulsarVelocities, pulsarPlayheads, pulsarStepCounts
                )
                val trackLevels = FloatArray(PULSAR_NUM_TRACKS)
                for (t in 0 until PULSAR_NUM_TRACKS) {
                    pollVizChannel(VIZ_PULSAR_TRACK_0 + t, pulsarReadPos, pulsarVizBuf, _pulsarTrackVizFlows[t])
                    val data = _pulsarTrackVizFlows[t].value
                    trackLevels[t] = if (data.isNotEmpty()) data[data.size - 1] else 0f
                }
                _pulsarVizFlow.value = PulsarVizData(
                    stepGates = Array(PULSAR_NUM_TRACKS) { t -> BooleanArray(PULSAR_MAX_STEPS) { s -> pulsarGates[t * PULSAR_MAX_STEPS + s] } },
                    stepVelocities = Array(PULSAR_NUM_TRACKS) { t -> FloatArray(PULSAR_MAX_STEPS) { s -> pulsarVelocities[t * PULSAR_MAX_STEPS + s] } },
                    playheads = pulsarPlayheads.copyOf(),
                    stepCounts = pulsarStepCounts.copyOf(),
                    trackLevels = trackLevels,
                )

                delay(VIZ_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * 5Hz poll of the Pulsar arrangement state. NOT gated on UI visibility:
     * PulsarSongEnding consumes this (via PulsarViewModel's always-on
     * enrichment collector) to detect song endings and auto-advance vibes
     * while the app plays in the background; media-session consumers
     * (Android Auto) also stay current through it.
     */
    private fun launchArrangementPoll() {
        if (arrangementPollJob != null) return
        arrangementPollJob = monitoringScope.launch(dispatcherProvider.io) {
            while (isActive) {
                nativeBridge.nativeGetPulsarArrangement(arrangementBuf)
                val sectionIdx = arrangementBuf[0]
                _arrangementStateFlow.value = if (sectionIdx >= 0) {
                    PulsarArrangementState(
                        sectionIndex = sectionIdx,
                        barsElapsed = arrangementBuf[1],
                        barsTotal = arrangementBuf[2],
                        soloActive = arrangementBuf[3] != 0,
                        soloTrack = arrangementBuf[4],
                        soloMode = arrangementBuf[5],
                    )
                } else null
                delay(200)
            }
        }
    }

    /** Start turntable viz polling (lightweight: 2x memcpy of 129 floats at 60fps). */
    fun startTurntableViz(): Unit = synchronized(pollLock) {
        turntableVizRequested = true
        // Only launch while the engine is monitoring and the UI is visible; the
        // request flag survives a pause/stop so startMonitoring()/setUiVisible()
        // can relaunch it later.
        if (!uiVisible || !monitoringActive) return@synchronized
        launchTurntableVizPoll()
    }

    private fun launchTurntableVizPoll() {
        if (turntableVizJob != null) return
        turntableVizJob = monitoringScope.launch(dispatcherProvider.io) {
            val ttBuf = FloatArray(TURNTABLE_VIZ_SIZE)
            val vizBuf = FloatArray(VIZ_BUF_SIZE)
            val readPositions = IntArray(VIZ_CHANNEL_COUNT)
            while (isActive) {
                pollTurntableViz(0, ttBuf, _djVizFlowA)
                pollTurntableViz(1, ttBuf, _djVizFlowB)
                pollVizChannel(VIZ_DJ_OUT, readPositions, vizBuf, _djOutVizFlow)
                // Poll latest beat_phase for zone-strip pulse animation
                vizReadPosBuf[0] = readPositions[VIZ_BEAT_PHASE]
                val beatCount = nativeBridge.nativeGetViz(VIZ_BEAT_PHASE, vizBuf, vizReadPosBuf)
                readPositions[VIZ_BEAT_PHASE] = vizReadPosBuf[0]
                if (beatCount > 0) {
                    _beatPhaseFlow.value = vizBuf[beatCount - 1].coerceIn(0f, 1f)
                }
                delay(VIZ_POLL_INTERVAL_MS)
            }
        }
    }

    /** Stop turntable viz polling. */
    fun stopTurntableViz(): Unit = synchronized(pollLock) {
        turntableVizRequested = false
        turntableVizJob?.cancel()
        turntableVizJob = null
        _djVizFlowA.value = FloatArray(0)
        _djVizFlowB.value = FloatArray(0)
    }

    /** Cancel all polling jobs. */
    fun stopMonitoring(): Unit = synchronized(pollLock) {
        monitoringActive = false
        vizJob?.cancel()
        vizJob = null
        turntableVizJob?.cancel()
        turntableVizJob = null
        pulsarVizJob?.cancel()
        pulsarVizJob = null
        arrangementPollJob?.cancel()
        arrangementPollJob = null
        monitoringJob?.cancel()
        monitoringJob = null
    }

    /** Enable/disable viz data polling. Only poll when Signal Monitor is active. */
    fun setVizEnabled(enabled: Boolean, isRunning: Boolean): Unit = synchronized(pollLock) {
        vizRequested = enabled
        if (enabled && isRunning && uiVisible) {
            launchVizPoll()
        } else if (!enabled && vizJob != null) {
            vizJob?.cancel()
            vizJob = null
            // Clear all flows
            listOf(_lfoVizFlow, _warpsCarrierVizFlow, _warpsModVizFlow, _warpsOutVizFlow,
                   _delayInVizFlow, _delayFbVizFlow, _delayOutVizFlow,
                   _reverbInVizFlow, _reverbOutVizFlow, _fluxCvVizFlow,
                   _resoInVizFlow, _resoOutVizFlow,
                   _drumOutVizFlow, _grainsInVizFlow, _grainsOutVizFlow,
                   _lfoCh1VizFlow, _lfoCh2VizFlow, _lfoCh3VizFlow,
                   _bassOutVizFlow, _masterOutVizFlow,
                   _hornInVizFlow, _hornOutVizFlow, _hornPhaseVizFlow, _wooferPhaseVizFlow,
                   _djOutVizFlow,
                   _tidesCh0VizFlow, _tidesCh1VizFlow, _tidesCh2VizFlow, _tidesCh3VizFlow,
            ).forEach { it.value = FloatArray(0) }
            _pulsarVizFlow.value = PulsarVizData()
        }
    }

    /** ~60fps Signal Monitor oscilloscope poll (UI-only consumers). */
    private fun launchVizPoll() {
        if (vizJob != null) return
        vizJob = monitoringScope.launch(dispatcherProvider.io) {
            val vizBuf = FloatArray(VIZ_BUF_SIZE)
            val readPositions = IntArray(VIZ_CHANNEL_COUNT)
            while (isActive) {
                pollVizChannel(VIZ_LFO, readPositions, vizBuf, _lfoVizFlow)
                pollVizChannel(VIZ_WARPS_C, readPositions, vizBuf, _warpsCarrierVizFlow)
                pollVizChannel(VIZ_WARPS_M, readPositions, vizBuf, _warpsModVizFlow)
                pollVizChannel(VIZ_WARPS_O, readPositions, vizBuf, _warpsOutVizFlow)
                pollVizChannel(VIZ_DELAY_IN, readPositions, vizBuf, _delayInVizFlow)
                pollVizChannel(VIZ_DELAY_FB, readPositions, vizBuf, _delayFbVizFlow)
                pollVizChannel(VIZ_DELAY_OUT, readPositions, vizBuf, _delayOutVizFlow)
                pollVizChannel(VIZ_REVERB_IN, readPositions, vizBuf, _reverbInVizFlow)
                pollVizChannel(VIZ_REVERB_OUT, readPositions, vizBuf, _reverbOutVizFlow)
                pollVizChannel(VIZ_FLUX_CV, readPositions, vizBuf, _fluxCvVizFlow)
                pollVizChannel(VIZ_RESO_IN, readPositions, vizBuf, _resoInVizFlow)
                pollVizChannel(VIZ_RESO_OUT, readPositions, vizBuf, _resoOutVizFlow)
                pollVizChannel(VIZ_DRUM_OUT, readPositions, vizBuf, _drumOutVizFlow)
                pollVizChannel(VIZ_GRAINS_IN, readPositions, vizBuf, _grainsInVizFlow)
                pollVizChannel(VIZ_GRAINS_OUT, readPositions, vizBuf, _grainsOutVizFlow)
                pollVizChannel(VIZ_LFO_CH1, readPositions, vizBuf, _lfoCh1VizFlow)
                pollVizChannel(VIZ_LFO_CH2, readPositions, vizBuf, _lfoCh2VizFlow)
                pollVizChannel(VIZ_LFO_CH3, readPositions, vizBuf, _lfoCh3VizFlow)
                pollVizChannel(VIZ_BASS_OUT, readPositions, vizBuf, _bassOutVizFlow)
                pollVizChannel(VIZ_MASTER_OUT, readPositions, vizBuf, _masterOutVizFlow)
                pollVizChannel(VIZ_HORN_IN, readPositions, vizBuf, _hornInVizFlow)
                pollVizChannel(VIZ_HORN_OUT, readPositions, vizBuf, _hornOutVizFlow)
                pollVizChannel(VIZ_HORN_PHASE, readPositions, vizBuf, _hornPhaseVizFlow)
                pollVizChannel(VIZ_WOOFER_PHASE, readPositions, vizBuf, _wooferPhaseVizFlow)
                pollVizChannel(VIZ_DJ_OUT, readPositions, vizBuf, _djOutVizFlow)
                pollVizChannel(VIZ_TIDES_CH0, readPositions, vizBuf, _tidesCh0VizFlow)
                pollVizChannel(VIZ_TIDES_CH1, readPositions, vizBuf, _tidesCh1VizFlow)
                pollVizChannel(VIZ_TIDES_CH2, readPositions, vizBuf, _tidesCh2VizFlow)
                pollVizChannel(VIZ_TIDES_CH3, readPositions, vizBuf, _tidesCh3VizFlow)

                delay(VIZ_SCOPE_POLL_INTERVAL_MS)
            }
        }
    }

    /** Update bend flow from external sources (setBend facade, setPluginPort). */
    fun updateBend(value: Float) {
        _bendFlow.value = value
    }

    /** Returns current peak value. */
    fun getPeak(): Float = _peakFlow.value

    companion object {
        private val log = logging("SynthEngineMonitor")
        private const val MONITOR_POLL_INTERVAL_MS = 200L
        private const val VIZ_POLL_INTERVAL_MS = 16L  // ~60fps
        // Signal-monitor oscilloscope poll — the heaviest UI poll (24 channels,
        // each allocating a fresh FloatArray ring per tick). Run at half the
        // Pulsar/turntable rate: the Orphoscope only redraws at the display
        // refresh anyway and 30Hz scope motion still reads as smooth, but the
        // per-second allocation/GC churn halves.
        private const val VIZ_SCOPE_POLL_INTERVAL_MS = 33L  // ~30fps
        private const val VIZ_BUF_SIZE = 480           // matches C++ VizRing::kVizBufSize
        // VizChannel IDs (must match C++ VizChannel enum)
        private const val VIZ_LFO = 0
        private const val VIZ_WARPS_C = 1
        private const val VIZ_WARPS_M = 2
        private const val VIZ_WARPS_O = 3
        private const val VIZ_DELAY_IN = 4
        private const val VIZ_DELAY_FB = 5
        private const val VIZ_DELAY_OUT = 6
        private const val VIZ_REVERB_IN = 7
        private const val VIZ_REVERB_OUT = 8
        private const val VIZ_FLUX_CV = 9
        private const val VIZ_RESO_IN = 10
        private const val VIZ_RESO_OUT = 11
        private const val VIZ_DRUM_OUT = 12
        private const val VIZ_GRAINS_IN = 13
        private const val VIZ_GRAINS_OUT = 14
        private const val VIZ_LFO_CH1 = 15
        private const val VIZ_LFO_CH2 = 16
        private const val VIZ_LFO_CH3 = 17
        private const val VIZ_BASS_OUT = 18
        private const val VIZ_MASTER_OUT = 19
        private const val VIZ_HORN_IN = 20
        private const val VIZ_HORN_OUT = 21
        private const val VIZ_HORN_PHASE = 22
        private const val VIZ_WOOFER_PHASE = 23
        private const val VIZ_DJ_OUT = 24
        private const val VIZ_TIDES_CH0 = 25
        private const val VIZ_TIDES_CH1 = 26
        private const val VIZ_TIDES_CH2 = 27
        private const val VIZ_TIDES_CH3 = 28
        private const val VIZ_PULSAR_TRACK_0 = 29
        private const val VIZ_BEAT_PHASE = 37
        private const val VIZ_CHANNEL_COUNT = 38  // 29 + 8 pulsar tracks + beat_phase
        private const val TURNTABLE_VIZ_SIZE = 129  // 128 waveform + 1 playhead
    }
}
