package org.balch.orpheus.djapp.ai

import com.diamondedge.logging.logging
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.log10
import kotlin.math.pow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.di.FeatureScope
import org.balch.orpheus.core.features.FeatureCoroutineScope
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.bench.BenchLayers
import org.balch.orpheus.features.pulsar.bench.MixBenchController
import org.balch.orpheus.features.pulsar.bench.SILENT_DB
import org.balch.orpheus.features.pulsar.bench.Scope
import org.balch.orpheus.features.pulsar.bench.auditionGain
import org.balch.orpheus.features.pulsar.mixer.DirectTrackGainSink
import org.balch.orpheus.features.pulsar.mixer.TrackGainSink
import org.balch.orpheus.features.pulsar.mixer.TrackTuning

/**
 * AI edition: the TRACKS view shares the agent's [MixBenchController], so its edits are layer edits.
 * Edits run off Main, so the app's exit (which blocks Main on the controller's stop) never waits behind one.
 */
@Inject
@SingleIn(FeatureScope::class)
@ContributesBinding(FeatureScope::class, replaces = [DirectTrackGainSink::class])
class AiTrackGainSink(
    controllerProvider: () -> MixBenchController,
    pulsarFeature: PulsarFeature,
    scope: FeatureCoroutineScope,
    dispatchers: DispatcherProvider,
) : TrackGainSink by MixBenchTrackGainSink(controllerProvider, pulsarFeature, scope, dispatchers.default)

/**
 * Turns TRACKS taps into [MixBenchController] edits, in order, and shows the controller's levels, mutes and solos
 * as heard in the playing section. The controller is resolved on the first edit or collection, never in a constructor.
 * One consumer on [editContext] runs the edits, so they land in tap order.
 */
class MixBenchTrackGainSink(
    private val controllerProvider: () -> MixBenchController,
    private val pulsar: PulsarFeature,
    scope: CoroutineScope,
    editContext: CoroutineContext = EmptyCoroutineContext,
) : TrackGainSink {
    private val log = logging("MixBenchTrackGainSink")
    private val edits = Channel<suspend MixBenchController.() -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch(editContext) {
            val controller by lazy(controllerProvider)
            for (edit in edits) {
                // One bad edit must not end the consumer: the scope is a SupervisorJob, so later taps would drop silently.
                try {
                    controller.edit()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error(e) { "TRACKS edit failed" }
                }
            }
        }
    }

    override val state: Flow<TrackTuning> = flow {
        val c = controllerProvider()
        val sections = pulsar.arrangementStateFlow.map { it.sectionIndex }.distinctUntilChanged()
        emitAll(combine(c.layers, c.muted, c.soloed, sections, ::tuning))
    }.distinctUntilChanged()

    override fun toggleMute(t: Int) = send { toggleMute(t) }

    override fun toggleSolo(t: Int) = send { toggleSolo(t) }

    // TRACKS shows no scope, so its moves are always whole-vibe, whatever scope the agent left set.
    override fun setLevel(t: Int, level: Float) = send { setHeardLevelDb(t, levelToDb(level), Scope.VIBE) }

    /** Clears levels, mutes and solos only; the agent's pans, sends and macros stay. */
    override fun reset() = send { resetLevels() }

    private fun send(edit: suspend MixBenchController.() -> Unit) {
        edits.trySend(edit)
    }

    private fun tuning(layers: BenchLayers, muted: Set<Int>, soloed: Set<Int>, section: Int) = TrackTuning(
        muted = List(TrackTuning.TRACK_COUNT) { it in muted },
        soloed = List(TrackTuning.TRACK_COUNT) { it in soloed },
        levels = List(TrackTuning.TRACK_COUNT) { layers.auditionGain(it, section) },
    )

    companion object {
        private val SILENT_GAIN = 10f.pow(SILENT_DB / 20f)
        private val MAX_DB = 20f * log10(TrackTuning.MAX_LEVEL)

        /** A closed fader lands on [SILENT_DB], which plays as exact silence and keeps the layers finite. */
        fun levelToDb(level: Float): Float =
            if (level <= SILENT_GAIN) SILENT_DB else (20f * log10(level)).coerceIn(SILENT_DB, MAX_DB)
    }
}
