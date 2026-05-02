package org.balch.orpheus.features.pulsar.playback

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.playback.SkipDirection
import org.balch.orpheus.core.playback.SkipHandler
import org.balch.orpheus.features.pulsar.PulsarFeature

/**
 * Cycles through Pulsar's vibe list on system skip-next/previous commands.
 * Used by both apps (Pulsar exists in both).
 */
@SingleIn(AppScope::class)
@Inject
class PulsarSkipHandler(private val pulsarFeature: PulsarFeature) : SkipHandler {
    override fun onSkip(direction: SkipDirection) {
        val list = pulsarFeature.vibeList
        if (list.isEmpty()) return
        val current = pulsarFeature.vibeFlow.value
        val currentIndex = list.indexOfFirst { it.name == current.name }
        val nextIndex = when (direction) {
            SkipDirection.NEXT -> (currentIndex + 1).mod(list.size)
            SkipDirection.PREVIOUS -> if (currentIndex <= 0) list.size - 1 else currentIndex - 1
        }
        pulsarFeature.applyVibe(list[nextIndex])
    }
}
