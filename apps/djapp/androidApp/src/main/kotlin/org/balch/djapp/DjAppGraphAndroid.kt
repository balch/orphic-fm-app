package org.balch.djapp

import android.app.Application
import androidx.media3.common.util.UnstableApi
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import org.balch.orpheus.core.features.FeatureGraphHolder
import org.balch.orpheus.core.media.MediaSessionManager
import org.balch.orpheus.core.media.MediaSessionStateManager
import org.balch.orpheus.core.playback.MetadataProducer
import org.balch.orpheus.core.update.InAppUpdateManager
import org.balch.orpheus.djapp.di.DjAppGraph
import org.balch.orpheus.djapp.lifecycle.DjAppLifecycleManager
import org.balch.orpheus.djapp.review.InAppReviewManager

/**
 * Android concrete graph. Declared HERE (not in `:apps:djapp:shared`) so Metro generates the graph
 * in a module that sees `:apps:djapp:ai` in the `ai` product flavor — `aiImplementation(project(
 * ":apps:djapp:ai"))` puts `AiTabContribution` (AppScope set) and `DjAiViewModel` (FeatureScope map)
 * on the `ai` variant's compile classpath, so Metro collects them. The `og` variant has no `ai`
 * module on its classpath, so the tab set is empty and `DjAiViewModel` is absent — correct (and
 * keeps INTERNET / AI deps out of the OG edition, per `verifyOgHasNoInternet`).
 *
 * Common members are inherited from [DjAppGraph]; only the Android-touched roots and the factory are
 * declared here.
 */
@DependencyGraph(AppScope::class)
interface DjAppGraphAndroid : DjAppGraph {
    val featureGraphHolder: FeatureGraphHolder

    @get:UnstableApi
    val mediaSessionManager: MediaSessionManager
    val mediaSessionStateManager: MediaSessionStateManager

    /** Eagerly initialized to register lifecycle callbacks. */
    val djAppLifecycleManager: DjAppLifecycleManager

    /** Google Play in-app update manager (Android only). */
    val inAppUpdateManager: InAppUpdateManager

    /** Google Play in-app review manager (Android only). */
    val inAppReviewManager: InAppReviewManager

    /** Now-playing artwork/title source — read by the home-screen widget. */
    val metadataProducer: MetadataProducer

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides application: Application,
        ): DjAppGraphAndroid
    }
}
