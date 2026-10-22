package org.balch.orpheus.features.pulsar.mixer

import androidx.compose.runtime.Immutable
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.core.plugin.PortValue.FloatValue
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import org.balch.orpheus.features.pulsar.PulsarFeature

/**
 * Per-track mute, solo and level for the debug TRACKS view. Solo works like a mixing desk:
 * any soloed track silences every track that is not soloed, and mute beats solo.
 */
@Immutable
data class TrackTuning(
    val muted: List<Boolean> = List(TRACK_COUNT) { false },
    val soloed: List<Boolean> = List(TRACK_COUNT) { false },
    val levels: List<Float> = List(TRACK_COUNT) { UNITY },
) {
    val anySolo: Boolean get() = soloed.any { it }

    /** True once anything differs from the untouched mix. */
    val isTouched: Boolean get() = this != TrackTuning()

    /** The multiplier the engine applies to track [t]'s final volume. */
    fun gain(t: Int): Float = if (muted[t] || (anySolo && !soloed[t])) 0f else levels[t]

    fun gains(): List<Float> = List(TRACK_COUNT) { gain(it) }

    fun toggleMute(t: Int) = copy(muted = muted.toggled(t))

    fun toggleSolo(t: Int) = copy(soloed = soloed.toggled(t))

    /** True when no track in [tracks] makes sound, by mute or by another track's solo. */
    fun isBusSilent(tracks: IntArray): Boolean = tracks.isNotEmpty() && tracks.all { gain(it) == 0f }

    fun withLevel(t: Int, level: Float) =
        copy(levels = levels.toMutableList().also { it[t] = level.coerceIn(0f, MAX_LEVEL) })

    companion object {
        const val TRACK_COUNT = 8
        const val UNITY = 1f
        const val MAX_LEVEL = 1.5f

        private fun List<Boolean>.toggled(t: Int) = toMutableList().also { it[t] = !it[t] }
    }
}

/**
 * Where the TRACKS view's edits go and what it shows. The core edition writes audition gain itself
 * ([DirectTrackGainSink]); the AI edition replaces it so the edits land in the shared mix controller.
 */
interface TrackGainSink {
    val state: Flow<TrackTuning>
    fun toggleMute(t: Int)
    fun toggleSolo(t: Int)
    /** [level] is linear gain, 0..[TrackTuning.MAX_LEVEL]. */
    fun setLevel(t: Int, level: Float)
    fun reset()
}

/** The default sink: a [TrackTuningController] that writes `track_N_audition_gain` directly. */
@Inject
@SingleIn(FeatureScope::class)
@ContributesBinding(FeatureScope::class)
class DirectTrackGainSink(
    synthController: SynthController,
    pulsarFeature: PulsarFeature,
    scope: FeatureCoroutineScope,
) : TrackGainSink by TrackTuningController(synthController, pulsarFeature.vibeLoadCount, scope)

/**
 * Holds the session's [TrackTuning] and writes each track's gain to `track_N_audition_gain`.
 * Never touches `track_N_mute`, which PulsarFeature owns. Every change to [vibeLoads] clears the
 * state and re-pushes unity, so a tap that raced the load's own engine reset cannot leave a track silent.
 */
class TrackTuningController(
    private val synthController: SynthController,
    vibeLoads: StateFlow<Int>,
    scope: CoroutineScope,
) : TrackGainSink {

    private val gainIds = List(TrackTuning.TRACK_COUNT) {
        PulsarSymbol.entries[PulsarSymbol.TRACK_0_AUDITION_GAIN.ordinal + it].controlId
    }

    private val _state = MutableStateFlow(TrackTuning())
    override val state: StateFlow<TrackTuning> = _state.asStateFlow()

    init {
        // The plugin outlives this controller, so seat the engine on the untouched mix.
        for (t in 0 until TrackTuning.TRACK_COUNT) push(t, TrackTuning.UNITY)
        // Compare against the count at construction so a load before the collector starts still counts.
        var seen = vibeLoads.value
        scope.launch {
            vibeLoads.collect {
                if (it != seen) {
                    seen = it
                    _state.value = TrackTuning()
                    // Idempotent with PulsarFeature's own write, but covers a tap that landed in between.
                    for (t in 0 until TrackTuning.TRACK_COUNT) push(t, TrackTuning.UNITY)
                }
            }
        }
    }

    override fun toggleMute(t: Int) = update { it.toggleMute(t) }

    override fun toggleSolo(t: Int) = update { it.toggleSolo(t) }

    override fun setLevel(t: Int, level: Float) = update { it.withLevel(t, level) }

    override fun reset() = update { TrackTuning() }

    private inline fun update(change: (TrackTuning) -> TrackTuning) {
        val old = _state.value
        val new = change(old)
        _state.value = new
        for (t in 0 until TrackTuning.TRACK_COUNT) {
            val g = new.gain(t)
            if (g != old.gain(t)) push(t, g)
        }
    }

    private fun push(t: Int, gain: Float) {
        synthController.setPluginControl(gainIds[t], FloatValue(gain))
    }
}
