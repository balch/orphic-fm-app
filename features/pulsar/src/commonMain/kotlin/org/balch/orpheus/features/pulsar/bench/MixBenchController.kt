package org.balch.orpheus.features.pulsar.bench

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.balch.orpheus.core.audio.dsp.BenchSummaryLayout
import org.balch.orpheus.core.audio.dsp.MixBenchTap
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.PortValue.FloatValue
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import org.balch.orpheus.features.pulsar.PulsarFeature

/**
 * Applies Mix Bench edits to the live Pulsar engine and turns [MixBenchTap] summaries into [meters].
 * While running it owns `track_N_audition_gain`: do not use the app's TRACKS mixer at the same time.
 * Every state change and engine write runs under one lock, so nothing the bench pushes lands after [stop].
 */
class MixBenchController(
    private val pulsar: PulsarFeature,
    private val synthController: SynthController,
    private val tap: MixBenchTap,
    scope: CoroutineScope,
    sampleRate: Float = 48_000f,
) {
    private val launcher = scope
    private val lock = Mutex()
    private val benchMeters = BenchMeters(sampleRate)
    private val drainBuf = FloatArray(MAX_DRAIN * BenchSummaryLayout.FLOATS)
    private val gainIds = List(TRACKS) { PulsarSymbol.entries[PulsarSymbol.TRACK_0_AUDITION_GAIN.ordinal + it].controlId }

    private val _layers = MutableStateFlow(BenchLayers())
    val layers: StateFlow<BenchLayers> = _layers.asStateFlow()

    private val _scope = MutableStateFlow(Scope.VIBE)
    val scope: StateFlow<Scope> = _scope.asStateFlow()

    private val _meters = MutableStateFlow(benchMeters.snapshot())
    val meters: StateFlow<MeterSnapshot> = _meters.asStateFlow()

    private val _muted = MutableStateFlow(emptySet<Int>())
    val muted: StateFlow<Set<Int>> = _muted.asStateFlow()

    private val _soloed = MutableStateFlow(emptySet<Int>())
    val soloed: StateFlow<Set<Int>> = _soloed.asStateFlow()

    /** The section [setLoop] holds, or null. */
    private val _loopSection = MutableStateFlow<Int?>(null)
    val loopSection: StateFlow<Int?> = _loopSection.asStateFlow()

    // Written only under [lock]; volatile for [isRunning] and the poll.
    @Volatile private var running = false
    @Volatile private var resetMeters = false
    private var job: Job? = null
    /** The last vibeLoadCount the layers belong to, so edits for another vibe never come back. */
    private var loadsApplied = -1

    val isRunning: Boolean get() = running

    private val vibe get() = pulsar.vibeFlow.value
    private val section get() = pulsar.arrangementStateFlow.value.sectionIndex

    /** Enables the tap, polls it every 33 ms and takes over audition gain, overlay and touched macros. */
    suspend fun start() = lock.withLock {
        if (running) return@withLock
        running = true
        tap.setEnabled(true)
        resetMeters = true
        val loads = pulsar.vibeLoadCount.value
        if (loadsApplied >= 0 && loadsApplied != loads) clearForNewVibe()
        loadsApplied = loads
        job = launcher.launch {
            launch { poll() }
            launch { followSections() }
            launch { followVibeLoads() }
        }
        pushGains()
        pushOverlay()
        pushMacros(touched(_layers.value))
    }

    /** Waits out the followers and poll, releases the engine to the file mix and disables the tap (~100 ms). */
    suspend fun stop() = lock.withLock {
        if (!running) return@withLock
        running = false
        job?.cancelAndJoin()
        job = null
        _loopSection.value = null
        tap.setEnabled(false)
        for (t in 0 until TRACKS) synthController.setPluginControl(gainIds[t], FloatValue(1f))
        pulsar.setMixOverlay(null)
        val v = vibe
        for (m in touched(_layers.value)) macroSetter(m)(m.vibeDefault(v))
    }

    /** Fader in dB: the whole vibe, or the playing section's offset on top of it in [Scope.SECTION]. */
    suspend fun setLevelDb(t: Int, db: Float) = edit(t) {
        val s = editSection()
        _layers.value = _layers.value.let { l ->
            if (s == null) l.copy(vibe = l.vibe.copy(levelDb = l.vibe.levelDb.with(t, db)))
            else l.withSection(s) { it.copy(levelDb = it.levelDb.with(t, db)) }
        }
        pushGains()
    }

    /** Pan is whole-vibe only. */
    suspend fun setPan(t: Int, p: Float) = edit(t) {
        val l = _layers.value
        _layers.value = l.copy(vibe = l.vibe.copy(pan = l.vibe.pan.with(t, p.coerceIn(-1f, 1f))))
        pushOverlay()
    }

    /** [Scope.VIBE]: a ratio on every section's send. [Scope.SECTION]: the absolute send heard there. */
    suspend fun setReverb(t: Int, v: Float) = edit(t) { setSend(t, v, reverb = true) }

    /** As [setReverb], for the delay send. */
    suspend fun setDelay(t: Int, v: Float) = edit(t) { setSend(t, v, reverb = false) }

    /** [Scope.VIBE]: the knob value. [Scope.SECTION]: the value heard in the playing section. */
    suspend fun setMacro(m: Macro, v: Float) = lock.withLock {
        val value = v.coerceIn(0f, 1f)
        val s = editSection()
        val l = _layers.value
        _layers.value =
            if (s == null) l.copy(vibe = l.vibe.copy(macros = l.vibe.macros + (m to value)))
            else l.withSection(s) { it.copy(macrosHeard = it.macrosHeard + (m to value)) }
        pushMacros(setOf(m))
    }

    suspend fun toggleMute(t: Int) = edit(t) {
        _muted.value = _muted.value.let { if (t in it) it - t else it + t }
        pushGains()
    }

    suspend fun toggleSolo(t: Int) = edit(t) {
        _soloed.value = _soloed.value.let { if (t in it) it - t else it + t }
        pushGains()
    }

    /** Section edits fall back to the vibe layer when the vibe has fewer than two sections. */
    suspend fun setScope(s: Scope) = lock.withLock { _scope.value = s }

    /** Jumps at the next loop wrap; a held loop moves to [section]. */
    suspend fun jumpTo(section: Int) = lock.withLock {
        if (_loopSection.value != null) _loopSection.value = section
        pulsar.jumpToSection(section)
    }

    /** Holds the playing section: each observed move away from it jumps back at the next wrap. */
    suspend fun setLoop(on: Boolean) = lock.withLock {
        _loopSection.value = if (on) section.takeIf { it >= 0 } else null
    }

    /** Drops every layer edit and plays the file's mix. Mutes and solos stay. */
    suspend fun resetToFile() = lock.withLock {
        val old = _layers.value
        _layers.value = BenchLayers()
        pushGains()
        pushOverlay()
        pushMacros(touched(old))
    }

    // Touches only the meters and the tap's reader side, so it runs outside the lock.
    private suspend fun poll() {
        while (currentCoroutineContext().isActive) {
            if (resetMeters) {
                resetMeters = false
                benchMeters.reset()
            }
            do {
                val n = tap.drain(drainBuf)
                if (n > 0) benchMeters.add(drainBuf, n)
            } while (n == MAX_DRAIN)
            _meters.value = benchMeters.snapshot()
            delay(POLL_MS)
        }
    }

    private suspend fun followSections() {
        pulsar.arrangementStateFlow.map { it.sectionIndex }.distinctUntilChanged().collect { s ->
            lock.withLock {
                if (!running) return@withLock
                pushGains()
                pushMacros(touched(_layers.value))
                val loop = _loopSection.value
                if (loop != null && s >= 0 && s != loop) pulsar.jumpToSection(loop)
            }
        }
    }

    // PulsarViewModel.applyVibe and TrackTuningController reset audition gain to 1.0 on every load,
    // and an engine rebuilt under the feature re-applies the vibe with the bench off.
    private suspend fun followVibeLoads() {
        pulsar.vibeLoadCount.collect { count ->
            lock.withLock {
                if (!running || count == loadsApplied) return@withLock
                loadsApplied = count
                clearForNewVibe()
                tap.setEnabled(true)
                pushGains()
                pushOverlay()
            }
        }
    }

    private suspend inline fun edit(t: Int, crossinline body: () -> Unit) {
        requireTrack(t)
        lock.withLock { body() }
    }

    private fun clearForNewVibe() {
        _layers.value = BenchLayers()
        _muted.value = emptySet()
        _soloed.value = emptySet()
        _loopSection.value = null
        resetMeters = true
    }

    private fun setSend(t: Int, v: Float, reverb: Boolean) {
        val value = v.coerceAtLeast(0f)
        val s = editSection()
        val l = _layers.value
        _layers.value = if (s == null) {
            l.copy(
                vibe = if (reverb) l.vibe.copy(reverbRatio = l.vibe.reverbRatio.with(t, value))
                else l.vibe.copy(delayRatio = l.vibe.delayRatio.with(t, value)),
            )
        } else {
            // The overlay multiplies section sends by the whole-vibe ratio, so store desired / ratio.
            val ratio = if (reverb) l.vibe.reverbRatio[t] else l.vibe.delayRatio[t]
            val desired = value.coerceAtMost(1f)
            val stored = if (ratio > 0f) desired / ratio else desired
            l.withSection(s) {
                if (reverb) it.copy(reverbSend = it.reverbSend.with(t, stored))
                else it.copy(delaySend = it.delaySend.with(t, stored))
            }
        }
        pushOverlay()
    }

    /** The section a [Scope.SECTION] edit lands in, or null for the vibe layer. */
    private fun editSection(): Int? =
        section.takeIf { _scope.value == Scope.SECTION && it >= 0 && vibe.sectionScopeAvailable() }

    private fun gain(t: Int): Float {
        val solo = _soloed.value
        if (t in _muted.value || (solo.isNotEmpty() && t !in solo)) return 0f
        return _layers.value.auditionGain(t, section)
    }

    private fun pushGains() {
        if (!running) return
        for (t in 0 until TRACKS) synthController.setPluginControl(gainIds[t], FloatValue(gain(t)))
    }

    private fun pushOverlay() {
        if (running) pulsar.setMixOverlay(_layers.value.overlay(vibe))
    }

    private fun pushMacros(macros: Set<Macro>) {
        if (!running) return
        val l = _layers.value
        val v = vibe
        val s = section
        for (m in macros) macroSetter(m)(l.macroPortValue(v, s, m))
    }

    private fun macroSetter(m: Macro): (Float) -> Unit = when (m) {
        Macro.ENERGY -> pulsar.actions.setEnergy
        Macro.COMPLEXITY -> pulsar.actions.setComplexity
        Macro.MOOD -> pulsar.actions.setMood
        Macro.SPACE -> pulsar.actions.setSpace
    }

    private companion object {
        const val TRACKS = 8
        const val POLL_MS = 33L
        const val MAX_DRAIN = 64

        fun requireTrack(t: Int) = require(t in 0 until TRACKS) { "track $t out of range" }

        /** Macros any layer sets, so moving off them re-pushes the knob value. */
        fun touched(l: BenchLayers): Set<Macro> =
            l.vibe.macros.keys + l.sections.values.flatMap { it.macrosHeard.keys }

        fun <T> List<T>.with(i: Int, v: T): List<T> = toMutableList().also { it[i] = v }

        fun BenchLayers.withSection(s: Int, edit: (SectionLayer) -> SectionLayer) =
            copy(sections = sections + (s to edit(sections[s] ?: SectionLayer())))
    }
}
