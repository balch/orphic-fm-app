package org.balch.orpheus.features.timer

/**
 * Pushes timer state changes to the Android home screen widget.
 * No-op on non-Android platforms.
 */
interface TimerWidgetNotifier {
    fun notifyStateChanged(remainingSeconds: Long, running: Boolean, statusText: String)
}

/** Default no-op — used directly in tests and bound via DI on JVM/WASM. */
class NoOpTimerWidgetNotifier : TimerWidgetNotifier {
    override fun notifyStateChanged(remainingSeconds: Long, running: Boolean, statusText: String) {
        // no-op
    }
}
