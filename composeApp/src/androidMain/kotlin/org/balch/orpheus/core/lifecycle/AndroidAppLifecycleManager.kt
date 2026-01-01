package org.balch.orpheus.core.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.balch.orpheus.core.audio.SynthEngine
import org.balch.orpheus.core.media.MediaSessionStateManager

/**
 * Android-specific lifecycle manager that mutes audio when the app is backgrounded
 * and there is no active MediaSession.
 * 
 * When MediaSession IS active, audio continues playing in background (foreground service).
 * When MediaSession is NOT active, audio is hushed on background to save resources.
 */
@SingleIn(AppScope::class)
@Inject
class AndroidAppLifecycleManager(
    private val application: Application,
    private val synthEngine: SynthEngine,
    private val mediaSessionStateManager: MediaSessionStateManager
) {
    private val log = logging("AndroidAppLifecycleManager")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private var activityCount = 0
    private val _isAppInForeground = MutableStateFlow(true)
    val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()
    
    private var savedMasterVolume: Float? = null
    
    init {
        registerActivityLifecycleCallbacks()
        log.info { "AndroidAppLifecycleManager initialized" }
    }
    
    private fun registerActivityLifecycleCallbacks() {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                activityCount++
                if (activityCount == 1) {
                    onAppForegrounded()
                }
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                activityCount--
                if (activityCount == 0) {
                    onAppBackgrounded()
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
    
    private fun onAppForegrounded() {
        log.info { "App foregrounded" }
        _isAppInForeground.value = true
        
        // Restore volume if we previously muted it
        savedMasterVolume?.let { volume ->
            log.info { "Restoring master volume to $volume after foreground" }
            synthEngine.setMasterVolume(volume)
            savedMasterVolume = null
        }
    }
    
    private fun onAppBackgrounded() {
        log.info { "App backgrounded, checking if MediaSession is active..." }
        _isAppInForeground.value = false
        
        // Check if MediaSession is needed (i.e., any audio source is active)
        // If not, mute the audio to save resources
        scope.launch {
            // Small delay to allow MediaSession state to settle
            delay(100)
            
            if (!mediaSessionStateManager.isMediaSessionNeeded.value) {
                log.info { "No MediaSession active - muting audio in background" }
                
                // Save current volume and mute
                val currentVolume = synthEngine.getMasterVolume()
                if (currentVolume > 0f) {
                    savedMasterVolume = currentVolume
                    synthEngine.setMasterVolume(0f)
                    log.debug { "Saved volume ($currentVolume) and muted for background" }
                }
            } else {
                log.info { "MediaSession active - allowing background audio playback" }
            }
        }
    }
}
