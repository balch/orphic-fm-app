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
            val engine = System.getProperty("orpheus.engine", "jsyn")
            log.info { "Audio engine selection: $engine" }
            return if (engine == "cpp") {
                log.info { "Using NativeDspAudioEngine (C++ DSP)" }
                NativeDspAudioEngine()
            } else {
                log.info { "Using OrpheusAudioEngine (JSyn)" }
                OrpheusAudioEngine()
            }
        }
    }
}
