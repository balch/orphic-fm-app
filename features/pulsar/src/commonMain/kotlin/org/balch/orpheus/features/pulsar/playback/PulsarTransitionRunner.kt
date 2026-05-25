package org.balch.orpheus.features.pulsar.playback

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.balch.orpheus.core.audio.FadeCurve
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.TransitionSpec
import org.balch.orpheus.core.audio.TransitionStyle
import org.balch.orpheus.core.plugin.PortValue
import org.balch.orpheus.core.plugin.symbols.PULSAR_URI
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol

/**
 * Runs a song-to-song transition. Each [TransitionStyle] is implemented by one
 * private suspend fun below. `applyNext` is invoked at the moment the new vibe
 * should be loaded — typically halfway through the handoff window (or at t=0
 * for [TransitionStyle.CUT]).
 *
 * The runner returns only after the transition is fully complete (including
 * the post-applyNext fade-in). Cancellation is cooperative — cancelling
 * mid-transition leaves the engine at whatever fader state was last requested;
 * the caller is responsible for any cleanup (e.g. resetting master volume).
 */
interface PulsarTransitionRunner {
    /**
     * Currently-running transition's resolved style, or null when no transition
     * is in flight. Observable for status-display purposes (e.g. showing
     * "CROSSFADE" in the Pulsar step-grid overlay during the transition).
     *
     * For RANDOM, this reflects the RESOLVED substyle — never RANDOM itself —
     * since the user wants to see what's actually happening, not the meta-choice.
     */
    val activeStyle: StateFlow<TransitionStyle?>

    suspend fun runTransition(spec: TransitionSpec, applyNext: suspend () -> Unit)
}

/**
 * Default [PulsarTransitionRunner] implementation. Drives the engine's master
 * fader / tape-stop / scratch primitives plus coroutine `delay`s to realize
 * each style's timeline.
 *
 * @param random Injectable picker for [TransitionStyle.RANDOM]; defaults to
 *   `List.random()`. Tests inject a deterministic picker.
 */
@SingleIn(AppScope::class)
@Inject
@ContributesBinding(AppScope::class)
class PulsarTransitionRunnerImpl(
    private val engine: SynthEngine,
    private val random: (List<TransitionStyle>) -> TransitionStyle = { it.random() },
) : PulsarTransitionRunner {

    private val _activeStyle = MutableStateFlow<TransitionStyle?>(null)
    override val activeStyle: StateFlow<TransitionStyle?> = _activeStyle.asStateFlow()

    override suspend fun runTransition(spec: TransitionSpec, applyNext: suspend () -> Unit) {
        if (spec.style == TransitionStyle.RANDOM) {
            runRandom(spec, applyNext)
            return
        }
        try {
            _activeStyle.value = spec.style
            when (spec.style) {
                TransitionStyle.CUT       -> runCut(applyNext)
                TransitionStyle.GAP       -> runGap(spec.handoffMs ?: DEFAULT_GAP_MS, applyNext)
                TransitionStyle.FADE      -> runFade(spec.handoffMs ?: DEFAULT_FADE_MS, applyNext)
                TransitionStyle.CROSSFADE -> runCrossfade(spec.handoffMs ?: DEFAULT_CROSSFADE_MS, applyNext)
                TransitionStyle.TAPE      -> runTape(spec.handoffMs ?: DEFAULT_TAPE_MS, applyNext)
                TransitionStyle.SCRATCH   -> runScratch(spec.handoffMs ?: DEFAULT_SCRATCH_MS, applyNext)
                TransitionStyle.FILTER    -> runFilter(spec.handoffMs ?: DEFAULT_FILTER_MS, applyNext)
                TransitionStyle.RANDOM    -> error("RANDOM handled above")
            }
        } finally {
            _activeStyle.value = null
        }
    }

    private suspend fun runCut(applyNext: suspend () -> Unit) {
        applyNext()
    }

    /** Quick declick fade-out, pause beats, silent gap, swap, fade-in, resume. */
    private suspend fun runGap(gapMs: Int, applyNext: suspend () -> Unit) {
        engine.fadeMasterVolume(0f, DECLICK_MS, FadeCurve.LINEAR)
        delay(DECLICK_MS.toLong())
        setPulsarPlaying(false)
        applyNext()
        delay(gapMs.toLong())
        setPulsarPlaying(true)
        engine.fadeMasterVolume(1f, DECLICK_MS, FadeCurve.LINEAR)
        delay(DECLICK_MS.toLong())
    }

    private suspend fun runFade(handoffMs: Int, applyNext: suspend () -> Unit) {
        val half = handoffMs / 2
        if (engine.getMasterVolume() < SMART_SKIP_VOL) {
            applyNext()
            engine.fadeMasterVolume(1f, half, FadeCurve.LINEAR)
            delay(half.toLong())
            return
        }
        engine.fadeMasterVolume(0f, half, FadeCurve.LINEAR)
        delay(half.toLong())
        applyNext()
        engine.fadeMasterVolume(1f, half, FadeCurve.LINEAR)
        delay(half.toLong())
    }

    private suspend fun runCrossfade(handoffMs: Int, applyNext: suspend () -> Unit) {
        val half = handoffMs / 2
        if (engine.getMasterVolume() < UNITY_THRESHOLD) {
            engine.fadeMasterVolume(1f, 1, FadeCurve.LINEAR)
        }
        engine.fadeMasterVolume(0.5f, half, FadeCurve.LINEAR)
        delay(half.toLong())
        applyNext()
        engine.fadeMasterVolume(1f, half, FadeCurve.LINEAR)
        delay(half.toLong())
    }

    /**
     * Tape-stop the master bus, snap the fader to 0, swap, then a fast fade-in.
     */
    private suspend fun runTape(handoffMs: Int, applyNext: suspend () -> Unit) {
        if (engine.getMasterVolume() < UNITY_THRESHOLD) {
            engine.fadeMasterVolume(1f, 1, FadeCurve.LINEAR)
        }
        engine.masterTapeStop(handoffMs)
        delay(handoffMs.toLong())
        engine.fadeMasterVolume(0f, 1, FadeCurve.LINEAR)
        applyNext()
        engine.fadeMasterVolume(1f, TAPE_FADE_IN_MS, FadeCurve.LINEAR)
        delay(TAPE_FADE_IN_MS.toLong())
    }

    /**
     * 4-stage allpass filter sweep with LFO and Leslie. The C++ MasterFilter
     * sweeps allpass stages from open to deep at the midpoint and back.
     * Like SCRATCH, it spans the transition boundary — the filter deepens
     * over the outgoing song, we swap at the midpoint, and it opens back
     * up over the beginning of the new song.
     */
    private suspend fun runFilter(handoffMs: Int, applyNext: suspend () -> Unit) {
        val half = handoffMs / 2
        if (engine.getMasterVolume() < UNITY_THRESHOLD) {
            engine.fadeMasterVolume(1f, 1, FadeCurve.LINEAR)
        }
        engine.masterFilter(handoffMs)
        engine.fadeMasterVolume(0.5f, half, FadeCurve.LINEAR)
        delay(half.toLong())
        applyNext()
        engine.fadeMasterVolume(1f, half, FadeCurve.LINEAR)
        delay(half.toLong())
    }

    /**
     * Beat-synced stutter gate over the transition boundary. The C++ MasterScratch
     * gates the audio with a beat-synced division ramp. Like CROSSFADE, the fader
     * dips to 0.5 so the stutter-gated audio blends with the reverb tail across
     * the swap.
     */
    private suspend fun runScratch(handoffMs: Int, applyNext: suspend () -> Unit) {
        val half = handoffMs / 2
        if (engine.getMasterVolume() < UNITY_THRESHOLD) {
            engine.fadeMasterVolume(1f, 1, FadeCurve.LINEAR)
        }
        engine.masterScratch(handoffMs)
        engine.fadeMasterVolume(0.5f, half, FadeCurve.LINEAR)
        delay(half.toLong())
        applyNext()
        engine.fadeMasterVolume(1f, half, FadeCurve.LINEAR)
        delay(half.toLong())
    }

    private suspend fun runRandom(spec: TransitionSpec, applyNext: suspend () -> Unit) {
        val pool = spec.randomPool.ifEmpty {
            TransitionStyle.entries.filter { it.isSafe }
        }
        val chosen = random(pool)
        runTransition(spec.copy(style = chosen), applyNext)
    }

    private fun setPulsarPlaying(playing: Boolean) {
        engine.setPluginPort(
            PULSAR_URI,
            PulsarSymbol.PLAYING.symbol,
            PortValue.IntValue(if (playing) 1 else 0),
        )
    }

    private companion object {
        const val DEFAULT_GAP_MS = 500
        const val DEFAULT_FADE_MS = 350
        const val DEFAULT_CROSSFADE_MS = 400
        const val DEFAULT_TAPE_MS = 300
        const val DEFAULT_SCRATCH_MS = 500
        const val DEFAULT_FILTER_MS = 500
        const val DECLICK_MS = 80
        const val TAPE_FADE_IN_MS = 100
        const val SMART_SKIP_VOL = 0.05f
        const val UNITY_THRESHOLD = 0.95f
    }
}
