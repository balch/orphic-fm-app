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

        /**
         * DELIBERATELY UNSCOPED. Do not add `@SingleIn(AppScope::class)` here.
         *
         * It buys nothing. `PulsarViewModel` is `@SingleIn(FeatureScope::class)`, so Metro already
         * memoizes the one instance inside the feature graph and `getFeature` returns that same
         * object on every call. A scope here adds no memoization, only an AppScope `DoubleCheck`
         * whose monitor would be held across this entire body, and this body is a cross-graph
         * call Metro can neither see nor order.
         *
         * A monitor held across exactly that kind of hidden edge is what used to hang an app's
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
    }
}
