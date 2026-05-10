package org.balch.orpheus.features.pulsar.playback

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.core.plugin.PortValue.IntValue
import org.balch.orpheus.core.plugin.symbols.PulsarSymbol
import org.balch.orpheus.features.pulsar.PulsarFeature
import kotlin.concurrent.Volatile

/**
 * Default behavior for [SongEndingEvent.SongEnded]: hold a short silent
 * inter-track gap, then advance to the next vibe in [PulsarFeature.vibeList]
 * (wrapping). Hosts that want custom advance logic can disable this and
 * listen to [PulsarSongEnding.songEndingEvents] themselves.
 *
 * The gap matches the Red Book CD pre-gap (2 s) — long enough for an outro
 * tail to fully decay and the listener to register the song-end, short
 * enough to keep the album feeling continuous.
 */
@SingleIn(AppScope::class)
@Inject
class PulsarSongAdvancer(
    private val pulsarFeature: PulsarFeature,
    private val songEndingEventSource: SongEndingEventSource,
    private val synthEngine: SynthEngine,
    private val synthController: SynthController,
    scope: AppCoroutineScope,
) {
    private val log = logging("PulsarSongAdvancer")

    @Volatile
    var enabled: Boolean = true

    /** Inter-track silence in milliseconds. Tunable for tests / future config. */
    @Volatile
    var interTrackGapMs: Long = DEFAULT_INTER_TRACK_GAP_MS

    init {
        scope.launch {
            songEndingEventSource.songEndingEvents.collect { event ->
                if (!enabled) return@collect
                if (event !is SongEndingEvent.SongEnded) return@collect
                // Pick the next vibe by NAME — never forces the other 25
                // vibes to materialize. Only the picked vibe's body is built
                // when applyVibeByName runs.
                val names = pulsarFeature.vibeNames
                if (names.isEmpty()) return@collect
                val currentName = pulsarFeature.vibeFlow.value.name
                val idx = names.indexOf(currentName)
                // idx == -1 (current not in list) → (-1+1) % size = 0, so we
                // fall back to the first name rather than crashing.
                val nextIndex = ((idx + 1) % names.size).coerceAtLeast(0)
                val nextName = names[nextIndex]
                log.info { "SongEnded(${event.vibeName}) -> gap ${interTrackGapMs}ms -> applyVibe($nextName)" }
                // Halt beats AND silence audio for the gap. SongEnded fires
                // on the boundary OUT of the final section, so the engine has
                // already advanced into a new section — without pausing the
                // PULSAR_PLAYING port, the step grid playhead would visibly
                // animate through that unwanted section during the gap.
                synthController.setPluginControl(
                    PulsarSymbol.PLAYING.controlId,
                    IntValue(0),
                )
                synthEngine.setMasterVolume(0f)
                if (interTrackGapMs > 0) delay(interTrackGapMs)
                synthEngine.setMasterVolume(1.0f)
                pulsarFeature.applyVibeByName(nextName)
                // Resume beats for the new vibe. PulsarPlaybackBridge owns
                // this port based on PlaybackController.state — it stayed
                // Playing throughout, so this is the only writer of "1"
                // we need to fire.
                synthController.setPluginControl(
                    PulsarSymbol.PLAYING.controlId,
                    IntValue(1),
                )
            }
        }
    }

    private companion object {
        /** Red Book CD pre-gap convention: 2 s of silence between tracks. */
        const val DEFAULT_INTER_TRACK_GAP_MS: Long = 2_000L
    }
}
