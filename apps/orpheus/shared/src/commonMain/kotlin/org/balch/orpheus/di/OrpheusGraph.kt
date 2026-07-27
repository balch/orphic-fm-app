package org.balch.orpheus.di

import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.SynthOrchestrator
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.core.playback.PlaybackController
import org.balch.orpheus.core.tempo.GlobalTempo
import org.balch.orpheus.features.ai.ControlHighlightEventBus
import org.balch.orpheus.features.pulsar.playback.PulsarPlaybackBridge
import org.balch.orpheus.features.pulsar.playback.PulsarSongAdvancer
import org.balch.orpheus.features.pulsar.playback.PulsarSongEnding
import org.balch.orpheus.util.ConsoleLogger

/**
 * Common surface the [org.balch.orpheus.App] composable consumes, independent of platform.
 *
 * NOTE: this is a plain interface, NOT the Metro `@DependencyGraph`. Each platform declares its own
 * concrete graph (`OrpheusGraphAndroid`, `OrpheusGraphJvm`, `OrpheusGraphIos`, `OrpheusGraphWasm`)
 * in its own source set and implements this interface. Metro has to generate the graph somewhere it
 * can see every contribution, and commonMain cannot see platform source sets, so a `@DependencyGraph`
 * declared here would silently miss every androidMain / jvmMain / iosMain / wasmJsMain binding.
 * Mirrors `DjAppGraph` in the DJ app.
 *
 * Anything every platform needs lives HERE, not in the platform files. The graph accessor is also
 * the eager-init hook: Metro builds a binding the first time something asks for it, so a root that
 * no platform touches is simply never constructed and its `init {}` collectors never subscribe.
 * Declaring those roots on this interface is what stops one platform from quietly losing a feature.
 * See `docs/di-architecture.md`.
 */
interface OrpheusGraph : ViewModelGraph {
    val synthOrchestrator: SynthOrchestrator
    val synthEngine: SynthEngine
    val synthController: SynthController
    val consoleLogger: ConsoleLogger
    val globalTempo: GlobalTempo
    val controlHighlightEventBus: ControlHighlightEventBus

    /**
     * Eagerly touched at startup so its init {} subscribes to flows.
     *
     * Every platform has a real media session behind `MediaSessionManager`: media3 on Android,
     * MacOsNowPlaying on desktop, MPNowPlayingInfoCenter on iOS, navigator.mediaSession on web.
     * So this is not Android-only.
     */
    val playbackController: PlaybackController

    /**
     * Eagerly touched at startup so its init {} collectors subscribe.
     *
     * This is the ONLY caller of `MediaSessionStateManager.setPulsarActive`. Without it Pulsar
     * never registers as an audio-activity source, so the media session drops as soon as the
     * timer or Evo stops even though the beat machine is still running, and the transport keys
     * stop working. It also mirrors playback state into the PULSAR_PLAYING port and routes
     * StopAll back through the controller.
     */
    val pulsarPlaybackBridge: PulsarPlaybackBridge

    /** Eagerly touched at startup so its init {} collectors observe playback/arrangement. */
    val pulsarSongEnding: PulsarSongEnding

    /** Eagerly touched at startup so its init {} collector subscribes to songEndingEvents. */
    val pulsarSongAdvancer: PulsarSongAdvancer
}
