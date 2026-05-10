package org.balch.orpheus

import android.app.Application
import com.diamondedge.logging.KmLogging
import dev.zacsweers.metro.createGraphFactory
import org.balch.orpheus.di.OrpheusGraph

/**
 * Application class that holds the DI graph.
 * 
 * CRITICAL: The graph MUST be created at the Application level to survive
 * Android configuration changes (rotation, dark mode, etc.).
 * If created in Activity.onCreate(), a new graph is created on each
 * configuration change, losing all singleton state.
 */
class OrpheusApplication : Application() {
    
    lateinit var graph: OrpheusGraph
        private set
    
    override fun onCreate() {
        super.onCreate()
        
        // Create the foreground service controller (app-module implementation)
        val foregroundServiceController = ForegroundServiceControllerImpl(this)
        
        // Create the DI graph ONCE at Application level with Application
        // This survives Activity recreation on configuration changes
        graph = createGraphFactory<OrpheusGraph.Factory>().create(
            this,
            foregroundServiceController
        )
        
        // Wire up logging to UI
        KmLogging.addLogger(graph.consoleLogger)
        
        // Eagerly initialize AndroidAppLifecycleManager to register lifecycle callbacks
        // This enables muting audio when the app is backgrounded without MediaSession
        graph.androidAppLifecycleManager

        // Eagerly initialize PlaybackController so its init {} subscribes
        // to flows at startup. PlaybackController is the single source of
        // truth for play/pause state across the app.
        graph.playbackController

        // Eagerly initialize PulsarPlaybackBridge so it observes PlaybackController
        // state and StopAll events from app launch. Decoupled from PulsarViewModel
        // to break the DI cycle that would otherwise stack-overflow Metro.
        graph.pulsarPlaybackBridge

        // Eagerly initialize PulsarSongEnding so its init {} collectors observe
        // playback/arrangement state at startup. Same decoupling reason as
        // PulsarPlaybackBridge.
        graph.pulsarSongEnding

        // Eagerly initialize PulsarSongAdvancer so its init {} collector subscribes
        // to PulsarSongEnding.songEndingEvents and auto-advances the vibe list.
        graph.pulsarSongAdvancer
    }
}
