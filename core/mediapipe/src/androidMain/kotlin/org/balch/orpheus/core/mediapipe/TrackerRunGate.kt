package org.balch.orpheus.core.mediapipe

/**
 * Decides whether the tracker's camera and recognizer should run: only while the caller wants
 * tracking AND the app is in the foreground. Backgrounding never clears what the caller asked for.
 */
internal class TrackerRunGate {
    private var wanted = false
    private var foreground = false

    /** The caller's start() or stop(). Returns whether the tracker should now be running. */
    fun onWanted(wanted: Boolean): Boolean {
        this.wanted = wanted
        return wanted && foreground
    }

    /** The app entering or leaving the foreground. Returns whether the tracker should now be running. */
    fun onForeground(foreground: Boolean): Boolean {
        this.foreground = foreground
        return wanted && foreground
    }
}
