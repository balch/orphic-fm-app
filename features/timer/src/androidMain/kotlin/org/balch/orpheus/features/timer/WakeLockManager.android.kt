package org.balch.orpheus.features.timer

import android.content.Context
import android.os.PowerManager
import com.diamondedge.logging.logging

actual class WakeLockManager actual constructor() {
    private val log = logging("WakeLockManager")
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Initialize with Android context. Must be called before acquire().
     * Typically called from the app module after DI setup.
     */
    fun init(context: Context) {
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "orpheus:sleep_timer"
        ).apply {
            setReferenceCounted(false)
        }
    }

    actual fun acquire() {
        val wl = wakeLock
        if (wl == null) {
            log.warn { "WakeLockManager.acquire() called before init(context)" }
            return
        }
        if (wl.isHeld) return
        // 5-hour max timeout as safety guard
        wl.acquire(5 * 60 * 60 * 1000L)
        log.debug { "WakeLock acquired" }
    }

    actual fun release() {
        val wl = wakeLock ?: return
        if (!wl.isHeld) return
        wl.release()
        log.debug { "WakeLock released" }
    }
}
