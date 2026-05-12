package org.balch.orpheus.features.pulsar.playback

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.MasterVolumeRamp
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.TimerFadeStatusProvider
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
import kotlin.math.ceil

/**
 * Owns the song-ending lifecycle: tracks playing-time, evaluates the
 * trigger ramp at each bar boundary, schedules the master-volume fade,
 * and emits [SongEndingEvent].
 *
 * Lives outside [PulsarFeature]/[org.balch.orpheus.features.pulsar.PulsarViewModel]
 * for the same reason [PulsarPlaybackBridge] does: injecting
 * [PlaybackController] into the ViewModel creates a Metro DI cycle
 * (PulsarViewModel → PlaybackController → MetadataProducer →
 *  PulsarMetadataProducer → PulsarFeature → PulsarViewModel).
 *
 * Eagerly instantiated via the platform DI graphs so the collectors
 * start at app launch.
 */
/**
 * Read-only event source for song-ending events. Implemented by
 * [PulsarSongEnding] in production; a fake implementation is used in tests
 * that exercise [PulsarSongAdvancer] without booting the full song-ending
 * pipeline.
 */
interface SongEndingEventSource {
    val songEndingEvents: SharedFlow<SongEndingEvent>
}

@SingleIn(AppScope::class)
@Inject
@ContributesBinding(AppScope::class)
class PulsarSongEnding(
    private val pulsarFeature: PulsarFeature,
    private val playbackController: PlaybackController,
    private val preferences: SongEndingPreferences,
    private val synthController: SynthController,
    private val synthEngine: SynthEngine,
    private val ramp: MasterVolumeRamp,
    private val timerFadeStatusProvider: TimerFadeStatusProvider,
    private val scope: AppCoroutineScope,
) : SongEndingEventSource {
    private val log = logging("PulsarSongEnding")

    private val _events = MutableSharedFlow<SongEndingEvent>(extraBufferCapacity = 4)
    /** Hot stream of song-ending events. [PulsarSongAdvancer] subscribes for auto-advance. */
    override val songEndingEvents: SharedFlow<SongEndingEvent> = _events.asSharedFlow()

    @Volatile private var playingMillis: Long = 0L
    // Long.MIN_VALUE = "not currently ticking" (sentinel chosen so a real
    // wall-clock or test-scheduler value of 0 still counts as ticking).
    @Volatile private var lastTickMillis: Long = NOT_TICKING
    @Volatile private var endingTriggered: Boolean = false
    @Volatile private var finalSectionIndex: Int = -1
    @Volatile private var lastObservedSectionIndex: Int = -1
    @Volatile private var lastObservedBarsElapsed: Int = -1
    @Volatile private var rampStarted: Boolean = false
    @Volatile private var songEndedEmitted: Boolean = false

    // Tracks the in-flight fade-out so a vibe change (manual skip OR auto-
    // advance) can interrupt it. Without cancellation the ramp keeps writing
    // decreasing volume into the next vibe — fading it in to silence.
    @Volatile private var rampJob: Job? = null

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
    }

    private fun onArrangementTick(state: PulsarArrangementState) {
        if (state.sectionIndex < 0) return  // unknown / no arrangement

        // SongEnded fires when the final section ENDS — either because the
        // section transitioned out (normal arrangement) or because the section
        // looped back to bar 0 (terminal section with no transitions, which the
        // C++ Markov picker re-enters indefinitely).
        if (endingTriggered && finalSectionIndex >= 0 && !songEndedEmitted) {
            val transitionedOut = lastObservedSectionIndex == finalSectionIndex
                && state.sectionIndex != finalSectionIndex
            val sectionLooped = state.sectionIndex == finalSectionIndex
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
        if (endingTriggered && finalSectionIndex < 0
            && lastObservedSectionIndex >= 0
            && state.sectionIndex != lastObservedSectionIndex) {
            finalSectionIndex = state.sectionIndex
            log.info { "final section captured: $finalSectionIndex" }
        }

        // Ramp scheduling: when we're inside the final section and within the
        // bar that contains the fade start point, kick off the master-volume
        // fade. rampBars is fractional (e.g. 0.25 = quarter-bar fade). Cap so
        // the outro plays at full volume for at least 2 bars before the fade
        // starts — short outros that can't honor that floor still get at
        // least one bar of fade.
        if (endingTriggered
            && finalSectionIndex >= 0
            && state.sectionIndex == finalSectionIndex
            && !rampStarted) {
            val endStyle = pulsarFeature.vibeFlow.value.arrangement?.endStyle
            val rampBars = (endStyle?.rampBars ?: 0f)
                .coerceAtMost((state.barsTotal - 2).coerceAtLeast(1).toFloat())
            if (rampBars > 0f && endStyle != null) {
                val barsRemaining = state.barsTotal - state.barsElapsed
                // Fade lives in the LAST ceil(rampBars) bars of the outro.
                val triggerAt = ceil(rampBars.toDouble()).toInt()
                if (barsRemaining <= triggerAt && !timerFadeStatusProvider.isFading()) {
                    rampStarted = true
                    val vibe = pulsarFeature.vibeFlow.value
                    // One C++ "bar" = one full pattern wrap = `stepCount` 16th-notes.
                    // stepCount=16 → one musical bar; stepCount=32 → two musical bars.
                    val barDurationMs = 60_000.0 * vibe.stepCount / (vibe.bpm.toDouble() * 4.0)
                    val rampMs = (rampBars.toDouble() * barDurationMs).toLong()
                        .coerceAtLeast(100L)
                    rampJob = scope.launch {
                        ramp.rampMasterVolumeTo(
                            target = 0f,
                            durationMs = rampMs,
                            curve = endStyle.curve,
                        )
                    }
                    log.info { "master ramp started: bars=$rampBars duration=${rampMs}ms" }
                }
            }
        }

        // Trigger evaluation (only fires once per song). Reads min/max/endStyle
        // from the active arrangement; vibes without an arrangement never end.
        if (!endingTriggered && preferences.enabledFlow.value) {
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

    private fun triggerOutro() {
        endingTriggered = true
        synthController.setPluginControl(
            PulsarSymbol.ARRANGEMENT_OUTRO_REQUEST.controlId,
            IntValue(1),
        )
        val name = pulsarFeature.vibeFlow.value.name
        log.info { "outro triggered for $name" }
        _events.tryEmit(SongEndingEvent.OutroTriggered(name))
    }

    /**
     * Reset all song-ending state. Driven internally by the [pulsarFeature.vibeFlow]
     * collector so a vibe switch automatically clears playing-time, the trigger flag,
     * and the C++ outro request.
     */
    private fun onVibeChanged() {
        // If we're mid-fade when the vibe switches, kill the in-flight ramp
        // and restore master volume so the new vibe isn't silenced. Guarded
        // on rampStarted so non-outro vibe changes don't clobber a user-set
        // master volume. PulsarSongAdvancer already restores volume on its
        // auto-advance path; doing it here too is idempotent there but the
        // only safety net for manual skip during outro.
        if (rampStarted) {
            rampJob?.cancel()
            synthEngine.setMasterVolume(1.0f)
        }
        rampJob = null
        playingMillis = 0L
        lastTickMillis = if (playbackController.state.value == PlaybackState.Playing) {
            nowMillis()
        } else {
            NOT_TICKING
        }
        endingTriggered = false
        finalSectionIndex = -1
        lastObservedSectionIndex = -1
        lastObservedBarsElapsed = -1
        rampStarted = false
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
    internal val endingTriggeredForTest: Boolean get() = endingTriggered
    internal val finalSectionIndexForTest: Int get() = finalSectionIndex
    internal val rampStartedForTest: Boolean get() = rampStarted
}
