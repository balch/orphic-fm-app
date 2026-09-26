package org.balch.orpheus.features.pulsar

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.coroutines.DispatcherProvider
import org.balch.orpheus.core.media.PlaybackProgress
import org.balch.orpheus.core.plugin.viz.PulsarVizData
import kotlin.time.TimeSource

/** What the progress wave draws from: the song so far, a beat at a time, and the music right now. */
interface MusicPulseSource {
    val songStory: StateFlow<SongStory>
    val pulse: StateFlow<MusicPulse>

    /**
     * Keeps [pulse] current until the handle is disposed. A live wave reads the pulse by value in
     * its frame loop rather than collecting it, so it holds one of these instead; a collector keeps
     * it current too. With neither, as on TV hardware, the recorder skips building the pulse and
     * keeps only the story.
     */
    fun holdPulse(): DisposableHandle = NoHold

    companion object {
        private val NoHold = DisposableHandle { }

        /** Nothing playing, ever: previews, fakes and ViewModel tests. */
        val Silent: MusicPulseSource = object : MusicPulseSource {
            override val songStory: StateFlow<SongStory> = MutableStateFlow(SongStory.EMPTY)
            override val pulse: StateFlow<MusicPulse> = MutableStateFlow(MusicPulse.SILENT)
        }
    }
}

/**
 * Records the current song's story from the Pulsar viz samples, each beat stamped with its place in
 * the song from [PulsarSession.progressFlow]. The samples only flow while the UI is visible, so a
 * backgrounded stretch has no beats and draws flat where it was. Built with [PulsarViewModel], not a
 * startup root; it starts over on every [PulsarSession.songGenerationFlow] bump.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MusicPulseRecorder internal constructor(
    synthEngine: SynthEngine,
    pulsarSession: PulsarSession,
    scope: AppCoroutineScope,
    dispatcherProvider: DispatcherProvider,
    // Stamps each sample; tests pass a TestTimeSource so a slow machine never reads as a stalled viz.
    timeSource: TimeSource,
) : MusicPulseSource {

    @Inject constructor(
        synthEngine: SynthEngine,
        pulsarSession: PulsarSession,
        scope: AppCoroutineScope,
        dispatcherProvider: DispatcherProvider,
    ) : this(synthEngine, pulsarSession, scope, dispatcherProvider, TimeSource.Monotonic)

    private val _songStory = MutableStateFlow(SongStory.EMPTY)
    override val songStory: StateFlow<SongStory> = _songStory.asStateFlow()

    private val _pulse = MutableStateFlow(MusicPulse.SILENT)
    override val pulse: StateFlow<MusicPulse> = _pulse.asStateFlow()

    private val pulseHolders = MutableStateFlow(0)

    override fun holdPulse(): DisposableHandle {
        pulseHolders.update { it + 1 }
        var held = true
        return DisposableHandle {
            if (held) {
                held = false
                pulseHolders.update { it - 1 }
            }
        }
    }

    init {
        scope.launch(dispatcherProvider.default) {
            val accumulator = BeatAccumulator()
            val clock = timeSource.markNow()
            pulsarSession.songGenerationFlow.collectLatest {
                accumulator.reset()
                _songStory.value = SongStory.EMPTY
                _pulse.value = MusicPulse.SILENT
                // One coroutine for both, so the accumulator is never shared. The replayed sample and
                // position are the old song's last ones.
                merge(synthEngine.pulsarVizFlow.drop(1), pulsarSession.progressFlow.drop(1)).collect { sample ->
                    val nowMs = clock.elapsedNow().inWholeMilliseconds
                    if (sample is PulsarVizData) {
                        // A collector of [pulse] reads it too, so it counts as a holder.
                        val live = pulseHolders.value > 0 || _pulse.subscriptionCount.value > 0
                        val beat = accumulator.add(sample, nowMs, buildPulse = live)
                        if (beat != null && beat.positionMs >= 0) {
                            _songStory.update { story ->
                                if (story.beats.size >= SongStory.MAX_BEATS) story else story.copy(beats = story.beats.appended(beat))
                            }
                        }
                        if (live) _pulse.value = accumulator.pulse
                    } else {
                        val progress = sample as PlaybackProgress?
                        accumulator.onProgress(progress?.positionMs, nowMs)
                        if (progress != null) _songStory.update { it.copy(elapsedMs = progress.positionMs) }
                    }
                }
            }
        }
    }
}

/**
 * These beats and one more, sharing the song so far rather than copying it: a story holds its beats
 * in a persistent list, so a beat costs the same at the cap as at the start.
 */
private fun List<StoryBeat>.appended(beat: StoryBeat): List<StoryBeat> =
    (this as? PersistentList<StoryBeat> ?: toPersistentList()).adding(beat)
