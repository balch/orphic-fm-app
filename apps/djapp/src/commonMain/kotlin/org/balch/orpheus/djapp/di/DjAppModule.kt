package org.balch.orpheus.djapp.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.audio.dsp.WiringGraphProvider
import org.balch.orpheus.core.audio.dsp.buildDjAppWiringGraph
import org.balch.orpheus.core.features.FeatureGraphHolder
import org.balch.orpheus.core.features.PulsarPlaybackMode
import org.balch.orpheus.core.features.RestoreStrategy
import org.balch.orpheus.core.playback.MetadataProducer
import org.balch.orpheus.core.playback.OverlaySubtitleProducer
import org.balch.orpheus.core.playback.PlayFromMediaIdHandler
import org.balch.orpheus.core.playback.SkipHandler
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.playback.PulsarMetadataProducer
import org.balch.orpheus.features.pulsar.playback.PulsarSkipHandler
import org.balch.orpheus.features.pulsar.playback.PulsarVibePicker
import org.balch.orpheus.features.timer.TimerFeature
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.features.timer.playback.TimerOverlayProducer

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

        @Provides
        @SingleIn(AppScope::class)
        fun providePulsarFeature(holder: FeatureGraphHolder): PulsarFeature =
            holder.featureGraph.featureCollection.getFeature(PulsarViewModel::class)

        @Provides
        @SingleIn(AppScope::class)
        fun provideTimerFeature(holder: FeatureGraphHolder): TimerFeature =
            holder.featureGraph.featureCollection.getFeature(TimerViewModel::class)

        @Provides
        fun provideMetadataProducer(p: PulsarMetadataProducer): MetadataProducer = p

        @Provides
        fun provideOverlayProducer(p: TimerOverlayProducer): OverlaySubtitleProducer? = p

        @Provides
        fun provideSkipHandler(h: PulsarSkipHandler): SkipHandler? = h

        @Provides
        fun providePlayFromMediaIdHandler(h: PulsarVibePicker): PlayFromMediaIdHandler? = h
    }
}
