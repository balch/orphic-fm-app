package org.balch.orpheus.djapp.ai.licklab

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.features.SynthFeature
import org.balch.orpheus.core.features.SynthFeatureKey
import org.balch.orpheus.core.features.synthFeature
import org.balch.orpheus.core.plugin.viz.PulsarVizData
import org.balch.orpheus.djapp.ai.songref.intervals
import org.balch.orpheus.djapp.ai.songref.pitchClassName
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.bench.BenchMeters
import org.balch.orpheus.features.pulsar.models.ChordFollow
import org.balch.orpheus.features.pulsar.models.TrackRole
import org.balch.orpheus.features.pulsar.models.TrackVoice
import org.balch.orpheus.features.pulsar.models.Vibe
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/**
 * Lick Lab: one armed Pulsar track played live from keys and pointer, with takes recorded against [LickClock].
 * Everything runs on the main thread (actions from the UI, collectors on [DispatcherProvider.main]), so the
 * session state below needs no locking. The ports are written synchronously from each action for low latency.
 */
@SingleIn(FeatureScope::class)
@SynthFeatureKey(LickLabFeature::class)
@ContributesIntoMap(FeatureScope::class, binding = binding<SynthFeature<*, *>>())
@ContributesBinding(FeatureScope::class, binding = binding<LickLabFeature>())
class LickLabViewModel internal constructor(
    private val pulsarFeature: PulsarFeature,
    private val synthEngine: SynthEngine,
    synthController: SynthController,
    private val scope: FeatureCoroutineScope,
    private val dispatchers: DispatcherProvider,
    nowMs: () -> Long,
) : LickLabFeature, LickLabActions {

    @Inject
    constructor(
        pulsarFeature: PulsarFeature,
        synthEngine: SynthEngine,
        synthController: SynthController,
        scope: FeatureCoroutineScope,
        dispatchers: DispatcherProvider,
    ) : this(pulsarFeature, synthEngine, synthController, scope, dispatchers, monotonicMs())

    private data class Pointer(val id: Long)

    private class Held(val home: Int, val velocity: Float)

    private val live = LiveTrackInput(synthController)
    private val heldNotes = HeldNotes()
    private val recorder = TakeRecorder(BEATS_PER_BAR)
    private val clock = LickClock(nowMs)

    private var vibe: Vibe = pulsarFeature.vibeFlow.value
    private val _state = MutableStateFlow(keyed(LickLabUiState(), vibe))
    override val stateFlow: StateFlow<LickLabUiState> = _state.asStateFlow()
    override val actions: LickLabActions get() = this

    private val _copied = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val copied: Flow<String> = _copied

    // Held keys and pointers in press order; the last one sounds, as in [heldNotes].
    private val held = LinkedHashMap<Any, Held>()
    private var shiftHeld = false
    private var takeContext: TakeContext? = null
    private var takeOpen = false
    private val answer = ArrayList<LiveNote>()
    private val call = ArrayList<LiveNote>()
    private var callSounding: Int? = null

    // The chord now sounding and the longest countdown seen for it, which is its length in beats.
    private var hintChord = -2
    private var chordBeats = 0f

    // Subtracted from the clock so beat 0 of a bar is a bar line; moved only between takes.
    private var barOffset = 0

    // The vibe the armed track was armed in, so a restart of it keeps the arm.
    private var armedVibe: String? = null

    private var shownJob: Job? = null
    private var pulseHold: DisposableHandle? = null
    private var tickJob: Job? = null
    private var playJob: Job? = null

    init {
        scope.launch(dispatchers.main) {
            pulsarFeature.vibeFlow.collect { v ->
                vibe = v
                _state.update { keyed(it, v) }
                // A caller that is no longer offered stops being the caller.
                val caller = _state.value.callerTrack
                if (caller != null && _state.value.tracks.none { it.index == caller }) setCaller(null)
            }
        }
        scope.launch(dispatchers.main) {
            pulsarFeature.vibeLoadCount.drop(1).collect { onVibeLoad() }
        }
        scope.onCleared {
            _state.value.armedTrack?.let(live::disarm)
            pulsarFeature.bypassSectionVolume(null)
            pulseHold?.dispose()
        }
    }

    // region actions

    override fun toggleShown() {
        if (_state.value.shown) {
            disarm()
            stopRecording()
            shownJob?.cancel()
            pulseHold?.dispose()
            pulseHold = null
            answer.clear()
            call.clear()
            callSounding = null
            _state.update { it.copy(shown = false, answerNotes = emptyList(), callNotes = emptyList()) }
        } else {
            _state.update { it.copy(shown = true) }
            pulseHold = pulsarFeature.holdMusicPulse()
            shownJob = scope.launch(dispatchers.main) {
                launch { synthEngine.pulsarVizFlow.collect(::onViz) }
                launch {
                    pulsarFeature.arrangementStateFlow.map { it.barsElapsed }.distinctUntilChanged().drop(1)
                        .collect { onBarLine() }
                }
            }
        }
    }

    override fun arm(track: Int) {
        val s = _state.value
        if (!s.shown || s.tracks.none { it.index == track }) return
        if (s.armedTrack == track) {
            disarm()
            return
        }
        disarm()
        // A track that is its own caller shows the notes it would have played, unheard.
        if (s.callerTrack == track) live.ghost(track, on = true)
        live.arm(track)
        // Boost moves with the player onto the new track.
        if (s.boost) boost(track, on = true)
        armedVibe = vibe.name
        _state.update {
            keyed(it.copy(armedTrack = track, slot = LickPitch.defaultSlotFor(vibe, track), boost = s.boost), vibe)
        }
    }

    /**
     * A restart or loop of the same vibe keeps the armed track, its slot and boost (re-armed, since the reload
     * releases held notes); another vibe may change the key and the tracks, so it disarms.
     */
    private fun onVibeLoad() {
        val s = _state.value
        val track = s.armedTrack ?: return
        val same = pulsarFeature.vibeFlow.value.name == armedVibe && s.tracks.any { it.index == track }
        disarm()
        if (!same) return
        arm(track)
        setSlot(s.slot)
        if (s.boost) toggleBoost()
    }

    override fun setCaller(track: Int?) {
        _state.value.armedTrack?.let { live.ghost(it, on = it == track) }
        call.clear()
        callSounding = null
        _state.update { it.copy(callerTrack = track, callNotes = emptyList()) }
    }

    override fun setSlot(slot: SlotRef) = _state.update { keyed(it.copy(slot = slot), vibe) }

    override fun setKeyMode(mode: KeyMode) = _state.update { it.copy(keyMode = mode) }

    override fun setHintMode(mode: HintMode) = _state.update { it.copy(hintMode = mode) }

    override fun toggleBoost() {
        val s = _state.value
        val track = s.armedTrack ?: return
        boost(track, on = !s.boost)
        _state.update { it.copy(boost = !s.boost) }
    }

    /** Boost lifts the track and plays it past its sections' volume overrides. */
    private fun boost(track: Int, on: Boolean) {
        live.boost(track, if (on) BOOST_GAIN else 1f)
        pulsarFeature.bypassSectionVolume(if (on) track else null)
    }

    override fun toggleRecording() {
        if (_state.value.recording) {
            stopRecording()
            return
        }
        _state.update { it.copy(recording = true) }
        tickJob = scope.launch(dispatchers.main) {
            while (isActive) {
                delay(TICK_MS)
                recorder.tick(beat())?.let(::closeTake)
            }
        }
    }

    override fun keyDown(key: Key) {
        if (key == Key.ShiftLeft || key == Key.ShiftRight) {
            shiftHeld = true
            return
        }
        if (key in held) return // auto-repeat
        val offset = LickKeyMap.offsetFor(key, _state.value.keyMode) ?: return
        val home = when (_state.value.keyMode) {
            KeyMode.InKey -> LickPitch.degreeToMidi(offset, vibe, _state.value.slot)
            KeyMode.Chromatic -> (_state.value.baseMidi + offset).coerceIn(0, 127)
        }
        press(key, home, KEY_VELOCITY)
    }

    override fun keyUp(key: Key) {
        if (key == Key.ShiftLeft || key == Key.ShiftRight) shiftHeld = false else release(key)
    }

    override fun pointerDown(id: Long, midi: Int, velocity: Float) = press(Pointer(id), midi, velocity)

    override fun pointerSlide(id: Long, midi: Int, glideRate: Float) {
        val track = _state.value.armedTrack ?: return
        val p = Pointer(id)
        val h = held[p] ?: return
        held[p] = Held(midi, h.velocity)
        if (heldNotes.move(p, midi) is HeldNotes.Change.Legato) {
            live.legato(track, sounding(midi), glideRate)
            noteOn(midi, h.velocity, slidIn = true, glideRate = glideRate)
        }
    }

    override fun pointerBend(id: Long, semis: Float) {
        val track = _state.value.armedTrack ?: return
        val p = Pointer(id)
        if (held.keys.lastOrNull() != p) return
        live.bend(track, sounding(held.getValue(p).home) + semis.coerceIn(-MAX_BEND, MAX_BEND))
    }

    override fun pointerUp(id: Long) = release(Pointer(id))

    override fun releaseAll() {
        shiftHeld = false
        val playing = playJob?.isActive == true
        playJob?.cancel()
        val hadNotes = held.isNotEmpty()
        heldNotes.clear()
        held.clear()
        val track = _state.value.armedTrack ?: return
        if (hadNotes || playing) live.release(track)
        if (hadNotes) noteOff()
    }

    override fun star(takeId: Long) =
        _state.update { s -> s.copy(takes = s.takes.map { if (it.id == takeId) it.copy(starred = !it.starred) else it }) }

    override fun delete(takeId: Long) {
        _state.update { s -> s.copy(takes = s.takes.filterNot { it.id == takeId }) }
    }

    override fun copy(takeId: Long) {
        kotlinFor(takeId)?.let { _copied.tryEmit(it) }
    }

    override fun playTake(takeId: Long) {
        val track = _state.value.armedTrack ?: return
        val take = _state.value.takes.firstOrNull { it.id == takeId } ?: return
        if (take.notes.isEmpty() || held.isNotEmpty()) return
        playJob?.cancel()
        playJob = scope.launch(dispatchers.main) {
            val msPerBeat = msPerBeat()
            var at = take.notes.first().startBeat
            take.notes.forEachIndexed { i, n ->
                delay(((n.startBeat - at) * msPerBeat).toLong())
                val prev = take.notes.getOrNull(i - 1)
                // The recorder ends a note where the next starts only for an overlap: legato, slid or hammered.
                if (prev != null && prev.endBeat >= n.startBeat) {
                    live.legato(track, sounding(n.midi), if (n.slidIn) n.glideRate else 0f)
                } else {
                    live.strike(track, sounding(n.midi), n.velocity, 0f)
                }
                delay(((n.endBeat - n.startBeat) * msPerBeat).toLong())
                at = n.endBeat
                val next = take.notes.getOrNull(i + 1)
                if (next == null || next.startBeat > n.endBeat) live.release(track)
            }
        }
    }

    // endregion

    override fun currentBeat(): Double = beat()

    // Both read only the immutable state, so the MCP thread may call them.
    override fun kotlinFor(takeId: Long): String? {
        val ctx = takeFor(takeId)?.context ?: return null
        val lick = exportFor(takeId) ?: return null
        return TakeExport.toKotlin(lick, ctx.slot, ctx.followsChords)
    }

    override fun exportFor(takeId: Long): ExportedLick? {
        val take = takeFor(takeId) ?: return null
        val ctx = take.context ?: return null
        return TakeExport.toSteps(take, ctx.vibe, ctx.slot)
    }

    private fun takeFor(takeId: Long): Take? = _state.value.takes.firstOrNull { it.id == takeId }

    private fun press(id: Any, home: Int, velocity: Float) {
        val track = _state.value.armedTrack ?: return
        if (id in held) return
        playJob?.cancel()
        held[id] = Held(home, velocity)
        when (heldNotes.press(id, home)) {
            is HeldNotes.Change.Strike -> {
                live.strike(track, sounding(home), velocity, 0f)
                noteOn(home, velocity, slidIn = false, glideRate = -1f)
            }
            is HeldNotes.Change.Legato -> {
                val glide = if (shiftHeld) SLIDE_GLIDE else 0f
                live.legato(track, sounding(home), glide)
                noteOn(home, velocity, slidIn = shiftHeld, glideRate = if (shiftHeld) glide else -1f)
            }
            else -> Unit
        }
    }

    private fun release(id: Any) {
        val track = _state.value.armedTrack ?: return
        held.remove(id) ?: return
        when (val c = heldNotes.release(id)) {
            is HeldNotes.Change.Release -> {
                live.release(track)
                noteOff()
            }
            is HeldNotes.Change.Legato -> {
                live.legato(track, sounding(c.midi), 0f)
                noteOn(c.midi, held.values.last().velocity, slidIn = false, glideRate = -1f)
            }
            else -> Unit
        }
    }

    private fun noteOn(home: Int, velocity: Float, slidIn: Boolean, glideRate: Float) {
        val b = beat()
        if (_state.value.recording) {
            recorder.noteOn(b, home, velocity, slidIn, glideRate)?.let(::closeTake)
            if (!takeOpen) {
                takeContext = TakeContext(vibe, _state.value.slot, followsChords(_state.value.armedTrack))
                takeOpen = true
            }
        }
        closeAnswer(b)
        answer += LiveNote(home, b, degree = degreeOf(home))
        _state.update { it.copy(lastPlayedMidi = home) }
        publishNotes(b, heldMidi = home)
    }

    private fun noteOff() {
        val b = beat()
        if (_state.value.recording) recorder.noteOff(b)
        closeAnswer(b)
        publishNotes(b, heldMidi = null)
    }

    private fun closeAnswer(b: Double) {
        val last = answer.lastOrNull() ?: return
        if (last.endBeat == null) answer[answer.lastIndex] = last.copy(endBeat = b)
    }

    /** Disarm, vibe change and hide: the track goes back to its sequencer with nothing held. */
    override fun disarm() {
        playJob?.cancel()
        heldNotes.clear()
        val hadNotes = held.isNotEmpty()
        held.clear()
        val track = _state.value.armedTrack ?: return
        live.disarm(track)
        pulsarFeature.bypassSectionVolume(null)
        if (_state.value.recording) flushTake()
        if (hadNotes) closeAnswer(beat())
        _state.update {
            it.copy(
                armedTrack = null, heldMidi = null, answerNotes = answer.toList(), boost = false,
                nextChordRootDegree = -1, changeBeat = null, hintWindowBeats = 1.0, lastPlayedMidi = null,
            )
        }
        hintChord = -2
        chordBeats = 0f
    }

    private fun stopRecording() {
        tickJob?.cancel()
        tickJob = null
        if (!_state.value.recording) return
        flushTake()
        _state.update { it.copy(recording = false) }
    }

    private fun flushTake() {
        recorder.flush(beat())?.let(::closeTake)
        takeOpen = false
    }

    private fun closeTake(take: Take) {
        takeOpen = false
        _state.update { it.copy(takes = (it.takes + take.copy(context = takeContext)).takeLast(MAX_TAKES)) }
    }

    private fun followsChords(track: Int?): Boolean = when (val role = track?.let { vibe.tracks.getOrNull(it)?.role }) {
        is TrackRole.Melodic -> role.chordFollow != ChordFollow.FIXED
        is TrackRole.Chordal -> role.chordFollow != ChordFollow.FIXED
        else -> false
    }

    private fun onViz(v: PulsarVizData) {
        val b = beat()
        val caller = _state.value.callerTrack
        if (caller != null) {
            val n = v.trackNotes.getOrElse(caller) { -1f }
            val midi = if (n < 0f) null else n.roundToInt()
            if (midi != callSounding) {
                call.lastOrNull()?.takeIf { it.endBeat == null }?.let { call[call.lastIndex] = it.copy(endBeat = b) }
                if (midi != null) call += LiveNote(midi, b, degree = degreeOf(midi))
                callSounding = midi
            }
        }
        // The engine publishes the next chord always; it is exact only while armed (the freeze), so the hint shows
        // only then. The countdown becomes an absolute clock beat.
        val hint = _state.value.armedTrack != null && v.nextChordRootDegree >= 0 && v.beatsToChordChange >= 0f
        val newChord = v.chordRootDegree != hintChord
        if (newChord) {
            hintChord = v.chordRootDegree
            chordBeats = 0f
        }
        // The poll can land late in a chord, so the largest countdown seen is the closest to its length.
        if (hint) chordBeats = max(chordBeats, v.beatsToChordChange)
        val held = _state.value.changeBeat
        val estimate = b + v.beatsToChordChange
        // Each poll is late by its staleness plus rounding, so one change keeps its earliest estimate; only a new
        // chord or a real jump re-anchors it, which keeps the state still between changes.
        val sameChange = hint && held != null && !newChord &&
            v.nextChordRootDegree == _state.value.nextChordRootDegree && abs(estimate - held) <= REANCHOR_BEATS
        _state.update {
            it.copy(
                chordRootDegree = v.chordRootDegree,
                krakenTarget = v.krakenShift,
                nextChordRootDegree = if (hint) v.nextChordRootDegree else -1,
                changeBeat = if (!hint) null else if (sameChange && held != null) min(held, estimate) else estimate,
                hintWindowBeats = when {
                    sameChange -> it.hintWindowBeats
                    // Half the chord up to 2 beats, so a bar-long chord gets an unhurried lap; a short one keeps up to 1.
                    hint && chordBeats > 0f -> min(MAX_WINDOW_BEATS, max(min(1.0, chordBeats.toDouble()), chordBeats / 2.0))
                    else -> 1.0
                },
            )
        }
        publishNotes(b, _state.value.heldMidi)
    }

    /**
     * The bar line was crossed less than a beat ago, so it sits on the last whole beat. Between takes only, and the
     * new offset is the largest at or below the old one, so the clock jumps ahead rather than back.
     */
    // The bar count arrives on a ~5 fps poll, so this runs up to ~200 ms after the bar line. Flooring the
    // beat is right while that lag stays under one beat: up to about 300 BPM. Nothing asserts it.
    private fun onBarLine() {
        if (takeOpen || held.isNotEmpty()) return
        val target = floor(rawBeat()).toInt().mod(BEATS_PER_BAR)
        barOffset -= (barOffset - target).mod(BEATS_PER_BAR)
    }

    private fun publishNotes(b: Double, heldMidi: Int?) {
        val from = b - WINDOW_BEATS
        answer.removeAll { (it.endBeat ?: b) < from }
        call.removeAll { (it.endBeat ?: b) < from }
        _state.update { it.copy(heldMidi = heldMidi, answerNotes = answer.toList(), callNotes = call.toList()) }
    }

    private fun degreeOf(midi: Int): Int? =
        (LickPitch.midiToDegree(midi, vibe, _state.value.slot) as? DegreeOrChromatic.Degree)?.degree

    /** The pitch the engine plays for a note in the vibe's own key, under the Kraken shift in effect. */
    private fun sounding(home: Int): Float =
        LickKraken.apply(home, vibe.rootNote.noteIndex, vibe.scaleType, synthEngine.pulsarVizFlow.value.krakenShift)
            .coerceIn(0, 127).toFloat()

    override fun msPerBeat(): Double {
        val pulse = pulsarFeature.musicPulseFlow.value.msPerBeat
        if (pulse > 0f) return pulse.toDouble()
        return 60_000.0 / pulsarFeature.stateFlow.value.bpm.coerceAtLeast(1f)
    }

    private fun rawBeat(): Double {
        val pulse = pulsarFeature.musicPulseFlow.value
        return clock.beat(msPerBeat(), pulse, pulse.beatPhase.toDouble().takeIf { pulse.msPerBeat > 0f })
    }

    private var lastBeat = Double.NEGATIVE_INFINITY

    private fun beat(): Double {
        lastBeat = max(lastBeat, rawBeat() - barOffset)
        return lastBeat
    }

    private fun keyed(s: LickLabUiState, v: Vibe): LickLabUiState {
        val base = LickPitch.baseMidi(v, s.slot)
        val scale = v.scaleType.name.lowercase().replace('_', ' ')
        return s.copy(
            baseMidi = base,
            keyLabel = "${pitchClassName(v.rootNote.noteIndex)} $scale · 0 = ${LickPitch.noteName(base)}",
            scale = v.scaleType.intervals().toList(),
            poolSize = v.lickRotation?.pool?.size ?: 0,
            soloTracks = v.tracks.indices.filterTo(HashSet()) { v.tracks[it].soloBehavior?.melodicOverride != null },
            tracks = v.tracks.mapIndexedNotNull { i, t ->
                if (t.role is TrackRole.Percussive) null
                else TrackChip(i, BenchMeters.CHANNEL_NAMES.getOrElse(i) { "T$i" }, chipDetail(t))
            },
        )
    }

    companion object {
        const val MAX_TAKES = 20
        // onBarLine snaps to whole beats of this bar; it assumes the bar-line poll lags under one beat.
        private const val BEATS_PER_BAR = 4
        /** +4 dB. */
        private const val BOOST_GAIN = 1.585f
        private const val KEY_VELOCITY = 0.8f
        private const val SLIDE_GLIDE = 0.3f
        private const val MAX_BEND = 2f
        private const val TICK_MS = 50L
        private const val WINDOW_BEATS = 8.0

        // A change estimate further than this from the held one is a real move (a cut, the clock re-snapping).
        private const val REANCHOR_BEATS = 0.25
        private const val MAX_WINDOW_BEATS = 2.0

        /** "BD · timpani": the engine (both slots when they differ) and the track's own name, if it has one. */
        internal fun chipDetail(t: TrackVoice): String {
            val edm = t.engineEdm.engineId.name
            val space = t.engineSpace.engineId.name
            val engines = if (edm == space) edm else "$edm/$space"
            return t.name?.let { "$engines · $it" } ?: engines
        }

        private fun monotonicMs(): () -> Long {
            val start = TimeSource.Monotonic.markNow()
            return { start.elapsedNow().inWholeMilliseconds }
        }

        fun previewFeature(state: LickLabUiState = LickLabUiState()): LickLabFeature = object : LickLabFeature {
            override val stateFlow: StateFlow<LickLabUiState> = MutableStateFlow(state)
            override val actions: LickLabActions = LickLabActions.EMPTY
        }

        @Composable
        fun feature(): LickLabFeature = synthFeature<LickLabFeature>()
    }
}
