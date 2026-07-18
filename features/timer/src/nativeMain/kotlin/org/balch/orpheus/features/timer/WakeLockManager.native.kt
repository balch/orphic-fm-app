package org.balch.orpheus.features.timer

actual class WakeLockManager actual constructor() {
    actual fun acquire() {
        // No-op on iOS/native. There's no per-request idle-timer API like Android's
        // PowerManager.WakeLock — UIApplication.idleTimerDisabled is a single global
        // flag. djapp's iOS entry (main.ios.kt) sets it directly off
        // PlaybackController.state, mirroring Android's FLAG_KEEP_SCREEN_ON gating;
        // the Orpheus app's iOS entry does not yet (its DI graph doesn't expose
        // playbackController on iOS). Either way, this timer-specific acquire()/
        // release() pair stays a no-op here.
    }

    actual fun release() {
        // No-op on native
    }
}
