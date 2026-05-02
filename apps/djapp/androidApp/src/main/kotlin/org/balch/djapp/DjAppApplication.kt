package org.balch.djapp

import android.app.Application
import android.content.Context
import dev.zacsweers.metro.createGraphFactory
import org.balch.orpheus.djapp.di.DjAppGraph

class DjAppApplication : Application() {

    lateinit var graph: DjAppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = createGraphFactory<DjAppGraph.Factory>().create(
            this,
            DjForegroundServiceControllerImpl(this),
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
    }

    companion object {
        fun getGraph(context: Context): DjAppGraph =
            (context.applicationContext as DjAppApplication).graph
    }
}
