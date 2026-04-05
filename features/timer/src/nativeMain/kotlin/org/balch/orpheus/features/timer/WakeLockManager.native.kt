package org.balch.orpheus.features.timer

actual class WakeLockManager actual constructor() {
    actual fun acquire() {
        // iOS manages idle timer via UIApplication.idleTimerDisabled (set at app level)
    }

    actual fun release() {
        // No-op on native
    }
}
