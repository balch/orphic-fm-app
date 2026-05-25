package org.balch.orpheus.features.pulsar.playback

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.playback.PlayFromMediaIdHandler
import org.balch.orpheus.features.pulsar.PulsarFeature

/**
 * Maps Android Auto media-id selections to Pulsar vibes.
 * mediaId convention: vibe.name (see DjMediaBrowserService.onLoadChildren).
 * Unknown ids are silently ignored — Auto can send stale entries.
 */
@SingleIn(AppScope::class)
@Inject
class PulsarVibePicker(
    // Lazy provider breaks the DI cycle: PulsarFeature → PulsarViewModel →
    // PulsarSongEnding → PlaybackController → PlayFromMediaIdHandler (= this)
    // → PulsarFeature. PulsarFeature is only touched inside onPlay(), so a
    // plain `by lazy` defers resolution to the first runtime callback.
    pulsarFeatureProvider: () -> PulsarFeature,
) : PlayFromMediaIdHandler {
    private val pulsarFeature: PulsarFeature by lazy(pulsarFeatureProvider)

    override fun onPlay(mediaId: String) {
        pulsarFeature.vibeList.firstOrNull { it.name == mediaId }
            ?.let { pulsarFeature.applyVibe(it) }
    }
}
