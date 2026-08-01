package org.balch.orpheus.features.pulsar.playback

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.TransitionSpec
import org.balch.orpheus.core.audio.TransitionStyle
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.playback.PlaybackController
import org.balch.orpheus.core.playback.PlaybackState
import org.balch.orpheus.core.plugin.PortValue.IntValue
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import org.balch.orpheus.core.plugin.viz.PulsarArrangementState
import org.balch.orpheus.features.pulsar.PulsarSession
import org.balch.orpheus.util.currentTimeMillis
import kotlin.concurrent.Volatile

/**
 * Read-only event source + observable state for song-ending. [PulsarSongEnding] implements it
 * in production; tests exercising [PulsarSongAdvancer] use a fake.
 */
interface SongEndingEventSource {
    val songEndingEvents: SharedFlow<SongEndingEvent>

    /**
     * The song's final section, or `-1` when no outro is armed. Observable so the UI can
     * label the in-flight final section with its transition style ("verse 3/8 — TAPE").
     */
    val finalSectionIndex: StateFlow<Int>

    /**
     * True from the moment the outro is armed until the song ends and state resets on the
     * next vibe. Shown as a highlighted pill so a manual arm visibly takes effect.
     */
    val endingTriggered: StateFlow<Boolean>

    /**
     * The style that will fire when this vibe ends. For `RANDOM` this is the pre-rolled
     * substyle, re-rolled once per vibe change, so the UI never displays "RANDOM" after a
     * real pick. [PulsarSongAdvancer] reads it too, so the shown style is the one that fires.
     */
    val resolvedTransitionStyle: StateFlow<TransitionStyle>

    /**
     * Manually arm the outro, equivalent to the auto-trigger firing. No-op if already armed.
     * Ignores `enabled` — a manual arm is explicit user intent.
     */
    fun armOutro()

    /**
     * A new song starts now. Resets playing-time, armed/final-section state, and the sticky
     * C++ `arrangement_outro_request` port.
     *
     * MUST be called from every `applyVibe()`, including re-applies of the playing vibe:
     * `vibeFlow` is a StateFlow and emits nothing on an equal value, so it cannot drive this,
     * and skipping it strands the song in its outro forever. Call BEFORE pushing the
     * arrangement so the cleared port precedes the `arrangement_generation` fence.
     */
    fun onVibeApplied()
}

/**
 * Owns the song-ending lifecycle: tracks playing-time, evaluates the random trigger at each bar
 * boundary, captures the final section once the C++ Markov picker rolls there, and emits
 * [SongEndingEvent] for the advancer. Eagerly instantiated so collectors start at app launch.
 *
 * Lives outside [org.balch.orpheus.features.pulsar.PulsarViewModel] for the same reason
 * [PulsarPlaybackBridge] does: injecting [PlaybackController] into the ViewModel creates a Metro
 * cycle. Reads vibe/arrangement state through [PulsarSession] instead.
 */

@SingleIn(AppScope::class)
@Inject
@ContributesBinding(AppScope::class)
class PulsarSongEnding(
    // Eager, not a () -> PulsarFeature provider: PulsarSession depends on nothing that
    // depends back on a feature, so there is no cycle left to defer around.
    private val pulsarSession: PulsarSession,
    private val playbackController: PlaybackController,
    private val preferences: SongEndingPreferences,
    private val transitionPreferences: TransitionPreferences,
    private val synthController: SynthController,
    private val scope: AppCoroutineScope,
) : SongEndingEventSource {
    private val log = logging("PulsarSongEnding")

    private val _events = MutableSharedFlow<SongEndingEvent>(extraBufferCapacity = 4)
    /** Hot stream of song-ending events. [PulsarSongAdvancer] subscribes for auto-advance. */
    override val songEndingEvents: SharedFlow<SongEndingEvent> = _events.asSharedFlow()

    // Test hook for deterministic RANDOM resolution.
    internal var randomPicker: (List<TransitionStyle>) -> TransitionStyle = { it.random() }

    // FADE so the panel shows something sane before the first vibe emission, matching
    // PulsarPanelActions' default spec.
    private val _resolvedTransitionStyle = MutableStateFlow(TransitionStyle.FADE)
    override val resolvedTransitionStyle: StateFlow<TransitionStyle> =
        _resolvedTransitionStyle.asStateFlow()

    @Volatile private var playingMillis: Long = 0L
    @Volatile private var lastTickMillis: Long = NOT_TICKING
    private val _endingTriggered = MutableStateFlow(false)
    override val endingTriggered: StateFlow<Boolean> = _endingTriggered.asStateFlow()
    private val _finalSectionIndex = MutableStateFlow(-1)
    override val finalSectionIndex: StateFlow<Int> = _finalSectionIndex.asStateFlow()
    @Volatile private var lastObservedSectionIndex: Int = -1
    @Volatile private var lastObservedBarsElapsed: Int = -1
    @Volatile private var songEndedEmitted: Boolean = false

    // Hooks for tests; production paths use real wall-clock + Random.
    internal var nowMillis: () -> Long = { currentTimeMillis() }
    internal var random: (Float, Float) -> Float =
        { lo, hi -> lo + kotlin.random.Random.nextFloat() * (hi - lo) }

    init {
        scope.launch {
            playbackController.state.collect { state ->
                val now = nowMillis()
                if (state == PlaybackState.Playing) {
                    lastTickMillis = now
                } else {
                    if (lastTickMillis != NOT_TICKING) {
                        playingMillis += (now - lastTickMillis)
                        lastTickMillis = NOT_TICKING
                    }
                }
            }
        }
        scope.launch {
            pulsarSession.arrangementStateFlow.collect { state ->
                onArrangementTick(state)
            }
        }
        // Pre-roll the transition style so the UI and PulsarSongAdvancer show the same pick,
        // never "RANDOM". Keyed on vibe NAME, not the play-through (per-song state resets via
        // onVibeApplied): the pick belongs to the vibe, so an engine recreation must not re-roll it.
        scope.launch {
            combine(
                pulsarSession.vibeFlow.filterNotNull().distinctUntilChangedBy { it.name },
                transitionPreferences.defaultFlow,
            ) { vibe, defaultSpec ->
                val spec = vibe.arrangement?.transitionOut ?: defaultSpec
                resolveStyle(spec)
            }.collect { resolved ->
                _resolvedTransitionStyle.value = resolved
            }
        }
    }

    /**
     * Resolve [TransitionSpec.style] to a concrete style. RANDOM picks from `spec.randomPool`,
     * or every `isSafe` style when empty. [TransitionSpec]'s init guarantees the pool excludes
     * RANDOM, so this bottoms out in one step.
     */
    private fun resolveStyle(spec: TransitionSpec): TransitionStyle {
        if (spec.style != TransitionStyle.RANDOM) return spec.style
        val pool = spec.randomPool.ifEmpty {
            TransitionStyle.entries.filter { it.isSafe }
        }
        return randomPicker(pool)
    }

    private fun onArrangementTick(state: PulsarArrangementState) {
        if (state.sectionIndex < 0) return  // unknown / no arrangement
        // No vibe pushed yet — no song to end. Snapshotted so one tick reads one vibe.
        val vibe = pulsarSession.vibeFlow.value ?: return

        // SongEnded fires when the final section ENDS: it either transitioned out, or looped
        // back to bar 0 (a terminal section, which the C++ Markov picker re-enters forever).
        if (_endingTriggered.value && _finalSectionIndex.value >= 0) {
            val transitionedOut = lastObservedSectionIndex == _finalSectionIndex.value
                && state.sectionIndex != _finalSectionIndex.value
            // The lastObservedSectionIndex guard counts only a TRUE loop, not the bars-reset
            // on first ENTERING the final section. Without it, capturing finalSectionIndex
            // ahead of arrival (see triggerOutro) fires a premature SongEnded on the entry bar.
            val sectionLooped = state.sectionIndex == _finalSectionIndex.value
                && lastObservedSectionIndex == _finalSectionIndex.value
                && lastObservedBarsElapsed >= 0
                && state.barsElapsed < lastObservedBarsElapsed
            if (transitionedOut || sectionLooped) {
                val name = vibe.name
                // Recovery net: both conditions are edge-triggered, so a second edge means the
                // last SongEnded never produced a vibe change, and the C++ outro pin makes
                // re-emitting the only escape. The advancer drops stale re-emits by vibe name.
                if (songEndedEmitted) {
                    log.warn { "still in the final section after SongEnded: $name — re-emitting" }
                } else {
                    log.info { "song ended: $name (transitionedOut=$transitionedOut sectionLooped=$sectionLooped)" }
                }
                // Latch only on a successful emit, so a dropped SongEnded retries on the next
                // section loop instead of stranding the song in its outro forever.
                if (_events.tryEmit(SongEndingEvent.SongEnded(name))) {
                    songEndedEmitted = true
                } else {
                    log.warn { "SongEnded emit dropped for $name; will retry on next loop" }
                }
            }
        }

        // Final-section detection: the first sectionIndex change after we
        // triggered is the final section.
        if (_endingTriggered.value && _finalSectionIndex.value < 0
            && lastObservedSectionIndex >= 0
            && state.sectionIndex != lastObservedSectionIndex) {
            _finalSectionIndex.value = state.sectionIndex
            log.info { "final section captured: ${_finalSectionIndex.value}" }
        }

        // Trigger evaluation (only fires once per song). Reads min/max from the
        // active arrangement; vibes without an arrangement never end.
        if (!_endingTriggered.value && preferences.enabledFlow.value) {
            val arr = vibe.arrangement
            if (arr != null) {
                val minS = arr.minVibeSeconds
                val maxS = arr.maxVibeSeconds
                val playingS = (playingMillisLive() / 1000L).toInt()
                if (playingS >= minS) {
                    val span = (maxS - minS).coerceAtLeast(1)
                    val p = ((playingS - minS).toFloat() / span.toFloat()).coerceIn(0f, 1f)
                    val forced = playingS >= maxS
                    val rolled = random(0f, 1f) < p
                    if (forced || rolled) {
                        triggerOutro()
                    }
                }
            }
        }

        // Structural terminal-outro safety net. A vibe can mark its outro section terminal
        // (no transitions, e.g. TechnoWobble's `drift`); if the Markov walk reaches it before
        // any arm, C++ self-loops it forever and the loop-back detection above never wakes
        // (it is gated on _endingTriggered). Arming here lets sectionLooped end the next loop.
        //
        // Ignores enabledFlow: a structurally terminal section is a hard end of the
        // arrangement, not the optional timed auto-end, and detection alone cannot un-trap the
        // engine. Gated on minVibeSeconds so an early walk doesn't cut the song short.
        if (!_endingTriggered.value) {
            val arr = vibe.arrangement
            val outroIdx = arr?.outroIndex ?: -1
            val outroSection = arr?.sections?.getOrNull(outroIdx)
            val playingS = (playingMillisLive() / 1000L).toInt()
            if (outroIdx >= 0 && state.sectionIndex == outroIdx &&
                outroSection != null && outroSection.transitions.isEmpty() &&
                playingS >= arr.minVibeSeconds
            ) {
                log.info { "reached terminal outro section $outroIdx unarmed at ${playingS}s — auto-arming" }
                triggerOutro()
            }
        }

        lastObservedSectionIndex = state.sectionIndex
        lastObservedBarsElapsed = state.barsElapsed
    }

    /** Routes through the same `triggerOutro()` as the auto-trigger, for identical state. */
    override fun armOutro() {
        if (_endingTriggered.value) return
        log.info { "armOutro() invoked manually" }
        triggerOutro()
    }

    private fun triggerOutro() {
        // Reachable manually via armOutro(); with no vibe loaded there is no song to end.
        val vibe = pulsarSession.vibeFlow.value ?: run {
            log.warn { "outro requested before any vibe was applied — ignoring" }
            return
        }
        _endingTriggered.value = true
        // C++ pins current_section to outro_index once armed (the request is sticky), so
        // capture the final section NOW. onArrangementTick's capture needs a section-index
        // CHANGE, which never comes when we arm while already in the outro section (e.g.
        // Tremolo Tide's breakdown, outroIndex == lastIndex), stranding it at -1 forever.
        val outroIndex = vibe.arrangement?.outroIndex ?: -1
        if (outroIndex >= 0) {
            _finalSectionIndex.value = outroIndex
        }
        synthController.setPluginControl(
            PulsarSymbol.ARRANGEMENT_OUTRO_REQUEST.controlId,
            IntValue(1),
        )
        val name = vibe.name
        log.info { "outro triggered for $name (finalSection=${_finalSectionIndex.value})" }
        _events.tryEmit(SongEndingEvent.OutroTriggered(name))
    }

    /**
     * Reset all song-ending state. Bookkeeping only: volume during transitions
     * is owned exclusively by [PulsarTransitionRunner].
     */
    override fun onVibeApplied() {
        playingMillis = 0L
        lastTickMillis = if (playbackController.state.value == PlaybackState.Playing) {
            nowMillis()
        } else {
            NOT_TICKING
        }
        _endingTriggered.value = false
        _finalSectionIndex.value = -1
        lastObservedSectionIndex = -1
        lastObservedBarsElapsed = -1
        songEndedEmitted = false
        // Defensive: tell C++ to drop any stale outro request from the previous vibe.
        synthController.setPluginControl(
            PulsarSymbol.ARRANGEMENT_OUTRO_REQUEST.controlId,
            IntValue(0),
        )
    }

    /**
     * Accrued playing-time plus the in-flight delta when playing. Computed on read so no
     * periodic refresh coroutine is needed.
     */
    private fun playingMillisLive(): Long {
        val base = playingMillis
        val tick = lastTickMillis
        return if (tick != NOT_TICKING) base + (nowMillis() - tick) else base
    }

    private companion object {
        /** Sentinel for "not currently accruing"; distinct from any real timestamp. */
        const val NOT_TICKING: Long = Long.MIN_VALUE
    }

    // ─── Test-only accessors ────────────────────────────────────────────────
    internal val playingMillisForTest: Long get() = playingMillisLive()
    internal val endingTriggeredForTest: Boolean get() = _endingTriggered.value
    internal val finalSectionIndexForTest: Int get() = _finalSectionIndex.value
}
