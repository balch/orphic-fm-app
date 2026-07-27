package org.balch.orpheus.di

import android.app.Application
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import org.balch.orpheus.core.lifecycle.AndroidAppLifecycleManager
import org.balch.orpheus.core.media.MediaSessionManager
import org.balch.orpheus.core.media.MediaSessionStateManager

/**
 * Android concrete graph. Declared in androidMain so Metro can see the androidMain contributions
 * (`AndroidRepositoryModule`, `OboeAudioEngine`, `AndroidTtsGenerator`, the Android
 * `VibeCatalogPolicyProvider`). Common members are inherited from [OrpheusGraph]; only the
 * Android-touched roots and the factory are declared here.
 */
@DependencyGraph(AppScope::class)
interface OrpheusGraphAndroid : OrpheusGraph {
    val mediaSessionManager: MediaSessionManager
    val mediaSessionStateManager: MediaSessionStateManager

    /**
     * Android-specific lifecycle manager for background audio handling.
     * Accessing this property ensures it gets initialized.
     */
    val androidAppLifecycleManager: AndroidAppLifecycleManager

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides application: Application,
        ): OrpheusGraphAndroid
    }
}
