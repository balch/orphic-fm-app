@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.balch.orpheus

import android.app.Application
import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.diamondedge.logging.KmLogging
import com.diamondedge.logging.logging
import dev.zacsweers.metro.createGraphFactory
import org.balch.orpheus.di.OrpheusGraphAndroid

/**
 * Application class that holds the DI graph.
 * 
 * CRITICAL: The graph MUST be created at the Application level to survive
 * Android configuration changes (rotation, dark mode, etc.).
 * If created in Activity.onCreate(), a new graph is created on each
 * configuration change, losing all singleton state.
 */
class OrpheusApplication : Application() {

    private val log = logging("OrpheusApplication")

    lateinit var graph: OrpheusGraphAndroid
        private set

    override fun onCreate() {
        super.onCreate()

        // Create the DI graph ONCE at Application level with Application
        // This survives Activity recreation on configuration changes
        graph = createGraphFactory<OrpheusGraphAndroid.Factory>().create(
            this,
        )

        graph.mediaSessionManager.setServiceIntent(
            Intent(this, OrpheusMediaSessionService::class.java)
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
}
