package org.balch.orpheus.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import org.balch.orpheus.core.audio.dsp.WiringGraphProvider
import org.balch.orpheus.core.audio.dsp.buildDefaultWiringGraph
import org.balch.orpheus.core.features.AgentGreetingMode
import org.balch.orpheus.core.features.FeatureGraphHolder
import org.balch.orpheus.core.features.PulsarPlaybackMode
import org.balch.orpheus.core.features.RestoreStrategy
import org.balch.orpheus.core.midi.MidiController
import org.balch.orpheus.core.midi.MidiMappingRepository
import org.balch.orpheus.core.midi.createMidiAccess
import org.balch.orpheus.core.playback.MetadataProducer
import org.balch.orpheus.core.playback.OverlaySubtitleProducer
import org.balch.orpheus.core.playback.PlayFromMediaIdHandler
import org.balch.orpheus.core.playback.SkipHandler
import org.balch.orpheus.features.ai.AiOptionsFeature
import org.balch.orpheus.features.ai.AiOptionsViewModel
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarViewModel
import org.balch.orpheus.features.pulsar.playback.PulsarSkipHandler
import org.balch.orpheus.features.timer.TimerFeature
import org.balch.orpheus.features.timer.TimerViewModel
import org.balch.orpheus.features.timer.playback.TimerOverlayProducer
import org.balch.orpheus.playback.OrpheusMetadataProducer

/**
 * Module providing core dependencies for the Orpheus application. Used by Metro DI to provide
 * singleton instances of infrastructure components.
 * 
 * Note: SynthPresetRepository and AppPreferencesRepository are now provided via
 * @ContributesBinding from platform-specific implementations.
 */
@ContributesTo(AppScope::class)
interface OrpheusModule {
    /**
     * The three playback hooks `PlaybackController` takes as optional constructor params.
     *
     * Bound to the NULLABLE type on purpose. Metro treats `T` and `T?` as distinct type keys, so a
     * non-null binding will not satisfy `overlayProducer: OverlaySubtitleProducer? = null`; the
     * param would silently fall back to its default and the feature would just never fire.
     */
    @Binds val TimerOverlayProducer.bindOverlayProducer: OverlaySubtitleProducer?

    @Binds val PulsarSkipHandler.bindSkipHandler: SkipHandler?

    @Binds val OrpheusMetadataProducer.bindMetadataProducer: MetadataProducer

    companion object Companion {
        @Provides
        @SingleIn(AppScope::class)
        fun provideWiringGraphProvider(): WiringGraphProvider =
            WiringGraphProvider { buildDefaultWiringGraph() }

        @Provides
        @SingleIn(AppScope::class)
        fun provideMidiController(): MidiController = MidiController { createMidiAccess() }

        @Provides
        @SingleIn(AppScope::class)
        fun provideMidiMappingRepository(): MidiMappingRepository = MidiMappingRepository()

        @Provides
        fun provideRestoreStrategy(): RestoreStrategy = RestoreStrategy.PRESET

        @Provides
        fun providePulsarPlaybackMode(): PulsarPlaybackMode = PulsarPlaybackMode.MIX_GATED

        @Provides
        fun provideAgentGreetingMode(): AgentGreetingMode = AgentGreetingMode.ON_START

        @Provides
        @SingleIn(AppScope::class)
        fun providePulsarFeature(holder: FeatureGraphHolder): PulsarFeature =
            holder.featureGraph.featureCollection.getFeature(PulsarViewModel::class)

        @Provides
        @SingleIn(AppScope::class)
        fun provideTimerFeature(holder: FeatureGraphHolder): TimerFeature =
            holder.featureGraph.featureCollection.getFeature(TimerViewModel::class)

        @Provides
        @SingleIn(AppScope::class)
        fun provideAiOptionsFeature(holder: FeatureGraphHolder): AiOptionsFeature =
            holder.featureGraph.featureCollection.getFeature(AiOptionsViewModel::class)

        /**
         * No `@Binds` counterpart: Orpheus has no media-ID browse tree, so this is an explicit
         * null. It still has to be bound. `PlayFromMediaIdHandler?` is its own type key and Metro
         * would otherwise use the constructor default, which is the same result but silently.
         */
        @Provides
        fun providePlayFromMediaIdHandler(): PlayFromMediaIdHandler? = null
    }
}
