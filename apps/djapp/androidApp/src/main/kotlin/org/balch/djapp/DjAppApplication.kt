@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.balch.djapp

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.diamondedge.logging.logging
import dev.zacsweers.metro.createGraphFactory
import org.balch.djapp.widget.DjWidgetUpdater
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarViewModel

class DjAppApplication : Application() {

    private val log = logging("DjAppApplication")

    lateinit var graph: DjAppGraphAndroid
        private set

    override fun onCreate() {
        super.onCreate()
        graph = createGraphFactory<DjAppGraphAndroid.Factory>().create(this)

        graph.mediaSessionManager.setServiceIntent(
            Intent(this, DjMediaLibraryService::class.java)
        )
        graph.mediaSessionManager.setLibraryCallback(
            DjLibraryCallback(
                featureProvider = {
                    try {
                        graph.featureGraphHolder.featureGraph.featureCollection
                            .getFeature(PulsarFeature::class)
                    } catch (_: Exception) { null }
                },
            )
        )

        // Builds every @StartupRoot, then the graph's startup features.
        graph.startupInitializer.run()

        // Keep the home-screen widget in sync with playback / vibe / timer state.
        DjWidgetUpdater(this, graph).start()

        // Pause UI-feeding polls (60Hz Pulsar viz, turntable viz, 5Hz meters)
        // while the app is backgrounded so Android doesn't kill us for
        // excessive background CPU. Audio playback and the arrangement poll
        // (song auto-advance / media metadata) keep running.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                log.info { "App foregrounded — resuming UI polling" }
                graph.synthEngine.setUiVisible(true)
            }

            override fun onStop(owner: LifecycleOwner) {
                log.info { "App backgrounded — pausing UI polling" }
                graph.synthEngine.setUiVisible(false)
            }
        })
    }

    companion object {
        fun getGraph(context: Context): DjAppGraphAndroid =
            (context.applicationContext as DjAppApplication).graph
    }
}
