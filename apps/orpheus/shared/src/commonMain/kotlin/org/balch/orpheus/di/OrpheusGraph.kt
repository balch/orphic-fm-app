package org.balch.orpheus.di

import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.SynthOrchestrator
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.features.StartupInitializer
import org.balch.orpheus.core.playback.PlaybackController
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.features.ai.ControlHighlightEventBus
import org.balch.orpheus.util.ConsoleLogger

/**
 * Common surface the [org.balch.orpheus.App] composable consumes, independent of platform.
 *
 * NOT the Metro `@DependencyGraph` -- each platform declares its own concrete graph and implements
 * this. Metro must generate the graph where it can see every contribution, and commonMain cannot
 * see platform source sets, so a `@DependencyGraph` here would silently miss every platform
 * binding. Mirrors `DjAppGraph`.
 *
 * Anything every platform needs lives HERE, not in the platform files.
 */
interface OrpheusGraph : ViewModelGraph {
    val synthOrchestrator: SynthOrchestrator
    val synthEngine: SynthEngine
    val synthController: SynthController
    val consoleLogger: ConsoleLogger
    val globalTempo: GlobalTempo
    val controlHighlightEventBus: ControlHighlightEventBus

    /** Every platform has a real media session behind `MediaSessionManager`, not just Android. */
    val playbackController: PlaybackController

    /** Constructing it builds every `@StartupRoot`; `run()` then the graph's startup features. */
    val startupInitializer: StartupInitializer
}
