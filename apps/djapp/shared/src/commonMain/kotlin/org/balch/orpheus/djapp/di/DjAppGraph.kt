package org.balch.orpheus.djapp.di

import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.SynthOrchestrator
import org.balch.orpheus.core.features.StartupInitializer
import org.balch.orpheus.core.playback.PlaybackController
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.djapp.variant.DjTabContribution

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

    /** Constructing it builds every `@StartupRoot`; `run()` then the graph's startup features. */
    val startupInitializer: StartupInitializer
}
