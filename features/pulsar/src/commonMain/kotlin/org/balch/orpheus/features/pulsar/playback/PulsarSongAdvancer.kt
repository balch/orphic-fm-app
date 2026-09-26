package org.balch.orpheus.features.pulsar.playback

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.launch
import org.balch.orpheus.core.di.StartupRoot
import org.balch.orpheus.core.audio.TransitionSpec
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.features.pulsar.PulsarFeature
import kotlin.concurrent.Volatile

/**
 * Default behavior for [SongEndingEvent.SongEnded]: resolve the active
 * [TransitionSpec] (per-vibe override or global default) and hand the
 * transition to [VibeNavigator], which runs it on the one job every vibe
 * change shares and applies the next vibe at the right moment.
 */
@SingleIn(AppScope::class)
@Inject
@ContributesIntoSet(AppScope::class, binding = binding<@StartupRoot Any>())
class PulsarSongAdvancer(
    private val pulsarFeature: PulsarFeature,
    private val songEndingEventSource: SongEndingEventSource,
    private val transitionPreferences: TransitionPreferences,
    private val vibeNavigator: VibeNavigator,
    scope: AppCoroutineScope,
) {
    private val log = logging("PulsarSongAdvancer")

    @Volatile
    var enabled: Boolean = true

    init {
        scope.launch {
            songEndingEventSource.songEndingEvents.collect { event ->
                if (!enabled) return@collect
                if (event !is SongEndingEvent.SongEnded) return@collect
                val currentName = pulsarFeature.vibeFlow.value.name
                // PulsarSongEnding re-emits SongEnded every outro loop as a
                // recovery net. One queued behind an in-flight runTransition
                // arrives after the swap; acting on it would skip the new song.
                if (event.vibeName != currentName) {
                    log.info { "stale SongEnded(${event.vibeName}); now playing $currentName — ignoring" }
                    return@collect
                }
                // A user request is playing out; it wins over the song's own ending. A cheap
                // pre-check only: VibeNavigator makes the final call when it dequeues the advance.
                if (vibeNavigator.isBusy) {
                    log.info { "SongEnded(${event.vibeName}) during a user transition — ignoring" }
                    return@collect
                }
                val nextName = neighborVibe(pulsarFeature.vibeNames, currentName, 1) ?: return@collect

                val configured: TransitionSpec = pulsarFeature.vibeFlow.value
                    .arrangement?.transitionOut
                    ?: transitionPreferences.defaultFlow.value
                // Use the pre-rolled style from PulsarSongEnding so the actual
                // transition matches what the panel displayed. For non-RANDOM
                // specs the resolved style equals the configured style, so this
                // is a no-op pass-through.
                val resolvedStyle = songEndingEventSource.resolvedTransitionStyle.value
                val spec = configured.copy(style = resolvedStyle)
                log.info { "SongEnded(${event.vibeName}) -> transition=${spec.style} (configured=${configured.style}) -> applyVibe($nextName)" }

                vibeNavigator.advance(from = currentName, name = nextName, spec = spec)
            }
        }
    }
}
