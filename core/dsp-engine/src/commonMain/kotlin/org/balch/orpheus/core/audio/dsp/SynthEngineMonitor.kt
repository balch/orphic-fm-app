package org.balch.orpheus.core.audio.dsp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.balch.orpheus.core.coroutines.DispatcherProvider

/**
 * Owns all monitoring StateFlows and signal visualization polling.
 * Extracted from DspSynthEngine for composition — not DI-injected.
 */
class SynthEngineMonitor(
    private val nativeBridge: NativeDspBridge,
    private val dispatcherProvider: DispatcherProvider
) {
    // Reactive monitoring flows
    private val _peakFlow = MutableStateFlow(0f)
    val peakFlow: StateFlow<Float> = _peakFlow.asStateFlow()

    private val _cpuLoadFlow = MutableStateFlow(0f)
    val cpuLoadFlow: StateFlow<Float> = _cpuLoadFlow.asStateFlow()

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

    // Monitoring
    private val monitoringScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var monitoringJob: Job? = null
    private var vizJob: Job? = null
    private var turntableVizJob: Job? = null
    var startRequested = false
    var vizRequested = false
        private set

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

    /** Start the monitoring polling coroutine. */
    fun startMonitoring() {
        // Poll monitor data from C++ via native bridge
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
                delay(MONITOR_POLL_INTERVAL_MS)
            }
        }
    }

    /** Start turntable viz polling (lightweight: 2x memcpy of 129 floats at 60fps). */
    fun startTurntableViz() {
        if (turntableVizJob != null) return
        turntableVizJob = monitoringScope.launch(dispatcherProvider.io) {
            val ttBuf = FloatArray(TURNTABLE_VIZ_SIZE)
            val vizBuf = FloatArray(VIZ_BUF_SIZE)
            val readPositions = IntArray(VIZ_CHANNEL_COUNT)
            while (isActive) {
                pollTurntableViz(0, ttBuf, _djVizFlowA)
                pollTurntableViz(1, ttBuf, _djVizFlowB)
                pollVizChannel(VIZ_DJ_OUT, readPositions, vizBuf, _djOutVizFlow)
                delay(VIZ_POLL_INTERVAL_MS)
            }
        }
    }

    /** Stop turntable viz polling. */
    fun stopTurntableViz() {
        turntableVizJob?.cancel()
        turntableVizJob = null
        _djVizFlowA.value = FloatArray(0)
        _djVizFlowB.value = FloatArray(0)
    }

    /** Cancel all polling jobs. */
    fun stopMonitoring() {
        vizJob?.cancel()
        vizJob = null
        turntableVizJob?.cancel()
        turntableVizJob = null
        monitoringJob?.cancel()
        monitoringJob = null
    }

    /** Enable/disable viz data polling. Only poll when Signal Monitor is active. */
    fun setVizEnabled(enabled: Boolean, isRunning: Boolean) {
        vizRequested = enabled
        if (enabled && vizJob == null && isRunning) {
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
                    delay(VIZ_POLL_INTERVAL_MS)
                }
            }
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
        }
    }

    /** Update bend flow from external sources (setBend facade, setPluginPort). */
    fun updateBend(value: Float) {
        _bendFlow.value = value
    }

    /** Returns current peak value. */
    fun getPeak(): Float = _peakFlow.value

    companion object {
        private const val MONITOR_POLL_INTERVAL_MS = 200L
        private const val VIZ_POLL_INTERVAL_MS = 16L  // ~60fps
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
        private const val VIZ_CHANNEL_COUNT = 29
        private const val TURNTABLE_VIZ_SIZE = 129  // 128 waveform + 1 playhead
    }
}
