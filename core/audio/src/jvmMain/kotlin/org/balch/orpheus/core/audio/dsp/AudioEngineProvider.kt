package org.balch.orpheus.core.audio.dsp

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@ContributesTo(AppScope::class)
interface AudioEngineModule {
    companion object {
        private val log = logging("AudioEngineProvider")

        @Provides
        @SingleIn(AppScope::class)
        fun provideAudioEngine(): AudioEngine {
            log.info { "Using NativeDspAudioEngine (C++ DSP)" }
            return NativeDspAudioEngine()
        }
    }
}
