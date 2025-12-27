package org.balch.orpheus.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.audio.dsp.AudioEngine
import org.balch.orpheus.core.audio.dsp.DspSynthEngine
import org.balch.orpheus.core.audio.dsp.plugins.DspPlugin

/**
 * Provides the AudioEngine and SynthEngine instances for DI.
 * 
 * CRITICAL: AudioEngine MUST be a singleton so all plugins and
 * DspSynthEngine share the same audio graph instance.
 */
@ContributesTo(AppScope::class)
interface AudioModule {
    companion object {
        @Provides
        @SingleIn(AppScope::class)
        fun provideAudioEngine(): AudioEngine = AudioEngine()
        
        @Provides
        @SingleIn(AppScope::class)
        fun provideSynthEngine(
            audioEngine: AudioEngine,
            plugins: Set<DspPlugin>
        ): SynthEngine = DspSynthEngine(audioEngine, plugins)
    }
}
