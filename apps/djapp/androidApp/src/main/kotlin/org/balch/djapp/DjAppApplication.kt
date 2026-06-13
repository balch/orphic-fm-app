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
import org.balch.orpheus.djapp.di.DjAppGraph
import org.balch.orpheus.features.pulsar.PulsarFeature
import org.balch.orpheus.features.pulsar.PulsarViewModel

class DjAppApplication : Application() {

    private val log = logging("DjAppApplication")

    lateinit var graph: DjAppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = createGraphFactory<DjAppGraph.Factory>().create(
            this,
        )

        graph.mediaSessionManager.setServiceIntent(
            Intent(this, DjMediaLibraryService::class.java)
        )
        graph.mediaSessionManager.setLibraryCallback(
            DjLibraryCallback(
                featureProvider = {
                    try {
                        graph.featureGraphHolder.featureGraph.featureCollection
                            .getFeature<PulsarFeature>(PulsarViewModel::class)
                    } catch (_: Exception) { null }
                },
            )
        )

        // Eagerly initialize lifecycle manager to register activity callbacks.
        // This enables tearing down the foreground service when backgrounded while paused.
        graph.djAppLifecycleManager

        // Eagerly initialize PlaybackController so its init {} subscribes
        // to flows at startup. PlaybackController is the single source of
        // truth for play/pause state across the app.
        graph.playbackController

        // Eagerly initialize PulsarPlaybackBridge so it observes PlaybackController
        // state and StopAll events from app launch. Decoupled from PulsarViewModel
        // to break the DI cycle that would otherwise stack-overflow Metro.
        graph.pulsarPlaybackBridge

        // Eagerly initialize PulsarSongEnding so its init {} collectors observe
        // playback state, the arrangement state flow, and the active vibe at
        // startup. Same decoupling reason as PulsarPlaybackBridge.
        graph.pulsarSongEnding

        // Eagerly initialize PulsarSongAdvancer so its init {} collector subscribes
        // to PulsarSongEnding.songEndingEvents and auto-advances the vibe list.
        graph.pulsarSongAdvancer

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
        fun getGraph(context: Context): DjAppGraph =
            (context.applicationContext as DjAppApplication).graph
    }
}
