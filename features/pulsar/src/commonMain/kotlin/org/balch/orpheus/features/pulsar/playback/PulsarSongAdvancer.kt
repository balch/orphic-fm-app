package org.balch.orpheus.features.pulsar.playback

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.TransitionSpec
import org.balch.orpheus.core.coroutines.AppCoroutineScope
import org.balch.orpheus.features.pulsar.PulsarFeature
import kotlin.concurrent.Volatile

/**
 * Default behavior for [SongEndingEvent.SongEnded]: resolve the active
 * [TransitionSpec] (per-vibe override or global default) and run it via
 * [PulsarTransitionRunner], which handles fades / gaps / tape-stop and
 * invokes `applyVibeByName` at the right moment.
 */
@SingleIn(AppScope::class)
@Inject
class PulsarSongAdvancer(
    private val pulsarFeature: PulsarFeature,
    private val songEndingEventSource: SongEndingEventSource,
    private val transitionPreferences: TransitionPreferences,
    private val transitionRunner: PulsarTransitionRunner,
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
                val names = pulsarFeature.vibeNames
                if (names.isEmpty()) return@collect
                val currentName = pulsarFeature.vibeFlow.value.name
                // PulsarSongEnding re-emits SongEnded every outro loop as a
                // recovery net. One queued behind an in-flight runTransition
                // arrives after the swap; acting on it would skip the new song.
                if (event.vibeName != currentName) {
                    log.info { "stale SongEnded(${event.vibeName}); now playing $currentName — ignoring" }
                    return@collect
                }
                val idx = names.indexOf(currentName)
                val nextIndex = ((idx + 1) % names.size).coerceAtLeast(0)
                val nextName = names[nextIndex]

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

                transitionRunner.runTransition(spec) {
                    pulsarFeature.applyVibeByName(nextName)
                }
            }
        }
    }
}
