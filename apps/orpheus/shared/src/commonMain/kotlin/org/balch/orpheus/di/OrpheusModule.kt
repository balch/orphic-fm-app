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
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.playback.PulsarSkipHandler
import org.balch.orpheus.features.timer.TimerFeature
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

        /**
         * DELIBERATELY UNSCOPED. Do not add `@SingleIn(AppScope::class)` here.
         *
         * It buys nothing. `PulsarViewModel` is `@SingleIn(FeatureScope::class)`, so Metro already
         * memoizes the one instance inside the feature graph and `getFeature` returns that same
         * object on every call. A scope here adds no memoization, only an AppScope `DoubleCheck`
         * whose monitor would be held across this entire body, and this body is a cross-graph
         * call Metro can neither see nor order.
         *
         * A monitor held across exactly that kind of hidden edge is what used to hang Baton's
         * startup four launches out of five: an AB-BA deadlock between a scoped provider's
         * `DoubleCheck` and `FeatureCollection`'s then-extant cache lock, with `jstack` naming
         * both monitors. The collection's cache and lock are gone, so that specific cycle cannot
         * re-form, but the rule stands: bridge providers stay unscoped and cheap.
         *
         * Guarded by `FeatureProviderScopeGuardTest` in `:core:features`.
         */
        @Provides
        fun providePulsarFeature(holder: FeatureGraphHolder): PulsarFeature =
            holder.featureGraph.featureCollection.getFeature(PulsarFeature::class)

        /**
         * DELIBERATELY UNSCOPED for the same reason as [providePulsarFeature] above: the
         * ViewModel behind this is already `@SingleIn(FeatureScope::class)`, so a scope here adds
         * no memoization, only a `DoubleCheck` monitor held across a cross-graph call. Do not add
         * `@SingleIn(AppScope::class)`.
         */
        @Provides
        fun provideTimerFeature(holder: FeatureGraphHolder): TimerFeature =
            holder.featureGraph.featureCollection.getFeature(TimerFeature::class)

        /**
         * DELIBERATELY UNSCOPED for the same reason as [providePulsarFeature] above: the
         * ViewModel behind this is already `@SingleIn(FeatureScope::class)`, so a scope here adds
         * no memoization, only a `DoubleCheck` monitor held across a cross-graph call. Do not add
         * `@SingleIn(AppScope::class)`.
         */
        @Provides
        fun provideAiOptionsFeature(holder: FeatureGraphHolder): AiOptionsFeature =
            holder.featureGraph.featureCollection.getFeature(AiOptionsFeature::class)

        /**
         * No `@Binds` counterpart: Orpheus has no media-ID browse tree, so this is an explicit
         * null. It still has to be bound. `PlayFromMediaIdHandler?` is its own type key and Metro
         * would otherwise use the constructor default, which is the same result but silently.
         */
        @Provides
        fun providePlayFromMediaIdHandler(): PlayFromMediaIdHandler? = null
    }
}
