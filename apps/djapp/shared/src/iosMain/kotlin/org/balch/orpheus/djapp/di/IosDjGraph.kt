package org.balch.orpheus.djapp.di

import org.balch.orpheus.core.features.FeatureGraphHolder
import org.balch.orpheus.core.playback.MetadataProducer

/**
 * What [org.balch.orpheus.djapp.DjAppHost] and the widget updater read off an iOS graph, in either
 * edition: Core's [DjAppGraphIos] here, or the AI edition's graph in `:apps:djapp:ai`.
 */
interface IosDjGraph : DjAppGraph {

    /** Resolves `PulsarFeature`/`TimerFeature` for the widget updater, outside any composition. */
    val featureGraphHolder: FeatureGraphHolder

    /** Now-playing artwork/title source — read by the home-screen widget. */
    val metadataProducer: MetadataProducer
}
