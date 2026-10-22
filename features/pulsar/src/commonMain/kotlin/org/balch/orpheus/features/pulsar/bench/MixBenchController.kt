package org.balch.orpheus.features.pulsar.bench

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext
import org.balch.orpheus.core.audio.dsp.BenchSummaryLayout
import org.balch.orpheus.core.audio.dsp.MixBenchTap
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.plugin.PortValue.FloatValue
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.models.Vibe

/** What a capture reads: the vibe, its bench layers, and the vibe load they belong to. */
data class CaptureSnapshot(val vibe: Vibe, val layers: BenchLayers, val loadCount: Int)

/** How [MixBenchController.setHeardSend] went. */
enum class SendEdit { APPLIED, ZERO_SEND, VIBE_LOADING }

/**
 * Applies Mix Bench edits to the live Pulsar engine and turns [MixBenchTap] summaries into [meters].
 * Idle it writes nothing: a track's audition gain is written only after a level, mute or solo edit to it,
 * and the tap runs only for [meterIdleMs] after a [readMeters] or a [holdingTaps] block. Whole-vibe macros
 * move the app's real knobs; a section's macros reach only the engine, so the saved knob never holds a section value.
 * Every state change and engine write runs under one lock, so nothing the bench pushes lands after [stop].
 * Edits return false, changing nothing, while a vibe load is not yet followed: they would hit the new vibe.
 */
class MixBenchController(
    private val pulsar: PulsarFeature,
    private val synthController: SynthController,
    private val tap: MixBenchTap,
    scope: CoroutineScope,
    sampleRate: Float = 48_000f,
    private val meterIdleMs: Long = 10_000,
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
    /** The last poll's meters; they go stale once metering idles off. Call [readMeters] to keep them live. */
    val meters: StateFlow<MeterSnapshot> = _meters.asStateFlow()

    private val _muted = MutableStateFlow(emptySet<Int>())
    val muted: StateFlow<Set<Int>> = _muted.asStateFlow()

    private val _soloed = MutableStateFlow(emptySet<Int>())
    val soloed: StateFlow<Set<Int>> = _soloed.asStateFlow()

    /** The section [setLoop] holds, or null. */
    private val _loopSection = MutableStateFlow<Int?>(null)
    val loopSection: StateFlow<Int?> = _loopSection.asStateFlow()

    // Written only under [lock]; volatile for [isRunning], [isMetering] and the poll.
    @Volatile private var running = false
    @Volatile private var metering = false
    @Volatile private var resetMeters = false
    private var job: Job? = null
    private var meterJob: Job? = null
    private var watchdog: Job? = null
    /** Bumped by every watchdog arm, so a watchdog a later read or release replaced never turns metering off. */
    private var meterReads = 0
    /** Open [holdingTaps] blocks; the idle watchdog leaves metering on while any is open. */
    private var holds = 0
    /** The last vibeLoadCount the layers belong to, so edits for another vibe never come back. */
    private var loadsApplied = -1

    // Ownership, all under [lock]: what the controller writes over the app.
    private val owned = mutableSetOf<Int>()
    private var overlayOwned = false
    /** Engine-only macro values the playing section holds, as last passed to [PulsarFeature.setBenchMacros]. */
    private var held: Map<Macro, Float> = emptyMap()

    val isRunning: Boolean get() = running

    /** True while the tap is on and the poll runs. */
    val isMetering: Boolean get() = metering

    private val vibe get() = pulsar.vibeFlow.value
    private val section get() = pulsar.arrangementStateFlow.value.sectionIndex

    /** Starts the section and vibe-load followers. Writes nothing on a first start; a restart plays what it still owns. */
    suspend fun start() = lock.withLock {
        if (running) return@withLock
        running = true
        val loads = pulsar.vibeLoadCount.value
        if (loadsApplied >= 0 && loadsApplied != loads) clearForNewVibe()
        loadsApplied = loads
        job = launcher.launch {
            launch { followSections() }
            launch { followVibeLoads() }
            launch { followKnobs() }
        }
        pushGains()
        if (overlayOwned) pushOverlay()
        applySectionMacros()
    }

    /** Releases owned gains, the overlay and held section macros, and stops followers and metering (~100 ms). */
    suspend fun stop() = lock.withLock {
        if (!running) return@withLock
        running = false
        job?.cancelAndJoin()
        job = null
        stopMetering()
        _loopSection.value = null
        for (t in owned) synthController.setPluginControl(gainIds[t], FloatValue(1f))
        if (overlayOwned) pulsar.setMixOverlay(null)
        restoreHeldMacros()
    }

    /**
     * The meters now. The first read enables the tap and the 33 ms poll, so it returns empty meters;
     * each read keeps metering on for [meterIdleMs] more. Does nothing before [start] or after [stop].
     */
    suspend fun readMeters(): MeterSnapshot = lock.withLock {
        if (running) {
            if (!metering) startMetering()
            armIdleWatchdog()
        }
        _meters.value
    }

    /**
     * Keeps metering, and so the native taps, on until [block] returns; the idle watchdog never stops it meanwhile.
     * Starts metering if it is off. Does not start it before [start] or after [stop].
     */
    suspend fun <T> holdingTaps(block: suspend () -> T): T {
        lock.withLock {
            holds++
            if (running && !metering) startMetering()
        }
        try {
            return block()
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    holds--
                    if (holds == 0 && metering) armIdleWatchdog()
                }
            }
        }
    }

    /** True while a vibe load is not yet followed, when edits and captures would hit the new vibe. */
    suspend fun vibeLoadInFlight(): Boolean = lock.withLock { loadInFlight() }

    /** Fader in dB: the whole vibe, or the playing section's offset on top of it in [Scope.SECTION]. */
    suspend fun setLevelDb(t: Int, db: Float) = edit(t) {
        owned += t
        val s = editSection()
        _layers.value = _layers.value.let { l ->
            if (s == null) l.copy(vibe = l.vibe.copy(levelDb = l.vibe.levelDb.with(t, db)))
            else l.withSection(s) { it.copy(levelDb = it.levelDb.with(t, db)) }
        }
        pushGains()
    }

    /**
     * Fader as heard: sets whichever layer the scope edits so vibe + section dB in the playing section equals [db].
     * [inScope] edits that layer without touching the shared [scope]. Reads and writes under one lock,
     * so a scope change or section flip can't split the two.
     */
    suspend fun setHeardLevelDb(t: Int, db: Float, inScope: Scope? = null) = edit(t) {
        owned += t
        val s = editSection(inScope ?: _scope.value)
        _layers.value = _layers.value.let { l ->
            if (s == null) {
                val sectionDb = l.sections[section]?.levelDb?.getOrNull(t) ?: 0f
                l.copy(vibe = l.vibe.copy(levelDb = l.vibe.levelDb.with(t, db - sectionDb)))
            } else {
                l.withSection(s) { it.copy(levelDb = it.levelDb.with(t, db - l.vibe.levelDb[t])) }
            }
        }
        pushGains()
    }

    /**
     * Send as heard in the playing section. [Scope.VIBE] turns it into a ratio over the send at ratio 1;
     * [SendEdit.ZERO_SEND] (changing nothing) when that send is 0, since no ratio can lift it.
     */
    suspend fun setHeardSend(t: Int, v: Float, reverb: Boolean): SendEdit {
        requireTrack(t)
        return lock.withLock {
            if (loadInFlight()) return@withLock SendEdit.VIBE_LOADING
            if (editSection() != null) {
                setSend(t, v, reverb)
                return@withLock SendEdit.APPLIED
            }
            val l = _layers.value
            val unit = l.copy(
                vibe = if (reverb) l.vibe.copy(reverbRatio = l.vibe.reverbRatio.with(t, 1f))
                else l.vibe.copy(delayRatio = l.vibe.delayRatio.with(t, 1f)),
            ).effective(vibe, section, t, pulsar.macroKnob(Macro.ENERGY))
            val base = if (reverb) unit.reverbSend else unit.delaySend
            if (base < MIN_SEND) return@withLock SendEdit.ZERO_SEND
            setSend(t, v.coerceIn(0f, 1f) / base, reverb)
            SendEdit.APPLIED
        }
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

    /**
     * [Scope.VIBE]: moves the app's knob, saved like the user's own. [Scope.SECTION]: the value heard in the
     * playing section, written to the engine port while that section plays; the knob keeps the whole-vibe value.
     * Returns false, changing nothing, while a vibe load is not yet followed: the edit would hit the new vibe.
     */
    suspend fun setMacro(m: Macro, v: Float): Boolean = lock.withLock {
        if (loadInFlight()) return@withLock false
        val value = v.coerceIn(0f, 1f)
        val s = editSection()
        val l = _layers.value
        if (s == null) {
            _layers.value = l.copy(vibe = l.vibe.copy(macros = l.vibe.macros + (m to value)))
            if (running) {
                setKnob(m, value)
                // The knob wrote its port, so a section holding this macro writes its value back over it.
                if (m in held) applySectionMacros(force = true)
            }
        } else {
            _layers.value = l.withSection(s) { it.copy(macrosHeard = it.macrosHeard + (m to value)) }
            applySectionMacros()
        }
        true
    }

    /** The vibe and the layers a capture reads, taken together; null while a vibe load is not yet followed. */
    suspend fun captureSnapshot(): CaptureSnapshot? = lock.withLock {
        if (loadInFlight()) null else CaptureSnapshot(vibe, _layers.value, pulsar.vibeLoadCount.value)
    }

    suspend fun toggleMute(t: Int) = edit(t) {
        owned += t
        _muted.value = _muted.value.let { if (t in it) it - t else it + t }
        pushGains()
    }

    /** A solo silences every other track, so it owns all of them. */
    suspend fun toggleSolo(t: Int) = edit(t) {
        owned += 0 until TRACKS
        _soloed.value = _soloed.value.let { if (t in it) it - t else it + t }
        pushGains()
    }

    /** Tracks whose audition gain the controller writes over the app. */
    suspend fun ownedTracks(): Set<Int> = lock.withLock { owned.toSet() }

    /** Section edits fall back to the vibe layer when the vibe has fewer than two sections. */
    suspend fun setScope(s: Scope) = lock.withLock { _scope.value = s }

    /** Jumps at the next bar line; a held loop moves to [section]. */
    suspend fun jumpTo(section: Int) = lock.withLock {
        if (_loopSection.value != null) _loopSection.value = section
        pulsar.jumpToSection(section)
    }

    /** Holds the playing section: each observed move away from it jumps back at the next bar line. */
    suspend fun setLoop(on: Boolean) = lock.withLock {
        _loopSection.value = if (on) section.takeIf { it >= 0 } else null
    }

    /**
     * Drops every layer edit and hands their tracks, the overlay and held section macros back to the app.
     * Mutes and solos stay, with the tracks they need. Whole-vibe macro moves stay on the app's knobs,
     * and in the vibe layer, so a capture still writes what the knob plays.
     */
    suspend fun resetToFile() = lock.withLock {
        _layers.value = BenchLayers(vibe = VibeLayer(macros = _layers.value.vibe.macros))
        val keep = if (_soloed.value.isNotEmpty()) owned.toSet() else owned intersect _muted.value
        val released = owned - keep
        owned.retainAll(keep)
        if (running) for (t in released) synthController.setPluginControl(gainIds[t], FloatValue(1f))
        pushGains()
        releaseOverlay()
        restoreHeldMacros()
    }

    /**
     * The TRACKS view's reset: clears every vibe and section level, mute and solo, and hands every track's
     * audition gain back to the app. Pans, sends, macros and the overlay stay.
     */
    suspend fun resetLevels() = lock.withLock {
        val l = _layers.value
        _layers.value = l.copy(
            vibe = l.vibe.copy(levelDb = List(TRACKS) { 0f }),
            sections = l.sections.mapValues { (_, layer) -> layer.copy(levelDb = List(TRACKS) { 0f }) },
        )
        _muted.value = emptySet()
        _soloed.value = emptySet()
        // With no level, mute or solo left, every track plays unity, so all of them go back to the app.
        if (running) for (t in owned) synthController.setPluginControl(gainIds[t], FloatValue(1f))
        owned.clear()
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
                // Layers from a vibe the load follower has not cleared yet must not touch the new vibe's knobs.
                if (!running || pulsar.vibeLoadCount.value != loadsApplied) return@withLock
                pushGains()
                applySectionMacros()
                val loop = _loopSection.value
                if (loop != null && s >= 0 && s != loop) pulsar.jumpToSection(loop)
            }
        }
    }

    // PulsarViewModel.applyVibe resets audition gain to 1.0 on every load (as does the core edition's TRACKS view),
    // and an engine rebuilt under the feature re-applies the vibe with the tap off.
    private suspend fun followVibeLoads() {
        pulsar.vibeLoadCount.collect { count ->
            lock.withLock {
                if (!running || count == loadsApplied) return@withLock
                loadsApplied = count
                clearForNewVibe()
                if (metering) tap.setEnabled(true)
            }
        }
    }

    // A knob move (hand, MIDI, AI) writes its port, so a macro the section holds goes back on the engine.
    private suspend fun followKnobs() {
        pulsar.stateFlow.map { Macro.entries.map(pulsar::macroKnob) }.distinctUntilChanged().collect {
            lock.withLock {
                if (!running || pulsar.vibeLoadCount.value != loadsApplied || held.isEmpty()) return@withLock
                applySectionMacros(force = true)
            }
        }
    }

    // Under [lock]. Stops metering [meterIdleMs] from now unless a later read or an open hold intervenes.
    private fun armIdleWatchdog() {
        val read = ++meterReads
        watchdog?.cancel()
        watchdog = launcher.launch {
            delay(meterIdleMs)
            lock.withLock {
                if (read != meterReads || holds > 0) return@withLock
                watchdog = null
                stopMetering()
            }
        }
    }

    private fun startMetering() {
        tap.setEnabled(true)
        // The poll is stopped here, so the meters can be reset without racing it.
        benchMeters.reset()
        resetMeters = false
        _meters.value = benchMeters.snapshot()
        metering = true
        meterJob = launcher.launch { poll() }
    }

    // The idle watchdog clears [watchdog] before calling this, so it never cancels itself mid-teardown.
    private suspend fun stopMetering() {
        watchdog?.cancel()
        watchdog = null
        val j = meterJob ?: return
        meterJob = null
        metering = false
        j.cancelAndJoin()
        tap.setEnabled(false)
    }

    private suspend inline fun edit(t: Int, crossinline body: () -> Unit): Boolean {
        requireTrack(t)
        return lock.withLock {
            if (loadInFlight()) return@withLock false
            body()
            true
        }
    }

    // The load already put the new vibe's knobs on the engine, so held section macros are dropped, not restored.
    private fun clearForNewVibe() {
        _layers.value = BenchLayers()
        _muted.value = emptySet()
        _soloed.value = emptySet()
        _loopSection.value = null
        resetMeters = true
        if (running) for (t in owned) synthController.setPluginControl(gainIds[t], FloatValue(1f))
        owned.clear()
        releaseOverlay()
        // The feature drops its copy on a load too; telling it covers any path that didn't.
        if (held.isNotEmpty()) pulsar.setBenchMacros(emptyMap())
        held = emptyMap()
    }

    /** True while the layers belong to a vibe the load follower has not cleared yet. */
    private fun loadInFlight() = running && pulsar.vibeLoadCount.value != loadsApplied

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
    private fun editSection(scope: Scope = _scope.value): Int? =
        section.takeIf { scope == Scope.SECTION && it >= 0 && vibe.sectionScopeAvailable() }

    private fun gain(t: Int): Float {
        val solo = _soloed.value
        if (t in _muted.value || (solo.isNotEmpty() && t !in solo)) return 0f
        return _layers.value.auditionGain(t, section)
    }

    private fun pushGains() {
        if (!running) return
        for (t in owned) synthController.setPluginControl(gainIds[t], FloatValue(gain(t)))
    }

    private fun pushOverlay() {
        overlayOwned = true
        if (running) pulsar.setMixOverlay(_layers.value.overlay(vibe))
    }

    private fun releaseOverlay() {
        if (!overlayOwned) return
        overlayOwned = false
        if (running) pulsar.setMixOverlay(null)
    }

    /**
     * Writes each heard macro of the playing section to the engine port; a macro no longer held plays its knob
     * again. [force] rewrites unchanged values after a knob move has overwritten the port.
     */
    private fun applySectionMacros(force: Boolean = false) {
        if (!running || loadInFlight()) return
        val l = _layers.value
        val s = section
        val heard = l.sections[s]?.macrosHeard.orEmpty()
        val want = heard.keys.associateWith { l.macroPortValue(vibe, s, it, pulsar.macroKnob(it)) }
        if (want == held && !(force && want.isNotEmpty())) return
        held = want
        pulsar.setBenchMacros(want)
    }

    /** Hands every held macro's port back to its knob. */
    private fun restoreHeldMacros() {
        if (held.isEmpty()) return
        held = emptyMap()
        pulsar.setBenchMacros(emptyMap())
    }

    private fun setKnob(m: Macro, v: Float) = when (m) {
        Macro.ENERGY -> pulsar.actions.setEnergy(v)
        Macro.COMPLEXITY -> pulsar.actions.setComplexity(v)
        Macro.MOOD -> pulsar.actions.setMood(v)
        Macro.SPACE -> pulsar.actions.setSpace(v)
    }

    private companion object {
        const val TRACKS = 8
        const val POLL_MS = 33L
        const val MAX_DRAIN = 64
        const val MIN_SEND = 1e-4f

        fun requireTrack(t: Int) = require(t in 0 until TRACKS) { "track $t out of range" }

        fun <T> List<T>.with(i: Int, v: T): List<T> = toMutableList().also { it[i] = v }

        fun BenchLayers.withSection(s: Int, edit: (SectionLayer) -> SectionLayer) =
            copy(sections = sections + (s to edit(sections[s] ?: SectionLayer())))
    }
}
