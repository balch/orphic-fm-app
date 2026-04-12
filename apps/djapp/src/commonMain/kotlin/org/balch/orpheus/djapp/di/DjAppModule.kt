package org.balch.orpheus.djapp.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.audio.dsp.WiringGraphProvider
import org.balch.orpheus.core.audio.dsp.buildDjAppWiringGraph
import org.balch.orpheus.core.features.PulsarPlaybackMode
import org.balch.orpheus.core.features.RestoreStrategy

@ContributesTo(AppScope::class)
interface DjAppModule {
    companion object {
        @Provides
        @SingleIn(AppScope::class)
        fun provideWiringGraphProvider(): WiringGraphProvider =
            WiringGraphProvider { buildDjAppWiringGraph() }

        @Provides
        fun provideRestoreStrategy(): RestoreStrategy = RestoreStrategy.USER_PREFERENCES

        @Provides
        fun providePulsarPlaybackMode(): PulsarPlaybackMode = PulsarPlaybackMode.EXPLICIT
    }
}
