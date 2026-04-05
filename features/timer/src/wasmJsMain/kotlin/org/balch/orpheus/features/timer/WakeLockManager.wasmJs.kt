package org.balch.orpheus.features.timer

actual class WakeLockManager actual constructor() {
    actual fun acquire() { /* no-op */ }
    actual fun release() { /* no-op */ }
}
