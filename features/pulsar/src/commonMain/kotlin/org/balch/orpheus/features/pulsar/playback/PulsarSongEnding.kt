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
import kotlinx.coroutines.flow.drop
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
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.util.currentTimeMillis
import kotlin.concurrent.Volatile

/**
 * Owns the song-ending lifecycle: tracks playing-time, evaluates the random
 * trigger at each bar boundary, captures the final section once the C++
 * Markov picker rolls there, and emits [SongEndingEvent] for the advancer.
 *
 * Lives outside [PulsarFeature]/[org.balch.orpheus.features.pulsar.PulsarViewModel]
 * for the same reason [PulsarPlaybackBridge] does: injecting
 * [PlaybackController] into the ViewModel creates a Metro DI cycle.
 *
 * Eagerly instantiated via the platform DI graphs so the collectors start at
 * app launch.
 */
/**
 * Read-only event source + observable state for song-ending. Implemented by
 * [PulsarSongEnding] in production; a fake implementation is used in tests
 * that exercise [PulsarSongAdvancer] without booting the full song-ending
 * pipeline.
 */
interface SongEndingEventSource {
    val songEndingEvents: SharedFlow<SongEndingEvent>

    /**
     * Index of the section identified as the song's final section, or `-1`
     * when no outro has been triggered for the current song. Observable so the
     * UI can mark the in-flight final section with the upcoming transition
     * style (e.g., "verse 3/8 — TAPE").
     */
    val finalSectionIndex: StateFlow<Int>

    /**
     * True from the moment the outro is armed (either by the random/maxVibe
     * trigger or by a manual [armOutro] call) until the song ends and state
     * resets on the next vibe change. UI shows this as a highlighted pill so
     * the user knows their manual arm took effect.
     */
    val endingTriggered: StateFlow<Boolean>

    /**
     * The transition style that will fire when this vibe ends. For concrete
     * styles this mirrors the configured style; for `RANDOM` it's the pre-rolled
     * substyle (re-rolled once per vibe change), so the pill/suffix never show
     * "RANDOM" when a real pick has already been made. [PulsarSongAdvancer]
     * also reads this when building the spec passed to the runner, so the
     * displayed style is always the one that fires.
     */
    val resolvedTransitionStyle: StateFlow<TransitionStyle>

    /**
     * Manually arm the outro — equivalent to the auto-trigger firing. The
     * next C++ section change becomes the song's final section. No-op if
     * the outro is already armed for the current song. Doesn't require
     * `enabled = true` — manual arming is an explicit user intent.
     */
    fun armOutro()
}

@SingleIn(AppScope::class)
@Inject
@ContributesBinding(AppScope::class)
class PulsarSongEnding(
    // Lazy provider: PulsarFeature is implemented by PulsarViewModel, which
    // takes SongEndingEventSource (this class) as a constructor parameter.
    // Resolving PulsarFeature eagerly here forms a DI cycle that crashes the
    // app at launch with StackOverflowError. All uses live inside
    // scope.launch { ... } so we can defer resolution until after the graph
    // is fully built.
    pulsarFeatureProvider: () -> PulsarFeature,
    private val playbackController: PlaybackController,
    private val preferences: SongEndingPreferences,
    private val transitionPreferences: TransitionPreferences,
    private val synthController: SynthController,
    private val scope: AppCoroutineScope,
) : SongEndingEventSource {
    private val pulsarFeature: PulsarFeature by lazy(pulsarFeatureProvider)
    private val log = logging("PulsarSongEnding")

    private val _events = MutableSharedFlow<SongEndingEvent>(extraBufferCapacity = 4)
    /** Hot stream of song-ending events. [PulsarSongAdvancer] subscribes for auto-advance. */
    override val songEndingEvents: SharedFlow<SongEndingEvent> = _events.asSharedFlow()

    // Test hook for deterministic RANDOM resolution. Production uses
    // `List.random()`; tests inject a picker that returns a known element.
    internal var randomPicker: (List<TransitionStyle>) -> TransitionStyle = { it.random() }

    // Default to FADE so the panel has a sensible value to display before the
    // first vibeFlow emission (matches the pre-existing PulsarPanelActions
    // default spec).
    private val _resolvedTransitionStyle = MutableStateFlow(TransitionStyle.FADE)
    override val resolvedTransitionStyle: StateFlow<TransitionStyle> =
        _resolvedTransitionStyle.asStateFlow()

    @Volatile private var playingMillis: Long = 0L
    // Long.MIN_VALUE = "not currently ticking" (sentinel chosen so a real
    // wall-clock or test-scheduler value of 0 still counts as ticking).
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
            pulsarFeature.arrangementStateFlow.collect { state ->
                onArrangementTick(state)
            }
        }
        // Reset song-ending state on every vibe change. drop(1) skips the
        // initial value emission (no reset on first observation).
        scope.launch {
            pulsarFeature.vibeFlow
                .distinctUntilChangedBy { it.name }
                .drop(1)
                .collect { onVibeChanged() }
        }
        // Pre-roll the transition style per vibe so the UI shows the actually
        // selected style (not "RANDOM") and so [PulsarSongAdvancer] uses the
        // same pick when firing. Re-resolves on vibe change or when the user
        // edits the configured spec.
        scope.launch {
            combine(
                pulsarFeature.vibeFlow.distinctUntilChangedBy { it.name },
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
     * Resolve [TransitionSpec.style] to a concrete (non-RANDOM) style. For
     * RANDOM, picks from `spec.randomPool` or — if empty — every style with
     * `TransitionStyle.isSafe = true`. [TransitionSpec]'s init block guarantees
     * the pool never contains RANDOM, so the recursion is bounded at one step.
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

        // SongEnded fires when the final section ENDS — either because the
        // section transitioned out (normal arrangement) or because the section
        // looped back to bar 0 (terminal section with no transitions, which the
        // C++ Markov picker re-enters indefinitely).
        if (_endingTriggered.value && _finalSectionIndex.value >= 0 && !songEndedEmitted) {
            val transitionedOut = lastObservedSectionIndex == _finalSectionIndex.value
                && state.sectionIndex != _finalSectionIndex.value
            // The `lastObservedSectionIndex == finalSectionIndex` guard ensures we
            // only count a TRUE loop (bar 0 re-entry while already in the final
            // section) — not the bars-reset that happens when we first ENTER the
            // final section from another one. Without it, capturing
            // finalSectionIndex ahead of arrival (see triggerOutro) would fire a
            // premature SongEnded on the entry bar.
            val sectionLooped = state.sectionIndex == _finalSectionIndex.value
                && lastObservedSectionIndex == _finalSectionIndex.value
                && lastObservedBarsElapsed >= 0
                && state.barsElapsed < lastObservedBarsElapsed
            if (transitionedOut || sectionLooped) {
                val name = pulsarFeature.vibeFlow.value.name
                log.info { "song ended: $name (transitionedOut=$transitionedOut sectionLooped=$sectionLooped)" }
                songEndedEmitted = true
                _events.tryEmit(SongEndingEvent.SongEnded(name))
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
            val arr = pulsarFeature.vibeFlow.value.arrangement
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

        lastObservedSectionIndex = state.sectionIndex
        lastObservedBarsElapsed = state.barsElapsed
    }

    /**
     * Manual outro arm — exposed via [SongEndingEventSource.armOutro]. Routes
     * through the same internal `triggerOutro()` so the UI path and the
     * auto-trigger path produce identical state.
     */
    override fun armOutro() {
        if (_endingTriggered.value) return
        log.info { "armOutro() invoked manually" }
        triggerOutro()
    }

    private fun triggerOutro() {
        _endingTriggered.value = true
        // When the arrangement names a dedicated outro section, the C++ engine
        // pins current_section to outro_index at the next boundary and re-routes
        // there every boundary after (the outro request is sticky). Capture it as
        // the final section NOW. The change-based capture in onArrangementTick()
        // only fires on a section-index CHANGE, which never happens when we arm
        // while already in the outro section (e.g. Tremolo Tide's breakdown, whose
        // outroIndex == lastIndex) — leaving finalSectionIndex at -1 forever, so
        // SongEnded never fires and the section loops endlessly.
        val outroIndex = pulsarFeature.vibeFlow.value.arrangement?.outroIndex ?: -1
        if (outroIndex >= 0) {
            _finalSectionIndex.value = outroIndex
        }
        synthController.setPluginControl(
            PulsarSymbol.ARRANGEMENT_OUTRO_REQUEST.controlId,
            IntValue(1),
        )
        val name = pulsarFeature.vibeFlow.value.name
        log.info { "outro triggered for $name (finalSection=${_finalSectionIndex.value})" }
        _events.tryEmit(SongEndingEvent.OutroTriggered(name))
    }

    /**
     * Reset all song-ending state. Driven internally by the [pulsarFeature.vibeFlow]
     * collector so a vibe switch automatically clears playing-time, the trigger flag,
     * and the C++ outro request.
     *
     * Volume management during transitions is owned exclusively by
     * [PulsarTransitionRunner] — both the auto-advance path
     * ([PulsarSongAdvancer]) and the manual-skip path ([PulsarSkipHandler])
     * route through it. This method only resets bookkeeping.
     */
    private fun onVibeChanged() {
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
     * Live playing-time read: persisted accrual plus the in-flight delta if
     * the engine is currently in [PlaybackState.Playing]. Computed on read so
     * we don't need a periodic refresh coroutine.
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
