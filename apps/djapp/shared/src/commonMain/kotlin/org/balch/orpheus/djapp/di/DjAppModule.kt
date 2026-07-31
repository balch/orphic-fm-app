package org.balch.orpheus.djapp.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.audio.dsp.WiringGraphProvider
import org.balch.orpheus.core.audio.dsp.buildDjAppWiringGraph
import org.balch.orpheus.core.features.AgentGreetingMode
import org.balch.orpheus.core.features.FeatureGraphHolder
import org.balch.orpheus.core.features.PulsarPlaybackMode
import org.balch.orpheus.core.features.RestoreStrategy
import org.balch.orpheus.core.playback.MetadataProducer
import org.balch.orpheus.core.playback.OverlaySubtitleProducer
import org.balch.orpheus.core.playback.PlayFromMediaIdHandler
import org.balch.orpheus.core.playback.SkipHandler
import org.balch.orpheus.djapp.variant.DjTabContribution
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.playback.PulsarMetadataProducer
import org.balch.orpheus.features.pulsar.playback.PulsarSkipHandler
import org.balch.orpheus.features.pulsar.playback.PulsarVibePicker
import org.balch.orpheus.features.timer.TimerFeature
import org.balch.orpheus.features.timer.playback.TimerOverlayProducer

@ContributesTo(AppScope::class)
interface DjAppModule {
    /**
     * `allowEmpty` because the `og` edition has no `:apps:djapp:ai` on its classpath, so nothing
     * contributes an `AiTabContribution` and the set is legitimately empty. Metro treats empty
     * multibindings as an error unless you say so here.
     */
    @Multibinds(allowEmpty = true)
    fun djTabContributions(): Set<DjTabContribution>

    /**
     * The four playback hooks `PlaybackController` takes as optional constructor params.
     *
     * Bound to the NULLABLE type on purpose. Metro treats `T` and `T?` as distinct type keys, so a
     * non-null binding will not satisfy `skipHandler: SkipHandler? = null`; the param would
     * silently fall back to its default and next/prev would quietly stop working.
     */
    @Binds val TimerOverlayProducer.bindOverlayProducer: OverlaySubtitleProducer?

    @Binds val PulsarSkipHandler.bindSkipHandler: SkipHandler?

    @Binds val PulsarVibePicker.bindPlayFromMediaIdHandler: PlayFromMediaIdHandler?

    @Binds val PulsarMetadataProducer.bindMetadataProducer: MetadataProducer

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
        fun provideAgentGreetingMode(): AgentGreetingMode = AgentGreetingMode.ON_FIRST_PROMPT

        @Provides
        @SingleIn(AppScope::class)
        fun providePulsarFeature(holder: FeatureGraphHolder): PulsarFeature =
            holder.featureGraph.featureCollection.getFeature(PulsarFeature::class)

        @Provides
        @SingleIn(AppScope::class)
        fun provideTimerFeature(holder: FeatureGraphHolder): TimerFeature =
            holder.featureGraph.featureCollection.getFeature(TimerFeature::class)
    }
}
