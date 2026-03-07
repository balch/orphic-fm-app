package org.balch.orpheus.worker

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.dsp.AudioEngine

/**
 * Metro DI graph for the DSP Web Worker.
 *
 * Metro discovers all `@ContributesBinding(AppScope)` and
 * `@ContributesTo(AppScope)` declarations from transitive dependencies:
 *
 * - [WorkerAudioEngine] provides [AudioEngine]
 * - core:foundation contributes [DefaultDispatcherProvider] -> [DispatcherProvider]
 * - core:dsp-engine contributes [DspSynthEngine] -> [SynthEngine],
 *   [DspFactoryImpl] -> [DspFactory], [PlaitsEngineFactoryImpl], etc.
 * - core:audio contributes DSP unit factories via `@ContributesBinding`
 * - core:foundation contributes [SynthController], [GlobalTempo] via `@Inject`
 */
@DependencyGraph(AppScope::class)
interface WorkerGraph {
    val synthEngine: SynthEngine
    val audioEngine: AudioEngine

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(): WorkerGraph
    }
}
