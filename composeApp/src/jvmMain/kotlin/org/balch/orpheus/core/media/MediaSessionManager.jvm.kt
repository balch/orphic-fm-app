package org.balch.orpheus.core.media

import com.diamondedge.logging.logging
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * JVM Desktop implementation of MediaSessionManager.
 * 
 * Uses JavaMediaTransportControls (JMTC) for Windows and Linux media key support.
 * macOS is currently unsupported by JMTC and will be a no-op.
 */
@SingleIn(AppScope::class)
@Inject
actual class MediaSessionManager {
    private val log = logging("MediaSessionManager")
    private var handler: MediaSessionActionHandler? = null
    private var jmtcInstance: Any? = null  // JMTC instance, typed as Any for now
    private var isActive = false
    
    actual fun activate() {
        if (isActive) return
        
        try {
            // Attempt to initialize JMTC if available
            // This will work on Windows and Linux, fail gracefully on macOS
            initializeJmtc()
            isActive = true
            log.info { "MediaSessionManager activated" }
        } catch (e: Exception) {
            // JMTC may not be available on macOS or if library is missing
            log.debug { "JMTC not available: ${e.message}" }
            isActive = true  // Still mark as active for state tracking
        }
    }
    
    actual fun deactivate() {
        if (!isActive) return
        
        try {
            disposeJmtc()
        } catch (e: Exception) {
            log.debug { "Error disposing JMTC: ${e.message}" }
        }
        isActive = false
        log.info { "MediaSessionManager deactivated" }
    }
    
    actual fun updatePlaybackState(isPlaying: Boolean) {
        // Update JMTC state if available
        // This will be implemented when we add the JMTC dependency
    }
    
    actual fun setActionHandler(handler: MediaSessionActionHandler) {
        this.handler = handler
    }
    
    private fun initializeJmtc() {
        // TODO: Initialize JMTC when dependency is added
        // Example:
        // val settings = JMTCSettings("Orpheus", "orpheus-synthesizer")
        // jmtcInstance = JMTC.getInstance(settings)
        // val callbacks = JMTCCallbacks().apply {
        //     onPlay = { handler?.onPlay() }
        //     onPause = { handler?.onPause() }
        //     onStop = { handler?.onStop() }
        // }
        // jmtcInstance.setCallbacks(callbacks)
        // jmtcInstance.setEnabled(true)
    }
    
    private fun disposeJmtc() {
        // TODO: Dispose JMTC when dependency is added
        // jmtcInstance?.setEnabled(false)
        jmtcInstance = null
    }
}
