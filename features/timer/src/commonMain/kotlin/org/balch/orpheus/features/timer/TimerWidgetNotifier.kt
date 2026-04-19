package org.balch.orpheus.features.timer

import kotlin.time.Duration

/**
 * Pushes timer state changes to the Android home screen widget.
 * No-op on non-Android platforms.
 */
interface TimerWidgetNotifier {
    fun notifyStateChanged(remainingTime: Duration, running: Boolean, statusText: String)
}

/** Default no-op — used directly in tests and bound via DI on JVM/WASM. */
class NoOpTimerWidgetNotifier : TimerWidgetNotifier {
    override fun notifyStateChanged(remainingTime: Duration, running: Boolean, statusText: String) {
        // no-op
    }
}
