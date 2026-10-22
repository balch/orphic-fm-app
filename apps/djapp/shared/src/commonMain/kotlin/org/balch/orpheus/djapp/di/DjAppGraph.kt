package org.balch.orpheus.djapp.di

import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.SynthOrchestrator
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.features.StartupInitializer
import org.balch.orpheus.core.playback.PlaybackController
import org.balch.orpheus.core.preferences.AppPreferencesRepository
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.djapp.DomeWiggleLaunch
import org.balch.orpheus.djapp.variant.DjTabContribution
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.bench.MixBenchController

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

    /** Persists the TV-mode docked-panel set, which has no ViewModel of its own. */
    val appPreferencesRepository: AppPreferencesRepository

    /** The dome's "swipe me" wiggle, spent once a process: it outlives an Android activity's recreation. */
    val domeWiggleLaunch: DomeWiggleLaunch

    /** The Mix Bench (desktop, `-Pbench=true`) drives these three directly. */
    val pulsarFeature: PulsarFeature
    val synthController: SynthController
    val audioEngine: AudioEngine

    /** The Mix Bench controller the agent's mix tools share; built on first read. */
    val mixBenchController: MixBenchController
}
