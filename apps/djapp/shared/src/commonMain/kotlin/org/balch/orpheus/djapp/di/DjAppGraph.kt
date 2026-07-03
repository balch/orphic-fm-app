package org.balch.orpheus.djapp.di

import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.SynthOrchestrator
import org.balch.orpheus.core.playback.PlaybackController
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.djapp.variant.DjTabContribution
import org.balch.orpheus.features.pulsar.playback.PulsarPlaybackBridge
import org.balch.orpheus.features.pulsar.playback.PulsarSongAdvancer
import org.balch.orpheus.features.pulsar.playback.PulsarSongEnding

/**
 * Common surface the [DjApp] composable consumes, independent of platform.
 *
 * NOTE: this is a plain interface, NOT the Metro `@DependencyGraph`. Each platform's concrete
 * `@DependencyGraph` (`DjAppGraphDesktop`, `DjAppGraphAndroid`, `DjAppGraphIos`) is declared in
 * its own entry-point module and implements this interface. The graph is generated there —
 * downstream of `:apps:djapp:ai` in the `ai` variant — so Metro collects `AiTabContribution`
 * (AppScope set) and `DjAiViewModel` (FeatureScope map). When the graph lived in `:apps:djapp:shared`
 * (upstream of `:apps:djapp:ai`) those contributions were invisible and the AI tab never appeared.
 */
interface DjAppGraph : ViewModelGraph {
    val synthOrchestrator: SynthOrchestrator
    val synthEngine: SynthEngine
    val globalTempo: GlobalTempo
    val djTabContributions: Set<DjTabContribution>

    /** Drives PULSAR_PLAYING via PulsarPlaybackBridge (EXPLICIT playback mode). */
    val playbackController: PlaybackController

    /** Eagerly touched at startup so its init {} collector subscribes. */
    val pulsarPlaybackBridge: PulsarPlaybackBridge

    /** Eagerly touched at startup so its init {} collectors observe playback/arrangement. */
    val pulsarSongEnding: PulsarSongEnding

    /** Eagerly touched at startup so its init {} collector subscribes to songEndingEvents. */
    val pulsarSongAdvancer: PulsarSongAdvancer
}
