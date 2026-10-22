package org.balch.orpheus.djapp.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.MixBenchTap
import org.balch.orpheus.core.audio.dsp.NoMeterTap
import org.balch.orpheus.core.controller.SynthController
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.bench.MixBenchController

@ContributesTo(AppScope::class)
@BindingContainer
interface MixBenchModule {
    companion object {
        /**
         * The one controller the agent's mix tools, the desktop bench page and the AI edition's TRACKS view share.
         * Built on first use, always after the feature graph exists: the AI TRACKS sink asks from a coroutine
         * just after startup, never from a constructor. Desktop stops it at app exit.
         */
        @Provides
        @SingleIn(AppScope::class)
        fun provideMixBenchController(
            pulsarFeatureProvider: () -> PulsarFeature,
            synthController: SynthController,
            audioEngine: AudioEngine,
        ): MixBenchController {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val controller = MixBenchController(
                pulsar = pulsarFeatureProvider(),
                synthController = synthController,
                tap = audioEngine as? MixBenchTap ?: NoMeterTap,
                scope = scope,
                sampleRate = audioEngine.sampleRate.toFloat(),
            )
            // start() never suspends on its uncontended lock, so this finishes before the first edit can arrive.
            scope.launch(start = CoroutineStart.UNDISPATCHED) { controller.start() }
            stopOnAppExit(controller)
            return controller
        }
    }
}

/** Lets the desktop window close stop [controller], so metering ends before the engine closes. */
internal expect fun stopOnAppExit(controller: MixBenchController)
